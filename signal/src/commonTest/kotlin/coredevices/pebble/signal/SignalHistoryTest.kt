package coredevices.pebble.signal

import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class SignalHistoryTest {
    private val now = time("2026-09-08T20:00:00Z")
    private val zone = TimeZone.of("America/Los_Angeles")
    private fun time(value: String) = Instant.parse(value).toEpochMilliseconds()
    private fun reading(key: String = "health.steps", value: String = "100", date: String = "2026-09-07", status: String = "available", period: String = "day") =
        SignalObservation(key, "watch", value, if (key == "health.heart_rate") "bpm" else "steps", now, now, status, date, period)
    private fun record(id: String, vararg observations: SignalObservation, createdAt: Long = now, question: String = "Survey", answer: String = "General report", watch: String = "watch-a", references: List<String> = emptyList()) =
        SignalRecord(id, "thread", createdAt, question, answer, provider = "openai", model = "test", watchId = watch, state = "ready", observations = observations.toList(), sourceKeys = observations.map { it.key }.toSet(), references = references)
    private fun retrieve(records: List<SignalRecord>, query: String, enabled: Set<String> = setOf("health.steps")) =
        SignalHistory.retrieve(records, query, enabled, now = now, zone = zone)

    @Test fun projectionKeepsOriginalIdentityAndOnlySendsSelectedEvidence() {
        val original = record("mixed", reading(), reading("health.heart_rate", "SECRET"), question = "Broad private question")
        val projection = SignalHistory.project(listOf(original), "steps yesterday", original.sourceKeys, now = now, zone = zone).single()
        assertEquals(original, projection.original)
        assertEquals("Selected observations", projection.excerpt.question)
        assertEquals("", projection.excerpt.answer)
        assertEquals(listOf("health.steps"), projection.excerpt.observations.map { it.key })
    }

    @Test fun globalRankingKeepsOlderStrongMatchBeyondThirtyRecentRecords() {
        val recent = (1..50).map { record("recent-$it", question = "cedar", answer = "", createdAt = now + it) }
        val strong = record("older", question = "cedar library quiet", answer = "", createdAt = now - 10000)
        val selected = mutableListOf<ProjectedContext>()
        (recent + strong).forEach { row ->
            selected += SignalHistory.project(listOf(row), "cedar library quiet", emptySet(), now = now, zone = zone)
            selected.sortWith(SignalHistory.order)
            if (selected.size > 30) selected.removeAt(selected.lastIndex)
        }
        assertEquals("older", selected.first().original.id)
        assertEquals(30, selected.size)
    }

    @Test fun nonLatinWordsDoNotBecomeAnEmptyQuery() {
        val rows = listOf(record("match", question = "図書館前", answer = ""), record("other", question = "station", answer = ""))
        assertEquals(listOf("match"), retrieve(rows, "図書館前").map { it.id })
    }

    @Test fun disabledObservationCannotHideBehindMissingSourceMetadata() {
        val saved = record("old", reading(), reading("health.heart_rate", "70")).copy(sourceKeys = setOf("health.steps"))
        assertFalse(SignalHistory.allowed(saved, setOf("health.steps")))
        assertTrue(retrieve(listOf(saved), "history").isEmpty())
    }

    @Test fun dependentNarrativeIsGatedRecursivelyAndCyclesDoNotEscape() {
        val original = record("a", reading("location", "private"))
        val narrative = record("b", references = listOf("a"))
        assertTrue(retrieve(listOf(original, narrative), "history").isEmpty())
        val cycleA = record("c", references = listOf("d"))
        val cycleB = record("d", references = listOf("c"))
        assertTrue(retrieve(listOf(cycleA, cycleB), "history").isEmpty())
        assertTrue(retrieve(listOf(record("missing", references = listOf("deleted"))), "history").isEmpty())
    }

    @Test fun explicitDayUsesLocalChatDateAndClipsSurveyNarrative() {
        val survey = record("survey", reading(date = "2026-09-06"), reading(value = "200", date = "2026-09-07"))
        val chat = record("chat", createdAt = time("2026-09-08T02:00:00Z"), question = "What happened?")
        val other = record("other", createdAt = time("2026-09-08T18:00:00Z"))
        val results = retrieve(listOf(survey, chat, other), "Show history on 2026-09-07")
        assertEquals(setOf("survey", "chat"), results.map { it.id }.toSet())
        val excerpt = results.first { it.id == "survey" }
        assertEquals(listOf("2026-09-07"), excerpt.observations.map { it.date })
        assertEquals("", excerpt.answer)
        assertEquals("", excerpt.summary)
        assertEquals("General report", survey.answer)
    }

    @Test fun relativeAndExclusiveDateRangesAreDeterministic() {
        val records = (1..8).map { day -> record("$day", reading(date = "2026-09-0$day")) }
        assertEquals(setOf("7"), retrieve(records, "steps yesterday").map { it.id }.toSet())
        assertEquals(setOf("6", "7", "8"), retrieve(records, "steps past 3 days").map { it.id }.toSet())
        assertEquals((1..6).map(Int::toString).toSet(), retrieve(records, "steps last week").map { it.id }.toSet())
        assertEquals(setOf("7", "8"), retrieve(records, "steps this week").map { it.id }.toSet())
        assertEquals(setOf("8"), retrieve(records, "steps after 2026-09-07").map { it.id }.toSet())
        assertEquals(setOf("7", "8"), retrieve(records, "steps since 2026-09-07").map { it.id }.toSet())
        assertEquals(setOf("1", "2"), retrieve(records, "steps before 2026-09-03").map { it.id }.toSet())
    }

    @Test fun invalidDateAndNoMatchingKeywordReturnNoEvidence() {
        val records = listOf(record("record", question = "Cafe survey", answer = "Found oak trees"))
        assertTrue(retrieve(records, "history 2026-02-31").isEmpty())
        assertTrue(retrieve(records, "Explain satellites").isEmpty())
        assertEquals(listOf("record"), retrieve(records, "oak").map { it.id })
        assertTrue(SignalHistory.retrieve(records, "history", emptySet(), limit = 0).isEmpty())
    }

    @Test fun metricAliasesSelectOnlyRelevantMeasurements() {
        val mixed = record("mixed", reading(), reading("health.sleep", "20000"), reading("health.heart_rate", "75"))
        val enabled = mixed.sourceKeys
        val pulse = retrieve(listOf(mixed), "my pulse", enabled).single()
        assertEquals(listOf("health.heart_rate"), pulse.observations.map { it.key })
        assertEquals("", pulse.answer)
        val steps = retrieve(listOf(mixed), "my steps", enabled).single()
        assertEquals(listOf("health.steps"), steps.observations.map { it.key })
    }

    @Test fun keywordMatchingIncludesReadingValuesAndIgnoresWorkingRecords() {
        val reading = reading("wifi.names", "Cedar library", period = "current")
        val saved = record("ready", reading)
        val working = saved.copy(id = "working", state = "working")
        assertEquals(listOf("ready"), retrieve(listOf(saved, working), "cedar", setOf("wifi.names")).map { it.id })
    }

    @Test fun dailySummaryDeduplicatesSameWatchAndPreservesMissingLatestData() {
        val old = record("old", reading(value = "100"), reading(value = "20", date = "2026-09-06"), createdAt = now - 1000)
        val newer = record("new", reading(value = "150"), reading(value = "", date = "2026-09-06", status = "unavailable"))
        val summary = SignalHistory.summarize(listOf(old, newer), setOf("health.steps"))
        assertContains(summary, "observedDays=1")
        assertContains(summary, "reportedDays=2")
        assertContains(summary, "unknownDays=1")
        assertContains(summary, "total=150.0")
        assertContains(summary, "fullWearCoverage=unknown")
        assertFalse(summary.contains("total=270"))
    }

    @Test fun differentWatchAndPeriodNeverMergeIntoOneDailyTotal() {
        val a = record("a", reading(value = "100"), reading(value = "999", period = "current"))
        val b = record("b", reading(value = "200"), watch = "watch-b")
        val summary = SignalHistory.summarize(listOf(a, b), setOf("health.steps"))
        assertContains(summary, "watch-a/health.steps/steps: observedDays=1")
        assertContains(summary, "watch-b/health.steps/steps: observedDays=1")
        assertContains(summary, "total=100.0")
        assertContains(summary, "total=200.0")
        assertFalse(summary.contains("999"))
    }

    @Test fun HeartRateHasNoMeaninglessTotalOrNonfiniteAverage() {
        val record = record("heart", reading("health.heart_rate", "70"), reading("health.heart_rate", "NaN", "2026-09-06"))
        val summary = SignalHistory.summarize(listOf(record), setOf("health.heart_rate"))
        assertContains(summary, "observedDays=1")
        assertContains(summary, "unknownDays=1")
        assertContains(summary, "unweightedDailyMean=70.0")
        assertFalse(summary.contains("total="))
    }

    @Test fun summaryCannotReusePartiallyDisabledNarrativeRecord() {
        val mixed = record("mixed", reading(), reading("health.heart_rate", "80"))
        assertEquals("", SignalHistory.summarize(listOf(mixed), setOf("health.steps")))
    }

    @Test fun deletionRemovesTransitiveDependentsIncludingCycles() {
        val a = record("a")
        val b = record("b", references = listOf("a", "c"))
        val c = record("c", references = listOf("b"))
        val untouched = record("d")
        assertEquals(setOf("a", "b", "c"), SignalHistory.deletionClosure(listOf(a, b, c, untouched), setOf("a")))
    }
}
