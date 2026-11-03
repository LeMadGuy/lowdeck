package com.linuxdeck.client.ui.remote

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxdeck.client.fixes.RemoteScreenViewer
import com.linuxdeck.client.fixes.RemoteScreenViewerController
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.KeyComboEvent
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
import com.linuxdeck.client.webrtc.StreamConnectionState
import com.linuxdeck.client.webrtc.WebRtcClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot

/**
 * Selectable touch interaction modes for Remote Desktop (`TASK-12`).
 */
enum class RemoteInteractionMode {
    /**
     * Direct Touch (Absolute): tapping/dragging a point on the monitor image maps `(norm_x, norm_y)`
     * in `[0.0, 1.0]` discounting letterbox bars and zoom/pan, dispatching `MouseMoveEvent(is_absolute=true)`
     * followed by mouse button actions.
     */
    DIRECT_TOUCH,

    /**
     * Relative Cursor (Trackpad over Video): dragging anywhere moves the remote cursor relatively
     * with velocity acceleration without needing to reach corners with the thumb.
     */
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
 * Real-time Hardware-Accelerated WebRTC Remote Desktop screen with synchronized Opus audio,
 * pinch-to-zoom/pan, Absolute Direct Touch & Relative Trackpad modes, retractable floating
 * `ModifierKeysBar` (`TASK-11`), and Android Virtual Keyboard overlay (`TASK-12`).
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
    val webRtcManager = remember(context) {
        WebRtcClientManager(context.applicationContext)
    }
    val viewerController = remember(sessionClient) {
        RemoteScreenViewerController(
            incomingEnvelopes = sessionClient.incomingEnvelopes,
            scope = ioScope,
        )
    }

    val streamState by webRtcManager.streamState.collectAsStateWithLifecycle()
    val videoResolution by webRtcManager.videoResolution.collectAsStateWithLifecycle()
    val isAudioMuted by webRtcManager.isAudioMuted.collectAsStateWithLifecycle()
    val fallbackBitmap by viewerController.fallbackBitmap.collectAsStateWithLifecycle()

    var interactionMode by remember { mutableStateOf(RemoteInteractionMode.DIRECT_TOUCH) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf(ViewportTransform()) }
    var isFloatingMenuExpanded by remember { mutableStateOf(false) }
    var isModifierBarExpanded by remember { mutableStateOf(false) }
    var isSoftKeyboardVisible by remember { mutableStateOf(false) }
    var lockedModifiers by remember { mutableStateOf<Set<StickyModifier>>(emptySet()) }
    val trackpadConfig = remember { TrackpadAccelerationConfig() }

    var surfaceRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

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

    // Start WebRTC stream on entry and strictly release SurfaceViewRenderer, PeerConnection,
    // STOP_STREAM signal, and any held modifiers on exit.
    DisposableEffect(Unit) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        webRtcManager.startStream(streamConfig)

        onDispose {
            view.keepScreenOn = prevKeepScreenOn

            // Release any sticky modifiers held on Linux /dev/uinput
            val releaseTransition = StickyModifierHelper.releaseAll(lockedModifiers)
            releaseTransition.envelopes.forEach { sessionClient.sendAsync(it) }

            // Explicitly release SurfaceViewRenderer, close PeerConnection, and send WebRtcSignal{STOP_STREAM}
            viewerController.release()
            webRtcManager.detachAndReleaseRenderer(surfaceRendererRef)
            surfaceRendererRef = null
            webRtcManager.stopStream(sendStopSignal = true)
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
        ioScope.launch(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val moveEnv = Envelope.newBuilder()
                .setRequestId("abs-move-${UUID.randomUUID()}")
                .setTimestampMs(nowMs)
                .setMouseMoveEvent(
                    MouseMoveEvent.newBuilder()
                        .setNormX(normX.coerceIn(0f, 1f))
                        .setNormY(normY.coerceIn(0f, 1f))
                        .setIsAbsolute(true)
                        .build(),
                )
                .build()
            sessionClient.sendAsync(moveEnv)

            if (button != null && action != null) {
                val btnEnv = Envelope.newBuilder()
                    .setRequestId("abs-btn-${UUID.randomUUID()}")
                    .setTimestampMs(nowMs)
                    .setMouseButtonEvent(
                        MouseButtonEvent.newBuilder()
                            .setButton(button)
                            .setAction(action)
                            .build(),
                    )
                    .build()
                sessionClient.sendAsync(btnEnv)
            }
        }
    }

    fun handleImeInput(insertedText: String) {
        if (insertedText.isEmpty()) return
        val activeMods = lockedModifiers
        val hasChord = StickyModifier.CTRL in activeMods ||
            StickyModifier.ALT in activeMods ||
            StickyModifier.SUPER in activeMods

        ioScope.launch(Dispatchers.IO) {
            if (insertedText == "\n" || insertedText == "\r") {
                sessionClient.sendAsync(StickyModifierHelper.buildKeyActionEnvelope("ENTER", ButtonAction.CLICK))
                return@launch
            }
            if (insertedText == "\t") {
                sessionClient.sendAsync(StickyModifierHelper.buildKeyActionEnvelope("TAB", ButtonAction.CLICK))
                return@launch
            }
            if (hasChord && insertedText.length == 1 && insertedText[0].isLetterOrDigit()) {
                val token = insertedText.uppercase(Locale.ROOT)
                sessionClient.sendAsync(StickyModifierHelper.buildKeyActionEnvelope(token, ButtonAction.CLICK))
                return@launch
            }
            val env = Envelope.newBuilder()
                .setRequestId("remote-text-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setKeyComboEvent(KeyComboEvent.newBuilder().setText(insertedText).build())
                .build()
            sessionClient.sendAsync(env)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
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
        // Hidden BasicTextField capturing Android Soft Keyboard (IME) input over the video
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

        // 1. Hardware-Accelerated WebRTC Video Surface with transparent JPEG fallback
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
            RemoteScreenViewer(
                webRtcManager = webRtcManager,
                controller = viewerController,
                onSurfaceRendererAttached = { renderer -> surfaceRendererRef = renderer },
                onSurfaceRendererDetached = { renderer ->
                    if (surfaceRendererRef === renderer) {
                        surfaceRendererRef = null
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
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

        // 3. Stream status indicator while negotiating or on error
        StreamStatusOverlay(
            streamState = streamState,
            isFallbackShowing = (fallbackBitmap != null),
            onRetry = { webRtcManager.startStream(streamConfig) },
            modifier = Modifier.align(Alignment.Center),
        )

        // 4. Floating HUD Controls (Top-Right) & Retractable ModifierKeysBar (Bottom)
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    transform = ViewportTransform(zoom = 1.0f, panX = 0f, panY = 0f)
                },
                onToggleMute = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    webRtcManager.setAudioMuted(!isAudioMuted)
                },
                onToggleModifierBar = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isModifierBarExpanded = !isModifierBarExpanded
                },
                onToggleSoftKeyboard = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    webRtcManager.startStream(streamConfig)
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )

            AnimatedVisibility(
                visible = isModifierBarExpanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
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

    Box(
        modifier = modifier.pointerInput(Unit) {
            val tapSlopPx = 18f
            val longPressMs = 450L
            val minMoveIntervalMs = 8L

            awaitPointerEventScope {
                var isPinching = false
                var isSingleDragging = false
                var downPos = Offset.Zero
                var lastSinglePos = Offset.Zero
                var downUptimeMs = 0L
                var lastMoveEmitMs = 0L
                var rightClickTriggered = false

                fun mapPoint(pos: Offset): MappedTouchCoordinate {
                    val vw = currentViewport.width.toFloat()
                    val vh = currentViewport.height.toFloat()
                    val tf = currentTransform
                    return TouchCoordinateMapper.mapTouchToNormalized(
                        touchX = pos.x,
                        touchY = pos.y,
                        viewWidth = vw,
                        viewHeight = vh,
                        streamWidth = currentStreamW,
                        streamHeight = currentStreamH,
                        zoom = tf.zoom,
                        panX = tf.panX,
                        panY = tf.panY,
                    )
                }

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    val nowMs = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()

                    if (pressed.isEmpty()) {
                        if (isSingleDragging) {
                            val mapped = mapPoint(lastSinglePos)
                            currentOnDragEnd(mapped.normX, mapped.normY)
                        } else if (!isPinching && !rightClickTriggered && downUptimeMs > 0L) {
                            val mapped = mapPoint(lastSinglePos)
                            if (mapped.isInsideVideo) {
                                currentOnTap(mapped.normX, mapped.normY, MouseButton.LEFT)
                            }
                        }
                        isPinching = false
                        isSingleDragging = false
                        rightClickTriggered = false
                        downUptimeMs = 0L
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    if (pressed.size >= 2) {
                        if (isSingleDragging) {
                            val mapped = mapPoint(lastSinglePos)
                            currentOnDragEnd(mapped.normX, mapped.normY)
                            isSingleDragging = false
                        }
                        isPinching = true
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)

                        val vw = currentViewport.width.toFloat()
                        val vh = currentViewport.height.toFloat()
                        if (vw > 0f && vh > 0f && centroid != Offset.Unspecified) {
                            val oldTf = currentTransform
                            val newZoom = (oldTf.zoom * zoomChange).coerceIn(
                                TouchCoordinateMapper.MIN_ZOOM,
                                TouchCoordinateMapper.MAX_ZOOM,
                            )
                            val actualScaleRatio = newZoom / oldTf.zoom
                            val cx = vw * 0.5f
                            val cy = vh * 0.5f

                            // Anchor zoom around pinch centroid + apply 2-finger pan
                            val rawPanX = (centroid.x - cx) * (1f - actualScaleRatio) +
                                oldTf.panX * actualScaleRatio + panChange.x
                            val rawPanY = (centroid.y - cy) * (1f - actualScaleRatio) +
                                oldTf.panY * actualScaleRatio + panChange.y

                            val clamped = TouchCoordinateMapper.clampPan(
                                viewWidth = vw,
                                viewHeight = vh,
                                streamWidth = currentStreamW,
                                streamHeight = currentStreamH,
                                zoom = newZoom,
                                panX = rawPanX,
                                panY = rawPanY,
                            )
                            currentOnTransformChange(
                                ViewportTransform(
                                    zoom = newZoom,
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
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (interactionMode == RemoteInteractionMode.DIRECT_TOUCH) {
                            Icons.Rounded.TouchApp
                        } else {
                            Icons.Rounded.Mouse
                        },
                        contentDescription = "Alternar modo de toque",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (zoom > 1.02f) {
                    IconButton(
                        onClick = onResetZoom,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusStrong,
                            contentDescription = "Resetar zoom 1.0x",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onToggleModifierBar,
                    modifier = Modifier.size(34.dp),
                ) {
                    Text(
                        text = "Fn",
                        color = if (isModifierBarExpanded) Color(0xFF38BDF8) else Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(
                    onClick = onToggleSoftKeyboard,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "Abrir teclado virtual",
                        tint = if (isSoftKeyboardVisible) Color(0xFF38BDF8) else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = onToggleMenu,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Menu do Desktop Remoto",
                        tint = if (isMenuExpanded) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Expanded floating menu for selecting interaction mode, audio mute, and stream refresh
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
                                    imageVector = if (isAudioMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
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

@Composable
private fun StreamStatusOverlay(
    streamState: StreamConnectionState,
    isFallbackShowing: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isFallbackShowing && streamState !is StreamConnectionState.Failed) {
        return
    }
    when (streamState) {
        is StreamConnectionState.Streaming -> {
            if (streamState.firstFrameRendered) return
            StatusBadgeCard(
                message = "Recebendo quadros H.264 (${streamState.videoWidth}x${streamState.videoHeight})...",
                showSpinner = true,
                onRetry = null,
                modifier = modifier,
            )
        }

        StreamConnectionState.Idle,
        StreamConnectionState.RequestingOffer -> {
            StatusBadgeCard(
                message = "Iniciando Desktop Remoto WebRTC...",
                showSpinner = true,
                onRetry = null,
                modifier = modifier,
            )
        }

        StreamConnectionState.NegotiatingIce -> {
            StatusBadgeCard(
                message = "Negociando SDP & ICE de baixa latência...",
                showSpinner = true,
                onRetry = null,
                modifier = modifier,
            )
        }

        StreamConnectionState.Stopped -> {
            StatusBadgeCard(
                message = "Stream encerrado",
                showSpinner = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is StreamConnectionState.Failed -> {
            StatusBadgeCard(
                message = streamState.reason,
                showSpinner = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun StatusBadgeCard(
    message: String,
    showSpinner: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.90f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = Color(0xFF38BDF8),
                )
            }
            Text(
                text = message,
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (onRetry != null) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onRetry),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0284C7),
                ) {
                    Text(
                        text = "Tentar Novamente",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
