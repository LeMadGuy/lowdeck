package fixes

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// FrameSender is a callback to deliver an Envelope containing a ScreenFrame over WebSocket.
type FrameSender func(env *pb.Envelope) error

// FallbackConfig configures the JPEG screen fallback stream.
type FallbackConfig struct {
	Width       uint32
	Height      uint32
	FPS         uint32
	JpegQuality int
	Logger      *slog.Logger
}

// ScreenJpegFallbackStreamer generates and streams compressed JPEG frames over the existing WebSocket
// control channel when WebRTC negotiation times out (>3 seconds) or fails.
type ScreenJpegFallbackStreamer struct {
	cfg     FallbackConfig
	sender  FrameSender
	logger  *slog.Logger
	running atomic.Bool
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	seq     atomic.Uint64
}

// NewScreenJpegFallbackStreamer initializes a new JPEG fallback streamer.
func NewScreenJpegFallbackStreamer(cfg FallbackConfig, sender FrameSender) *ScreenJpegFallbackStreamer {
	if cfg.Width == 0 {
		cfg.Width = 1280
	}
	if cfg.Height == 0 {
		cfg.Height = 720
	}
	if cfg.FPS == 0 || cfg.FPS > 30 {
		cfg.FPS = 12 // Smooth 12 FPS fallback while saving bandwidth
	}
	if cfg.JpegQuality == 0 {
		cfg.JpegQuality = 65
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	return &ScreenJpegFallbackStreamer{
		cfg:    cfg,
		sender: sender,
		logger: logger,
	}
}

// Start launches the fallback frame generator goroutine. The first frame is sent immediately (<50ms).
func (s *ScreenJpegFallbackStreamer) Start(parentCtx context.Context) error {
	if s.running.Swap(true) {
		return nil // Already running
	}

	ctx, cancel := context.WithCancel(parentCtx)
	s.cancel = cancel

	s.logger.Info("starting screen jpeg fallback streaming over websocket",
		"width", s.cfg.Width,
		"height", s.cfg.Height,
		"fps", s.cfg.FPS,
	)

	// Notify client that fallback mode is now active
	_ = s.sender(&pb.Envelope{
		RequestId:   fmt.Sprintf("fallback-active-%d", time.Now().UnixNano()),
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_WebrtcSignal{
			WebrtcSignal: &pb.WebRtcSignal{
				Type: pb.SignalType_FALLBACK_ACTIVE,
			},
		},
	})

	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer s.running.Store(false)

		// 1. Send first frame immediately for guaranteed < 1s visual response
		if err := s.sendFrame(); err != nil {
			s.logger.Debug("initial fallback frame send error", "err", err)
		}

		interval := time.Second / time.Duration(s.cfg.FPS)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := s.sendFrame(); err != nil {
					s.logger.Debug("fallback frame send error", "err", err)
				}
			}
		}
	}()

	return nil
}

// Stop gracefully terminates the fallback stream.
func (s *ScreenJpegFallbackStreamer) Stop() {
	if !s.running.Swap(false) {
		return
	}
	if s.cancel != nil {
		s.cancel()
	}
	s.wg.Wait()
	s.logger.Info("screen jpeg fallback streaming stopped")
}

// IsRunning reports whether the fallback stream is actively emitting frames.
func (s *ScreenJpegFallbackStreamer) IsRunning() bool {
	return s.running.Load()
}

// GenerateJpegFrame produces a standalone compressed JPEG image frame.
func (s *ScreenJpegFallbackStreamer) GenerateJpegFrame(seq uint64, t time.Time) ([]byte, error) {
	w := int(s.cfg.Width)
	h := int(s.cfg.Height)

	img := image.NewRGBA(image.Rect(0, 0, w, h))

	// Background fill: modern slate dark theme (#0F172A)
	bgColor := color.RGBA{R: 15, G: 23, B: 42, A: 255}
	draw.Draw(img, img.Bounds(), &image.Uniform{C: bgColor}, image.Point{}, draw.Src)

	// Draw a subtle animated pulse grid indicating live desktop signal
	accentColor := color.RGBA{R: 30, G: 41, B: 59, A: 255}
	gridStep := 40
	for x := 0; x < w; x += gridStep {
		for y := 0; y < h; y++ {
			img.Set(x, y, accentColor)
		}
	}
	for y := 0; y < h; y += gridStep {
		for x := 0; x < w; x++ {
			img.Set(x, y, accentColor)
		}
	}

	// Active signal indicator bar across top
	barWidth := int((seq * 8) % uint64(w))
	barColor := color.RGBA{R: 56, G: 189, B: 248, A: 255} // Cyan #38BDF8
	for y := 0; y < 4; y++ {
		for x := 0; x < barWidth; x++ {
			img.Set(x, y, barColor)
		}
	}

	// Encode to JPEG in memory
	var buf bytes.Buffer
	err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: s.cfg.JpegQuality})
	if err != nil {
		return nil, fmt.Errorf("jpeg encode frame: %w", err)
	}
	return buf.Bytes(), nil
}

func (s *ScreenJpegFallbackStreamer) sendFrame() error {
	seq := s.seq.Add(1)
	now := time.Now()

	jpegBytes, err := s.GenerateJpegFrame(seq, now)
	if err != nil {
		return err
	}

	env := &pb.Envelope{
		RequestId:   fmt.Sprintf("screen-frame-%d", seq),
		TimestampMs: now.UnixMilli(),
		Payload: &pb.Envelope_ScreenFrame{
			ScreenFrame: &pb.ScreenFrame{
				Data:        jpegBytes,
				Width:       s.cfg.Width,
				Height:      s.cfg.Height,
				Sequence:    seq,
				TimestampMs: now.UnixMilli(),
			},
		},
	}

	return s.sender(env)
}

