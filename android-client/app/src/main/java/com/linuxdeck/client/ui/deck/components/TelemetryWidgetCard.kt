package com.linuxdeck.client.ui.deck.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linuxdeck.client.ui.deck.DeckButtonUiModel
import com.linuxdeck.client.ui.deck.TelemetryUiState
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private val GaugeCyan = Color(0xFF38BDF8)
private val GaugeEmerald = Color(0xFF34D399)
private val GaugeAmber = Color(0xFFFBBF24)
private val GaugeRed = Color(0xFFF87171)
private val GaugeTrack = Color.White.copy(alpha = 0.12f)

/**
 * Live hardware telemetry widget card (`CPU`, `RAM`, `TEMPERATURE`, `NETWORK`, or `ALL`).
 *
 * **Recomposition Isolation Guarantee:**
 * [telemetryFlow] is collected exclusively inside this leaf `@Composable` scope via
 * [collectAsStateWithLifecycle]. Consequently, 1 Hz `TelemetrySnapshot` packets invalidate only
 * visible [TelemetryWidgetCard] scopes and NEVER trigger recomposition of `DeckScreen` or
 * sibling `DeckButtonCard` cells.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TelemetryWidgetCard(
    button: DeckButtonUiModel,
    telemetryFlow: StateFlow<TelemetryUiState>,
    onLongClick: (DeckButtonUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val baseColor = remember(button.color) { parseDeckHexColor(button.color, Color(0xFF0F172A)) }
    val metric = remember(button.telemetryMetric) {
        button.telemetryMetric.trim().uppercase(Locale.ROOT)
    }
    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick(button)
                },
            ),
        shape = shape,
        color = baseColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        when (metric) {
            "CPU" -> CpuGaugeContent(title = button.title, telemetry = telemetry)
            "RAM", "MEMORY" -> RamGaugeContent(title = button.title, telemetry = telemetry)
            "TEMPERATURE", "TEMP" -> TempGaugeContent(title = button.title, telemetry = telemetry)
            "NETWORK", "NET" -> NetworkBarsContent(title = button.title, telemetry = telemetry)
            else -> CombinedOverviewContent(title = button.title, telemetry = telemetry)
        }
    }
}

@Composable
private fun CpuGaugeContent(
    title: String,
    telemetry: TelemetryUiState,
) {
    val fraction = (telemetry.cpuPercent / 100f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "CpuGaugeFraction",
    )
    val accent = when {
        telemetry.cpuPercent >= 85f -> GaugeRed
        telemetry.cpuPercent >= 65f -> GaugeAmber
        else -> GaugeCyan
    }

    CircularMetricLayout(
        title = title.ifBlank { "CPU" },
        fraction = animatedFraction,
        valueText = if (telemetry.hasSample) {
            String.format(Locale.US, "%.0f%%", telemetry.cpuPercent)
        } else {
            "--%"
        },
        subtitleText = if (telemetry.primaryTempCelsius != null) {
            String.format(Locale.US, "%.0f°C", telemetry.primaryTempCelsius)
        } else {
            "CPU"
        },
        accentColor = accent,
    )
}

@Composable
private fun RamGaugeContent(
    title: String,
    telemetry: TelemetryUiState,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = telemetry.ramFraction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "RamGaugeFraction",
    )
    val accent = when {
        telemetry.ramFraction >= 0.88f -> GaugeRed
        telemetry.ramFraction >= 0.72f -> GaugeAmber
        else -> GaugeEmerald
    }

    CircularMetricLayout(
        title = title.ifBlank { "RAM" },
        fraction = animatedFraction,
        valueText = if (telemetry.hasSample && telemetry.ramTotalMb > 0L) {
            String.format(Locale.US, "%.1fG", telemetry.ramUsedGb)
        } else {
            "--G"
        },
        subtitleText = if (telemetry.hasSample && telemetry.ramTotalMb > 0L) {
            String.format(Locale.US, "/ %.0f GB", telemetry.ramTotalGb)
        } else {
            "RAM"
        },
        accentColor = accent,
    )
}

@Composable
private fun TempGaugeContent(
    title: String,
    telemetry: TelemetryUiState,
) {
    val tempC = telemetry.primaryTempCelsius ?: 0f
    val fraction = (tempC / 100f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = if (telemetry.primaryTempCelsius != null) fraction else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "TempGaugeFraction",
    )
    val accent = when {
        tempC >= 85f -> GaugeRed
        tempC >= 70f -> GaugeAmber
        else -> GaugeCyan
    }

    CircularMetricLayout(
        title = title.ifBlank { "Temp" },
        fraction = animatedFraction,
        valueText = if (telemetry.primaryTempCelsius != null) {
            String.format(Locale.US, "%.0f°C", tempC)
        } else {
            "--°C"
        },
        subtitleText = telemetry.primaryTempSensor.ifBlank { "térmico" },
        accentColor = accent,
    )
}

@Composable
private fun CircularMetricLayout(
    title: String,
    fraction: Float,
    valueText: String,
    subtitleText: String,
    accentColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val strokePx = 6.dp.toPx()
                val diameter = size.minDimension - strokePx
                val topLeft = Offset(
                    x = (size.width - diameter) / 2f,
                    y = (size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)

                // Background arc (240° sweep)
                drawArc(
                    color = GaugeTrack,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )

                // Animated foreground arc
                drawArc(
                    color = accentColor,
                    startAngle = 150f,
                    sweepAngle = 240f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            Text(
                text = valueText,
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        Text(
            text = subtitleText,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NetworkBarsContent(
    title: String,
    telemetry: TelemetryUiState,
) {
    // Normalize throughput logarithmically/proportionally up to 100 Mbps (102_400 kbps) ceiling for visual bar scale.
    val ceilingKbps = 25_600f
    val rxTarget = (telemetry.rxKbps / ceilingKbps).coerceIn(0f, 1f)
    val txTarget = (telemetry.txKbps / ceilingKbps).coerceIn(0f, 1f)

    val animatedRx by animateFloatAsState(
        targetValue = rxTarget,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "RxBarFraction",
    )
    val animatedTx by animateFloatAsState(
        targetValue = txTarget,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "TxBarFraction",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.ifBlank { "Rede" },
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        ThroughputBarRow(
            label = "↓ RX",
            formattedRate = formatThroughput(telemetry.rxKbps, telemetry.hasSample),
            fraction = animatedRx,
            barColor = GaugeEmerald,
        )

        ThroughputBarRow(
            label = "↑ TX",
            formattedRate = formatThroughput(telemetry.txKbps, telemetry.hasSample),
            fraction = animatedTx,
            barColor = GaugeCyan,
        )
    }
}

@Composable
private fun ThroughputBarRow(
    label: String,
    formattedRate: String,
    fraction: Float,
    barColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formattedRate,
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(GaugeTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun CombinedOverviewContent(
    title: String,
    telemetry: TelemetryUiState,
) {
    val cpuFraction by animateFloatAsState(
        targetValue = (telemetry.cpuPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "CombinedCpuFraction",
    )
    val ramFraction by animateFloatAsState(
        targetValue = telemetry.ramFraction,
        animationSpec = tween(durationMillis = 400),
        label = "CombinedRamFraction",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.ifBlank { "Telemetria" },
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ThroughputBarRow(
            label = "CPU",
            formattedRate = if (telemetry.hasSample) String.format(Locale.US, "%.0f%%", telemetry.cpuPercent) else "--%",
            fraction = cpuFraction,
            barColor = GaugeCyan,
        )
        ThroughputBarRow(
            label = "RAM",
            formattedRate = if (telemetry.hasSample) String.format(Locale.US, "%.1fG", telemetry.ramUsedGb) else "--G",
            fraction = ramFraction,
            barColor = GaugeEmerald,
        )
    }
}

private fun formatThroughput(kbps: Float, hasSample: Boolean): String {
    if (!hasSample) return "-- KB/s"
    // Note: kbps from daemon represents kilobits/sec or kilobytes/sec; format cleanly in KB/s or MB/s.
    val kBps = kbps / 8f
    return if (kBps >= 1024f) {
        String.format(Locale.US, "%.1f MB/s", kBps / 1024f)
    } else {
        String.format(Locale.US, "%.0f KB/s", kBps)
    }
}

