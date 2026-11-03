package fixes

import (
	"bytes"
	"context"
	"sync"
	"sync/atomic"
	"time"
)

// FIX-06: Terminal Remoto: Crashes Fatais, Travamentos da UI e Lentidão
//
// Causa Raiz Identificada:
// Explosão de recomposição na UI Thread do Android. Ao abrir o shell, o bash/terminfo emite sequências
// de escape ANSI em rajada. O app Android acumulava essas strings diretamente em estado observável da UI,
// provocando recomposição infinita, saturação de 100% da CPU na UI Thread e Watchdog Crash / ANR.
//
// Solução Implementada no Servidor:
// Controle de fluxo com handshake PtyReady e janela deslizante para que o PTY não inunde o cliente
// antes dele estar pronto.

const (
	// HandshakePtyReadyToken is the payload sent by the client when the terminal UI is mounted and ready.
	HandshakePtyReadyToken = "PTY_READY"

	// DefaultMaxWindowBytes is the maximum burst allowed in flight before throttling (64 KB).
	DefaultMaxWindowBytes = 64 * 1024
)

// ThrottledPtyController manages sliding-window flow control and client readiness for PTY output.
type ThrottledPtyController struct {
	readyOnce     sync.Once
	readyCh       chan struct{}
	isReady       atomic.Bool
	maxWindow     int64
	bytesInFlight atomic.Int64
}

// NewThrottledPtyController constructs a flow controller for a PTY session.
func NewThrottledPtyController(maxWindow int64) *ThrottledPtyController {
	if maxWindow <= 0 {
		maxWindow = DefaultMaxWindowBytes
	}
	return &ThrottledPtyController{
		readyCh:   make(chan struct{}),
		maxWindow: maxWindow,
	}
}

// IsPtyReadyPayload returns true if the incoming data payload represents the PtyReady handshake.
func IsPtyReadyPayload(data []byte) bool {
	return bytes.Equal(bytes.TrimSpace(data), []byte(HandshakePtyReadyToken))
}

// MarkReady marks the client as initialized and ready to receive terminal output.
func (c *ThrottledPtyController) MarkReady() {
	c.readyOnce.Do(func() {
		c.isReady.Store(true)
		close(c.readyCh)
	})
}

// IsReady returns true if the client has signaled readiness or if fallback timeout elapsed.
func (c *ThrottledPtyController) IsReady() bool {
	return c.isReady.Load()
}

// WaitReady blocks until the client sends PtyReady or until timeout expires (fallback).
func (c *ThrottledPtyController) WaitReady(ctx context.Context, timeout time.Duration) bool {
	if c.isReady.Load() {
		return true
	}
	if timeout <= 0 {
		timeout = 350 * time.Millisecond
	}

	select {
	case <-c.readyCh:
		return true
	case <-time.After(timeout):
		// Graceful timeout fallback: don't stall legacy clients indefinitely
		c.MarkReady()
		return false
	case <-ctx.Done():
		return false
	}
}

// RecordSent increases bytes currently in flight to the client.
func (c *ThrottledPtyController) RecordSent(n int) {
	c.bytesInFlight.Add(int64(n))
}

// AcknowledgeBytes reduces in-flight count when the client confirms receipt/rendering.
func (c *ThrottledPtyController) AcknowledgeBytes(n int) {
	newVal := c.bytesInFlight.Add(-int64(n))
	if newVal < 0 {
		c.bytesInFlight.Store(0)
	}
}

// ShouldThrottle returns true if in-flight bytes exceed the sliding window limit.
func (c *ThrottledPtyController) ShouldThrottle() bool {
	return c.bytesInFlight.Load() >= c.maxWindow
}

// ResetWindow clears the in-flight tracking (e.g. on terminal clear or screen reset).
func (c *ThrottledPtyController) ResetWindow() {
	c.bytesInFlight.Store(0)
}

