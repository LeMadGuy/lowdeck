package macro

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

type mockClientSession struct {
	id     string
	ctx    context.Context
	cancel context.CancelFunc
	sendCh chan *pb.Envelope
	mu     sync.Mutex
	sent   []*pb.Envelope
}

func newMockClientSession() *mockClientSession {
	ctx, cancel := context.WithCancel(context.Background())
	return &mockClientSession{
		id:     "test-client-01",
		ctx:    ctx,
		cancel: cancel,
		sendCh: make(chan *pb.Envelope, 64),
	}
}

func (m *mockClientSession) ID() string                 { return m.id }
func (m *mockClientSession) DeviceID() string           { return "dev-01" }
func (m *mockClientSession) DeviceName() string         { return "Test Device" }
func (m *mockClientSession) CertFingerprint() string    { return "abcd" }
func (m *mockClientSession) Context() context.Context   { return m.ctx }
func (m *mockClientSession) Close() error               { m.cancel(); return nil }

func (m *mockClientSession) Send(env *pb.Envelope) error {
	m.mu.Lock()
	m.sent = append(m.sent, env)
	m.mu.Unlock()
	select {
	case m.sendCh <- env:
	default:
	}
	return nil
}

func TestProfileStoreDefaultCreationAndReload(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	store, err := NewProfileStore(tmpDir)
	if err != nil {
		t.Fatalf("NewProfileStore: %v", err)
	}

	prof := store.GetProfile()
	if prof.ProfileID != "default" || prof.Rows != 3 || prof.Cols != 5 {
		t.Fatalf("unexpected default profile: %+v", prof)
	}
	if _, ok := store.FindButton("shortcut-terminal"); !ok {
		t.Errorf("expected default profile to contain 'shortcut-terminal'")
	}
	if _, ok := store.FindButton("widget-cpu"); !ok {
		t.Errorf("expected default profile to contain 'widget-cpu'")
	}

	protoSync, err := store.ToProto()
	if err != nil {
		t.Fatalf("ToProto: %v", err)
	}
	if len(protoSync.Pages) < 2 || protoSync.RawJson == "" {
		t.Errorf("unexpected ToProto result: pages=%d", len(protoSync.Pages))
	}

	// Reload from disk and verify persistence.
	reloaded, err := NewProfileStore(tmpDir)
	if err != nil {
		t.Fatalf("reload NewProfileStore: %v", err)
	}
	if reloaded.GetProfile().Name != prof.Name {
		t.Errorf("reloaded profile name = %q, want %q", reloaded.GetProfile().Name, prof.Name)
	}
}

func TestMacroEngineAsyncStreamingAndDynamicState(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	store, err := NewProfileStore(tmpDir)
	if err != nil {
		t.Fatalf("NewProfileStore: %v", err)
	}

	engine := NewEngine(store, nil)
	router := server.NewRouter(nil)
	engine.Register(router)

	client := newMockClientSession()
	defer client.Close()

	// Execute a command that outputs stdout, stderr, and a DECK_STATE dynamic directive.
	envReq := &pb.Envelope{
		RequestId:   "macro-req-1",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_MacroExecRequest{
			MacroExecRequest: &pb.MacroExecRequest{
				ButtonId: "cmd-uptime",
				Command:  `echo "OUT_LINE_1"; echo "ERR_LINE_1" >&2; echo 'DECK_STATE:{"title":"VPN: ON","color":"#10B981","is_active":true}'`,
				Env: map[string]string{
					"CUSTOM_VAR": "deck_val",
				},
				TimeoutSeconds: 5,
			},
		},
	}

	if err := router.Dispatch(context.Background(), client, envReq); err != nil {
		t.Fatalf("router.Dispatch MacroExecRequest: %v", err)
	}

	engine.Wait()

	var (
		sawRunning bool
		stdoutBuf  strings.Builder
		stderrBuf  strings.Builder
		finalSync  *pb.MacroStateSync
	)

	client.mu.Lock()
	events := append([]*pb.Envelope{}, client.sent...)
	client.mu.Unlock()

	for _, ev := range events {
		if out := ev.GetMacroOutputEvent(); out != nil {
			stdoutBuf.Write(out.Stdout)
			stderrBuf.Write(out.Stderr)
		}
		if st := ev.GetMacroStateSync(); st != nil {
			if st.State == pb.MacroRunState_RUNNING {
				sawRunning = true
			}
			if st.State == pb.MacroRunState_SUCCESS || st.State == pb.MacroRunState_ERROR {
				finalSync = st
			}
		}
	}

	if !sawRunning {
		t.Errorf("expected initial MacroRunState_RUNNING state sync")
	}
	if !strings.Contains(stdoutBuf.String(), "OUT_LINE_1") {
		t.Errorf("stdout %q does not contain 'OUT_LINE_1'", stdoutBuf.String())
	}
	if !strings.Contains(stderrBuf.String(), "ERR_LINE_1") {
		t.Errorf("stderr %q does not contain 'ERR_LINE_1'", stderrBuf.String())
	}
	if finalSync == nil {
		t.Fatalf("expected final MacroStateSync event")
	}
	if finalSync.State != pb.MacroRunState_SUCCESS || finalSync.ExitCode != 0 {
		t.Errorf("expected SUCCESS with exit_code=0, got state=%v exit_code=%d", finalSync.State, finalSync.ExitCode)
	}
	if finalSync.Title != "VPN: ON" || finalSync.Color != "#10B981" || !finalSync.IsActive {
		t.Errorf("expected dynamic directive override (VPN: ON, #10B981, active=true), got %+v", finalSync)
	}
}

