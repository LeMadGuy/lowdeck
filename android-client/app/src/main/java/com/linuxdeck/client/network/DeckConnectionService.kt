package com.linuxdeck.client.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linuxdeck.client.protocol.v1.DeckProfileSync
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.HandshakeRequest
import com.linuxdeck.client.protocol.v1.Ping
import com.linuxdeck.client.protocol.v1.Pong
import com.linuxdeck.client.protocol.v1.SessionStatus
import com.linuxdeck.client.protocol.v1.TelemetrySnapshot
import com.linuxdeck.client.security.MtlsKeyManager
import com.linuxdeck.client.security.PairedHostConfig
import com.linuxdeck.client.ui.pairing.PairingActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

/**
 * Unidirectional connection state exposed to all Compose screens via [StateFlow].
 */
sealed interface ConnectionState {
    data class Disconnected(val reason: String? = null) : ConnectionState

    data class Connecting(
        val host: String,
        val port: Int,
        val attempt: Int,
    ) : ConnectionState

    data class Connected(
        val host: String,
        val port: Int,
        val sessionId: String,
        val hostDeviceName: String,
        val latencyMs: Long? = null,
    ) : ConnectionState

    data class Reconnecting(
        val host: String,
        val port: Int,
        val attempt: Int,
        val nextRetryDelayMs: Long,
        val lastError: String? = null,
    ) : ConnectionState

    data class AuthFailed(val message: String) : ConnectionState
}

/**
 * Pure exponential backoff calculator for WebSocket reconnection attempts.
 */
object ExponentialBackoffPolicy {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L
    const val MAX_JITTER_MS = 400L

    fun computeDelayMs(attempt: Int, jitterMs: Long = Random.nextLong(0L, MAX_JITTER_MS)): Long {
        val safeAttempt = (attempt - 1).coerceIn(0, 5)
        val exponential = (INITIAL_DELAY_MS shl safeAttempt).coerceAtMost(MAX_DELAY_MS)
        return (exponential + jitterMs.coerceAtLeast(0L)).coerceAtMost(MAX_DELAY_MS)
    }
}

/**
 * Core mTLS 1.3 + Binary Protobuf WebSocket session manager.
 *
 * Runs all socket, TLS, and Protobuf serialization operations strictly on [ioDispatcher] (`Dispatchers.IO`).
 */
class DeckSessionClient(
    private val keyManager: MtlsKeyManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clientVersion: String = "1.0.0",
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val activeWebSocket = AtomicReference<WebSocket?>(null)
    private val outboundChannel = Channel<Envelope>(capacity = 256)
    private val pingSeq = AtomicLong(0L)

    private var connectionLoopJob: Job? = null
    private var activeConfig: PairedHostConfig? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingEnvelopes = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val incomingEnvelopes: SharedFlow<Envelope> = _incomingEnvelopes.asSharedFlow()

    private val _latestDeckProfile = MutableStateFlow<DeckProfileSync?>(null)
    val latestDeckProfile: StateFlow<DeckProfileSync?> = _latestDeckProfile.asStateFlow()

    private val _latestTelemetry = MutableStateFlow<TelemetrySnapshot?>(null)
    val latestTelemetry: StateFlow<TelemetrySnapshot?> = _latestTelemetry.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    init {
        scope.launch {
            for (envelope in outboundChannel) {
                sendEnvelopeInternal(envelope)
            }
        }
    }

    /**
     * Starts or restarts the persistent mTLS 1.3 WebSocket connection loop with exponential backoff.
     */
    fun connect(config: PairedHostConfig) {
        activeConfig = config
        connectionLoopJob?.cancel()
        activeWebSocket.getAndSet(null)?.close(1000, "Reconfiguring connection")

        connectionLoopJob = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                _connectionState.value = ConnectionState.Connecting(
                    host = config.host,
                    port = config.port,
                    attempt = attempt,
                )

                val outcome = runSingleSession(config)
                when (outcome) {
                    is SessionOutcome.CleanDisconnect -> {
                        _connectionState.value = ConnectionState.Disconnected(outcome.reason)
                        return@launch
                    }

                    is SessionOutcome.FatalAuthError -> {
                        _connectionState.value = ConnectionState.AuthFailed(outcome.message)
                        return@launch
                    }

                    is SessionOutcome.RetryableFailure -> {
                        if (outcome.wasConnected) {
                            // Reset attempt counter if the session had successfully established before dropping.
                            attempt = 1
                        }
                        val delayMs = ExponentialBackoffPolicy.computeDelayMs(attempt)
                        _connectionState.value = ConnectionState.Reconnecting(
                            host = config.host,
                            port = config.port,
                            attempt = attempt,
                            nextRetryDelayMs = delayMs,
                            lastError = outcome.error,
                        )
                        delay(delayMs)
                    }
                }
            }
        }
    }

    /**
     * Stops reconnection and closes the active WebSocket cleanly on [ioDispatcher].
     */
    fun disconnect(reason: String = "Desconectado") {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        activeConfig = null
        scope.launch {
            activeWebSocket.getAndSet(null)?.close(1000, reason)
            _connectionState.value = ConnectionState.Disconnected(reason)
        }
    }

    /**
     * Enqueues an [Envelope] for non-blocking transmission on [Dispatchers.IO].
     */
    fun sendAsync(envelope: Envelope): Boolean =
        outboundChannel.trySend(envelope).isSuccess

    /**
     * Marshals and sends a binary Protobuf [Envelope] on [ioDispatcher].
     */
    suspend fun sendEnvelope(envelope: Envelope): Boolean = withContext(ioDispatcher) {
        sendEnvelopeInternal(envelope)
    }

    /**
     * Sends an application-level Protobuf `Ping` to refresh RTT latency measurements.
     */
    fun sendPingNow() {
        val nowMs = System.currentTimeMillis()
        val seq = pingSeq.incrementAndGet()
        val env = Envelope.newBuilder()
            .setRequestId("ping-$seq")
            .setTimestampMs(nowMs)
            .setPing(
                Ping.newBuilder()
                    .setSeq(seq)
                    .setClientTimestampMs(nowMs)
                    .build(),
            )
            .build()
        sendAsync(env)
    }

    fun close() {
        disconnect("Serviço encerrado")
        outboundChannel.close()
        scope.cancel()
    }

    private suspend fun sendEnvelopeInternal(envelope: Envelope): Boolean = withContext(ioDispatcher) {
        val ws = activeWebSocket.get() ?: return@withContext false
        val bytes = envelope.toByteArray().toByteString()
        ws.send(bytes)
    }

    private suspend fun runSingleSession(config: PairedHostConfig): SessionOutcome = withContext(ioDispatcher) {
        val sessionEnded = CompletableDeferred<SessionOutcome>()
        var handshakeAccepted = false

        val okHttpClient = try {
            keyManager.buildPinnedOkHttpClient(
                expectedServerFingerprintSha256 = config.serverFingerprintSha256,
                deviceName = config.deviceName,
                connectTimeoutSeconds = 8L,
                pingIntervalSeconds = 15L,
            )
        } catch (err: Exception) {
            return@withContext SessionOutcome.FatalAuthError(
                "Erro ao inicializar mTLS 1.3: ${err.message}",
            )
        }

        val wsUrl = "wss://${config.host}:${config.port}/ws"
        val request = Request.Builder().url(wsUrl).build()

        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(CLIENT_PING_INTERVAL_MS)
                if (_connectionState.value is ConnectionState.Connected) {
                    sendPingNow()
                }
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeWebSocket.set(webSocket)
                scope.launch {
                    val nowMs = System.currentTimeMillis()
                    val handshakeEnv = Envelope.newBuilder()
                        .setRequestId("hs-${UUID.randomUUID()}")
                        .setTimestampMs(nowMs)
                        .setHandshakeRequest(
                            HandshakeRequest.newBuilder()
                                .setProtocolVersion(PROTOCOL_VERSION)
                                .setDeviceId(config.deviceId)
                                .setDeviceName(config.deviceName)
                                .setClientVersion(clientVersion)
                                .build(),
                        )
                        .build()
                    sendEnvelopeInternal(handshakeEnv)
                    sendPingNow()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    val env = runCatching { Envelope.parseFrom(bytes.toByteArray()) }
                        .getOrNull() ?: return@launch

                    when (env.payloadCase) {
                        Envelope.PayloadCase.HANDSHAKE_RESPONSE -> {
                            val resp = env.handshakeResponse
                            if (resp.accepted && resp.status == SessionStatus.SESSION_ACTIVE) {
                                handshakeAccepted = true
                                _connectionState.value = ConnectionState.Connected(
                                    host = config.host,
                                    port = config.port,
                                    sessionId = resp.sessionId,
                                    hostDeviceName = resp.deviceName.ifEmpty { config.host },
                                    latencyMs = _latencyMs.value,
                                )
                            } else {
                                webSocket.close(1008, "Handshake rejected")
                                sessionEnded.complete(
                                    SessionOutcome.FatalAuthError(
                                        resp.message.ifEmpty { "Sessão rejeitada pelo daemon (${resp.status})." },
                                    ),
                                )
                            }
                        }

                        Envelope.PayloadCase.PING -> {
                            val ping = env.ping
                            val nowMs = System.currentTimeMillis()
                            val pongEnv = Envelope.newBuilder()
                                .setRequestId(env.requestId)
                                .setTimestampMs(nowMs)
                                .setPong(
                                    Pong.newBuilder()
                                        .setSeq(ping.seq)
                                        .setClientTimestampMs(ping.clientTimestampMs)
                                        .setServerTimestampMs(nowMs)
                                        .build(),
                                )
                                .build()
                            sendEnvelopeInternal(pongEnv)
                        }

                        Envelope.PayloadCase.PONG -> {
                            val pong = env.pong
                            val rtt = (System.currentTimeMillis() - pong.clientTimestampMs).coerceAtLeast(0L)
                            _latencyMs.value = rtt
                            val cur = _connectionState.value
                            if (cur is ConnectionState.Connected) {
                                _connectionState.value = cur.copy(latencyMs = rtt)
                            }
                        }

                        Envelope.PayloadCase.DECK_PROFILE_SYNC -> {
                            _latestDeckProfile.value = env.deckProfileSync
                        }

                        Envelope.PayloadCase.TELEMETRY_SNAPSHOT -> {
                            _latestTelemetry.value = env.telemetrySnapshot
                        }

                        else -> Unit
                    }

                    _incomingEnvelopes.emit(env)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeWebSocket.compareAndSet(webSocket, null)
                if (code == 1000) {
                    sessionEnded.complete(SessionOutcome.CleanDisconnect(reason))
                } else {
                    sessionEnded.complete(
                        SessionOutcome.RetryableFailure(
                            error = reason.ifEmpty { "Conexão encerrada (código $code)" },
                            wasConnected = handshakeAccepted,
                        ),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeWebSocket.compareAndSet(webSocket, null)
                val httpCode = response?.code
                if (httpCode == 401 || httpCode == 403) {
                    sessionEnded.complete(
                        SessionOutcome.FatalAuthError(
                            "Certificado cliente recusado pelo daemon (HTTP $httpCode). Pareie o dispositivo novamente.",
                        ),
                    )
                    return
                }
                if (t is SSLHandshakeException &&
                    t.message.orEmpty().contains("Certificate Pinning", ignoreCase = true)
                ) {
                    sessionEnded.complete(
                        SessionOutcome.FatalAuthError(
                            t.message ?: "Falha de Certificate Pinning SHA-256 do servidor.",
                        ),
                    )
                    return
                }
                sessionEnded.complete(
                    SessionOutcome.RetryableFailure(
                        error = t.message ?: "Falha de rede WebSocket",
                        wasConnected = handshakeAccepted,
                    ),
                )
            }
        }

        val ws = okHttpClient.newWebSocket(request, listener)
        try {
            sessionEnded.await()
        } finally {
            heartbeatJob.cancel()
            activeWebSocket.compareAndSet(ws, null)
            ws.cancel()
        }
    }

    private sealed interface SessionOutcome {
        data class CleanDisconnect(val reason: String?) : SessionOutcome
        data class RetryableFailure(val error: String?, val wasConnected: Boolean) : SessionOutcome
        data class FatalAuthError(val message: String) : SessionOutcome
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val CLIENT_PING_INTERVAL_MS = 10_000L
    }
}

/**
 * Persistent Android `ForegroundService` keeping the mTLS 1.3 + Protobuf WebSocket session alive
 * across all four screens (Macro Deck, Trackpad, WebRTC Remote Desktop, PTY Terminal).
 */
class DeckConnectionService : Service() {

    inner class LocalBinder : Binder() {
        val service: DeckConnectionService get() = this@DeckConnectionService
        val sessionClient: DeckSessionClient get() = obtainClient(applicationContext)
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null

    val connectionState: StateFlow<ConnectionState>
        get() = obtainClient(applicationContext).connectionState

    val incomingEnvelopes: SharedFlow<Envelope>
        get() = obtainClient(applicationContext).incomingEnvelopes

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val client = obtainClient(applicationContext)

        stateObserverJob = serviceScope.launch {
            client.connectionState.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val client = obtainClient(applicationContext)
        val keyMgr = obtainKeyManager(applicationContext)

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                client.disconnect("Desconectado pela notificação")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startInForeground(client.connectionState.value)
                val overrideHost = intent?.getStringExtra(EXTRA_OVERRIDE_HOST)?.trim()
                val overridePort = intent?.getIntExtra(EXTRA_OVERRIDE_PORT, -1) ?: -1

                serviceScope.launch(Dispatchers.IO) {
                    val baseCfg = keyMgr.pairedHost.value ?: return@launch
                    val targetCfg = if (!overrideHost.isNullOrEmpty()) {
                        val port = if (overridePort in 1..65535) overridePort else baseCfg.port
                        keyMgr.updateEndpointAddress(overrideHost, port) ?: baseCfg.copy(
                            host = overrideHost,
                            port = port,
                        )
                    } else {
                        baseCfg
                    }
                    client.connect(targetCfg)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stateObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(state: ConnectionState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ConnectionState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val contentText = when (state) {
            is ConnectionState.Connected -> {
                val rtt = state.latencyMs?.let { " • ${it}ms" }.orEmpty()
                "Conectado a ${state.hostDeviceName} (${state.host}:${state.port})$rtt"
            }

            is ConnectionState.Connecting ->
                "Conectando a ${state.host}:${state.port} (tentativa ${state.attempt})..."

            is ConnectionState.Reconnecting ->
                "Reconectando a ${state.host}:${state.port} em ${state.nextRetryDelayMs / 1000}s..."

            is ConnectionState.AuthFailed ->
                "Autenticação recusada: ${state.message}"

            is ConnectionState.Disconnected ->
                state.reason ?: "Desconectado"
        }

        val openIntent = Intent(this, PairingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, DeckConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Linux Deck & Remote Control")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Desconectar",
                disconnectPending,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Conexão Linux Deck",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém a sessão segura mTLS 1.3 + WebSocket com o PC Linux."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.linuxdeck.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.linuxdeck.client.action.DISCONNECT"
        const val EXTRA_OVERRIDE_HOST = "extra_override_host"
        const val EXTRA_OVERRIDE_PORT = "extra_override_port"

        private const val NOTIFICATION_CHANNEL_ID = "linuxdeck_connection_channel"
        private const val NOTIFICATION_ID = 8443

        @Volatile
        private var sharedKeyManager: MtlsKeyManager? = null

        @Volatile
        private var sharedClient: DeckSessionClient? = null

        fun obtainKeyManager(context: Context): MtlsKeyManager =
            sharedKeyManager ?: synchronized(this) {
                sharedKeyManager ?: MtlsKeyManager(context.applicationContext).also {
                    sharedKeyManager = it
                }
            }

        fun obtainClient(context: Context): DeckSessionClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: DeckSessionClient(obtainKeyManager(context)).also {
                    sharedClient = it
                }
            }

        /**
         * Starts the persistent [DeckConnectionService] and initiates connection to the paired host.
         */
        fun startAndConnect(
            context: Context,
            overrideHost: String? = null,
            overridePort: Int? = null,
        ) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DeckConnectionService::class.java).apply {
                action = ACTION_CONNECT
                if (!overrideHost.isNullOrBlank()) {
                    putExtra(EXTRA_OVERRIDE_HOST, overrideHost.trim())
                }
                if (overridePort != null && overridePort in 1..65535) {
                    putExtra(EXTRA_OVERRIDE_PORT, overridePort)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }

        /**
         * Disconnects and stops the persistent [DeckConnectionService].
         */
        fun stop(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DeckConnectionService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            appCtx.startService(intent)
        }
    }
}

