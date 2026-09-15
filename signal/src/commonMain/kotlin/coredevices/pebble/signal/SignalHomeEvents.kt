package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** No background reconnection or action channel: collection owns one authenticated session. */
internal fun signalHomeAssistantEvents(transport:SignalHomeTransport,clock:()->Long):Flow<HomeEntity> = flow {
    val services=homeBounded(transport.json("/api/services") as? JsonArray ?: throw HomeException("Invalid service catalog."))
    val helper=SignalHomeAssistantConnector(transport,clock)
    val credential=transport.token()
    val client=HttpClient(transport.client.engine) { followRedirects=false;expectSuccess=false;install(WebSockets) }
    try {
        val ws=transport.url("/api/websocket").replaceFirst("https://","wss://").replaceFirst("http://","ws://")
        client.webSocket(ws) {
            suspend fun message():JsonObject {
                val frame=incoming.receive() as? Frame.Text ?: throw HomeException("Unexpected controller event.")
                require(frame.data.size<=512*1024);return SignalHomeTransport.parse(frame.readText()).jsonObject
            }
            withTimeout(10_000) {
                require(message().text("type")=="auth_required") { "Unexpected controller handshake." }
                send(Frame.Text(buildJsonObject { put("type","auth");put("access_token",credential) }.toString()))
                require(message().text("type")=="auth_ok") { "Controller authentication failed." }
                send(Frame.Text("{\"id\":1,\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}"))
                val ack=message();require(ack.text("type")=="result" && ack["success"]?.jsonPrimitive?.booleanOrNull==true) { "Controller refused event subscription." }
            }
            for(frame in incoming) {
                if(frame !is Frame.Text) continue
                require(frame.data.size<=512*1024)
                val row=SignalHomeTransport.parse(frame.readText()).jsonObject
                if(row.text("type")!="event") continue
                val event=row.obj("event");val data=event.obj("data")
                val state=data["new_state"] as? JsonObject
                if(state!=null) emit(helper.entity(state,services))
                else if(data.text("entity_id").isNotBlank()) emit(HomeEntity(transport.connection.id,data.text("entity_id"),data.text("entity_id"),state="removed",available=false,observedAt=clock(),updatedAt=homeTime(event.text("time_fired"))))
            }
        }
    } catch(e:CancellationException) { throw e } catch(_:Exception) { throw HomeException("Live Home Assistant updates stopped. Refresh to reconnect.") }
    finally { client.close() }
}

internal fun signalOpenHabEvents(transport:SignalHomeTransport,clock:()->Long):Flow<HomeEntity> = flow {
    val entities=SignalOpenHabConnector(transport,clock).catalog().associateBy { it.id }.toMutableMap()
    val credential=transport.token()
    // A clean streaming client omits finite REST request deadlines, but has no reconnect policy.
    val client=HttpClient(transport.client.engine) { followRedirects=false;expectSuccess=false }
    try {
        client.prepareGet(transport.url("/rest/events?topics=openhab/items/*/statechanged")) { basicAuth(credential, "");accept(ContentType.Text.EventStream) }.execute { response ->
            if(response.status.value!=200) throw HomeException("Controller event stream unavailable.")
            val channel=response.bodyAsChannel();val buffer=ByteArray(4096);val pending=ArrayList<Byte>();var data=""
            suspend fun event(raw:String) {
                if(raw.isBlank()) return
                val obj=SignalHomeTransport.parse(raw).jsonObject;val parts=obj.text("topic").split('/')
                if(parts.size!=4 || parts[0]!="openhab" || parts[1]!="items" || parts[3]!="statechanged") return
                val existing=entities[parts[2]] ?: return
                val payload=SignalHomeTransport.parse(obj.text("payload")).jsonObject
                val value=payload.text("value");if(value.length>512) return
                val next=existing.copy(state=value,available=true,observedAt=clock(),updatedAt=homeTime(payload.text("lastStateUpdate")) ?: homeTime(obj.text("timestamp")))
                entities[next.id]=next;emit(next)
            }
            while(true) {
                val n=channel.readAvailable(buffer,0,buffer.size);if(n<0) break;if(n==0) continue
                repeat(n) { pending.add(buffer[it]) }
                require(pending.size<=64*1024 && data.length<=64*1024)
                while(10.toByte() in pending) {
                    val end=pending.indexOf(10.toByte())
                    val line=pending.take(end).toByteArray().decodeToString().removeSuffix("\r")
                    repeat(end+1) { pending.removeAt(0) }
                    when { line.isEmpty()-> { event(data);data="" };line.startsWith("data:")-> { if(data.isNotEmpty()) data+="\n";data+=line.removePrefix("data:").removePrefix(" ") } }
                }
            }
        }
    } catch(e:CancellationException) { throw e } catch(_:Exception) { throw HomeException("Live openHAB updates stopped. Refresh to reconnect.") }
    finally { client.close() }
}
