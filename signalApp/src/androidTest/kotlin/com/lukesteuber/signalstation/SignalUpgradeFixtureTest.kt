package com.lukesteuber.signalstation

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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
        withTimeout(15_000) { (context.applicationContext as SignalApplication).station.state.first { it.initialized && it.homeReady } }
        val version = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val store = SignalStore(context)
        try {
            if (mode == "seed") {
                assertTrue(version in setOf(6L, 8L, 9L, 10L, 17L), "Seed a published build 6, 8, 9, 10 or 17 before upgrading.")
                // Decode the original wire shapes so this fixture also runs against published builds.
                store.settings(Json.decodeFromString<SignalSettings>("""{"onboardingComplete":true,"learningEnabled":true,"provider":"xai","model":"upgrade-fixture-model","recognition":"stock","enabled":["device.battery"]}"""))
                store.put("xai", "synthetic-upgrade-credential-never-sent")
                store.save(Json.decodeFromString<SignalRecord>("""{"id":"upgrade-fixture-record","threadId":"upgrade-fixture-thread","createdAt":1789080000000,"question":"Upgrade fixture","answer":"Preserved original","provider":"local","model":"","state":"ready","kind":"capture","sourceKeys":["device.battery"],"observations":[{"id":"upgrade-fixture-reading","key":"device.battery","source":"phone","value":"73","unit":"%","collectedAt":1789080000000,"measuredAt":1789080000000,"status":"fresh"}]}"""))
                store.saveQuestion(Json.decodeFromString<SavedQuestion>("""{"id":"upgrade-fixture-question","title":"Fixture question","question":"What changed?","sourceKeys":["device.battery"],"attachmentIds":["upgrade-fixture-record"]}"""))
                store.saveMemory(Json.decodeFromString<SignalMemory>("""{"id":"upgrade-fixture-memory","fingerprint":"upgrade-fixture-pattern","kind":"baseline","text":"My corrected fixture wording","state":"confirmed","createdAt":1789080000000,"evaluatedAt":1789080000000,"sourceKeys":["device.battery"],"revision":4,"evidence":[{"recordId":"upgrade-fixture-record","observationIds":["upgrade-fixture-reading"],"collectedAt":1789080000000,"description":"Fixture evidence"}]}"""))
                if (version == 17L) store.document("home:v1", "home", """{"connections":[{"id":"upgrade-home","name":"Synthetic disabled Home","kind":"HOME_ASSISTANT","baseUrl":"https://fixture.invalid","enabled":false}],"grants":[{"id":"upgrade-grant","connectionId":"upgrade-home","entityId":"light.fixture","capabilityId":"turn_on","createdAt":1789080000000}],"tiles":[{"id":"upgrade-tile","connectionId":"upgrade-home","entityId":"light.fixture","title":"Synthetic fixture","watchFavorite":true}]}""")
            } else {
                assertTrue(version >= 11)
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
                if (version >= 18) {
                    val home = Json { ignoreUnknownKeys = true }.decodeFromString<HomeState>(assertNotNull(store.document("home:v1")))
                    assertEquals("upgrade-home", home.connections.single().id)
                    assertFalse(home.connections.single().enabled)
                    assertEquals("upgrade-grant", home.grants.single().id)
                    assertTrue(home.tiles.single().watchFavorite)
                    assertTrue(home.ledger.isEmpty(), "Upgrade must not prepare or dispatch any action.")
                }
            }
        } finally { store.close() }
    }
}
