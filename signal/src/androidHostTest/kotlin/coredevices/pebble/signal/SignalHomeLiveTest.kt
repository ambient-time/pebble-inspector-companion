package coredevices.pebble.signal

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in only: isolated synthetic servers, never an enrolled household connection. */
class SignalHomeLiveTest {
    @Test fun disposableHomeAssistantRestAndWebSocket():Unit=runBlocking {
        validate(HomeConnectorKind.HOME_ASSISTANT,5010,"ha-config/token","input_boolean.signal_test","input_boolean.turn_on","input_boolean.turn_off","on")
    }
    @Test fun disposableOpenHabRestAndSse():Unit=runBlocking {
        validate(HomeConnectorKind.OPENHAB,5011,"openhab-token","SignalTest","ON","OFF","ON")
    }
    private suspend fun validate(kind:HomeConnectorKind,port:Int,tokenPath:String,entityId:String,on:String,off:String,onState:String) = kotlinx.coroutines.coroutineScope {
        val directory=System.getenv("SIGNAL_HOME_DISPOSABLE_DIR")
        assumeTrue("Requires explicitly provisioned loopback-only disposable servers",directory!=null)
        val token=File(directory!!,tokenPath).readText().trim()
        val secrets=object:SignalSecrets { override suspend fun get(provider:String)=token;override suspend fun put(provider:String,key:String)=Unit }
        val connection=HomeConnection("test","Disposable",kind,"http://127.0.0.1:$port",allowPrivateHttp=true)
        val http=createSignalHomeHttpClient();val factory=SignalHomeConnectorFactory(http,secrets,System::currentTimeMillis)
        val c=factory.create(connection)
        try {
            val e=c.catalog().first { it.id==entityId };assertTrue(e.capabilities.any { it.id==on })
            c.execute(HomeAction("reset","test",e.id,off,createdAt=System.currentTimeMillis(),identity=e.identity))
            val events=factory.create(connection)
            try {
                val event=async { withTimeout(10_000) { events.foregroundEvents().first { it.id==entityId && it.state==onState } } }
                delay(800)
                val result=c.execute(HomeAction("send","test",e.id,on,createdAt=System.currentTimeMillis(),identity=e.identity))
                assertEquals(HomeActionStatus.ACCEPTED,result.status)
                val observed=event.await();assertEquals(onState,observed.state);assertNotNull(observed.updatedAt)
                assertEquals(onState,c.read(entityId)?.state)
            } finally { events.close() }
            val invalid=SignalHomeConnectorFactory(http,object:SignalSecrets { override suspend fun get(provider:String)="expired-disposable-token";override suspend fun put(provider:String,key:String)=Unit },System::currentTimeMillis).create(connection)
            try { assertFailsWith<HomeException> { invalid.catalog() } } finally { invalid.close() }
        } finally { c.close();http.close() }
    }
}
