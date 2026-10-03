package coredevices.pebble.signal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import kotlin.test.*

class SignalHomeReplayTest {
    private class Store {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var rejectWrite: Int? = null
        fun helper(capacity: Int = 4096) = SignalHomeReplay(
            get = { values[it] },
            put = { key, value -> writes++; if (writes == rejectWrite) error("Storage unavailable"); values[key] = value },
            capacity = capacity,
        )
    }
    private fun request(id: Int = 1, kind: String = "home-review") = buildJsonObject {
        put("request_id", id); put("kind", kind); put("favorite_id", "favorite"); put("action_id", "on")
    }
    private val success = buildJsonObject { put("mode", "result"); put("favorite_id", "favorite"); put("text", "Accepted.") }
    private fun outcome(value: JsonObject) = value["outcome"]?.jsonPrimitive?.content

    @Test fun reservationPrecedesBlockAndRestartReturnsExactDurableResult() = runTest {
        val store = Store(); var sends = 0
        val first = store.helper().run("stable-watch", 1, "home-review", request()) {
            assertEquals(1, store.writes); assertTrue(store.values.values.single().contains("reserved")); sends++; success
        }
        assertEquals(success, first)
        assertEquals(success, store.helper().run("stable-watch", 1, "home-review", request()) { sends++; error("Replay dispatched") })
        assertEquals(1, sends); assertEquals(2, store.writes)
        assertFalse(store.values.toString().contains("stable-watch"))
    }
    @Test fun structuralObjectOrderingDoesNotChangeFingerprintButArrayOrderDoes() = runTest {
        val store=Store(); val helper=store.helper(); var sends=0
        val first=Json.parseToJsonElement("""{"favorite_id":"favorite","nested":{"b":2,"a":1},"list":[1,2]}""").jsonObject
        val reordered=Json.parseToJsonElement("""{"list":[1,2],"nested":{"a":1,"b":2},"favorite_id":"favorite"}""").jsonObject
        helper.run("watch",1,"home-review",first){sends++;success}
        assertEquals(success,helper.run("watch",1,"home-review",reordered){error("must replay")})
        val changed=JsonObject(reordered+ ("list" to buildJsonArray { add(2);add(1) }))
        assertEquals("refused",outcome(helper.run("watch",1,"home-review",changed){error("must reject")}));assertEquals(1,sends)
    }
    @Test fun ownerAndKindSeparateRecordsWhileChangedArgumentsFailClosed() = runTest {
        val store=Store(); val helper=store.helper(); var sends=0
        helper.run("watch-one",1,"home-review",request()){sends++;success}
        assertEquals("refused",outcome(helper.run("watch-one",1,"home-review",JsonObject(request()+ ("action_id" to JsonPrimitive("off")))){error("different action")}))
        helper.run("watch-two",1,"home-review",request()){sends++;success}
        helper.run("watch-one",1,"home-cancel",request(kind="home-cancel")){sends++;success}
        assertEquals(3,sends)
    }
    @Test fun interruptedReservationSurvivesRestartAndNeverRetriesMutation() = runTest {
        val store=Store(); var sends=0
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){sends++;error("lost response")}))
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){sends++;success}))
        assertEquals(1,sends);assertEquals(1,store.writes)
    }
    @Test fun failedReservationCannotDispatchAndFailedCompletionCannotResend() = runTest {
        val store=Store();store.rejectWrite=1;var sends=0
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){sends++;success}));assertEquals(0,sends)
        val second=Store();second.rejectWrite=2
        assertEquals("unknown",outcome(second.helper().run("watch",1,"home-review",request()){sends++;success}))
        second.rejectWrite=null
        assertEquals("unknown",outcome(second.helper().run("watch",1,"home-review",request()){sends++;success}));assertEquals(1,sends)
    }
    @Test fun concurrentDuplicateWaitsForSameResultAndCancelIsNotBlockedByNetwork() = runTest {
        val store=Store(); val helper=store.helper(); val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var sends=0
        val first=async { helper.run("watch",1,"home-review",request()){sends++;entered.complete(Unit);release.await();success} }
        entered.await()
        val second=async { helper.run("watch",1,"home-review",request()){error("duplicate")} }
        yield();assertFalse(second.isCompleted)
        assertEquals(success,helper.run("watch",1,"home-cancel",request(kind="home-cancel")){success})
        release.complete(Unit);assertEquals(success,first.await());assertEquals(success,second.await());assertEquals(1,sends)
    }
    @Test fun callerCancellationLeavesDurableUnknownAndReleasesLiveWaiters() = runTest {
        val store=Store(); val helper=store.helper(); val entered=CompletableDeferred<Unit>();var sends=0
        val first=async { helper.run("watch",1,"home-review",request()){sends++;entered.complete(Unit);CompletableDeferred<Unit>().await();success} }
        entered.await();val replay=async { helper.run("watch",1,"home-review",request()){error("duplicate")} };yield()
        first.cancelAndJoin();assertEquals("unknown",outcome(replay.await()))
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){error("restart duplicate")}));assertEquals(1,sends)
    }
    @Test fun capacityNeverEvictsRecordsAndCorruptionNeverResetsJournal() = runTest {
        val store=Store(); val helper=store.helper(1)
        helper.run("watch",1,"home-review",request()){success}
        assertEquals("refused",outcome(helper.run("watch",2,"home-review",request(2)){error("capacity")}))
        assertEquals(success,store.helper(1).run("watch",1,"home-review",request()){error("evicted")})
        store.values[store.values.keys.single()]="{broken"
        assertEquals("unknown",outcome(store.helper().run("watch",2,"home-review",request(2)){error("corrupt reset")}))
    }
    @Test fun invalidIdentityOrConflictingEnvelopeNeverReachesBlock() = runTest {
        val store=Store();val helper=store.helper()
        assertEquals("refused",outcome(helper.run("",1,"home-review",request()){error("identity")}))
        assertEquals("refused",outcome(helper.run("watch",0,"home-review",request()){error("id")}))
        assertEquals("refused",outcome(helper.run("watch",2,"home-review",request()){error("envelope")}))
        assertEquals("refused",outcome(helper.run("watch",1,"home-confirm",request()){error("kind")}))
        assertEquals(0,store.writes)
    }
    @Test fun oversizedResultRetainsReservationWithoutReexecution() = runTest {
        val store=Store();var sends=0
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){sends++;buildJsonObject{put("text","x".repeat(4097))}}))
        assertEquals("unknown",outcome(store.helper().run("watch",1,"home-review",request()){sends++;success}));assertEquals(1,sends)
    }
    @Test fun sha256KnownVectorsIncludeMultiBlockInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",signalHomeReplayDigest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",signalHomeReplayDigest("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",signalHomeReplayDigest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
    }

    @Test fun retirementIsAtomicPreservesLegacyEvidenceAndOldParserFailsClosed() = runTest {
        val store=Store();val helper=store.helper(1)
        helper.run("watch",1,"home-review",request()){success}
        val before=store.values.values.single()
        helper.retireLegacyJournal()
        val retired=Json.parseToJsonElement(store.values.values.single()).jsonObject
        assertEquals(2,retired["version"]?.jsonPrimitive?.int)
        assertEquals(Json.parseToJsonElement(before),retired["legacy"])
        assertEquals("unknown",outcome(store.helper().run("watch",2,"home-confirm",request(2,"home-confirm")){error("old parser must reject retirement marker")}))
        val writes=store.writes;store.helper().retireLegacyJournal();assertEquals(writes,store.writes)
    }

    @Test fun interruptedOrCorruptRetirementCannotEraseOrResetLegacyJournal() = runTest {
        val store=Store();store.helper().run("watch",1,"home-review",request()){success}
        val before=store.values.toMap();store.rejectWrite=store.writes+1
        assertFailsWith<IllegalStateException> { store.helper().retireLegacyJournal() }
        assertEquals(before,store.values)
        store.rejectWrite=null;store.helper().retireLegacyJournal()
        val key=store.values.keys.single();store.values[key]="{broken"
        assertFailsWith<IllegalArgumentException> { store.helper().retireLegacyJournal() }
        assertEquals("{broken",store.values[key])
    }

    @Test fun completelyFullLegacyJournalRetiresWithoutDiscardingReservations() = runTest {
        val store=Store()
        val old=buildJsonObject { put("version",1);putJsonObject("entries") {
            repeat(4096) { n -> put(n.toString(16).padStart(64,'0'),buildJsonObject { put("hash","f".repeat(64));put("state","reserved") }) }
        } }
        store.values["signal-home-watch-replay-v1"]=old.toString()
        store.helper().retireLegacyJournal()
        val retired=Json.parseToJsonElement(store.values.values.single()).jsonObject
        assertEquals(old,retired["legacy"])
        assertEquals(1,store.writes)
    }

    @Test fun tenThousandReadsReviewsAndConfirmRepliesNeverFillSessionCache() = runTest {
        var now=1000L;val cache=SignalHomeRequestCache({now});var calls=0
        repeat(10_001) { n ->
            for(kind in listOf("home-list","home-review","home-confirm")) {
                val value=cache.run("session",n+1,kind,request(n+1,kind)){active->assertTrue(active());calls++;success}
                assertEquals(success,value)
            }
            now+=1
        }
        assertEquals(30_003,calls)
    }

    @Test fun inFlightEntriesStayPinnedAndCancellationCanPassBlockedWork() = runTest {
        val cache=SignalHomeRequestCache({1000},capacity=1)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=async { cache.run("session",1,"home-review",request()){active->entered.complete(Unit);release.await();assertFalse(active());success} }
        entered.await()
        val duplicate=async { cache.run("session",1,"home-review",request()){error("duplicate")} }
        yield();assertFalse(duplicate.isCompleted)
        assertFailsWith<IllegalArgumentException> { cache.run("session",2,"home-list",request(2,"home-list")){error("must not evict in flight")} }
        cache.cancel("session",1);release.complete(Unit)
        assertEquals(success,first.await());assertEquals(success,duplicate.await())
        assertEquals(success,cache.run("session",2,"home-list",request(2,"home-list")){success})
    }

    @Test fun cacheExpiryAndNewSessionMayRepeatReadButCannotAuthorizeMutation() = runTest {
        var now=1000L;val cache=SignalHomeRequestCache({now});var reads=0
        cache.run("old",1,"home-review",request()){reads++;success}
        cache.run("old",1,"home-review",request()){error("cached")}
        now+=120_000
        cache.run("old",1,"home-review",request()){reads++;success}
        cache.run("new",1,"home-review",request()){reads++;success}
        assertEquals(3,reads)
        cache.cancel("new",2)
        assertFailsWith<IllegalArgumentException> { cache.run("new",2,"home-review",request(2)){error("cancelled")} }
        now+=120_000
        cache.run("new",2,"home-review",request(2)){reads++;success}
        assertEquals(4,reads)
    }
}
