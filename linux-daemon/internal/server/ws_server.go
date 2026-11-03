package server

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/local/linux-deck-daemon/internal/auth"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	"google.golang.org/protobuf/proto"
)

const (
	// DefaultHeartbeatInterval is the periodic Ping/Pong interval (10 seconds).
	DefaultHeartbeatInterval = 10 * time.Second
	// DefaultWriteTimeout is the maximum duration allowed for writing a WebSocket frame.
	DefaultWriteTimeout = 5 * time.Second
	// DefaultSendBufferSize is the capacity of a client's outbound Envelope channel.
	DefaultSendBufferSize = 256
	// MaxMessageSize limits incoming binary WebSocket messages (1 MiB).
	MaxMessageSize = 1024 * 1024
)

var (
	// ErrSessionClosed is returned when attempting to send on a closed ClientSession.
	ErrSessionClosed = errors.New("server session: client session is closed")
	// ErrSendQueueFull is returned when a client's outbound sendCh is full.
	ErrSendQueueFull = errors.New("server session: client outbound queue is full")
)

// Config holds parameters for the mTLS WebSocket server.
type Config struct {
	ListenAddr        string
	ServerIdentity    *auth.ServerIdentity
	TrustedStore      *auth.TrustedStore
	Router            *Router
	HeartbeatInterval time.Duration
	Logger            *slog.Logger
}

// Server manages the mTLS 1.3 listener and active Protobuf WebSocket sessions.
type Server struct {
	cfg           Config
	router        *Router
	logger        *slog.Logger
	upgrader      websocket.Upgrader
	mu            sync.RWMutex
	listener      net.Listener
	httpServer    *http.Server
	sessions      map[string]*wsSession
	onConnect     []func(ClientSession)
	onDisconnect  []func(ClientSession)
	heartbeatSeq  atomic.Uint64
}

type wsSession struct {
	id              string
	deviceID        string
	deviceName      string
	certFingerprint string
	conn            *websocket.Conn
	ctrlCh          chan *pb.Envelope
	bulkCh          chan *pb.Envelope
	ctx             context.Context
	cancel          context.CancelFunc
	closeOnce       sync.Once
	closed          atomic.Bool
	writeTimeout    time.Duration
	writeMu         sync.Mutex
}

func (s *wsSession) ID() string              { return s.id }
func (s *wsSession) DeviceID() string        { return s.deviceID }
func (s *wsSession) DeviceName() string      { return s.deviceName }
func (s *wsSession) CertFingerprint() string { return s.certFingerprint }
func (s *wsSession) Context() context.Context { return s.ctx }

func (s *wsSession) Send(env *pb.Envelope) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	if env == nil {
		return nil
	}

	if IsHighPriority(env) {
		select {
		case <-s.ctx.Done():
			return ErrSessionClosed
		case s.ctrlCh <- env:
			return nil
		default:
			return ErrSendQueueFull
		}
	}

	select {
	case <-s.ctx.Done():
		return ErrSessionClosed
	case s.bulkCh <- env:
		return nil
	default:
		return ErrSendQueueFull
	}
}

func (s *wsSession) SendImmediate(env *pb.Envelope) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	if env == nil {
		return nil
	}

	raw, err := proto.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}

	s.writeMu.Lock()
	defer s.writeMu.Unlock()

	if s.closed.Load() {
		return ErrSessionClosed
	}

	_ = s.conn.SetWriteDeadline(time.Now().Add(s.writeTimeout))
	if err := s.conn.WriteMessage(websocket.BinaryMessage, raw); err != nil {
		return fmt.Errorf("write immediate binary message: %w", err)
	}
	return nil
}

func (s *wsSession) Close() error {
	var err error
	s.closeOnce.Do(func() {
		s.closed.Store(true)
		s.cancel()
		err = s.conn.Close()
	})
	return err
}

// NewServer constructs a new mTLS 1.3 WebSocket control server.
func NewServer(cfg Config) (*Server, error) {
	if cfg.ServerIdentity == nil {
		return nil, errors.New("server init: ServerIdentity is required")
	}
	if cfg.TrustedStore == nil {
		return nil, errors.New("server init: TrustedStore is required")
	}
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = fmt.Sprintf("0.0.0.0:%d", auth.DefaultControlPort)
	}
	if cfg.HeartbeatInterval <= 0 {
		cfg.HeartbeatInterval = DefaultHeartbeatInterval
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}
	router := cfg.Router
	if router == nil {
		router = NewRouter(logger)
	}

	return &Server{
		cfg:      cfg,
		router:   router,
		logger:   logger,
		sessions: make(map[string]*wsSession),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  16 * 1024,
			WriteBufferSize: 16 * 1024,
			CheckOrigin: func(_ *http.Request) bool {
				// Authentication is enforced at the mTLS 1.3 layer via client certificates.
				return true
			},
		},
	}, nil
}

// Router returns the server's message router.
func (s *Server) Router() *Router {
	return s.router
}

// OnConnect registers a callback invoked whenever a client session is established.
func (s *Server) OnConnect(fn func(ClientSession)) {
	if fn == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onConnect = append(s.onConnect, fn)
}

// OnDisconnect registers a callback invoked whenever a client session closes.
func (s *Server) OnDisconnect(fn func(ClientSession)) {
	if fn == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onDisconnect = append(s.onDisconnect, fn)
}

// ClientCount returns the number of currently connected authenticated clients.
func (s *Server) ClientCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.sessions)
}

// Broadcast enqueues env to all currently connected client sessions.
func (s *Server) Broadcast(env *pb.Envelope) {
	s.mu.RLock()
	list := make([]*wsSession, 0, len(s.sessions))
	for _, sess := range s.sessions {
		list = append(list, sess)
	}
	s.mu.RUnlock()

	for _, sess := range list {
		_ = sess.Send(env)
	}
}

// Addr returns the network address the server is listening on, or nil if not started.
func (s *Server) Addr() net.Addr {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.listener == nil {
		return nil
	}
	return s.listener.Addr()
}

// Start opens the mTLS 1.3 listener and serves binary Protobuf WebSocket connections in the background
// until ctx is cancelled or Shutdown is called.
func (s *Server) Start(ctx context.Context) (net.Addr, error) {
	tlsCfg, err := auth.ServerTLSConfig(s.cfg.ServerIdentity, s.cfg.TrustedStore)
	if err != nil {
		return nil, fmt.Errorf("server build tls config: %w", err)
	}

	tcpLn, err := net.Listen("tcp", s.cfg.ListenAddr)
	if err != nil {
		return nil, fmt.Errorf("server listen %s: %w", s.cfg.ListenAddr, err)
	}
	ln := tls.NewListener(&lowLatencyListener{Listener: tcpLn}, tlsCfg)

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWebSocket)
	mux.HandleFunc("/", s.handleWebSocket)

	httpSrv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	s.mu.Lock()
	s.listener = ln
	s.httpServer = httpSrv
	s.mu.Unlock()

	s.logger.Info("mTLS 1.3 WebSocket server listening", "addr", ln.Addr().String())

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = s.Shutdown(shutdownCtx)
	}()

	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			s.logger.Error("server http loop exited with error", "err", err)
		}
	}()

	return ln.Addr(), nil
}

// Shutdown gracefully closes all active client sessions and stops the mTLS listener.
func (s *Server) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	httpSrv := s.httpServer
	sessions := make([]*wsSession, 0, len(s.sessions))
	for _, sess := range s.sessions {
		sessions = append(sessions, sess)
	}
	s.sessions = make(map[string]*wsSession)
	s.mu.Unlock()

	for _, sess := range sessions {
		_ = sess.Close()
	}

	if httpSrv != nil {
		return httpSrv.Shutdown(ctx)
	}
	return nil
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		http.Error(w, "mTLS client certificate required", http.StatusUnauthorized)
		return
	}

	peerCert := r.TLS.PeerCertificates[0]
	clientFP := auth.CertFingerprintSHA256(peerCert.Raw)
	trustedClient, ok := s.cfg.TrustedStore.GetByFingerprint(clientFP)
	if !ok {
		http.Error(w, "untrusted client certificate", http.StatusForbidden)
		return
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.logger.Warn("websocket upgrade failed", "err", err)
		return
	}

	sessCtx, cancel := context.WithCancel(context.Background())
	sess := &wsSession{
		id:              newSessionID(),
		deviceID:        trustedClient.DeviceID,
		deviceName:      trustedClient.DeviceName,
		certFingerprint: clientFP,
		conn:            conn,
		ctrlCh:          make(chan *pb.Envelope, DefaultControlBufferSize),
		bulkCh:          make(chan *pb.Envelope, DefaultBulkBufferSize),
		ctx:             sessCtx,
		cancel:          cancel,
		writeTimeout:    DefaultWriteTimeout,
	}

	s.registerSession(sess)
	defer s.unregisterSession(sess)

	go s.writeLoop(sess)
	s.readLoop(sess)
}

func (s *Server) registerSession(sess *wsSession) {
	s.mu.Lock()
	s.sessions[sess.id] = sess
	callbacks := append([]func(ClientSession){}, s.onConnect...)
	s.mu.Unlock()

	s.logger.Info("client connected",
		"session_id", sess.id,
		"device_id", sess.deviceID,
		"device_name", sess.deviceName,
	)

	for _, fn := range callbacks {
		fn(sess)
	}
}

func (s *Server) unregisterSession(sess *wsSession) {
	_ = sess.Close()

	s.mu.Lock()
	delete(s.sessions, sess.id)
	callbacks := append([]func(ClientSession){}, s.onDisconnect...)
	s.mu.Unlock()

	s.logger.Info("client disconnected",
		"session_id", sess.id,
		"device_id", sess.deviceID,
	)

	for _, fn := range callbacks {
		fn(sess)
	}
}

func (s *Server) readLoop(sess *wsSession) {
	sess.conn.SetReadLimit(MaxMessageSize)
	_ = sess.conn.SetReadDeadline(time.Now().Add(3 * s.cfg.HeartbeatInterval))
	sess.conn.SetPongHandler(func(string) error {
		_ = sess.conn.SetReadDeadline(time.Now().Add(3 * s.cfg.HeartbeatInterval))
		return nil
	})

	for {
		msgType, data, err := sess.conn.ReadMessage()
		if err != nil {
			return
		}
		_ = sess.conn.SetReadDeadline(time.Now().Add(3 * s.cfg.HeartbeatInterval))

		if msgType != websocket.BinaryMessage {
			s.logger.Warn("ignoring non-binary websocket frame",
				"session_id", sess.id,
				"msg_type", msgType,
			)
			continue
		}

		var env pb.Envelope
		if err := proto.Unmarshal(data, &env); err != nil {
			s.logger.Warn("failed to unmarshal protobuf envelope",
				"session_id", sess.id,
				"err", err,
			)
			continue
		}

		// FIX-05: O Ping é respondido instantaneamente na camada de rede sem enfileiramento
		if ping := env.GetPing(); ping != nil {
			nowMs := time.Now().UnixMilli()
			pongEnv := &pb.Envelope{
				RequestId:   env.RequestId,
				TimestampMs: nowMs,
				Payload: &pb.Envelope_Pong{
					Pong: &pb.Pong{
						Seq:               ping.Seq,
						ClientTimestampMs: ping.ClientTimestampMs,
						ServerTimestampMs: nowMs,
					},
				},
			}
			if err := sess.SendImmediate(pongEnv); err != nil {
				s.logger.Warn("failed to send immediate network-layer pong",
					"session_id", sess.id,
					"err", err,
				)
			}
			continue
		}

		_ = s.router.Dispatch(sess.ctx, sess, &env)
	}
}

func (s *Server) writeLoop(sess *wsSession) {
	ticker := time.NewTicker(s.cfg.HeartbeatInterval)
	defer ticker.Stop()

	for {
		// Strict priority drain: flush all high-priority control envelopes before processing bulk data
		select {
		case <-sess.ctx.Done():
			return
		case env, ok := <-sess.ctrlCh:
			if !ok || env == nil {
				return
			}
			if err := s.writeEnvelope(sess, env); err != nil {
				_ = sess.Close()
				return
			}
			continue
		default:
		}

		select {
		case <-sess.ctx.Done():
			return

		case env, ok := <-sess.ctrlCh:
			if !ok || env == nil {
				return
			}
			if err := s.writeEnvelope(sess, env); err != nil {
				_ = sess.Close()
				return
			}

		case env, ok := <-sess.bulkCh:
			if !ok || env == nil {
				return
			}
			// Pre-check before sending heavy data: if high-priority control arrived, bypass and write it first
			select {
			case ctrlEnv, ok := <-sess.ctrlCh:
				if ok && ctrlEnv != nil {
					if err := s.writeEnvelope(sess, ctrlEnv); err != nil {
						_ = sess.Close()
						return
					}
				}
			default:
			}

			if err := s.writeEnvelope(sess, env); err != nil {
				_ = sess.Close()
				return
			}

		case <-ticker.C:
			seq := s.heartbeatSeq.Add(1)
			nowMs := time.Now().UnixMilli()
			hb := &pb.Envelope{
				RequestId:   fmt.Sprintf("hb-%d", seq),
				TimestampMs: nowMs,
				Payload: &pb.Envelope_Ping{
					Ping: &pb.Ping{
						Seq:               seq,
						ClientTimestampMs: nowMs,
					},
				},
			}
			if err := s.writeEnvelope(sess, hb); err != nil {
				_ = sess.Close()
				return
			}
			sess.writeMu.Lock()
			_ = sess.conn.SetWriteDeadline(time.Now().Add(sess.writeTimeout))
			err := sess.conn.WriteMessage(websocket.PingMessage, nil)
			sess.writeMu.Unlock()
			if err != nil {
				_ = sess.Close()
				return
			}
		}
	}
}

func (s *Server) writeEnvelope(sess *wsSession, env *pb.Envelope) error {
	raw, err := proto.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}

	sess.writeMu.Lock()
	defer sess.writeMu.Unlock()

	if sess.closed.Load() {
		return ErrSessionClosed
	}

	_ = sess.conn.SetWriteDeadline(time.Now().Add(sess.writeTimeout))
	if err := sess.conn.WriteMessage(websocket.BinaryMessage, raw); err != nil {
		return fmt.Errorf("write binary message: %w", err)
	}
	return nil
}

func newSessionID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// TCPKeepAlivePeriod detects dead USB (adb reverse) / Wi-Fi peers quickly without application traffic.
const TCPKeepAlivePeriod = 15 * time.Second

// lowLatencyListener forces TCP_NODELAY (disables Nagle's algorithm) and aggressive TCP keep-alive on
// every accepted connection so small, high-frequency input frames (MouseMoveEvent ~120 Hz) are flushed
// to the wire immediately instead of being delayed up to 40 ms by Nagle + delayed-ACK interaction.
type lowLatencyListener struct {
	net.Listener
}

func (l *lowLatencyListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	ConfigureLowLatencyConn(conn)
	return conn, nil
}

// ConfigureLowLatencyConn enables TCP_NODELAY and keep-alive on TCP connections (no-op otherwise).
func ConfigureLowLatencyConn(conn net.Conn) {
	tcpConn, ok := conn.(*net.TCPConn)
	if !ok {
		return
	}
	_ = tcpConn.SetNoDelay(true)
	_ = tcpConn.SetKeepAliveConfig(net.KeepAliveConfig{
		Enable:   true,
		Idle:     TCPKeepAlivePeriod,
		Interval: 5 * time.Second,
		Count:    3,
	})
}

