package discovery

import (
	"context"
	"log/slog"
	"testing"
)

func TestBridgeManager_InitAndDetectIP(t *testing.T) {
	bm := NewBridgeManager(8443, 8444, 8445, slog.Default())
	if bm == nil {
		t.Fatal("expected non-nil BridgeManager")
	}

	ip := bm.detectOutboundIP()
	if ip == "" {
		t.Fatal("expected non-empty outbound IP")
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	wifiIP := bm.PrepareWiFi(ctx)
	if wifiIP == "" {
		t.Fatal("expected non-empty WiFi IP")
	}
}

