package coredevices.pebble.signal

import kotlinx.serialization.Serializable

@Serializable enum class HomeConnectorKind { HOME_ASSISTANT, OPENHAB, GEEPERS }
@Serializable data class HomeConnection(val id: String, val name: String, val kind: HomeConnectorKind, val baseUrl: String, val credentialKey: String = "home:$id", val allowPrivateHttp: Boolean = false, val enabled: Boolean = true, val revision: Long = 1)
@Serializable data class HomeParameter(val name: String, val type: String = "string", val required: Boolean = true, val minimum: Double? = null, val maximum: Double? = null, val options: List<String> = emptyList())
@Serializable data class HomeCapability(val id: String, val name: String = id, val parameters: List<HomeParameter> = emptyList(), val expectedValues: Map<String, String> = emptyMap())
@Serializable data class HomeValue(val key: String, val value: String, val unit: String? = null, val measuredAt: Long? = null)
@Serializable data class HomeEntity(val connectionId: String, val id: String, val name: String, val domain: String = "", val state: String = "unknown", val available: Boolean = false, val capabilities: List<HomeCapability> = emptyList(), val updatedAt: Long? = null, val observedAt: Long = 0, val attributes: Map<String, String> = emptyMap(), val identity: String = id, val values: List<HomeValue> = emptyList())
@Serializable data class HomeAction(val id: String, val connectionId: String, val entityId: String, val capabilityId: String, val parameters: Map<String, String> = emptyMap(), val createdAt: Long, val identity: String = entityId, val connectionBinding: String = "", val expectedValues: Map<String, String> = emptyMap(), val capabilityBinding: String = "")
@Serializable data class HomeTile(val id: String, val connectionId: String, val entityId: String, val title: String, val capabilityId: String? = null, val parameters: Map<String, String> = emptyMap(), val position: Int = 0, val room: String = "", val watchFavorite: Boolean = false)
@Serializable data class HomeGrant(val id: String, val connectionId: String, val entityId: String, val capabilityId: String, val constraints: Map<String, String> = emptyMap(), val createdAt: Long, val expiresAt: Long? = null, val enabled: Boolean = true, val identity: String = entityId, val connectionBinding: String = "", val capabilityBinding: String = "")
@Serializable enum class HomeActionStatus { AWAITING_CONFIRMATION, READY, SENDING, ACCEPTED, OBSERVED, FAILED, UNKNOWN, CANCELLED, EXPIRED }
@Serializable data class HomeLedgerEntry(val action: HomeAction, val status: HomeActionStatus, val updatedAt: Long, val confirmationExpiresAt: Long, val confirmedAt: Long? = null, val grantId: String? = null, val sentAt: Long? = null, val receiptId: String? = null, val message: String = "", val cancelRequested: Boolean = false)
@Serializable data class HomeSnapshot(val entities: List<HomeEntity> = emptyList(), val collectedAt: Long = 0, val errors: Map<String, String> = emptyMap())
@Serializable data class HomeTarget(val connectionId: String, val entityId: String)
fun homeTargetKey(connectionId: String, entityId: String): String = "${connectionId.length}:$connectionId$entityId"
@Serializable data class HomeState(val connections: List<HomeConnection> = emptyList(), val tiles: List<HomeTile> = emptyList(), val grants: List<HomeGrant> = emptyList(), val ledger: List<HomeLedgerEntry> = emptyList(), val snapshot: HomeSnapshot = HomeSnapshot(), val captureTargets: List<HomeTarget> = emptyList())
data class HomeDispatchResult(val status: HomeActionStatus = HomeActionStatus.ACCEPTED, val receiptId: String? = null, val message: String = "Controller accepted the request; physical outcome is unverified.")
/** Implement with atomic encrypted persistence. A successful save must be durable before returning. */
interface HomePersistence { suspend fun load(): HomeState; suspend fun save(state: HomeState) }
interface HomeConnector {
    val warnings: List<String> get() = emptyList()
    /** Collect only while the Home UI is foreground; cancellation closes the live transport. */
    fun foregroundEvents(): kotlinx.coroutines.flow.Flow<HomeEntity> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun catalog(): List<HomeEntity>
    suspend fun read(entityId: String): HomeEntity? = catalog().firstOrNull { it.id == entityId }
    suspend fun execute(action: HomeAction): HomeDispatchResult
    suspend fun receipt(id: String): HomeDispatchResult? = null
    fun close() {}
}
class HomeException(message: String) : Exception(message)
fun homeCapabilityBinding(capability: HomeCapability): String = kotlinx.serialization.json.Json.encodeToString(capability.copy(parameters=capability.parameters.sortedBy { it.name }, expectedValues=capability.expectedValues.entries.sortedBy { it.key }.associate { it.toPair() }))
fun homeConnectionBinding(c: HomeConnection): String = kotlinx.serialization.json.Json.encodeToString(listOf(c.id, c.kind.name, c.baseUrl.trim().trimEnd('/'), c.credentialKey, c.allowPrivateHttp.toString(), c.revision.toString()))
fun normalizeHomeParameters(capability: HomeCapability, supplied: Map<String, String>): Map<String, String> {
    require(supplied.size <= 20 && capability.parameters.size <= 20) { "Too many parameters." }
    require(supplied.keys.all { key -> capability.parameters.any { it.name == key } }) { "Unsupported parameter." }
    return capability.parameters.mapNotNull { spec ->
        val raw = supplied[spec.name]
        if (raw == null) { require(!spec.required) { "Missing ${spec.name}." }; null } else {
            require(raw.length <= 512) { "Parameter is too long." }
            val normalized = when (spec.type) {
                "number", "integer" -> {
                    val n = raw.toDoubleOrNull(); require(n != null && n.isFinite()) { "Invalid number." }
                    require(spec.minimum == null || n >= spec.minimum); require(spec.maximum == null || n <= spec.maximum)
                    if (spec.type == "integer") { require(n % 1.0 == 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE); n.toInt().toString() } else n.toString()
                }
                "hsb" -> { val parts = raw.split(',').map { it.trim().toDoubleOrNull() }; require(parts.size == 3 && parts.all { it != null && it.isFinite() }); require(parts[0]!! in 0.0..360.0 && parts[1]!! in 0.0..100.0 && parts[2]!! in 0.0..100.0); parts.joinToString(",") { it.toString() } }
                "boolean" -> { require(raw.lowercase() in listOf("true", "false")); raw.lowercase() }
                else -> raw.trim()
            }
            require(spec.options.isEmpty() || normalized in spec.options) { "Unsupported value." }
            spec.name to normalized
        }
    }.sortedBy { it.first }.toMap()
}
