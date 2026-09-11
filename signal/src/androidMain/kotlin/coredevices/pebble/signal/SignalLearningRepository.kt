package coredevices.pebble.signal

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal class SignalLearningRepository(private val store: SignalStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun indexHistory(progress: (Long) -> Unit) {
        if (store.document("index-v2") == "complete") return
        var count = 0L
        store.walk { _, record ->
            currentCoroutineContext().ensureActive()
            store.index(record)
            if (record.state in setOf("working", "recording")) store.save(record.copy(state = "interrupted", summary = "Interrupted; no request was retried."))
            if (++count % 500 == 0L) progress(count)
        }
        store.document("index-v2", "checkpoint", "complete")
    }

    suspend fun ingest(record: SignalRecord, now: Long) {
        if (record.kind !in setOf("capture", "presence", "observation", "health_import") || record.state != "ready" || record.references.isNotEmpty() || now - record.createdAt !in 0..SignalLearning.WINDOW_MILLIS) return
        val date = Instant.fromEpochMilliseconds(record.createdAt).toLocalDateTime(TimeZone.currentSystemDefault())
        val healthSeries = if (record.kind == "health_import") record.observations.firstOrNull()?.let { "${it.key}:${it.identity}:${it.period}" }.orEmpty() else ""
        val bucket = store.opaqueIndex("${date.date}:${date.hour}:${record.kind}:${record.watchId}:${record.observations.firstOrNull()?.source}:$healthSeries")
        val key = "f:$bucket"
        val normalized = SignalLearning.normalize(record)
        val budget = SignalBudget.retain(normalized.observations
            .filter { it.number != null || it.identity.startsWith("fence:") || it.identity.startsWith("target:") }, 64)
        val frame = normalized.copy(question = "", answer = "", summary = "", observations = budget.observations.map { it.copy(value = it.value.take(200)) }, coverage = budget.coverage)
        if (frame.observations.isEmpty()) return
        val old = store.document(key)?.let { json.decodeFromString<SignalRecord>(it) }
        if (old == null || old.createdAt <= record.createdAt)
            store.document(key, "frame", json.encodeToString(frame))
    }

    suspend fun backfill(now: Long, progress: (Long) -> Unit) {
        // Only disposable frames are rebuilt. Originals, identifiers, keys and corrections stay intact.
        if (store.document("learning-frame-version") != "3") {
            rebuild()
            store.document("learning-frame-version", "checkpoint", "3")
        }
        val cursor = store.document("learning-cursor")?.toLongOrNull() ?: 0L
        var last = cursor; var count = 0L
        store.walk(cursor) { position, record ->
            currentCoroutineContext().ensureActive(); ingest(record, now); last = position
            if (++count % 500 == 0L) { store.document("learning-cursor", "checkpoint", last.toString()); progress(count) }
        }
        store.document("learning-cursor", "checkpoint", last.toString())
    }

    suspend fun rebuild() { store.clearDocuments("frame"); store.document("learning-cursor", "checkpoint", "0") }

    suspend fun evaluate(settings: SignalSettings, now: Long): List<SignalMemory> {
        val known = store.memory().toMutableList()
        if (!settings.learningEnabled) return known
        val frames = store.documents("frame").map { json.decodeFromString<SignalRecord>(it) }
        // Stale summaries are disposable; original history has no retention expiry.
        val live = frames.filter { now - it.createdAt in 0..SignalLearning.WINDOW_MILLIS }
        if (frames.size != live.size) {
            store.clearDocuments("frame")
            live.forEach { ingest(it, now) }
        }
        val candidates = SignalLearning.proposals(live, settings, now)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).date
        var surfaced = known.count { Instant.fromEpochMilliseconds(it.createdAt).toLocalDateTime(TimeZone.currentSystemDefault()).date == today && it.kind != "note" }
        for (candidate in candidates.sortedBy { it.fingerprint }) {
            if (store.document("forgot:${store.opaqueIndex(candidate.fingerprint)}") != null) continue
            val previous = known.firstOrNull { it.fingerprint == candidate.fingerprint }
            val next = when {
                previous == null && surfaced < 3 -> candidate.copy(id = UUID.randomUUID().toString(), coverage = candidate.coverage + " Learning uses at most one original capture per source group per hour.").also { surfaced++ }
                previous == null -> continue
                previous.state in setOf("rejected", "forgotten", "note") -> continue
                previous.state == "proposed" -> candidate.copy(id = previous.id, createdAt = previous.createdAt, revision = if (candidate.text == previous.text && candidate.evidence == previous.evidence) previous.revision else previous.revision + 1)
                else -> previous.copy(evaluatedAt = now, needsReview = candidate.text != previous.acceptedPatternText.ifBlank { previous.text },
                    proposedText = if (candidate.text != previous.acceptedPatternText.ifBlank { previous.text }) candidate.text else "",
                    proposedEvidence = if (candidate.text != previous.acceptedPatternText.ifBlank { previous.text }) candidate.evidence else emptyList())
            }
            store.saveMemory(next); known.removeAll { it.id == next.id }; known += next
        }
        return known.sortedByDescending { it.createdAt }
    }

    suspend fun eligible(record: SignalRecord, settings: SignalSettings, memories: List<SignalMemory>): Boolean =
        eligible(record, settings, memories, mutableSetOf())

    private suspend fun eligible(record: SignalRecord, settings: SignalSettings, memories: List<SignalMemory>, scopedVisits: MutableSet<String>): Boolean {
        val visiting = mutableSetOf<String>(); val verified = mutableSetOf<String>()
        val stack = ArrayDeque<Pair<SignalRecord, Boolean>>(); stack.addLast(record to false)
        while (stack.isNotEmpty()) {
            val (row, exit) = stack.removeLast()
            if (exit) { visiting.remove(row.id); verified += row.id; continue }
            if (row.id in verified) continue
            if (!visiting.add(row.id) || visiting.size + verified.size > 2048 || !SignalHistory.allowed(row, settings.enabled)) return false
            if (row.memoryReferences.any { (id, revision) -> memories.none { it.id == id && it.revision == revision && SignalLearning.eligible(it, settings) } }) return false
            val selection = row.evidenceScope
            if (selection != null) {
                if (scopedVisits.size >= 64 || !scopedVisits.add(row.id)) return false
                if (!settings.enabled.containsAll(selection.sourceKeys) || selection.recordIds != row.references.toSet()) return false
                val selectedSettings = settings.copy(enabled = selection.sourceKeys)
                for ((key, expected) in selection.revisions) {
                    val evidence = store.record(key) ?: return false
                    val actual = java.security.MessageDigest.getInstance("SHA-256").digest(json.encodeToString(evidence).toByteArray()).joinToString("") { "%02x".format(it) }
                    if (actual != expected) return false
                }
                for (key in selection.recordIds) {
                    val evidence = store.record(key) ?: return false
                    if (key !in selection.revisions || SignalHistoryScope.project(evidence, selection.projectionQuery, selectedSettings.enabled) == null) return false
                    val raw = evidence.references.isEmpty() && evidence.memoryReferences.isEmpty() && evidence.observations.isNotEmpty() &&
                        (evidence.kind in setOf("capture", "observation", "health_import") || evidence.provider == "local")
                    if (!raw && !eligible(evidence, selectedSettings, memories, scopedVisits)) return false
                }
                scopedVisits.remove(row.id)
                visiting.remove(row.id); verified += row.id
                continue
            }
            stack.addLast(row to true)
            for (reference in row.references.distinct()) {
                if (reference in visiting) return false
                if (reference !in verified) stack.addLast((store.record(reference) ?: return false) to false)
            }
        }
        return true
    }
}
