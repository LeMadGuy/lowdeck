package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/local/linux-deck-daemon/internal/auth"
	deckwebrtc "github.com/local/linux-deck-daemon/internal/webrtc"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	pionwebrtc "github.com/pion/webrtc/v4"
	"google.golang.org/protobuf/proto"
)

type savedServerMetadata struct {
	ServerFP string `json:"server_fp"`
	PairedAt int64  `json:"paired_at"`
}

func defaultClientDir(configDir string) (string, error) {
	if configDir == "" {
		var err error
		configDir, err = auth.DefaultConfigDir()
		if err != nil {
			return "", err
		}
	}
	return filepath.Join(configDir, "mock-client"), nil
}

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	subcmd := os.Args[1]
	switch subcmd {
	case "pair":
		if err := runPair(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "pair error: %v\n", err)
			os.Exit(1)
		}
	case "ping":
		if err := runPing(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "ping error: %v\n", err)
			os.Exit(1)
		}
	case "input":
		if err := runInput(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "input error: %v\n", err)
			os.Exit(1)
		}
	case "macro":
		if err := runMacro(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "macro error: %v\n", err)
			os.Exit(1)
		}
	case "pty":
		if err := runPty(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "pty error: %v\n", err)
			os.Exit(1)
		}
	case "telemetry":
		if err := runTelemetry(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "telemetry error: %v\n", err)
			os.Exit(1)
		}
	case "media":
		if err := runMedia(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "media error: %v\n", err)
			os.Exit(1)
		}
	case "power":
		if err := runPower(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "power error: %v\n", err)
			os.Exit(1)
		}
	case "stream-test":
		if err := runStreamTest(ctx, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "stream-test error: %v\n", err)
			os.Exit(1)
		}
	default:
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Fprintf(os.Stderr, `Usage:
  mock-client pair --token <TOKEN> [--host 127.0.0.1:8444] [--config-dir <dir>]
  mock-client ping [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client input move --dx <int> --dy <int> [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client input abs --x <0.0..1.0> --y <0.0..1.0> [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client input combo "<COMBO>" [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client macro --button-id <ID> [--cmd "<CMD>"] [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client pty [--cmd "<CMD>"] [--cols 120 --rows 40] [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client telemetry [--count 1] [--host 127.0.0.1:8443] [--config-dir <dir>]
  mock-client media --action <play-pause|next|prev|stop|mute|vol-up|vol-down> [--host 127.0.0.1:8443]
  mock-client power --action <lock|unlock|suspend|reboot|poweroff> [--host 127.0.0.1:8443]
  mock-client stream-test [--frames 10] [--width 640 --height 360 --fps 60] [--host 127.0.0.1:8443] [--config-dir <dir>]
`)
}

func runPair(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	token := fs.String("token", "", "One-time pairing token from daemon --pair")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultPairingPort), "Daemon pairing host:port")
	deviceID := fs.String("device-id", "mock-cli-01", "Device identifier")
	deviceName := fs.String("device-name", "LinuxDeck Mock CLI", "Device display name")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory (default: ~/.config/deck-daemon)")
	if err := fs.Parse(args); err != nil {
		return err
	}

	if strings.TrimSpace(*token) == "" {
		return errors.New("--token is required")
	}

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}

	clientIdentity, err := auth.LoadOrCreateClientIdentity(clientDir, *deviceName)
	if err != nil {
		return fmt.Errorf("load/create client identity in %s: %w", clientDir, err)
	}

	resp, err := auth.SendPairingRequest(ctx, *host, auth.PairingRequest{
		Token:         *token,
		DeviceID:      *deviceID,
		DeviceName:    *deviceName,
		ClientCertPEM: string(clientIdentity.CertPEM),
	}, clientIdentity, *serverFP)
	if err != nil {
		return err
	}

	metaPath := filepath.Join(clientDir, "server_meta.json")
	metaBytes, _ := json.MarshalIndent(savedServerMetadata{
		ServerFP: resp.ServerFP,
		PairedAt: resp.PairedAt,
	}, "", "  ")
	_ = os.WriteFile(metaPath, metaBytes, auth.FilePerm)

	fmt.Printf("Paired successfully with daemon!\n")
	fmt.Printf("  Client Cert: %s\n", clientIdentity.CertPath)
	fmt.Printf("  Client FP:   %s\n", resp.ClientFP)
	fmt.Printf("  Server FP:   %s\n", resp.ServerFP)
	return nil
}

func runPing(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("ping", flag.ContinueOnError)
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory (default: ~/.config/deck-daemon)")
	if err := fs.Parse(args); err != nil {
		return err
	}

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}

	conn, err := dialAuthenticatedWebSocket(ctx, *host, clientDir, *serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	start := time.Now()
	nowMs := start.UnixMilli()
	reqID := fmt.Sprintf("ping-%d", start.UnixNano())

	pingEnv := &pb.Envelope{
		RequestId:   reqID,
		TimestampMs: nowMs,
		Payload: &pb.Envelope_Ping{
			Ping: &pb.Ping{
				Seq:               1,
				ClientTimestampMs: nowMs,
			},
		},
	}

	raw, err := proto.Marshal(pingEnv)
	if err != nil {
		return fmt.Errorf("marshal ping envelope: %w", err)
	}

	if err := conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, raw); err != nil {
		return fmt.Errorf("send binary ping: %w", err)
	}

	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}

	for {
		msgType, respBytes, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read pong response: %w", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}

		var respEnv pb.Envelope
		if err := proto.Unmarshal(respBytes, &respEnv); err != nil {
			return fmt.Errorf("unmarshal response envelope: %w", err)
		}

		if pong := respEnv.GetPong(); pong != nil {
			rtt := time.Since(start)
			fmt.Printf("PONG received (seq=%d, request_id=%s, rtt=%s / %.2f ms)\n",
				pong.Seq, respEnv.RequestId, rtt.Round(time.Microsecond), float64(rtt.Microseconds())/1000.0)
			return nil
		}
	}
}

func dialAuthenticatedWebSocket(ctx context.Context, host, clientDir, expectedServerFP string) (*websocket.Conn, error) {
	clientIdentity, err := auth.LoadOrCreateClientIdentity(clientDir, "LinuxDeck Mock CLI")
	if err != nil {
		return nil, fmt.Errorf("load client certificate from %s: %w", clientDir, err)
	}

	if expectedServerFP == "" {
		metaPath := filepath.Join(clientDir, "server_meta.json")
		if data, err := os.ReadFile(metaPath); err == nil {
			var meta savedServerMetadata
			if json.Unmarshal(data, &meta) == nil {
				expectedServerFP = meta.ServerFP
			}
		}
	}

	tlsCfg, err := auth.ClientTLSConfig(clientIdentity, expectedServerFP)
	if err != nil {
		return nil, err
	}

	wsURL := host
	if !strings.HasPrefix(wsURL, "wss://") && !strings.HasPrefix(wsURL, "ws://") {
		wsURL = "wss://" + strings.TrimSuffix(wsURL, "/") + "/ws"
	}

	dialer := websocket.Dialer{
		TLSClientConfig:  tlsCfg,
		HandshakeTimeout: 5 * time.Second,
	}

	conn, resp, err := dialer.DialContext(ctx, wsURL, http.Header{})
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("websocket dial %s failed (HTTP %d): %w", wsURL, resp.StatusCode, err)
		}
		return nil, fmt.Errorf("websocket dial %s failed: %w", wsURL, err)
	}
	return conn, nil
}

func runInput(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New("missing input subcommand (expected: move, abs, combo)")
	}

	action := args[0]
	rest := args[1:]

	switch action {
	case "move":
		fs := flag.NewFlagSet("input move", flag.ContinueOnError)
		dx := fs.Int("dx", 0, "Relative X delta in pixels")
		dy := fs.Int("dy", 0, "Relative Y delta in pixels")
		host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
		serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
		configDir := fs.String("config-dir", "", "Base config directory")
		if err := fs.Parse(rest); err != nil {
			return err
		}
		env := &pb.Envelope{
			RequestId:   fmt.Sprintf("input-move-%d", time.Now().UnixNano()),
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_MouseMoveEvent{
				MouseMoveEvent: &pb.MouseMoveEvent{
					Dx:         int32(*dx),
					Dy:         int32(*dy),
					IsAbsolute: false,
				},
			},
		}
		if err := sendInputEnvelope(ctx, *host, *configDir, *serverFP, env); err != nil {
			return err
		}
		fmt.Printf("Sent relative MouseMoveEvent (dx=%d, dy=%d)\n", *dx, *dy)
		return nil

	case "abs":
		fs := flag.NewFlagSet("input abs", flag.ContinueOnError)
		normX := fs.Float64("x", 0.5, "Normalized X coordinate (0.0..1.0)")
		normY := fs.Float64("y", 0.5, "Normalized Y coordinate (0.0..1.0)")
		host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
		serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
		configDir := fs.String("config-dir", "", "Base config directory")
		if err := fs.Parse(rest); err != nil {
			return err
		}
		env := &pb.Envelope{
			RequestId:   fmt.Sprintf("input-abs-%d", time.Now().UnixNano()),
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_MouseMoveEvent{
				MouseMoveEvent: &pb.MouseMoveEvent{
					NormX:      float32(*normX),
					NormY:      float32(*normY),
					IsAbsolute: true,
				},
			},
		}
		if err := sendInputEnvelope(ctx, *host, *configDir, *serverFP, env); err != nil {
			return err
		}
		fmt.Printf("Sent absolute MouseMoveEvent (x=%.3f, y=%.3f)\n", *normX, *normY)
		return nil

	case "combo":
		// Support positional combo argument before or after flags: e.g. `mock-client input combo "ctrl+alt+t" --host ...`
		var positionalCombo string
		var flagArgs []string
		for i := 0; i < len(rest); i++ {
			arg := rest[i]
			if strings.HasPrefix(arg, "-") {
				flagArgs = append(flagArgs, arg)
				if !strings.Contains(arg, "=") && i+1 < len(rest) {
					flagArgs = append(flagArgs, rest[i+1])
					i++
				}
			} else if positionalCombo == "" {
				positionalCombo = arg
			}
		}

		fs := flag.NewFlagSet("input combo", flag.ContinueOnError)
		comboFlag := fs.String("combo", "", "Key combination string (e.g. ctrl+alt+t)")
		host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
		serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
		configDir := fs.String("config-dir", "", "Base config directory")
		if err := fs.Parse(flagArgs); err != nil {
			return err
		}

		combo := strings.TrimSpace(*comboFlag)
		if combo == "" {
			combo = strings.TrimSpace(positionalCombo)
		}
		if combo == "" {
			return errors.New("combo expression is required (e.g. mock-client input combo \"ctrl+alt+t\")")
		}

		env := &pb.Envelope{
			RequestId:   fmt.Sprintf("input-combo-%d", time.Now().UnixNano()),
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_KeyComboEvent{
				KeyComboEvent: &pb.KeyComboEvent{
					Combo: combo,
				},
			},
		}
		if err := sendInputEnvelope(ctx, *host, *configDir, *serverFP, env); err != nil {
			return err
		}
		fmt.Printf("Sent KeyComboEvent (%q)\n", combo)
		return nil

	default:
		return fmt.Errorf("unknown input subcommand %q (expected: move, abs, combo)", action)
	}
}

func sendInputEnvelope(ctx context.Context, host, configDir, serverFP string, env *pb.Envelope) error {
	clientDir, err := defaultClientDir(configDir)
	if err != nil {
		return err
	}
	conn, err := dialAuthenticatedWebSocket(ctx, host, clientDir, serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	raw, err := proto.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal input envelope: %w", err)
	}
	if err := conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	return conn.WriteMessage(websocket.BinaryMessage, raw)
}

func runMacro(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("macro", flag.ContinueOnError)
	buttonID := fs.String("button-id", "cmd-uptime", "Target button ID in Deck profile")
	cmdStr := fs.String("cmd", "", "Optional command override")
	timeoutSec := fs.Uint("timeout", 15, "Command execution timeout in seconds")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}
	conn, err := dialAuthenticatedWebSocket(ctx, *host, clientDir, *serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	reqID := fmt.Sprintf("macro-%d", time.Now().UnixNano())
	env := &pb.Envelope{
		RequestId:   reqID,
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_MacroExecRequest{
			MacroExecRequest: &pb.MacroExecRequest{
				ButtonId:       *buttonID,
				Command:        *cmdStr,
				TimeoutSeconds: uint32(*timeoutSec),
			},
		},
	}

	raw, err := proto.Marshal(env)
	if err != nil {
		return err
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, raw); err != nil {
		return err
	}

	_ = conn.SetReadDeadline(time.Now().Add(time.Duration(*timeoutSec+5) * time.Second))
	for {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read macro stream: %w", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		var resp pb.Envelope
		if err := proto.Unmarshal(data, &resp); err != nil {
			continue
		}
		if out := resp.GetMacroOutputEvent(); out != nil && out.ButtonId == *buttonID {
			if len(out.Stdout) > 0 {
				_, _ = os.Stdout.Write(out.Stdout)
			}
			if len(out.Stderr) > 0 {
				_, _ = os.Stderr.Write(out.Stderr)
			}
		}
		if st := resp.GetMacroStateSync(); st != nil && st.ButtonId == *buttonID {
			if st.State == pb.MacroRunState_SUCCESS || st.State == pb.MacroRunState_ERROR {
				fmt.Printf("\n[MacroStateSync] button=%s state=%s exit_code=%d title=%q active=%v\n",
					st.ButtonId, st.State.String(), st.ExitCode, st.Title, st.IsActive)
				return nil
			}
		}
		if errResp := resp.GetErrorResponse(); errResp != nil && errResp.RefRequestId == reqID {
			return fmt.Errorf("daemon error (%d): %s", errResp.Code, errResp.Message)
		}
	}
}

func runPty(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("pty", flag.ContinueOnError)
	sessionID := fs.String("session-id", fmt.Sprintf("cli-pty-%d", time.Now().Unix()), "PTY session identifier")
	cmdStr := fs.String("cmd", `echo "HELLO_DECK"`, "Command to execute inside PTY session")
	cols := fs.Uint("cols", 120, "Terminal columns")
	rows := fs.Uint("rows", 40, "Terminal rows")
	shell := fs.String("shell", "/bin/sh", "Shell binary to launch")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}
	conn, err := dialAuthenticatedWebSocket(ctx, *host, clientDir, *serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	sendEnv := func(e *pb.Envelope) error {
		b, mErr := proto.Marshal(e)
		if mErr != nil {
			return mErr
		}
		return conn.WriteMessage(websocket.BinaryMessage, b)
	}

	// 1. Open PTY session
	if err := sendEnv(&pb.Envelope{
		RequestId:   "pty-open",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_PtyOpenRequest{
			PtyOpenRequest: &pb.PtyOpenRequest{
				SessionId: *sessionID,
				Cols:      uint32(*cols),
				Rows:      uint32(*rows),
				Shell:     *shell,
			},
		},
	}); err != nil {
		return fmt.Errorf("send PtyOpenRequest: %w", err)
	}

	// 2. Resize PTY session
	if err := sendEnv(&pb.Envelope{
		RequestId:   "pty-resize",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_PtyResizeEvent{
			PtyResizeEvent: &pb.PtyResizeEvent{
				SessionId: *sessionID,
				Cols:      uint32(*cols),
				Rows:      uint32(*rows),
			},
		},
	}); err != nil {
		return fmt.Errorf("send PtyResizeEvent: %w", err)
	}

	// 3. Send command followed by exit
	inputLine := fmt.Sprintf("%s\nexit\n", *cmdStr)
	if err := sendEnv(&pb.Envelope{
		RequestId:   "pty-data",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_PtyDataEvent{
			PtyDataEvent: &pb.PtyDataEvent{
				SessionId: *sessionID,
				Data:      []byte(inputLine),
			},
		},
	}); err != nil {
		return fmt.Errorf("send PtyDataEvent: %w", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			break
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		var resp pb.Envelope
		if err := proto.Unmarshal(data, &resp); err != nil {
			continue
		}
		if d := resp.GetPtyDataEvent(); d != nil && d.SessionId == *sessionID {
			_, _ = os.Stdout.Write(d.Data)
		}
		if c := resp.GetPtyCloseEvent(); c != nil && c.SessionId == *sessionID {
			return nil
		}
	}
	return nil
}

func runTelemetry(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("telemetry", flag.ContinueOnError)
	count := fs.Int("count", 1, "Number of TelemetrySnapshots to receive before exiting")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}
	conn, err := dialAuthenticatedWebSocket(ctx, *host, clientDir, *serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	received := 0
	_ = conn.SetReadDeadline(time.Now().Add(time.Duration(*count+5) * time.Second))
	for received < *count {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("read telemetry snapshot: %w", err)
		}
		if msgType != websocket.BinaryMessage {
			continue
		}
		var env pb.Envelope
		if err := proto.Unmarshal(data, &env); err != nil {
			continue
		}
		if snap := env.GetTelemetrySnapshot(); snap != nil {
			received++
			fmt.Printf("[Telemetry #%d] CPU: %.1f%% | RAM: %d/%d MB | Net RX: %.1f kbps TX: %.1f kbps | Temps: %v\n",
				received, snap.CpuPercent, snap.RamUsedMb, snap.RamTotalMb, snap.RxKbps, snap.TxKbps, snap.Temperatures)
		}
	}
	return nil
}

func runMedia(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("media", flag.ContinueOnError)
	action := fs.String("action", "play-pause", "Media command (play-pause, play, pause, next, prev, stop, mute, vol-up, vol-down)")
	player := fs.String("player", "", "Optional MPRIS2 player name")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	var cmdType pb.MediaCommandType
	switch strings.ToLower(strings.TrimSpace(*action)) {
	case "play-pause", "play_pause", "playpause":
		cmdType = pb.MediaCommandType_PLAY_PAUSE
	case "play":
		cmdType = pb.MediaCommandType_PLAY
	case "pause":
		cmdType = pb.MediaCommandType_PAUSE
	case "next":
		cmdType = pb.MediaCommandType_NEXT
	case "prev", "previous":
		cmdType = pb.MediaCommandType_PREVIOUS
	case "stop":
		cmdType = pb.MediaCommandType_STOP
	case "vol-up", "volume_up", "volup":
		cmdType = pb.MediaCommandType_VOLUME_UP
	case "vol-down", "volume_down", "voldown":
		cmdType = pb.MediaCommandType_VOLUME_DOWN
	case "mute":
		cmdType = pb.MediaCommandType_MUTE
	default:
		return fmt.Errorf("unknown media action %q", *action)
	}

	env := &pb.Envelope{
		RequestId:   fmt.Sprintf("media-%d", time.Now().UnixNano()),
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_MediaControlCommand{
			MediaControlCommand: &pb.MediaControlCommand{
				Command: cmdType,
				Player:  *player,
			},
		},
	}
	return sendInputEnvelope(ctx, *host, *configDir, *serverFP, env)
}

func runPower(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("power", flag.ContinueOnError)
	action := fs.String("action", "lock", "Power action (lock, unlock, suspend, reboot, poweroff)")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	var powerAct pb.PowerAction
	switch strings.ToLower(strings.TrimSpace(*action)) {
	case "lock":
		powerAct = pb.PowerAction_LOCK
	case "unlock":
		powerAct = pb.PowerAction_UNLOCK
	case "suspend":
		powerAct = pb.PowerAction_SUSPEND
	case "reboot":
		powerAct = pb.PowerAction_REBOOT
	case "poweroff":
		powerAct = pb.PowerAction_POWEROFF
	default:
		return fmt.Errorf("unknown power action %q", *action)
	}

	env := &pb.Envelope{
		RequestId:   fmt.Sprintf("power-%d", time.Now().UnixNano()),
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_PowerControlCommand{
			PowerControlCommand: &pb.PowerControlCommand{
				Action: powerAct,
			},
		},
	}
	return sendInputEnvelope(ctx, *host, *configDir, *serverFP, env)
}

func runStreamTest(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("stream-test", flag.ContinueOnError)
	targetFrames := fs.Int("frames", 10, "Number of H.264 frames to receive before completing")
	width := fs.Uint("width", 640, "Requested video width")
	height := fs.Uint("height", 360, "Requested video height")
	fps := fs.Uint("fps", 60, "Requested video framerate")
	bitrate := fs.Uint("bitrate", 2000, "Requested video bitrate in kbps")
	audio := fs.Bool("audio", true, "Enable Opus audio track")
	timeout := fs.Duration("timeout", 10*time.Second, "Maximum duration to wait for WebRTC stream")
	host := fs.String("host", fmt.Sprintf("127.0.0.1:%d", auth.DefaultControlPort), "Daemon control host:port")
	serverFP := fs.String("server-fp", "", "Optional expected server SHA-256 fingerprint")
	configDir := fs.String("config-dir", "", "Base config directory")
	if err := fs.Parse(args); err != nil {
		return err
	}

	streamCtx, cancel := context.WithTimeout(ctx, *timeout)
	defer cancel()

	clientDir, err := defaultClientDir(*configDir)
	if err != nil {
		return err
	}
	conn, err := dialAuthenticatedWebSocket(streamCtx, *host, clientDir, *serverFP)
	if err != nil {
		return err
	}
	defer conn.Close()

	var writeMu sync.Mutex
	sendSignal := func(sig *pb.WebRtcSignal) error {
		env := &pb.Envelope{
			RequestId:   fmt.Sprintf("webrtc-%d", time.Now().UnixNano()),
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_WebrtcSignal{
				WebrtcSignal: sig,
			},
		}
		raw, mErr := proto.Marshal(env)
		if mErr != nil {
			return mErr
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		return conn.WriteMessage(websocket.BinaryMessage, raw)
	}

	api, err := deckwebrtc.NewLocalWebRTCAPI()
	if err != nil {
		return err
	}
	pc, err := api.NewPeerConnection(pionwebrtc.Configuration{
		ICEServers: []pionwebrtc.ICEServer{},
	})
	if err != nil {
		return fmt.Errorf("create client PeerConnection: %w", err)
	}
	defer func() { _ = pc.Close() }()

	var (
		h264Frames   atomic.Int32
		h264Packets  atomic.Int32
		videoBytes   atomic.Uint64
		opusPackets  atomic.Int32
		doneCh       = make(chan struct{})
		doneOnce     sync.Once
		remoteSetMu  sync.Mutex
		remoteSet    bool
		queuedRemote []pionwebrtc.ICECandidateInit
	)

	pc.OnICECandidate(func(c *pionwebrtc.ICECandidate) {
		if c == nil {
			return
		}
		raw, mErr := json.Marshal(c.ToJSON())
		if mErr != nil {
			return
		}
		_ = sendSignal(&pb.WebRtcSignal{
			Type:        pb.SignalType_ICE_CANDIDATE,
			PayloadJson: string(raw),
		})
	})

	pc.OnTrack(func(track *pionwebrtc.TrackRemote, _ *pionwebrtc.RTPReceiver) {
		mime := strings.ToLower(track.Codec().MimeType)
		for {
			pkt, _, rErr := track.ReadRTP()
			if rErr != nil {
				return
			}
			if strings.Contains(mime, "h264") && len(pkt.Payload) > 0 {
				h264Packets.Add(1)
				videoBytes.Add(uint64(len(pkt.Payload)))
				if pkt.Header.Marker {
					if int(h264Frames.Add(1)) >= *targetFrames {
						doneOnce.Do(func() { close(doneCh) })
					}
				}
			} else if strings.Contains(mime, "opus") && len(pkt.Payload) > 0 {
				opusPackets.Add(1)
			}
		}
	})

	wsErrCh := make(chan error, 1)
	go func() {
		for {
			msgType, data, rErr := conn.ReadMessage()
			if rErr != nil {
				wsErrCh <- rErr
				return
			}
			if msgType != websocket.BinaryMessage {
				continue
			}
			var env pb.Envelope
			if proto.Unmarshal(data, &env) != nil {
				continue
			}
			if errResp := env.GetErrorResponse(); errResp != nil {
				wsErrCh <- fmt.Errorf("daemon error (%d): %s", errResp.Code, errResp.Message)
				return
			}
			sig := env.GetWebrtcSignal()
			if sig == nil {
				continue
			}

			switch sig.Type {
			case pb.SignalType_SDP_OFFER:
				offer, pErr := deckwebrtc.ParseSessionDescriptionJSON(sig.PayloadJson, pionwebrtc.SDPTypeOffer)
				if pErr != nil {
					wsErrCh <- fmt.Errorf("parse sdp_offer: %w", pErr)
					return
				}
				if sErr := pc.SetRemoteDescription(offer); sErr != nil {
					wsErrCh <- fmt.Errorf("SetRemoteDescription: %w", sErr)
					return
				}
				answer, aErr := pc.CreateAnswer(nil)
				if aErr != nil {
					wsErrCh <- fmt.Errorf("CreateAnswer: %w", aErr)
					return
				}
				if lErr := pc.SetLocalDescription(answer); lErr != nil {
					wsErrCh <- fmt.Errorf("SetLocalDescription: %w", lErr)
					return
				}

				remoteSetMu.Lock()
				remoteSet = true
				pending := queuedRemote
				queuedRemote = nil
				remoteSetMu.Unlock()

				for _, ic := range pending {
					_ = pc.AddICECandidate(ic)
				}

				ansBytes, _ := json.Marshal(answer)
				if sErr := sendSignal(&pb.WebRtcSignal{
					Type:        pb.SignalType_SDP_ANSWER,
					PayloadJson: string(ansBytes),
				}); sErr != nil {
					wsErrCh <- fmt.Errorf("send sdp_answer: %w", sErr)
					return
				}

			case pb.SignalType_ICE_CANDIDATE:
				cand, pErr := deckwebrtc.ParseICECandidateJSON(sig.PayloadJson)
				if pErr != nil || cand.Candidate == "" {
					continue
				}
				remoteSetMu.Lock()
				if !remoteSet {
					queuedRemote = append(queuedRemote, cand)
					remoteSetMu.Unlock()
					continue
				}
				remoteSetMu.Unlock()
				_ = pc.AddICECandidate(cand)
			}
		}
	}()

	start := time.Now()
	if err := sendSignal(&pb.WebRtcSignal{
		Type:         pb.SignalType_START_STREAM,
		Width:        uint32(*width),
		Height:       uint32(*height),
		Fps:          uint32(*fps),
		BitrateKbps:  uint32(*bitrate),
		AudioEnabled: *audio,
	}); err != nil {
		return fmt.Errorf("send START_STREAM: %w", err)
	}

	select {
	case <-doneCh:
		elapsed := time.Since(start).Round(time.Millisecond)
		_ = sendSignal(&pb.WebRtcSignal{Type: pb.SignalType_STOP_STREAM})
		fmt.Printf("[WebRTC Stream OK] h264_frames=%d rtp_packets=%d video_bytes=%d opus_packets=%d elapsed=%s\n",
			h264Frames.Load(), h264Packets.Load(), videoBytes.Load(), opusPackets.Load(), elapsed)
		return nil
	case err := <-wsErrCh:
		return fmt.Errorf("webrtc signaling stream failed: %w", err)
	case <-streamCtx.Done():
		return fmt.Errorf("stream-test timed out after %s (received %d/%d H.264 frames)",
			*timeout, h264Frames.Load(), *targetFrames)
	}
}





