package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import java.io.Writer

/** One instance per engine. Archive writes and the bounded active projection
 * commit together using the existing encrypted document table and transaction. */
class SignalHomeStore(private val store: SignalStore, private val clock: () -> Long, private val changed: (HomeState) -> Unit = {}) : HomePersistence {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pinned = mutableMapOf<String, Int>()
    override suspend fun load(): HomeState = store.documentLarge("home:v1")?.let { json.decodeFromString<HomeState>(it) } ?: HomeState()
    override suspend fun save(state: HomeState) {
        val projection = SignalHomeRetention.project(state, clock(), pinned.keys)
        store.atomic {
            projection.archived.forEach { entry ->
                store.document("home-audit:${entry.action.id}", ARCHIVE_KIND, json.encodeToString(entry))
            }
            store.document("home:v1", "home", json.encodeToString(projection.state))
        }
        changed(projection.state)
    }
    override suspend fun archived(actionId: String): HomeLedgerEntry? = store.document("home-audit:$actionId")?.let { json.decodeFromString<HomeLedgerEntry>(it) }
    override fun pin(actionId: String) { pinned[actionId] = (pinned[actionId] ?: 0) + 1 }
    override fun unpin(actionId: String) { val count = pinned[actionId] ?: return; if (count == 1) pinned.remove(actionId) else pinned[actionId] = count - 1 }

    /** Explicit Home-only export. The read transaction gives a consistent snapshot
     * without loading the complete archive or credentials into memory. */
    suspend fun export(writer: Writer): Long {
        var count = 0L
        store.atomic {
            val current = load()
            writer.write("{\"schemaVersion\":1,\"kind\":\"signal-home-action-audit\",\"actions\":[")
            store.walkDocuments(ARCHIVE_KIND) { _, value ->
                val entry = json.decodeFromString<HomeLedgerEntry>(value)
                if (count++ > 0) writer.write(",")
                writer.write(json.encodeToString(entry))
            }
            current.ledger.forEach { entry ->
                if (count++ > 0) writer.write(",")
                writer.write(json.encodeToString(entry))
            }
            writer.write("],\"actionCount\":$count}")
        }
        return count
    }
    companion object { const val ARCHIVE_KIND = "home_action_audit" }
}
