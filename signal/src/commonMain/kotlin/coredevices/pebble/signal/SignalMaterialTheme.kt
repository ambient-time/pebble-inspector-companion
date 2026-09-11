package coredevices.pebble.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalMaterialTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF5CD9FF), onPrimary = Color(0xFF003640),
        primaryContainer = Color(0xFF094957), onPrimaryContainer = Color(0xFFBBEFFF),
        secondary = Color(0xFFA5CDD3), onSecondary = Color(0xFF12343A),
        secondaryContainer = Color(0xFF2B4B52), onSecondaryContainer = Color(0xFFC5E9EF),
        tertiary = Color(0xFFF5B441), onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Color(0xFFFFDF99),
        background = Color(0xFF101416), onBackground = Color(0xFFE3EAF2),
        surface = Color(0xFF151B1E), onSurface = Color(0xFFE3EAF2),
        surfaceVariant = Color(0xFF253034), onSurfaceVariant = Color(0xFFBDCCD4),
        outline = Color(0xFF879BA5), outlineVariant = Color(0xFF3B4F59),
        inverseSurface = Color(0xFFE3EAF2), inverseOnSurface = Color(0xFF152530), inversePrimary = Color(0xFF00666C),
        surfaceTint = Color(0xFF5CD9FF),
    ) else lightColorScheme(
        primary = Color(0xFF00666C), onPrimary = Color.White,
        primaryContainer = Color(0xFFBFECEF), onPrimaryContainer = Color(0xFF00363B),
        secondary = Color(0xFF365D64), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD5E9EC), onSecondaryContainer = Color(0xFF19373D),
        tertiary = Color(0xFF7A5700), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE4AC), onTertiaryContainer = Color(0xFF362600),
        background = Color(0xFFF7F9F9), onBackground = Color(0xFF162A30),
        surface = Color(0xFFF7F9F9), onSurface = Color(0xFF162A30),
        surfaceVariant = Color(0xFFDFE9E9), onSurfaceVariant = Color(0xFF3C5055),
        outline = Color(0xFF6D8185), outlineVariant = Color(0xFFBBCBCD),
        inverseSurface = Color(0xFF23383E), inverseOnSurface = Color(0xFFEDF5F5), inversePrimary = Color(0xFF5CD9FF),
        surfaceTint = Color(0xFF00666C),
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) { content() }
    }
}

@Composable
internal fun SignalAntennaGlyph() {
    val ring = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(32.dp)) {
        val radius = size.minDimension * 0.4f
        listOf(0.33f, 0.66f, 1f).forEach { drawCircle(ring, radius * it, style = Stroke(1.5.dp.toPx())) }
        drawCircle(dot, 3.dp.toPx(), Offset(center.x + radius * 0.65f, center.y - radius * 0.65f))
    }
}

internal fun signalCount(count: Int, singular: String): String = "$count ${if (count == 1) singular else if (singular == "memory") "memories" else "${singular}s"}"

/** Coverage counts retain missing outcomes rather than implying that they are measured zeroes. */
internal fun signalObservationCoverage(record: SignalRecord): String {
    val missing = record.observations.count { it.status !in setOf("available", "fresh", "cached", "stale", "partial", "estimate", "coarse_estimate", "candidate", "observed", "inside", "outside", "recorded", "modeled", "forecast", "timestamp_unknown") }
    val measured = record.observations.size - missing
    val sources = record.sourceKeys.size.takeIf { it > 0 } ?: record.observations.map { it.key }.distinct().size
    return "${signalCount(measured, "reading")} from ${signalCount(sources, "source")}" +
        if (missing > 0) " · $missing unavailable" else ""
}
