package fixes

import (
	"context"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/ice/v4"
	pionwebrtc "github.com/pion/webrtc/v4"
)

const (
	// DefaultWebRTCHandshakeTimeout is the strict 3-second limit for local ICE/SDP connection.
	DefaultWebRTCHandshakeTimeout = 3 * time.Second
)

// ConfigureLocalWebRTC returns a Pion WebRTC Configuration and SettingEngine tuned specifically
// for LAN, USB-tethering/adb-reverse, and local loopback operation without STUN servers.
func ConfigureLocalWebRTC() (pionwebrtc.Configuration, pionwebrtc.SettingEngine) {
	// Zero STUN/TURN servers: prevents DNS lookup delays of up to 30s in closed or offline networks.
	config := pionwebrtc.Configuration{
		ICEServers: []pionwebrtc.ICEServer{},
	}

	settingEngine := pionwebrtc.SettingEngine{}
	// Include 127.0.0.1 / ::1 host candidates for USB adb-reverse and loopback testing.
	settingEngine.SetIncludeLoopbackCandidate(true)
	// Disable multicast DNS to emit direct IPv4/IPv6 host candidates immediately.
	settingEngine.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	// Rapid ICE timeouts so dead connections trigger fallback without freezing.
	settingEngine.SetICETimeouts(2*time.Second, 4*time.Second, 1*time.Second)

	return config, settingEngine
}

// WebRTCHandshakeWatchdog monitors the ICE connection state of a WebRTC session.
// If the connection does not establish within the timeout (default 3s), it triggers OnTimeout
// to launch the automatic JPEG screen fallback.
type WebRTCHandshakeWatchdog struct {
	timeout     time.Duration
	onTimeout   func()
	onConnected func()
	logger      *slog.Logger

	mu        sync.Mutex
	timer     *time.Timer
	connected atomic.Bool
	timedOut  atomic.Bool
	stopped   atomic.Bool
}

// NewWebRTCHandshakeWatchdog constructs a watchdog with the given callbacks.
func NewWebRTCHandshakeWatchdog(
	timeout time.Duration,
	logger *slog.Logger,
	onTimeout func(),
	onConnected func(),
) *WebRTCHandshakeWatchdog {
	if timeout <= 0 {
		timeout = DefaultWebRTCHandshakeTimeout
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &WebRTCHandshakeWatchdog{
		timeout:     timeout,
		logger:      logger,
		onTimeout:   onTimeout,
		onConnected: onConnected,
	}
}

// Start arms the 3-second watchdog timer.
func (w *WebRTCHandshakeWatchdog) Start(ctx context.Context) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.stopped.Load() || w.connected.Load() {
		return
	}

	w.timer = time.AfterFunc(w.timeout, func() {
		if w.stopped.Load() || w.connected.Load() {
			return
		}
		if w.timedOut.CompareAndSwap(false, true) {
			w.logger.Warn("webrtc connection negotiation timed out after 3s; activating screen fallback",
				"timeout", w.timeout,
			)
			if w.onTimeout != nil {
				w.onTimeout()
			}
		}
	})
}

// NotifyICEState updates the watchdog with current ICE connection state.
func (w *WebRTCHandshakeWatchdog) NotifyICEState(state pionwebrtc.ICEConnectionState) {
	switch state {
	case pionwebrtc.ICEConnectionStateConnected, pionwebrtc.ICEConnectionStateCompleted:
		if w.connected.CompareAndSwap(false, true) {
			w.mu.Lock()
			if w.timer != nil {
				w.timer.Stop()
			}
			w.mu.Unlock()

			w.logger.Info("webrtc connection established successfully within deadline",
				"state", state.String(),
			)
			if w.onConnected != nil {
				w.onConnected()
			}
		}

	case pionwebrtc.ICEConnectionStateFailed, pionwebrtc.ICEConnectionStateDisconnected:
		if !w.connected.Load() && w.timedOut.CompareAndSwap(false, true) {
			w.logger.Warn("webrtc connection state failed; activating screen fallback",
				"state", state.String(),
			)
			if w.onTimeout != nil {
				w.onTimeout()
			}
		}
	}
}

// Stop disarms and cleans up the watchdog.
func (w *WebRTCHandshakeWatchdog) Stop() {
	if w.stopped.Swap(true) {
		return
	}
	w.mu.Lock()
	if w.timer != nil {
		w.timer.Stop()
	}
	w.mu.Unlock()
}

// IsConnected returns whether WebRTC reached connected state.
func (w *WebRTCHandshakeWatchdog) IsConnected() bool {
	return w.connected.Load()
}

// HasTimedOut returns whether the 3s timeout fired.
func (w *WebRTCHandshakeWatchdog) HasTimedOut() bool {
	return w.timedOut.Load()
}

// TriggerTimeout immediately forces the timeout action (useful for tests and manual fallback switches).
func (w *WebRTCHandshakeWatchdog) TriggerTimeout() {
	if w.stopped.Load() || w.connected.Load() {
		return
	}
	if w.timedOut.CompareAndSwap(false, true) {
		w.logger.Warn("webrtc connection negotiation timeout triggered manually")
		if w.onTimeout != nil {
			w.onTimeout()
		}
	}
}
