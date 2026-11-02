package com.linuxdeck.client.ui.deck.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxdeck.client.protocol.v1.MediaCommandType
import com.linuxdeck.client.ui.deck.MediaUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Compact MPRIS2 media & system volume controller bar.
 *
 * Collects [mediaFlow] strictly inside its own leaf `@Composable` scope so track metadata or
 * playback updates never cause recomposition of the Deck button grid.
 */
@Composable
fun MediaControllerBar(
    mediaFlow: StateFlow<MediaUiState>,
    onMediaCommand: (MediaCommandType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val media by mediaFlow.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    fun trigger(cmd: MediaCommandType) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onMediaCommand(cmd)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: MPRIS2 Track Title & Artist
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (media.isPlaying) {
                        Color(0xFF0284C7).copy(alpha = 0.25f)
                    } else {
                        Color.White.copy(alpha = 0.08f)
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = if (media.isPlaying) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier
                            .padding(7.dp)
                            .size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title.ifBlank { "Nenhuma mídia ativa" },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = buildString {
                        if (media.artist.isNotBlank()) append(media.artist)
                        if (media.player.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(media.player)
                        }
                    }.ifBlank { "MPRIS2 / Controle de Áudio" }

                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Right: Prev, Play/Pause, Next & Volume controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = { trigger(MediaCommandType.PREVIOUS) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Faixa anterior",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = { trigger(MediaCommandType.PLAY_PAUSE) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (media.isPlaying) Color(0xFF0284C7) else Color(0xFF1E293B),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (media.isPlaying) "Pausar" else "Reproduzir",
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = { trigger(MediaCommandType.NEXT) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Próxima faixa",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = { trigger(MediaCommandType.VOLUME_DOWN) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeDown,
                        contentDescription = "Diminuir volume",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp),
                    )
                }

                IconButton(
                    onClick = { trigger(MediaCommandType.MUTE) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeOff,
                        contentDescription = "Silenciar áudio",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = { trigger(MediaCommandType.VOLUME_UP) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = "Aumentar volume",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

