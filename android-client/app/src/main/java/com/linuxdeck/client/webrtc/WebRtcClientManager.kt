package com.linuxdeck.client.webrtc

import android.content.Context
import androidx.compose.runtime.Immutable
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.DeckSessionClient
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.SignalType
import com.linuxdeck.client.protocol.v1.WebRtcSignal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Requested video/audio capture parameters sent in `WebRtcSignal{START_STREAM}`.
 */
@Immutable
data class RemoteStreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrateKbps: Int = 8_000,
    val audioEnabled: Boolean = true,
)

/**
 * Decoded live stream dimensions reported by `SurfaceViewRenderer` (`RendererEvents`).
 */
@Immutable
data class VideoResolution(
    val width: Int = 1920,
    val height: Int = 1080,
)

/**
 * Unidirectional state of the WebRTC Remote Desktop session.
 */
sealed interface StreamConnectionState {
    data object Idle : StreamConnectionState
    data object RequestingOffer : StreamConnectionState
    data object NegotiatingIce : StreamConnectionState
    data class Streaming(
        val firstFrameRendered: Boolean,
        val videoWidth: Int,
        val videoHeight: Int,
    ) : StreamConnectionState
    data object Stopped : StreamConnectionState
    data class Failed(val reason: String) : StreamConnectionState
}

/**
 * Signaling transport abstraction over the authenticated mTLS 1.3 WebSocket channel.
 */
interface WebRtcSignalingTransport {
    val incomingEnvelopes: SharedFlow<Envelope>
    suspend fun sendEnvelope(envelope: Envelope): Boolean
    fun sendAsync(envelope: Envelope): Boolean
}

class SessionClientSignalingTransport(
    private val sessionClient: DeckSessionClient,
) : WebRtcSignalingTransport {
    override val incomingEnvelopes: SharedFlow<Envelope> = sessionClient.incomingEnvelopes
    override suspend fun sendEnvelope(envelope: Envelope): Boolean = sessionClient.sendEnvelope(envelope)
    override fun sendAsync(envelope: Envelope): Boolean = sessionClient.sendAsync(envelope)
}

/**
 * Pure JSON encoder/decoder matching `pion/webrtc/v4` (`signaling_handler.go`) for `SDP_OFFER`,
 * `SDP_ANSWER`, and `ICE_CANDIDATE` payloads inside `WebRtcSignal.payload_json`.
 */
object WebRtcSignalJsonCodec {

    fun parseSessionDescription(payloadJson: String, fallbackType: SessionDescription.Type): SessionDescription {
        val obj = JSONObject(payloadJson.trim())
        val sdp = obj.optString("sdp", "").trim()
        require(sdp.isNotEmpty()) { "WebRTC SDP payload_json is missing 'sdp'" }

        val rawType = obj.optString("type", "").trim().lowercase(Locale.ROOT)
        val resolvedType = when (rawType) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            "rollback" -> SessionDescription.Type.ROLLBACK
            else -> fallbackType
        }
        return SessionDescription(resolvedType, sdp)
    }

    fun encodeSessionDescription(desc: SessionDescription): String {
        val obj = JSONObject()
        obj.put("type", desc.type.canonicalForm())
        obj.put("sdp", desc.description)
        return obj.toString()
    }

    fun parseIceCandidate(payloadJson: String): IceCandidate? {
        val trimmed = payloadJson.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "{}") {
            return null
        }
        val obj = JSONObject(trimmed)
        val candSdp = obj.optString("candidate", "").ifBlank {
            obj.optString("sdp", "")
        }.trim()
        if (candSdp.isEmpty()) {
            return null
        }
        val sdpMid = if (obj.isNull("sdpMid")) "" else obj.optString("sdpMid", "")
        val sdpMLineIndex = obj.optInt("sdpMLineIndex", 0)
        return IceCandidate(sdpMid, sdpMLineIndex, candSdp)
    }

    fun encodeIceCandidate(candidate: IceCandidate): String {
        val obj = JSONObject()
        obj.put("candidate", candidate.sdp)
        obj.put("sdpMid", candidate.sdpMid ?: "")
        obj.put("sdpMLineIndex", candidate.sdpMLineIndex)
        return obj.toString()
    }
}

/**
 * Manages hardware-accelerated WebRTC video/audio reception (`EglBase` + `DefaultVideoDecoderFactory`),
 * SDP Offer/Answer + Trickle ICE signaling over mTLS WebSocket, and `SurfaceViewRenderer` lifecycle.
 */
class WebRtcClientManager(
    context: Context,
    private val transport: WebRtcSignalingTransport = SessionClientSignalingTransport(
        DeckConnectionService.obtainClient(context.applicationContext),
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val released = AtomicBoolean(false)

    private var eglBase: EglBase? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var activeVideoTrack: VideoTrack? = null
    private var activeAudioTrack: AudioTrack? = null
    private var boundRenderer: SurfaceViewRenderer? = null
    private val initializedRenderers = HashSet<SurfaceViewRenderer>()

    private var remoteDescriptionSet: Boolean = false
    private val pendingIceCandidates = ArrayList<IceCandidate>()
    private var signalingCollectorJob: Job? = null

    private val _streamState = MutableStateFlow<StreamConnectionState>(StreamConnectionState.Idle)
    val streamState: StateFlow<StreamConnectionState> = _streamState.asStateFlow()

    private val _videoResolution = MutableStateFlow(VideoResolution(1920, 1080))
    val videoResolution: StateFlow<VideoResolution> = _videoResolution.asStateFlow()

    private val _isAudioMuted = MutableStateFlow(false)
    val isAudioMuted: StateFlow<Boolean> = _isAudioMuted.asStateFlow()

    init {
        ensureFactoryInitialized()
        signalingCollectorJob = scope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                if (envelope.payloadCase == Envelope.PayloadCase.WEBRTC_SIGNAL) {
                    handleIncomingSignal(envelope.webrtcSignal)
                }
            }
        }
    }

    val eglBaseContext: EglBase.Context?
        get() = synchronized(lock) { eglBase?.eglBaseContext }

    private fun ensureFactoryInitialized() {
        synchronized(lock) {
            if (released.get() || peerConnectionFactory != null) return

            initializeGlobalPeerConnectionFactory(appContext)

            val createdEgl = EglBase.create()
            val adm = JavaAudioDeviceModule.builder(appContext)
                .createAudioDeviceModule()
            val decoderFactory = DefaultVideoDecoderFactory(createdEgl.eglBaseContext)
            val encoderFactory = DefaultVideoEncoderFactory(
                createdEgl.eglBaseContext,
                /* enableIntelVp8Encoder = */ true,
                /* enableH264HighProfile = */ true,
            )

            val factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setAudioDeviceModule(adm)
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory()

            eglBase = createdEgl
            audioDeviceModule = adm
            peerConnectionFactory = factory
        }
    }

    /**
     * Initializes [renderer] with the hardware [EglBase.Context] and binds the live [VideoTrack] sink.
     */
    fun attachRenderer(renderer: SurfaceViewRenderer) {
        synchronized(lock) {
            if (released.get()) return
            ensureFactoryInitialized()
            val eglCtx = eglBase?.eglBaseContext ?: return

            if (boundRenderer != null && boundRenderer !== renderer) {
                activeVideoTrack?.removeSink(boundRenderer)
            }

            if (initializedRenderers.add(renderer)) {
                renderer.init(
                    eglCtx,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            val res = _videoResolution.value
                            _streamState.value = StreamConnectionState.Streaming(
                                firstFrameRendered = true,
                                videoWidth = res.width,
                                videoHeight = res.height,
                            )
                        }

                        override fun onFrameResolutionChanged(
                            videoWidth: Int,
                            videoHeight: Int,
                            rotation: Int,
                        ) {
                            val effectiveW = if (rotation == 90 || rotation == 270) videoHeight else videoWidth
                            val effectiveH = if (rotation == 90 || rotation == 270) videoWidth else videoHeight
                            if (effectiveW > 0 && effectiveH > 0) {
                                _videoResolution.value = VideoResolution(effectiveW, effectiveH)
                                val current = _streamState.value
                                if (current is StreamConnectionState.Streaming) {
                                    _streamState.value = current.copy(
                                        videoWidth = effectiveW,
                                        videoHeight = effectiveH,
                                    )
                                }
                            }
                        }
                    },
                )
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer.setEnableHardwareScaler(true)
            }

            boundRenderer = renderer
            activeVideoTrack?.addSink(renderer)
        }
    }

    /**
     * Detaches the video sink from [renderer] and calls `SurfaceViewRenderer.release()` to prevent
     * EGL surface and GPU memory leaks.
     */
    fun detachAndReleaseRenderer(renderer: SurfaceViewRenderer?) {
        if (renderer == null) return
        synchronized(lock) {
            runCatching { activeVideoTrack?.removeSink(renderer) }
            if (boundRenderer === renderer) {
                boundRenderer = null
            }
            if (initializedRenderers.remove(renderer)) {
                runCatching { renderer.clearImage() }
                runCatching { renderer.release() }
            }
        }
    }

    /**
     * Starts a fresh WebRTC Remote Desktop session by creating a `PeerConnection` and dispatching
     * `WebRtcSignal{START_STREAM}` on [ioDispatcher].
     */
    fun startStream(config: RemoteStreamConfig = RemoteStreamConfig()) {
        if (released.get()) return
        scope.launch {
            closePeerConnectionLocked()

            synchronized(lock) {
                if (released.get()) return@launch
                ensureFactoryInitialized()
                val factory = peerConnectionFactory ?: run {
                    _streamState.value = StreamConnectionState.Failed("Falha ao inicializar PeerConnectionFactory")
                    return@launch
                }

                val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }

                val createdPc = factory.createPeerConnection(rtcConfig, createPeerObserver())
                if (createdPc == null) {
                    _streamState.value = StreamConnectionState.Failed("Não foi possível criar PeerConnection WebRTC")
                    return@launch
                }

                val recvOnlyInit = RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                )
                createdPc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnlyInit)
                createdPc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnlyInit)

                peerConnection = createdPc
                remoteDescriptionSet = false
                pendingIceCandidates.clear()
                _videoResolution.value = VideoResolution(
                    width = config.width.coerceAtLeast(320),
                    height = config.height.coerceAtLeast(240),
                )
                _streamState.value = StreamConnectionState.RequestingOffer
            }

            val startSignal = WebRtcSignal.newBuilder()
                .setType(SignalType.START_STREAM)
                .setWidth(config.width.coerceAtLeast(320))
                .setHeight(config.height.coerceAtLeast(240))
                .setFps(config.fps.coerceIn(15, 120))
                .setBitrateKbps(config.bitrateKbps.coerceAtLeast(500))
                .setAudioEnabled(config.audioEnabled)
                .build()

            val sent = sendSignalEnvelope(startSignal)
            if (!sent) {
                _streamState.value = StreamConnectionState.Failed(
                    "Sem conexão mTLS ativa com o daemon Linux para iniciar o stream WebRTC.",
                )
            }
        }
    }

    /**
     * Toggles local playback mute on the remote Opus audio track.
     */
    fun setAudioMuted(muted: Boolean) {
        synchronized(lock) {
            _isAudioMuted.value = muted
            activeAudioTrack?.setEnabled(!muted)
        }
    }

    /**
     * Stops the active stream, closes `PeerConnection`, and transmits `WebRtcSignal{STOP_STREAM}`.
     */
    fun stopStream(sendStopSignal: Boolean = true) {
        closePeerConnectionLocked()
        _streamState.value = StreamConnectionState.Stopped

        if (sendStopSignal) {
            val stopEnvelope = Envelope.newBuilder()
                .setRequestId("webrtc-stop-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setWebrtcSignal(
                    WebRtcSignal.newBuilder()
                        .setType(SignalType.STOP_STREAM)
                        .build(),
                )
                .build()
            transport.sendAsync(stopEnvelope)
        }
    }

    /**
     * Releases all native WebRTC/EGL resources (`SurfaceViewRenderer.release()`,
     * `PeerConnection.close()`, `PeerConnectionFactory.dispose()`, `EglBase.release()`) and sends
     * `WebRtcSignal{STOP_STREAM}`. Safe to call multiple times.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return

        // Always notify the Linux daemon to stop PipeWire/GStreamer encoding immediately.
        val stopEnvelope = Envelope.newBuilder()
            .setRequestId("webrtc-stop-${UUID.randomUUID()}")
            .setTimestampMs(System.currentTimeMillis())
            .setWebrtcSignal(
                WebRtcSignal.newBuilder()
                    .setType(SignalType.STOP_STREAM)
                    .build(),
            )
            .build()
        transport.sendAsync(stopEnvelope)

        signalingCollectorJob?.cancel()
        signalingCollectorJob = null

        synchronized(lock) {
            val currentRenderer = boundRenderer
            boundRenderer = null
            if (currentRenderer != null) {
                runCatching { activeVideoTrack?.removeSink(currentRenderer) }
            }
            for (renderer in initializedRenderers.toList()) {
                runCatching { renderer.clearImage() }
                runCatching { renderer.release() }
            }
            initializedRenderers.clear()

            closePeerConnectionLocked()

            runCatching { peerConnectionFactory?.dispose() }
            peerConnectionFactory = null

            runCatching { audioDeviceModule?.release() }
            audioDeviceModule = null

            runCatching { eglBase?.release() }
            eglBase = null
        }

        scope.cancel()
    }

    private fun closePeerConnectionLocked() {
        synchronized(lock) {
            val renderer = boundRenderer
            if (renderer != null) {
                runCatching { activeVideoTrack?.removeSink(renderer) }
            }
            activeVideoTrack = null
            activeAudioTrack = null
            remoteDescriptionSet = false
            pendingIceCandidates.clear()

            val pc = peerConnection
            peerConnection = null
            if (pc != null) {
                runCatching { pc.close() }
                runCatching { pc.dispose() }
            }
        }
    }

    private fun handleIncomingSignal(signal: WebRtcSignal) {
        if (released.get()) return
        when (signal.type) {
            SignalType.SDP_OFFER -> onRemoteOfferReceived(signal.payloadJson)
            SignalType.ICE_CANDIDATE -> onRemoteIceCandidateReceived(signal.payloadJson)
            SignalType.STOP_STREAM -> {
                closePeerConnectionLocked()
                _streamState.value = StreamConnectionState.Stopped
            }
            else -> Unit
        }
    }

    private fun onRemoteOfferReceived(payloadJson: String) {
        val offerDesc = runCatching {
            WebRtcSignalJsonCodec.parseSessionDescription(payloadJson, SessionDescription.Type.OFFER)
        }.getOrElse { err ->
            _streamState.value = StreamConnectionState.Failed("SDP Offer inválido: ${err.message}")
            return
        }

        val pc = synchronized(lock) { peerConnection } ?: return
        _streamState.value = StreamConnectionState.NegotiatingIce

        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    val drainedCandidates: List<IceCandidate>
                    val activePc: PeerConnection?
                    synchronized(lock) {
                        activePc = peerConnection
                        if (activePc !== pc) return
                        remoteDescriptionSet = true
                        drainedCandidates = ArrayList(pendingIceCandidates)
                        pendingIceCandidates.clear()
                    }
                    drainedCandidates.forEach { cand -> activePc?.addIceCandidate(cand) }
                    createAndSendAnswer(pc)
                }

                override fun onSetFailure(error: String?) {
                    _streamState.value = StreamConnectionState.Failed(
                        "Falha ao aplicar SDP Offer remoto: ${error.orEmpty()}",
                    )
                }
            },
            offerDesc,
        )
    }

    private fun createAndSendAnswer(pc: PeerConnection) {
        pc.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val answer = desc ?: return
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                val answerJson = WebRtcSignalJsonCodec.encodeSessionDescription(answer)
                                scope.launch {
                                    sendSignalEnvelope(
                                        WebRtcSignal.newBuilder()
                                            .setType(SignalType.SDP_ANSWER)
                                            .setPayloadJson(answerJson)
                                            .build(),
                                    )
                                }
                            }

                            override fun onSetFailure(error: String?) {
                                _streamState.value = StreamConnectionState.Failed(
                                    "Falha ao definir SDP Answer local: ${error.orEmpty()}",
                                )
                            }
                        },
                        answer,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    _streamState.value = StreamConnectionState.Failed(
                        "Falha ao gerar SDP Answer: ${error.orEmpty()}",
                    )
                }
            },
            MediaConstraints(),
        )
    }

    private fun onRemoteIceCandidateReceived(payloadJson: String) {
        val candidate = runCatching {
            WebRtcSignalJsonCodec.parseIceCandidate(payloadJson)
        }.getOrNull() ?: return

        val immediatePc: PeerConnection? = synchronized(lock) {
            val pc = peerConnection ?: return
            if (remoteDescriptionSet) {
                pc
            } else {
                pendingIceCandidates.add(candidate)
                null
            }
        }
        immediatePc?.addIceCandidate(candidate)
    }

    private fun createPeerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    val res = _videoResolution.value
                    val current = _streamState.value
                    if (current !is StreamConnectionState.Streaming) {
                        _streamState.value = StreamConnectionState.Streaming(
                            firstFrameRendered = false,
                            videoWidth = res.width,
                            videoHeight = res.height,
                        )
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    _streamState.value = StreamConnectionState.Failed("Negociação ICE WebRTC falhou")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    if (!released.get()) {
                        _streamState.value = StreamConnectionState.Failed("Stream WebRTC desconectado")
                    }
                }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            val cand = candidate ?: return
            if (released.get()) return
            val json = WebRtcSignalJsonCodec.encodeIceCandidate(cand)
            val sig = WebRtcSignal.newBuilder()
                .setType(SignalType.ICE_CANDIDATE)
                .setPayloadJson(json)
                .build()
            scope.launch {
                sendSignalEnvelope(sig)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { attachIncomingVideoTrack(it) }
            stream?.audioTracks?.firstOrNull()?.let { attachIncomingAudioTrack(it) }
        }

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(dataChannel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            when (val track = receiver?.track()) {
                is VideoTrack -> attachIncomingVideoTrack(track)
                is AudioTrack -> attachIncomingAudioTrack(track)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val track = transceiver?.receiver?.track()) {
                is VideoTrack -> attachIncomingVideoTrack(track)
                is AudioTrack -> attachIncomingAudioTrack(track)
            }
        }
    }

    private fun attachIncomingVideoTrack(videoTrack: VideoTrack) {
        synchronized(lock) {
            if (released.get() || activeVideoTrack === videoTrack) return
            val renderer = boundRenderer
            if (renderer != null && activeVideoTrack != null) {
                runCatching { activeVideoTrack?.removeSink(renderer) }
            }
            videoTrack.setEnabled(true)
            activeVideoTrack = videoTrack
            if (renderer != null) {
                videoTrack.addSink(renderer)
            }
        }
    }

    private fun attachIncomingAudioTrack(audioTrack: AudioTrack) {
        synchronized(lock) {
            if (released.get()) return
            activeAudioTrack = audioTrack
            audioTrack.setEnabled(!_isAudioMuted.value)
        }
    }

    private suspend fun sendSignalEnvelope(signal: WebRtcSignal): Boolean {
        val env = Envelope.newBuilder()
            .setRequestId("webrtc-${UUID.randomUUID()}")
            .setTimestampMs(System.currentTimeMillis())
            .setWebrtcSignal(signal)
            .build()
        return transport.sendEnvelope(env)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private val factoryInitialized = AtomicBoolean(false)

        private fun initializeGlobalPeerConnectionFactory(appContext: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
            }
        }
    }
}

