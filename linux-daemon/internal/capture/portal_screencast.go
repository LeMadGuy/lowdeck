package capture

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"

	godbus "github.com/godbus/dbus/v5"
	"github.com/local/linux-deck-daemon/internal/auth"
)

const (
	portalDest          = "org.freedesktop.portal.Desktop"
	portalObjectPath    = "/org/freedesktop/portal/desktop"
	screenCastInterface = "org.freedesktop.portal.ScreenCast"
	requestInterface    = "org.freedesktop.portal.Request"
	sessionInterface    = "org.freedesktop.portal.Session"

	// SourceTypeMonitor selects physical/virtual monitor capture in xdg-desktop-portal.
	SourceTypeMonitor uint32 = 1
	// CursorModeEmbedded embeds the cursor directly into the PipeWire video frames.
	CursorModeEmbedded uint32 = 2
	// PersistModePermanent saves a restore token until explicitly revoked by the user.
	PersistModePermanent uint32 = 2
)

// PortalStateMachineState tracks the lifecycle stages of the Wayland ScreenCast portal handshake.
type PortalStateMachineState string

const (
	PortalStateUninitialized   PortalStateMachineState = "UNINITIALIZED"
	PortalStateConnecting      PortalStateMachineState = "CONNECTING"
	PortalStateSessionCreated  PortalStateMachineState = "SESSION_CREATED"
	PortalStateSourcesSelected PortalStateMachineState = "SOURCES_SELECTED"
	PortalStateStarted         PortalStateMachineState = "STARTED"
	PortalStatePipeWireOpened  PortalStateMachineState = "PIPEWIRE_OPENED"
	PortalStateActive          PortalStateMachineState = "ACTIVE"
	PortalStateFailed          PortalStateMachineState = "FAILED"
	PortalStateClosed          PortalStateMachineState = "CLOSED"
)

// PipeWirePortalSession holds an active Wayland ScreenCast portal session and PipeWire remote FD.
type PipeWirePortalSession struct {
	NodeID        uint32
	PipeWireFile  *os.File
	SessionHandle godbus.ObjectPath
	RestoreToken  string
	conn          *godbus.Conn
	state         PortalStateMachineState
	closeOnce     sync.Once
	logger        *slog.Logger
	configDir     string
}

// State returns the current portal lifecycle state.
func (s *PipeWirePortalSession) State() PortalStateMachineState {
	if s == nil {
		return PortalStateClosed
	}
	return s.state
}

// Close releases the PipeWire file descriptor, closes the portal session, and disconnects D-Bus.
func (s *PipeWirePortalSession) Close() error {
	if s == nil {
		return nil
	}
	s.closeOnce.Do(func() {
		s.state = PortalStateClosed
		if s.PipeWireFile != nil {
			_ = s.PipeWireFile.Close()
			s.PipeWireFile = nil
		}
		if s.conn != nil {
			if s.SessionHandle != "" {
				obj := s.conn.Object(portalDest, s.SessionHandle)
				_ = obj.Call(sessionInterface+".Close", 0).Err
				s.SessionHandle = ""
			}
			_ = s.conn.Close()
			s.conn = nil
		}
	})
	return nil
}

// IsWaylandSession returns true if the current desktop session is Wayland.
func IsWaylandSession() bool {
	if strings.EqualFold(strings.TrimSpace(os.Getenv("XDG_SESSION_TYPE")), "wayland") {
		return true
	}
	return strings.TrimSpace(os.Getenv("WAYLAND_DISPLAY")) != ""
}

// NegotiateWaylandScreenCast performs the full XDG Desktop Portal ScreenCast D-Bus handshake:
// CreateSession -> SelectSources (Monitor, Embedded Cursor, persist_mode=2) -> Start -> OpenPipeWireRemote.
func NegotiateWaylandScreenCast(ctx context.Context, configDir string, logger *slog.Logger) (*PipeWirePortalSession, error) {
	if logger == nil {
		logger = slog.Default()
	}
	if configDir == "" {
		if defDir, err := auth.DefaultConfigDir(); err == nil {
			configDir = defDir
		}
	}

	conn, err := godbus.ConnectSessionBus()
	if err != nil {
		return nil, fmt.Errorf("portal screencast connect session bus: %w", err)
	}

	sess := &PipeWirePortalSession{
		conn:      conn,
		state:     PortalStateConnecting,
		logger:    logger,
		configDir: configDir,
	}

	var success bool
	defer func() {
		if !success {
			sess.state = PortalStateFailed
			_ = sess.Close()
		}
	}()

	senderName := sanitizeSenderName(conn.Names()[0])
	portalObj := conn.Object(portalDest, godbus.ObjectPath(portalObjectPath))

	// Step 1: CreateSession
	createToken := randomPortalToken("deck_req")
	sessToken := randomPortalToken("deck_sess")
	createResults, err := callPortalRequest(ctx, conn, senderName, createToken, func() error {
		opts := map[string]godbus.Variant{
			"handle_token":         godbus.MakeVariant(createToken),
			"session_handle_token": godbus.MakeVariant(sessToken),
		}
		var reqPath godbus.ObjectPath
		return portalObj.CallWithContext(ctx, screenCastInterface+".CreateSession", 0, opts).Store(&reqPath)
	})
	if err != nil {
		return nil, fmt.Errorf("portal CreateSession: %w", err)
	}

	sessionHandle, err := extractSessionHandle(createResults, senderName, sessToken)
	if err != nil {
		return nil, err
	}
	sess.SessionHandle = sessionHandle
	sess.state = PortalStateSessionCreated

	// Step 2: SelectSources (Monitor, embedded cursor, persist_mode = 2 + optional restore_token)
	selectToken := randomPortalToken("deck_sel")
	savedRestoreToken := loadRestoreToken(configDir)

	selectSourcesFn := func(useRestoreToken string) (map[string]godbus.Variant, error) {
		return callPortalRequest(ctx, conn, senderName, selectToken, func() error {
			opts := map[string]godbus.Variant{
				"handle_token": godbus.MakeVariant(selectToken),
				"types":        godbus.MakeVariant(SourceTypeMonitor),
				"multiple":     godbus.MakeVariant(false),
				"cursor_mode":  godbus.MakeVariant(CursorModeEmbedded),
				"persist_mode": godbus.MakeVariant(PersistModePermanent),
			}
			if useRestoreToken != "" {
				opts["restore_token"] = godbus.MakeVariant(useRestoreToken)
			}
			var reqPath godbus.ObjectPath
			return portalObj.CallWithContext(ctx, screenCastInterface+".SelectSources", 0, sessionHandle, opts).Store(&reqPath)
		})
	}

	_, err = selectSourcesFn(savedRestoreToken)
	if err != nil && savedRestoreToken != "" {
		// If saved restore token was rejected or expired, clear it and retry prompt cleanly
		logger.Warn("saved screencast restore_token rejected; clearing and retrying interactive source selection", "err", err)
		clearRestoreToken(configDir)
		selectToken = randomPortalToken("deck_sel_retry")
		_, err = selectSourcesFn("")
	}
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "denied") || strings.Contains(strings.ToLower(err.Error()), "cancelled") {
			clearRestoreToken(configDir)
		}
		return nil, fmt.Errorf("portal SelectSources: %w", err)
	}
	sess.state = PortalStateSourcesSelected

	// Step 3: Start
	startToken := randomPortalToken("deck_start")
	startResults, err := callPortalRequest(ctx, conn, senderName, startToken, func() error {
		opts := map[string]godbus.Variant{
			"handle_token": godbus.MakeVariant(startToken),
		}
		var reqPath godbus.ObjectPath
		return portalObj.CallWithContext(ctx, screenCastInterface+".Start", 0, sessionHandle, "", opts).Store(&reqPath)
	})
	if err != nil {
		return nil, fmt.Errorf("portal Start: %w", err)
	}
	sess.state = PortalStateStarted

	nodeID, err := extractPipeWireNodeID(startResults)
	if err != nil {
		return nil, err
	}
	sess.NodeID = nodeID

	var newRestoreToken string
	if tokVar, ok := startResults["restore_token"]; ok {
		if tokStr, ok := tokVar.Value().(string); ok && tokStr != "" {
			newRestoreToken = tokStr
			saveRestoreToken(configDir, tokStr)
		}
	}
	sess.RestoreToken = newRestoreToken

	// Step 4: OpenPipeWireRemote
	var pwUnixFD godbus.UnixFD
	emptyOpts := map[string]godbus.Variant{}
	if err := portalObj.CallWithContext(ctx, screenCastInterface+".OpenPipeWireRemote", 0, sessionHandle, emptyOpts).Store(&pwUnixFD); err != nil {
		return nil, fmt.Errorf("portal OpenPipeWireRemote: %w", err)
	}

	dupFD, err := syscall.Dup(int(pwUnixFD))
	_ = syscall.Close(int(pwUnixFD))
	if err != nil {
		return nil, fmt.Errorf("portal dup pipewire fd: %w", err)
	}
	pwFile := os.NewFile(uintptr(dupFD), "pipewire-remote")
	if pwFile == nil {
		_ = syscall.Close(dupFD)
		return nil, errors.New("portal create os.File from pipewire fd failed")
	}
	sess.PipeWireFile = pwFile
	sess.state = PortalStatePipeWireOpened

	success = true
	sess.state = PortalStateActive
	logger.Info("wayland portal screencast negotiated",
		"pipewire_node_id", nodeID,
		"restored", savedRestoreToken != "",
	)

	return sess, nil
}

func callPortalRequest(
	ctx context.Context,
	conn *godbus.Conn,
	senderName string,
	handleToken string,
	invoke func() error,
) (map[string]godbus.Variant, error) {
	expectedPath := godbus.ObjectPath(fmt.Sprintf("/org/freedesktop/portal/desktop/request/%s/%s", senderName, handleToken))

	matchOpts := []godbus.MatchOption{
		godbus.WithMatchInterface(requestInterface),
		godbus.WithMatchMember("Response"),
		godbus.WithMatchObjectPath(expectedPath),
	}
	if err := conn.AddMatchSignalContext(ctx, matchOpts...); err != nil {
		return nil, fmt.Errorf("add match signal: %w", err)
	}
	defer func() {
		_ = conn.RemoveMatchSignal(matchOpts...)
	}()

	sigCh := make(chan *godbus.Signal, 4)
	conn.Signal(sigCh)
	defer conn.RemoveSignal(sigCh)

	if err := invoke(); err != nil {
		return nil, err
	}

	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case sig := <-sigCh:
			if sig == nil || sig.Path != expectedPath || sig.Name != requestInterface+".Response" {
				continue
			}
			if len(sig.Body) < 2 {
				return nil, errors.New("malformed portal Response signal body")
			}
			respCode, ok := sig.Body[0].(uint32)
			if !ok {
				return nil, errors.New("unexpected portal response code type")
			}
			if respCode != 0 {
				return nil, fmt.Errorf("portal request denied or cancelled (response=%d)", respCode)
			}
			results, _ := sig.Body[1].(map[string]godbus.Variant)
			return results, nil
		}
	}
}

func extractSessionHandle(results map[string]godbus.Variant, senderName, sessToken string) (godbus.ObjectPath, error) {
	if v, ok := results["session_handle"]; ok {
		switch s := v.Value().(type) {
		case string:
			if s != "" {
				return godbus.ObjectPath(s), nil
			}
		case godbus.ObjectPath:
			if s != "" {
				return s, nil
			}
		}
	}
	return godbus.ObjectPath(fmt.Sprintf("/org/freedesktop/portal/desktop/session/%s/%s", senderName, sessToken)), nil
}

func extractPipeWireNodeID(results map[string]godbus.Variant) (uint32, error) {
	streamsVar, ok := results["streams"]
	if !ok {
		return 0, errors.New("portal Start results missing 'streams'")
	}

	// go-dbus unmarshals a(ua{sv}) into [][]any or []struct{...}.
	switch s := streamsVar.Value().(type) {
	case [][]any:
		if len(s) > 0 && len(s[0]) > 0 {
			if id, ok := s[0][0].(uint32); ok {
				return id, nil
			}
		}
	}
	return 0, fmt.Errorf("portal unable to parse pipewire node id from streams (%T)", streamsVar.Value())
}

func sanitizeSenderName(uniqueName string) string {
	s := strings.TrimPrefix(uniqueName, ":")
	return strings.ReplaceAll(s, ".", "_")
}

func randomPortalToken(prefix string) string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return prefix + "_" + hex.EncodeToString(b[:])
}

func loadRestoreToken(configDir string) string {
	if configDir == "" {
		return ""
	}
	b, err := os.ReadFile(filepath.Join(configDir, "screencast_restore_token"))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func saveRestoreToken(configDir, token string) {
	if configDir == "" || token == "" {
		return
	}
	_ = os.MkdirAll(configDir, auth.DirPerm)
	_ = os.WriteFile(filepath.Join(configDir, "screencast_restore_token"), []byte(token), auth.FilePerm)
}

func clearRestoreToken(configDir string) {
	if configDir == "" {
		return
	}
	_ = os.Remove(filepath.Join(configDir, "screencast_restore_token"))
}
