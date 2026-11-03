package pty

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/creack/pty"
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	// DefaultMaxSessionsPerClient limits simultaneous PTY tabs per connected client.
	DefaultMaxSessionsPerClient = 4
	// DefaultCols is the fallback terminal width in columns.
	DefaultCols = 80
	// DefaultRows is the fallback terminal height in rows.
	DefaultRows = 24
	// TermEnvValue is the required TERM environment variable for PTY child processes.
	TermEnvValue = "xterm-256color"
)

var (
	// ErrMaxSessionsReached is returned when a client exceeds DefaultMaxSessionsPerClient.
	ErrMaxSessionsReached = errors.New("pty manager: maximum concurrent PTY sessions per client reached")
	// ErrSessionNotFound is returned when operating on a non-existent PTY session_id.
	ErrSessionNotFound = errors.New("pty manager: session not found")
)

type ptySession struct {
	sessionID string
	clientID  string
	client    server.ClientSession
	cmd       *exec.Cmd
	ptmx      *os.File
	fd        int
	closeOnce sync.Once
	doneCh    chan struct{}
	mu        sync.Mutex
	rows      uint16
	cols      uint16
}

// Manager manages interactive PTY shell sessions across connected clients.
type Manager struct {
	mu                   sync.RWMutex
	sessions             map[string]*ptySession // keyed by sessionID
	maxSessionsPerClient int
	logger               *slog.Logger
	wg                   sync.WaitGroup
}

// NewManager creates a new PTY session manager.
func NewManager(maxSessionsPerClient int, logger *slog.Logger) *Manager {
	if maxSessionsPerClient <= 0 {
		maxSessionsPerClient = DefaultMaxSessionsPerClient
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &Manager{
		sessions:             make(map[string]*ptySession),
		maxSessionsPerClient: maxSessionsPerClient,
		logger:               logger,
	}
}

// Register binds PtyOpen, PtyData, PtyResize, and PtyClose handlers on router.
func (m *Manager) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindPtyOpen, m.HandleOpen)
	router.Register(server.KindPtyData, m.HandleData)
	router.Register(server.KindPtyResize, m.HandleResize)
	router.Register(server.KindPtyClose, m.HandleClose)
}

// HandleOpen starts a new interactive PTY session for the client.
func (m *Manager) HandleOpen(ctx context.Context, client server.ClientSession, env *pb.Envelope) error {
	req := env.GetPtyOpenRequest()
	if req == nil {
		return errors.New("pty manager: nil PtyOpenRequest")
	}

	sessionID := strings.TrimSpace(req.SessionId)
	if sessionID == "" {
		return errors.New("pty manager: session_id is required")
	}

	clientID := "local"
	if client != nil {
		clientID = client.ID()
	}

	cols := uint16(req.Cols)
	if cols == 0 {
		cols = DefaultCols
	}
	rows := uint16(req.Rows)
	if rows == 0 {
		rows = DefaultRows
	}

	shell := resolveShell(req.Shell)

	m.mu.Lock()
	if existing, ok := m.sessions[sessionID]; ok {
		m.mu.Unlock()
		_ = m.closeSession(existing, "reopened")
		m.mu.Lock()
	}

	clientCount := 0
	for _, s := range m.sessions {
		if s.clientID == clientID {
			clientCount++
		}
	}
	if clientCount >= m.maxSessionsPerClient {
		m.mu.Unlock()
		return fmt.Errorf("%w (max %d)", ErrMaxSessionsReached, m.maxSessionsPerClient)
	}

	cmd := exec.Command(shell)
	cmd.SysProcAttr = &syscall.SysProcAttr{
		Setsid: true,
	}

	// Build environment with TERM=xterm-256color guaranteed.
	envList := make([]string, 0, len(os.Environ())+len(req.Env)+1)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "TERM=") {
			envList = append(envList, kv)
		}
	}
	for k, v := range req.Env {
		if !strings.EqualFold(k, "TERM") {
			envList = append(envList, fmt.Sprintf("%s=%s", k, v))
		}
	}
	envList = append(envList, "TERM="+TermEnvValue)
	cmd.Env = envList

	winSize := &pty.Winsize{
		Rows: rows,
		Cols: cols,
	}
	ptmx, err := pty.StartWithSize(cmd, winSize)
	if err != nil {
		m.mu.Unlock()
		return fmt.Errorf("pty start %s: %w", shell, err)
	}

	// Set master PTY to non-blocking mode (O_NONBLOCK) so readLoop and writes
	// never block indefinitely or freeze goroutines on congested I/O.
	ptmxFd := int(ptmx.Fd())
	if err := syscall.SetNonblock(ptmxFd, true); err != nil {
		m.logger.Warn("failed to set ptmx non-blocking", "err", err)
	}

	sess := &ptySession{
		sessionID: sessionID,
		clientID:  clientID,
		client:    client,
		cmd:       cmd,
		ptmx:      ptmx,
		fd:        ptmxFd,
		doneCh:    make(chan struct{}),
		rows:      rows,
		cols:      cols,
	}
	m.sessions[sessionID] = sess
	m.mu.Unlock()

	m.logger.Info("pty session opened",
		"session_id", sessionID,
		"client_id", clientID,
		"shell", shell,
		"cols", cols,
		"rows", rows,
	)

	// Monitor client context cancellation.
	if client != nil && client.Context() != nil {
		go func() {
			select {
			case <-client.Context().Done():
				_ = m.CloseSession(sessionID, "client disconnected")
			case <-ctx.Done():
				_ = m.CloseSession(sessionID, "context cancelled")
			case <-sess.doneCh:
			}
		}()
	} else {
		go func() {
			select {
			case <-ctx.Done():
				_ = m.CloseSession(sessionID, "context cancelled")
			case <-sess.doneCh:
			}
		}()
	}

	// Start PTY stdout/stderr reader loop.
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		m.readLoop(sess)
	}()

	return nil
}

// HandleData writes raw bytes from the client into the PTY master file descriptor.
func (m *Manager) HandleData(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetPtyDataEvent()
	if ev == nil {
		return errors.New("pty manager: nil PtyDataEvent")
	}
	if len(ev.Data) == 0 {
		return nil
	}

	sess, ok := m.getSession(ev.SessionId)
	if !ok {
		return fmt.Errorf("%w: %s", ErrSessionNotFound, ev.SessionId)
	}

	if _, err := sess.ptmx.Write(ev.Data); err != nil {
		return fmt.Errorf("pty write session %s: %w", ev.SessionId, err)
	}
	return nil
}

// HandleResize updates the terminal window dimensions (rows, cols) via pty.Setsize.
func (m *Manager) HandleResize(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetPtyResizeEvent()
	if ev == nil {
		return errors.New("pty manager: nil PtyResizeEvent")
	}

	sess, ok := m.getSession(ev.SessionId)
	if !ok {
		return fmt.Errorf("%w: %s", ErrSessionNotFound, ev.SessionId)
	}

	cols := uint16(ev.Cols)
	rows := uint16(ev.Rows)
	if cols == 0 || rows == 0 {
		return errors.New("pty resize: rows and cols must be > 0")
	}

	sess.mu.Lock()
	defer sess.mu.Unlock()

	if err := pty.Setsize(sess.ptmx, &pty.Winsize{
		Rows: rows,
		Cols: cols,
	}); err != nil {
		return fmt.Errorf("pty setsize session %s: %w", ev.SessionId, err)
	}
	sess.rows = rows
	sess.cols = cols
	return nil
}

// HandleClose closes a PTY session upon receiving PtyCloseEvent.
func (m *Manager) HandleClose(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetPtyCloseEvent()
	if ev == nil {
		return errors.New("pty manager: nil PtyCloseEvent")
	}
	reason := ev.Reason
	if reason == "" {
		reason = "closed by client"
	}
	return m.CloseSession(ev.SessionId, reason)
}

// WindowSize returns the current (rows, cols) of an active PTY session using pty.GetsizeFull.
func (m *Manager) WindowSize(sessionID string) (rows, cols int, err error) {
	sess, ok := m.getSession(sessionID)
	if !ok {
		return 0, 0, fmt.Errorf("%w: %s", ErrSessionNotFound, sessionID)
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	r, c, err := pty.Getsize(sess.ptmx)
	if err != nil {
		return int(sess.rows), int(sess.cols), err
	}
	return r, c, nil
}

// ActiveCount returns the total number of open PTY sessions.
func (m *Manager) ActiveCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.sessions)
}

// CloseSession terminates a specific PTY session and reaps its child process.
func (m *Manager) CloseSession(sessionID string, reason string) error {
	m.mu.Lock()
	sess, ok := m.sessions[sessionID]
	if ok {
		delete(m.sessions, sessionID)
	}
	m.mu.Unlock()

	if !ok {
		return nil
	}
	return m.closeSession(sess, reason)
}

// CloseClientSessions closes all PTY sessions belonging to a disconnected client.
func (m *Manager) CloseClientSessions(clientID string) {
	m.mu.Lock()
	var list []*ptySession
	for id, sess := range m.sessions {
		if sess.clientID == clientID {
			list = append(list, sess)
			delete(m.sessions, id)
		}
	}
	m.mu.Unlock()

	for _, sess := range list {
		_ = m.closeSession(sess, "client disconnected")
	}
}

// CloseAll terminates all active PTY sessions and waits for reader goroutines to exit.
func (m *Manager) CloseAll() {
	m.mu.Lock()
	list := make([]*ptySession, 0, len(m.sessions))
	for _, sess := range m.sessions {
		list = append(list, sess)
	}
	m.sessions = make(map[string]*ptySession)
	m.mu.Unlock()

	for _, sess := range list {
		_ = m.closeSession(sess, "daemon shutdown")
	}
	m.wg.Wait()
}

func (m *Manager) getSession(sessionID string) (*ptySession, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.sessions[sessionID]
	return s, ok
}

func (m *Manager) readLoop(sess *ptySession) {
	buf := make([]byte, 8192)
	pending := make([]byte, 0, 16384)
	flushTimer := time.NewTimer(time.Hour)
	flushTimer.Stop()
	flushTimerActive := false

	flush := func() {
		if len(pending) == 0 || sess.client == nil {
			return
		}
		chunk := make([]byte, len(pending))
		copy(chunk, pending)
		pending = pending[:0]
		_ = sess.client.Send(&pb.Envelope{
			RequestId:   fmt.Sprintf("pty-data-%d", time.Now().UnixNano()),
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_PtyDataEvent{
				PtyDataEvent: &pb.PtyDataEvent{
					SessionId: sess.sessionID,
					Data:      chunk,
				},
			},
		})
	}

	defer func() {
		flushTimer.Stop()
		flush()
		_ = m.CloseSession(sess.sessionID, "process exited")
	}()

	fd := sess.fd
	for {
		// Non-blocking read directly via syscall
		n, err := syscall.Read(fd, buf)
		if n > 0 {
			pending = append(pending, buf[:n]...)

			// If buffer has grown large (>= 4KB), flush immediately without waiting for timer
			if len(pending) >= 4096 {
				if flushTimerActive {
					flushTimer.Stop()
					flushTimerActive = false
				}
				flush()
			} else if !flushTimerActive {
				// Coalesce small fragmented byte bursts with a short 8ms-12ms window
				flushTimer.Reset(10 * time.Millisecond)
				flushTimerActive = true
			}
			continue
		}

		if err != nil {
			if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) {
				// Check if the timer fired while waiting
				if flushTimerActive {
					select {
					case <-flushTimer.C:
						flushTimerActive = false
						flush()
					default:
					}
				}

				// Check if session has been closed
				select {
				case <-sess.doneCh:
					return
				default:
				}

				// Wait for data readiness using syscall.Select with a brief timeout (8ms or 4ms)
				timeoutUsec := int64(8000)
				if flushTimerActive {
					timeoutUsec = 4000
				}
				tv := syscall.NsecToTimeval(timeoutUsec * 1000)
				var rset syscall.FdSet
				// Bits in FdSet
				rset.Bits[fd/64] |= 1 << (uint(fd) % 64)
				_, selErr := syscall.Select(fd+1, &rset, nil, nil, &tv)
				if selErr != nil && !errors.Is(selErr, syscall.EINTR) {
					break
				}
				continue
			}
			// EOF (EIO on Linux PTY slave close) or fatal read error
			break
		}

		if n == 0 {
			// Zero bytes read from PTY indicates EOF
			break
		}
	}
}

func (m *Manager) closeSession(sess *ptySession, reason string) error {
	var closeErr error
	sess.closeOnce.Do(func() {
		close(sess.doneCh)

		// Close PTY master FD first so readLoop unblocks.
		if sess.ptmx != nil {
			_ = sess.ptmx.Close()
		}

		exitCode := int32(0)
		if sess.cmd != nil && sess.cmd.Process != nil {
			pid := sess.cmd.Process.Pid
			// Send SIGHUP/SIGKILL to the session leader process group.
			_ = syscall.Kill(-pid, syscall.SIGHUP)
			doneWait := make(chan error, 1)
			go func() {
				doneWait <- sess.cmd.Wait()
			}()

			select {
			case waitErr := <-doneWait:
				if waitErr != nil {
					var exitErr *exec.ExitError
					if errors.As(waitErr, &exitErr) {
						exitCode = int32(exitErr.ExitCode())
					} else {
						exitCode = -1
					}
				}
			case <-time.After(300 * time.Millisecond):
				_ = syscall.Kill(-pid, syscall.SIGKILL)
				_ = sess.cmd.Process.Kill()
				<-doneWait
				exitCode = -1
			}
		}

		if sess.client != nil {
			_ = sess.client.Send(&pb.Envelope{
				RequestId:   fmt.Sprintf("pty-close-%d", time.Now().UnixNano()),
				TimestampMs: time.Now().UnixMilli(),
				Payload: &pb.Envelope_PtyCloseEvent{
					PtyCloseEvent: &pb.PtyCloseEvent{
						SessionId: sess.sessionID,
						ExitCode:  exitCode,
						Reason:    reason,
					},
				},
			})
		}

		m.logger.Info("pty session closed",
			"session_id", sess.sessionID,
			"reason", reason,
			"exit_code", exitCode,
		)
	})
	return closeErr
}

func resolveShell(requested string) string {
	if s := strings.TrimSpace(requested); s != "" {
		return s
	}
	if envShell := strings.TrimSpace(os.Getenv("SHELL")); envShell != "" {
		if _, err := os.Stat(envShell); err == nil {
			return envShell
		}
	}
	if _, err := os.Stat("/bin/bash"); err == nil {
		return "/bin/bash"
	}
	return "/bin/sh"
}

