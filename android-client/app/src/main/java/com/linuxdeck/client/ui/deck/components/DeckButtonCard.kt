package com.linuxdeck.client.ui.deck.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxdeck.client.protocol.v1.ButtonType
import com.linuxdeck.client.protocol.v1.MacroRunState
import com.linuxdeck.client.ui.deck.ButtonRuntimeState
import com.linuxdeck.client.ui.deck.DeckButtonUiModel
import com.linuxdeck.client.ui.deck.DeckProfileJsonCodec
import com.linuxdeck.client.ui.deck.QuickEditButtonDraft
import java.util.Locale

private val StatusRunningAmber = Color(0xFFF59E0B)
private val StatusSuccessGreen = Color(0xFF22C55E)
private val StatusErrorRed = Color(0xFFEF4444)
private val ActiveAccentCyan = Color(0xFF38BDF8)

/**
 * Tactile Stream-Deck style button card with `HapticFeedbackType.LongPress`, real-time
 * execution status (`RUNNING`, `SUCCESS`, `ERROR`), dynamic label/icon/color support,
 * and long-press quick editing.
 *
 * Accepts strictly `@Immutable` parameters ([DeckButtonUiModel] and [ButtonRuntimeState])
 * so Compose skips recomposition whenever 1 Hz `TelemetrySnapshot` packets arrive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckButtonCard(
    button: DeckButtonUiModel,
    runtimeState: ButtonRuntimeState?,
    onClick: (DeckButtonUiModel) -> Unit,
    onLongClick: (DeckButtonUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val runState = runtimeState?.runState ?: MacroRunState.IDLE
    val exitCode = runtimeState?.exitCode ?: 0
    val isActive = runtimeState?.isActive == true
    val hasError = runState == MacroRunState.ERROR || (runState == MacroRunState.SUCCESS && exitCode != 0)
    val isRunning = runState == MacroRunState.RUNNING
    val isSuccess = runState == MacroRunState.SUCCESS && exitCode == 0

    val resolvedHex = runtimeState?.dynamicColor ?: button.color
    val baseColor = remember(resolvedHex) { parseDeckHexColor(resolvedHex) }

    val borderColor by animateColorAsState(
        targetValue = when {
            isRunning -> StatusRunningAmber
            hasError -> StatusErrorRed
            isSuccess -> StatusSuccessGreen
            isActive -> ActiveAccentCyan
            button.type == ButtonType.FOLDER -> Color(0xFF60A5FA).copy(alpha = 0.65f)
            else -> Color.White.copy(alpha = 0.14f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "DeckButtonBorderColor",
    )

    val resolvedTitle = runtimeState?.dynamicTitle ?: button.title
    val resolvedIconKey = if (isActive || isRunning) {
        (runtimeState?.dynamicActiveIcon ?: button.activeIcon)
            .ifBlank { runtimeState?.dynamicInactiveIcon ?: button.inactiveIcon }
    } else {
        (runtimeState?.dynamicInactiveIcon ?: button.inactiveIcon)
            .ifBlank { runtimeState?.dynamicActiveIcon ?: button.activeIcon }
    }
    val iconVector = remember(resolvedIconKey, button.type, button.mediaAction) {
        resolveButtonIcon(resolvedIconKey, button.type, button.mediaAction)
    }

    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick(button)
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick(button)
                },
            ),
        shape = shape,
        color = baseColor,
        border = BorderStroke(
            width = if (isRunning || hasError || isSuccess || isActive) 2.dp else 1.dp,
            color = borderColor,
        ),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.24f),
                        ),
                    ),
                )
                .padding(8.dp),
        ) {
            // Top-right execution / folder status indicator
            ExecutionStatusBadge(
                runState = runState,
                exitCode = exitCode,
                hasError = hasError,
                isFolder = button.type == ButtonType.FOLDER,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            // Center icon + title + optional subtitle/output preview
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = resolvedTitle,
                    tint = if (isActive) ActiveAccentCyan else Color.White,
                    modifier = Modifier.size(28.dp),
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = resolvedTitle,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    lineHeight = 14.sp,
                    overflow = TextOverflow.Ellipsis,
                )

                val subtitle = when {
                    !runtimeState?.lastOutputLine.isNullOrBlank() -> runtimeState?.lastOutputLine
                    button.type == ButtonType.KEY_COMBO && button.keyCombo.isNotBlank() -> button.keyCombo
                    else -> null
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutionStatusBadge(
    runState: MacroRunState,
    exitCode: Int,
    hasError: Boolean,
    isFolder: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        runState == MacroRunState.RUNNING -> {
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(50))
                    .background(StatusRunningAmber.copy(alpha = 0.22f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.8.dp,
                    color = StatusRunningAmber,
                )
                Text(
                    text = "RUN",
                    color = StatusRunningAmber,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        hasError -> {
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(50))
                    .background(StatusErrorRed.copy(alpha = 0.24f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = "Erro na execução",
                    tint = StatusErrorRed,
                    modifier = Modifier.size(12.dp),
                )
                if (exitCode != 0) {
                    Text(
                        text = "$exitCode",
                        color = StatusErrorRed,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        runState == MacroRunState.SUCCESS -> {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Executado com sucesso",
                tint = StatusSuccessGreen,
                modifier = modifier.size(15.dp),
            )
        }

        isFolder -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "PASTA",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Long-press quick-edit modal allowing the user to customize a button's title, icon, hex color,
 * and command/shortcut/media action, then persist it back to the Linux daemon via `DeckProfileSync`.
 */
@Composable
fun QuickEditButtonDialog(
    initialDraft: QuickEditButtonDraft,
    onDismiss: () -> Unit,
    onSave: (QuickEditButtonDraft) -> Unit,
) {
    var selectedType by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.type) }
    var title by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.title) }
    var colorHex by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.color) }
    var inactiveIcon by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.inactiveIcon) }
    var activeIcon by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.activeIcon) }
    var actionValue by remember(initialDraft.buttonId) { mutableStateOf(initialDraft.actionValue) }

    val colorPresets = remember {
        listOf("#1E293B", "#0F172A", "#1D4ED8", "#065F46", "#7F1D1D", "#6D28D9", "#B45309")
    }
    val iconPresets = remember {
        listOf("terminal", "lock", "play_arrow", "skip_next", "volume_up", "folder", "storage", "code", "bolt")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Editar Botão (${initialDraft.buttonId})",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Button Type Selector (for non-telemetry buttons, or switching actions)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val editableTypes = listOf(
                        ButtonType.COMMAND to "Comando",
                        ButtonType.KEY_COMBO to "Atalho",
                        ButtonType.MEDIA to "Mídia",
                        ButtonType.FOLDER to "Pasta",
                        ButtonType.TELEMETRY_WIDGET to "Telemetria",
                    )
                    editableTypes.forEach { (typeOption, label) ->
                        FilterChip(
                            selected = selectedType == typeOption,
                            onClick = { selectedType = typeOption },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val actionLabel = when (selectedType) {
                    ButtonType.COMMAND -> "Comando Bash (ex: htop, loginctl lock-session)"
                    ButtonType.KEY_COMBO -> "Atalho (ex: CTRL+ALT+T, SUPER+D)"
                    ButtonType.MEDIA -> "Ação de Mídia (PLAY_PAUSE, NEXT, PREVIOUS, MUTE)"
                    ButtonType.FOLDER -> "ID da Pasta Alvo (ex: sysadmin)"
                    ButtonType.TELEMETRY_WIDGET -> "Métrica (CPU, RAM, TEMPERATURE, NETWORK)"
                    else -> "Comando / Ação"
                }

                OutlinedTextField(
                    value = actionValue,
                    onValueChange = { actionValue = it },
                    label = { Text(actionLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = inactiveIcon,
                    onValueChange = { inactiveIcon = it },
                    label = { Text("Ícone (ex: terminal, lock, code, bolt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    iconPresets.forEach { preset ->
                        FilterChip(
                            selected = inactiveIcon.equals(preset, ignoreCase = true),
                            onClick = { inactiveIcon = preset },
                            label = { Text(preset, fontSize = 11.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = colorHex,
                    onValueChange = { colorHex = it },
                    label = { Text("Cor Hex (#RRGGBB)") },
                    singleLine = true,
                    isError = colorHex.isNotBlank() && !DeckProfileJsonCodec.isValidHexColor(colorHex),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    colorPresets.forEach { hex ->
                        val swatchColor = parseDeckHexColor(hex)
                        val isSelected = DeckProfileJsonCodec.normalizeHexColor(colorHex) == hex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape,
                                )
                                .clickable { colorHex = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        QuickEditButtonDraft(
                            buttonId = initialDraft.buttonId,
                            type = selectedType,
                            title = title,
                            color = DeckProfileJsonCodec.normalizeHexColor(colorHex),
                            inactiveIcon = inactiveIcon,
                            activeIcon = activeIcon,
                            actionValue = actionValue,
                        ),
                    )
                },
            ) {
                Text("Salvar e Sincronizar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

/**
 * Parses `#RRGGBB` or `#RRGGBBAA` strings into a Compose [Color] without relying on Android `android.graphics.Color`.
 */
internal fun parseDeckHexColor(hex: String, fallback: Color = Color(0xFF1E293B)): Color {
    val clean = hex.trim().removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> {
                val rgb = clean.toLong(16)
                Color(0xFF000000L or rgb)
            }
            8 -> {
                val rgba = clean.toLong(16)
                val r = ((rgba shr 24) and 0xFF).toInt()
                val g = ((rgba shr 16) and 0xFF).toInt()
                val b = ((rgba shr 8) and 0xFF).toInt()
                val a = (rgba and 0xFF).toInt()
                Color(red = r, green = g, blue = b, alpha = a)
            }
            else -> fallback
        }
    }.getOrDefault(fallback)
}

private fun resolveButtonIcon(
    iconKey: String,
    type: ButtonType,
    mediaAction: String,
): ImageVector {
    val normalized = iconKey.trim().lowercase(Locale.ROOT)
    when (normalized) {
        "terminal", "terminal_active", "shell", "console" -> return Icons.Rounded.Terminal
        "lock" -> return Icons.Rounded.Lock
        "lock_open", "unlock" -> return Icons.Rounded.LockOpen
        "folder", "folder_open" -> return Icons.Rounded.Folder
        "play", "play_arrow" -> return Icons.Rounded.PlayArrow
        "pause" -> return Icons.Rounded.Pause
        "skip_previous", "previous", "prev" -> return Icons.Rounded.SkipPrevious
        "skip_next", "next" -> return Icons.Rounded.SkipNext
        "stop" -> return Icons.Rounded.Stop
        "volume_up", "vol_up" -> return Icons.Rounded.VolumeUp
        "volume_down", "vol_down" -> return Icons.Rounded.VolumeDown
        "volume_off", "mute" -> return Icons.Rounded.VolumeOff
        "schedule", "uptime", "clock", "timer" -> return Icons.Rounded.Schedule
        "storage", "disk", "drive" -> return Icons.Rounded.Storage
        "power", "power_settings_new", "shutdown" -> return Icons.Rounded.PowerSettingsNew
        "refresh", "reload", "reboot" -> return Icons.Rounded.Refresh
        "code", "script" -> return Icons.Rounded.Code
        "computer", "desktop", "pc" -> return Icons.Rounded.Computer
        "memory", "cpu", "ram" -> return Icons.Rounded.Memory
        "keyboard", "shortcut" -> return Icons.Rounded.Keyboard
        "bolt", "zap" -> return Icons.Rounded.Bolt
    }

    return when (type) {
        ButtonType.FOLDER -> Icons.Rounded.Folder
        ButtonType.KEY_COMBO -> Icons.Rounded.Keyboard
        ButtonType.MEDIA -> when (mediaAction.trim().uppercase(Locale.ROOT)) {
            "PREVIOUS" -> Icons.Rounded.SkipPrevious
            "NEXT" -> Icons.Rounded.SkipNext
            "PAUSE" -> Icons.Rounded.Pause
            "STOP" -> Icons.Rounded.Stop
            "MUTE" -> Icons.Rounded.VolumeOff
            "VOLUME_UP" -> Icons.Rounded.VolumeUp
            "VOLUME_DOWN" -> Icons.Rounded.VolumeDown
            else -> Icons.Rounded.PlayArrow
        }
        ButtonType.TELEMETRY_WIDGET -> Icons.Rounded.Memory
        else -> Icons.Rounded.Bolt
    }
}

