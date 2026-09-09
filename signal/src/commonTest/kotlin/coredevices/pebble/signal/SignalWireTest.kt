package coredevices.pebble.signal

import kotlinx.serialization.json.*
import kotlin.test.*

class SignalWireTest {
    private val received = 1_789_000_000_000L
    @Test fun cNumericObjectAndNullValuesRoundTripWithoutInventingHeartRateAge() {
        val payload = Json.parseToJsonElement("""[
            {"key":"health.steps","source":"watch","value":42,"unit":"steps","collectedAt":1,"period":"today","status":"fresh"},
            {"key":"watch.motion","source":"watch","value":{"samples":100,"rms":0.12},"unit":"g","collectedAt":1,"period":"5_second_sample","status":"fresh"},
            {"key":"health.heart_rate","source":"watch","value":null,"collectedAt":1,"measuredAt":1789000000000,"status":"timestamp_unknown"}
        ]""")
        val rows = assertNotNull(SignalWire.observations(payload, received))
        assertEquals("42", rows[0].value)
        assertEquals("{\"samples\":100,\"rms\":0.12}", rows[1].value)
        assertEquals("", rows[2].value)
        assertNull(rows[2].measuredAt)
        assertTrue(rows.all { it.collectedAt == received })
        val keys = rows.map { it.key }.toSet()
        assertTrue(rows.all { SignalWatchValidation.validate(it, keys, keys, received) != null })
    }
    @Test fun actualCSerializedSnapshotIsAccepted() {
        // Captured from pebble-field-inspector/tests/fixtures/watch-observations.json.
        val payload = Json.parseToJsonElement("""{
  "request_id": 45,
  "observations": [
    {
      "key": "health.steps",
      "source": "watch",
      "value": 4321,
      "unit": "steps",
      "collectedAt": 1788886800000,
      "status": "fresh",
      "period": "today",
      "measuredAt": 1788886800000,
      "windowStart": 1788850800000,
      "windowEnd": 1788886800000,
      "date": "2026-09-08"
    },
    {
      "key": "health.restful_sleep",
      "source": "watch",
      "value": null,
      "unit": "seconds",
      "collectedAt": 1788886800000,
      "status": "permission_denied",
      "period": "day",
      "measuredAt": 1788850800000,
      "windowStart": 1788764400000,
      "windowEnd": 1788850800000,
      "date": "2026-09-07"
    },
    {
      "key": "watch.motion",
      "source": "watch",
      "value": {
        "samples": 50,
        "mean_x": 20,
        "mean_y": -30,
        "mean_z": 998,
        "peak_abs_axis": 1200
      },
      "unit": "mg",
      "collectedAt": 1788886800000,
      "status": "fresh",
      "period": "5_second_sample",
      "measuredAt": 1788886805000,
      "windowStart": 1788886800000,
      "windowEnd": 1788886805000
    },
    {
      "key": "health.heart_rate",
      "source": "watch",
      "value": 72,
      "unit": "bpm",
      "collectedAt": 1788886800000,
      "status": "timestamp_unknown",
      "period": "current"
    }
  ],
  "complete": true
}""").jsonObject
        val at = 1788886805000L
        val rows = assertNotNull(SignalWire.observations(payload.getValue("observations"), at))
        val keys = rows.map { it.key }.toSet()
        assertEquals("4321", rows[0].value)
        assertEquals("", rows[1].value)
        assertEquals("permission_denied", rows[1].status)
        assertTrue(rows[2].value.contains("peak_abs_axis"))
        assertEquals("72", rows[3].value)
        assertNull(rows[3].measuredAt)
        assertNull(rows[3].windowStart)
        assertTrue(rows.all { SignalWatchValidation.validate(it, keys, keys, at) != null })
    }
    @Test fun malformedAndOversizedBatchesAreRejected() {
        assertNull(SignalWire.observations(Json.parseToJsonElement("{}"), received))
        assertNull(SignalWire.observations(Json.parseToJsonElement("[null]"), received))
        assertNull(SignalWire.observations(Json.parseToJsonElement("[{}]"), received))
        assertNull(SignalWire.observations(Json.parseToJsonElement("[" + List(13) { "{}" }.joinToString() + "]"), received))
    }
}
