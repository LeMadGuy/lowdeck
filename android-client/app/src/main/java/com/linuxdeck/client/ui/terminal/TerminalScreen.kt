package com.linuxdeck.client.ui.terminal

import android.annotation.SuppressLint
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService

/**
 * FIX-02: Isolated WebTerminal screen using embedded xterm.js + direct binary WebSocket over :8444.
 *
 * Replaces CPU-heavy Jetpack Compose Canvas / Text VT100 parsing with hardware-accelerated WebView.
 * Raw PTY bytes never touch the Android main thread, preventing ANRs and memory leaks.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    terminalViewModel: TerminalViewModel? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(view) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prevKeepScreenOn
        }
    }

    val sessionClient = remember(context) {
        DeckConnectionService.obtainClient(context.applicationContext)
    }

    val connectionState by sessionClient.connectionState.collectAsStateWithLifecycle()
    val host = remember(connectionState) {
        (connectionState as? ConnectionState.Connected)?.host ?: "127.0.0.1"
    }
    val terminalUrl = remember(host) { "http://$host:8444/terminal" }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isCtrlArmed by remember { mutableStateOf(false) }

    fun sendRawBytes(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        webViewInstance?.evaluateJavascript("window.sendRawBase64('$b64')", null)
    }

    fun handleQuickKey(key: SysadminQuickKey) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val payload = when (key) {
            SysadminQuickKey.ESC -> byteArrayOf(0x1B)
            SysadminQuickKey.TAB -> byteArrayOf(0x09)
            SysadminQuickKey.CTRL_C -> byteArrayOf(0x03)
            SysadminQuickKey.CTRL_Z -> byteArrayOf(0x1A)
            SysadminQuickKey.CTRL_D -> byteArrayOf(0x04)
            SysadminQuickKey.CTRL_L -> byteArrayOf(0x0C)
            SysadminQuickKey.ARROW_UP -> "\u001B[A".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_DOWN -> "\u001B[B".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_LEFT -> "\u001B[D".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_RIGHT -> "\u001B[C".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.PIPE -> "|".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.SLASH -> "/".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.DASH -> "-".toByteArray(Charsets.US_ASCII)
        }
        sendRawBytes(payload)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .imePadding(),
    ) {
        // Top Bar: Terminal info & quick actions
        TerminalTopBar(
            host = host,
            onReload = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                webViewInstance?.reload()
            },
            onToggleKeyboard = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                webViewInstance?.requestFocus()
                keyboardController?.show()
            },
        )

        // Center: Hardware-accelerated WebView rendering xterm.js
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()

                        isFocusable = true
                        isFocusableInTouchMode = true

                        loadUrl(terminalUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                },
            )
        }

        // Bottom Bar: Sysadmin quick toolbar above software keyboard
        SysadminQuickToolbar(
            isCtrlArmed = isCtrlArmed,
            onToggleCtrl = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isCtrlArmed = !isCtrlArmed
            },
            onQuickKey = { key ->
                handleQuickKey(key)
                if (isCtrlArmed) isCtrlArmed = false
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply {
                loadUrl("about:blank")
                stopLoading()
                destroy()
            }
            webViewInstance = null
        }
    }
}

@Composable
private fun TerminalTopBar(
    host: String,
    onReload: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3FB950)),
                )
                Text(
                    text = "Terminal PTY",
                    color = Color(0xFFC9D1D9),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "$host:8444",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "Abrir teclado",
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = onReload,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Recarregar terminal",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SysadminQuickToolbar(
    isCtrlArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onQuickKey: (SysadminQuickKey) -> Unit,
) {
    val orderedKeys = remember {
        listOf(
            SysadminQuickKey.ESC,
            SysadminQuickKey.TAB,
            SysadminQuickKey.CTRL_C,
            SysadminQuickKey.CTRL_Z,
            SysadminQuickKey.CTRL_D,
            SysadminQuickKey.CTRL_L,
            SysadminQuickKey.ARROW_UP,
            SysadminQuickKey.ARROW_DOWN,
            SysadminQuickKey.ARROW_LEFT,
            SysadminQuickKey.ARROW_RIGHT,
            SysadminQuickKey.PIPE,
            SysadminQuickKey.SLASH,
            SysadminQuickKey.DASH,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SysadminKeyPill(
                label = "CTRL",
                highlighted = isCtrlArmed,
                dangerAccent = false,
                onClick = onToggleCtrl,
            )

            orderedKeys.forEach { key ->
                SysadminKeyPill(
                    label = key.label,
                    highlighted = false,
                    dangerAccent = key == SysadminQuickKey.CTRL_C || key == SysadminQuickKey.CTRL_D,
                    onClick = { onQuickKey(key) },
                )
            }
        }
    }
}

@Composable
private fun SysadminKeyPill(
    label: String,
    highlighted: Boolean,
    dangerAccent: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        highlighted -> Color(0xFF58A6FF)
        dangerAccent -> Color(0xFFF85149).copy(alpha = 0.5f)
        else -> Color(0xFF30363D)
    }
    val textColor = when {
        highlighted -> Color(0xFF58A6FF)
        dangerAccent -> Color(0xFFFFA198)
        else -> Color(0xFFC9D1D9)
    }

    Surface(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (highlighted) Color(0xFF388BFD).copy(alpha = 0.15f) else Color(0xFF21262D),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
