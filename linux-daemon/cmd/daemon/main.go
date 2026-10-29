package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/local/linux-deck-daemon/internal/auth"
	"github.com/local/linux-deck-daemon/internal/discovery"
	"github.com/local/linux-deck-daemon/internal/input"
	"github.com/local/linux-deck-daemon/internal/server"
)

func main() {
	var (
		configDir   = flag.String("config-dir", "", "Configuration directory (default: ~/.config/deck-daemon)")
		host        = flag.String("host", "", "Advertised host/IP for QR code pairing")
		port        = flag.Int("port", auth.DefaultControlPort, "mTLS WebSocket control server port")
		pairingPort = flag.Int("pairing-port", auth.DefaultPairingPort, "Temporary TLS pairing server port")
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

	if *pairFlag {
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
	inputInjector := input.NewInputInjector(logger)
	defer func() {
		_ = inputInjector.Close()
	}()
	input.NewHandler(inputInjector, logger).Register(router)

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

	listenAddr, err := wsSrv.Start(ctx)
	if err != nil {
		logger.Error("failed to start mTLS WebSocket server", "err", err)
		os.Exit(1)
	}

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
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = wsSrv.Shutdown(shutdownCtx)
	logger.Info("daemon stopped cleanly")
}

