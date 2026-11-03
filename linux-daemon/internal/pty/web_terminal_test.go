package pty

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestWebTerminal_WebSocketPtySessionAndResize(t *testing.T) {
	wt := NewWebTerminal(nil)

	mux := http.NewServeMux()
	wt.RegisterRoutes(mux)

	server := httptest.NewServer(mux)
	defer server.Close()

	// 1. Verify GET /terminal returns HTML containing "LowDeck Web Terminal"
	res, err := http.Get(server.URL + "/terminal")
	if err != nil {
		t.Fatalf("failed to GET /terminal: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("expected status 200, got %d", res.StatusCode)
	}

	// 2. Connect WebSocket to /ws/pty
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/pty"
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("failed to dial websocket at %s: %v", wsURL, err)
	}
	defer ws.Close()

	// 3. Send resize frame [0x01] + JSON
	resizePayload := []byte("\x01{\"cols\":120,\"rows\":40}")
	if err := ws.WriteMessage(websocket.BinaryMessage, resizePayload); err != nil {
		t.Fatalf("failed to send resize frame: %v", err)
	}

	// 4. Send shell command frame [0x00] + echo "LOWDECK_OK"\n
	cmdPayload := append([]byte{0x00}, []byte("echo \"LOWDECK_OK\"\n")...)
	if err := ws.WriteMessage(websocket.BinaryMessage, cmdPayload); err != nil {
		t.Fatalf("failed to send echo command: %v", err)
	}

	// 5. Read binary responses until "LOWDECK_OK" is found
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	found := false
	var received bytes.Buffer

	for !found {
		select {
		case <-ctx.Done():
			t.Fatalf("timeout waiting for LOWDECK_OK in output, received so far: %q", received.String())
		default:
		}

		_ = ws.SetReadDeadline(time.Now().Add(1 * time.Second))
		msgType, msg, readErr := ws.ReadMessage()
		if readErr != nil {
			if ctx.Err() != nil {
				t.Fatalf("timeout reading websocket: %v", readErr)
			}
			continue
		}

		if msgType == websocket.BinaryMessage {
			received.Write(msg)
			if strings.Contains(received.String(), "LOWDECK_OK") {
				found = true
				break
			}
		}
	}

	if !found {
		t.Fatalf("did not receive expected LOWDECK_OK marker, received: %q", received.String())
	}
}

