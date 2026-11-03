package pty

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

type testPtyClient struct {
	id     string
	ctx    context.Context
	cancel context.CancelFunc
	sendCh chan *pb.Envelope
}

func newTestPtyClient() *testPtyClient {
	ctx, cancel := context.WithCancel(context.Background())
	return &testPtyClient{
		id:     "pty-test-client",
		ctx:    ctx,
		cancel: cancel,
		sendCh: make(chan *pb.Envelope, 128),
	}
}

func (c *testPtyClient) ID() string               { return c.id }
func (c *testPtyClient) DeviceID() string         { return "dev-pty" }
func (c *testPtyClient) DeviceName() string       { return "PTY Test Client" }
func (c *testPtyClient) CertFingerprint() string  { return "1234" }
func (c *testPtyClient) Context() context.Context { return c.ctx }
func (c *testPtyClient) Close() error             { c.cancel(); return nil }

func (c *testPtyClient) SendImmediate(env *pb.Envelope) error {
	return c.Send(env)
}

func (c *testPtyClient) Send(env *pb.Envelope) error {
	select {
	case c.sendCh <- env:
		return nil
	default:
		return errors.New("send buffer full")
	}
}

func TestPtyOpenEchoResizeAndCloseLifecycle(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mgr := NewManager(DefaultMaxSessionsPerClient, nil)
	defer mgr.CloseAll()

	router := server.NewRouter(nil)
	mgr.Register(router)

	client := newTestPtyClient()
	defer client.Close()

	const sessionID = "sess-sh-1"

	// 1. Open real /bin/sh PTY session (80x24).
	if err := router.Dispatch(ctx, client, &pb.Envelope{
		RequestId: "open-1",
		Payload: &pb.Envelope_PtyOpenRequest{
			PtyOpenRequest: &pb.PtyOpenRequest{
				SessionId: sessionID,
				Cols:      80,
				Rows:      24,
				Shell:     "/bin/sh",
			},
		},
	}); err != nil {
		t.Fatalf("PtyOpenRequest failed: %v", err)
	}

	if got := mgr.ActiveCount(); got != 1 {
		t.Fatalf("ActiveCount() = %d, want 1", got)
	}

	// 2. Send `echo "HELLO_DECK"\n` via PtyDataEvent.
	if err := router.Dispatch(ctx, client, &pb.Envelope{
		RequestId: "data-1",
		Payload: &pb.Envelope_PtyDataEvent{
			PtyDataEvent: &pb.PtyDataEvent{
				SessionId: sessionID,
				Data:      []byte("echo \"HELLO_DECK\" && echo \"TERM_IS_$TERM\"\n"),
			},
		},
	}); err != nil {
		t.Fatalf("PtyDataEvent failed: %v", err)
	}

	// 3. Read PtyDataEvents from channel until both HELLO_DECK and TERM_IS_xterm-256color appear.
	var accumulated strings.Builder
	deadline := time.After(5 * time.Second)
	for !strings.Contains(accumulated.String(), "HELLO_DECK") || !strings.Contains(accumulated.String(), "TERM_IS_xterm-256color") {
		select {
		case env := <-client.sendCh:
			if dataEv := env.GetPtyDataEvent(); dataEv != nil && dataEv.SessionId == sessionID {
				accumulated.Write(dataEv.Data)
			}
		case <-deadline:
			t.Fatalf("timed out waiting for 'HELLO_DECK' in PTY output; got so far: %q", accumulated.String())
		}
	}

	// 4. Resize terminal to 120x40 (cols=120, rows=40).
	if err := router.Dispatch(ctx, client, &pb.Envelope{
		RequestId: "resize-1",
		Payload: &pb.Envelope_PtyResizeEvent{
			PtyResizeEvent: &pb.PtyResizeEvent{
				SessionId: sessionID,
				Cols:      120,
				Rows:      40,
			},
		},
	}); err != nil {
		t.Fatalf("PtyResizeEvent failed: %v", err)
	}

	rows, cols, err := mgr.WindowSize(sessionID)
	if err != nil {
		t.Fatalf("WindowSize failed: %v", err)
	}
	if cols != 120 || rows != 40 {
		t.Fatalf("WindowSize = (%d rows, %d cols), want (40 rows, 120 cols)", rows, cols)
	}

	// 5. Close session via PtyCloseEvent and verify cleanup without orphan processes.
	if err := router.Dispatch(ctx, client, &pb.Envelope{
		RequestId: "close-1",
		Payload: &pb.Envelope_PtyCloseEvent{
			PtyCloseEvent: &pb.PtyCloseEvent{
				SessionId: sessionID,
				Reason:    "test completed",
			},
		},
	}); err != nil {
		t.Fatalf("PtyCloseEvent failed: %v", err)
	}

	if got := mgr.ActiveCount(); got != 0 {
		t.Fatalf("ActiveCount() after close = %d, want 0", got)
	}
}

func TestPtyMaxSessionsLimitPerClient(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mgr := NewManager(2, nil)
	defer mgr.CloseAll()

	client := newTestPtyClient()
	defer client.Close()

	for i, id := range []string{"tab-1", "tab-2"} {
		if err := mgr.HandleOpen(ctx, client, &pb.Envelope{
			Payload: &pb.Envelope_PtyOpenRequest{
				PtyOpenRequest: &pb.PtyOpenRequest{
					SessionId: id,
					Shell:     "/bin/sh",
				},
			},
		}); err != nil {
			t.Fatalf("HandleOpen #%d (%s) failed: %v", i, id, err)
		}
	}

	// Opening a 3rd session when max is 2 must return ErrMaxSessionsReached.
	err := mgr.HandleOpen(ctx, client, &pb.Envelope{
		Payload: &pb.Envelope_PtyOpenRequest{
			PtyOpenRequest: &pb.PtyOpenRequest{
				SessionId: "tab-3",
				Shell:     "/bin/sh",
			},
		},
	})
	if !errors.Is(err, ErrMaxSessionsReached) {
		t.Fatalf("expected ErrMaxSessionsReached on 3rd session, got: %v", err)
	}
}

