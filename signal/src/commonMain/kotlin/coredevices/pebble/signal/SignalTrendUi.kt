package coredevices.pebble.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun SignalTrendPanel(record: SignalRecord, state: SignalState, onDetail: (String) -> Unit) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }) { Text(if (expanded) "Hide numeric history" else "Numeric history · local") }
        if (!expanded) return@Column
        Text("Retained measurements up to this observation. Calculated on this phone; no key or network is needed.", style = MaterialTheme.typography.bodySmall)
        val series = remember(record, state.records, state.settings.enabled) { SignalTrend.series(record, state.records, state.settings.enabled) }
        if (series.isEmpty()) {
            Text(if (!SignalHistory.allowed(record, state.settings.enabled)) "Enable this record’s sources in Settings to view its numeric history."
                else "No suitable numeric readings in this record. Cached readings, radio scan slots, angular values and counters without a comparable interval are excluded.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        var selected by remember(record.id) { mutableStateOf(series.first().identity.id) }
        val current = series.firstOrNull { it.identity.id == selected } ?: series.first()
        SignalChoice("Measurement", current.identity.id, series.map { it.identity.id to trendTitle(it.identity, state.sources) }) { selected = it }
        val key = current.identity
        Text("Origin: ${key.source} · device metadata: ${key.device}", style = MaterialTheme.typography.bodySmall)
        Text("Period: ${key.period}${key.recordingMethod.takeIf { it.isNotBlank() }?.let { " · recording method: $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        if (key.source.startsWith("health_connect:")) Text("Device metadata can be incomplete and does not uniquely identify physical hardware. Origins and supplied device metadata remain separate.", style = MaterialTheme.typography.bodySmall)
        if (key.bootScope.isNotBlank()) Text("Counter intervals are from one recorded phone boot.", style = MaterialTheme.typography.bodySmall)
        Text("${current.points.size} distinct retained measurements · ${current.independentSessions} sessions or imports", style = MaterialTheme.typography.labelLarge)
        if (current.points.isEmpty()) Text("Matching rows were unusable, conflicting or overlapping. No values are plotted.")
        else {
            Text("Median ${trendValue(current.median!!)} ${key.unit} · range ${trendValue(current.minimum!!)}–${trendValue(current.maximum!!)} ${key.unit}".trim())
            Text(current.difference?.let { "First-to-last difference: ${trendValue(it)} ${key.unit}." }
                ?: if (current.points.size < 2) "At least two distinct measurements are needed for a difference." else "First-to-last difference exceeds the numeric range.",
                style = MaterialTheme.typography.bodySmall)
            SignalTrendPlot(current)
            Text("Points show measured times; space between them is unsampled time. No continuous coverage or cause is inferred.", style = MaterialTheme.typography.bodySmall)
        }
        val excluded = current.unusableRows + current.incompatibleWindows + current.duplicateCopies + current.conflictingRows + current.overlappingRows
        if (excluded > 0) Text("Excluded rows: ${current.unusableRows} unusable, ${current.incompatibleWindows} incompatible windows, ${current.duplicateCopies} repeated copies, ${current.conflictingRows} conflicting, ${current.overlappingRows} overlapping.", style = MaterialTheme.typography.bodySmall)
        if (current.olderPointsOmitted > 0) Text("${current.olderPointsOmitted} older comparable points are outside this 60-point view.", style = MaterialTheme.typography.bodySmall)
        if (current.sourceRowsOmitted > 0) Text("These records report ${current.sourceRowsOmitted} omitted rows for this source. That count may include other measurements from the same source.", style = MaterialTheme.typography.bodySmall)
        Text("Only currently enabled, loaded original records are considered. Deleted, unloaded and disabled evidence cannot contribute to this view.", style = MaterialTheme.typography.bodySmall)
        if (current.points.isNotEmpty()) SignalTrendValues(current, onDetail)
    }
}

@Composable
private fun SignalTrendPlot(series: SignalTrendSeries) {
    val dot = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val points = series.points
    Column {
        Text("Maximum ${trendValue(series.maximum!!)} ${series.identity.unit}".trim(), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {
            contentDescription = "Plot of ${points.size} measured values. Use Exact values and evidence below for the complete table."
        }) {
            val inset = 8.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(0f)
            val height = (size.height - 2 * inset).coerceAtLeast(0f)
            drawLine(axis, Offset(inset, inset), Offset(inset, inset + height), 1.dp.toPx())
            drawLine(axis, Offset(inset, inset + height), Offset(inset + width, inset + height), 1.dp.toPx())
            // Scale before subtraction so finite extremes cannot overflow the chart range.
            val scale = points.maxOf { abs(it.value) }.takeIf { it > 0 } ?: 1.0
            val minimum = points.minOf { it.value / scale }
            val maximum = points.maxOf { it.value / scale }
            val first = points.first().measuredAt.toDouble()
            val last = points.last().measuredAt.toDouble()
            points.forEach { point ->
                val x = if (last > first) (point.measuredAt.toDouble() - first) / (last - first) else 0.5
                val y = if (maximum > minimum) (point.value / scale - minimum) / (maximum - minimum) else 0.5
                drawCircle(dot, 3.dp.toPx(), Offset(inset + width * x.toFloat(), inset + height * (1 - y.toFloat())))
            }
        }
        Text("Minimum ${trendValue(series.minimum!!)} ${series.identity.unit}".trim(), style = MaterialTheme.typography.labelSmall)
        Text("${signalDateTime(points.first().measuredAt)} → ${signalDateTime(points.last().measuredAt)}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalTrendValues(series: SignalTrendSeries, onDetail: (String) -> Unit) {
    var expanded by remember(series.identity.id) { mutableStateOf(false) }
    var visible by remember(series.identity.id) { mutableStateOf(10) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
        stateDescription = if (expanded) "Expanded" else "Collapsed"
    }) { Text(if (expanded) "Hide exact values and evidence" else "Exact values and evidence") }
    if (!expanded) return
    Text("Measurement table · phone local time", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    series.points.takeLast(visible).reversed().forEach { point ->
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(signalDateTime(point.measuredAt), Modifier.weight(1f))
            Text("${trendValue(point.value)} ${series.identity.unit}".trim(), Modifier.weight(1f))
        }
        if (point.windowStart != null && point.windowEnd != null) Text("Window: ${signalDateTime(point.windowStart)} to ${signalDateTime(point.windowEnd)}", style = MaterialTheme.typography.bodySmall)
        point.sampleCount?.let { Text("Underlying sample count reported by this reading: $it", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { onDetail(point.recordId) }) { Text("Evidence · ${point.recordId}") }
    }
    if (series.points.size > visible) TextButton(onClick = { visible += 10 }) { Text("Show more values · ${series.points.size - visible} remaining") }
}

private fun trendTitle(key: SignalTrendIdentity, sources: List<SignalSource>): String = buildString {
    append(sources.firstOrNull { it.key == key.key }?.name ?: key.key)
    if (key.metric.isNotBlank()) append(" · ${key.metric.replace('_', ' ').replace('.', ' ')}")
    if (key.unit.isNotBlank()) append(" (${key.unit})")
    append(" · ${key.period}")
    if (key.source != "phone") append(" · ${key.source} · ${key.device}")
}

private fun trendValue(value: Double): String = value.toString().removeSuffix(".0")
