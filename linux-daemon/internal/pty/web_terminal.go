package pty

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"time"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all local connections (USB, LAN, localhost)
	},
	ReadBufferSize:  8192,
	WriteBufferSize: 8192,
}

// WebTerminal provides HTTP and WebSocket handlers for the xterm.js terminal.
type WebTerminal struct {
	logger *slog.Logger
}

// NewWebTerminal creates a new WebTerminal server handler.
func NewWebTerminal(logger *slog.Logger) *WebTerminal {
	if logger == nil {
		logger = slog.Default()
	}
	return &WebTerminal{
		logger: logger,
	}
}

// TerminalHandler serves the embedded HTML/JS page containing xterm.js.
func (wt *WebTerminal) TerminalHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(TerminalHTML))
	}
}

// WsHandler handles pure binary WebSocket connections on /ws/pty.
func (wt *WebTerminal) WsHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ws, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			wt.logger.Warn("web terminal: failed to upgrade websocket", "err", err)
			return
		}
		defer ws.Close()

		shell := os.Getenv("SHELL")
		if shell == "" {
			shell = "/bin/bash"
			if _, err := os.Stat(shell); err != nil {
				shell = "/bin/sh"
			}
		}

		cmd := exec.Command(shell)
		cmd.Env = append(os.Environ(),
			"TERM=xterm-256color",
			"COLORTERM=truecolor",
		)

		initialWinsize := &pty.Winsize{
			Cols: DefaultCols,
			Rows: DefaultRows,
		}

		ptmx, err := pty.StartWithSize(cmd, initialWinsize)
		if err != nil {
			wt.logger.Error("web terminal: failed to start pty shell", "err", err, "shell", shell)
			return
		}

		wt.logger.Info("web terminal: started pty session", "pid", cmd.Process.Pid, "shell", shell)

		// Guaranteed cleanup on disconnect: close ptmx and kill shell process
		var closeOnce sync.Once
		cleanup := func() {
			closeOnce.Do(func() {
				_ = ptmx.Close()
				if cmd.Process != nil {
					_ = cmd.Process.Signal(syscall.SIGKILL)
					_ = cmd.Wait()
				}
				wt.logger.Info("web terminal: pty session cleanly terminated", "pid", cmd.Process.Pid)
			})
		}
		defer cleanup()

		ctx, cancel := context.WithCancel(r.Context())
		defer cancel()

		var wsMu sync.Mutex
		dataCh := make(chan []byte, 64)

		// Goroutine: Read raw bytes from PTY master
		go func() {
			defer close(dataCh)
			readBuf := make([]byte, 4096)
			for {
				n, readErr := ptmx.Read(readBuf)
				if readErr != nil {
					return
				}
				if n > 0 {
					chunk := make([]byte, n)
					copy(chunk, readBuf[:n])
					select {
					case dataCh <- chunk:
					case <-ctx.Done():
						return
					}
				}
			}
		}()

		// Goroutine: Aggregate and send binary frames (up to 12ms or 8192 bytes)
		go func() {
			ticker := time.NewTicker(12 * time.Millisecond)
			defer ticker.Stop()

			var batch []byte

			flush := func() error {
				if len(batch) == 0 {
					return nil
				}
				wsMu.Lock()
				defer wsMu.Unlock()
				writeErr := ws.WriteMessage(websocket.BinaryMessage, batch)
				batch = nil
				return writeErr
			}

			for {
				select {
				case chunk, ok := <-dataCh:
					if !ok {
						_ = flush()
						cancel()
						return
					}
					batch = append(batch, chunk...)
					if len(batch) >= 8192 {
						if err := flush(); err != nil {
							cancel()
							return
						}
					}
				case <-ticker.C:
					if err := flush(); err != nil {
						cancel()
						return
					}
				case <-ctx.Done():
					return
				}
			}
		}()

		// Loop: Read incoming client binary messages
		for {
			messageType, payload, msgErr := ws.ReadMessage()
			if msgErr != nil {
				if websocket.IsUnexpectedCloseError(msgErr, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
					wt.logger.Debug("web terminal: websocket closed", "err", msgErr)
				}
				break
			}

			if messageType != websocket.BinaryMessage {
				continue
			}

			if len(payload) == 0 {
				continue
			}

			op := payload[0]
			data := payload[1:]

			switch op {
			case 0x00: // OP_DATA: Write raw bytes to PTY stdin
				if len(data) > 0 {
					_, _ = ptmx.Write(data)
				}
			case 0x01: // OP_RESIZE: Resize JSON {"cols": 100, "rows": 30}
				var req struct {
					Cols uint16 `json:"cols"`
					Rows uint16 `json:"rows"`
				}
				if err := json.Unmarshal(data, &req); err == nil && req.Cols > 0 && req.Rows > 0 {
					_ = pty.Setsize(ptmx, &pty.Winsize{
						Cols: req.Cols,
						Rows: req.Rows,
					})
				}
			}
		}
	}
}

// RegisterRoutes binds the /terminal and /ws/pty routes to an http.ServeMux or similar interface.
func (wt *WebTerminal) RegisterRoutes(mux interface {
	HandleFunc(pattern string, handler func(http.ResponseWriter, *http.Request))
}) {
	if mux == nil {
		return
	}
	mux.HandleFunc("/terminal", wt.TerminalHandler())
	mux.HandleFunc("/ws/pty", wt.WsHandler())
}
