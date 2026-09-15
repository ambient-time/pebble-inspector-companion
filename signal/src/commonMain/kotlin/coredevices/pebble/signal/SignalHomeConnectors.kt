package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Home-specific opt-in. Cleartext is limited to explicit private literals or local names. */
object SignalHomeUrlPolicy {
    fun validate(connection: HomeConnection): Url {
        val raw = connection.baseUrl.trim()
        require(raw.length in 1..2048 && !raw.any { it.isWhitespace() || it.code < 32 } && '\\' !in raw) { "Invalid Home URL." }
        val url = Url(raw)
        require(url.protocol in setOf(URLProtocol.HTTPS, URLProtocol.HTTP)) { "Use HTTPS or trusted private HTTP." }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty()) { "Keep credentials, queries and fragments out of the connection URL." }
        require(url.host.isNotBlank() && url.encodedPath.split('/').none { it == "." || it == ".." || '%' in it }) { "Invalid connection path." }
        if (url.protocol == URLProtocol.HTTP) require(connection.allowPrivateHttp && isPrivateHost(url.host)) { "HTTP requires explicit trust for a private controller." }
        return url
    }
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h == "localhost" || h.endsWith(".local")) return true
        if (h == "::1" || h.startsWith("fc") && ':' in h || h.startsWith("fd") && ':' in h || h.startsWith("fe80:") ) return true
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if(p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31 || p[0] == 169 && p[1] == 254
    }
}

/** Owns a clean client configuration: no redirects, retries, default auth or logging plugins. */
class SignalHomeTransport(http: HttpClient, val connection: HomeConnection, private val secrets: SignalSecrets) {
    private val base = SignalHomeUrlPolicy.validate(connection).toString().trimEnd('/')
    val client = HttpClient(http.engine) {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
    }
    suspend fun token(): String = secrets.get(connection.credentialKey)?.takeIf { it.isNotBlank() && it.length <= 8192 && '\n' !in it && '\r' !in it } ?: throw HomeException("Add the controller token in connection settings.")
    fun url(path: String): String { require(path.startsWith('/') && !path.startsWith("//")); return base + path }
    suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, payload: String? = null, type: ContentType = ContentType.Application.Json): String = withTimeout(15_000) {
        try {
            val credential = token()
            client.prepareRequest(url(path)) {
                this.method = method; if(connection.kind == HomeConnectorKind.OPENHAB) basicAuth(credential, "") else bearerAuth(credential); accept(ContentType.Application.Json)
                if (payload != null) { require(payload.encodeToByteArray().size <= 16_384); contentType(type); setBody(payload) }
            }.execute { response ->
                if(response.status.value !in 200..299) throw HomeException(when(response.status.value) { 401,403 -> "Controller authentication failed."; in 300..399 -> "Controller redirect refused. Update the connection URL."; 409 -> "Controller rejected a stale or conflicting action."; else -> "Controller request failed (${response.status.value})." })
                val channel = response.bodyAsChannel(); val bytes = ArrayList<Byte>(); val buffer = ByteArray(8192)
                while (true) { val count = channel.readAvailable(buffer,0,buffer.size); if(count < 0) break; if(count == 0) continue
                    if(bytes.size + count > 4 * 1024 * 1024) throw HomeException("Controller response exceeds the catalog limit.")
                    repeat(count) { bytes.add(buffer[it]) }
                }
                bytes.toByteArray().decodeToString()
            }
        } catch(e: CancellationException) { throw e } catch(e: HomeException) { throw e } catch(_:Exception) { throw HomeException("Controller could not be reached. Nothing will retry automatically.") }
    }
    suspend fun json(path: String): JsonElement = parse(request(path))
    fun close() = client.close()
    companion object { fun parse(text: String): JsonElement = try { Json.parseToJsonElement(text) } catch(_:Exception) { throw HomeException("Controller returned an invalid response.") } }
}
internal fun JsonObject.text(key:String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.obj(key:String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.arr(key:String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun homeTime(value:String):Long? = try { Instant.parse(value.substringBefore('[')).toEpochMilliseconds() } catch(_:Exception) { null }
internal fun homeSegment(value:String):String { require(value.length in 1..256); return value.encodeURLPathPart() }
internal fun homeBounded(rows:JsonArray):List<JsonObject> { require(rows.size <= 5000) { "Controller catalog exceeds 5000 entities." }; return rows.map { it as? JsonObject ?: throw HomeException("Invalid controller entity.") } }
internal fun homeScalarFields(obj:JsonObject):Map<String,String> = obj.entries.filter { it.value is JsonPrimitive }.take(40).associate { it.key.take(80) to (it.value as JsonPrimitive).content.take(512) }
internal fun homeSchemaIdentity(id:String, schema:List<HomeCapability>, extra:String = ""):String = id + "|" + extra + "|" + Json.encodeToString(schema)

class SignalHomeAssistantConnector(private val transport: SignalHomeTransport, private val clock: () -> Long) : HomeConnector {
    private var catalogCache:Map<String,HomeEntity> = emptyMap()
    override suspend fun catalog(): List<HomeEntity> {
        val services = homeBounded(transport.json("/api/services") as? JsonArray ?: throw HomeException("Invalid service catalog."))
        val states = homeBounded(transport.json("/api/states") as? JsonArray ?: throw HomeException("Invalid entity catalog."))
        return states.map { state -> entity(state, services) }.also { catalogCache=it.associateBy { e->e.id } }
    }
    internal fun entity(row:JsonObject, services:List<JsonObject>): HomeEntity {
        val id = row.text("entity_id"); require(id.matches(Regex("[a-z0-9_]+\\.[a-z0-9_]+")))
        val domain = id.substringBefore('.'); val attrs = row.obj("attributes")
        val caps = services.firstOrNull { it.text("domain") == domain }?.obj("services")?.entries?.take(80)?.mapNotNull { (service,raw) ->
            if (!service.matches(Regex("[a-z0-9_]+"))) return@mapNotNull null
            val spec = raw as? JsonObject ?: return@mapNotNull null
            val fields = spec.obj("fields").filterKeys { it != "entity_id" }
            val parameters = fields.mapNotNull { (name,value) -> haParameter(name,value as? JsonObject ?: JsonObject(emptyMap())) }
            // Complex controller fields are unsupported, never silently stripped from a required schema.
            if(fields.any { (name,value) -> (value as? JsonObject)?.get("required")?.jsonPrimitive?.booleanOrNull == true && parameters.none { it.name == name } }) return@mapNotNull null
            HomeCapability("$domain.$service", spec.text("name").ifBlank { service.replace('_',' ') }, parameters, when(service) { "turn_on" -> mapOf("state" to "on"); "turn_off" -> mapOf("state" to "off"); "lock" -> mapOf("state" to "locked"); "unlock" -> mapOf("state" to "unlocked"); else -> emptyMap() })
        }.orEmpty()
        return HomeEntity(transport.connection.id,id,attrs.text("friendly_name").ifBlank{id},domain,row.text("state"),row.text("state") !in setOf("unavailable",""),caps,homeTime(row.text("last_updated")),clock(),homeScalarFields(attrs),homeSchemaIdentity(id,caps,listOf("friendly_name","unique_id","device_class","supported_features","unit_of_measurement").joinToString("|") { attrs[it].toString() } + if(domain in setOf("scene","script")) attrs["entity_id"].toString() else ""))
    }
    private fun haParameter(name:String,field:JsonObject):HomeParameter? {
        val selector = field.obj("selector"); val required = field["required"]?.jsonPrimitive?.booleanOrNull == true
        return when {
            "number" in selector -> { val n=selector.obj("number");HomeParameter(name,"number",required,n["min"]?.jsonPrimitive?.doubleOrNull,n["max"]?.jsonPrimitive?.doubleOrNull) }
            "boolean" in selector -> HomeParameter(name,"boolean",required)
            "text" in selector -> HomeParameter(name,"string",required)
            "select" in selector -> { val options=selector.obj("select").arr("options").mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("value") }; if(options.isEmpty()) null else HomeParameter(name,"string",required,options=options) }
            selector.isEmpty() && name in setOf("temperature","brightness","brightness_pct","percentage","position","volume_level") -> HomeParameter(name,"number",required)
            else -> null
        }
    }
    override suspend fun execute(action:HomeAction):HomeDispatchResult {
        val parts=action.capabilityId.split('.'); require(parts.size==2 && parts.all { it.matches(Regex("[a-z0-9_]+")) })
        val current=catalogCache[action.entityId] ?: read(action.entityId) ?: throw HomeException("Entity removed.");require(current.identity==action.identity)
        val cap=current.capabilities.firstOrNull { it.id==action.capabilityId } ?: throw HomeException("Unsupported action.")
        val args=normalizeHomeParameters(cap,action.parameters)
        val payload=buildJsonObject { put("entity_id",action.entityId);args.forEach { (key,value)-> put(key,when(cap.parameters.first { it.name==key }.type) { "number","integer"->JsonPrimitive(value.toDouble());"boolean"->JsonPrimitive(value.toBoolean());else->JsonPrimitive(value) }) } }
        transport.request("/api/services/${parts[0]}/${parts[1]}",HttpMethod.Post,payload.toString())
        return HomeDispatchResult()
    }
    override fun foregroundEvents() = signalHomeAssistantEvents(transport, clock)
    override fun close()=transport.close()
}

class SignalOpenHabConnector(private val transport:SignalHomeTransport,private val clock:()->Long):HomeConnector {
    private var catalogCache:Map<String,HomeEntity> = emptyMap()
    override suspend fun catalog():List<HomeEntity> = homeBounded(transport.json("/rest/items?recursive=false") as? JsonArray ?: throw HomeException("Invalid item catalog.")).map { row ->
        val id=row.text("name");require(id.matches(Regex("[A-Za-z0-9_]+")));val type=row.text("type");val state=row.text("state")
        val cap = when(type) {
            "Switch" -> listOf(HomeCapability("ON","Turn on",expectedValues=mapOf("state" to "ON")),HomeCapability("OFF","Turn off",expectedValues=mapOf("state" to "OFF")))
            "Dimmer" -> listOf(HomeCapability("set","Set brightness",listOf(HomeParameter("value","integer",minimum=0.0,maximum=100.0)),mapOf("state" to "$"+"value")))
            "Rollershutter" -> listOf("UP","DOWN","STOP").map { HomeCapability(it,it.lowercase().replaceFirstChar { c->c.uppercase() }) } + HomeCapability("set","Set position",listOf(HomeParameter("value","integer",minimum=0.0,maximum=100.0)),mapOf("state" to "$"+"value"))
            "Player" -> listOf("PLAY","PAUSE","NEXT","PREVIOUS","REWIND","FASTFORWARD").map { HomeCapability(it,it.lowercase()) }
            "String" -> listOf(HomeCapability("set","Send text",listOf(HomeParameter("value"))))
            "Color" -> listOf(HomeCapability("set","Set HSB color",listOf(HomeParameter("value","hsb"))))
            else -> if(type=="Number" || type.startsWith("Number:")) listOf(HomeCapability("set","Set value",listOf(HomeParameter("value","number")),mapOf("state" to "$"+"value"))) else emptyList()
        }
        val caps=if(row.obj("stateDescription")["readOnly"]?.jsonPrimitive?.booleanOrNull==true) emptyList() else cap
        HomeEntity(transport.connection.id,id,row.text("label").ifBlank{id},type,state,true,caps,homeTime(row.text("lastStateUpdate")),clock(),homeScalarFields(row),homeSchemaIdentity(id,caps,row.text("label")+"|"+type+"|"+row.arr("groupNames").toString()))
    }.also { catalogCache=it.associateBy { e->e.id } }
    override suspend fun execute(action:HomeAction):HomeDispatchResult {
        val device=catalogCache[action.entityId] ?: read(action.entityId) ?: throw HomeException("Item removed.");require(device.identity==action.identity)
        val cap=device.capabilities.firstOrNull { it.id==action.capabilityId } ?: throw HomeException("Unsupported command.")
        val args=normalizeHomeParameters(cap,action.parameters);val command=if(action.capabilityId=="set") args.getValue("value") else action.capabilityId
        transport.request("/rest/items/${homeSegment(action.entityId)}",HttpMethod.Post,command,ContentType.Text.Plain)
        return HomeDispatchResult()
    }
    override fun foregroundEvents() = signalOpenHabEvents(transport, clock)
    override fun close()=transport.close()
}

class SignalGeepersHomeConnector(private val transport:SignalHomeTransport,private val clock:()->Long):HomeConnector {
    private val prefix="/api/signal-home/v1"
    private var catalogCache:Map<String,HomeEntity> = emptyMap()
    override var warnings:List<String> = emptyList(); private set
    override suspend fun catalog():List<HomeEntity> {
        warnings=emptyList();val rows=mutableListOf<HomeEntity>();val cursors=mutableSetOf<String>();var cursor:String?=null
        do {
            val page=transport.json("$prefix/snapshot?limit=100"+(cursor?.let { "&cursor="+it.encodeURLParameter() } ?: "")) as? JsonObject ?: throw HomeException("Invalid Geepers snapshot.")
            require(page["version"]?.jsonPrimitive?.intOrNull==1)
            warnings=(warnings+page.arr("warnings").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(80) }).distinct().take(20)
            rows += homeBounded(page.arr("devices")).map { row ->
                val caps=row.arr("capabilities").take(80).map { raw ->
                    val c=raw.jsonObject;val schema=c.obj("args");val required=schema.arr("required").map { it.jsonPrimitive.content }
                    HomeCapability(c.text("action"),c.text("action").replace('_',' '),schema.obj("properties").map { (name,p) -> val prop=p.jsonObject;HomeParameter(name,prop.text("type").ifBlank{"string"},name in required,prop["minimum"]?.jsonPrimitive?.doubleOrNull,prop["maximum"]?.jsonPrimitive?.doubleOrNull,prop.arr("enum").map { it.jsonPrimitive.content }) })
                }
                val values=row.arr("values").take(40).associate { raw -> val v=raw.jsonObject; v.text("key") to v.text("value") }
                HomeEntity(transport.connection.id,row.text("id"),row.text("name"),row.text("kind"),values["state"] ?: row.text("availability"),row.text("availability") in setOf("online","available"),caps,row["measured_at"]?.jsonPrimitive?.longOrNull,row["observed_at"]?.jsonPrimitive?.longOrNull ?: clock(),values,row.text("identity"),row.arr("values").take(40).map { raw -> val v=raw.jsonObject;HomeValue(v.text("key"),v.text("value"),(v["unit"] as? JsonPrimitive)?.contentOrNull,v["measured_at"]?.jsonPrimitive?.longOrNull) })
            }
            require(rows.size<=500) { "Geepers catalog exceeds 500 devices." }
            cursor=(page["next_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if(cursor!=null) require(cursor.length<=512 && cursors.add(cursor)) { "Invalid pagination cursor." }
        } while(cursor!=null)
        catalogCache=rows.associateBy { it.id };return rows
    }
    override suspend fun execute(action:HomeAction):HomeDispatchResult {
        val device=catalogCache[action.entityId] ?: read(action.entityId) ?: throw HomeException("Device removed.");require(device.identity==action.identity)
        val cap=device.capabilities.firstOrNull { it.id==action.capabilityId } ?: throw HomeException("Unsupported action.")
        val args=normalizeHomeParameters(cap,action.parameters)
        val payload=buildJsonObject { put("intent_id",action.id);put("device_id",action.entityId);put("identity",action.identity);put("action",action.capabilityId);put("args",buildJsonObject { args.forEach { (k,v)->put(k,when(cap.parameters.first { it.name==k }.type){"integer"->JsonPrimitive(v.toInt());"number"->JsonPrimitive(v.toDouble());"boolean"->JsonPrimitive(v.toBoolean());else->JsonPrimitive(v)}) } }) }
        return result(SignalHomeTransport.parse(transport.request("$prefix/actions",HttpMethod.Post,payload.toString())).jsonObject,action.id)
    }
    override suspend fun receipt(id:String):HomeDispatchResult = result(transport.json("$prefix/receipts/${homeSegment(id)}").jsonObject,id)
    private fun result(row:JsonObject,id:String):HomeDispatchResult { require(row.text("intent_id")==id);return HomeDispatchResult(when(row.text("status")){"accepted"->HomeActionStatus.ACCEPTED;"observed"->HomeActionStatus.OBSERVED;"failed"->HomeActionStatus.FAILED;else->HomeActionStatus.UNKNOWN},id,when(row.text("status")){"observed"->"Device reports completion. Physical appearance is unverified.";"failed"->"Controller reported failure.";"accepted"->if(row["command_reported_complete"]?.jsonPrimitive?.booleanOrNull==true) "Device reports command completion; matching state and physical appearance remain unverified." else "Controller accepted the request; physical outcome is unverified.";else->"Completion is unknown. No automatic retry."}) }
    override fun close()=transport.close()
}
