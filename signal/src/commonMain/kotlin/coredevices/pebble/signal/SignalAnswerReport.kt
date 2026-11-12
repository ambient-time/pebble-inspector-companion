package coredevices.pebble.signal

import io.ktor.http.encodeURLParameter

/** A report about one generated answer. It leaves the device only as an email the person sends. */
internal object SignalAnswerReport {
    val reasons = listOf(
        "offensive" to "Offensive or hateful",
        "sexual" to "Sexual content",
        "dangerous" to "Dangerous or harmful",
        "false" to "False or misleading",
        "other" to "Something else",
    )
    const val NOTE_LIMIT = 500
    private const val QUESTION_LIMIT = 600
    private const val ANSWER_LIMIT = 3000

    fun reportable(record: SignalRecord) = record.provider != "local" && record.answer.isNotBlank() && record.state == "ready"

    fun reasonLabel(reason: String) = reasons.firstOrNull { it.first == reason }?.second ?: reason

    private fun clip(text: String, limit: Int) = if (text.length > limit) text.take(limit - 12) + "\n[shortened]" else text

    /** Question, answer and where the answer came from. No readings, keys or device identifiers. */
    fun text(record: SignalRecord, reason: String, note: String, build: String): String = listOf(
        "Reason: ${reasonLabel(reason)}",
        note.trim().take(NOTE_LIMIT).takeIf { it.isNotEmpty() }?.let { "Note: $it" },
        "App: Signal Station for Android, $build",
        "Answer source: ${SignalDisplay.answerSource(record.provider, record.model)}",
        "Answered: ${signalDateTime(record.createdAt)}",
        "",
        "Question:",
        clip(record.question, QUESTION_LIMIT).ifBlank { "(none)" },
        "",
        "Reported answer:",
        clip(record.answer, ANSWER_LIMIT),
    ).filterNotNull().joinToString("\n")

    fun mailto(email: String, record: SignalRecord, reason: String, note: String, build: String): String =
        "mailto:$email?subject=${"Signal Station answer report: ${reasonLabel(reason)}".encodeURLParameter()}&body=${text(record, reason, note, build).encodeURLParameter()}"
}
