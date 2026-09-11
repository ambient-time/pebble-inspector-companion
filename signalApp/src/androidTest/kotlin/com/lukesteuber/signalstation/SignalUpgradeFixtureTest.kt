package com.lukesteuber.signalstation

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Explicit emulator-only, two-install upgrade fixture. It never uninstalls or clears storage. */
class SignalUpgradeFixtureTest {
    @Test fun preserveDistributedPreviewData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("upgrade_mode")
        assumeTrue(mode == "seed" || mode == "verify")
        assumeTrue(Build.MODEL.contains("sdk") || Build.HARDWARE.contains("ranchu"))
        val context = instrumentation.targetContext
        val version = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val store = SignalStore(context)
        try {
            if (mode == "seed") {
                assertTrue(version == 6L || version == 8L || version == 9L, "Seed a published build 6, 8 or 9 before upgrading.")
                // Decode the original wire shapes so this fixture also runs against published builds.
                store.settings(Json.decodeFromString<SignalSettings>("""{"onboardingComplete":true,"learningEnabled":true,"provider":"xai","model":"upgrade-fixture-model","recognition":"stock","enabled":["device.battery"]}"""))
                store.put("xai", "synthetic-upgrade-credential-never-sent")
                store.save(Json.decodeFromString<SignalRecord>("""{"id":"upgrade-fixture-record","threadId":"upgrade-fixture-thread","createdAt":1789080000000,"question":"Upgrade fixture","answer":"Preserved original","provider":"local","model":"","state":"ready","kind":"capture","sourceKeys":["device.battery"],"observations":[{"id":"upgrade-fixture-reading","key":"device.battery","source":"phone","value":"73","unit":"%","collectedAt":1789080000000,"measuredAt":1789080000000,"status":"fresh"}]}"""))
                store.saveQuestion(Json.decodeFromString<SavedQuestion>("""{"id":"upgrade-fixture-question","title":"Fixture question","question":"What changed?","sourceKeys":["device.battery"],"attachmentIds":["upgrade-fixture-record"]}"""))
                store.saveMemory(Json.decodeFromString<SignalMemory>("""{"id":"upgrade-fixture-memory","fingerprint":"upgrade-fixture-pattern","kind":"baseline","text":"My corrected fixture wording","state":"confirmed","createdAt":1789080000000,"evaluatedAt":1789080000000,"sourceKeys":["device.battery"],"revision":4,"evidence":[{"recordId":"upgrade-fixture-record","observationIds":["upgrade-fixture-reading"],"collectedAt":1789080000000,"description":"Fixture evidence"}]}"""))
            } else {
                assertTrue(version >= 10)
                assertEquals("xai", store.settings().provider)
                assertEquals("upgrade-fixture-model", store.settings().model)
                assertEquals(setOf("device.battery"), store.settings().enabled)
                assertFalse(store.settings().lookups.nearbyPlaces)
                assertFalse(store.settings().lookups.radioLocation)
                assertEquals("synthetic-upgrade-credential-never-sent", store.get("xai"))
                val record = store.record("upgrade-fixture-record")!!
                assertEquals("Preserved original", record.answer)
                assertEquals("upgrade-fixture-reading", record.observations.single().id)
                assertEquals("73", record.observations.single().value)
                assertEquals("", record.observations.single().metric)
                assertEquals("Fixture question", store.savedQuestions().single { it.id == "upgrade-fixture-question" }.title)
                val memory = store.memory().single { it.id == "upgrade-fixture-memory" }
                assertEquals("My corrected fixture wording", memory.text); assertEquals(4L, memory.revision)
                assertTrue("m:upgrade-fixture-memory" in store.deletionClosure(setOf(record.id)))
            }
        } finally { store.close() }
    }
}
