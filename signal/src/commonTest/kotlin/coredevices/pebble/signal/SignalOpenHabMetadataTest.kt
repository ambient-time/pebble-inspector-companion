package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SignalOpenHabMetadataTest {
    private class Fixture(var row:String) {
        val posts=mutableListOf<String>()
        private val http=HttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Get) respond("[$row]",HttpStatusCode.OK)
            else { posts+=(request.body as TextContent).text;respond("",HttpStatusCode.OK) }
        })
        val connector=SignalHomeConnectorFactory(http,object:SignalSecrets {
            override suspend fun get(provider:String)="fixture-token"
            override suspend fun put(provider:String,key:String)=Unit
        },{1000}).create(HomeConnection("c","Test",HomeConnectorKind.OPENHAB,"https://controller.test"))
        suspend fun action(entity:HomeEntity,id:String,args:Map<String,String> = emptyMap()) = connector.execute(HomeAction("intent","c",entity.id,id,args,0,entity.identity))
        fun close() { connector.close();http.close() }
    }
    @Test fun declaredStringCommandsOverrideGenericInputAndSetIsLiteral():Unit=runBlocking {
        val f=Fixture("""{"name":"Mode","type":"String","state":"HOME","commandDescription":{"commandOptions":[{"command":"HOME","label":"Home"},{"command":"AWAY","label":"Away"},{"command":"set","label":"Declared set"}]},"stateDescription":{"options":[{"value":"WRONG"}]}}""")
        try {
            val e=f.connector.catalog().single();assertEquals(listOf("HOME","AWAY","set"),e.capabilities.map { it.id })
            assertTrue(e.capabilities.all { it.parameters.isEmpty() })
            assertFailsWith<IllegalArgumentException> { f.action(e,"set",mapOf("value" to "unexpected")) }
            assertFailsWith<HomeException> { f.action(e,"WRONG") }
            assertTrue(f.posts.isEmpty())
            f.action(e,"set");assertEquals(listOf("set"),f.posts)
        } finally { f.close() }
    }
    @Test fun stateOptionsProvideCommandsWhenDedicatedOptionsAbsent():Unit=runBlocking {
        val f=Fixture("""{"name":"Mode","type":"String","state":"HOME","stateDescription":{"options":[{"value":"HOME","label":"At home"},{"value":"AWAY","label":"Away"}]}}""")
        try { val e=f.connector.catalog().single();assertEquals(listOf("HOME","AWAY"),e.capabilities.map { it.id });f.action(e,"AWAY");assertEquals(listOf("AWAY"),f.posts) }
        finally { f.close() }
    }
    @Test fun numericLimitsAndNativeUnitsAreEnforcedBeforePost():Unit=runBlocking {
        val f=Fixture("""{"name":"Temperature","type":"Number:Temperature","unitSymbol":"°C","state":"20 °C","lastStateUpdate":123456,"stateDescription":{"minimum":10,"maximum":30}}""")
        try {
            val e=f.connector.catalog().single();assertEquals(123456L,e.updatedAt)
            assertEquals(10.0,e.capabilities.single().parameters.single().minimum)
            assertTrue(e.capabilities.single().name.contains("°C"))
            for(value in listOf("9","31","NaN","22 °F")) assertFailsWith<IllegalArgumentException> { f.action(e,"set",mapOf("value" to value)) }
            assertTrue(f.posts.isEmpty());f.action(e,"set",mapOf("value" to "22"));assertEquals(listOf("22.0 °C"),f.posts)
        } finally { f.close() }
    }
    @Test fun declaredMetadataChangeInvalidatesCachedIdentity():Unit=runBlocking {
        val f=Fixture("""{"name":"Level","type":"Dimmer","label":"Level","state":"30","stateDescription":{"minimum":10,"maximum":70}}""")
        try {
            val before=f.connector.catalog().single();f.row=f.row.replace("\"maximum\":70","\"maximum\":50")
            val after=f.connector.catalog().single();assertNotEquals(before.identity,after.identity)
            assertFailsWith<IllegalArgumentException> { f.action(before,"set",mapOf("value" to "40")) }
            assertFailsWith<IllegalArgumentException> { f.action(after,"set",mapOf("value" to "60")) }
            assertTrue(f.posts.isEmpty())
        } finally { f.close() }
    }
    @Test fun readOnlyAndUndefinedItemsCannotDispatch():Unit=runBlocking {
        for(extra in listOf("\"state\":\"OFF\",\"stateDescription\":{\"readOnly\":true}","\"state\":\"NULL\"","\"state\":\"UNDEF\"")) {
            val f=Fixture("{\"name\":\"Lamp\",\"type\":\"Switch\",$extra}")
            try {
                val e=f.connector.catalog().single()
                if("readOnly" in extra) { assertTrue(e.capabilities.isEmpty());assertFailsWith<HomeException> { f.action(e,"ON") } }
                else { assertFalse(e.available);assertFailsWith<IllegalArgumentException> { f.action(e,"ON") } }
                assertTrue(f.posts.isEmpty())
            } finally { f.close() }
        }
    }
    @Test fun supportedTypesWithoutOptionsKeepTheirNativeCommands():Unit=runBlocking {
        for((type,command,args,wire) in listOf(
            arrayOf("Switch","ON","","ON"),arrayOf("Dimmer","set","25","25"),
            arrayOf("Rollershutter","STOP","","STOP"),arrayOf("Player","PAUSE","","PAUSE"),
            arrayOf("String","set","hello","hello"),arrayOf("Color","set","120,50,40","120.0,50.0,40.0"),
            arrayOf("Number","set","12","12.0")
        )) {
            val f=Fixture("{\"name\":\"Item\",\"type\":\"$type\",\"state\":\"0\"}")
            try { val e=f.connector.catalog().single();f.action(e,command,if(args.isEmpty()) emptyMap() else mapOf("value" to args));assertEquals(listOf(wire),f.posts) }
            finally { f.close() }
        }
    }
}
