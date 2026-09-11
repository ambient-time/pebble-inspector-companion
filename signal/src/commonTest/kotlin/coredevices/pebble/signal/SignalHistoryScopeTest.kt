package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class SignalHistoryScopeTest {
    private val at = Instant.parse("2026-09-11T01:00:00Z").toEpochMilliseconds()
    private fun record(id: String = "one") = SignalRecord(id, "thread", at, "Private original question", "Private original answer", provider = "local", model = "", state = "ready", kind = "capture",
        observations = listOf(SignalObservation("wifi", "android", "-55", collectedAt = at, date = "2020-01-01"),
            SignalObservation("location", "android", "private coordinates", collectedAt = at)), sourceKeys = setOf("wifi", "location"))
    @Test fun captureDateUsesChosenTimezoneNotMeasurementDate() {
        val query = SignalHistoryQuery(fromDate = "2026-09-10", throughDate = "2026-09-10", zoneId = "America/Los_Angeles")
        assertNotNull(SignalHistoryScope.project(record(), query, setOf("wifi", "location")))
        assertNull(SignalHistoryScope.project(record(), query.copy(zoneId = "UTC"), setOf("wifi", "location")))
        assertFailsWith<IllegalArgumentException> { SignalHistoryScope.validate(query.copy(fromDate = "2026-09-12")) }
    }
    @Test fun sourceProjectionDoesNotLeakBroadProseOrMetadata() {
        val original = record().copy(fieldTest = null, watchId = "private watch", references = listOf("private reference"), memoryReferences = mapOf("private memory" to 1))
        val projected = assertNotNull(SignalHistoryScope.project(original, SignalHistoryQuery(sourceKeys = setOf("wifi")), setOf("wifi", "location")))
        assertEquals(listOf("wifi"), projected.observations.map { it.key })
        val serialized = Json.encodeToString(projected)
        assertFalse(serialized.contains("Private")); assertFalse(serialized.contains("private")); assertFalse(serialized.contains("location"))
        assertEquals(original, original.copy())
    }
    @Test fun disabledSourcesCannotBeSelectedAndTextSearchUsesProjectedEvidence() {
        assertNull(SignalHistoryScope.project(record(), SignalHistoryQuery(sourceKeys = setOf("location")), setOf("wifi")))
        assertNull(SignalHistoryScope.project(record(), SignalHistoryQuery(text = "private", sourceKeys = setOf("wifi")), setOf("wifi")))
        assertNotNull(SignalHistoryScope.project(record(), SignalHistoryQuery(text = "-55", kind = "captures"), setOf("wifi")))
        assertNull(SignalHistoryScope.project(record(), SignalHistoryQuery(kind = "conversations"), setOf("wifi")))
    }
    @Test fun comparisonNeedsTwoMeasuredFramesAndCommonSource() {
        assertTrue(SignalHistoryScope.comparable(listOf(record(), record("two"))))
        assertFalse(SignalHistoryScope.comparable(listOf(record())))
        assertFalse(SignalHistoryScope.comparable(listOf(record(), record("two").copy(observations = listOf(SignalObservation("weather", "android", collectedAt = at))))))
    }
    @Test fun explicitIdsRetainUnrestrictedConversationProjection() {
        val query = SignalHistoryQuery()
        val selection = SignalEvidenceSelection(setOf("chat"), query, setOf("wifi"), 1).copy(query = null)
        val chat = record().copy(id = "chat", provider = "openai", kind = "analysis", observations = emptyList(), sourceKeys = emptySet())
        assertEquals(chat, SignalHistoryScope.project(chat, selection.projectionQuery, selection.sourceKeys))
        assertNotNull(SignalHistoryScope.project(chat.copy(state = "error"), query, emptySet()))
    }
    @Test fun legacyRecipeDecodesWithoutScope() {
        val recipe = Json.decodeFromString<SavedQuestion>("""{"id":"old","title":"Old","question":"Question"}""")
        assertNull(recipe.historyQuery); assertFalse(recipe.scopedEvidence)
    }
}
