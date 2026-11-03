package server

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"sync/atomic"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	DefaultUdpFastLanePort = 8443

	OpPing        byte = 0x01
	OpPong        byte = 0x02
	OpMouseRel    byte = 0x10
	OpMouseAbs    byte = 0x11
	OpMouseBtn    byte = 0x12
	OpMouseScroll byte = 0x13
)

// FastLaneInjector defines the minimal input injection interface needed by the UDP fast-lane.
type FastLaneInjector interface {
	MoveRelative(dx, dy int32) error
	MoveAbsolute(normX, normY float32) error
	MouseButton(button pb.MouseButton, action pb.ButtonAction) error
	Scroll(dx, dy int32) error
}

// UdpFastLaneServer implements FIX-01: dedicated UDP fast-lane for mouse, touch and ping on :8443/udp.
// Uses a zero-allocation read loop with pre-allocated buffer to eliminate Head-of-Line blocking and GC pauses.
type UdpFastLaneServer struct {
	addr     string
	conn     *net.UDPConn
	injector FastLaneInjector
	logger   *slog.Logger
	running  atomic.Bool
	closeCh  chan struct{}
}

// NewUdpFastLaneServer creates a new UDP fast-lane server instance.
func NewUdpFastLaneServer(addr string, injector FastLaneInjector, logger *slog.Logger) *UdpFastLaneServer {
	if addr == "" {
		addr = fmt.Sprintf("0.0.0.0:%d", DefaultUdpFastLanePort)
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &UdpFastLaneServer{
		addr:     addr,
		injector: injector,
		logger:   logger,
		closeCh:  make(chan struct{}),
	}
}

// Start opens the UDP socket and begins servicing incoming datagrams in a background goroutine.
func (s *UdpFastLaneServer) Start(ctx context.Context) (net.Addr, error) {
	udpAddr, err := net.ResolveUDPAddr("udp", s.addr)
	if err != nil {
		return nil, fmt.Errorf("udp fast-lane: invalid addr %q: %w", s.addr, err)
	}

	conn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return nil, fmt.Errorf("udp fast-lane: listen failed: %w", err)
	}

	// Configure large OS socket buffers to prevent packet loss under bursts
	_ = conn.SetReadBuffer(262144)
	_ = conn.SetWriteBuffer(262144)

	s.conn = conn
	s.running.Store(true)

	s.logger.Info("UDP fast-lane server listening", "addr", conn.LocalAddr().String())

	go s.serveLoop(ctx)

	return conn.LocalAddr(), nil
}

// serveLoop runs the zero-allocation packet processing loop.
func (s *UdpFastLaneServer) serveLoop(ctx context.Context) {
	defer func() {
		s.running.Store(false)
		if s.conn != nil {
			_ = s.conn.Close()
		}
	}()

	// Static 32-byte array preallocated outside loop to guarantee zero heap allocations per packet
	var buf [32]byte

	for {
		select {
		case <-ctx.Done():
			return
		case <-s.closeCh:
			return
		default:
		}

		n, remoteAddr, err := s.conn.ReadFromUDP(buf[:])
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}

		if n < 1 {
			continue
		}

		op := buf[0]

		switch op {
		case OpPing: // 0x01: [0x01][int64 client_ts_nanos] (9 bytes)
			if n >= 9 {
				buf[0] = OpPong
				_, _ = s.conn.WriteToUDP(buf[:9], remoteAddr)
			}

		case OpMouseRel: // 0x10: [0x10][int16 dx][int16 dy] (5 bytes)
			if n >= 5 && s.injector != nil {
				dx := int16(binary.BigEndian.Uint16(buf[1:3]))
				dy := int16(binary.BigEndian.Uint16(buf[3:5]))
				_ = s.injector.MoveRelative(int32(dx), int32(dy))
			}

		case OpMouseAbs: // 0x11: [0x11][uint16 norm_x][uint16 norm_y] (5 bytes, 0..65535)
			if n >= 5 && s.injector != nil {
				rawX := binary.BigEndian.Uint16(buf[1:3])
				rawY := binary.BigEndian.Uint16(buf[3:5])
				normX := float32(rawX) / 65535.0
				normY := float32(rawY) / 65535.0
				_ = s.injector.MoveAbsolute(normX, normY)
			}

		case OpMouseBtn: // 0x12: [0x12][uint8 button][uint8 action] (3 bytes)
			if n >= 3 && s.injector != nil {
				var btn pb.MouseButton
				switch buf[1] {
				case 1:
					btn = pb.MouseButton_LEFT
				case 2:
					btn = pb.MouseButton_RIGHT
				case 3:
					btn = pb.MouseButton_MIDDLE
				default:
					btn = pb.MouseButton_LEFT
				}

				var act pb.ButtonAction
				switch buf[2] {
				case 1:
					act = pb.ButtonAction_PRESS
				case 0:
					act = pb.ButtonAction_RELEASE
				case 2:
					act = pb.ButtonAction_CLICK
				default:
					act = pb.ButtonAction_CLICK
				}

				_ = s.injector.MouseButton(btn, act)
			}

		case OpMouseScroll: // 0x13: [0x13][int16 v_scroll][int16 h_scroll] (5 bytes)
			if n >= 5 && s.injector != nil {
				vScroll := int16(binary.BigEndian.Uint16(buf[1:3]))
				hScroll := int16(binary.BigEndian.Uint16(buf[3:5]))
				_ = s.injector.Scroll(int32(hScroll), int32(vScroll))
			}
		}
	}
}

// Close shuts down the UDP listener.
func (s *UdpFastLaneServer) Close() error {
	if s.running.Swap(false) {
		close(s.closeCh)
		if s.conn != nil {
			return s.conn.Close()
		}
	}
	return nil
}
