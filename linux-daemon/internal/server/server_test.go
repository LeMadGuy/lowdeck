package server

import (
	"context"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/local/linux-deck-daemon/internal/auth"
	"github.com/local/linux-deck-daemon/internal/discovery"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	"google.golang.org/protobuf/proto"
)

func TestWebSocketServerPairAndPingPongIntegration(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// 1. Initialize server identity, trusted store, and pairing manager.
	serverIdentity, err := auth.LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity: %v", err)
	}
	store, err := auth.NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("NewTrustedStore: %v", err)
	}
	pairingMgr := auth.NewPairingManager(serverIdentity, store)

	// Verify mDNS TXT records construction.
	mdnsAdv, err := discovery.NewMdnsAdvertiser(discovery.Config{
		Hostname:          "arch-workstation",
		Port:              8443,
		ServerFingerprint: serverIdentity.Fingerprint,
	})
	if err != nil {
		t.Fatalf("NewMdnsAdvertiser: %v", err)
	}
	txt := mdnsAdv.TXTRecords()
	if len(txt) != 3 || txt[0] != "version=1" || txt[1] != "hostname=arch-workstation" || txt[2] != "fp="+serverIdentity.Fingerprint[:16] {
		t.Fatalf("unexpected mDNS TXT records: %v", txt)
	}

	// 2. Start pairing server on random port and generate one-time token.
	pairAddr, shutdownPair, err := pairingMgr.StartPairingServer(ctx, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("StartPairingServer: %v", err)
	}
	defer func() { _ = shutdownPair(context.Background()) }()

	payload, err := pairingMgr.GenerateSession("127.0.0.1", 0, 0)
	if err != nil {
		t.Fatalf("GenerateSession: %v", err)
	}

	// 3. Start mTLS 1.3 WebSocket control server on random port.
	router := NewRouter(nil)
	srv, err := NewServer(Config{
		ListenAddr:        "127.0.0.1:0",
		ServerIdentity:    serverIdentity,
		TrustedStore:      store,
		Router:            router,
		HeartbeatInterval: 200 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	wsAddr, err := srv.Start(ctx)
	if err != nil {
		t.Fatalf("srv.Start: %v", err)
	}
	defer func() { _ = srv.Shutdown(context.Background()) }()

	// 4. Pair an in-memory client using the pairing token.
	clientIdentity, err := auth.GenerateClientIdentity("in-memory-test-client")
	if err != nil {
		t.Fatalf("GenerateClientIdentity: %v", err)
	}

	pairResp, err := auth.SendPairingRequest(ctx, pairAddr.String(), auth.PairingRequest{
		Token:         payload.Token,
		DeviceID:      "mem-client-01",
		DeviceName:    "In-Memory Test Client",
		ClientCertPEM: string(clientIdentity.CertPEM),
	}, clientIdentity, serverIdentity.Fingerprint)
	if err != nil {
		t.Fatalf("SendPairingRequest: %v", err)
	}
	if pairResp.Status != "paired" {
		t.Fatalf("expected pairResp.Status == 'paired', got %q", pairResp.Status)
	}

	// 5. Connect to the mTLS 1.3 WebSocket control server with the paired client certificate.
	clientTLS, err := auth.ClientTLSConfig(clientIdentity, serverIdentity.Fingerprint)
	if err != nil {
		t.Fatalf("ClientTLSConfig: %v", err)
	}
	dialer := websocket.Dialer{
		TLSClientConfig:  clientTLS,
		HandshakeTimeout: 5 * time.Second,
	}
	wsURL := "wss://" + wsAddr.String() + "/ws"
	conn, _, err := dialer.DialContext(ctx, wsURL, http.Header{})
	if err != nil {
		t.Fatalf("websocket DialContext failed: %v", err)
	}
	defer conn.Close()

	// 6. Send a binary Protobuf Envelope{Ping} and verify the Envelope{Pong} response.
	sentTs := time.Now().UnixMilli()
	pingEnv := &pb.Envelope{
		RequestId:   "req-ping-42",
		TimestampMs: sentTs,
		Payload: &pb.Envelope_Ping{
			Ping: &pb.Ping{
				Seq:               42,
				ClientTimestampMs: sentTs,
			},
		},
	}
	rawPing, err := proto.Marshal(pingEnv)
	if err != nil {
		t.Fatalf("proto.Marshal ping: %v", err)
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, rawPing); err != nil {
		t.Fatalf("WriteMessage ping: %v", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	var gotPong *pb.Pong
	var gotHeartbeatPing *pb.Ping

	for gotPong == nil || gotHeartbeatPing == nil {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("ReadMessage: %v", err)
		}
		if msgType != websocket.BinaryMessage {
			t.Fatalf("expected BinaryMessage (%d), got %d", websocket.BinaryMessage, msgType)
		}
		var env pb.Envelope
		if err := proto.Unmarshal(data, &env); err != nil {
			t.Fatalf("proto.Unmarshal response: %v", err)
		}
		if p := env.GetPong(); p != nil && p.Seq == 42 {
			if env.RequestId != "req-ping-42" {
				t.Errorf("expected Pong RequestId 'req-ping-42', got %q", env.RequestId)
			}
			if p.ClientTimestampMs != sentTs {
				t.Errorf("expected ClientTimestampMs %d, got %d", sentTs, p.ClientTimestampMs)
			}
			gotPong = p
		}
		if hb := env.GetPing(); hb != nil {
			gotHeartbeatPing = hb
		}
	}
}

func TestRouterCustomHandlerAndConcurrentSend(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	serverIdentity, err := auth.LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity: %v", err)
	}
	store, err := auth.NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("NewTrustedStore: %v", err)
	}

	clientIdentity, err := auth.GenerateClientIdentity("concurrent-client")
	if err != nil {
		t.Fatalf("GenerateClientIdentity: %v", err)
	}
	if err := store.RegisterClient(auth.TrustedClient{
		DeviceID:              "conc-01",
		DeviceName:            "Concurrent Client",
		CertFingerprintSHA256: clientIdentity.Fingerprint,
	}); err != nil {
		t.Fatalf("RegisterClient: %v", err)
	}

	router := NewRouter(nil)
	const fanoutCount = 10
	router.Register(KindKeyCombo, func(_ context.Context, client ClientSession, env *pb.Envelope) error {
		var wg sync.WaitGroup
		for i := 0; i < fanoutCount; i++ {
			wg.Add(1)
			go func(seq uint64) {
				defer wg.Done()
				_ = client.Send(&pb.Envelope{
					RequestId:   env.RequestId,
					TimestampMs: time.Now().UnixMilli(),
					Payload: &pb.Envelope_Pong{
						Pong: &pb.Pong{Seq: seq},
					},
				})
			}(uint64(i + 1))
		}
		wg.Wait()
		return nil
	})

	srv, err := NewServer(Config{
		ListenAddr:        "127.0.0.1:0",
		ServerIdentity:    serverIdentity,
		TrustedStore:      store,
		Router:            router,
		HeartbeatInterval: 30 * time.Second,
	})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	wsAddr, err := srv.Start(ctx)
	if err != nil {
		t.Fatalf("srv.Start: %v", err)
	}
	defer func() { _ = srv.Shutdown(context.Background()) }()

	clientTLS, err := auth.ClientTLSConfig(clientIdentity, serverIdentity.Fingerprint)
	if err != nil {
		t.Fatalf("ClientTLSConfig: %v", err)
	}
	dialer := websocket.Dialer{TLSClientConfig: clientTLS}
	conn, _, err := dialer.DialContext(ctx, "wss://"+wsAddr.String()+"/ws", http.Header{})
	if err != nil {
		t.Fatalf("DialContext: %v", err)
	}
	defer conn.Close()

	reqBytes, err := proto.Marshal(&pb.Envelope{
		RequestId:   "combo-test",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_KeyComboEvent{
			KeyComboEvent: &pb.KeyComboEvent{Combo: "CTRL+ALT+T"},
		},
	})
	if err != nil {
		t.Fatalf("proto.Marshal: %v", err)
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, reqBytes); err != nil {
		t.Fatalf("WriteMessage: %v", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	for i := 0; i < fanoutCount; i++ {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("ReadMessage #%d: %v", i, err)
		}
		var env pb.Envelope
		if err := proto.Unmarshal(data, &env); err != nil {
			t.Fatalf("Unmarshal #%d: %v", i, err)
		}
		if env.GetPong() == nil {
			t.Fatalf("expected Pong payload at #%d", i)
		}
	}
}

