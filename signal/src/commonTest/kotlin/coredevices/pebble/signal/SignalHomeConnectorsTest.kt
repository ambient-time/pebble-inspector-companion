package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class SignalHomeConnectorsTest {
    private class Secrets:SignalSecrets { override suspend fun get(provider:String)="test-private-token";override suspend fun put(provider:String,key:String)=Unit }
    @Test fun homeHttpTrustNeverChangesHttpsDefaultsOrAllowsPublicCleartext() {
        fun c(url:String,trust:Boolean=false)=HomeConnection("c","C",HomeConnectorKind.GEEPERS,url,allowPrivateHttp=trust)
        SignalHomeUrlPolicy.validate(c("https://controller.example"))
        assertFailsWith<IllegalArgumentException> { SignalHomeUrlPolicy.validate(c("http://192.168.0.100")) }
        SignalHomeUrlPolicy.validate(c("http://192.168.0.100:5012",true))
        assertFailsWith<IllegalArgumentException> { SignalHomeUrlPolicy.validate(c("http://example.com",true)) }
        assertFailsWith<IllegalArgumentException> { SignalHomeUrlPolicy.validate(c("https://user:secret@example.com")) }
        assertFailsWith<IllegalArgumentException> { SignalHomeUrlPolicy.validate(c("https://controller.example?token=x")) }
    }
    @Test fun redirectRefusedWithoutSecondRequestOrCredentialForwarding():Unit=runBlocking {
        var calls=0;val http=HttpClient(MockEngine { calls++;respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://other.example/steal")) })
        val c=SignalHomeTransport(http,HomeConnection("c","C",HomeConnectorKind.GEEPERS,"https://controller.test"),Secrets())
        try { assertFailsWith<HomeException> { c.request("/api/test") };assertEquals(1,calls) } finally { c.close();http.close() }
    }
    @Test fun haDiscoversNativeServicesAndUsesServiceCallNotStateMutation():Unit=runBlocking {
        var posts=0;val http=HttpClient(MockEngine { r ->
            assertEquals("Bearer test-private-token",r.headers[HttpHeaders.Authorization])
            when(r.url.encodedPath) {
                "/api/services" -> respond("""[{"domain":"lock","services":{"unlock":{"name":"Unlock","fields":{}}}}]""",HttpStatusCode.OK)
                "/api/states" -> respond("""[{"entity_id":"lock.front","state":"locked","last_updated":"2026-09-15T12:00:00Z","attributes":{"friendly_name":"Front door","supported_features":0}}]""",HttpStatusCode.OK)
                "/api/services/lock/unlock" -> { posts++;assertEquals(HttpMethod.Post,r.method);assertEquals("lock.front",Json.parseToJsonElement((r.body as TextContent).text).jsonObject.text("entity_id"));respond("[]",HttpStatusCode.OK) }
                else -> error("unexpected route")
            }
        })
        val c=SignalHomeConnectorFactory(http,Secrets(),{99}).create(HomeConnection("c","C",HomeConnectorKind.HOME_ASSISTANT,"https://controller.test"))
        try { val entity=c.catalog().single();assertNotNull(entity.updatedAt);assertEquals(99L,entity.observedAt);assertEquals("lock.unlock",entity.capabilities.single().id)
            val result=c.execute(HomeAction("id","c",entity.id,"lock.unlock",createdAt=0,identity=entity.identity));assertEquals(HomeActionStatus.ACCEPTED,result.status);assertEquals(1,posts)
        } finally { c.close();http.close() }
    }
    @Test fun openHabPostsTextCommandAndRetainsUnknownNativeTime():Unit=runBlocking {
        val http=HttpClient(MockEngine { r -> assertTrue(r.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "));if(r.method==HttpMethod.Get) respond("""[{"name":"Lamp","type":"Switch","label":"Lamp","state":"OFF"}]""",HttpStatusCode.OK)
            else { assertEquals("/rest/items/Lamp",r.url.encodedPath);assertEquals("ON",(r.body as TextContent).text);assertTrue(r.body.contentType.toString().startsWith("text/plain"));respond("",HttpStatusCode.OK) } })
        val c=SignalHomeConnectorFactory(http,Secrets(),{100}).create(HomeConnection("c","C",HomeConnectorKind.OPENHAB,"https://controller.test"))
        try { val e=c.catalog().single();assertNull(e.updatedAt);assertEquals(HomeActionStatus.ACCEPTED,c.execute(HomeAction("id","c",e.id,"ON",createdAt=0,identity=e.identity)).status) } finally { c.close();http.close() }
    }
    @Test fun geepersPreservesIdentityMillisecondsWarningsAndTypedAction():Unit=runBlocking {
        val http=HttpClient(MockEngine { r -> when {
            r.url.encodedPath.endsWith("snapshot") -> respond("""{"version":1,"next_cursor":null,"warnings":["hub_unavailable"],"devices":[{"id":"display:pulse-01","identity":"schema-sha","name":"Pulse","kind":"display","availability":"online","observed_at":123000,"measured_at":120000,"values":[{"key":"rotation_degrees","value":0}],"capabilities":[{"action":"configure","args":{"type":"object","properties":{"rotation_degrees":{"type":"integer","enum":[0,90,180,270]}},"required":["rotation_degrees"],"additionalProperties":false}}]}]}""",HttpStatusCode.OK)
            r.url.encodedPath.endsWith("actions") -> { val body=Json.parseToJsonElement((r.body as TextContent).text).jsonObject;assertEquals("schema-sha",body.text("identity"));assertEquals(90,body.obj("args")["rotation_degrees"]?.jsonPrimitive?.int);respond("""{"intent_id":"intent","status":"accepted"}""",HttpStatusCode.Accepted) }
            else -> respond("""{"intent_id":"intent","status":"observed"}""",HttpStatusCode.OK)
        } })
        val c=SignalHomeConnectorFactory(http,Secrets(),{0}).create(HomeConnection("c","C",HomeConnectorKind.GEEPERS,"https://controller.test"))
        try { val e=c.catalog().single();assertEquals(123000L,e.observedAt);assertEquals(120000L,e.updatedAt);assertEquals(listOf("hub_unavailable"),c.warnings)
            val result=c.execute(HomeAction("intent","c",e.id,"configure",mapOf("rotation_degrees" to "90"),0,e.identity));assertEquals(HomeActionStatus.ACCEPTED,result.status);assertEquals(HomeActionStatus.OBSERVED,c.receipt("intent")?.status)
        } finally { c.close();http.close() }
    }
    @Test fun openHabForegroundEventsPreserveNativeTimeWithoutPollingOrCommands():Unit=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { r -> calls++;assertEquals(HttpMethod.Get,r.method);assertTrue(r.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
            if(r.url.encodedPath=="/rest/items") respond("""[{"name":"Lamp","type":"Switch","state":"OFF"}]""",HttpStatusCode.OK)
            else {
                assertEquals("/rest/events",r.url.encodedPath)
                val payload=buildJsonObject { put("type","ItemStateChangedEvent");put("topic","openhab/items/Lamp/statechanged");put("timestamp","2026-09-15T12:00:00Z");put("payload","{\"value\":\"ON\"}") }
                respond("data: $payload\n\n",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"text/event-stream"))
            }
        })
        val c=SignalHomeConnectorFactory(http,Secrets(),{100}).create(HomeConnection("c","C",HomeConnectorKind.OPENHAB,"https://controller.test"))
        try { val e=c.foregroundEvents().first();assertEquals("ON",e.state);assertNotNull(e.updatedAt);assertEquals(100L,e.observedAt);assertEquals(2,calls) }
        finally { c.close();http.close() }
    }
    @Test fun overflowingCatalogFailsWithoutDispatch():Unit=runBlocking {
        val http=HttpClient(MockEngine { respond("["+List(5001){"{}"}.joinToString(",")+"]",HttpStatusCode.OK) })
        val c=SignalHomeConnectorFactory(http,Secrets(),{0}).create(HomeConnection("c","C",HomeConnectorKind.OPENHAB,"https://controller.test"))
        try { assertFailsWith<IllegalArgumentException> { c.catalog() } } finally { c.close();http.close() }
    }
    @Test fun largeCatalogIsBoundedAndRenamedEntityInvalidatesIdentity():Unit=runBlocking {
        var label="Original"
        val http=HttpClient(MockEngine { respond("["+List(750) { n -> "{\"name\":\"Item$n\",\"type\":\"Switch\",\"label\":\"$label\",\"state\":\"OFF\"}" }.joinToString(",")+"]",HttpStatusCode.OK) })
        val c=SignalHomeConnectorFactory(http,Secrets(),{0}).create(HomeConnection("c","C",HomeConnectorKind.OPENHAB,"https://controller.test"))
        try { val before=c.catalog();assertEquals(750,before.size);label="Renamed";assertNotEquals(before.first().identity,c.catalog().first().identity) }
        finally { c.close();http.close() }
    }
    @Test fun expiredCredentialsFailWithoutActionRequest():Unit=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { calls++;respond("expired",HttpStatusCode.Unauthorized) })
        val c=SignalHomeConnectorFactory(http,Secrets(),{0}).create(HomeConnection("c","C",HomeConnectorKind.OPENHAB,"https://controller.test"))
        try { assertFailsWith<HomeException> { c.catalog() };assertEquals(1,calls) } finally { c.close();http.close() }
    }

}
