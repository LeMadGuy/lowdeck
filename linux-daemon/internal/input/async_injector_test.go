package input

import (
	"errors"
	"sync"
	"testing"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// blockingInjector wraps MockInjector and blocks every injection until release is closed,
// simulating a congested /dev/uinput or compositor.
type blockingInjector struct {
	*MockInjector
	release chan struct{}
	once    sync.Once
	started chan struct{}
}

func newBlockingInjector() *blockingInjector {
	return &blockingInjector{
		MockInjector: NewMockInjector(),
		release:      make(chan struct{}),
		started:      make(chan struct{}),
	}
}

func (b *blockingInjector) wait() {
	b.once.Do(func() { close(b.started) })
	<-b.release
}

func (b *blockingInjector) MoveRelative(dx, dy int32) error {
	b.wait()
	return b.MockInjector.MoveRelative(dx, dy)
}

func (b *blockingInjector) MouseButton(btn pb.MouseButton, act pb.ButtonAction) error {
	b.wait()
	return b.MockInjector.MouseButton(btn, act)
}

func TestAsyncInjectorNeverBlocksAndCoalescesInOrder(t *testing.T) {
	t.Parallel()

	inner := newBlockingInjector()
	async := NewAsyncInjector(inner, 64, nil)
	defer async.Close()

	// First event occupies the worker (blocked inside the "kernel" write).
	if err := async.MoveRelative(1, 1); err != nil {
		t.Fatalf("enqueue first move: %v", err)
	}
	<-inner.started

	// While the worker is blocked, the network reader must keep enqueuing without blocking.
	start := time.Now()
	for i := 0; i < 500; i++ {
		if err := async.MoveRelative(2, -1); err != nil {
			t.Fatalf("enqueue move %d: %v", i, err)
		}
	}
	if err := async.MouseButton(pb.MouseButton_LEFT, pb.ButtonAction_CLICK); err != nil {
		t.Fatalf("enqueue click: %v", err)
	}
	for i := 0; i < 10; i++ {
		_ = async.MoveRelative(1, 0)
	}
	if elapsed := time.Since(start); elapsed > 200*time.Millisecond {
		t.Fatalf("enqueue blocked the caller for %v", elapsed)
	}

	close(inner.release)
	async.Flush()

	events := inner.Events()
	if len(events) != 4 {
		t.Fatalf("expected 4 coalesced kernel events, got %d: %+v", len(events), events)
	}
	if events[0].Kind != "move_rel" || events[0].DX != 1 || events[0].DY != 1 {
		t.Errorf("unexpected event[0]: %+v", events[0])
	}
	if events[1].Kind != "move_rel" || events[1].DX != 1000 || events[1].DY != -500 {
		t.Errorf("unexpected coalesced event[1]: %+v", events[1])
	}
	if events[2].Kind != "mouse_btn" || events[2].Action != pb.ButtonAction_CLICK {
		t.Errorf("click must be ordered after preceding motion: %+v", events[2])
	}
	if events[3].Kind != "move_rel" || events[3].DX != 10 {
		t.Errorf("motion after click must not be merged before it: %+v", events[3])
	}

	coalesced, dropped := async.Stats()
	if coalesced != 508 || dropped != 0 {
		t.Errorf("stats coalesced=%d dropped=%d, want 508/0", coalesced, dropped)
	}
}

func TestAsyncInjectorDiscardsDiscreteEventsWhenSaturated(t *testing.T) {
	t.Parallel()

	inner := newBlockingInjector()
	async := NewAsyncInjector(inner, 4, nil)

	_ = async.MoveRelative(1, 1)
	<-inner.started

	// Fill the bounded queue (capacity 4) with non-mergeable discrete events.
	for i := 0; i < 4; i++ {
		if err := async.MouseButton(pb.MouseButton_LEFT, pb.ButtonAction_CLICK); err != nil {
			t.Fatalf("enqueue click %d: %v", i, err)
		}
	}
	if err := async.MouseButton(pb.MouseButton_RIGHT, pb.ButtonAction_CLICK); !errors.Is(err, ErrInputQueueFull) {
		t.Fatalf("expected ErrInputQueueFull, got %v", err)
	}
	if _, dropped := async.Stats(); dropped != 1 {
		t.Fatalf("expected 1 dropped event, got %d", dropped)
	}

	close(inner.release)
	if err := async.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if got := len(inner.Events()); got != 5 {
		t.Fatalf("expected pending events drained on Close (5), got %d", got)
	}
	if err := async.MoveRelative(1, 1); !errors.Is(err, ErrInputQueueClosed) {
		t.Fatalf("expected ErrInputQueueClosed after Close, got %v", err)
	}
}

