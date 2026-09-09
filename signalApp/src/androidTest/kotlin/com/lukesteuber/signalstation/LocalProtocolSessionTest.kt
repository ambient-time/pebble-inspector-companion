package com.lukesteuber.signalstation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.SignalWatchResponse
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class LocalProtocolSessionTest {
    @Test fun failedChecksNeverClaimWatchAcknowledgement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val watch = WatchIdentifier("failed-check-test")
        for (rejectedByPhone in listOf(false, true)) {
            val statuses = CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sender = Proxy.newProxyInstance(PebbleSender::class.java.classLoader, arrayOf(PebbleSender::class.java)) { _, method, _ ->
                if (method.name == "sendDataToPebble") emptyMap<WatchIdentifier, TransmissionResult>() else null
            } as PebbleSender
            val session = withContext(Dispatchers.Main.immediate) {
                LocalProtocolSession(context, watch.value, "failure", scope, sender, onStatus = { statuses += it }) { _, _, _, _ ->
                    if (rejectedByPhone) SignalWatchResponse("{}", 403)
                    else SignalWatchResponse("{\"configured\":true,\"enabled\":[]}", 200)
                }.also { it.start() }
            }
            try {
                val expected = if (rejectedByPhone) "Phone rejected the link." else "Watch did not acknowledge."
                withTimeout(15000) { while (statuses.none { it.startsWith(expected) }) delay(25) }
                assertFalse(statuses.contains("Watch acknowledged the connection."))
            } finally {
                withContext(Dispatchers.Main.immediate) { session.close() }
                scope.cancel()
            }
        }
    }

    @Test fun realRuntimeRoutesAndAcknowledgesThenRejectsClosedSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packets = CopyOnWriteArrayList<PebbleDictionary>()
        val requests = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        val watch = WatchIdentifier("isolated-test")
        val sender = Proxy.newProxyInstance(PebbleSender::class.java.classLoader, arrayOf(PebbleSender::class.java)) { _, method, args ->
            if (method.name == "sendDataToPebble") {
                @Suppress("UNCHECKED_CAST")
                packets += args[1] as PebbleDictionary
                mapOf(watch to TransmissionResult.Success)
            } else null
        } as PebbleSender
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = withContext(Dispatchers.Main.immediate) {
            LocalProtocolSession(context, watch.value, "test-1", scope, sender, onStatus = { statuses += it }) { url, method, _, _ ->
                assertEquals("GET", method)
                assertTrue(url.endsWith("/capabilities"))
                requests += url
                SignalWatchResponse("""{"configured":true,"enabled":["watch.battery"]}""", 200)
            }.also { it.start() }
        }
        try {
            withTimeout(15000) { while (packets.size < 1) delay(50) }
            withTimeout(5000) { while (statuses.lastOrNull() != "Watch acknowledged the connection.") delay(50) }
            assertTrue(statuses.contains("Phone ready; waiting for watch acknowledgement…"))
            assertEquals(PebbleDictionaryItem.Int32(1), packets[0][10024u])
            assertEquals(PebbleDictionaryItem.Text("[\"watch.battery\"]"), packets[0][10019u])
            withContext(Dispatchers.Main.immediate) { assertTrue(session.ready); session.sendConfigMessage("{\"kind\":\"refresh\"}") }
            withTimeout(5000) { while (packets.size < 2) delay(50) }
            withContext(Dispatchers.Main.immediate) {
                assertTrue(session.receive(mapOf(10000u to PebbleDictionaryItem.Text("ready"))))
            }
            withTimeout(5000) { while (packets.size < 3) delay(50) }
            assertEquals(3, requests.size)
            withContext(Dispatchers.Main.immediate) {
                session.close()
                assertFalse(session.ready)
                assertFalse(session.receive(mapOf(10000u to PebbleDictionaryItem.Text("ready"))))
            }
            assertEquals(3, requests.size)
        } finally {
            withContext(Dispatchers.Main.immediate) { session.close() }
            scope.cancel()
        }
    }
}
