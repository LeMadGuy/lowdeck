package com.linuxdeck.client.ui.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.security.PairedHostConfig
import com.linuxdeck.client.ui.deck.DeckScreen
import com.linuxdeck.client.ui.pairing.PairingRoute
import com.linuxdeck.client.ui.remote.RemoteDesktopScreen
import com.linuxdeck.client.ui.terminal.TerminalScreen
import com.linuxdeck.client.ui.trackpad.TrackpadScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Route constant for the QR Code & mTLS 1.3 Pairing flow (`TASK-09`).
 */
const val PAIRING_ROUTE: String = "pairing"

/**
 * Primary navigation tabs unified in [MainScaffold] (`TASK-13`):
 * 1. **Deck** (`TASK-10`)
 * 2. **Trackpad** (`TASK-11`)
 * 3. **Tela Remota** (`TASK-12`)
 * 4. **Terminal** (`TASK-13`)
 */
enum class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
) {
    DECK(
        route = "deck",
        label = "Deck",
        icon = Icons.Rounded.GridView,
        contentDescription = "Macro Deck Dinâmico e Widgets de Telemetria",
    ),
    TRACKPAD(
        route = "trackpad",
        label = "Trackpad",
        icon = Icons.Rounded.TouchApp,
        contentDescription = "Trackpad Multi-gesto e Barra de Modificadores",
    ),
    REMOTE_DESKTOP(
        route = "remote",
        label = "Tela Remota",
        icon = Icons.Rounded.DesktopWindows,
        contentDescription = "Desktop Remoto WebRTC de Baixa Latência",
    ),
    TERMINAL(
        route = "terminal",
        label = "Terminal",
        icon = Icons.Rounded.Terminal,
        contentDescription = "Emulador de Terminal Interativo PTY",
    ),
    ;

    companion object {
        fun fromRoute(route: String?): MainDestination? =
            entries.firstOrNull { it.route == route }
    }
}

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF020617),
    secondary = Color(0xFF34D399),
    background = Color(0xFF020617),
    surface = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFE2E8F0),
)

/**
 * Optional standalone Activity entrypoint hosting [LinuxDeckApp] with [MainScaffold].
 */
class MainNavigationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LinuxDeckApp()
        }
    }
}

/**
 * Root Material 3 Dark Theme wrapper mounting [MainScaffold].
 */
@Composable
fun LinuxDeckApp(
    modifier: Modifier = Modifier,
) {
    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainScaffold()
        }
    }
}

/**
 * Unified top-level navigation scaffold integrating all 4 operational modes of Linux Deck & Remote
 * Control (**Deck**, **Trackpad**, **Tela Remota**, and **Terminal**) alongside the persistent
 * top bar displaying live mTLS 1.3 session status and RTT latency (`ms`).
 */
@Composable
fun MainScaffold(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()

    val keyManager = remember(context) { DeckConnectionService.obtainKeyManager(context) }
    val sessionClient = remember(context) { DeckConnectionService.obtainClient(context) }

    val pairedHost by keyManager.pairedHost.collectAsStateWithLifecycle()
    val connectionState by sessionClient.connectionState.collectAsStateWithLifecycle()
    val latencyMs by sessionClient.latencyMs.collectAsStateWithLifecycle()

    // Initial route: jump straight to Deck if a paired Linux host exists, or Pairing on first run.
    val initialStartRoute = remember {
        if (keyManager.pairedHost.value != null) {
            MainDestination.DECK.route
        } else {
            PAIRING_ROUTE
        }
    }

    // Automatically initiate background mTLS connection when a paired host is present on launch.
    LaunchedEffect(pairedHost?.host, pairedHost?.port) {
        val cfg = pairedHost ?: return@LaunchedEffect
        val currentState = sessionClient.connectionState.value
        if (currentState is ConnectionState.Disconnected && currentState.reason == null) {
            DeckConnectionService.startAndConnect(
                context = context,
                overrideHost = cfg.host,
                overridePort = cfg.port,
            )
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val isOnPairingScreen = currentRoute == PAIRING_ROUTE

    val navigateToTab: (MainDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navigateToPairing: () -> Unit = {
        if (currentRoute != PAIRING_ROUTE) {
            navController.navigate(PAIRING_ROUTE) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF020617),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MtlsConnectionStatusTopBar(
                connectionState = connectionState,
                pairedHost = pairedHost,
                latencyMs = latencyMs,
                isOnPairingScreen = isOnPairingScreen,
                onPingRequested = {
                    ioScope.launch(Dispatchers.IO) {
                        sessionClient.sendPingNow()
                    }
                },
                onReconnectRequested = {
                    val cfg = pairedHost
                    if (cfg != null) {
                        DeckConnectionService.startAndConnect(
                            context = context,
                            overrideHost = cfg.host,
                            overridePort = cfg.port,
                        )
                    } else {
                        navigateToPairing()
                    }
                },
                onOpenPairing = navigateToPairing,
                onBackFromPairing = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navigateToTab(MainDestination.DECK)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0B1120),
                contentColor = Color(0xFFE2E8F0),
                tonalElevation = 0.dp,
            ) {
                MainDestination.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateToTab(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.contentDescription,
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF38BDF8),
                            selectedTextColor = Color(0xFF38BDF8),
                            indicatorColor = Color(0xFF0C4A6E),
                            unselectedIconColor = Color(0xFF94A3B8),
                            unselectedTextColor = Color(0xFF94A3B8),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = initialStartRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(route = MainDestination.DECK.route) {
                DeckScreen(
                    modifier = Modifier.fillMaxSize(),
                    onNavigateToPairing = navigateToPairing,
                )
            }

            composable(route = MainDestination.TRACKPAD.route) {
                TrackpadScreen(
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(route = MainDestination.REMOTE_DESKTOP.route) {
                RemoteDesktopScreen(
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(route = MainDestination.TERMINAL.route) {
                TerminalScreen(
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(route = PAIRING_ROUTE) {
                PairingRoute(
                    onSessionReady = {
                        navController.navigate(MainDestination.DECK.route) {
                            popUpTo(PAIRING_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

/**
 * Persistent top status bar displaying the live mTLS 1.3 state, paired Linux host identity,
 * RTT `Ping`/`Pong` latency in milliseconds, and one-tap controls for pairing and reconnection.
 */
@Composable
fun MtlsConnectionStatusTopBar(
    connectionState: ConnectionState,
    pairedHost: PairedHostConfig?,
    latencyMs: Long?,
    isOnPairingScreen: Boolean,
    onPingRequested: () -> Unit,
    onReconnectRequested: () -> Unit,
    onOpenPairing: () -> Unit,
    onBackFromPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (dotColor, statusTitle, statusSubtitle) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            Color(0xFF10B981),
            "mTLS 1.3 · ${connectionState.hostDeviceName.ifBlank { connectionState.host }}",
            "${connectionState.host}:${connectionState.port}",
        )

        is ConnectionState.Connecting -> Triple(
            Color(0xFFF59E0B),
            "Conectando mTLS (#${connectionState.attempt})...",
            "${connectionState.host}:${connectionState.port}",
        )

        is ConnectionState.Reconnecting -> Triple(
            Color(0xFFF59E0B),
            "Reconectando (#${connectionState.attempt})...",
            "${connectionState.host}:${connectionState.port} · ${(connectionState.nextRetryDelayMs / 1000).coerceAtLeast(1)}s",
        )

        is ConnectionState.AuthFailed -> Triple(
            Color(0xFFF43F5E),
            "Falha de Autenticação mTLS",
            connectionState.message,
        )

        is ConnectionState.Disconnected -> Triple(
            Color(0xFF64748B),
            if (pairedHost != null) "Desconectado · ${pairedHost.host}" else "Host Linux não pareado",
            connectionState.reason ?: if (pairedHost != null) "Toque em reconectar" else "Pareie via QR Code",
        )
    }

    val effectiveLatencyMs = latencyMs ?: (connectionState as? ConnectionState.Connected)?.latencyMs

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF0B1120),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOnPairingScreen) {
                    IconButton(
                        onClick = onBackFromPairing,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Voltar às abas principais",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        color = Color(0xFFF8FAFC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusSubtitle,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (connectionState is ConnectionState.Connected) {
                    LatencyBadge(
                        latencyMs = effectiveLatencyMs,
                        onClick = onPingRequested,
                    )
                } else {
                    IconButton(
                        onClick = onReconnectRequested,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reconectar ao daemon Linux",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                IconButton(
                    onClick = if (isOnPairingScreen) onBackFromPairing else onOpenPairing,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = "Pareamento QR Code e Configuração mTLS",
                        tint = if (isOnPairingScreen) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyBadge(
    latencyMs: Long?,
    onClick: () -> Unit,
) {
    val badgeColor = when {
        latencyMs == null -> Color(0xFF94A3B8)
        latencyMs <= 15L -> Color(0xFF34D399)
        latencyMs <= 50L -> Color(0xFFFBBF24)
        else -> Color(0xFFFB7185)
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = badgeColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = "Atualizar latência RTT",
                tint = badgeColor,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = if (latencyMs != null) "${latencyMs} ms" else "-- ms",
                color = badgeColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

