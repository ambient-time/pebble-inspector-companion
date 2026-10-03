package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.test.*

/** Actual Android encrypted Room documents; no controller, provider or device. */
class SignalHomeStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val json=Json { encodeDefaults=true }
    private fun entry(n:Int,status:HomeActionStatus=HomeActionStatus.OBSERVED)=HomeLedgerEntry(
        HomeAction("intent-$n","c","lamp","on",mapOf("note" to "Synthetic fixture $n ".repeat(20)),0),status,0,120_000)
    private suspend fun fixture(block:suspend(SignalStore,SignalHomeStore)->Unit) {
        val name="home-storage-${UUID.randomUUID()}";val store=SignalStore(context,name)
        try { block(store,SignalHomeStore(store,{1000})) }
        finally { store.close();context.deleteDatabase("$name-history.db");context.getSharedPreferences("${name}_private",0).edit().clear().commit() }
    }

    @Test fun moreThanTenThousandEncryptedActionsRemainBoundedExportableAndNonExecutableWhenArchived()=runBlocking {
        fixture { store,persistence ->
            var number=0
            while(number<10_001) {
                val count=minOf(100,10_001-number)
                val next=(1..count).map { entry(++number) }
                persistence.save(persistence.load().copy(ledger=persistence.load().ledger+next))
            }
            val state=persistence.load()
            assertEquals(128,state.ledger.size)
            assertEquals(9_873L,state.archivedIntents)
            assertEquals(9_873L,store.documentCount(SignalHomeStore.ARCHIVE_KIND))
            assertTrue(assertNotNull(store.document("home:v1")).encodeToByteArray().size<=SignalHomeRetention.MAX_STATE_BYTES)
            assertTrue(store.maximumDocumentLength("home")<1024*1024)
            assertTrue(store.maximumDocumentLength(SignalHomeStore.ARCHIVE_KIND)<64*1024)
            assertEquals(entry(1),persistence.archived("intent-1"))
            val seen=mutableSetOf<String>()
            store.walkDocuments(SignalHomeStore.ARCHIVE_KIND) { _,value -> assertTrue(seen.add(json.decodeFromString<HomeLedgerEntry>(value).action.id)) }
            assertEquals(9_873,seen.size)
            val output=File(context.cacheDir,"home-scale-${UUID.randomUUID()}.json")
            try {
                val count=output.bufferedWriter().use { persistence.export(it) }
                assertEquals(10_001L,count)
                // Check endpoints and count without constructing one huge JSON tree.
                val text=output.readText()
                assertTrue(text.contains("\"actionCount\":10001"))
                assertTrue(text.contains("\"id\":\"intent-1\""));assertTrue(text.contains("\"id\":\"intent-10001\""))
            } finally { output.delete() }
            val engine=SignalHomeEngine(persistence,{error("No controller may be contacted")},{1000},{"unused"})
            assertFailsWith<HomeException> { engine.confirm("intent-1") }
            assertFailsWith<HomeException> { engine.dispatch("intent-1") }
            assertEquals(HomeActionStatus.OBSERVED,engine.reconcile("intent-1").status)

            // The active projection and audit rows roll back as one transaction.
            val before=assertNotNull(store.document("home:v1"));val archiveCount=store.documentCount(SignalHomeStore.ARCHIVE_KIND)
            assertFailsWith<IllegalStateException> { store.atomic {
                persistence.save(persistence.load().copy(ledger=persistence.load().ledger+(10002..10150).map(::entry)))
                error("Synthetic interruption before transaction commit")
            } }
            assertEquals(before,store.document("home:v1"));assertEquals(archiveCount,store.documentCount(SignalHomeStore.ARCHIVE_KIND))
        }
    }

    @Test fun oversizedLegacyHomeAndFullReplayJournalMigrateWithoutCursorSizedReadsOrLostEvidence()=runBlocking {
        fixture { store,persistence ->
            val old=HomeState(ledger=(1..3000).map { entry(it,when(it){1->HomeActionStatus.SENDING;2->HomeActionStatus.READY;else->HomeActionStatus.OBSERVED}) })
            val encoded=json.encodeToString(old)
            store.document("home:v1","home",encoded)
            assertTrue(store.maximumDocumentLength("home")>2*1024*1024)
            assertEquals(encoded,store.documentLarge("home:v1"))
            val legacy=buildJsonObject { put("version",1);putJsonObject("entries") { repeat(4096) { n ->
                put(n.toString(16).padStart(64,'0'),buildJsonObject { put("hash","a".repeat(64));put("state","done");putJsonObject("response") { put("mode","result");put("text","Synthetic retained result ".repeat(50)) } })
            } } }
            store.document("signal-home-watch-replay-v1","home_replay",legacy.toString())
            assertTrue(store.maximumDocumentLength("home_replay")>2*1024*1024)
            val engine=SignalHomeEngine(persistence,{error("Migration is read-only externally")},{1000},{"unused"})
            engine.recoverInterrupted()
            assertEquals(HomeActionStatus.UNKNOWN,persistence.archived("intent-1")?.status)
            assertEquals(HomeActionStatus.EXPIRED,persistence.archived("intent-2")?.status)
            val archived=store.documentCount(SignalHomeStore.ARCHIVE_KIND)
            engine.recoverInterrupted() // Interrupted between native migration and journal retirement.
            assertEquals(archived,store.documentCount(SignalHomeStore.ARCHIVE_KIND))
            val replay=SignalHomeReplay({store.documentLarge(it)},{key,value->store.document(key,"home_replay",value)})
            replay.retireLegacyJournal()
            val retired=Json.parseToJsonElement(assertNotNull(store.documentLarge("signal-home-watch-replay-v1"))).jsonObject
            assertEquals(2,retired["version"]?.jsonPrimitive?.int)
            assertEquals(legacy,retired["legacy"])
            assertEquals(3000,persistence.load().ledger.size+store.documentCount(SignalHomeStore.ARCHIVE_KIND).toInt())
            assertTrue(store.maximumDocumentLength("home")<1024*1024)
            assertFailsWith<HomeException> { engine.confirm("intent-2") }
        }
    }

    @Test fun referenceCountedPinsAndTemporaryLiveCapacityRecoverWithoutDeletingEvidence()=runBlocking {
        fixture { store,persistence ->
            persistence.save(HomeState(ledger=listOf(entry(1))))
            persistence.pin("intent-1");persistence.pin("intent-1");persistence.unpin("intent-1")
            persistence.save(persistence.load().copy(ledger=persistence.load().ledger+(2..300).map(::entry)))
            assertTrue(persistence.load().ledger.any { it.action.id=="intent-1" })
            persistence.unpin("intent-1")
            persistence.save(persistence.load())
            assertNotNull(persistence.archived("intent-1"))
            val before=assertNotNull(store.document("home:v1"))
            assertFailsWith<HomeException> { persistence.save(persistence.load().copy(ledger=persistence.load().ledger+(400..464).map { entry(it,HomeActionStatus.AWAITING_CONFIRMATION) })) }
            assertEquals(before,store.document("home:v1"))
            persistence.save(persistence.load().copy(ledger=persistence.load().ledger+(400..463).map { entry(it,HomeActionStatus.AWAITING_CONFIRMATION) }))
            val expired=SignalHomeStore(store,{120_000})
            expired.save(expired.load())
            assertTrue(expired.load().ledger.none { it.status==HomeActionStatus.AWAITING_CONFIRMATION })
            expired.save(expired.load().copy(ledger=expired.load().ledger+entry(465,HomeActionStatus.AWAITING_CONFIRMATION).copy(confirmationExpiresAt=240_000)))
            assertTrue(expired.load().ledger.any { it.action.id=="intent-465" })
        }
    }
}
