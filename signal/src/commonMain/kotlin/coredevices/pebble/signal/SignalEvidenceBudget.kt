package coredevices.pebble.signal

import kotlinx.serialization.json.Json

/** Keep record identity and dated evidence when a capture is larger than the request allowance. */
object SignalEvidenceBudget {
    private val json = Json { encodeDefaults = true }
    private fun bytes(record: SignalRecord) = json.encodeToString(record).encodeToByteArray().size

    fun excerpts(records: List<SignalRecord>, maxBytes: Int = 64 * 1024): List<SignalRecord> {
        if (records.isEmpty()) return emptyList()
        require(maxBytes > 0)
        val unique = records.distinctBy { it.id }
        var remaining = maxBytes
        return unique.mapIndexed { index, record ->
            val allowance = remaining / (unique.size - index)
            val excerpt = excerpt(record, allowance)
            remaining -= bytes(excerpt)
            excerpt
        }
    }

    private fun excerpt(record: SignalRecord, allowance: Int): SignalRecord {
        if (bytes(record) <= allowance) return record
        val shell = record.copy(
            question = SignalProviders.truncateUtf8(record.question, minOf(1000, allowance / 8)),
            answer = SignalProviders.truncateUtf8(record.answer, minOf(2000, allowance / 8)),
            summary = "", observations = emptyList(), references = emptyList(), fieldTest = null,
            memoryReferences = emptyMap(), coverage = emptyList(),
        )
        if (bytes(shell) > allowance) tooLarge()
        fun candidate(budget: Int): SignalRecord {
            val retained = SignalBudget.retain(record.observations, 200, budget) { json.encodeToString(it).encodeToByteArray().size }
            val coverage = retained.coverage.map { current ->
                val original = record.coverage.firstOrNull { it.key == current.key }
                val observed = maxOf(current.observed, original?.observed ?: 0)
                current.copy(observed = observed, omitted = observed - current.retained,
                    status = if (observed > current.retained) "partial" else current.status)
            } + record.coverage.filter { original -> retained.coverage.none { it.key == original.key } }
                .map { original -> original.copy(retained = 0, omitted = original.observed,
                    status = if (original.observed > 0) "partial" else original.status) }
            return shell.copy(observations = retained.observations, coverage = coverage)
        }
        // The serialized envelope (including coverage and separators) counts toward the limit.
        var low = 0; var high = allowance
        var result = candidate(0)
        if (bytes(result) > allowance) tooLarge()
        while (low <= high) {
            val middle = (low + high) / 2
            val next = candidate(middle)
            if (bytes(next) <= allowance) { result = next; low = middle + 1 } else high = middle - 1
        }
        if (record.observations.isNotEmpty() && result.observations.isEmpty()) tooLarge()
        return result
    }

    private fun tooLarge(): Nothing = throw SignalProviderException("The selected evidence cannot fit safely in one question. Choose fewer captures or sources, then review again. Nothing was sent.")
}
