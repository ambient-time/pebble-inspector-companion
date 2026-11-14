package coredevices.pebble.signal

/** Wording for saved records. Stored kinds, states and ids stay as they are; only what people read changes. */
internal object SignalDisplay {
    private const val CAPTURE_PROMPT = "Capture current context"

    fun title(record: SignalRecord): String = when {
        record.kind == "capture" && record.question == CAPTURE_PROMPT -> "Capture"
        else -> record.question
    }

    fun kind(kind: String): String = when (kind) {
        "capture" -> "Capture"
        "changes" -> "Comparison"
        "analysis" -> "Answer"
        "observation" -> "Session capture"
        "enrichment" -> "Lookup"
        "presence" -> "Nearby check"
        "field_test" -> "Field trial"
        "health_import", "health_sync" -> "Health import"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Blank for a finished record: "ready" told people nothing. */
    fun state(state: String): String = when (state) {
        "ready" -> ""
        "working" -> "in progress"
        "error" -> "failed"
        "cancelled" -> "canceled"
        else -> state.replace('_', ' ')
    }

    fun kindLine(record: SignalRecord): String =
        listOf(kind(record.kind), state(record.state)).filter { it.isNotBlank() && it != title(record) }.joinToString(" · ")

    /** On-device models have one fixed model each, so its internal id is left out. */
    fun answerSource(provider: String, model: String): String =
        if (provider in SignalLocalModels.providers || model.isBlank()) providerLabel(provider) else "${providerLabel(provider)} · $model"

    fun sourceLine(record: SignalRecord): String =
        listOf(if (record.provider == "local") "Saved on this device" else answerSource(record.provider, record.model), state(record.state))
            .filter { it.isNotBlank() }.joinToString(" · ")

    /** One line per record a question will include, for the review screen. */
    fun reviewLine(record: SignalRecord): String = "${kind(record.kind)} · ${signalDateTime(record.createdAt)}"

    fun number(value: Double): String = if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
}
