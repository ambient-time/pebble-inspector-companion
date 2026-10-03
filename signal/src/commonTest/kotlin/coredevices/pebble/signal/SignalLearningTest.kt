package coredevices.pebble.signal

import kotlin.test.*

class SignalLearningTest {
    private val day = 86_400_000L
    private val now = 1_789_000_000_000L
    private val key = "device.battery"
    private val settings = SignalSettings(learningEnabled = true, enabled = setOf(key, "presence.places", "presence.bluetooth"),
        placeFences = listOf(SignalPlaceFence("home", "Home", 0.0, 0.0)),
        presenceTargets = listOf(SignalPresenceTarget("tag", "bluetooth", "AA:BB:CC:DD:EE:FF", "My tag")))
    private fun reading(at: Long, number: Double = 80.0) = SignalObservation(key, "phone", number.toString(), "%", at, at, "fresh", number = number)
    private fun record(index: Int, at: Long, observations: List<SignalObservation> = listOf(reading(at))) = SignalRecord("r$index", "thread", at, "sample", provider = "local", model = "", state = "ready", kind = "observation", observations = observations, sourceKeys = observations.map { it.key }.toSet(), sessionId = "s$index")
    private fun baselineRecords() = (0..9).map { n -> record(n, now - (n / 2) * day - (n % 2) * 7_200_000) }

    @Test fun baselineRequiresIndependentDatesAndMeasurements() {
        assertEquals(1, SignalLearning.proposals(baselineRecords(), settings, now).size)
        assertTrue(SignalLearning.proposals(baselineRecords().take(9), settings, now).isEmpty())
        val duplicates = baselineRecords().map { it.copy(observations = listOf(reading(now))) }
        assertTrue(SignalLearning.proposals(duplicates, settings, now).isEmpty())
        val cached = baselineRecords().map { it.copy(observations = it.observations.map { o -> o.copy(status = "cached") }) }
        assertTrue(SignalLearning.proposals(cached, settings, now).isEmpty())
    }
    @Test fun derivedAnswersAndDifferentUnitsCannotBecomeOriginalEvidence() {
        assertTrue(SignalLearning.proposals(baselineRecords().map { it.copy(kind = "analysis") }, settings, now).isEmpty())
        assertTrue(SignalLearning.proposals(baselineRecords().map { it.copy(references = listOf("other")) }, settings, now).isEmpty())
        val mixed = baselineRecords().mapIndexed { i, r -> r.copy(observations = r.observations.map { it.copy(unit = if (i % 2 == 0) "%" else "V") }) }
        assertTrue(SignalLearning.proposals(mixed, settings, now).isEmpty())
    }
    @Test fun placeAssociationNeedsThreeSeparateSessionsAcrossTwoDays() {
        fun presence(index: Int, session: String = "s$index"): SignalRecord {
            val at = now - index * day
            return record(index, at, listOf(
                SignalObservation("presence.places", "phone", "Home", collectedAt = at, measuredAt = at, status = "inside", identity = "fence:home"),
                SignalObservation("presence.bluetooth", "phone", "My tag", collectedAt = at, measuredAt = at, status = "observed", identity = "target:tag"),
            )).copy(sessionId = session)
        }
        val rows = (0..2).map { presence(it) }
        assertEquals(1, SignalLearning.proposals(rows, settings, now).count { it.kind == "association" })
        assertTrue(SignalLearning.proposals((0..9).map { presence(it, "same-session") }, settings, now).none { it.kind == "association" })
        assertTrue(SignalLearning.proposals(rows, settings.copy(presenceTargets = emptyList()), now).isEmpty())
    }
    @Test fun routineRequiresSevenDaySpanAndConsistentSampledDays() {
        val rows = (0..6).map { i -> val at = now - i * day; record(i, at, listOf(SignalObservation("presence.places", "phone", "Home", collectedAt = at, measuredAt = at, status = "inside", identity = "fence:home"))) }
        assertEquals(1, SignalLearning.proposals(rows, settings, now).count { it.kind == "routine" })
        assertTrue(SignalLearning.proposals(rows.take(5), settings, now).none { it.kind == "routine" })
        assertTrue(SignalLearning.proposals(rows.mapIndexed { i, r -> r.copy(observations = r.observations.map { it.copy(status = if (i % 2 == 0) "outside" else "inside") }) }, settings, now).none { it.kind == "routine" })
    }
    @Test fun onlyConfirmedEligibleMemoriesCanBeSuggestedAndFiveIsMaximum() {
        val proposal = SignalLearning.proposals(baselineRecords(), settings, now).single()
        assertTrue(SignalLearning.suggest(listOf(proposal), "battery", settings).isEmpty())
        val confirmed = proposal.copy(id = "m", state = "confirmed", confirmedAt = now)
        assertEquals(1, SignalLearning.suggest(listOf(confirmed), "battery", settings).size)
        assertTrue(SignalLearning.suggest(listOf(confirmed), "battery", settings.copy(enabled = emptySet())).isEmpty())
        assertTrue(SignalLearning.suggest(listOf(confirmed.copy(needsReview = true)), "battery", settings).isEmpty())
        assertEquals(5, SignalLearning.suggest((0..9).map { confirmed.copy(id = "$it") }, "battery", settings).size)
        assertTrue(SignalLearning.suggest(listOf(confirmed), "", settings).isEmpty())
    }
    @Test fun observationNormalizationNeverAcceptsNonFiniteNumbers() {
        val old = record(1, now, listOf(reading(now).copy(number = null, value = "NaN")))
        assertNull(SignalLearning.normalize(old).observations.single().number)
        assertEquals("r1:0", SignalLearning.normalize(old).observations.single().id)
    }
    @Test fun completedHealthIntervalsAreHistoricalEvidenceWithoutInventingCurrentReadings() {
        val imported = reading(now - day).copy(source = "health_connect:origin", collectedAt = now, measuredAt = now - day, status = "recorded", period = "interval:1000", windowStart = now - day - 1000, windowEnd = now - day)
        assertTrue(SignalLearning.fresh(imported))
        assertFalse(SignalLearning.fresh(imported.copy(windowEnd = now + 1)))
        assertFalse(SignalLearning.fresh(imported.copy(windowStart = now)))
    }

    @Test fun scalarBaselinesRejectAngularReadingsAndInvalidMeasurementQuality() {
        val angles = baselineRecords().mapIndexed { i, record -> record.copy(observations = record.observations.map {
            it.copy(number = if (i % 2 == 0) 359.0 else 1.0, identity = "android.sensor.orientation")
        }) }
        assertTrue(SignalLearning.proposals(angles, settings, now).isEmpty())
        for (invalid in listOf<(SignalObservation) -> SignalObservation>(
            { it.copy(number = Double.POSITIVE_INFINITY) }, { it.copy(accuracy = 0) }, { it.copy(sampleCount = 0) },
            { it.copy(identity = "android.sensor.rotation_vector") }, { it.copy(period = "since_reboot") },
        )) assertTrue(SignalLearning.proposals(baselineRecords().map { it.copy(observations = it.observations.map(invalid)) }, settings, now).isEmpty())
    }

    @Test fun durationBootDeviceAndRecordingMethodSeparateBaselinePopulations() {
        fun intervals(change: (Int, SignalObservation) -> SignalObservation) = baselineRecords().mapIndexed { index, record ->
            record.copy(observations = record.observations.map { observation -> change(index,
                observation.copy(period = "endpoint_interval", windowStart = observation.measuredAt!! - 60_000,
                    windowEnd = observation.measuredAt, fields = mapOf("bootScope" to "boot-a"))) })
        }
        assertEquals(1, SignalLearning.proposals(intervals { _, row -> row }, settings, now).size)
        for (dimension in listOf("duration", "bootScope", "deviceId", "recordingMethod")) {
            val rows = intervals { i, row -> if (i % 2 == 0) row else when (dimension) {
                "duration" -> row.copy(windowStart = row.measuredAt!! - 3_600_000)
                else -> row.copy(fields = row.fields + (dimension to "other"))
            } }
            assertTrue(SignalLearning.proposals(rows, settings, now).isEmpty(), dimension)
        }
        assertTrue(SignalLearning.proposals(intervals { _, row -> row.copy(fields = emptyMap()) }, settings, now).isEmpty())
    }

    @Test fun duplicatesConflictsAndOverlapsDoNotInflateBaselineEvidence() {
        val originals = baselineRecords()
        val duplicate = originals.first().copy(id = "copy")
        val proposal = SignalLearning.proposals(originals + duplicate, settings, now).single()
        assertEquals(10, proposal.evidence.size)
        assertContains(proposal.coverage, "1 copies deduplicated")
        val conflicting = duplicate.copy(observations = duplicate.observations.map { it.copy(number = 20.0) })
        assertTrue(SignalLearning.proposals(originals + conflicting, settings, now).isEmpty())
        val overlapping = originals.map { record -> record.copy(observations = record.observations.map {
            it.copy(period = "interval", windowStart = it.measuredAt!! - 8 * 3_600_000, windowEnd = it.measuredAt)
        }) }
        assertTrue(SignalLearning.proposals(overlapping, settings, now).isEmpty())
    }

    @Test fun legacyConfirmedBaselinesRemainIneligibleUntilRecomputedAndReviewed() {
        val current = SignalLearning.proposals(baselineRecords(), settings, now).single()
        assertEquals(SignalLearning.BASELINE_VERSION, current.derivationVersion)
        assertTrue(current.fingerprintAliases.isNotEmpty())
        val legacy = current.copy(state = "confirmed", derivationVersion = 0)
        assertFalse(SignalLearning.eligible(legacy, settings))
        assertFalse(SignalLearning.canConfirm(legacy))
        assertFalse(SignalLearning.canConfirm(legacy.copy(proposedDerivationVersion = SignalLearning.BASELINE_VERSION)))
        val recomputed = legacy.copy(needsReview = true, proposedDerivationVersion = SignalLearning.BASELINE_VERSION,
            proposedText = current.text, proposedEvidence = current.evidence)
        assertTrue(SignalLearning.canConfirm(recomputed))
        assertFalse(SignalLearning.eligible(recomputed, settings))
        assertTrue(SignalLearning.eligible(current.copy(state = "confirmed"), settings))
        assertTrue(SignalLearning.eligible(legacy.copy(kind = "note", state = "note"), settings))
    }
}
