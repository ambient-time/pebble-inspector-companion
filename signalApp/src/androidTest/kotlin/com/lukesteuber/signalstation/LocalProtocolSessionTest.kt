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
    @Test fun realRuntimeRoutesAndAcknowledgesThenRejectsClosedSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packets = CopyOnWriteArrayList<PebbleDictionary>()
        val requests = CopyOnWriteArrayList<String>()
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
            LocalProtocolSession(context, watch.value, "test-1", scope, sender) { url, method, _, _ ->
                assertEquals("GET", method)
                assertTrue(url.endsWith("/capabilities"))
                requests += url
                SignalWatchResponse("""{"configured":true,"enabled":["watch.battery"]}""", 200)
            }.also { it.start() }
        }
        try {
            withTimeout(15000) { while (packets.size < 1) delay(50) }
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
