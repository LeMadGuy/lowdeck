package fixes

import (
	"bytes"
	"context"
	"image/jpeg"
	"sync/atomic"
	"testing"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	pionwebrtc "github.com/pion/webrtc/v4"
)

func TestConfigureLocalWebRTC(t *testing.T) {
	cfg, _ := ConfigureLocalWebRTC()
	if len(cfg.ICEServers) != 0 {
		t.Fatalf("expected 0 ICEServers for offline LAN/USB operation, got %d", len(cfg.ICEServers))
	}
}

func TestWebRTCHandshakeWatchdog_TimeoutTriggersFallback(t *testing.T) {
	var timeoutFired atomic.Bool
	var connectedFired atomic.Bool

	watchdog := NewWebRTCHandshakeWatchdog(
		50*time.Millisecond,
		nil,
		func() { timeoutFired.Store(true) },
		func() { connectedFired.Store(true) },
	)

	watchdog.Start(context.Background())

	time.Sleep(100 * time.Millisecond)

	if !timeoutFired.Load() {
		t.Fatal("expected watchdog timeout to trigger fallback within deadline")
	}
	if connectedFired.Load() {
		t.Fatal("connected callback should not have fired on timeout")
	}
	if !watchdog.HasTimedOut() {
		t.Fatal("watchdog.HasTimedOut() should be true")
	}
}

func TestWebRTCHandshakeWatchdog_ConnectedCancelsTimeout(t *testing.T) {
	var timeoutFired atomic.Bool
	var connectedFired atomic.Bool

	watchdog := NewWebRTCHandshakeWatchdog(
		100*time.Millisecond,
		nil,
		func() { timeoutFired.Store(true) },
		func() { connectedFired.Store(true) },
	)

	watchdog.Start(context.Background())
	watchdog.NotifyICEState(pionwebrtc.ICEConnectionStateConnected)

	time.Sleep(150 * time.Millisecond)

	if timeoutFired.Load() {
		t.Fatal("watchdog timeout should not fire after successful connection")
	}
	if !connectedFired.Load() {
		t.Fatal("expected connected callback to fire")
	}
	if !watchdog.IsConnected() {
		t.Fatal("watchdog.IsConnected() should be true")
	}
}

func TestScreenJpegFallbackStreamer_GeneratesValidJpegAndStreams(t *testing.T) {
	var frameCount atomic.Int32
	var hasActiveSignal atomic.Bool
	var lastFrameData []byte

	sender := func(env *pb.Envelope) error {
		if sig := env.GetWebrtcSignal(); sig != nil && sig.Type == pb.SignalType_FALLBACK_ACTIVE {
			hasActiveSignal.Store(true)
		}
		if sf := env.GetScreenFrame(); sf != nil {
			frameCount.Add(1)
			lastFrameData = sf.Data
		}
		return nil
	}

	streamer := NewScreenJpegFallbackStreamer(FallbackConfig{
		Width:       640,
		Height:      360,
		FPS:         15,
		JpegQuality: 60,
	}, sender)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := streamer.Start(ctx); err != nil {
		t.Fatalf("streamer start error: %v", err)
	}

	if !streamer.IsRunning() {
		t.Fatal("streamer should be running")
	}

	// Give enough time for immediate frame + a couple of ticker frames
	time.Sleep(120 * time.Millisecond)
	streamer.Stop()

	if streamer.IsRunning() {
		t.Fatal("streamer should have stopped")
	}

	if !hasActiveSignal.Load() {
		t.Fatal("expected FALLBACK_ACTIVE signal to be sent")
	}

	if frameCount.Load() == 0 {
		t.Fatal("expected at least 1 frame to be sent immediately")
	}

	// Validate JPEG header and decoding
	if len(lastFrameData) < 4 {
		t.Fatalf("frame data too short: %d bytes", len(lastFrameData))
	}
	// JPEG magic: 0xFF, 0xD8
	if lastFrameData[0] != 0xFF || lastFrameData[1] != 0xD8 {
		t.Fatalf("invalid JPEG SOI header: %x %x", lastFrameData[0], lastFrameData[1])
	}

	img, err := jpeg.Decode(bytes.NewReader(lastFrameData))
	if err != nil {
		t.Fatalf("failed to decode emitted JPEG frame: %v", err)
	}
	if img.Bounds().Dx() != 640 || img.Bounds().Dy() != 360 {
		t.Fatalf("unexpected image dimensions: got %dx%d, want 640x360", img.Bounds().Dx(), img.Bounds().Dy())
	}
}

