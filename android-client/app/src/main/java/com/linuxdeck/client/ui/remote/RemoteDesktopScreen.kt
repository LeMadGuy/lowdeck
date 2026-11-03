package com.linuxdeck.client.ui.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.UdpFastControlClient
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.MouseButton
import com.linuxdeck.client.protocol.v1.MouseButtonEvent
import com.linuxdeck.client.protocol.v1.MouseMoveEvent
import com.linuxdeck.client.ui.trackpad.GestureDetectorSurface
import com.linuxdeck.client.ui.trackpad.ModifierKeysBar
import com.linuxdeck.client.ui.trackpad.StickyModifier
import com.linuxdeck.client.ui.trackpad.StickyModifierHelper
import com.linuxdeck.client.ui.trackpad.TrackpadAccelerationConfig
import com.linuxdeck.client.ui.trackpad.TrackpadAction
import com.linuxdeck.client.webrtc.RemoteStreamConfig
import com.linuxdeck.client.webrtc.VideoResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.hypot

/**
 * Selectable touch interaction modes for Remote Desktop (`TASK-12`).
 */
enum class RemoteInteractionMode {
    DIRECT_TOUCH,
    RELATIVE_CURSOR,
}

@Immutable
private data class ViewportTransform(
    val zoom: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

private const val REMOTE_IME_SENTINEL = " "

/**
 * Continuous Persistent Remote Desktop Viewer (`FIX-03` / `docs/fixes/fix-08.md`).
 *
 * Replaces the fragile WebRTC negotiation pipeline with a resilient, continuous HTTP multipart
 * stream (`http://<host>:8444/screen.mjpg`) decoded at low RAM footprint via `RGB_565`.
 * Controls input via the high-speed `UdpFastControlClient` on port `:8443/udp`.
 */
@Composable
fun RemoteDesktopScreen(
    modifier: Modifier = Modifier,
    streamConfig: RemoteStreamConfig = RemoteStreamConfig(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val ioScope = rememberCoroutineScope()

    val sessionClient = remember(context) {
        DeckConnectionService.obtainClient(context.applicationContext)
    }

    val connectionState by sessionClient.connectionState.collectAsStateWithLifecycle()
    val host = remember(connectionState) {
        (connectionState as? ConnectionState.Connected)?.host ?: "127.0.0.1"
    }
    val streamUrl = remember(host) { "http://$host:8444/screen.mjpg" }

    val udpClient = remember(host) {
        UdpFastControlClient(
            host = host,
            port = 8443,
            sessionClient = sessionClient,
            scope = ioScope,
        )
    }

    var desktopBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var reconnectTrigger by remember { mutableIntStateOf(0) }
    var videoResolution by remember { mutableStateOf(VideoResolution(1280, 720)) }
    var isAudioMuted by remember { mutableStateOf(false) }

    var interactionMode by remember { mutableStateOf(RemoteInteractionMode.DIRECT_TOUCH) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf(ViewportTransform()) }
    var isFloatingMenuExpanded by remember { mutableStateOf(false) }
    var isModifierBarExpanded by remember { mutableStateOf(false) }
    var isSoftKeyboardVisible by remember { mutableStateOf(false) }
    var lockedModifiers by remember { mutableStateOf<Set<StickyModifier>>(emptySet()) }
    val trackpadConfig = remember { TrackpadAccelerationConfig() }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeBuffer by remember {
        mutableStateOf(
            TextFieldValue(
                text = REMOTE_IME_SENTINEL,
                selection = TextRange(REMOTE_IME_SENTINEL.length),
            ),
        )
    }

    DisposableEffect(Unit) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true

        onDispose {
            view.keepScreenOn = prevKeepScreenOn
            udpClient.close()

            // Release any sticky modifiers held on Linux /dev/uinput
            val releaseTransition = StickyModifierHelper.releaseAll(lockedModifiers)
            releaseTransition.envelopes.forEach { sessionClient.sendAsync(it) }
        }
    }

    // Continuous MJPEG stream reader running in Dispatchers.IO
    LaunchedEffect(streamUrl, reconnectTrigger) {
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // Cuts RAM consumption by half
                inMutable = true
            }

            while (isActive) {
                var conn: HttpURLConnection? = null
                var stream: BufferedInputStream? = null
                try {
                    streamError = null
                    val url = URL(streamUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 5000
                        requestMethod = "GET"
                        useCaches = false
                        setRequestProperty("Connection", "close")
                    }
                    conn.connect()
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP ${conn.responseCode}: ${conn.responseMessage}")
                    }

                    stream = BufferedInputStream(conn.inputStream, 64 * 1024)
                    val buffer = ByteArrayOutputStream(128 * 1024)
                    var prevByte = -1

                    while (isActive) {
                        val b = stream.read()
                        if (b == -1) break

                        // Detect JPEG SOI (0xFF, 0xD8)
                        if (prevByte == 0xFF && b == 0xD8) {
                            buffer.reset()
                            buffer.write(0xFF)
                            buffer.write(0xD8)
                            prevByte = -1

                            // Read until JPEG EOI (0xFF, 0xD9)
                            var innerPrev = -1
                            while (isActive) {
                                val ib = stream.read()
                                if (ib == -1) break
                                buffer.write(ib)
                                if (innerPrev == 0xFF && ib == 0xD9) {
                                    val jpegBytes = buffer.toByteArray()
                                    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                                    if (bmp != null) {
                                        desktopBitmap = bmp.asImageBitmap()
                                        if (bmp.width != videoResolution.width || bmp.height != videoResolution.height) {
                                            videoResolution = VideoResolution(bmp.width, bmp.height)
                                        }
                                    }
                                    break
                                }
                                innerPrev = ib
                            }
                        } else {
                            prevByte = b
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    streamError = "Falha ao conectar no stream MJPEG: ${e.message ?: "Conexão encerrada"}"
                    delay(1000) // Auto-reconnect after 1000ms
                } finally {
                    try { stream?.close() } catch (_: Exception) {}
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(isSoftKeyboardVisible) {
        if (isSoftKeyboardVisible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    fun sendEnvelopeOnIo(builder: () -> Envelope) {
        ioScope.launch(Dispatchers.IO) {
            sessionClient.sendAsync(builder())
        }
    }

    fun sendAbsoluteMoveAndButton(
        normX: Float,
        normY: Float,
        button: MouseButton?,
        action: ButtonAction?,
    ) {
        val clampedX = normX.coerceIn(0f, 1f)
        val clampedY = normY.coerceIn(0f, 1f)
        udpClient.sendMouseAbsolute(clampedX, clampedY)

        if (button != null && action != null) {
            udpClient.sendMouseButton(button, action)
        }
    }

    fun handleImeInput(text: String) {
        for (ch in text) {
            sendEnvelopeOnIo {
                StickyModifierHelper.buildKeyActionEnvelope(ch.toString(), ButtonAction.CLICK)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .onSizeChanged { newSize ->
                viewportSize = newSize
                if (newSize.width > 0 && newSize.height > 0) {
                    val clamped = TouchCoordinateMapper.clampPan(
                        viewWidth = newSize.width.toFloat(),
                        viewHeight = newSize.height.toFloat(),
                        streamWidth = videoResolution.width,
                        streamHeight = videoResolution.height,
                        zoom = transform.zoom,
                        panX = transform.panX,
                        panY = transform.panY,
                    )
                    transform = transform.copy(panX = clamped.panX, panY = clamped.panY)
                }
            },
    ) {
        // Hidden BasicTextField capturing Android Soft Keyboard (IME) input over the desktop
        BasicTextField(
            value = imeBuffer,
            onValueChange = { next ->
                val raw = next.text
                when {
                    raw.isEmpty() -> {
                        sendEnvelopeOnIo {
                            StickyModifierHelper.buildKeyActionEnvelope("BACKSPACE", ButtonAction.CLICK)
                        }
                    }
                    raw.startsWith(REMOTE_IME_SENTINEL) && raw.length > REMOTE_IME_SENTINEL.length -> {
                        handleImeInput(raw.substring(REMOTE_IME_SENTINEL.length))
                    }
                    raw != REMOTE_IME_SENTINEL -> {
                        handleImeInput(raw)
                    }
                }
                imeBuffer = TextFieldValue(
                    text = REMOTE_IME_SENTINEL,
                    selection = TextRange(REMOTE_IME_SENTINEL.length),
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    sendEnvelopeOnIo {
                        StickyModifierHelper.buildKeyActionEnvelope("ENTER", ButtonAction.CLICK)
                    }
                },
                onDone = {
                    sendEnvelopeOnIo {
                        StickyModifierHelper.buildKeyActionEnvelope("ENTER", ButtonAction.CLICK)
                    }
                },
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )

        // 1. Continuous Screen Display Surface (scaled & panned around viewport center)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    translationX = transform.panX
                    translationY = transform.panY
                },
            contentAlignment = Alignment.Center,
        ) {
            val currentBmp = desktopBitmap
            if (currentBmp != null) {
                Image(
                    bitmap = currentBmp,
                    contentDescription = "Desktop Remoto Contínuo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (streamError != null) {
                            Text(
                                text = streamError ?: "",
                                color = Color(0xFFF87171),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { reconnectTrigger++ },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0284C7),
                            ) {
                                Text(
                                    text = "Reconectar Agora",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(0xFF38BDF8),
                            )
                            Text(
                                text = "Conectando ao Stream Contínuo (:8444/screen.mjpg)...",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        // 2. Stationary Touch Interaction Overlay (Direct Absolute Touch OR Relative Trackpad)
        when (interactionMode) {
            RemoteInteractionMode.DIRECT_TOUCH -> {
                DirectAbsoluteTouchOverlay(
                    viewportSize = viewportSize,
                    streamWidth = videoResolution.width,
                    streamHeight = videoResolution.height,
                    transform = transform,
                    onTransformChange = { updated -> transform = updated },
                    onAbsoluteTap = { normX, normY, button ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        sendAbsoluteMoveAndButton(normX, normY, button, ButtonAction.CLICK)
                    },
                    onAbsoluteDragStart = { normX, normY ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        sendAbsoluteMoveAndButton(normX, normY, MouseButton.LEFT, ButtonAction.PRESS)
                    },
                    onAbsoluteDragMove = { normX, normY ->
                        sendAbsoluteMoveAndButton(normX, normY, null, null)
                    },
                    onAbsoluteDragEnd = { normX, normY ->
                        sendAbsoluteMoveAndButton(normX, normY, MouseButton.LEFT, ButtonAction.RELEASE)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            RemoteInteractionMode.RELATIVE_CURSOR -> {
                GestureDetectorSurface(
                    config = trackpadConfig,
                    showVisualChrome = false,
                    onAction = { action: TrackpadAction ->
                        sendEnvelopeOnIo { action.toEnvelope() }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 3. Floating HUD Controls (Top-Right) & Retractable ModifierKeysBar (Bottom)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            FloatingRemoteHudMenu(
                interactionMode = interactionMode,
                zoom = transform.zoom,
                isAudioMuted = isAudioMuted,
                isMenuExpanded = isFloatingMenuExpanded,
                isModifierBarExpanded = isModifierBarExpanded,
                isSoftKeyboardVisible = isSoftKeyboardVisible,
                onToggleMenu = { isFloatingMenuExpanded = !isFloatingMenuExpanded },
                onSelectMode = { mode ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    interactionMode = mode
                },
                onResetZoom = {
                    transform = ViewportTransform(zoom = 1.0f, panX = 0f, panY = 0f)
                },
                onToggleMute = {
                    isAudioMuted = !isAudioMuted
                },
                onToggleModifierBar = {
                    isModifierBarExpanded = !isModifierBarExpanded
                },
                onToggleSoftKeyboard = {
                    val target = !isSoftKeyboardVisible
                    isSoftKeyboardVisible = target
                    if (target) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
                    }
                },
                onReconnectStream = {
                    reconnectTrigger++
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )

            // Retractable Modifier Keys Bar anchored at the bottom
            AnimatedVisibility(
                visible = isModifierBarExpanded,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ModifierKeysBar(
                    lockedModifiers = lockedModifiers,
                    onToggleModifier = { mod ->
                        val transition = StickyModifierHelper.toggleModifier(lockedModifiers, mod)
                        lockedModifiers = transition.activeModifiers
                        ioScope.launch(Dispatchers.IO) {
                            transition.envelopes.forEach { sessionClient.sendAsync(it) }
                        }
                    },
                    onClearModifiers = {
                        val transition = StickyModifierHelper.releaseAll(lockedModifiers)
                        lockedModifiers = transition.activeModifiers
                        ioScope.launch(Dispatchers.IO) {
                            transition.envelopes.forEach { sessionClient.sendAsync(it) }
                        }
                    },
                    onSpecialKeyClick = { keyName ->
                        sendEnvelopeOnIo {
                            StickyModifierHelper.buildKeyActionEnvelope(keyName, ButtonAction.CLICK)
                        }
                    },
                    onComboClick = { combo ->
                        val activeSnapshot = lockedModifiers
                        ioScope.launch(Dispatchers.IO) {
                            StickyModifierHelper.buildComboEnvelopes(combo, activeSnapshot)
                                .forEach { sessionClient.sendAsync(it) }
                        }
                    },
                    isSoftKeyboardVisible = isSoftKeyboardVisible,
                    onToggleSoftKeyboard = {
                        val target = !isSoftKeyboardVisible
                        isSoftKeyboardVisible = target
                        if (target) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    },
                )
            }
        }
    }
}

/**
 * Direct Touch (`is_absolute = true`) & 2-finger Pinch-Zoom/Pan gesture layer operating in
 * stationary viewport coordinates and mapping via [TouchCoordinateMapper].
 */
@Composable
private fun DirectAbsoluteTouchOverlay(
    viewportSize: IntSize,
    streamWidth: Int,
    streamHeight: Int,
    transform: ViewportTransform,
    onTransformChange: (ViewportTransform) -> Unit,
    onAbsoluteTap: (Float, Float, MouseButton) -> Unit,
    onAbsoluteDragStart: (Float, Float) -> Unit,
    onAbsoluteDragMove: (Float, Float) -> Unit,
    onAbsoluteDragEnd: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentViewport by rememberUpdatedState(viewportSize)
    val currentStreamW by rememberUpdatedState(streamWidth)
    val currentStreamH by rememberUpdatedState(streamHeight)
    val currentTransform by rememberUpdatedState(transform)
    val currentOnTransformChange by rememberUpdatedState(onTransformChange)
    val currentOnTap by rememberUpdatedState(onAbsoluteTap)
    val currentOnDragStart by rememberUpdatedState(onAbsoluteDragStart)
    val currentOnDragMove by rememberUpdatedState(onAbsoluteDragMove)
    val currentOnDragEnd by rememberUpdatedState(onAbsoluteDragEnd)

    fun mapPoint(pos: Offset): MappedTouchCoordinate {
        return TouchCoordinateMapper.mapTouchToNormalized(
            touchX = pos.x,
            touchY = pos.y,
            viewWidth = currentViewport.width.toFloat(),
            viewHeight = currentViewport.height.toFloat(),
            streamWidth = currentStreamW,
            streamHeight = currentStreamH,
            zoom = currentTransform.zoom,
            panX = currentTransform.panX,
            panY = currentTransform.panY,
        )
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            val tapSlopPx = viewConfiguration.touchSlop
            val longPressMs = viewConfiguration.longPressTimeoutMillis
            val minMoveIntervalMs = 12L

            var isPinching = false
            var downUptimeMs = 0L
            var downPos = Offset.Zero
            var lastSinglePos = Offset.Zero
            var isSingleDragging = false
            var rightClickTriggered = false
            var lastMoveEmitMs = 0L

            while (true) {
                val event = awaitPointerEventScope {
                    awaitPointerEvent(PointerEventPass.Main)
                }

                val nowMs = SystemClock.uptimeMillis()
                val pressed = event.changes.filter { it.pressed }

                if (pressed.isEmpty()) {
                    if (!isPinching && downUptimeMs > 0L) {
                        if (isSingleDragging) {
                            val mapped = mapPoint(lastSinglePos)
                            currentOnDragEnd(mapped.normX, mapped.normY)
                        } else if (!rightClickTriggered) {
                            val elapsed = nowMs - downUptimeMs
                            val dist = hypot(lastSinglePos.x - downPos.x, lastSinglePos.y - downPos.y)
                            if (dist <= tapSlopPx && elapsed < longPressMs) {
                                val mapped = mapPoint(downPos)
                                if (mapped.isInsideVideo) {
                                    currentOnTap(mapped.normX, mapped.normY, MouseButton.LEFT)
                                }
                            }
                        }
                    }

                    isPinching = false
                    downUptimeMs = 0L
                    downPos = Offset.Zero
                    lastSinglePos = Offset.Zero
                    isSingleDragging = false
                    rightClickTriggered = false
                    continue
                }

                if (pressed.size >= 2) {
                    if (isSingleDragging) {
                        val mapped = mapPoint(lastSinglePos)
                        currentOnDragEnd(mapped.normX, mapped.normY)
                        isSingleDragging = false
                    }
                    isPinching = true
                    downUptimeMs = 0L
                    rightClickTriggered = false

                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val centroid = event.calculateCentroid()

                    if (zoomChange != 1.0f || panChange != Offset.Zero) {
                        val prev = currentTransform
                        val targetZoom = (prev.zoom * zoomChange).coerceIn(1.0f, 4.0f)
                        val viewW = currentViewport.width.toFloat()
                        val viewH = currentViewport.height.toFloat()

                        val cx = centroid.x - viewW / 2f
                        val cy = centroid.y - viewH / 2f
                        val rawPanX = (prev.panX - cx) * (targetZoom / prev.zoom) + cx + panChange.x
                        val rawPanY = (prev.panY - cy) * (targetZoom / prev.zoom) + cy + panChange.y

                        val clamped = TouchCoordinateMapper.clampPan(
                            viewWidth = viewW,
                            viewHeight = viewH,
                            streamWidth = currentStreamW,
                            streamHeight = currentStreamH,
                            zoom = targetZoom,
                            panX = rawPanX,
                            panY = rawPanY,
                        )

                        currentOnTransformChange(
                            ViewportTransform(
                                zoom = targetZoom,
                                panX = clamped.panX,
                                panY = clamped.panY,
                            ),
                        )
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }

                // Exactly 1 finger pressed
                if (!isPinching) {
                    val pos = pressed[0].position
                    if (downUptimeMs == 0L) {
                        downUptimeMs = nowMs
                        downPos = pos
                        lastSinglePos = pos
                    } else {
                        lastSinglePos = pos
                        val dist = hypot(pos.x - downPos.x, pos.y - downPos.y)
                        if (!isSingleDragging && !rightClickTriggered) {
                            if (dist > tapSlopPx) {
                                val startMapped = mapPoint(downPos)
                                if (startMapped.isInsideVideo) {
                                    isSingleDragging = true
                                    currentOnDragStart(startMapped.normX, startMapped.normY)
                                    val currMapped = mapPoint(pos)
                                    currentOnDragMove(currMapped.normX, currMapped.normY)
                                    lastMoveEmitMs = nowMs
                                }
                            } else if (nowMs - downUptimeMs >= longPressMs) {
                                val mapped = mapPoint(pos)
                                if (mapped.isInsideVideo) {
                                    rightClickTriggered = true
                                    currentOnTap(mapped.normX, mapped.normY, MouseButton.RIGHT)
                                }
                            }
                        } else if (isSingleDragging && nowMs - lastMoveEmitMs >= minMoveIntervalMs) {
                            val mapped = mapPoint(pos)
                            currentOnDragMove(mapped.normX, mapped.normY)
                            lastMoveEmitMs = nowMs
                        }
                    }
                }
                event.changes.forEach { it.consume() }
            }
        },
    )
}

@Composable
private fun FloatingRemoteHudMenu(
    interactionMode: RemoteInteractionMode,
    zoom: Float,
    isAudioMuted: Boolean,
    isMenuExpanded: Boolean,
    isModifierBarExpanded: Boolean,
    isSoftKeyboardVisible: Boolean,
    onToggleMenu: () -> Unit,
    onSelectMode: (RemoteInteractionMode) -> Unit,
    onResetZoom: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleModifierBar: () -> Unit,
    onToggleSoftKeyboard: () -> Unit,
    onReconnectStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Compact quick-action pill always reachable in one tap
        Surface(
            shape = CircleShape,
            color = Color(0xFF0F172A).copy(alpha = 0.86f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        val next = if (interactionMode == RemoteInteractionMode.DIRECT_TOUCH) {
                            RemoteInteractionMode.RELATIVE_CURSOR
                        } else {
                            RemoteInteractionMode.DIRECT_TOUCH
                        }
                        onSelectMode(next)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (interactionMode == RemoteInteractionMode.DIRECT_TOUCH) {
                            Icons.Rounded.TouchApp
                        } else {
                            Icons.Rounded.Mouse
                        },
                        contentDescription = "Alternar Modo de Toque",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = onToggleSoftKeyboard,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "Teclado Virtual",
                        tint = if (isSoftKeyboardVisible) Color(0xFFFACC15) else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (zoom > 1.05f) {
                    IconButton(
                        onClick = onResetZoom,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = "Resetar Zoom",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onToggleMenu,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Menu Desktop Remoto",
                        tint = if (isMenuExpanded) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Expanded Options Card
        AnimatedVisibility(visible = isMenuExpanded) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Modo de Interação",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = interactionMode == RemoteInteractionMode.DIRECT_TOUCH,
                            onClick = { onSelectMode(RemoteInteractionMode.DIRECT_TOUCH) },
                            label = { Text("Toque Direto (Absoluto)", fontSize = 11.sp) },
                        )
                        FilterChip(
                            selected = interactionMode == RemoteInteractionMode.RELATIVE_CURSOR,
                            onClick = { onSelectMode(RemoteInteractionMode.RELATIVE_CURSOR) },
                            label = { Text("Cursor Relativo", fontSize = 11.sp) },
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onToggleMute),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (isAudioMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = null,
                                    tint = if (isAudioMuted) Color(0xFFF87171) else Color(0xFF34D399),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAudioMuted) "Áudio Mudo" else "Áudio Ativo",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onReconnectStream),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Reconectar",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
