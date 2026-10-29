package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	// DirPerm enforces owner-only access on configuration and certificate directories.
	DirPerm os.FileMode = 0o700
	// FilePerm enforces owner-only read/write access on private keys and trust stores.
	FilePerm os.FileMode = 0o600
	// DefaultCertValidity is the default lifetime for generated X.509 certificates (10 years).
	DefaultCertValidity = 10 * 365 * 24 * time.Hour
)

// ServerIdentity holds the daemon's X.509 certificate, private key, and SHA-256 fingerprint.
type ServerIdentity struct {
	Certificate tls.Certificate
	X509Cert    *x509.Certificate
	PrivateKey  *ecdsa.PrivateKey
	CertPEM     []byte
	KeyPEM      []byte
	Fingerprint string
	CertPath    string
	KeyPath     string
}

// ClientIdentity holds a client's X.509 certificate, private key, and SHA-256 fingerprint.
type ClientIdentity struct {
	Certificate tls.Certificate
	X509Cert    *x509.Certificate
	PrivateKey  *ecdsa.PrivateKey
	CertPEM     []byte
	KeyPEM      []byte
	Fingerprint string
	CertPath    string
	KeyPath     string
}

// DefaultConfigDir returns the default configuration directory (~/.config/deck-daemon).
func DefaultConfigDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("auth resolve user home dir: %w", err)
	}
	return filepath.Join(home, ".config", "deck-daemon"), nil
}

// LoadOrCreateServerIdentity loads the daemon's X.509 certificate and ECDSA P-256 private key
// from <configDir>/certs/server.{crt,key}, or generates and persists a new pair with 0600 permissions.
func LoadOrCreateServerIdentity(configDir string) (*ServerIdentity, error) {
	if configDir == "" {
		var err error
		configDir, err = DefaultConfigDir()
		if err != nil {
			return nil, err
		}
	}

	certsDir := filepath.Join(configDir, "certs")
	if err := os.MkdirAll(certsDir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth create certs dir: %w", err)
	}
	if err := os.Chmod(certsDir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth chmod certs dir: %w", err)
	}

	certPath := filepath.Join(certsDir, "server.crt")
	keyPath := filepath.Join(certsDir, "server.key")

	if fileExists(certPath) && fileExists(keyPath) {
		return loadServerIdentityFromDisk(certPath, keyPath)
	}

	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		hostname = "linux-deck-host"
	}

	certPEM, keyPEM, err := generateSelfSignedECDSACert(hostname, true)
	if err != nil {
		return nil, fmt.Errorf("auth generate server cert: %w", err)
	}

	if err := writeSecureFile(certPath, certPEM); err != nil {
		return nil, fmt.Errorf("auth write server cert: %w", err)
	}
	if err := writeSecureFile(keyPath, keyPEM); err != nil {
		return nil, fmt.Errorf("auth write server key: %w", err)
	}

	return parseServerIdentity(certPEM, keyPEM, certPath, keyPath)
}

// LoadOrCreateClientIdentity loads a client certificate and private key from <dir>/client.{crt,key},
// or generates and saves a new ECDSA P-256 identity with 0600 permissions.
func LoadOrCreateClientIdentity(dir string, commonName string) (*ClientIdentity, error) {
	if err := os.MkdirAll(dir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth create client dir: %w", err)
	}
	if err := os.Chmod(dir, DirPerm); err != nil {
		return nil, fmt.Errorf("auth chmod client dir: %w", err)
	}

	certPath := filepath.Join(dir, "client.crt")
	keyPath := filepath.Join(dir, "client.key")

	if fileExists(certPath) && fileExists(keyPath) {
		certPEM, err := os.ReadFile(certPath)
		if err != nil {
			return nil, fmt.Errorf("auth read client cert: %w", err)
		}
		keyPEM, err := os.ReadFile(keyPath)
		if err != nil {
			return nil, fmt.Errorf("auth read client key: %w", err)
		}
		if err := os.Chmod(certPath, FilePerm); err != nil {
			return nil, fmt.Errorf("auth chmod client cert: %w", err)
		}
		if err := os.Chmod(keyPath, FilePerm); err != nil {
			return nil, fmt.Errorf("auth chmod client key: %w", err)
		}
		id, err := parseClientIdentity(certPEM, keyPEM)
		if err != nil {
			return nil, err
		}
		id.CertPath = certPath
		id.KeyPath = keyPath
		return id, nil
	}

	id, err := GenerateClientIdentity(commonName)
	if err != nil {
		return nil, err
	}

	if err := writeSecureFile(certPath, id.CertPEM); err != nil {
		return nil, fmt.Errorf("auth write client cert: %w", err)
	}
	if err := writeSecureFile(keyPath, id.KeyPEM); err != nil {
		return nil, fmt.Errorf("auth write client key: %w", err)
	}
	id.CertPath = certPath
	id.KeyPath = keyPath
	return id, nil
}

// GenerateClientIdentity generates an in-memory ECDSA P-256 client certificate and private key.
func GenerateClientIdentity(commonName string) (*ClientIdentity, error) {
	if strings.TrimSpace(commonName) == "" {
		commonName = "linuxdeck-android-client"
	}
	certPEM, keyPEM, err := generateSelfSignedECDSACert(commonName, false)
	if err != nil {
		return nil, fmt.Errorf("auth generate client cert: %w", err)
	}
	return parseClientIdentity(certPEM, keyPEM)
}

// SignClientCSR signs a PEM-encoded PKCS#10 Certificate Signing Request using the server's CA identity.
func (s *ServerIdentity) SignClientCSR(csrPEM []byte, validity time.Duration) ([]byte, string, error) {
	if s == nil || s.X509Cert == nil || s.PrivateKey == nil {
		return nil, "", errors.New("auth sign csr: server identity is not initialized")
	}
	block, _ := pem.Decode(csrPEM)
	if block == nil || block.Type != "CERTIFICATE REQUEST" && block.Type != "NEW CERTIFICATE REQUEST" {
		return nil, "", errors.New("auth sign csr: invalid CSR PEM block")
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		return nil, "", fmt.Errorf("auth parse csr: %w", err)
	}
	if err := csr.CheckSignature(); err != nil {
		return nil, "", fmt.Errorf("auth verify csr signature: %w", err)
	}

	if validity <= 0 {
		validity = DefaultCertValidity
	}
	serial, err := randomSerialNumber()
	if err != nil {
		return nil, "", err
	}

	now := time.Now().UTC()
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               csr.Subject,
		NotBefore:             now.Add(-5 * time.Minute),
		NotAfter:              now.Add(validity),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, tmpl, s.X509Cert, csr.PublicKey, s.PrivateKey)
	if err != nil {
		return nil, "", fmt.Errorf("auth create signed client cert: %w", err)
	}

	signedPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "CERTIFICATE",
		Bytes: derBytes,
	})
	return signedPEM, CertFingerprintSHA256(derBytes), nil
}

// ServerTLSConfig returns a strict mTLS 1.3 configuration for the daemon control server.
// Only clients whose certificate SHA-256 fingerprint is registered in store are accepted.
func ServerTLSConfig(serverIdentity *ServerIdentity, store *TrustedStore) (*tls.Config, error) {
	if serverIdentity == nil {
		return nil, errors.New("auth server tls config: nil server identity")
	}
	if store == nil {
		return nil, errors.New("auth server tls config: nil trusted store")
	}

	return &tls.Config{
		MinVersion:            tls.VersionTLS13,
		MaxVersion:            tls.VersionTLS13,
		Certificates:          []tls.Certificate{serverIdentity.Certificate},
		ClientAuth:            tls.RequireAnyClientCert,
		VerifyPeerCertificate: store.VerifyPeerCertificate,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if cs.Version != tls.VersionTLS13 {
				return fmt.Errorf("auth tls version %x rejected: TLS 1.3 required", cs.Version)
			}
			if len(cs.PeerCertificates) == 0 {
				return errors.New("auth mTLS: peer presented no certificate")
			}
			rawCerts := make([][]byte, len(cs.PeerCertificates))
			for i, c := range cs.PeerCertificates {
				rawCerts[i] = c.Raw
			}
			return store.VerifyPeerCertificate(rawCerts, cs.VerifiedChains)
		},
	}, nil
}

// ClientTLSConfig builds a TLS 1.3 client configuration that presents the client's certificate
// and pins the server certificate via its expected SHA-256 fingerprint.
func ClientTLSConfig(clientIdentity *ClientIdentity, expectedServerFingerprint string) (*tls.Config, error) {
	if clientIdentity == nil {
		return nil, errors.New("auth client tls config: nil client identity")
	}
	expectedFP := NormalizeFingerprint(expectedServerFingerprint)

	return &tls.Config{
		MinVersion:         tls.VersionTLS13,
		MaxVersion:         tls.VersionTLS13,
		Certificates:       []tls.Certificate{clientIdentity.Certificate},
		InsecureSkipVerify: true, // Verified explicitly via VerifyPeerCertificate pinning below.
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("auth client tls: server presented no certificate")
			}
			cert, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return fmt.Errorf("auth client tls parse server cert: %w", err)
			}
			now := time.Now()
			if now.Before(cert.NotBefore) || now.After(cert.NotAfter) {
				return errors.New("auth client tls: server certificate is expired or not yet valid")
			}
			actualFP := CertFingerprintSHA256(rawCerts[0])
			if expectedFP != "" && actualFP != expectedFP {
				return fmt.Errorf("auth client tls: server fingerprint mismatch: got %s, want %s", actualFP, expectedFP)
			}
			return nil
		},
	}, nil
}

// CertFingerprintSHA256 returns the lowercase hex-encoded SHA-256 digest of a DER-encoded certificate.
func CertFingerprintSHA256(rawDER []byte) string {
	sum := sha256.Sum256(rawDER)
	return hex.EncodeToString(sum[:])
}

// FingerprintFromPEM parses a PEM-encoded X.509 certificate and returns its SHA-256 fingerprint and parsed cert.
func FingerprintFromPEM(certPEM []byte) (string, *x509.Certificate, error) {
	block, _ := pem.Decode(certPEM)
	if block == nil || block.Type != "CERTIFICATE" {
		return "", nil, errors.New("auth parse cert pem: invalid CERTIFICATE block")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return "", nil, fmt.Errorf("auth parse x509 cert: %w", err)
	}
	return CertFingerprintSHA256(cert.Raw), cert, nil
}

// NormalizeFingerprint normalizes a hex SHA-256 fingerprint for comparison.
func NormalizeFingerprint(fp string) string {
	s := strings.TrimSpace(strings.ToLower(fp))
	s = strings.TrimPrefix(s, "sha256:")
	s = strings.ReplaceAll(s, ":", "")
	return s
}

func loadServerIdentityFromDisk(certPath, keyPath string) (*ServerIdentity, error) {
	if err := os.Chmod(certPath, FilePerm); err != nil {
		return nil, fmt.Errorf("auth chmod server cert: %w", err)
	}
	if err := os.Chmod(keyPath, FilePerm); err != nil {
		return nil, fmt.Errorf("auth chmod server key: %w", err)
	}

	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return nil, fmt.Errorf("auth read server cert: %w", err)
	}
	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("auth read server key: %w", err)
	}
	return parseServerIdentity(certPEM, keyPEM, certPath, keyPath)
}

func parseServerIdentity(certPEM, keyPEM []byte, certPath, keyPath string) (*ServerIdentity, error) {
	tlsCert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("auth load server x509 keypair: %w", err)
	}
	x509Cert, err := x509.ParseCertificate(tlsCert.Certificate[0])
	if err != nil {
		return nil, fmt.Errorf("auth parse server leaf cert: %w", err)
	}
	tlsCert.Leaf = x509Cert

	ecdsaKey, ok := tlsCert.PrivateKey.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("auth server private key is not ECDSA")
	}

	return &ServerIdentity{
		Certificate: tlsCert,
		X509Cert:    x509Cert,
		PrivateKey:  ecdsaKey,
		CertPEM:     certPEM,
		KeyPEM:      keyPEM,
		Fingerprint: CertFingerprintSHA256(x509Cert.Raw),
		CertPath:    certPath,
		KeyPath:     keyPath,
	}, nil
}

func parseClientIdentity(certPEM, keyPEM []byte) (*ClientIdentity, error) {
	tlsCert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("auth load client x509 keypair: %w", err)
	}
	x509Cert, err := x509.ParseCertificate(tlsCert.Certificate[0])
	if err != nil {
		return nil, fmt.Errorf("auth parse client leaf cert: %w", err)
	}
	tlsCert.Leaf = x509Cert

	ecdsaKey, ok := tlsCert.PrivateKey.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("auth client private key is not ECDSA")
	}

	return &ClientIdentity{
		Certificate: tlsCert,
		X509Cert:    x509Cert,
		PrivateKey:  ecdsaKey,
		CertPEM:     certPEM,
		KeyPEM:      keyPEM,
		Fingerprint: CertFingerprintSHA256(x509Cert.Raw),
	}, nil
}

func generateSelfSignedECDSACert(commonName string, isServer bool) (certPEM []byte, keyPEM []byte, err error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("ecdsa P-256 keygen: %w", err)
	}

	serial, err := randomSerialNumber()
	if err != nil {
		return nil, nil, err
	}

	now := time.Now().UTC()
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   commonName,
			Organization: []string{"LinuxDeck Remote"},
		},
		NotBefore:             now.Add(-5 * time.Minute),
		NotAfter:              now.Add(DefaultCertValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		BasicConstraintsValid: true,
	}

	if isServer {
		tmpl.IsCA = true
		tmpl.KeyUsage |= x509.KeyUsageCertSign
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth}
		tmpl.DNSNames = []string{"localhost", commonName}
		tmpl.IPAddresses = localIPAddresses()
	} else {
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		return nil, nil, fmt.Errorf("create x509 cert: %w", err)
	}

	keyBytes, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return nil, nil, fmt.Errorf("marshal ecdsa private key: %w", err)
	}

	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyBytes})
	return certPEM, keyPEM, nil
}

func randomSerialNumber() (*big.Int, error) {
	serialLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, serialLimit)
	if err != nil {
		return nil, fmt.Errorf("auth generate serial number: %w", err)
	}
	return serial, nil
}

func localIPAddresses() []net.IP {
	ips := []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, addr := range addrs {
		var ip net.IP
		switch v := addr.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		}
		if ip != nil && !ip.IsLoopback() {
			ips = append(ips, ip)
		}
	}
	return ips
}

func writeSecureFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, DirPerm); err != nil {
		return err
	}
	tmpPath := path + ".tmp"
	f, err := os.OpenFile(tmpPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, FilePerm)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := f.Chmod(FilePerm); err != nil {
		_ = f.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return os.Rename(tmpPath, path)
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

