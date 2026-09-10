package com.lukesteuber.signalstation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.rebble.pebblekit2.client.*
import io.rebble.pebblekit2.common.model.*
import io.rebble.pebblekit2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PebbleAppLinkTest {
    private val watch = WatchIdentifier("test-watch")
    private val running = Watchapp(UUID.fromString(AndroidSignalStation.APP_UUID), "Signal Station", Watchapp.Type.WATCHAPP)
    private val connected = ConnectedWatch(watch, "Test Pebble", "diorite", "test", 4, 3, 0, null)

    private class Picker(var selected: String? = "test.host") : PebbleAndroidAppPicker {
        override var enableAutoSelect = false
        override suspend fun getCurrentlySelectedApp() = selected
        override suspend fun selectApp(packageName: String?) { selected = packageName }
        override fun getAllEligibleApps() = listOf("test.host", "other.host", "coredevices.coreapp.inspectorlab")
    }
    private class Information(watches: List<ConnectedWatch>, app: Watchapp?) : PebbleInfoRetriever {
        val connected = MutableStateFlow(watches)
        val active = MutableStateFlow(app)
        override fun getConnectedWatches() = connected
        override fun getActiveApp(watch: WatchIdentifier) = active
    }
    private suspend fun until(check: () -> Boolean) { withTimeout(15000) { while (!check()) delay(25) } }

    @Test fun selectsAndDisconnectsWithRealSenderBeforeAnyWatchConnection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val picker = Picker(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val link = withContext(Dispatchers.Main.immediate) {
            PebbleAppLink(context, picker, Information(emptyList(), null)).also { it.initialize(scope) }
        }
        try {
            link.selectApp("test.host")
            assertEquals("test.host", picker.selected)
            link.selectApp("other.host")
            assertEquals("other.host", picker.selected)
            link.selectApp(null)
            assertNull(picker.selected)
            link.selectApp(null)
            assertTrue(link.watches.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun selectionCanFinishBeforeStorageInitializesWatchMonitoring() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val picker = Picker(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val link = withContext(Dispatchers.Main.immediate) {
            PebbleAppLink(context, picker, Information(listOf(connected), null))
        }
        try {
            link.selectApp("test.host")
            assertEquals("test.host", picker.selected)
            assertTrue(link.watches.value.isEmpty())
            withContext(Dispatchers.Main.immediate) { link.initialize(scope) }
            until { link.watches.value.size == 1 }
            link.selectApp(null)
        } finally { scope.cancel() }
    }

    @Test fun restoresAlreadyOpenAppAndClosesAcrossLifecycleBoundaries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val picker = Picker()
        val information = Information(listOf(connected), running)
        val packets = CopyOnWriteArrayList<PebbleDictionary>()
        val callers = CopyOnWriteArrayList<SignalWatchSession>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sender = Proxy.newProxyInstance(PebbleSender::class.java.classLoader, arrayOf(PebbleSender::class.java)) { _, method, args ->
            if (method.name == "sendDataToPebble") {
                @Suppress("UNCHECKED_CAST")
                packets += args[1] as PebbleDictionary
                mapOf(watch to TransmissionResult.Success)
            } else null
        } as PebbleSender
        val link = withContext(Dispatchers.Main.immediate) {
            PebbleAppLink(context, picker, information) { sender }.also {
                it.request = { _, _, _, caller -> callers += caller; SignalWatchResponse("""{"configured":true,"enabled":[]}""", 200) }
                it.initialize(scope)
            }
        }
        try {
            // No app-open callback: the host already reports the app running.
            until { packets.isNotEmpty() && callers.isNotEmpty() }
            val first = callers.last()
            assertTrue(withContext(Dispatchers.Main.immediate) { link.isTrusted(first) })
            assertFalse("coredevices.coreapp.inspectorlab" in link.availableApps())
            withContext(Dispatchers.Main.immediate) { link.opened(watch.value) }
            delay(100)
            assertEquals(first.connectionId, link.watches.value.single().connectionId)

            information.active.value = null
            until { !link.watches.value.single().appOpen }
            assertFalse(withContext(Dispatchers.Main.immediate) { link.isTrusted(first) })
            assertEquals(ReceiveResult.Nack, link.receive(watch.value, emptyMap()))

            information.active.value = running
            until { callers.last() !== first }
            val reopened = callers.last()
            information.connected.value = emptyList()
            until { link.watches.value.isEmpty() }
            assertFalse(withContext(Dispatchers.Main.immediate) { link.isTrusted(reopened) })
            information.connected.value = listOf(connected)
            until { callers.last() !== reopened }
            val reconnected = callers.last()

            information.active.value = null
            link.selectApp("other.host")
            until { link.watches.value.size == 1 }
            assertFalse(link.watches.value.single().appOpen)
            assertFalse(withContext(Dispatchers.Main.immediate) { link.isTrusted(reconnected) })
            link.selectApp(null)
            information.active.value = running
            delay(100)
            assertTrue(link.watches.value.isEmpty())
        } finally {
            link.selectApp(null)
            scope.cancel()
        }
    }
}
