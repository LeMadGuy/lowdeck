package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/local/linux-deck-daemon/internal/auth"
	"github.com/local/linux-deck-daemon/internal/capture"
	"github.com/local/linux-deck-daemon/internal/dbus"
	"github.com/local/linux-deck-daemon/internal/discovery"
	"github.com/local/linux-deck-daemon/internal/input"
	"github.com/local/linux-deck-daemon/internal/macro"
	"github.com/local/linux-deck-daemon/internal/pty"
	"github.com/local/linux-deck-daemon/internal/server"
	"github.com/local/linux-deck-daemon/internal/telemetry"
	"github.com/local/linux-deck-daemon/internal/webrtc"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

func main() {
	var (
		configDir   = flag.String("config-dir", "", "Configuration directory (default: ~/.config/deck-daemon)")
		host        = flag.String("host", "", "Advertised host/IP for QR code pairing")
		port        = flag.Int("port", auth.DefaultControlPort, "mTLS WebSocket control server port")
		pairingPort = flag.Int("pairing-port", 8445, "Temporary TLS pairing server port")
		screenPort  = flag.Int("screen-port", capture.DefaultMjpegPort, "Continuous HTTP MJPEG screen stream port")
		pairFlag    = flag.Bool("pair", false, "Generate and display a 5-minute pairing QR Code and token in the terminal")
		printOnly   = flag.Bool("print-qr-only", false, "When combined with --pair, print QR code/token and exit immediately")
	)
	flag.Parse()

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	serverIdentity, err := auth.LoadOrCreateServerIdentity(*configDir)
	if err != nil {
		logger.Error("failed to initialize server certificate identity", "err", err)
		os.Exit(1)
	}

	trustedStore, err := auth.NewTrustedStore(*configDir)
	if err != nil {
		logger.Error("failed to initialize trusted client store", "err", err)
		os.Exit(1)
	}

	pairingMgr := auth.NewPairingManager(serverIdentity, trustedStore)

	enablePairing := *pairFlag
	if !enablePairing && len(trustedStore.ListClients()) == 0 {
		logger.Info("no trusted clients registered yet; enabling initial 5-minute pairing mode automatically")
		enablePairing = true
	}

	if enablePairing {
		payload, err := pairingMgr.GenerateSession(*host, *port, *pairingPort)
		if err != nil {
			logger.Error("failed to generate pairing session", "err", err)
			os.Exit(1)
		}

		fmt.Println("================================================================")
		fmt.Println("  LINUX DECK DAEMON — PAIRING QR CODE (Valid for 5 minutes)")
		fmt.Println("================================================================")
		if err := auth.PrintQRCode(payload); err != nil {
			logger.Error("failed to render QR code", "err", err)
			os.Exit(1)
		}
		rawJSON, _ := json.Marshal(payload)
		fmt.Printf("\nPairing JSON:  %s\n", string(rawJSON))
		fmt.Printf("Pairing Token: %s\n", payload.Token)
		fmt.Printf("Server SHA256: %s\n", payload.ServerFP)
		fmt.Printf("Expires At:    %s\n", time.Unix(payload.ExpiresAt, 0).Format(time.RFC3339))
		fmt.Printf("USB (ADB):     adb reverse tcp:%d tcp:%d && adb reverse tcp:%d tcp:%d\n", *port, *port, *pairingPort, *pairingPort)
		fmt.Printf("Wi-Fi (UFW):   sudo ufw allow %d,%d/tcp\n", *port, *pairingPort)
		fmt.Println("================================================================")

		if *printOnly {
			return
		}

		pairListenAddr := fmt.Sprintf("0.0.0.0:%d", *pairingPort)
		pairAddr, _, err := pairingMgr.StartPairingServer(ctx, pairListenAddr)
		if err != nil {
			logger.Error("failed to start pairing server", "addr", pairListenAddr, "err", err)
			os.Exit(1)
		}
		logger.Info("pairing endpoint active", "addr", pairAddr.String())
	}

	router := server.NewRouter(logger)
	// Asynchronous bounded/coalescing queue: the WebSocket read loop never blocks on /dev/uinput writes.
	inputInjector := input.NewAsyncInjector(input.NewInputInjector(logger), input.DefaultAsyncQueueCapacity, logger)
	defer func() {
		_ = inputInjector.Close()
	}()
	input.NewHandler(inputInjector, logger).Register(router)

	profileStore, err := macro.NewProfileStore(*configDir)
	if err != nil {
		logger.Error("failed to initialize macro profile store", "err", err)
		os.Exit(1)
	}
	macroEngine := macro.NewEngine(profileStore, logger)
	macroEngine.Register(router)

	ptyMgr := pty.NewManager(pty.DefaultMaxSessionsPerClient, logger)
	ptyMgr.Register(router)

	mediaCtrl := dbus.NewMediaController(logger)
	mediaCtrl.Register(router)

	powerCtrl := dbus.NewPowerController(logger)
	powerCtrl.Register(router)

	webrtcSig := webrtc.NewSignalingHandler(*configDir, nil, logger)
	webrtcSig.Register(router)

	telemetryCollector := telemetry.NewCollector(telemetry.Config{
		Interval: telemetry.DefaultInterval,
		MediaProvider: func(c context.Context) (*pb.MediaStatusEvent, error) {
			return mediaCtrl.GetStatus(c, "")
		},
		Logger: logger,
	})

	wsSrv, err := server.NewServer(server.Config{
		ListenAddr:     fmt.Sprintf("0.0.0.0:%d", *port),
		ServerIdentity: serverIdentity,
		TrustedStore:   trustedStore,
		Router:         router,
		Logger:         logger,
	})
	if err != nil {
		logger.Error("failed to create control server", "err", err)
		os.Exit(1)
	}

	wsSrv.OnConnect(func(client server.ClientSession) {
		_ = macroEngine.SendProfileToClient(client)
	})
	wsSrv.OnDisconnect(func(client server.ClientSession) {
		ptyMgr.CloseClientSessions(client.ID())
		webrtcSig.CloseClientStream(client.ID())
	})

	listenAddr, err := wsSrv.Start(ctx)
	if err != nil {
		if enablePairing && strings.Contains(strings.ToLower(err.Error()), "address already in use") {
			logger.Info("control port already served by active deck-daemon; keeping pairing server open for 5 minutes",
				"control_port", *port,
				"pairing_port", *pairingPort,
			)
			select {
			case <-ctx.Done():
			case <-time.After(auth.PairingTokenTTL):
				logger.Info("pairing window expired; exiting helper pairing process")
			}
			return
		}
		logger.Error("failed to start mTLS WebSocket server", "err", err)
		os.Exit(1)
	}

	telemetryCollector.Start(ctx, wsSrv.ClientCount, wsSrv.Broadcast)

	mdnsAdv, err := discovery.NewMdnsAdvertiser(discovery.Config{
		Port:              *port,
		ServerFingerprint: serverIdentity.Fingerprint,
		Logger:            logger,
	})
	if err != nil {
		logger.Warn("failed to initialize mDNS advertiser", "err", err)
	} else if err := mdnsAdv.Start(ctx); err != nil {
		logger.Warn("failed to start mDNS advertiser", "err", err)
	}

	mjpegSrv := capture.NewMjpegServer(fmt.Sprintf("0.0.0.0:%d", *screenPort), logger)
	webTerm := pty.NewWebTerminal(logger)
	webTerm.RegisterRoutes(mjpegSrv)
	mjpegAddr, err := mjpegSrv.Start(ctx)
	if err != nil {
		logger.Warn("failed to start continuous mjpeg screen stream server", "err", err, "port", *screenPort)
	} else {
		logger.Info("continuous mjpeg screen stream ready", "addr", mjpegAddr.String(), "path", capture.DefaultMjpegPath)
	}

	logger.Info("daemon ready",
		"control_addr", listenAddr.String(),
		"server_fp", serverIdentity.Fingerprint[:16],
		"trusted_clients", len(trustedStore.ListClients()),
	)

	<-ctx.Done()
	logger.Info("shutting down daemon...")

	if mdnsAdv != nil {
		mdnsAdv.Shutdown()
	}
	if mjpegSrv != nil {
		_ = mjpegSrv.Shutdown(context.Background())
	}
	webrtcSig.CloseAll()
	ptyMgr.CloseAll()
	macroEngine.Wait()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = wsSrv.Shutdown(shutdownCtx)
	logger.Info("daemon stopped cleanly")
}

