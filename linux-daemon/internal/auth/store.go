package auth

import (
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// ErrUntrustedClientCert is returned when a peer presents a certificate not registered in TrustedStore.
var ErrUntrustedClientCert = errors.New("auth store: client certificate is not in trusted store")

// TrustedClient represents an authorized Android or CLI client persisted in trusted_clients.json.
type TrustedClient struct {
	DeviceID              string `json:"device_id"`
	DeviceName            string `json:"device_name"`
	CertFingerprintSHA256 string `json:"cert_fingerprint_sha256"`
	PairedAt              int64  `json:"paired_at"`
}

type trustedStoreFile struct {
	Clients []TrustedClient `json:"clients"`
}

// TrustedStore manages the persistent registry of trusted client certificates.
type TrustedStore struct {
	mu       sync.RWMutex
	filePath string
	clients  map[string]TrustedClient // keyed by normalized cert_fingerprint_sha256
}

// NewTrustedStore loads or initializes trusted_clients.json inside configDir with 0600 permissions.
func NewTrustedStore(configDir string) (*TrustedStore, error) {
	if configDir == "" {
		var err error
		configDir, err = DefaultConfigDir()
		if err != nil {
			return nil, err
		}
	}

	if err := os.MkdirAll(configDir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth store create config dir: %w", err)
	}
	if err := os.Chmod(configDir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth store chmod config dir: %w", err)
	}

	filePath := filepath.Join(configDir, "trusted_clients.json")
	s := &TrustedStore{
		filePath: filePath,
		clients:  make(map[string]TrustedClient),
	}

	if fileExists(filePath) {
		if err := s.load(); err != nil {
			return nil, err
		}
	} else {
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}

	return s, nil
}

// FilePath returns the absolute path to trusted_clients.json.
func (s *TrustedStore) FilePath() string {
	return s.filePath
}

// RegisterClient adds or updates an authorized client and persists trusted_clients.json with 0600 permissions.
func (s *TrustedStore) RegisterClient(client TrustedClient) error {
	fp := NormalizeFingerprint(client.CertFingerprintSHA256)
	if fp == "" {
		return errors.New("auth store register client: empty certificate fingerprint")
	}
	if client.DeviceID == "" {
		client.DeviceID = fp[:16]
	}
	if client.DeviceName == "" {
		client.DeviceName = "LinuxDeck Client"
	}
	if client.PairedAt == 0 {
		client.PairedAt = time.Now().UTC().Unix()
	}
	client.CertFingerprintSHA256 = fp

	s.mu.Lock()
	defer s.mu.Unlock()

	// Remove any older entry with the same DeviceID if it rotated its certificate.
	for k, existing := range s.clients {
		if existing.DeviceID == client.DeviceID && k != fp {
			delete(s.clients, k)
		}
	}

	s.clients[fp] = client
	return s.saveLocked()
}

// RemoveByFingerprint removes a client by its certificate SHA-256 fingerprint.
func (s *TrustedStore) RemoveByFingerprint(fingerprint string) error {
	fp := NormalizeFingerprint(fingerprint)
	s.mu.Lock()
	defer s.mu.Unlock()

	delete(s.clients, fp)
	return s.saveLocked()
}

// IsTrusted checks if a certificate SHA-256 fingerprint is registered in the store.
// If not found in memory, reloads trusted_clients.json from disk once so clients paired via a
// separate `deck-daemon --pair` process are accepted immediately without restarting the daemon.
func (s *TrustedStore) IsTrusted(fingerprint string) bool {
	fp := NormalizeFingerprint(fingerprint)
	if fp == "" {
		return false
	}
	s.mu.RLock()
	_, ok := s.clients[fp]
	s.mu.RUnlock()
	if ok {
		return true
	}

	if fileExists(s.filePath) {
		_ = s.load()
		s.mu.RLock()
		_, ok = s.clients[fp]
		s.mu.RUnlock()
	}
	return ok
}

// GetByFingerprint returns the TrustedClient matching the given SHA-256 fingerprint.
func (s *TrustedStore) GetByFingerprint(fingerprint string) (TrustedClient, bool) {
	fp := NormalizeFingerprint(fingerprint)
	s.mu.RLock()
	c, ok := s.clients[fp]
	s.mu.RUnlock()
	if ok {
		return c, true
	}
	if fileExists(s.filePath) {
		_ = s.load()
		s.mu.RLock()
		c, ok = s.clients[fp]
		s.mu.RUnlock()
	}
	return c, ok
}

// ListClients returns a snapshot of all registered trusted clients.
func (s *TrustedStore) ListClients() []TrustedClient {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]TrustedClient, 0, len(s.clients))
	for _, c := range s.clients {
		out = append(out, c)
	}
	return out
}

// VerifyPeerCertificate implements the tls.Config.VerifyPeerCertificate callback.
// It validates the peer's X.509 certificate validity window and checks that its SHA-256 fingerprint
// exists in trusted_clients.json.
func (s *TrustedStore) VerifyPeerCertificate(rawCerts [][]byte, _ [][]*x509.Certificate) error {
	if len(rawCerts) == 0 {
		return errors.New("auth store verify peer: no client certificate provided")
	}

	leaf, err := x509.ParseCertificate(rawCerts[0])
	if err != nil {
		return fmt.Errorf("auth store parse client cert: %w", err)
	}

	now := time.Now()
	if now.Before(leaf.NotBefore) {
		return fmt.Errorf("auth store verify peer: client certificate not valid before %s", leaf.NotBefore.Format(time.RFC3339))
	}
	if now.After(leaf.NotAfter) {
		return fmt.Errorf("auth store verify peer: client certificate expired at %s", leaf.NotAfter.Format(time.RFC3339))
	}

	fp := CertFingerprintSHA256(leaf.Raw)
	if !s.IsTrusted(fp) {
		return fmt.Errorf("%w: fingerprint %s", ErrUntrustedClientCert, fp)
	}

	return nil
}

func (s *TrustedStore) load() error {
	if err := os.Chmod(s.filePath, FilePerm); err != nil {
		return fmt.Errorf("auth store chmod %s: %w", s.filePath, err)
	}
	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return fmt.Errorf("auth store read %s: %w", s.filePath, err)
	}
	if len(data) == 0 {
		return nil
	}

	var parsed trustedStoreFile
	if err := json.Unmarshal(data, &parsed); err != nil {
		// Support top-level array fallback if needed.
		var list []TrustedClient
		if arrErr := json.Unmarshal(data, &list); arrErr != nil {
			return fmt.Errorf("auth store parse %s: %w", s.filePath, err)
		}
		parsed.Clients = list
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.clients = make(map[string]TrustedClient, len(parsed.Clients))
	for _, c := range parsed.Clients {
		fp := NormalizeFingerprint(c.CertFingerprintSHA256)
		if fp != "" {
			c.CertFingerprintSHA256 = fp
			s.clients[fp] = c
		}
	}
	return nil
}

func (s *TrustedStore) saveLocked() error {
	list := make([]TrustedClient, 0, len(s.clients))
	for _, c := range s.clients {
		list = append(list, c)
	}
	payload, err := json.MarshalIndent(trustedStoreFile{Clients: list}, "", "  ")
	if err != nil {
		return fmt.Errorf("auth store marshal: %w", err)
	}
	if err := writeSecureFile(s.filePath, payload); err != nil {
		return fmt.Errorf("auth store write %s: %w", s.filePath, err)
	}
	return nil
}

