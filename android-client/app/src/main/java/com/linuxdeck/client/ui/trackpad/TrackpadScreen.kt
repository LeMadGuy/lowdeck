package com.linuxdeck.client.ui.trackpad

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.DeckSessionClient
import com.linuxdeck.client.network.UdpFastControlClient
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.KeyComboEvent
import com.linuxdeck.client.protocol.v1.MouseButton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Outbound transport abstraction for virtual trackpad and keyboard events.
 */
interface TrackpadEventSink {
    val connectionState: StateFlow<ConnectionState>
    fun sendAsync(envelope: Envelope): Boolean
}

class SessionClientTrackpadSink(
    private val sessionClient: DeckSessionClient,
) : TrackpadEventSink {
    override val connectionState: StateFlow<ConnectionState> = sessionClient.connectionState
    override fun sendAsync(envelope: Envelope): Boolean = sessionClient.sendAsync(envelope)
}

/**
 * Immutable UI state for [TrackpadScreen].
 */
@Immutable
data class TrackpadUiState(
    val accelerationConfig: TrackpadAccelerationConfig = TrackpadAccelerationConfig(),
    val lockedModifiers: Set<StickyModifier> = emptySet(),
    val isSettingsExpanded: Boolean = false,
    val isSoftKeyboardVisible: Boolean = false,
)

/**
 * Unidirectional MVVM ViewModel managing trackpad sensitivity/acceleration settings,
 * sticky modifier key locks, and non-blocking Protobuf input dispatch on [Dispatchers.IO].
 */
class TrackpadViewModel(
    private val sink: TrackpadEventSink,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val udpClient: UdpFastControlClient? = null,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = sink.connectionState
    val udpRttMs: StateFlow<Long>? = udpClient?.rttMs

    private val _uiState = MutableStateFlow(TrackpadUiState())
    val uiState: StateFlow<TrackpadUiState> = _uiState.asStateFlow()

    fun onTrackpadAction(action: TrackpadAction) {
        if (udpClient != null) {
            when (action) {
                is TrackpadAction.MoveRelative -> {
                    udpClient.sendMouseRelative(action.dx, action.dy)
                    return
                }
                is TrackpadAction.Scroll -> {
                    udpClient.sendMouseScroll(vScroll = action.dy, hScroll = action.dx)
                    return
                }
                is TrackpadAction.Button -> {
                    udpClient.sendMouseButton(action.button, action.action)
                    return
                }
            }
        }
        viewModelScope.launch(ioDispatcher) {
            sink.sendAsync(action.toEnvelope())
        }
    }

    fun onMouseButtonAction(button: MouseButton, action: ButtonAction) {
        if (udpClient != null) {
            udpClient.sendMouseButton(button, action)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            sink.sendAsync(TrackpadAction.Button(button, action).toEnvelope())
        }
    }

    fun toggleStickyModifier(modifier: StickyModifier) {
        var outgoing: List<Envelope> = emptyList()
        _uiState.update { current ->
            val transition = StickyModifierHelper.toggleModifier(current.lockedModifiers, modifier)
            outgoing = transition.envelopes
            current.copy(lockedModifiers = transition.activeModifiers)
        }
        if (outgoing.isNotEmpty()) {
            viewModelScope.launch(ioDispatcher) {
                outgoing.forEach { sink.sendAsync(it) }
            }
        }
    }

    fun clearStickyModifiers() {
        var outgoing: List<Envelope> = emptyList()
        _uiState.update { current ->
            val transition = StickyModifierHelper.releaseAll(current.lockedModifiers)
            outgoing = transition.envelopes
            current.copy(lockedModifiers = transition.activeModifiers)
        }
        if (outgoing.isNotEmpty()) {
            viewModelScope.launch(ioDispatcher) {
                outgoing.forEach { sink.sendAsync(it) }
            }
        }
    }

    fun sendSpecialKey(keyName: String) {
        viewModelScope.launch(ioDispatcher) {
            val env = StickyModifierHelper.buildKeyActionEnvelope(
                keyName = keyName,
                action = ButtonAction.CLICK,
            )
            sink.sendAsync(env)
        }
    }

    fun sendKeyCombo(combo: String) {
        val lockedSnapshot = _uiState.value.lockedModifiers
        viewModelScope.launch(ioDispatcher) {
            val envelopes = StickyModifierHelper.buildComboEnvelopes(
                combo = combo,
                lockedModifiers = lockedSnapshot,
            )
            envelopes.forEach { sink.sendAsync(it) }
        }
    }

    /**
     * Translates text committed by the Android Soft Keyboard (IME) into `/dev/uinput` events.
     *
     * When chord modifiers (`Ctrl`, `Alt`, `Super`) are sticky-locked and a single character is typed
     * (e.g. `Ctrl` + `c`), sends `KeyEvent(key = "C", CLICK)` so Linux shortcut chords work naturally.
     * Otherwise sends `KeyComboEvent(text = text)` for full ASCII typing.
     */
    fun onSoftKeyboardInput(insertedText: String) {
        if (insertedText.isEmpty()) return
        val locked = _uiState.value.lockedModifiers
        val hasChordModifier = StickyModifier.CTRL in locked ||
            StickyModifier.ALT in locked ||
            StickyModifier.SUPER in locked

        viewModelScope.launch(ioDispatcher) {
            if (insertedText == "\n" || insertedText == "\r") {
                sink.sendAsync(StickyModifierHelper.buildKeyActionEnvelope("ENTER", ButtonAction.CLICK))
                return@launch
            }
            if (insertedText == "\t") {
                sink.sendAsync(StickyModifierHelper.buildKeyActionEnvelope("TAB", ButtonAction.CLICK))
                return@launch
            }

            if (hasChordModifier && insertedText.length == 1) {
                val ch = insertedText[0]
                if (ch.isLetterOrDigit()) {
                    val keyToken = ch.toString().uppercase(Locale.ROOT)
                    sink.sendAsync(StickyModifierHelper.buildKeyActionEnvelope(keyToken, ButtonAction.CLICK))
                    return@launch
                }
            }

            val env = Envelope.newBuilder()
                .setRequestId("text-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setKeyComboEvent(
                    KeyComboEvent.newBuilder()
                        .setText(insertedText)
                        .build(),
                )
                .build()
            sink.sendAsync(env)
        }
    }

    fun updateSensitivity(baseSensitivity: Float) {
        _uiState.update { current ->
            current.copy(
                accelerationConfig = current.accelerationConfig.copy(
                    baseSensitivity = baseSensitivity.coerceIn(0.4f, 3.5f),
                ),
            )
        }
    }

    fun updateAccelerationFactor(accelerationFactor: Float) {
        _uiState.update { current ->
            current.copy(
                accelerationConfig = current.accelerationConfig.copy(
                    accelerationFactor = accelerationFactor.coerceIn(0f, 1.0f),
                ),
            )
        }
    }

    fun updateNaturalScrolling(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(
                accelerationConfig = current.accelerationConfig.copy(
                    naturalScrolling = enabled,
                ),
            )
        }
    }

    fun toggleSettingsPanel() {
        _uiState.update { it.copy(isSettingsExpanded = !it.isSettingsExpanded) }
    }

    fun setSoftKeyboardVisible(visible: Boolean) {
        _uiState.update { it.copy(isSoftKeyboardVisible = visible) }
    }

    override fun onCleared() {
        clearStickyModifiers()
        udpClient?.close()
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val client = DeckConnectionService.obtainClient(appContext)
                    val connState = client.connectionState.value
                    val host = (connState as? ConnectionState.Connected)?.host ?: "127.0.0.1"
                    val udp = UdpFastControlClient(
                        host = host,
                        port = 8443,
                        sessionClient = client,
                        scope = CoroutineScope(Dispatchers.IO),
                    )
                    return TrackpadViewModel(
                        sink = SessionClientTrackpadSink(client),
                        ioDispatcher = Dispatchers.IO,
                        udpClient = udp,
                    ) as T
                }
            }
        }
    }
}

private const val IME_SENTINEL = " "

/**
 * Full precision Virtual Trackpad & Linux Modifier Keyboard screen (`TASK-11`).
 */
@Composable
fun TrackpadScreen(
    modifier: Modifier = Modifier,
    trackpadViewModel: TrackpadViewModel = viewModel(factory = TrackpadViewModel.factory(LocalContext.current)),
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prevKeepScreenOn
            trackpadViewModel.clearStickyModifiers()
        }
    }

    val uiState by trackpadViewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by trackpadViewModel.connectionState.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeBuffer by remember {
        mutableStateOf(TextFieldValue(text = IME_SENTINEL, selection = TextRange(IME_SENTINEL.length)))
    }

    LaunchedEffect(uiState.isSoftKeyboardVisible) {
        if (uiState.isSoftKeyboardVisible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .safeDrawingPadding(),
    ) {
        // Zero-size proxy BasicTextField capturing Android IME characters, Backspace, and Enter
        BasicTextField(
            value = imeBuffer,
            onValueChange = { nextValue ->
                val raw = nextValue.text
                when {
                    raw.isEmpty() -> {
                        // Sentinel character was deleted -> Backspace key!
                        trackpadViewModel.sendSpecialKey("BACKSPACE")
                    }
                    raw.startsWith(IME_SENTINEL) && raw.length > IME_SENTINEL.length -> {
                        val inserted = raw.substring(IME_SENTINEL.length)
                        trackpadViewModel.onSoftKeyboardInput(inserted)
                    }
                    raw != IME_SENTINEL -> {
                        trackpadViewModel.onSoftKeyboardInput(raw)
                    }
                }
                imeBuffer = TextFieldValue(
                    text = IME_SENTINEL,
                    selection = TextRange(IME_SENTINEL.length),
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = { trackpadViewModel.sendSpecialKey("ENTER") },
                onDone = { trackpadViewModel.sendSpecialKey("ENTER") },
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header with connection status & sensitivity tuner toggle
            TrackpadHeaderBar(
                connectionState = connectionState,
                isSettingsExpanded = uiState.isSettingsExpanded,
                onToggleSettings = trackpadViewModel::toggleSettingsPanel,
            )

            // Expandable sensitivity, acceleration & natural-scroll settings panel
            AnimatedVisibility(visible = uiState.isSettingsExpanded) {
                TrackpadSettingsCard(
                    config = uiState.accelerationConfig,
                    onSensitivityChange = trackpadViewModel::updateSensitivity,
                    onAccelerationChange = trackpadViewModel::updateAccelerationFactor,
                    onNaturalScrollChange = trackpadViewModel::updateNaturalScrolling,
                )
            }

            // Main multi-gesture touchpad surface
            GestureDetectorSurface(
                config = uiState.accelerationConfig,
                onAction = trackpadViewModel::onTrackpadAction,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // Dedicated physical-style Left / Middle / Right mouse button strip
            MouseHardwareButtonsRow(
                onMouseButtonAction = trackpadViewModel::onMouseButtonAction,
            )

            // Sticky Linux Modifier & Special Keys Bar
            ModifierKeysBar(
                lockedModifiers = uiState.lockedModifiers,
                onToggleModifier = trackpadViewModel::toggleStickyModifier,
                onClearModifiers = trackpadViewModel::clearStickyModifiers,
                onSpecialKeyClick = trackpadViewModel::sendSpecialKey,
                onComboClick = trackpadViewModel::sendKeyCombo,
                isSoftKeyboardVisible = uiState.isSoftKeyboardVisible,
                onToggleSoftKeyboard = {
                    val target = !uiState.isSoftKeyboardVisible
                    trackpadViewModel.setSoftKeyboardVisible(target)
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

@Composable
private fun TrackpadHeaderBar(
    connectionState: ConnectionState,
    isSettingsExpanded: Boolean,
    onToggleSettings: () -> Unit,
) {
    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF22C55E)
        is ConnectionState.Connecting,
        is ConnectionState.Reconnecting -> Color(0xFFF59E0B)
        is ConnectionState.AuthFailed,
        is ConnectionState.Disconnected -> Color(0xFFEF4444)
    }

    val statusText = when (connectionState) {
        is ConnectionState.Connected -> {
            val rtt = connectionState.latencyMs?.let { " • ${it}ms" }.orEmpty()
            "${connectionState.hostDeviceName} (/dev/uinput)$rtt"
        }
        is ConnectionState.Connecting -> "Conectando ${connectionState.host}:${connectionState.port}..."
        is ConnectionState.Reconnecting -> "Reconectando (#${connectionState.attempt})..."
        is ConnectionState.AuthFailed -> "Falha mTLS: ${connectionState.message}"
        is ConnectionState.Disconnected -> "Desconectado"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F172A),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trackpad & Teclado Linux",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onToggleSettings,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = "Ajustes de sensibilidade e aceleração",
                    tint = if (isSettingsExpanded) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackpadSettingsCard(
    config: TrackpadAccelerationConfig,
    onSensitivityChange: (Float) -> Unit,
    onAccelerationChange: (Float) -> Unit,
    onNaturalScrollChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Sensibilidade Base",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
                Text(
                    text = String.format(Locale.US, "%.2fx", config.baseSensitivity),
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = config.baseSensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 0.5f..3.0f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Curva de Aceleração",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
                Text(
                    text = String.format(Locale.US, "%.2f", config.accelerationFactor),
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = config.accelerationFactor,
                onValueChange = onAccelerationChange,
                valueRange = 0.0f..0.8f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Rolagem Natural (2 dedos)",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
                Switch(
                    checked = config.naturalScrolling,
                    onCheckedChange = onNaturalScrollChange,
                )
            }
        }
    }
}

@Composable
private fun MouseHardwareButtonsRow(
    onMouseButtonAction: (MouseButton, ButtonAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TactileMouseButton(
            label = "ESQ",
            button = MouseButton.LEFT,
            onMouseButtonAction = onMouseButtonAction,
            modifier = Modifier
                .weight(1.35f)
                .fillMaxHeight(),
        )
        TactileMouseButton(
            label = "MEIO",
            button = MouseButton.MIDDLE,
            onMouseButtonAction = onMouseButtonAction,
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight(),
        )
        TactileMouseButton(
            label = "DIR",
            button = MouseButton.RIGHT,
            onMouseButtonAction = onMouseButtonAction,
            modifier = Modifier
                .weight(1.35f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun TactileMouseButton(
    label: String,
    button: MouseButton,
    onMouseButtonAction: (MouseButton, ButtonAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = modifier
            .clip(shape)
            .pointerInput(button) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMouseButtonAction(button, ButtonAction.PRESS)
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                            onMouseButtonAction(button, ButtonAction.RELEASE)
                        }
                    },
                )
            },
        shape = shape,
        color = if (isPressed) Color(0xFF1E3A8A) else Color(0xFF0F172A),
        border = BorderStroke(
            width = 1.dp,
            color = if (isPressed) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.14f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.White.copy(alpha = if (isPressed) 1f else 0.72f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

