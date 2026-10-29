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
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/local/linux-deck-daemon/internal/auth"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
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


