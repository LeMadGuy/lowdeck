package fixes

import (
	"context"
	"testing"
	"time"
)

func TestThrottledPtyController_PtyReadyHandshake(t *testing.T) {
	ctrl := NewThrottledPtyController(32 * 1024)

	if ctrl.IsReady() {
		t.Fatal("controller should not be ready initially")
	}

	// Verify handshake payload recognition
	if !IsPtyReadyPayload([]byte("PTY_READY")) {
		t.Error("expected true for PTY_READY")
	}
	if !IsPtyReadyPayload([]byte("  PTY_READY\n")) {
		t.Error("expected true for trimmed PTY_READY")
	}
	if IsPtyReadyPayload([]byte("ls -la")) {
		t.Error("expected false for shell command")
	}

	// Mark ready
	ctrl.MarkReady()
	if !ctrl.IsReady() {
		t.Fatal("controller should be ready after MarkReady")
	}

	// WaitReady should return immediately
	ctx := context.Background()
	if !ctrl.WaitReady(ctx, 100*time.Millisecond) {
		t.Error("WaitReady returned false after MarkReady")
	}
}

func TestThrottledPtyController_WaitReadyTimeoutFallback(t *testing.T) {
	ctrl := NewThrottledPtyController(16 * 1024)

	// In the absence of client signal, WaitReady falls back gracefully after timeout
	ctx := context.Background()
	ready := ctrl.WaitReady(ctx, 20*time.Millisecond)
	if ready {
		t.Error("expected false on timeout fallback")
	}
	if !ctrl.IsReady() {
		t.Error("controller should be marked ready after timeout fallback")
	}
}

func TestThrottledPtyController_SlidingWindowThrottling(t *testing.T) {
	window := int64(1024)
	ctrl := NewThrottledPtyController(window)

	ctrl.RecordSent(500)
	if ctrl.ShouldThrottle() {
		t.Error("should not throttle at 500/1024")
	}

	ctrl.RecordSent(600) // Total 1100 > 1024
	if !ctrl.ShouldThrottle() {
		t.Error("should throttle when exceeding window")
	}

	// Client acks 500 bytes -> 600 remaining <= 1024
	ctrl.AcknowledgeBytes(500)
	if ctrl.ShouldThrottle() {
		t.Error("should no longer throttle after ack")
	}

	// Reset window
	ctrl.ResetWindow()
	if ctrl.ShouldThrottle() {
		t.Error("should not throttle after reset")
	}
}

