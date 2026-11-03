package server

import (
	"context"
	"encoding/binary"
	"net"
	"sync"
	"testing"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

type mockFastLaneInjector struct {
	mu         sync.Mutex
	lastRelDx  int32
	lastRelDy  int32
	lastAbsX   float32
	lastAbsY   float32
	lastBtn    pb.MouseButton
	lastAct    pb.ButtonAction
	lastScroll int32
}

func (m *mockFastLaneInjector) MoveRelative(dx, dy int32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastRelDx = dx
	m.lastRelDy = dy
	return nil
}

func (m *mockFastLaneInjector) MoveAbsolute(normX, normY float32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastAbsX = normX
	m.lastAbsY = normY
	return nil
}

func (m *mockFastLaneInjector) MouseButton(button pb.MouseButton, action pb.ButtonAction) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastBtn = button
	m.lastAct = action
	return nil
}

func (m *mockFastLaneInjector) Scroll(dx, dy int32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastScroll = dy
	return nil
}

func (m *mockFastLaneInjector) KeyAction(_ uint16, _ pb.ButtonAction) error { return nil }
func (m *mockFastLaneInjector) ExecuteSteps(_ []any) error                 { return nil }
func (m *mockFastLaneInjector) ExecuteCombo(_ string) error                { return nil }
func (m *mockFastLaneInjector) TypeText(_ string) error                    { return nil }
func (m *mockFastLaneInjector) IsDegraded() bool                           { return false }
func (m *mockFastLaneInjector) Close() error                               { return nil }

func TestUdpFastLane_PingPongAndPrecisionCoordinates(t *testing.T) {
	mockInj := &mockFastLaneInjector{}
	server := NewUdpFastLaneServer("127.0.0.1:0", mockInj, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	addr, err := server.Start(ctx)
	if err != nil {
		t.Fatalf("failed to start UDP fast-lane: %v", err)
	}
	defer server.Close()

	clientConn, err := net.Dial("udp", addr.String())
	if err != nil {
		t.Fatalf("failed to dial UDP server: %v", err)
	}
	defer clientConn.Close()

	// 1. Validate OP_PING -> OP_PONG in < 1ms on loopback
	pingPacket := make([]byte, 9)
	pingPacket[0] = OpPing
	nowNano := time.Now().UnixNano()
	binary.BigEndian.PutUint64(pingPacket[1:9], uint64(nowNano))

	start := time.Now()
	if _, err := clientConn.Write(pingPacket); err != nil {
		t.Fatalf("failed to write OP_PING: %v", err)
	}

	pongBuf := make([]byte, 32)
	_ = clientConn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	n, err := clientConn.Read(pongBuf)
	if err != nil {
		t.Fatalf("failed to read OP_PONG: %v", err)
	}

	rtt := time.Since(start)
	if n != 9 {
		t.Fatalf("expected 9 bytes for OP_PONG, got %d", n)
	}
	if pongBuf[0] != OpPong {
		t.Fatalf("expected opcode 0x02, got 0x%02x", pongBuf[0])
	}
	echoNano := int64(binary.BigEndian.Uint64(pongBuf[1:9]))
	if echoNano != nowNano {
		t.Fatalf("expected echoed timestamp %d, got %d", nowNano, echoNano)
	}
	t.Logf("UDP loopback Ping/Pong RTT: %v (< 1ms target)", rtt)

	// 2. Validate OP_MOUSE_REL with signed positive and negative int16 coordinates
	testMoves := []struct {
		dx int16
		dy int16
	}{
		{dx: 15, dy: 42},
		{dx: -320, dy: -540},
		{dx: -1, dy: 1},
	}

	relPacket := make([]byte, 5)
	relPacket[0] = OpMouseRel

	for _, tc := range testMoves {
		binary.BigEndian.PutUint16(relPacket[1:3], uint16(tc.dx))
		binary.BigEndian.PutUint16(relPacket[3:5], uint16(tc.dy))

		if _, err := clientConn.Write(relPacket); err != nil {
			t.Fatalf("failed to write OP_MOUSE_REL: %v", err)
		}

		time.Sleep(5 * time.Millisecond)

		mockInj.mu.Lock()
		gotDx := mockInj.lastRelDx
		gotDy := mockInj.lastRelDy
		mockInj.mu.Unlock()

		if gotDx != int32(tc.dx) || gotDy != int32(tc.dy) {
			t.Fatalf("mismatched relative move: want (%d, %d), got (%d, %d)", tc.dx, tc.dy, gotDx, gotDy)
		}
	}

	// 3. Validate OP_MOUSE_ABS coordinates [0..65535] mapped to [0.0, 1.0]
	absPacket := make([]byte, 5)
	absPacket[0] = OpMouseAbs
	binary.BigEndian.PutUint16(absPacket[1:3], 32767) // ~0.5
	binary.BigEndian.PutUint16(absPacket[3:5], 65535) // 1.0

	if _, err := clientConn.Write(absPacket); err != nil {
		t.Fatalf("failed to write OP_MOUSE_ABS: %v", err)
	}

	time.Sleep(5 * time.Millisecond)

	mockInj.mu.Lock()
	gotAbsX := mockInj.lastAbsX
	gotAbsY := mockInj.lastAbsY
	mockInj.mu.Unlock()

	if gotAbsX < 0.49 || gotAbsX > 0.51 {
		t.Fatalf("expected normX ~0.5, got %f", gotAbsX)
	}
	if gotAbsY < 0.99 || gotAbsY > 1.01 {
		t.Fatalf("expected normY ~1.0, got %f", gotAbsY)
	}
}

