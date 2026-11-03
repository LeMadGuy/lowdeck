package capture

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	DefaultMjpegPort = 8444
	DefaultMjpegPath = "/screen.mjpg"
)

// MjpegServer serves a continuous HTTP multipart MJPEG stream (/screen.mjpg).
type MjpegServer struct {
	addr       string
	mux        *http.ServeMux
	server     *http.Server
	listener   net.Listener
	logger     *slog.Logger
	running    atomic.Bool
	activeConn atomic.Int32
	mu         sync.Mutex
}

// NewMjpegServer constructs an MjpegServer listening on addr (e.g. "0.0.0.0:8444" or ":8444").
func NewMjpegServer(addr string, logger *slog.Logger) *MjpegServer {
	if addr == "" {
		addr = fmt.Sprintf("0.0.0.0:%d", DefaultMjpegPort)
	}
	if logger == nil {
		logger = slog.Default()
	}

	mux := http.NewServeMux()

	s := &MjpegServer{
		addr:   addr,
		mux:    mux,
		logger: logger,
	}

	mux.HandleFunc(DefaultMjpegPath, s.handleScreenMjpeg)
	mux.HandleFunc("/screen.mjpeg", s.handleScreenMjpeg)
	mux.HandleFunc("/live.mjpg", s.handleScreenMjpeg)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK\n"))
	})

	s.server = &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	return s
}

// HandleFunc registers an HTTP handler function on the server mux before starting.
func (s *MjpegServer) HandleFunc(pattern string, handler func(http.ResponseWriter, *http.Request)) {
	s.mux.HandleFunc(pattern, handler)
}

// Start binds the socket and starts the HTTP server in a background goroutine.
func (s *MjpegServer) Start(ctx context.Context) (net.Addr, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running.Load() {
		if s.listener != nil {
			return s.listener.Addr(), nil
		}
		return nil, errors.New("mjpeg server already running")
	}

	ln, err := net.Listen("tcp", s.addr)
	if err != nil {
		return nil, fmt.Errorf("mjpeg server listen %s: %w", s.addr, err)
	}
	s.listener = ln
	s.running.Store(true)

	s.logger.Info("continuous mjpeg screen stream server listening", "addr", ln.Addr().String(), "path", DefaultMjpegPath)

	go func() {
		if err := s.server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			s.logger.Error("mjpeg server serve error", "err", err)
		}
		s.running.Store(false)
	}()

	// Monitor context cancellation to automatically shutdown
	go func() {
		<-ctx.Done()
		_ = s.Shutdown(context.Background())
	}()

	return ln.Addr(), nil
}

// Shutdown gracefully terminates the HTTP server and stops all active streaming connections.
func (s *MjpegServer) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running.Swap(false) {
		return nil
	}
	if s.server != nil {
		return s.server.Shutdown(ctx)
	}
	return nil
}

// IsRunning reports whether the server is actively listening.
func (s *MjpegServer) IsRunning() bool {
	return s.running.Load()
}

// ActiveConnections returns the number of active streaming client connections.
func (s *MjpegServer) ActiveConnections() int {
	return int(s.activeConn.Load())
}

// ServeHTTP implements http.Handler for direct mounting onto other routers or tests.
func (s *MjpegServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.handleScreenMjpeg(w, r)
}

func (s *MjpegServer) handleScreenMjpeg(w http.ResponseWriter, r *http.Request) {
	s.activeConn.Add(1)
	defer s.activeConn.Add(-1)

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	// 1. Unbuffered HTTP multipart streaming headers
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Connection", "close")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	s.logger.Info("client connected to mjpeg screen stream",
		"remote_addr", r.RemoteAddr,
		"user_agent", r.UserAgent(),
	)

	// 2. Auto-detection of capture command (runs only 1 process while client is connected)
	if os.Getenv(EnvTestSource) == "1" {
		s.logger.Info("DECK_CAPTURE_TEST_SOURCE=1 active; using universal synthetic fallback directly")
		s.streamUniversalFallback(w, r, flusher)
		return
	}

	// Check if we can run Option 1: Wayland / PipeWire com GStreamer
	if _, err := exec.LookPath("gst-launch-1.0"); err == nil && os.Getenv("WAYLAND_DISPLAY") != "" {
		s.logger.Info("attempting Option 1: PipeWire + GStreamer MJPEG pipeline")
		cmd := exec.CommandContext(r.Context(), "gst-launch-1.0", "-q",
			"pipewiresrc", "do-timestamp=true",
			"!", "videoconvert",
			"!", "videoscale",
			"!", "videorate",
			"!", "image/jpeg,width=1280,height=720,framerate=25/1,quality=70",
			"!", "multipartmux", "boundary=frame",
			"!", "fdsink", "fd=1",
		)
		if s.tryRunProcess(r.Context(), cmd, w, flusher) {
			return
		}
	}

	// Check if we can run Option 2: X11 / XWayland com FFmpeg
	display := os.Getenv("DISPLAY")
	if display == "" {
		display = ":0"
	}
	if _, err := exec.LookPath("ffmpeg"); err == nil {
		s.logger.Info("attempting Option 2: X11/XWayland + FFmpeg MJPEG pipeline", "display", display)
		cmd := exec.CommandContext(r.Context(), "ffmpeg",
			"-loglevel", "quiet",
			"-f", "x11grab",
			"-framerate", "25",
			"-i", display,
			"-vf", "scale=1280:-1",
			"-q:v", "7",
			"-f", "mpjpeg",
			"-boundary_tag", "frame",
			"-",
		)
		if s.tryRunProcess(r.Context(), cmd, w, flusher) {
			return
		}
	}

	// Check if we can run Option 3: wf-recorder + ffmpeg pipe
	if _, errWf := exec.LookPath("wf-recorder"); errWf == nil {
		if _, errFf := exec.LookPath("ffmpeg"); errFf == nil && os.Getenv("WAYLAND_DISPLAY") != "" {
			s.logger.Info("attempting Option 3: wf-recorder + FFmpeg pipe")
			cmd := exec.CommandContext(r.Context(), "bash", "-c",
				"wf-recorder -c rawvideo -m avi -f pipe:1 | ffmpeg -loglevel quiet -i pipe:0 -vf scale=1280:-1 -q:v 7 -f mpjpeg -boundary_tag frame -",
			)
			if s.tryRunProcess(r.Context(), cmd, w, flusher) {
				return
			}
		}
	}

	// Option 4: Universal Fallback (grim or pure Go synthetic generator)
	s.logger.Info("pipelines exited or unavailable; running Option 4: Universal Fallback loop (15 FPS)")
	s.streamUniversalFallback(w, r, flusher)
}

func (s *MjpegServer) tryRunProcess(ctx context.Context, cmd *exec.Cmd, w http.ResponseWriter, flusher http.Flusher) bool {
	// Set process group so killing cmd kills any child subprocesses
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return false
	}

	if err := cmd.Start(); err != nil {
		s.logger.Debug("failed to start capture command", "cmd", cmd.Path, "err", err)
		return false
	}

	killed := false
	killProc := func() {
		if !killed && cmd.Process != nil {
			killed = true
			// Kill the process group to terminate pipe children cleanly
			_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
			_ = cmd.Process.Kill()
		}
	}
	defer func() {
		killProc()
		_ = cmd.Wait()
	}()

	// Wait up to 500ms for first data or process exit
	firstBuf := make([]byte, 8192)
	type readRes struct {
		n   int
		err error
	}
	firstCh := make(chan readRes, 1)
	go func() {
		n, rErr := stdout.Read(firstBuf)
		firstCh <- readRes{n: n, err: rErr}
	}()

	select {
	case <-time.After(500 * time.Millisecond):
		s.logger.Debug("capture candidate timed out after 500ms without data", "cmd", cmd.Path)
		return false

	case res := <-firstCh:
		if res.err != nil || res.n == 0 {
			s.logger.Debug("capture candidate failed on initial read", "cmd", cmd.Path, "err", res.err)
			return false
		}
		// First chunk read successfully; write to response
		if _, wErr := w.Write(firstBuf[:res.n]); wErr != nil {
			return true
		}
		flusher.Flush()

	case <-ctx.Done():
		return true
	}

	// Continue piping stream until client disconnects or process finishes
	buf := make([]byte, 32*1024)
	for {
		select {
		case <-ctx.Done():
			return true
		default:
		}

		n, rErr := stdout.Read(buf)
		if n > 0 {
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				return true
			}
			flusher.Flush()
		}
		if rErr != nil {
			return true
		}
	}
}

func (s *MjpegServer) streamUniversalFallback(w http.ResponseWriter, r *http.Request, flusher http.Flusher) {
	ticker := time.NewTicker(66 * time.Millisecond) // ~15 FPS
	defer ticker.Stop()

	hasGrim := false
	if _, err := exec.LookPath("grim"); err == nil && os.Getenv("WAYLAND_DISPLAY") != "" && os.Getenv(EnvTestSource) != "1" {
		hasGrim = true
	}

	var seq uint64
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
			seq++
			var jpegBytes []byte
			var err error

			if hasGrim {
				cmd := exec.CommandContext(r.Context(), "grim", "-t", "jpeg", "-q", "65", "-")
				jpegBytes, err = cmd.Output()
			}

			if len(jpegBytes) == 0 || err != nil {
				jpegBytes = GenerateSyntheticScreenFrame(seq, 1280, 720)
			}

			// Format standard multipart boundary delimiter and content headers
			header := fmt.Sprintf("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(jpegBytes))
			if _, err := io.WriteString(w, header); err != nil {
				return
			}
			if _, err := w.Write(jpegBytes); err != nil {
				return
			}
			if _, err := io.WriteString(w, "\r\n"); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

// GenerateSyntheticScreenFrame creates a standalone JPEG frame with desktop graphics and timestamp.
func GenerateSyntheticScreenFrame(seq uint64, width, height int) []byte {
	if width <= 0 {
		width = 1280
	}
	if height <= 0 {
		height = 720
	}

	img := image.NewRGBA(image.Rect(0, 0, width, height))

	// Slate dark background (#0F172A)
	bgColor := color.RGBA{R: 15, G: 23, B: 42, A: 255}
	draw.Draw(img, img.Bounds(), &image.Uniform{C: bgColor}, image.Point{}, draw.Src)

	// Animated grid lines
	gridColor := color.RGBA{R: 30, G: 41, B: 59, A: 255}
	step := 50
	for x := 0; x < width; x += step {
		for y := 0; y < height; y++ {
			img.Set(x, y, gridColor)
		}
	}
	for y := 0; y < height; y += step {
		for x := 0; x < width; x++ {
			img.Set(x, y, gridColor)
		}
	}

	// Moving scan line indicating live visual signal
	scanY := int((seq * 8) % uint64(height))
	scanColor := color.RGBA{R: 56, G: 189, B: 248, A: 255} // Cyan #38BDF8
	for dy := 0; dy < 3 && (scanY+dy) < height; dy++ {
		for x := 0; x < width; x++ {
			img.Set(x, scanY+dy, scanColor)
		}
	}

	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 65})
	return buf.Bytes()
}

