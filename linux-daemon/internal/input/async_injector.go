package input

import (
	"errors"
	"log/slog"
	"math"
	"sync"
	"sync/atomic"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// DefaultAsyncQueueCapacity bounds the number of pending (not yet injected) input operations.
const DefaultAsyncQueueCapacity = 1024

var (
	// ErrInputQueueFull is returned when a discrete input operation (button/key) is discarded
	// because the bounded asynchronous queue is saturated.
	ErrInputQueueFull = errors.New("input async: queue full, event discarded")
	// ErrInputQueueClosed is returned when enqueueing on a closed AsyncInjector.
	ErrInputQueueClosed = errors.New("input async: injector closed")
)

type asyncOpKind uint8

const (
	opMoveRel asyncOpKind = iota + 1
	opMoveAbs
	opScroll
	opDiscrete
	opBarrier
)

type asyncOp struct {
	kind    asyncOpKind
	dx, dy  int32
	x, y    float32
	run     func(InputInjector) error
	barrier chan struct{}
}

// AsyncInjector decouples the WebSocket read loop from blocking /dev/uinput write(2) syscalls.
//
// All operations are enqueued in strict arrival order into a bounded in-memory queue consumed by a
// single dedicated worker goroutine:
//   - Consecutive relative moves and scroll deltas still waiting in the queue are coalesced (summed)
//     into one kernel event, and consecutive absolute moves keep only the latest coordinate, so a slow
//     compositor or uinput backlog can never cause cumulative cursor lag.
//   - Discrete operations (buttons, keys, combos, text) are never merged; when the queue is saturated
//     they are discarded (drop policy) instead of blocking the network reader, and counted.
type AsyncInjector struct {
	inner    InputInjector
	logger   *slog.Logger
	capacity int

	mu     sync.Mutex
	queue  []asyncOp
	closed bool

	signal chan struct{}
	stopCh chan struct{}
	doneCh chan struct{}

	closeOnce sync.Once

	coalesced atomic.Uint64
	dropped   atomic.Uint64
}

// NewAsyncInjector wraps inner with a bounded, coalescing, non-blocking injection queue.
func NewAsyncInjector(inner InputInjector, capacity int, logger *slog.Logger) *AsyncInjector {
	if inner == nil {
		inner = NewMockInjector()
	}
	if capacity <= 0 {
		capacity = DefaultAsyncQueueCapacity
	}
	if logger == nil {
		logger = slog.Default()
	}
	a := &AsyncInjector{
		inner:    inner,
		logger:   logger,
		capacity: capacity,
		queue:    make([]asyncOp, 0, 64),
		signal:   make(chan struct{}, 1),
		stopCh:   make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
	go a.worker()
	return a
}

// Stats returns how many motion events were coalesced and how many discrete events were dropped.
func (a *AsyncInjector) Stats() (coalesced, dropped uint64) {
	return a.coalesced.Load(), a.dropped.Load()
}

func (a *AsyncInjector) IsDegraded() bool { return a.inner.IsDegraded() }

func (a *AsyncInjector) MoveRelative(dx, dy int32) error {
	return a.enqueue(asyncOp{kind: opMoveRel, dx: dx, dy: dy})
}

func (a *AsyncInjector) MoveAbsolute(normX, normY float32) error {
	return a.enqueue(asyncOp{kind: opMoveAbs, x: normX, y: normY})
}

func (a *AsyncInjector) Scroll(dx, dy int32) error {
	return a.enqueue(asyncOp{kind: opScroll, dx: dx, dy: dy})
}

func (a *AsyncInjector) MouseButton(button pb.MouseButton, action pb.ButtonAction) error {
	if _, err := mapMouseButton(button); err != nil {
		return err
	}
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.MouseButton(button, action)
	}})
}

func (a *AsyncInjector) KeyAction(keyCode uint16, action pb.ButtonAction) error {
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.KeyAction(keyCode, action)
	}})
}

func (a *AsyncInjector) ExecuteSteps(steps []KeyStep) error {
	copied := make([]KeyStep, len(steps))
	copy(copied, steps)
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.ExecuteSteps(copied)
	}})
}

func (a *AsyncInjector) ExecuteCombo(combo string) error {
	// Validate synchronously so callers still receive parse errors immediately.
	if _, err := ParseKeyCombo(combo); err != nil {
		return err
	}
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.ExecuteCombo(combo)
	}})
}

func (a *AsyncInjector) TypeText(text string) error {
	if _, err := TextToKeySteps(text); err != nil {
		return err
	}
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.TypeText(text)
	}})
}

func (a *AsyncInjector) ReleaseAll() error {
	return a.enqueue(asyncOp{kind: opDiscrete, run: func(inj InputInjector) error {
		return inj.ReleaseAll()
	}})
}

// Flush blocks until every operation enqueued before the call has been injected.
func (a *AsyncInjector) Flush() {
	done := make(chan struct{})
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	// Barriers bypass the capacity limit so Flush can never deadlock on a saturated queue.
	a.queue = append(a.queue, asyncOp{kind: opBarrier, barrier: done})
	a.mu.Unlock()
	a.notify()

	select {
	case <-done:
	case <-a.doneCh:
	}
}

// Close drains pending operations, stops the worker goroutine, and destroys the inner devices.
func (a *AsyncInjector) Close() error {
	var err error
	a.closeOnce.Do(func() {
		a.mu.Lock()
		a.closed = true
		a.mu.Unlock()
		close(a.stopCh)
		<-a.doneCh

		coalesced, dropped := a.Stats()
		if dropped > 0 {
			a.logger.Warn("input async queue discarded events", "dropped", dropped, "coalesced", coalesced)
		}
		err = a.inner.Close()
	})
	return err
}

func (a *AsyncInjector) enqueue(op asyncOp) error {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return ErrInputQueueClosed
	}

	if n := len(a.queue); n > 0 {
		last := &a.queue[n-1]
		if last.kind == op.kind {
			switch op.kind {
			case opMoveRel, opScroll:
				last.dx = saturatingAdd(last.dx, op.dx)
				last.dy = saturatingAdd(last.dy, op.dy)
				a.mu.Unlock()
				a.coalesced.Add(1)
				return nil
			case opMoveAbs:
				last.x, last.y = op.x, op.y
				a.mu.Unlock()
				a.coalesced.Add(1)
				return nil
			}
		}
	}

	if len(a.queue) >= a.capacity {
		a.mu.Unlock()
		a.dropped.Add(1)
		if op.kind == opDiscrete {
			return ErrInputQueueFull
		}
		// Motion events are inherently superseded by newer samples: discard silently.
		return nil
	}

	a.queue = append(a.queue, op)
	a.mu.Unlock()
	a.notify()
	return nil
}

func (a *AsyncInjector) notify() {
	select {
	case a.signal <- struct{}{}:
	default:
	}
}

func (a *AsyncInjector) worker() {
	defer close(a.doneCh)
	batch := make([]asyncOp, 0, 64)

	for {
		select {
		case <-a.stopCh:
			// Drain whatever is left so no button/key is left half-pressed in the kernel.
			batch = a.takeAll(batch[:0])
			a.execute(batch)
			return
		case <-a.signal:
			for {
				batch = a.takeAll(batch[:0])
				if len(batch) == 0 {
					break
				}
				a.execute(batch)
			}
		}
	}
}

func (a *AsyncInjector) takeAll(dst []asyncOp) []asyncOp {
	a.mu.Lock()
	dst = append(dst, a.queue...)
	clear(a.queue)
	a.queue = a.queue[:0]
	a.mu.Unlock()
	return dst
}

func (a *AsyncInjector) execute(batch []asyncOp) {
	for i := range batch {
		op := &batch[i]
		var err error
		switch op.kind {
		case opMoveRel:
			err = a.inner.MoveRelative(op.dx, op.dy)
		case opMoveAbs:
			err = a.inner.MoveAbsolute(op.x, op.y)
		case opScroll:
			err = a.inner.Scroll(op.dx, op.dy)
		case opDiscrete:
			err = op.run(a.inner)
		case opBarrier:
			close(op.barrier)
		}
		if err != nil {
			a.logger.Debug("input async injection failed", "err", err)
		}
		op.run = nil
		op.barrier = nil
	}
}

func saturatingAdd(a, b int32) int32 {
	sum := int64(a) + int64(b)
	if sum > math.MaxInt32 {
		return math.MaxInt32
	}
	if sum < math.MinInt32 {
		return math.MinInt32
	}
	return int32(sum)
}

