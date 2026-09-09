package coredevices.pebble.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SignalFieldTest(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val attempts: List<String> = List(10) { "unrecorded" },
    val falseTriggers: Int? = null,
    val buildVersion: String = "",
    val stopRecordIds: List<String> = List(3) { "" },
    val startBattery: Int? = null,
    val endBattery: Int? = null,
    val note: String = "",
) {
    fun valid(): Boolean = id.isNotBlank() && startedAt > 0 &&
        (endedAt == null || endedAt >= startedAt) && attempts.size == 10 &&
        attempts.all { it in setOf("unrecorded", "recognized", "missed") } &&
        (falseTriggers == null || falseTriggers in 0..999) && stopRecordIds.size == 3 &&
        stopRecordIds.filter { it.isNotBlank() }.distinct().size == stopRecordIds.count { it.isNotBlank() } &&
        (startBattery == null || startBattery in 0..100) && (endBattery == null || endBattery in 0..100) && note.length <= 2000

    fun summary(): String = buildString {
        append("Field trial · manually recorded\n")
        append("${attempts.count { it == "recognized" }} recognized, ${attempts.count { it == "missed" }} missed, ${attempts.count { it == "unrecorded" }} unrecorded.\n")
        append("${falseTriggers?.let { "$it false triggers reported" } ?: "False triggers not recorded"}. ${stopRecordIds.count { it.isNotBlank() }}/3 stops linked.\n")
        if (buildVersion.isNotBlank()) append("Build: $buildVersion.\n")
        endedAt?.let { append("Elapsed: ${(it - startedAt) / 60_000} minutes.\n") } ?: append("Trial still in progress.\n")
        if (startBattery != null && endBattery != null) append("Phone battery: $startBattery% → $endBattery%. Other phone activity may affect this change.\n")
        if (note.isNotBlank()) append(note)
    }

    fun record(referenceRecords: List<SignalRecord>): SignalRecord {
        require(valid()) { "Invalid field trial" }
        val ids = stopRecordIds.filter { it.isNotBlank() }
        val refs = ids.map { id -> referenceRecords.firstOrNull { it.id == id && it.state == "ready" && it.observations.isNotEmpty() } ?: error("A linked capture is no longer available") }
        return SignalRecord(id = id, threadId = id, createdAt = startedAt, question = "20-minute field trial",
            answer = summary(), summary = summary(), provider = "local", model = "manual", state = "ready", kind = "field_test",
            references = ids, sourceKeys = refs.flatMap { it.sourceKeys + it.observations.map { row -> row.key } }.toSet(),
            endpoint = "", observations = emptyList(), fieldTest = this)
    }

    fun encode(): String = Json.encodeToString(this)
}
