package coredevices.pebble.signal

/** Quoted RFC 4180 cells; text cannot become a spreadsheet formula. Numeric values stay numeric. */
object SignalCsv {
    private fun cell(value: String, numeric: Boolean = false): String {
        val text = if (!numeric && (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@') || value.firstOrNull() in setOf('\t', '\r', '\n'))) "'$value" else value
        return "\"${text.replace("\"", "\"\"")}\""
    }
    val header = listOf("record_id", "session_id", "observation_id", "source_key", "origin", "device", "metric", "value", "unit", "status", "collected_at_ms", "measured_at_ms", "window_start_ms", "window_end_ms", "period", "sample_count", "accuracy", "parent_records").joinToString(",") { cell(it) } + "\r\n"
    fun rows(record: SignalRecord, enabled: Set<String>): String {
        if (!SignalHistory.allowed(record, enabled)) return ""
        return SignalLearning.normalize(record).observations.filter { it.number?.isFinite() == true }.joinToString("") { o ->
            val values = listOf(record.id, record.sessionId, o.id, o.key, o.source, record.watchId.ifBlank { o.identity }, o.metric, o.number.toString(), o.unit, o.status,
                o.collectedAt.toString(), o.measuredAt?.toString().orEmpty(), o.windowStart?.toString().orEmpty(), o.windowEnd?.toString().orEmpty(), o.period,
                o.sampleCount?.toString().orEmpty(), o.accuracy?.toString().orEmpty(), record.references.joinToString(";"))
            values.mapIndexed { index, value -> cell(value, index in setOf(7, 10, 11, 12, 13, 15, 16)) }.joinToString(",") + "\r\n"
        }
    }
}
