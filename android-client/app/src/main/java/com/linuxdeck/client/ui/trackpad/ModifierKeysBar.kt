package com.linuxdeck.client.ui.trackpad

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.KeyComboEvent
import com.linuxdeck.client.protocol.v1.KeyEvent
import java.util.UUID

/**
 * Linux modifier keys supported as sticky (toggle-locked) keys across mouse clicks and keyboard events.
 */
enum class StickyModifier(
    val protoKeyName: String,
    val displayLabel: String,
) {
    CTRL(protoKeyName = "CTRL", displayLabel = "Ctrl"),
    ALT(protoKeyName = "ALT", displayLabel = "Alt"),
    SUPER(protoKeyName = "SUPER", displayLabel = "Super"),
    SHIFT(protoKeyName = "SHIFT", displayLabel = "Shift"),
}

/**
 * Result of toggling or clearing sticky modifiers, pairing the updated UI set with the
 * exact `/dev/uinput` `KeyEvent` (`PRESS` or `RELEASE`) envelopes to transmit.
 */
@Immutable
data class StickyModifierTransition(
    val activeModifiers: Set<StickyModifier>,
    val envelopes: List<Envelope>,
)

/**
 * Pure state helper for sticky modifier keys (`Ctrl`, `Alt`, `Super`, `Shift`) and Linux
 * special key/combo generation.
 *
 * When a modifier is toggled ON, emits `KeyEvent(key = mod, action = PRESS)` so the Linux kernel
 * holds the modifier down across both `/dev/uinput` relative mouse clicks (e.g. `Ctrl+Click`,
 * `Super+Drag`) and subsequent keyboard events. When toggled OFF or disposed, emits `RELEASE`.
 */
object StickyModifierHelper {

    fun toggleModifier(
        current: Set<StickyModifier>,
        modifier: StickyModifier,
        nowMs: Long = System.currentTimeMillis(),
    ): StickyModifierTransition {
        val next = LinkedHashSet(current)
        val isNowLocked = if (next.contains(modifier)) {
            next.remove(modifier)
            false
        } else {
            next.add(modifier)
            true
        }

        val action = if (isNowLocked) ButtonAction.PRESS else ButtonAction.RELEASE
        val env = buildKeyActionEnvelope(
            keyName = modifier.protoKeyName,
            action = action,
            nowMs = nowMs,
        )
        return StickyModifierTransition(
            activeModifiers = next,
            envelopes = listOf(env),
        )
    }

    fun releaseAll(
        current: Set<StickyModifier>,
        nowMs: Long = System.currentTimeMillis(),
    ): StickyModifierTransition {
        if (current.isEmpty()) {
            return StickyModifierTransition(emptySet(), emptyList())
        }
        val releaseEnvelopes = current.map { mod ->
            buildKeyActionEnvelope(
                keyName = mod.protoKeyName,
                action = ButtonAction.RELEASE,
                nowMs = nowMs,
            )
        }
        return StickyModifierTransition(
            activeModifiers = emptySet(),
            envelopes = releaseEnvelopes,
        )
    }

    fun buildKeyActionEnvelope(
        keyName: String,
        action: ButtonAction = ButtonAction.CLICK,
        nowMs: Long = System.currentTimeMillis(),
    ): Envelope = Envelope.newBuilder()
        .setRequestId("key-${UUID.randomUUID()}")
        .setTimestampMs(nowMs)
        .setKeyEvent(
            KeyEvent.newBuilder()
                .setKey(keyName)
                .setAction(action)
                .build(),
        )
        .build()

    /**
     * Builds a `KeyComboEvent` (e.g., `SUPER+1`..`SUPER+5`) and, if any sticky modifier used in the
     * combo was locked prior to the shortcut, re-asserts its `PRESS` state afterward so the kernel
     * never loses a user-locked sticky modifier.
     */
    fun buildComboEnvelopes(
        combo: String,
        lockedModifiers: Set<StickyModifier>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Envelope> {
        val out = ArrayList<Envelope>(lockedModifiers.size + 1)
        out.add(
            Envelope.newBuilder()
                .setRequestId("combo-${UUID.randomUUID()}")
                .setTimestampMs(nowMs)
                .setKeyComboEvent(
                    KeyComboEvent.newBuilder()
                        .setCombo(combo)
                        .build(),
                )
                .build(),
        )
        // Re-assert locked modifiers that ParseKeyCombo released at the end of the chord.
        val upperComboTokens = combo.uppercase().split("+").toSet()
        for (mod in lockedModifiers) {
            if (mod.protoKeyName in upperComboTokens) {
                out.add(
                    buildKeyActionEnvelope(
                        keyName = mod.protoKeyName,
                        action = ButtonAction.PRESS,
                        nowMs = nowMs,
                    ),
                )
            }
        }
        return out
    }
}

/**
 * Compact, tactile Linux modifier & special keys toolbar reusable across `TrackpadScreen` (`TASK-11`)
 * and `RemoteDesktopScreen` (`TASK-12`).
 *
 * Provides:
 * - Sticky ("travadas") toggle keys for `Ctrl`, `Alt`, `Super`, `Shift`
 * - Direct keys for `Esc`, `Tab`, `Enter`, `Bksp`, and directional arrows (`←`, `↓`, `↑`, `→`)
 * - Direct Linux workspace switchers (`Super+1` .. `Super+5`)
 * - Expandable `F1..F12` & navigation (`Home`, `End`, `PgUp`, `PgDn`, `Del`) strip
 * - Optional button to toggle the Android soft keyboard (IME)
 */
@Composable
fun ModifierKeysBar(
    lockedModifiers: Set<StickyModifier>,
    onToggleModifier: (StickyModifier) -> Unit,
    onClearModifiers: () -> Unit,
    onSpecialKeyClick: (String) -> Unit,
    onComboClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSoftKeyboardVisible: Boolean = false,
    onToggleSoftKeyboard: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    var showExtendedKeys by remember { mutableStateOf(false) }

    fun tapKey(keyName: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onSpecialKeyClick(keyName)
    }

    fun tapCombo(combo: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onComboClick(combo)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Row 1: Sticky Modifiers (Ctrl, Alt, Super, Shift) + Fn toggle + Soft Keyboard trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StickyModifier.entries.forEach { mod ->
                    val isLocked = mod in lockedModifiers
                    StickyModifierChip(
                        label = mod.displayLabel,
                        isLocked = isLocked,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleModifier(mod)
                        },
                    )
                }

                if (lockedModifiers.isNotEmpty()) {
                    DirectKeyButton(
                        label = "Limpar",
                        accentColor = Color(0xFFF59E0B),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClearModifiers()
                        },
                    )
                }

                DirectKeyButton(
                    label = if (showExtendedKeys) "Fn ▲" else "F1-12",
                    accentColor = if (showExtendedKeys) Color(0xFF38BDF8) else null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showExtendedKeys = !showExtendedKeys
                    },
                )

                if (onToggleSoftKeyboard != null) {
                    IconKeyButton(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = "Teclado virtual",
                        highlighted = isSoftKeyboardVisible,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSoftKeyboard()
                        },
                    )
                }
            }

            // Row 2: Direct Special Keys (Esc, Tab, Bksp, Enter) + Arrow Cluster + Workspaces (Super+1..5)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectKeyButton(label = "Esc", onClick = { tapKey("ESC") })
                DirectKeyButton(label = "Tab", onClick = { tapKey("TAB") })
                DirectKeyButton(label = "Bksp", onClick = { tapKey("BACKSPACE") })
                DirectKeyButton(label = "Enter", onClick = { tapKey("ENTER") })

                Spacer(modifier = Modifier.width(2.dp))

                // Arrow keys
                IconKeyButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Seta Esquerda",
                    onClick = { tapKey("LEFT") },
                )
                IconKeyButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Seta Baixo",
                    onClick = { tapKey("DOWN") },
                )
                IconKeyButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Seta Cima",
                    onClick = { tapKey("UP") },
                )
                IconKeyButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Seta Direita",
                    onClick = { tapKey("RIGHT") },
                )

                Spacer(modifier = Modifier.width(2.dp))

                // Workspace shortcuts (Super+1..5)
                for (ws in 1..5) {
                    DirectKeyButton(
                        label = "WS$ws",
                        accentColor = Color(0xFF93C5FD),
                        onClick = { tapCombo("SUPER+$ws") },
                    )
                }
            }

            // Expandable Row 3: Function keys F1-F12 & Navigation keys
            AnimatedVisibility(visible = showExtendedKeys) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (fn in 1..12) {
                        DirectKeyButton(
                            label = "F$fn",
                            onClick = { tapKey("F$fn") },
                        )
                    }
                    DirectKeyButton(label = "Del", onClick = { tapKey("DELETE") })
                    DirectKeyButton(label = "Home", onClick = { tapKey("HOME") })
                    DirectKeyButton(label = "End", onClick = { tapKey("END") })
                    DirectKeyButton(label = "PgUp", onClick = { tapKey("PAGEUP") })
                    DirectKeyButton(label = "PgDn", onClick = { tapKey("PAGEDOWN") })
                }
            }
        }
    }
}

@Composable
private fun StickyModifierChip(
    label: String,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isLocked) Color(0xFF0284C7).copy(alpha = 0.32f) else Color(0xFF1E293B),
        animationSpec = tween(durationMillis = 150),
        label = "StickyModifierBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isLocked) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.14f),
        animationSpec = tween(durationMillis = 150),
        label = "StickyModifierBorder",
    )

    Surface(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(if (isLocked) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isLocked) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.25f)),
            )
            Text(
                text = label,
                color = if (isLocked) Color(0xFFE0F2FE) else Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isLocked) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DirectKeyButton(
    label: String,
    onClick: () -> Unit,
    accentColor: Color? = null,
) {
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, (accentColor ?: Color.White).copy(alpha = 0.18f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = accentColor ?: Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun IconKeyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) Color(0xFF0284C7).copy(alpha = 0.30f) else Color(0xFF1E293B),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.16f),
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlighted) Color(0xFF38BDF8) else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
