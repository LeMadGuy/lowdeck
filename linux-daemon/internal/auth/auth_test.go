package auth

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestServerAndClientCertificateGenerationAndPermissions(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	serverID, err := LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity failed: %v", err)
	}
	if serverID.Fingerprint == "" || len(serverID.Fingerprint) != 64 {
		t.Fatalf("expected 64-char SHA-256 hex fingerprint, got %q", serverID.Fingerprint)
	}

	// Verify 0700 directory permission and 0600 file permissions.
	certsDirInfo, err := os.Stat(filepath.Join(tmpDir, "certs"))
	if err != nil {
		t.Fatalf("stat certs dir: %v", err)
	}
	if got := certsDirInfo.Mode().Perm(); got != DirPerm {
		t.Errorf("certs dir permissions = %04o, want %04o", got, DirPerm)
	}

	for _, p := range []string{serverID.CertPath, serverID.KeyPath} {
		info, err := os.Stat(p)
		if err != nil {
			t.Fatalf("stat %s: %v", p, err)
		}
		if got := info.Mode().Perm(); got != FilePerm {
			t.Errorf("%s permissions = %04o, want %04o", p, got, FilePerm)
		}
	}

	// Reloading from the same directory must return the exact same certificate & fingerprint.
	reloadedID, err := LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("reload server identity failed: %v", err)
	}
	if reloadedID.Fingerprint != serverID.Fingerprint {
		t.Errorf("reloaded fingerprint %s != original %s", reloadedID.Fingerprint, serverID.Fingerprint)
	}

	// Generate and persist client identity in a subdirectory.
	clientDir := filepath.Join(tmpDir, "mock-client")
	clientID, err := LoadOrCreateClientIdentity(clientDir, "pixel-9-pro")
	if err != nil {
		t.Fatalf("LoadOrCreateClientIdentity failed: %v", err)
	}
	if len(clientID.Fingerprint) != 64 {
		t.Fatalf("expected 64-char client fingerprint, got %q", clientID.Fingerprint)
	}
	for _, p := range []string{clientID.CertPath, clientID.KeyPath} {
		info, err := os.Stat(p)
		if err != nil {
			t.Fatalf("stat %s: %v", p, err)
		}
		if got := info.Mode().Perm(); got != FilePerm {
			t.Errorf("%s permissions = %04o, want %04o", p, got, FilePerm)
		}
	}
}

func TestPairingFlowValidTokenAndOneTimeUse(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	serverID, err := LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity: %v", err)
	}
	store, err := NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("NewTrustedStore: %v", err)
	}

	// Verify trusted_clients.json permissions (0600).
	storeInfo, err := os.Stat(store.FilePath())
	if err != nil {
		t.Fatalf("stat trusted_clients.json: %v", err)
	}
	if got := storeInfo.Mode().Perm(); got != FilePerm {
		t.Errorf("trusted_clients.json permissions = %04o, want %04o", got, FilePerm)
	}

	pm := NewPairingManager(serverID, store)
	payload, err := pm.GenerateSession("192.168.1.50", 8443, 8444)
	if err != nil {
		t.Fatalf("GenerateSession: %v", err)
	}
	if len(payload.Token) != 64 {
		t.Errorf("expected 64-hex-char (256-bit) token, got len=%d (%q)", len(payload.Token), payload.Token)
	}
	if payload.ServerFP != serverID.Fingerprint {
		t.Errorf("payload.ServerFP = %s, want %s", payload.ServerFP, serverID.Fingerprint)
	}

	// Verify QR code rendering and JSON structure.
	var qrBuf bytes.Buffer
	if err := RenderQRCode(&qrBuf, payload); err != nil {
		t.Fatalf("RenderQRCode: %v", err)
	}
	if qrBuf.Len() == 0 {
		t.Errorf("expected non-empty QR Code output")
	}

	rawJSON, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	var decodedMap map[string]any
	if err := json.Unmarshal(rawJSON, &decodedMap); err != nil {
		t.Fatalf("unmarshal payload map: %v", err)
	}
	for _, key := range []string{"host", "port", "pairing_port", "server_fp", "token", "expires_at"} {
		if _, ok := decodedMap[key]; !ok {
			t.Errorf("missing required JSON key %q in PairingPayload", key)
		}
	}

	// Start temporary pairing TLS server on random port.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	addr, shutdown, err := pm.StartPairingServer(ctx, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("StartPairingServer: %v", err)
	}
	defer func() {
		_ = shutdown(context.Background())
	}()

	clientID, err := GenerateClientIdentity("android-test-device")
	if err != nil {
		t.Fatalf("GenerateClientIdentity: %v", err)
	}

	// First pairing attempt with valid token must succeed.
	resp, err := SendPairingRequest(ctx, addr.String(), PairingRequest{
		Token:      payload.Token,
		DeviceID:   "android-001",
		DeviceName: "Pixel 9 Pro",
	}, clientID, serverID.Fingerprint)
	if err != nil {
		t.Fatalf("SendPairingRequest with valid token failed: %v", err)
	}
	if resp.ClientFP != clientID.Fingerprint {
		t.Errorf("resp.ClientFP = %s, want %s", resp.ClientFP, clientID.Fingerprint)
	}
	if !store.IsTrusted(clientID.Fingerprint) {
		t.Errorf("expected client fingerprint %s to be trusted after pairing", clientID.Fingerprint)
	}

	// Reload store from disk to verify persistence.
	reloadedStore, err := NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("reload TrustedStore: %v", err)
	}
	if !reloadedStore.IsTrusted(clientID.Fingerprint) {
		t.Errorf("expected client fingerprint %s to persist in trusted_clients.json", clientID.Fingerprint)
	}

	// Second attempt with the exact same token must fail (single-use token).
	secondClient, err := GenerateClientIdentity("second-device")
	if err != nil {
		t.Fatalf("GenerateClientIdentity: %v", err)
	}
	_, err = SendPairingRequest(ctx, addr.String(), PairingRequest{
		Token:      payload.Token,
		DeviceID:   "android-002",
		DeviceName: "Second Phone",
	}, secondClient, serverID.Fingerprint)
	if err == nil {
		t.Fatalf("expected second pairing attempt with already-used token to fail, but it succeeded")
	}
	if store.IsTrusted(secondClient.Fingerprint) {
		t.Errorf("second client should not be trusted after replaying consumed token")
	}
}

func TestPairingRejectsInvalidAndExpiredTokens(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	serverID, err := LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity: %v", err)
	}
	store, err := NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("NewTrustedStore: %v", err)
	}
	pm := NewPairingManager(serverID, store)

	baseTime := time.Date(2026, 10, 29, 18, 0, 0, 0, time.UTC)
	currentTime := baseTime
	pm.SetClock(func() time.Time { return currentTime })

	payload, err := pm.GenerateSession("127.0.0.1", 8443, 8444)
	if err != nil {
		t.Fatalf("GenerateSession: %v", err)
	}

	clientID, err := GenerateClientIdentity("unpaired-client")
	if err != nil {
		t.Fatalf("GenerateClientIdentity: %v", err)
	}

	// 1. Test invalid token (wrong hex string).
	_, err = pm.CompletePairing(PairingRequest{
		Token:         "0000000000000000000000000000000000000000000000000000000000000000",
		DeviceID:      "dev-invalid",
		DeviceName:    "Invalid Token Device",
		ClientCertPEM: string(clientID.CertPEM),
	}, nil)
	if !errors.Is(err, ErrInvalidPairingToken) {
		t.Fatalf("expected ErrInvalidPairingToken for wrong token, got: %v", err)
	}
	if store.IsTrusted(clientID.Fingerprint) {
		t.Fatalf("client must not be trusted after invalid token")
	}

	// 2. Advance clock past 5 minutes and verify valid token is now rejected as expired.
	currentTime = baseTime.Add(5*time.Minute + time.Second)
	_, err = pm.CompletePairing(PairingRequest{
		Token:         payload.Token,
		DeviceID:      "dev-expired",
		DeviceName:    "Expired Token Device",
		ClientCertPEM: string(clientID.CertPEM),
	}, nil)
	if !errors.Is(err, ErrExpiredPairingToken) {
		t.Fatalf("expected ErrExpiredPairingToken for expired token, got: %v", err)
	}
	if store.IsTrusted(clientID.Fingerprint) {
		t.Fatalf("client must not be trusted after expired token")
	}

	// 3. Test CSR signing flow with fresh token.
	currentTime = baseTime.Add(10 * time.Minute)
	freshPayload, err := pm.GenerateSession("127.0.0.1", 8443, 8444)
	if err != nil {
		t.Fatalf("GenerateSession: %v", err)
	}
	csrKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	csrDER, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{
		Subject: pkix.Name{CommonName: "android-csr-client"},
	}, csrKey)
	if err != nil {
		t.Fatalf("CreateCertificateRequest: %v", err)
	}
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})

	resp, err := pm.CompletePairing(PairingRequest{
		Token:      freshPayload.Token,
		DeviceID:   "csr-device-01",
		DeviceName: "CSR Android Phone",
		CSRPEM:     string(csrPEM),
	}, nil)
	if err != nil {
		t.Fatalf("CompletePairing with CSR failed: %v", err)
	}
	if resp.ClientCertPEM == "" || !store.IsTrusted(resp.ClientFP) {
		t.Fatalf("expected signed client certificate and trusted fingerprint in store")
	}
}

func TestMutualTLS13HandshakeAcceptsTrustedAndRejectsUntrusted(t *testing.T) {
	t.Parallel()
	tmpDir := t.TempDir()

	serverID, err := LoadOrCreateServerIdentity(tmpDir)
	if err != nil {
		t.Fatalf("LoadOrCreateServerIdentity: %v", err)
	}
	store, err := NewTrustedStore(tmpDir)
	if err != nil {
		t.Fatalf("NewTrustedStore: %v", err)
	}

	trustedClient, err := GenerateClientIdentity("trusted-android")
	if err != nil {
		t.Fatalf("GenerateClientIdentity (trusted): %v", err)
	}
	untrustedClient, err := GenerateClientIdentity("untrusted-attacker")
	if err != nil {
		t.Fatalf("GenerateClientIdentity (untrusted): %v", err)
	}

	// Register only trustedClient in store.
	if err := store.RegisterClient(TrustedClient{
		DeviceID:              "trusted-01",
		DeviceName:            "Trusted Phone",
		CertFingerprintSHA256: trustedClient.Fingerprint,
	}); err != nil {
		t.Fatalf("RegisterClient: %v", err)
	}

	serverTLS, err := ServerTLSConfig(serverID, store)
	if err != nil {
		t.Fatalf("ServerTLSConfig: %v", err)
	}
	if serverTLS.MinVersion != tls.VersionTLS13 {
		t.Fatalf("ServerTLSConfig.MinVersion = %x, want VersionTLS13 (%x)", serverTLS.MinVersion, tls.VersionTLS13)
	}

	ln, err := tls.Listen("tcp", "127.0.0.1:0", serverTLS)
	if err != nil {
		t.Fatalf("tls.Listen: %v", err)
	}
	defer ln.Close()

	// Echo server loop that completes TLS handshake on each connection.
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				tlsConn, ok := c.(*tls.Conn)
				if !ok {
					return
				}
				if err := tlsConn.Handshake(); err != nil {
					return
				}
				_, _ = c.Write([]byte("OK_MTLS_13"))
			}(conn)
		}
	}()

	// Case 1: Trusted client with matching server fingerprint succeeds.
	trustedTLS, err := ClientTLSConfig(trustedClient, serverID.Fingerprint)
	if err != nil {
		t.Fatalf("ClientTLSConfig (trusted): %v", err)
	}
	conn, err := tls.Dial("tcp", ln.Addr().String(), trustedTLS)
	if err != nil {
		t.Fatalf("expected trusted client TLS 1.3 dial to succeed, got error: %v", err)
	}
	msg, err := io.ReadAll(conn)
	_ = conn.Close()
	if err != nil || string(msg) != "OK_MTLS_13" {
		t.Fatalf("expected 'OK_MTLS_13' from server, got %q (err=%v)", string(msg), err)
	}

	// Case 2: Untrusted client (not in store) must be rejected during TLS handshake.
	untrustedTLS, err := ClientTLSConfig(untrustedClient, serverID.Fingerprint)
	if err != nil {
		t.Fatalf("ClientTLSConfig (untrusted): %v", err)
	}
	badConn, err := tls.Dial("tcp", ln.Addr().String(), untrustedTLS)
	if err == nil {
		// In TLS 1.3, client certificate verification happens after ClientFinished is sent;
		// reading from the connection surfaces the server's alert immediately.
		buf := make([]byte, 16)
		_, readErr := badConn.Read(buf)
		_ = badConn.Close()
		if readErr == nil {
			t.Fatalf("expected untrusted client connection to be rejected by server, but read succeeded")
		}
	}

	// Case 3: Trusted client pinning a wrong server fingerprint rejects the server.
	wrongPinTLS, err := ClientTLSConfig(trustedClient, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
	if err != nil {
		t.Fatalf("ClientTLSConfig (wrong pin): %v", err)
	}
	pinConn, err := tls.Dial("tcp", ln.Addr().String(), wrongPinTLS)
	if err == nil {
		_ = pinConn.Close()
		t.Fatalf("expected client with mismatched server fingerprint pin to reject connection")
	}
}

