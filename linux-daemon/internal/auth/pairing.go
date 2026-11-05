package auth

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/mdp/qrterminal/v3"
)

const (
	// PairingTokenTTL is the strict 5-minute validity window for a one-time pairing token.
	PairingTokenTTL = 5 * time.Minute
	// PairingTokenBytes is 32 bytes (256 bits) -> 64 hex characters.
	PairingTokenBytes = 32
	// DefaultControlPort is the default mTLS WebSocket port.
	DefaultControlPort = 8443
	// DefaultPairingPort is the default temporary pairing port.
	DefaultPairingPort = 8444
)

var (
	// ErrInvalidPairingToken indicates that the token does not match, has already been consumed, or does not exist.
	ErrInvalidPairingToken = errors.New("auth pairing: invalid or already used pairing token")
	// ErrExpiredPairingToken indicates that the 5-minute pairing window has elapsed.
	ErrExpiredPairingToken = errors.New("auth pairing: pairing token has expired")
)

// PairingPayload represents the JSON structure encoded inside the terminal QR Code.
type PairingPayload struct {
	Host        string `json:"host"`
	Port        int    `json:"port"`
	PairingPort int    `json:"pairing_port"`
	ServerFP    string `json:"server_fp"`
	Token       string `json:"token"`
	ExpiresAt   int64  `json:"expires_at"`
}

// PairingRequest is sent by a client to the temporary pairing endpoint.
type PairingRequest struct {
	Token         string `json:"token"`
	DeviceID      string `json:"device_id"`
	DeviceName    string `json:"device_name"`
	ClientCertPEM string `json:"client_cert_pem,omitempty"`
	CSRPEM        string `json:"csr_pem,omitempty"`
}

// PairingResponse is returned by the pairing endpoint upon successful registration.
type PairingResponse struct {
	Status        string `json:"status"`
	DeviceID      string `json:"device_id"`
	ServerFP      string `json:"server_fp"`
	ServerCertPEM string `json:"server_cert_pem,omitempty"`
	ClientCertPEM string `json:"client_cert_pem,omitempty"`
	ClientFP      string `json:"client_fingerprint_sha256"`
	PairedAt      int64  `json:"paired_at"`
}

type activeToken struct {
	payload   PairingPayload
	expiresAt time.Time
	used      bool
}

// PairingManager coordinates one-time pairing tokens, QR code generation, and client registration.
type PairingManager struct {
	mu             sync.Mutex
	serverIdentity *ServerIdentity
	store          *TrustedStore
	clock          func() time.Time
	currentToken   *activeToken
}

// NewPairingManager constructs a PairingManager backed by serverIdentity and store.
func NewPairingManager(serverIdentity *ServerIdentity, store *TrustedStore) *PairingManager {
	return &PairingManager{
		serverIdentity: serverIdentity,
		store:          store,
		clock:          time.Now,
	}
}

// SetClock overrides the time source (used in unit tests to simulate token expiration).
func (pm *PairingManager) SetClock(clock func() time.Time) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	if clock != nil {
		pm.clock = clock
	}
}

// GenerateSession creates a new 256-bit one-time pairing token valid for 5 minutes
// and returns the PairingPayload to be rendered as a QR Code.
func (pm *PairingManager) GenerateSession(host string, port, pairingPort int) (PairingPayload, error) {
	if pm.serverIdentity == nil {
		return PairingPayload{}, errors.New("auth pairing: server identity is nil")
	}
	if strings.TrimSpace(host) == "" {
		host = detectOutboundHost()
	}
	if port <= 0 {
		port = DefaultControlPort
	}
	if pairingPort <= 0 {
		pairingPort = DefaultPairingPort
	}

	rawToken := make([]byte, PairingTokenBytes)
	if _, err := io.ReadFull(rand.Reader, rawToken); err != nil {
		return PairingPayload{}, fmt.Errorf("auth pairing generate random token: %w", err)
	}
	tokenHex := hex.EncodeToString(rawToken)

	pm.mu.Lock()
	defer pm.mu.Unlock()

	expiresAt := pm.clock().UTC().Add(PairingTokenTTL)
	payload := PairingPayload{
		Host:        host,
		Port:        port,
		PairingPort: pairingPort,
		ServerFP:    pm.serverIdentity.Fingerprint,
		Token:       tokenHex,
		ExpiresAt:   expiresAt.Unix(),
	}

	pm.currentToken = &activeToken{
		payload:   payload,
		expiresAt: expiresAt,
		used:      false,
	}

	return payload, nil
}

// RenderQRCode serializes payload as JSON and renders an ASCII/UTF-8 QR Code to w.
func RenderQRCode(w io.Writer, payload PairingPayload) error {
	if w == nil {
		w = os.Stdout
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("auth pairing marshal qr payload: %w", err)
	}

	cfg := qrterminal.Config{
		Level:      qrterminal.M,
		Writer:     w,
		HalfBlocks: true,
		QuietZone:  1,
	}
	qrterminal.GenerateWithConfig(string(data), cfg)
	return nil
}

// PrintQRCode prints the pairing QR code and JSON summary to os.Stdout.
func PrintQRCode(payload PairingPayload) error {
	return RenderQRCode(os.Stdout, payload)
}

// CompletePairing validates the one-time token, extracts or signs the client's certificate,
// registers the client's SHA-256 fingerprint in TrustedStore, and immediately invalidates the token.
func (pm *PairingManager) CompletePairing(req PairingRequest, peerCerts []*x509.Certificate) (*PairingResponse, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pm.currentToken == nil || pm.currentToken.used {
		return nil, ErrInvalidPairingToken
	}

	now := pm.clock().UTC()
	if !now.Before(pm.currentToken.expiresAt) {
		pm.currentToken.used = true
		return nil, ErrExpiredPairingToken
	}

	providedToken := strings.TrimSpace(req.Token)
	expectedToken := pm.currentToken.payload.Token
	if len(providedToken) != len(expectedToken) ||
		subtle.ConstantTimeCompare([]byte(providedToken), []byte(expectedToken)) != 1 {
		return nil, ErrInvalidPairingToken
	}

	var (
		clientFP      string
		signedCertPEM string
	)

	switch {
	case strings.TrimSpace(req.ClientCertPEM) != "":
		fp, cert, err := FingerprintFromPEM([]byte(req.ClientCertPEM))
		if err != nil {
			return nil, fmt.Errorf("auth pairing invalid client_cert_pem: %w", err)
		}
		if now.Before(cert.NotBefore) || now.After(cert.NotAfter) {
			return nil, errors.New("auth pairing: client certificate is expired or not yet valid")
		}
		clientFP = fp
	case strings.TrimSpace(req.CSRPEM) != "":
		certPEM, fp, err := pm.serverIdentity.SignClientCSR([]byte(req.CSRPEM), DefaultCertValidity)
		if err != nil {
			return nil, fmt.Errorf("auth pairing sign csr: %w", err)
		}
		clientFP = fp
		signedCertPEM = string(certPEM)
	case len(peerCerts) > 0:
		leaf := peerCerts[0]
		if now.Before(leaf.NotBefore) || now.After(leaf.NotAfter) {
			return nil, errors.New("auth pairing: peer certificate is expired or not yet valid")
		}
		clientFP = CertFingerprintSHA256(leaf.Raw)
	default:
		return nil, errors.New("auth pairing: request must provide client_cert_pem, csr_pem, or TLS peer certificate")
	}

	deviceID := strings.TrimSpace(req.DeviceID)
	if deviceID == "" {
		deviceID = clientFP[:16]
	}
	deviceName := strings.TrimSpace(req.DeviceName)
	if deviceName == "" {
		deviceName = "Android Deck Client"
	}
	pairedAt := now.Unix()

	if err := pm.store.RegisterClient(TrustedClient{
		DeviceID:              deviceID,
		DeviceName:            deviceName,
		CertFingerprintSHA256: clientFP,
		PairedAt:              pairedAt,
	}); err != nil {
		return nil, fmt.Errorf("auth pairing store client: %w", err)
	}

	// Token is strictly single-use: invalidate immediately on first success.
	pm.currentToken.used = true
	pm.currentToken = nil

	return &PairingResponse{
		Status:        "paired",
		DeviceID:      deviceID,
		ServerFP:      pm.serverIdentity.Fingerprint,
		ServerCertPEM: string(pm.serverIdentity.CertPEM),
		ClientCertPEM: signedCertPEM,
		ClientFP:      clientFP,
		PairedAt:      pairedAt,
	}, nil
}

// Handler returns an http.Handler for the temporary pairing endpoint.
func (pm *PairingManager) Handler() http.Handler {
	mux := http.NewServeMux()
	handlePair := func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		defer r.Body.Close()

		var req PairingRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&req); err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid JSON payload")
			return
		}

		var peerCerts []*x509.Certificate
		if r.TLS != nil {
			peerCerts = r.TLS.PeerCertificates
		}

		resp, err := pm.CompletePairing(req, peerCerts)
		if err != nil {
			status := http.StatusUnauthorized
			if !errors.Is(err, ErrInvalidPairingToken) && !errors.Is(err, ErrExpiredPairingToken) {
				status = http.StatusBadRequest
			}
			writeJSONError(w, status, err.Error())
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(resp)
	}

	mux.HandleFunc("/pair", handlePair)
	mux.HandleFunc("/api/v1/pair", handlePair)
	mux.HandleFunc("/", handlePair)
	return mux
}

// StartPairingServer starts a temporary TLS 1.3 server on listenAddr (e.g. "0.0.0.0:8444" or "127.0.0.1:0")
// that accepts pairing requests from unpained clients (RequestClientCert).
func (pm *PairingManager) StartPairingServer(ctx context.Context, listenAddr string) (net.Addr, func(context.Context) error, error) {
	if pm.serverIdentity == nil {
		return nil, nil, errors.New("auth pairing server: nil server identity")
	}

	tlsCfg := &tls.Config{
		MinVersion:   tls.VersionTLS13,
		MaxVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{pm.serverIdentity.Certificate},
		ClientAuth:   tls.RequestClientCert,
	}

	ln, err := tls.Listen("tcp", listenAddr, tlsCfg)
	if err != nil {
		return nil, nil, fmt.Errorf("auth pairing listen %s: %w", listenAddr, err)
	}

	srv := &http.Server{
		Handler:           pm.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	go func() {
		_ = srv.Serve(ln)
	}()

	return ln.Addr(), srv.Shutdown, nil
}

// SendPairingRequest sends a pairing request over TLS 1.3 to pairingAddr (e.g. "127.0.0.1:8444").
// If expectedServerFP is non-empty, the server's certificate SHA-256 fingerprint is pinned and verified.
func SendPairingRequest(ctx context.Context, pairingAddr string, req PairingRequest, clientIdentity *ClientIdentity, expectedServerFP string) (*PairingResponse, error) {
	expectedFP := NormalizeFingerprint(expectedServerFP)

	tlsCfg := &tls.Config{
		MinVersion:         tls.VersionTLS13,
		MaxVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true, // Pinning performed in VerifyPeerCertificate below.
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("auth pairing client: server presented no certificate")
			}
			if expectedFP != "" {
				actualFP := CertFingerprintSHA256(rawCerts[0])
				if actualFP != expectedFP {
					return fmt.Errorf("auth pairing client: server fingerprint mismatch: got %s, want %s", actualFP, expectedFP)
				}
			}
			return nil
		},
	}

	if clientIdentity != nil {
		tlsCfg.Certificates = []tls.Certificate{clientIdentity.Certificate}
		if req.ClientCertPEM == "" && req.CSRPEM == "" {
			req.ClientCertPEM = string(clientIdentity.CertPEM)
		}
	}

	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("auth pairing marshal request: %w", err)
	}

	endpoint := pairingAddr
	if !strings.HasPrefix(endpoint, "https://") && !strings.HasPrefix(endpoint, "http://") {
		endpoint = "https://" + strings.TrimSuffix(endpoint, "/") + "/pair"
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("auth pairing create http request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: tlsCfg,
		},
	}

	resp, err := client.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("auth pairing post %s: %w", endpoint, err)
	}
	defer resp.Body.Close()

	respBytes, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, fmt.Errorf("auth pairing read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("auth pairing failed with HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(respBytes)))
	}

	var parsed PairingResponse
	if err := json.Unmarshal(respBytes, &parsed); err != nil {
		return nil, fmt.Errorf("auth pairing decode response: %w", err)
	}
	return &parsed, nil
}

func writeJSONError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func detectOutboundHost() string {
	conn, err := net.Dial("udp4", "8.8.8.8:80")
	if err == nil {
		defer conn.Close()
		if localAddr, ok := conn.LocalAddr().(*net.UDPAddr); ok && !localAddr.IP.IsLoopback() {
			return localAddr.IP.String()
		}
	}
	addrs, err := net.InterfaceAddrs()
	if err == nil {
		for _, addr := range addrs {
			if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
				if ip4 := ipNet.IP.To4(); ip4 != nil {
					return ip4.String()
				}
			}
		}
	}
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "127.0.0.1"
}

