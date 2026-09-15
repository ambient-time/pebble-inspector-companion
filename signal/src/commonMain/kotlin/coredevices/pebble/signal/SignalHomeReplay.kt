package coredevices.pebble.signal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Durable at-most-once dispatch for watch mutations, independent of phone sessions.
 *
 * Use one instance per store; the store must have one process writer and atomically,
 * durably replace a value before put returns. Do not evict this journal on reconnect
 * or app restart. A lost/corrupt store is not an empty journal. Wrap actuating review
 * and confirmation requests; reads and idempotent cancellation need not consume slots.
 * The stable watch identity is hashed into keys, never stored in plaintext here.
 */
class SignalHomeReplay(
    private val get: suspend (String) -> String?,
    private val put: suspend (String, String) -> Unit,
    private val capacity: Int = 4096,
) {
    private data class Entry(val fingerprint: String, val response: JsonObject? = null)
    private data class Decision(val response: JsonObject? = null, val wait: CompletableDeferred<JsonObject>? = null, val execute: Boolean = false)
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    init { require(capacity in 1..4096) }

    suspend fun run(
        ownerWatchId: String,
        requestId: Int,
        kind: String,
        request: JsonObject,
        block: suspend () -> JsonObject,
    ): JsonObject {
        if (ownerWatchId.isBlank() || ownerWatchId.encodeToByteArray().size > 512 || requestId <= 0 || kind !in KINDS ||
            !boundedDepth(request, 0) || request.toString().encodeToByteArray().size > MAX_REQUEST_BYTES ||
            (request["request_id"] != null && (request["request_id"] as? JsonPrimitive)?.intOrNull != requestId) ||
            (request["kind"] != null && (request["kind"] as? JsonPrimitive)?.contentOrNull != kind)) {
            return refusal(request, "Invalid Home request. No action was sent.")
        }
        val key = signalHomeReplayDigest(buildJsonArray { add(ownerWatchId); add(requestId); add(kind) }.toString())
        val fingerprint = signalHomeReplayDigest(canonical(request).toString())
        val completion = CompletableDeferred<JsonObject>()
        val decision = try {
            mutex.withLock {
                val entries = read()
                val previous = entries[key]
                when {
                    previous != null && previous.fingerprint != fingerprint -> Decision(response = refusal(request, "This request ID already belongs to a different action. No action was sent."))
                    previous?.response != null -> Decision(response = previous.response)
                    previous != null -> Decision(response = if (inFlight[key] == null) unknown(request) else null, wait = inFlight[key])
                    entries.size >= capacity -> Decision(response = refusal(request, "Home request history is full. Review it on the phone before sending new actions."))
                    else -> {
                        entries[key] = Entry(fingerprint)
                        write(entries) // Reservation MUST be durable before block can run.
                        inFlight[key] = completion
                        Decision(execute = true)
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return unknown(request) }
        decision.response?.let { return it }
        decision.wait?.let { return it.await() }
        check(decision.execute)
        var answer = unknown(request)
        try {
            currentCoroutineContext().ensureActive()
            val result = block()
            if (!boundedDepth(result, 0) || result.toString().encodeToByteArray().size > MAX_RESULT_BYTES) return answer
            mutex.withLock {
                val entries = read()
                val reserved = entries[key]
                check(reserved != null && reserved.fingerprint == fingerprint && reserved.response == null) { "Replay reservation changed" }
                entries[key] = Entry(fingerprint, result)
                write(entries) // A result is replayable only after durable completion.
            }
            answer = result
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Retain reservation: side effect may have happened. */ }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    completion.complete(answer)
                    inFlight.remove(key)
                }
            }
        }
        return answer
    }

    private suspend fun read(): MutableMap<String, Entry> {
        val stored = get(STORE_KEY) ?: return linkedMapOf()
        require(stored.length <= 4096 * (MAX_RESULT_BYTES + 512)) { "Replay journal too large" }
        val root = Json.parseToJsonElement(stored).jsonObject
        require(root["version"]?.jsonPrimitive?.intOrNull == 1)
        val rows = root["entries"]?.jsonObject ?: error("Missing replay entries")
        require(rows.size <= 4096)
        return rows.mapValues { (key, value) ->
            require(key.length == 64 && key.all { it in "0123456789abcdef" })
            val row = value.jsonObject
            val hash = row["hash"]?.jsonPrimitive?.content ?: error("Missing fingerprint")
            require(hash.length == 64 && hash.all { it in "0123456789abcdef" })
            val state = row["state"]?.jsonPrimitive?.content
            require(state == "reserved" || state == "done")
            val response = if (state == "done") row["response"]?.jsonObject ?: error("Missing result") else null
            require(response == null || response.toString().encodeToByteArray().size <= MAX_RESULT_BYTES)
            Entry(hash, response)
        }.toMutableMap()
    }

    private suspend fun write(entries: Map<String, Entry>) = put(STORE_KEY, buildJsonObject {
        put("version", 1)
        put("entries", buildJsonObject {
            entries.forEach { (key, entry) -> put(key, buildJsonObject {
                put("hash", entry.fingerprint)
                put("state", if (entry.response == null) "reserved" else "done")
                entry.response?.let { put("response", it) }
            }) }
        })
    }.toString())

    private fun unknown(request: JsonObject) = terminal(request, "unknown", "Outcome unknown. Check the device before trying again.")
    private fun refusal(request: JsonObject, message: String) = terminal(request, "refused", message)
    private fun terminal(request: JsonObject, outcome: String, message: String) = buildJsonObject {
        val favorite = (request["favorite_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        put("mode", "result")
        put("favorite_id", if (favorite.encodeToByteArray().size <= 64 && favorite.none { it.code < 32 || it.code == 127 }) favorite else "")
        put("text", message)
        put("outcome", outcome)
    }
    private fun boundedDepth(value: JsonElement, depth: Int): Boolean = depth <= 16 && when (value) {
        is JsonObject -> value.values.all { boundedDepth(it, depth + 1) }
        is JsonArray -> value.all { boundedDepth(it, depth + 1) }
        else -> true
    }
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    companion object {
        private const val STORE_KEY = "signal-home-watch-replay-v1"
        private const val MAX_REQUEST_BYTES = 8192
        private const val MAX_RESULT_BYTES = 4096
        private val KINDS = setOf("home-list", "home-open", "home-review", "home-confirm", "home-cancel", "home-phone")
    }
}

// Portable SHA-256 for opaque journal keys/fingerprints; encryption is the store's job.
internal fun signalHomeReplayDigest(text: String): String {
    val input = text.encodeToByteArray()
    val bytes = ByteArray((input.size + 9 + 63) / 64 * 64)
    input.copyInto(bytes); bytes[input.size] = 0x80.toByte()
    val bits = input.size.toLong() * 8
    for (i in 0..7) bytes[bytes.lastIndex - i] = (bits ushr (8 * i)).toByte()
    val h = intArrayOf(0x6a09e667, 0xbb67ae85u.toInt(), 0x3c6ef372, 0xa54ff53au.toInt(), 0x510e527f, 0x9b05688cu.toInt(), 0x1f83d9ab, 0x5be0cd19)
    val k = intArrayOf(
        0x428a2f98,0x71374491,0xb5c0fbcfu.toInt(),0xe9b5dba5u.toInt(),0x3956c25b,0x59f111f1,0x923f82a4u.toInt(),0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(),0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1feu.toInt(),0x9bdc06a7u.toInt(),0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(),0xefbe4786u.toInt(),0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152u.toInt(),0xa831c66du.toInt(),0xb00327c8u.toInt(),0xbf597fc7u.toInt(),0xc6e00bf3u.toInt(),0xd5a79147u.toInt(),0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92eu.toInt(),0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(),0xa81a664bu.toInt(),0xc24b8b70u.toInt(),0xc76c51a3u.toInt(),0xd192e819u.toInt(),0xd6990624u.toInt(),0xf40e3585u.toInt(),0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814u.toInt(),0x8cc70208u.toInt(),0x90befffau.toInt(),0xa4506cebu.toInt(),0xbef9a3f7u.toInt(),0xc67178f2u.toInt())
    fun rotate(v: Int, n: Int) = (v ushr n) or (v shl (32 - n))
    val w = IntArray(64)
    for (offset in bytes.indices step 64) {
        for (i in 0..15) { val p = offset + i * 4; w[i] = ((bytes[p].toInt() and 255) shl 24) or ((bytes[p+1].toInt() and 255) shl 16) or ((bytes[p+2].toInt() and 255) shl 8) or (bytes[p+3].toInt() and 255) }
        for (i in 16..63) { val x = w[i-15]; val y = w[i-2]; w[i] = w[i-16] + (rotate(x,7) xor rotate(x,18) xor (x ushr 3)) + w[i-7] + (rotate(y,17) xor rotate(y,19) xor (y ushr 10)) }
        var a=h[0];var b=h[1];var c=h[2];var d=h[3];var e=h[4];var f=h[5];var g=h[6];var z=h[7]
        for (i in 0..63) {
            val t1=z+(rotate(e,6) xor rotate(e,11) xor rotate(e,25))+((e and f) xor (e.inv() and g))+k[i]+w[i]
            val t2=(rotate(a,2) xor rotate(a,13) xor rotate(a,22))+((a and b) xor (a and c) xor (b and c))
            z=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=z
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}
