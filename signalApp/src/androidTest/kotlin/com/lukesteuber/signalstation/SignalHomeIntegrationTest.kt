package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual station, coordinator, connectors and encrypted store; HTTP alone is replaced. */
class SignalHomeIntegrationTest {
    @Test fun connectionSecretsScopesReplacementAndRemovalUseRealStore() = runBlocking {
        fixture { f ->
            val connection = f.connect()
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            assertTrue(f.station.state.value.home.grants.isEmpty())
            assertEquals(f.token, f.store.get(connection.credentialKey))
            val prefs = context.getSharedPreferences("${f.name}_private", 0)
            val encrypted = assertNotNull(prefs.getString("key:${connection.credentialKey}", null))
            assertNotEquals(f.token, encrypted)
            assertFalse(prefs.all.values.any { it.toString().contains(f.token) })
            assertFalse(assertNotNull(f.store.document("home:v1")).contains(f.token))

            f.main { saveHomeTile(HomeTile("lamp", connection.id, "switch.lamp", "Lamp", "switch.turn_on", watchFavorite = true)) }
            until { f.station.state.value.home.tiles.size == 1 }
            assertTrue(f.station.state.value.home.captureTargets.isEmpty())
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            f.main { selectHomeCapture(connection.id, "switch.lamp", true) }
            until { f.station.state.value.home.captureTargets.size == 1 }
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            f.main { setHomeAccess(setOf(connection.id)) }
            assertEquals(setOf(connection.id), f.station.state.value.homeAccess)
            f.main { removeHomeTile("lamp") }
            until { f.station.state.value.home.tiles.isEmpty() }
            assertEquals(1, f.station.state.value.home.captureTargets.size)
            assertEquals(setOf(connection.id), f.station.state.value.homeAccess)
            f.main { selectHomeCapture(connection.id, "switch.lamp", false) }
            until { f.station.state.value.home.captureTargets.isEmpty() }
            assertEquals(setOf(connection.id), f.station.state.value.homeAccess)

            val pending = f.request("switch.turn_on")
            f.main { confirmHomeAction(pending.action.id, true) }
            until { f.posts.size == 1 && f.station.state.value.home.grants.size == 1 && f.entry(pending.action.id).status == HomeActionStatus.OBSERVED }
            val secondPending = f.request("switch.turn_off")
            f.token = "replacement-secret-${UUID.randomUUID()}"
            val replacement = f.connect(connection)
            assertNotEquals(connection.credentialKey, replacement.credentialKey)
            assertNull(f.store.get(connection.credentialKey))
            assertEquals(f.token, f.store.get(replacement.credentialKey))
            assertTrue(f.station.state.value.home.grants.isEmpty())
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            assertEquals(HomeActionStatus.CANCELLED, f.entry(secondPending.action.id).status)
            f.main { confirmHomeAction(secondPending.action.id, false) }
            // A new request cannot inherit the revoked grant.
            val afterReplacement = f.request("switch.turn_on")
            assertEquals(HomeActionStatus.AWAITING_CONFIRMATION, afterReplacement.status)
            assertEquals(1, f.posts.size)
            f.main { removeHomeConnection(replacement.id) }
            until { f.station.state.value.home.connections.isEmpty() }
            until { f.store.get(replacement.credentialKey) == null }
            assertTrue(f.station.state.value.home.grants.isEmpty())
            assertTrue(f.station.state.value.home.snapshot.entities.isEmpty())
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            assertEquals(HomeActionStatus.CANCELLED, f.entry(afterReplacement.action.id).status)
            assertEquals(1, f.posts.size)
        }
    }

    @Test fun typedProviderToolsRequireReviewCancelAndHonorOnlyExactGrant() = runBlocking {
        fixture { f ->
            val connection = f.connect()
            f.main { setHomeAccess(setOf(connection.id)) }
            f.replies += tool("query", "home_state", connection.id)
            f.replies += answer
            f.ask("Read the lamp state")
            assertTrue(f.providerRequests.any { it.contains("home_state") && it.contains("function_call_output") && it.contains("switch.lamp") })
            assertTrue(f.posts.isEmpty())
            assertTrue(f.station.state.value.home.ledger.isEmpty())

            suspend fun agentAction(action: String): HomeLedgerEntry {
                val before = f.station.state.value.home.ledger.map { it.action.id }.toSet()
                f.replies += tool("action-${UUID.randomUUID()}", "home_request_action", connection.id, action)
                f.replies += answer
                f.ask("Request $action for the lamp")
                return f.station.state.value.home.ledger.single { it.action.id !in before }
            }
            val cancelled = agentAction("switch.turn_on")
            assertEquals(HomeActionStatus.AWAITING_CONFIRMATION, cancelled.status)
            assertTrue(f.posts.isEmpty())
            f.main { cancelHomeAction(cancelled.action.id) }
            until { f.entry(cancelled.action.id).status == HomeActionStatus.CANCELLED }
            assertTrue(f.posts.isEmpty())
            val approved = agentAction("switch.turn_on")
            assertEquals(HomeActionStatus.AWAITING_CONFIRMATION, approved.status)
            f.main { confirmHomeAction(approved.action.id, true) }
            until { f.posts.size == 1 && f.station.state.value.home.grants.size == 1 }
            val granted = agentAction("switch.turn_on")
            until { f.posts.size == 2 }
            assertTrue(granted.status in setOf(HomeActionStatus.ACCEPTED, HomeActionStatus.OBSERVED))
            val different = agentAction("switch.turn_off")
            assertEquals(HomeActionStatus.AWAITING_CONFIRMATION, different.status)
            assertEquals(2, f.posts.size)
            assertTrue(f.posts.all { it == "/api/services/switch/turn_on" })
            assertTrue(f.providerRequests.none { it.contains(f.token) })
            assertTrue(f.replies.isEmpty(), "All scripted provider turns must be consumed")
        }
    }

    private inner class Fixture {
        val name = "home-integration-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        var token = "home-private-${UUID.randomUUID()}"
        val posts = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        val providerRequests = CopyOnWriteArrayList<String>()
        val provider = HttpClient(MockEngine { request ->
            providerRequests += (request.body as TextContent).text
            check(replies.isNotEmpty()) { "Unexpected provider request" }
            respond(replies.removeAt(0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val home = HttpClient(MockEngine { request ->
            assertEquals("controller.test", request.url.host)
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            val body = when (request.url.encodedPath) {
                "/api/services" -> """[{"domain":"switch","services":{"turn_on":{"fields":{}},"turn_off":{"fields":{}}}}]"""
                "/api/states" -> """[{"entity_id":"switch.lamp","state":"on","attributes":{"friendly_name":"Lamp","unique_id":"fixture-lamp"}}]"""
                "/api/services/switch/turn_on", "/api/services/switch/turn_off" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(buildJsonObject { put("entity_id", "switch.lamp") }, Json.parseToJsonElement((request.body as TextContent).text))
                    posts += request.url.encodedPath
                    "[]"
                }
                else -> error("Unexpected Home route ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val station = AndroidSignalStation(context, noWatch, provider, name, homeClient = home)
        suspend fun main(block: AndroidSignalStation.() -> Unit) = withContext(Dispatchers.Main) { station.block() }
        suspend fun connect(draft: HomeConnection = HomeConnection("controller", "Test Home", HomeConnectorKind.HOME_ASSISTANT, "https://controller.test")): HomeConnection {
            main { testHomeConnection(draft, token) }
            until { !station.state.value.homeBusy }
            val preview = assertNotNull(station.state.value.homePreview, station.state.value.homeStatus)
            assertEquals("switch.lamp", station.state.value.homePreviewEntities.single().id)
            main { saveHomeConnection() }
            until { station.state.value.home.connections.any { it == preview } && station.state.value.homeStatus.startsWith("Connection saved") }
            return preview
        }
        suspend fun request(action: String): HomeLedgerEntry {
            val before = station.state.value.home.ledger.map { it.action.id }.toSet()
            main { requestHomeAction("controller", "switch.lamp", action, emptyMap()) }
            until { station.state.value.home.ledger.any { it.action.id !in before } }
            return station.state.value.home.ledger.single { it.action.id !in before }
        }
        fun entry(id: String) = station.state.value.home.ledger.single { it.action.id == id }
        suspend fun ask(question: String) {
            withContext(Dispatchers.Main) {
                until { !station.state.value.busy && !station.state.value.historyLoading }
                station.ask(question, false)
            }
            until { !station.state.value.busy }
            assertNotNull(station.state.value.questionReview, station.state.value.status)
            main { sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertNull(station.state.value.questionReview, station.state.value.status)
        }
    }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try {
            f.store.settings(SignalSettings(onboardingComplete = true, enabled = emptySet()))
            f.store.put("openai", "fixture-provider-key")
            f.main { initialize() }
            until { f.station.state.value.initialized && f.station.state.value.historyReady }
            test(f)
        } finally {
            f.main { close() }; f.store.close(); f.provider.close(); f.home.close()
            context.deleteDatabase("${f.name}-history.db")
            context.getSharedPreferences("${f.name}_private", 0).edit().clear().commit()
        }
    }
    private fun tool(id: String, name: String, connection: String, action: String? = null): String = buildJsonObject {
        put("status", "completed")
        putJsonArray("output") { add(buildJsonObject {
            put("type", "function_call"); put("call_id", id); put("name", name)
            put("arguments", buildJsonObject {
                put("connection_id", connection); put("entity_id", "switch.lamp")
                if (action != null) { put("action_id", action); putJsonObject("parameters") {} }
            }.toString())
        }) }
    }.toString()
    private val answer = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Fixture result"}]}]}"""
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(25) }
    private val noWatch = object : SignalWatchLink {
        override val watches = MutableStateFlow(emptyList<SignalWatch>())
        override val capabilities = SignalWatchCapabilities()
        override fun initialize(scope: CoroutineScope) {}
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun launch(watchId: String) { error("No watch should be launched") }
        override suspend fun install(watchId: String) { error("No watch should be installed") }
    }
}
