package com.lukesteuber.signalstation

import coredevices.pebble.signal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private class FakeGarmin(connected: Boolean = true) : GarminConnection {
    val mutable = MutableStateFlow(listOf(GarminDevice(7, "Venu 2S", connected)))
    override val devices: StateFlow<List<GarminDevice>> = mutable.asStateFlow()
    override val status = MutableStateFlow("Garmin Connect ready.").asStateFlow()
    val sent = mutableListOf<Pair<Long, Map<String, Any>>>()
    var openResult = GarminOpenResult.PROMPT_SHOWN
    override fun start(onMessage: (Long, Any?) -> Unit) {}
    override suspend fun send(deviceId: Long, message: Map<String, Any>): Boolean { sent += deviceId to message; return true }
    override suspend fun open(deviceId: Long) = openResult
}

private fun hello(session: Int) = mapOf("v" to 1, "s" to session, "t" to "hello")
private fun bye(session: Int) = mapOf("v" to 1, "s" to session, "t" to "bye")
private fun req(session: Int, id: Int, route: String, body: Map<String, Any>) =
    mapOf("v" to 1, "s" to session, "t" to "req", "id" to id, "route" to route, "body" to body)

class GarminAppLinkTest {
    private data class Call(val url: String, val method: String, val body: String?, val session: SignalWatchSession)

    private fun link(garmin: FakeGarmin, reply: String = """{"mode":"list","page":0,"pages":1,"items":[{"id":"f1","label":"Porch light"}],"note":null}"""): Pair<GarminAppLink, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val link = GarminAppLink(garmin)
        link.request = { url, method, body, session -> calls += Call(url, method, body, session); SignalWatchResponse(reply, 200) }
        return link to calls
    }

    @Test fun helloOpensASessionTheStationCanMatch() = runTest {
        val garmin = FakeGarmin()
        val (link, _) = link(garmin)
        var changes = 0
        link.onChanged = { changes++ }
        link.handle(7, hello(41))
        val watch = link.watches.value.single()
        assertEquals("garmin:7", watch.id)
        assertTrue(watch.appOpen)
        assertTrue(watch.connectionId.startsWith("garmin:1:"))
        assertTrue(changes > 0)
    }

    @Test fun requestReachesTheHandlerAndTheReplyNamesSessionAndId() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin)
        link.handle(7, hello(41))
        link.handle(7, req(41, 9, "home", mapOf("kind" to "home-list", "page" to 0, "request_id" to 9)))
        val call = calls.single()
        assertEquals(AndroidSignalStation.PREFIX + "home", call.url)
        assertEquals("POST", call.method)
        assertEquals(9, Json.parseToJsonElement(call.body!!).jsonObject["request_id"]!!.jsonPrimitive.int)
        assertTrue(link.isTrusted(call.session))
        assertEquals(link.watches.value.single().connectionId, call.session.connectionId)
        val (device, message) = garmin.sent.single()
        assertEquals(7L, device)
        assertEquals(41, message["s"]); assertEquals(9, message["id"]); assertEquals(200, message["status"]); assertEquals("res", message["t"])
        val data = message["data"] as Map<*, *>
        assertEquals("list", data["mode"]); assertEquals(1, data["pages"]); assertFalse("note" in data)
        assertEquals("Porch light", ((data["items"] as List<*>).single() as Map<*, *>)["label"])
    }

    @Test fun historyIsAGetWithItsRequestIdInTheUrl() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin, """{"text":"Saved"}""")
        link.handle(7, hello(41))
        link.handle(7, req(41, 3, "history", mapOf("request_id" to 3)))
        assertEquals(AndroidSignalStation.PREFIX + "history?request_id=3", calls.single().url)
        assertEquals("GET", calls.single().method)
    }

    @Test fun aNewAppRunReplacesTheOldSession() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin)
        link.handle(7, req(41, 1, "capabilities", mapOf("request_id" to 1)))
        assertEquals(AndroidSignalStation.PREFIX + "capabilities", calls.single().url)
        val first = calls.single().session
        link.handle(7, hello(42))
        assertFalse(link.isTrusted(first))
        link.handle(7, req(42, 2, "capabilities", mapOf("request_id" to 2)))
        assertTrue(link.isTrusted(calls.last().session))
        assertNotEquals(first.connectionId, calls.last().session.connectionId)
    }

    @Test fun byeAndDisconnectEndTheSession() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin)
        link.handle(7, req(41, 1, "capabilities", emptyMap()))
        val session = calls.single().session
        link.handle(7, bye(40))
        assertTrue(link.isTrusted(session), "a bye for another run is ignored")
        link.handle(7, bye(41))
        assertFalse(link.isTrusted(session))
        assertFalse(link.watches.value.single().appOpen)

        link.handle(7, hello(43))
        garmin.mutable.value = listOf(GarminDevice(7, "Venu 2S", false))
        link.handle(7, req(43, 2, "capabilities", emptyMap()))
        assertEquals(1, calls.size, "a disconnected watch is not answered")
    }

    @Test fun malformedMessagesAreIgnored() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin)
        link.handle(7, mapOf("v" to 2, "s" to 41, "t" to "hello"))
        link.handle(7, req(41, 1, "start", mapOf("kind" to "ask")))
        link.handle(7, req(41, 1, "home", mapOf("nested" to mapOf("a" to 1))))
        link.handle(7, req(41, 0, "home", emptyMap()))
        link.handle(7, req(0, 1, "home", emptyMap()))
        link.handle(7, req(41, 1, "home", mapOf("kind" to "x".repeat(2000))))
        link.handle(7, "not a dictionary")
        assertTrue(calls.isEmpty())
        assertTrue(garmin.sent.isEmpty())
        assertFalse(link.watches.value.any { it.appOpen })
    }

    @Test fun handlerFailureIsReportedAsAServerError() = runTest {
        val garmin = FakeGarmin()
        val link = GarminAppLink(garmin)
        link.request = { _, _, _, _ -> error("boom") }
        link.handle(7, hello(41))
        link.handle(7, req(41, 5, "home", emptyMap()))
        assertEquals(500, garmin.sent.single().second["status"])
    }

    @Test fun aLateMessageFromAnEarlierRunCannotTakeOverTheLink() = runTest {
        val garmin = FakeGarmin()
        val (link, calls) = link(garmin)
        link.handle(7, hello(42))
        val current = link.watches.value.single().connectionId
        link.handle(7, req(41, 8, "home", mapOf("kind" to "home-confirm", "intent_id" to "old")))
        assertTrue(calls.isEmpty())
        assertEquals(403, garmin.sent.single().second["status"])
        assertEquals(41, garmin.sent.single().second["s"])
        assertEquals(current, link.watches.value.single().connectionId)
    }

    @Test fun launchAsksTheWatchToOpenTheApp() = runTest {
        val garmin = FakeGarmin()
        val (link, _) = link(garmin)
        garmin.openResult = GarminOpenResult.NOT_INSTALLED
        assertFailsWith<SignalProviderException> { link.launch("garmin:7") }
        garmin.openResult = GarminOpenResult.PROMPT_SHOWN
        link.launch("garmin:7")
        assertFailsWith<SignalProviderException> { link.launch("garmin:8") }
    }

    @Test fun refreshAsksTheOpenAppToRecheck() = runTest {
        val garmin = FakeGarmin()
        val (link, _) = link(garmin)
        link.handle(7, hello(41))
        link.refresh("garmin:7")
        assertEquals(mapOf("v" to 1, "s" to 41, "t" to "reset"), garmin.sent.single().second)
    }
}

class CombinedWatchLinkTest {
    private class Plain(id: String) : SignalWatchLink {
        override val watches = MutableStateFlow(listOf(SignalWatch(id, id, true)))
        override val capabilities = SignalWatchCapabilities(messages = true, customTranscription = true)
        val launched = mutableListOf<String>()
        override fun initialize(scope: CoroutineScope) {}
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun launch(watchId: String) { launched += watchId }
        override suspend fun install(watchId: String) {}
    }

    @Test fun listsEveryWatchAndRoutesToItsOwner() = runTest {
        val pebble = Plain("pebble-1")
        val garmin = FakeGarmin()
        val garminLink = GarminAppLink(garmin).apply { request = { _, _, _, _ -> SignalWatchResponse("{}", 200) } }
        val combined = CombinedWatchLink(pebble, garminLink)
        garminLink.onChanged = combined::sync
        garminLink.handle(7, hello(41))
        assertEquals(listOf("pebble-1", "garmin:7"), combined.watches.value.map { it.id })
        assertTrue(combined.watches.value.last().appOpen)
        assertTrue(combined.capabilities.customTranscription)

        combined.launch("pebble-1")
        assertEquals(listOf("pebble-1"), pebble.launched)
        combined.launch("garmin:7")
        assertEquals(listOf("pebble-1"), pebble.launched)
        assertTrue(garmin.sent.isNotEmpty(), "an open Garmin session is refreshed rather than relaunched")
        assertFailsWith<SignalProviderException> { combined.launch("missing") }
    }
}
