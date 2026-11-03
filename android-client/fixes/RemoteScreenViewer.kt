package com.linuxdeck.client.fixes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.SignalType
import com.linuxdeck.client.webrtc.StreamConnectionState
import com.linuxdeck.client.webrtc.WebRtcClientManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.SurfaceViewRenderer

/**
 * FIX-07: Controller managing dual-mode Remote Desktop presentation.
 *
 * Supports transparent fallback to compressed JPEG frames over the existing WebSocket control
 * channel if WebRTC negotiation takes longer than 3 seconds or fails in closed/offline networks.
 */
class RemoteScreenViewerController(
    private val incomingEnvelopes: SharedFlow<Envelope>,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val bitmapDecoder: (ByteArray) -> Bitmap? = { data ->
        BitmapFactory.decodeByteArray(data, 0, data.size)
    },
) {
    private val _fallbackBitmap = MutableStateFlow<Bitmap?>(null)
    val fallbackBitmap: StateFlow<Bitmap?> = _fallbackBitmap.asStateFlow()

    private val _isFallbackActive = MutableStateFlow(false)
    val isFallbackActive: StateFlow<Boolean> = _isFallbackActive.asStateFlow()

    private val _isWebRtcStreaming = MutableStateFlow(false)
    val isWebRtcStreaming: StateFlow<Boolean> = _isWebRtcStreaming.asStateFlow()

    private val _resolution = MutableStateFlow(IntSize(1280, 720))
    val resolution: StateFlow<IntSize> = _resolution.asStateFlow()

    private var collectorJob: Job? = null

    init {
        startCollecting()
    }

    fun startCollecting() {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            incomingEnvelopes.collect { envelope ->
                handleIncomingEnvelope(envelope)
            }
        }
    }

    suspend fun handleIncomingEnvelope(envelope: Envelope) {
        when (envelope.payloadCase) {
            Envelope.PayloadCase.SCREEN_FRAME -> {
                val frame = envelope.screenFrame
                val data = frame.data.toByteArray()
                if (data.isNotEmpty()) {
                    val bmp = withContext(computeDispatcher) {
                        bitmapDecoder(data)
                    }
                    if (bmp != null) {
                        _fallbackBitmap.value = bmp
                        _isFallbackActive.value = true
                        _resolution.value = IntSize(frame.width.toInt(), frame.height.toInt())
                    }
                }
            }

            Envelope.PayloadCase.WEBRTC_SIGNAL -> {
                val sig = envelope.webrtcSignal
                if (sig.type == SignalType.FALLBACK_ACTIVE) {
                    _isFallbackActive.value = true
                }
            }

            else -> Unit
        }
    }

    fun onWebRtcStateChanged(state: StreamConnectionState) {
        when (state) {
            is StreamConnectionState.Streaming -> {
                if (state.firstFrameRendered) {
                    _isWebRtcStreaming.value = true
                    _isFallbackActive.value = false
                    _resolution.value = IntSize(state.videoWidth, state.videoHeight)
                }
            }

            StreamConnectionState.Stopped,
            is StreamConnectionState.Failed -> {
                _isWebRtcStreaming.value = false
            }

            else -> Unit
        }
    }

    fun release() {
        collectorJob?.cancel()
        collectorJob = null
        _fallbackBitmap.value = null
        _isFallbackActive.value = false
        _isWebRtcStreaming.value = false
    }
}

/**
 * Composable component presenting hardware WebRTC video stream with transparent fallback
 * to JPEG frames if WebRTC takes longer than 3 seconds to negotiate.
 */
@Composable
fun RemoteScreenViewer(
    webRtcManager: WebRtcClientManager,
    controller: RemoteScreenViewerController,
    modifier: Modifier = Modifier,
    onSurfaceRendererAttached: (SurfaceViewRenderer) -> Unit = {},
    onSurfaceRendererDetached: (SurfaceViewRenderer?) -> Unit = {},
) {
    val streamState by webRtcManager.streamState.collectAsState()
    val fallbackBitmap by controller.fallbackBitmap.collectAsState()
    val isFallbackActive by controller.isFallbackActive.collectAsState()
    val isWebRtcStreaming by controller.isWebRtcStreaming.collectAsState()

    LaunchedEffect(streamState) {
        controller.onWebRtcStateChanged(streamState)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Native WebRTC hardware SurfaceViewRenderer
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val renderer = SurfaceViewRenderer(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                    addView(renderer)
                    webRtcManager.attachRenderer(renderer)
                    onSurfaceRendererAttached(renderer)
                }
            },
            update = { container ->
                val renderer = container.getChildAt(0) as? SurfaceViewRenderer
                if (renderer != null) {
                    webRtcManager.attachRenderer(renderer)
                    onSurfaceRendererAttached(renderer)
                }
            },
            onRelease = { container ->
                val renderer = container.getChildAt(0) as? SurfaceViewRenderer
                webRtcManager.detachAndReleaseRenderer(renderer)
                onSurfaceRendererDetached(renderer)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Fallback JPEG Frame Viewer overlay when WebRTC is not yet rendered or in fallback mode
        val activeBitmap = fallbackBitmap
        if ((!isWebRtcStreaming || isFallbackActive) && activeBitmap != null) {
            Image(
                bitmap = activeBitmap.asImageBitmap(),
                contentDescription = "Desktop Screen Fallback",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            // Non-intrusive status pill indicating fallback streaming is active
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.85f),
            ) {
                Text(
                    text = "Modo Fallback JPEG Ativo",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
