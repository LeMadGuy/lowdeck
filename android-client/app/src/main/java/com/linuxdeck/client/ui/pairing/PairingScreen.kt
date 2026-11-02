package com.linuxdeck.client.ui.pairing

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.DiscoveredHost
import com.linuxdeck.client.network.MdnsDiscoveryManager
import com.linuxdeck.client.security.PairedHostConfig
import com.linuxdeck.client.security.PairingQrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable UI state for [PairingScreen] following unidirectional MVVM.
 */
data class PairingUiState(
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val isDiscovering: Boolean = false,
    val discoveryError: String? = null,
    val hostInput: String = "127.0.0.1",
    val controlPortInput: String = PairingQrPayload.DEFAULT_CONTROL_PORT.toString(),
    val pairingPortInput: String = PairingQrPayload.DEFAULT_PAIRING_PORT.toString(),
    val serverFpInput: String = "",
    val qrOrTokenInput: String = "",
    val isScannerOpen: Boolean = false,
    val isPairing: Boolean = false,
    val statusStep: String? = null,
    val errorMessage: String? = null,
    val pairedHost: PairedHostConfig? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected(),
    val clientCertShortFp: String = "",
)

/**
 * Single-shot UI events emitted via [SharedFlow].
 */
sealed interface PairingUiEvent {
    data class ShowMessage(val message: String) : PairingUiEvent
    data class SessionReady(val hostConfig: PairedHostConfig) : PairingUiEvent
}

/**
 * ViewModel coordinating mDNS discovery, QR code / manual token pairing, and mTLS 1.3 handshake.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val keyManager = DeckConnectionService.obtainKeyManager(appContext)
    private val sessionClient = DeckConnectionService.obtainClient(appContext)
    private val mdnsManager = MdnsDiscoveryManager(appContext)

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PairingUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PairingUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { keyManager.ensureClientIdentity() }.onSuccess { id ->
                _uiState.update { it.copy(clientCertShortFp = id.fingerprintSha256.take(16)) }
            }
            mdnsManager.startDiscovery()
        }

        viewModelScope.launch {
            combine(
                mdnsManager.discoveredHosts,
                mdnsManager.isDiscovering,
                mdnsManager.lastError,
                keyManager.pairedHost,
                sessionClient.connectionState,
            ) { hosts, discovering, mdnsErr, paired, connState ->
                 quintuple(hosts, discovering, mdnsErr, paired, connState)
            }.collect { (hosts, discovering, mdnsErr, paired, connState) ->
                _uiState.update { current ->
                    current.copy(
                        discoveredHosts = hosts,
                        isDiscovering = discovering,
                        discoveryError = mdnsErr,
                        pairedHost = paired,
                        connectionState = connState,
                        hostInput = if (current.hostInput == "127.0.0.1" && paired != null) {
                            paired.host
                        } else {
                            current.hostInput
                        },
                    )
                }
            }
        }
    }

    fun onHostChanged(value: String) {
        _uiState.update { it.copy(hostInput = value, errorMessage = null) }
    }

    fun onControlPortChanged(value: String) {
        _uiState.update { it.copy(controlPortInput = value.filter(Char::isDigit), errorMessage = null) }
    }

    fun onPairingPortChanged(value: String) {
        _uiState.update { it.copy(pairingPortInput = value.filter(Char::isDigit), errorMessage = null) }
    }

    fun onServerFpChanged(value: String) {
        _uiState.update { it.copy(serverFpInput = value, errorMessage = null) }
    }

    fun onQrOrTokenChanged(value: String) {
        _uiState.update { it.copy(qrOrTokenInput = value, errorMessage = null) }
        val trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            runCatching { PairingQrPayload.parse(trimmed) }.onSuccess { parsed ->
                _uiState.update {
                    it.copy(
                        hostInput = parsed.host,
                        controlPortInput = parsed.port.toString(),
                        pairingPortInput = parsed.pairingPort.toString(),
                        serverFpInput = parsed.serverFp,
                    )
                }
            }
        }
    }

    fun setScannerOpen(open: Boolean) {
        _uiState.update { it.copy(isScannerOpen = open, errorMessage = null) }
    }

    fun onQrCodeScanned(rawValue: String) {
        onQrOrTokenChanged(rawValue)
        _uiState.update { it.copy(isScannerOpen = false) }
        submitPairing()
    }

    fun selectDiscoveredHost(host: DiscoveredHost) {
        _uiState.update {
            it.copy(
                hostInput = host.hostAddress,
                controlPortInput = host.port.toString(),
                pairingPortInput = host.pairingPort.toString(),
                errorMessage = null,
            )
        }
    }

    fun selectUsbLoopback() {
        val usb = DiscoveredHost.usbAdbReverse()
        selectDiscoveredHost(usb)
        viewModelScope.launch {
            _events.emit(
                PairingUiEvent.ShowMessage(
                    "Modo USB selecionado (127.0.0.1:8443). Execute 'adb reverse tcp:8443 tcp:8443 && adb reverse tcp:8444 tcp:8444' no PC.",
                ),
            )
        }
    }

    fun refreshDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            mdnsManager.refresh()
        }
    }

    fun submitPairing() {
        val snapshot = _uiState.value
        if (snapshot.isPairing) return

        viewModelScope.launch(Dispatchers.IO) {
            val controlPort = snapshot.controlPortInput.toIntOrNull()
                ?: PairingQrPayload.DEFAULT_CONTROL_PORT
            val pairingPort = snapshot.pairingPortInput.toIntOrNull()
                ?: PairingQrPayload.DEFAULT_PAIRING_PORT

            val payload = try {
                PairingQrPayload.parse(
                    rawInput = snapshot.qrOrTokenInput,
                    fallbackHost = snapshot.hostInput,
                    fallbackPort = controlPort,
                    fallbackPairingPort = pairingPort,
                    fallbackServerFp = snapshot.serverFpInput,
                )
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Dados de pareamento inválidos.") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isPairing = true,
                    errorMessage = null,
                    statusStep = "Gerando identidade ECDSA P-256 e validando token no Linux...",
                )
            }

            val pairedResult = runCatching {
                keyManager.pairWithDaemon(qrPayload = payload)
            }

            val pairedCfg = pairedResult.getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isPairing = false,
                        statusStep = null,
                        errorMessage = err.message ?: "Falha no pareamento mTLS.",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(statusStep = "Iniciando sessão WebSocket mTLS 1.3 e Handshake...")
            }

            DeckConnectionService.startAndConnect(
                context = appContext,
                overrideHost = pairedCfg.host,
                overridePort = pairedCfg.port,
            )

            val established = withTimeoutOrNull(8_000L) {
                sessionClient.connectionState.first { state ->
                    state is ConnectionState.Connected || state is ConnectionState.AuthFailed
                }
            }

            when (established) {
                is ConnectionState.Connected -> {
                    _uiState.update {
                        it.copy(
                            isPairing = false,
                            statusStep = "Conectado a ${established.hostDeviceName}!",
                            qrOrTokenInput = "",
                        )
                    }
                    _events.emit(PairingUiEvent.SessionReady(pairedCfg))
                }

                is ConnectionState.AuthFailed -> {
                    _uiState.update {
                        it.copy(
                            isPairing = false,
                            statusStep = null,
                            errorMessage = established.message,
                        )
                    }
                }

                else -> {
                    _uiState.update {
                        it.copy(
                            isPairing = false,
                            statusStep = "Pareado! Conectando em segundo plano a ${pairedCfg.host}:${pairedCfg.port}...",
                        )
                    }
                    _events.emit(PairingUiEvent.SessionReady(pairedCfg))
                }
            }
        }
    }

    fun connectPairedHost() {
        val snapshot = _uiState.value
        val host = snapshot.hostInput.trim().ifEmpty { snapshot.pairedHost?.host ?: return }
        val port = snapshot.controlPortInput.toIntOrNull()
            ?: snapshot.pairedHost?.port
            ?: PairingQrPayload.DEFAULT_CONTROL_PORT

        DeckConnectionService.startAndConnect(
            context = appContext,
            overrideHost = host,
            overridePort = port,
        )
    }

    fun disconnectSession() {
        DeckConnectionService.stop(appContext)
    }

    fun unpairHost() {
        viewModelScope.launch(Dispatchers.IO) {
            DeckConnectionService.stop(appContext)
            keyManager.clearPairedHost()
            _events.emit(PairingUiEvent.ShowMessage("Pareamento removido do dispositivo."))
        }
    }

    override fun onCleared() {
        viewModelScope.launch(Dispatchers.IO) {
            mdnsManager.stopDiscovery()
        }
        super.onCleared()
    }

    private data class Tuple5<A, B, C, D, E>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
        val e: E,
    )

    private fun <A, B, C, D, E> quintuple(a: A, b: B, c: C, d: D, e: E) = Tuple5(a, b, c, d, e)
}

/**
 * Main entry activity hosting [PairingRoute] (and ready to integrate with `MainScaffold` in TASK-13).
 */
class PairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PairingRoute()
                }
            }
        }
    }
}

@Composable
fun PairingRoute(
    onSessionReady: (PairedHostConfig) -> Unit = {},
    viewModel: PairingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PairingUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is PairingUiEvent.SessionReady -> onSessionReady(event.hostConfig)
            }
        }
    }

    PairingScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefreshDiscovery = viewModel::refreshDiscovery,
        onSelectHost = viewModel::selectDiscoveredHost,
        onSelectUsb = viewModel::selectUsbLoopback,
        onHostChange = viewModel::onHostChanged,
        onControlPortChange = viewModel::onControlPortChanged,
        onPairingPortChange = viewModel::onPairingPortChanged,
        onServerFpChange = viewModel::onServerFpChanged,
        onQrOrTokenChange = viewModel::onQrOrTokenChanged,
        onToggleScanner = viewModel::setScannerOpen,
        onQrCodeDetected = viewModel::onQrCodeScanned,
        onSubmitPairing = viewModel::submitPairing,
        onConnectPaired = viewModel::connectPairedHost,
        onDisconnect = viewModel::disconnectSession,
        onUnpair = viewModel::unpairHost,
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    state: PairingUiState,
    snackbarHostState: SnackbarHostState,
    onRefreshDiscovery: () -> Unit,
    onSelectHost: (DiscoveredHost) -> Unit,
    onSelectUsb: () -> Unit,
    onHostChange: (String) -> Unit,
    onControlPortChange: (String) -> Unit,
    onPairingPortChange: (String) -> Unit,
    onServerFpChange: (String) -> Unit,
    onQrOrTokenChange: (String) -> Unit,
    onToggleScanner: (Boolean) -> Unit,
    onQrCodeDetected: (String) -> Unit,
    onSubmitPairing: () -> Unit,
    onConnectPaired: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionDenied = !granted
        onToggleScanner(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Linux Deck — Conexão Segura", fontWeight = FontWeight.Bold)
                        if (state.clientCertShortFp.isNotEmpty()) {
                            Text(
                                text = "Cliente mTLS ECDSA: ${state.clientCertShortFp}…",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshDiscovery) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar descoberta mDNS")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionBannerCard(
                connectionState = state.connectionState,
                pairedHost = state.pairedHost,
                onConnect = onConnectPaired,
                onDisconnect = onDisconnect,
                onUnpair = onUnpair,
            )

            // Discovered Hosts on LAN + USB Quick Action
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PCs Descobertos na Rede (_linuxdeck._tcp)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }

                    AssistChip(
                        onClick = onSelectUsb,
                        label = { Text("Cabo USB (adb reverse 127.0.0.1:8443)") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Cable,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )

                    if (state.discoveredHosts.isEmpty()) {
                        Text(
                            text = state.discoveryError
                                ?: "Procurando daemons Linux na LAN via mDNS…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.discoveredHosts.forEach { host ->
                            DiscoveredHostRow(
                                host = host,
                                isSelected = state.hostInput == host.hostAddress &&
                                    state.controlPortInput == host.port.toString(),
                                onClick = { onSelectHost(host) },
                            )
                        }
                    }
                }
            }

            // QR Code Camera Scanner
            if (state.isScannerOpen) {
                QrCameraScannerCard(
                    onQrDetected = onQrCodeDetected,
                    onClose = { onToggleScanner(false) },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                cameraPermissionDenied = false
                                onToggleScanner(true)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Escanear QR Code")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text.orEmpty()
                            if (clipText.isNotBlank()) {
                                onQrOrTokenChange(clipText)
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Colar")
                    }
                }
            }

            if (cameraPermissionDenied) {
                Text(
                    text = "Permissão de câmera negada. Você ainda pode colar o JSON ou Token gerado por 'daemon --pair' abaixo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Manual / Paste Pairing Form
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Pareamento via QR JSON ou Token Manual",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    OutlinedTextField(
                        value = state.qrOrTokenInput,
                        onValueChange = onQrOrTokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("JSON do QR Code ou Token Hex (uso único)") },
                        placeholder = { Text("{\"host\":\"192.168.1.10\",\"port\":8443,\"token\":\"…\"}") },
                        minLines = 2,
                        maxLines = 4,
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.hostInput,
                            onValueChange = onHostChange,
                            modifier = Modifier.weight(1.8f),
                            singleLine = true,
                            label = { Text("IP / Hostname / Tailscale") },
                        )
                        OutlinedTextField(
                            value = state.controlPortInput,
                            onValueChange = onControlPortChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("Porta WSS") },
                        )
                        OutlinedTextField(
                            value = state.pairingPortInput,
                            onValueChange = onPairingPortChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("Porta Pair") },
                        )
                    }

                    OutlinedTextField(
                        value = state.serverFpInput,
                        onValueChange = onServerFpChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Server SHA-256 Fingerprint (Pinning)") },
                    )

                    if (!state.errorMessage.isNullOrBlank()) {
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (!state.statusStep.isNullOrBlank()) {
                        Text(
                            text = state.statusStep,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Button(
                        onClick = onSubmitPairing,
                        enabled = !state.isPairing && state.qrOrTokenInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isPairing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Pareando via TLS 1.3…")
                        } else {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Parear e Iniciar Handshake mTLS")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConnectionBannerCard(
    connectionState: ConnectionState,
    pairedHost: PairedHostConfig?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is ConnectionState.AuthFailed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (connectionState is ConnectionState.Connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    val headline = when (connectionState) {
                        is ConnectionState.Connected -> {
                            val rtt = connectionState.latencyMs?.let { " • ${it}ms" }.orEmpty()
                            "Conectado: ${connectionState.hostDeviceName}$rtt"
                        }
                        is ConnectionState.Connecting ->
                            "Conectando a ${connectionState.host}:${connectionState.port}…"
                        is ConnectionState.Reconnecting ->
                            "Reconectando (tentativa ${connectionState.attempt})…"
                        is ConnectionState.AuthFailed ->
                            "Erro de Autenticação mTLS"
                        is ConnectionState.Disconnected ->
                            if (pairedHost != null) "Host pareado (desconectado)" else "Nenhum PC pareado"
                    }
                    Text(headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    if (pairedHost != null) {
                        Text(
                            text = "${pairedHost.host}:${pairedHost.port} • Pin SHA-256: ${pairedHost.serverFingerprintSha256.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else if (connectionState is ConnectionState.AuthFailed) {
                        Text(connectionState.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (pairedHost != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionState is ConnectionState.Connected ||
                        connectionState is ConnectionState.Connecting ||
                        connectionState is ConnectionState.Reconnecting
                    ) {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Desconectar")
                        }
                    } else {
                        Button(onClick = onConnect) {
                            Text("Conectar")
                        }
                    }

                    OutlinedButton(onClick = onUnpair) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Esquecer PC")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredHostRow(
    host: DiscoveredHost,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Computer, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(host.hostname, fontWeight = FontWeight.Medium)
            val fpSuffix = host.shortFingerprint.takeIf { it.isNotEmpty() }?.let { " • fp=$it" }.orEmpty()
            Text(
                text = "${host.hostAddress}:${host.port}$fpSuffix",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun QrCameraScannerCard(
    onQrDetected: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processQrImageProxy(barcodeScanner, imageProxy) { rawQr ->
                            if (consumed.compareAndSet(false, true)) {
                                mainExecutor.execute { onQrDetected(rawQr) }
                            }
                        }
                    }
                }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, mainExecutor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Aponte a câmera para o QR Code no terminal Linux",
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar câmera")
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processQrImageProxy(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    onDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val qrText = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
            if (!qrText.isNullOrBlank()) {
                onDetected(qrText)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

