package coredevices.pebble.signal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.changes.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.Duration
import java.time.ZoneId

/** Read-only import. Provider records remain separate by origin and interval; never sum overlapping sources. */
internal class SignalHealthConnect(private val context: Context, private val store: SignalStore) {
    private val client get() = HealthConnectClient.getOrCreate(context)
    fun availability(): String = when {
        Build.VERSION.SDK_INT < 28 -> "Health Connect requires Android 9 or later."
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE -> "Health Connect is available. Choose data types, review access, then import."
        else -> "Install or update Health Connect in Android settings before importing."
    }
    fun sources() = types.keys.map { SignalSource(it, it.removePrefix("healthconnect.").replace('_', ' ').replaceFirstChar(Char::uppercase), "Health Connect", Build.VERSION.SDK_INT >= 28 && HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) }
    fun permissions(enabled: Set<String>, days: Int, background: Boolean): Set<String> {
        if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        return buildSet {
            types.filterKeys { it in enabled }.values.forEach { add(HealthPermission.getReadPermission(it)) }
            if (isNotEmpty() && days > 30 && feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
            if (isNotEmpty() && background && feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }
    private fun feature(id: Int) = client.features.getFeatureStatus(id) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    suspend fun sync(settings: SignalSettings, background: Boolean, upsert: suspend (SignalRecord) -> Unit, remove: suspend (String) -> Unit): String {
        if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return availability()
        val granted = client.permissionController.getGrantedPermissions()
        if (background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) return "Background health access is off. Import manually while Signal Station is open."
        val selected = types.filter { (key, type) -> key in settings.enabled && HealthPermission.getReadPermission(type) in granted }
        if (selected.isEmpty()) return "No selected health types have read access. Use Review health access."
        val days = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) settings.healthHistoryDays else settings.healthHistoryDays.coerceAtMost(30)
        val end = Instant.now(); val start = end.minus(Duration.ofDays(days.toLong()))
        var imported = 0
        suspend fun ingest(key: String, row: Record) {
            val interval = healthInterval(row) ?: return
            if (interval.endTime < start || interval.endTime > end) return
            val id = "hc:${store.opaqueIndex(row.metadata.id)}"
            if (store.document("deleted:$id") != null) return
            val record = mapHealthRecord(key, row, id, end.toEpochMilli()) ?: return
            upsert(record); imported++
        }
        for ((key, type) in selected) {
            currentCoroutineContext().ensureActive()
            val checkpoint = "health-token:${store.opaqueIndex("$key:$days")}"
            var token = store.document(checkpoint)
            if (token != null && client.getChanges(token).changesTokenExpired) token = null
            if (token == null) {
                // Capture a token before the snapshot so concurrent edits are replayed afterward.
                token = client.getChangesToken(ChangesTokenRequest(setOf(type)))
                val seen = mutableSetOf<String>(); var pageToken: String? = null
                do {
                    val response = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 200, pageToken = pageToken))
                    for (row in response.records) { seen += "hc:${store.opaqueIndex(row.metadata.id)}"; ingest(key, row) }
                    pageToken = response.pageToken
                    yield()
                } while (!pageToken.isNullOrEmpty())
                // An expired token requires reconciliation, including deletions in this requested window.
                val missing = mutableListOf<String>()
                store.walk { _, row -> if (row.kind == "health_import" && key in row.sourceKeys && row.observations.all { (it.windowStart ?: Long.MIN_VALUE) >= start.toEpochMilli() && (it.windowEnd ?: Long.MAX_VALUE) <= end.toEpochMilli() } && row.id !in seen) missing += row.id }
                missing.forEach { remove(it) }
            }
            do {
                val changes = client.getChanges(token!!)
                if (changes.changesTokenExpired) throw IllegalStateException("Health change token expired during import")
                for (change in changes.changes) when (change) {
                    is UpsertionChange -> ingest(key, change.record)
                    is DeletionChange -> remove("hc:${store.opaqueIndex(change.recordId)}")
                }
                token = changes.nextChangesToken
                store.document(checkpoint, "health_checkpoint", token)
            } while (changes.hasMore)
        }
        return "Health import complete: $imported updates across ${selected.size} permitted types; requested window $days days. Origins and intervals remain separate."
    }
    companion object {
        val types: Map<String, kotlin.reflect.KClass<out Record>> = mapOf("healthconnect.steps" to StepsRecord::class, "healthconnect.distance" to DistanceRecord::class,
            "healthconnect.active_calories" to ActiveCaloriesBurnedRecord::class, "healthconnect.total_calories" to TotalCaloriesBurnedRecord::class,
            "healthconnect.exercise" to ExerciseSessionRecord::class, "healthconnect.sleep" to SleepSessionRecord::class, "healthconnect.heart_rate" to HeartRateRecord::class)
    }
}

internal data class HealthInterval(val startTime: Instant, val endTime: Instant, val endZoneOffset: java.time.ZoneOffset?)
internal fun healthInterval(row: Record): HealthInterval? = when (row) {
    is StepsRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is DistanceRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is ActiveCaloriesBurnedRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is TotalCaloriesBurnedRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is ExerciseSessionRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is SleepSessionRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    is HeartRateRecord -> HealthInterval(row.startTime, row.endTime, row.endZoneOffset)
    else -> null
}

internal fun mapHealthRecord(key: String, row: Record, id: String, importedAt: Long): SignalRecord? {
    val interval = healthInterval(row) ?: return null
    val start = interval.startTime.toEpochMilli(); val end = interval.endTime.toEpochMilli()
    if (end <= start || end > importedAt) return null
    val (number, unit) = when (row) {
        is StepsRecord -> row.count.toDouble() to "steps"
        is DistanceRecord -> row.distance.inMeters to "m"
        is ActiveCaloriesBurnedRecord -> row.energy.inKilocalories to "kcal"
        is TotalCaloriesBurnedRecord -> row.energy.inKilocalories to "kcal"
        is ExerciseSessionRecord -> (end - start) / 60_000.0 to "session minutes"
        is SleepSessionRecord -> (end - start) / 60_000.0 to "sleep session minutes"
        is HeartRateRecord -> row.samples.map { it.beatsPerMinute.toDouble() }.average() to "bpm interval mean"
        else -> return null
    }
    if (!number.isFinite()) return null
    val origin = row.metadata.dataOrigin.packageName
    val observation = SignalObservation(key, "health_connect:$origin", number.toString(), unit, importedAt, end, "recorded",
        interval.endTime.atZone(interval.endZoneOffset ?: ZoneId.systemDefault()).toLocalDate().toString(), "interval:${end - start}", start, end, identity = origin, id = "$id:0", number = number)
    val summary = "${key.removePrefix("healthconnect.").replace('_', ' ')}: $number $unit. Origin: $origin. Interval: ${interval.startTime} to ${interval.endTime}. Separate observation; overlapping origins must not be added."
    return SignalRecord(id, "health-import", end, "Health Connect reading", summary, summary, "local", "", state = "ready", observations = listOf(observation), sourceKeys = setOf(key), kind = "health_import", sessionId = id)
}

class SignalHealthPermissionActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launcher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (!signalPackageEnabled(packageName)) { finish(); return }
        activityScope.launch {
            try {
                val permissionStore = SignalStore(this@SignalHealthPermissionActivity)
                val permissions = try { SignalHealthConnect(this@SignalHealthPermissionActivity, permissionStore).permissions(intent.getStringArrayExtra("sources").orEmpty().toSet(), intent.getIntExtra("days", 7), intent.getBooleanExtra("background", false)) } finally { permissionStore.close() }
                if (permissions.isEmpty()) finish() else launcher.launch(permissions)
            } catch (_: Exception) { finish() }
        }
    }
    override fun onDestroy() { activityScope.cancel(); super.onDestroy() }
}

class SignalHealthRationaleActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = "Signal Station reads only the health types you choose. Imported readings are encrypted on this phone and stay here until deleted. A language model receives health context only when you explicitly send a request with those sources enabled. Signal Station does not write health records. Manage access in Health Connect; delete local copies in Activity and Memory."
            setPadding(40, 80, 40, 40); textSize = 18f
        })
    }
}
