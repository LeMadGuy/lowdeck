package macro

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	// DefaultCommandTimeout is the default execution timeout for macro commands (30 seconds).
	DefaultCommandTimeout = 30 * time.Second
	// StateDirectivePrefix allows a macro script to emit a JSON object on stdout to dynamically
	// update the button's title, color, active icon, and toggle state.
	StateDirectivePrefix = "DECK_STATE:"
)

// DynamicButtonDirective can be emitted by a command or poll script (either via DECK_STATE:{...} or raw JSON)
// to dynamically update a button's appearance on the Android Deck.
type DynamicButtonDirective struct {
	Title        string `json:"title,omitempty"`
	Color        string `json:"color,omitempty"`
	ActiveIcon   string `json:"active_icon,omitempty"`
	InactiveIcon string `json:"inactive_icon,omitempty"`
	IsActive     *bool  `json:"is_active,omitempty"`
}

// Engine executes macro commands asynchronously, streams stdout/stderr, and synchronizes Deck profiles.
type Engine struct {
	store        *ProfileStore
	logger       *slog.Logger
	wg           sync.WaitGroup
	mu           sync.Mutex
	toggleStates map[string]bool
}

// NewEngine creates a new Macro Engine backed by store.
func NewEngine(store *ProfileStore, logger *slog.Logger) *Engine {
	if logger == nil {
		logger = slog.Default()
	}
	return &Engine{
		store:        store,
		logger:       logger,
		toggleStates: make(map[string]bool),
	}
}

// Register binds MacroExecRequest and DeckProfileSync handlers on router.
func (e *Engine) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindMacroExec, e.HandleMacroExec)
	router.Register(server.KindDeckProfileSync, e.HandleDeckProfileSync)
}

// SendProfileToClient sends the current DeckProfileSync snapshot to a connected client session.
func (e *Engine) SendProfileToClient(client server.ClientSession) error {
	if e.store == nil || client == nil {
		return nil
	}
	syncMsg, err := e.store.ToProto()
	if err != nil {
		return err
	}
	return client.Send(&pb.Envelope{
		RequestId:   fmt.Sprintf("profile-sync-%d", time.Now().UnixNano()),
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_DeckProfileSync{
			DeckProfileSync: syncMsg,
		},
	})
}

// HandleDeckProfileSync handles incoming profile updates or sync requests from the client.
func (e *Engine) HandleDeckProfileSync(_ context.Context, client server.ClientSession, env *pb.Envelope) error {
	req := env.GetDeckProfileSync()
	if req == nil {
		return errors.New("macro engine: nil DeckProfileSync")
	}

	// If client sent a non-empty RawJson profile update, validate and persist it.
	if strings.TrimSpace(req.RawJson) != "" && e.store != nil {
		var updated ProfileConfig
		if err := json.Unmarshal([]byte(req.RawJson), &updated); err != nil {
			return fmt.Errorf("macro engine parse profile raw_json: %w", err)
		}
		if err := e.store.SaveProfile(updated); err != nil {
			return fmt.Errorf("macro engine save profile: %w", err)
		}
	}

	if e.store == nil || client == nil {
		return nil
	}
	syncMsg, err := e.store.ToProto()
	if err != nil {
		return err
	}
	return client.Send(&pb.Envelope{
		RequestId:   env.RequestId,
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_DeckProfileSync{
			DeckProfileSync: syncMsg,
		},
	})
}

// HandleMacroExec dispatches a macro command execution in a dedicated non-blocking goroutine tracked by WaitGroup.
func (e *Engine) HandleMacroExec(ctx context.Context, client server.ClientSession, env *pb.Envelope) error {
	req := env.GetMacroExecRequest()
	if req == nil {
		return errors.New("macro engine: nil MacroExecRequest")
	}

	var btnCfg ButtonConfig
	if e.store != nil && req.ButtonId != "" {
		btnCfg, _ = e.store.FindButton(req.ButtonId)
	}

	cmdStr := strings.TrimSpace(req.Command)
	if cmdStr == "" {
		cmdStr = strings.TrimSpace(btnCfg.Command)
	}
	if cmdStr == "" {
		return fmt.Errorf("macro engine: no command configured for button %q", req.ButtonId)
	}

	buttonID := req.ButtonId
	if buttonID == "" {
		buttonID = "adhoc-cmd"
	}

	mergedEnv := make(map[string]string)
	for k, v := range btnCfg.Env {
		mergedEnv[k] = v
	}
	for k, v := range req.Env {
		mergedEnv[k] = v
	}

	workDir := strings.TrimSpace(req.WorkingDir)
	if workDir == "" {
		workDir = strings.TrimSpace(btnCfg.WorkingDir)
	}

	timeout := DefaultCommandTimeout
	if req.TimeoutSeconds > 0 {
		timeout = time.Duration(req.TimeoutSeconds) * time.Second
	} else if btnCfg.TimeoutSeconds > 0 {
		timeout = time.Duration(btnCfg.TimeoutSeconds) * time.Second
	}

	reqID := env.RequestId

	e.wg.Add(1)
	go func() {
		defer e.wg.Done()
		e.runCommandAsync(ctx, client, reqID, buttonID, cmdStr, workDir, mergedEnv, timeout, btnCfg)
	}()

	return nil
}

// EvaluatePollStatus executes a button's poll_command (or command) and returns the resulting MacroStateSync
// for toggle/status buttons.
func (e *Engine) EvaluatePollStatus(ctx context.Context, buttonID string, commandOverride string) (*pb.MacroStateSync, error) {
	var btnCfg ButtonConfig
	if e.store != nil && buttonID != "" {
		btnCfg, _ = e.store.FindButton(buttonID)
	}

	pollCmd := strings.TrimSpace(commandOverride)
	if pollCmd == "" {
		pollCmd = strings.TrimSpace(btnCfg.PollCommand)
	}
	if pollCmd == "" {
		pollCmd = strings.TrimSpace(btnCfg.Command)
	}
	if pollCmd == "" {
		return nil, fmt.Errorf("macro poll: no command found for button %q", buttonID)
	}

	execCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	cmd := exec.CommandContext(execCtx, "/bin/sh", "-c", pollCmd)
	var outBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &outBuf

	err := cmd.Run()
	exitCode := exitCodeFromError(err)

	stateSync := buildStateSync(buttonID, btnCfg, outBuf.String(), exitCode, exitCode == 0)
	return stateSync, nil
}

// Wait blocks until all running macro goroutines have completed.
func (e *Engine) Wait() {
	e.wg.Wait()
}

func (e *Engine) runCommandAsync(
	parentCtx context.Context,
	client server.ClientSession,
	reqID string,
	buttonID string,
	cmdStr string,
	workDir string,
	envMap map[string]string,
	timeout time.Duration,
	btnCfg ButtonConfig,
) {
	if client != nil {
		_ = client.Send(&pb.Envelope{
			RequestId:   reqID,
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_MacroStateSync{
				MacroStateSync: &pb.MacroStateSync{
					ButtonId:     buttonID,
					State:        pb.MacroRunState_RUNNING,
					Title:        btnCfg.Title,
					Color:        btnCfg.Color,
					ActiveIcon:   btnCfg.ActiveIcon,
					InactiveIcon: btnCfg.InactiveIcon,
					IsActive:     true,
				},
			},
		})
	}

	execCtx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()

	cmd := exec.CommandContext(execCtx, "/bin/sh", "-c", cmdStr)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		}
		return nil
	}

	if workDir != "" {
		cmd.Dir = workDir
	}

	envList := os.Environ()
	for k, v := range envMap {
		envList = append(envList, fmt.Sprintf("%s=%s", k, v))
	}
	cmd.Env = envList

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		e.sendErrorState(client, reqID, buttonID, btnCfg, -1)
		return
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		e.sendErrorState(client, reqID, buttonID, btnCfg, -1)
		return
	}

	if err := cmd.Start(); err != nil {
		e.sendErrorState(client, reqID, buttonID, btnCfg, -1)
		return
	}

	var stdoutAccum bytes.Buffer
	var streamWg sync.WaitGroup
	streamWg.Add(2)

	go func() {
		defer streamWg.Done()
		streamPipe(stdoutPipe, func(chunk []byte) {
			stdoutAccum.Write(chunk)
			if client != nil {
				_ = client.Send(&pb.Envelope{
					RequestId:   reqID,
					TimestampMs: time.Now().UnixMilli(),
					Payload: &pb.Envelope_MacroOutputEvent{
						MacroOutputEvent: &pb.MacroOutputEvent{
							ButtonId: buttonID,
							Stdout:   chunk,
						},
					},
				})
			}
		})
	}()

	go func() {
		defer streamWg.Done()
		streamPipe(stderrPipe, func(chunk []byte) {
			if client != nil {
				_ = client.Send(&pb.Envelope{
					RequestId:   reqID,
					TimestampMs: time.Now().UnixMilli(),
					Payload: &pb.Envelope_MacroOutputEvent{
						MacroOutputEvent: &pb.MacroOutputEvent{
							ButtonId: buttonID,
							Stderr:   chunk,
						},
					},
				})
			}
		})
	}()

	streamWg.Wait()
	waitErr := cmd.Wait()
	exitCode := exitCodeFromError(waitErr)

	e.mu.Lock()
	var isActive bool
	if exitCode == 0 {
		isActive = !e.toggleStates[buttonID]
		e.toggleStates[buttonID] = isActive
	}
	e.mu.Unlock()

	finalState := buildStateSync(buttonID, btnCfg, stdoutAccum.String(), exitCode, isActive)
	if client != nil {
		_ = client.Send(&pb.Envelope{
			RequestId:   reqID,
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_MacroStateSync{
				MacroStateSync: finalState,
			},
		})
	}
}

func (e *Engine) sendErrorState(client server.ClientSession, reqID, buttonID string, btnCfg ButtonConfig, code int32) {
	if client == nil {
		return
	}
	_ = client.Send(&pb.Envelope{
		RequestId:   reqID,
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_MacroStateSync{
			MacroStateSync: &pb.MacroStateSync{
				ButtonId:     buttonID,
				State:        pb.MacroRunState_ERROR,
				ExitCode:     code,
				Title:        btnCfg.Title,
				Color:        btnCfg.Color,
				ActiveIcon:   btnCfg.ActiveIcon,
				InactiveIcon: btnCfg.InactiveIcon,
				IsActive:     false,
			},
		},
	})
}

func streamPipe(r io.Reader, onChunk func([]byte)) {
	buf := make([]byte, 4096)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			onChunk(chunk)
		}
		if err != nil {
			return
		}
	}
}

func exitCodeFromError(err error) int32 {
	if err == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return int32(exitErr.ExitCode())
	}
	return -1
}

func buildStateSync(buttonID string, btnCfg ButtonConfig, stdoutText string, exitCode int32, defaultActive bool) *pb.MacroStateSync {
	runState := pb.MacroRunState_SUCCESS
	if exitCode != 0 {
		runState = pb.MacroRunState_ERROR
	}

	syncMsg := &pb.MacroStateSync{
		ButtonId:     buttonID,
		State:        runState,
		ExitCode:     exitCode,
		Title:        btnCfg.Title,
		Color:        btnCfg.Color,
		ActiveIcon:   btnCfg.ActiveIcon,
		InactiveIcon: btnCfg.InactiveIcon,
		IsActive:     defaultActive,
	}

	// Parse optional dynamic directive from script stdout (either DECK_STATE:{...} or JSON line).
	for _, line := range strings.Split(stdoutText, "\n") {
		trimmed := strings.TrimSpace(line)
		var jsonPart string
		if strings.HasPrefix(trimmed, StateDirectivePrefix) {
			jsonPart = strings.TrimSpace(strings.TrimPrefix(trimmed, StateDirectivePrefix))
		} else if strings.HasPrefix(trimmed, "{") && strings.HasSuffix(trimmed, "}") {
			jsonPart = trimmed
		}
		if jsonPart == "" {
			continue
		}
		var dir DynamicButtonDirective
		if err := json.Unmarshal([]byte(jsonPart), &dir); err == nil {
			if dir.Title != "" {
				syncMsg.Title = dir.Title
			}
			if dir.Color != "" {
				syncMsg.Color = dir.Color
			}
			if dir.ActiveIcon != "" {
				syncMsg.ActiveIcon = dir.ActiveIcon
			}
			if dir.InactiveIcon != "" {
				syncMsg.InactiveIcon = dir.InactiveIcon
			}
			if dir.IsActive != nil {
				syncMsg.IsActive = *dir.IsActive
			}
		}
	}

	return syncMsg
}

