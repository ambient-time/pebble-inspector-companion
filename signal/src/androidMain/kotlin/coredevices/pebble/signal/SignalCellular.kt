package coredevices.pebble.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.*
import kotlinx.coroutines.*
import java.util.concurrent.Executor

/** Public Android cell observations only; no subscriber identifiers or modem commands. */
internal class SignalCellular(private val context: Context) {
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    @Suppress("DEPRECATION")
    suspend fun collect(enabled: Set<String>): List<SignalObservation> {
        val keys = enabled.filter { it == "cellular" || it == "cellular.identifiers" }
        if (keys.isEmpty()) return emptyList()
        fun missing(status: String) = keys.map { SignalObservation(it, "phone", collectedAt = System.currentTimeMillis(), status = status) }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return missing("hardware_unavailable")
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return missing("permission_denied")
        val locations = context.getSystemService(android.location.LocationManager::class.java)
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) locations.isLocationEnabled else locations.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || locations.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!locationEnabled) return missing("location_services_disabled")
        val base = context.getSystemService(TelephonyManager::class.java) ?: return missing("hardware_unavailable")
        val subscriptions = if (granted(Manifest.permission.READ_PHONE_STATE)) runCatching {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().take(4)
        }.getOrDefault(emptyList()) else emptyList()
        val managers = if (subscriptions.isEmpty()) listOf(Triple(base, "default", "Default subscription; additional SIM context may require phone-state access"))
            else subscriptions.map { Triple(base.createForSubscriptionId(it.subscriptionId), it.subscriptionId.toString(), "SIM ${it.simSlotIndex + 1}") }
        data class Update(val subscription: String, val label: String, val rows: List<CellInfo>, val failure: String)
        val updates = coroutineScope {
            managers.map { (manager, subscription, label) -> async {
                try {
                    val rows = if (Build.VERSION.SDK_INT >= 29) {
                        val result = CompletableDeferred<List<CellInfo>>()
                        manager.requestCellInfoUpdate(Executor { it.run() }, object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) { result.complete(cellInfo.toList()) }
                            override fun onError(errorCode: Int, detail: Throwable?) { result.complete(emptyList()) }
                        })
                        withTimeoutOrNull(8_000) { result.await() }.orEmpty()
                    } else emptyList()
                    Update(subscription, label, rows, "unavailable")
                } catch (error: CancellationException) { throw error }
                catch (_: SecurityException) { Update(subscription, label, emptyList(), "permission_denied") }
                catch (_: Exception) { Update(subscription, label, emptyList(), "unavailable") }
            } }.awaitAll()
        }
        fun retain(all: List<CellInfo>, subscription: String, label: String): List<SignalObservation> {
            val selected = all.sortedByDescending { it.isRegistered }.take(16)
            return selected.flatMapIndexed { index, cell -> readings(cell, keys, subscription, label, index) } + keys.map {
                SignalObservation(it, "phone", "${selected.size} retained cell observations for $label; ${all.size - selected.size} omitted", collectedAt = System.currentTimeMillis(),
                    status = if (all.size > selected.size) "partial" else "available", metric = "coverage",
                    fields = mapOf("retained" to selected.size.toString(), "omitted" to (all.size - selected.size).toString()))
            }
        }
        val successful = updates.filter { it.rows.isNotEmpty() }
        if (successful.isNotEmpty()) return successful.flatMap { retain(it.rows, it.subscription, it.label) } +
            updates.filter { it.rows.isEmpty() }.flatMap { update -> missing(update.failure).map { it.copy(value = "${update.label}: ${update.failure}") } }
        // Android getAllCellInfo is device-wide, even on a subscription-specific manager.
        // Read it once and never invent per-SIM ownership for its cached cells.
        return try {
            val cached = base.allCellInfo.orEmpty()
            if (cached.isEmpty()) missing(if (base.simState == TelephonyManager.SIM_STATE_ABSENT) "no_sim" else "unavailable")
            else retain(cached, "unknown", "Device-wide cache; SIM attribution unavailable")
        } catch (_: SecurityException) { missing("permission_denied") }
        catch (_: Exception) { missing("unavailable") }
    }

    @Suppress("DEPRECATION")
    private fun readings(cell: CellInfo, keys: List<String>, subscription: String, label: String, index: Int): List<SignalObservation> {
        val fields = linkedMapOf("subscription" to label, "subscriptionId" to subscription, "slot" to "$subscription:$index", "registered" to cell.isRegistered.toString())
        val identifiers = linkedMapOf<String, String>()
        fun identity(radio: String, mcc: String?, mnc: String?, area: Int, id: Long) {
            fields["radioType"] = radio
            mcc?.takeIf { it.matches(Regex("[0-9]{3}")) }?.let { fields["mcc"] = it; identifiers["mcc"] = it }
            mnc?.takeIf { it.matches(Regex("[0-9]{2,3}")) }?.let { fields["mnc"] = it; identifiers["mnc"] = it }
            if (area != CellInfo.UNAVAILABLE && area >= 0) identifiers["locationAreaCode"] = area.toString()
            if (id != Long.MAX_VALUE && id != Int.MAX_VALUE.toLong() && id >= 0) identifiers["cellId"] = id.toString()
            identifiers["radioType"] = radio
        }
        fun metric(name: String, value: Int) { if (value != CellInfo.UNAVAILABLE) fields[name] = value.toString() }
        val dbm: Int = when (cell) {
            is CellInfoLte -> {
                val c = cell.cellIdentity
                identity("lte", if (Build.VERSION.SDK_INT >= 28) c.mccString else c.mcc.toString(), if (Build.VERSION.SDK_INT >= 28) c.mncString else c.mnc.toString(), c.tac, c.ci.toLong())
                metric("rsrpDbm", cell.cellSignalStrength.rsrp); metric("rsrqDb", cell.cellSignalStrength.rsrq)
                cell.cellSignalStrength.dbm
            }
            is CellInfoGsm -> {
                val c = cell.cellIdentity
                identity("gsm", if (Build.VERSION.SDK_INT >= 28) c.mccString else c.mcc.toString(), if (Build.VERSION.SDK_INT >= 28) c.mncString else c.mnc.toString(), c.lac, c.cid.toLong())
                cell.cellSignalStrength.dbm
            }
            is CellInfoWcdma -> {
                val c = cell.cellIdentity
                identity("wcdma", if (Build.VERSION.SDK_INT >= 28) c.mccString else c.mcc.toString(), if (Build.VERSION.SDK_INT >= 28) c.mncString else c.mnc.toString(), c.lac, c.cid.toLong())
                cell.cellSignalStrength.dbm
            }
            is CellInfoCdma -> {
                fields["radioType"] = "cdma"
                val c = cell.cellIdentity
                if (c.basestationId >= 0 && c.basestationId != CellInfo.UNAVAILABLE) identifiers["baseStationId"] = c.basestationId.toString()
                if (c.networkId >= 0 && c.networkId != CellInfo.UNAVAILABLE) identifiers["networkId"] = c.networkId.toString()
                if (c.systemId >= 0 && c.systemId != CellInfo.UNAVAILABLE) identifiers["systemId"] = c.systemId.toString()
                cell.cellSignalStrength.dbm
            }
            else -> if (Build.VERSION.SDK_INT >= 29 && cell is CellInfoNr) {
                val c = cell.cellIdentity as CellIdentityNr
                identity("nr", c.mccString, c.mncString, c.tac, c.nci)
                val signal = cell.cellSignalStrength as CellSignalStrengthNr
                metric("ssRsrpDbm", signal.ssRsrp); metric("ssRsrqDb", signal.ssRsrq); metric("ssSinrDb", signal.ssSinr)
                signal.dbm
            } else CellInfo.UNAVAILABLE
        }
        val elapsed = if (Build.VERSION.SDK_INT >= 30) cell.timestampMillis else cell.timeStamp / 1_000_000
        val age = SystemClock.elapsedRealtime() - elapsed
        val now = System.currentTimeMillis()
        val at = if (elapsed > 0 && age >= 0) now - age else null
        val status = if (at == null) "unknown" else if (age <= 30_000) "fresh" else "cached"
        val signal = dbm.takeIf { it != CellInfo.UNAVAILABLE && it in -200..0 }
        return buildList {
            if ("cellular" in keys) add(SignalObservation("cellular", "phone", "$label · ${fields["radioType"] ?: "unsupported technology"} · ${if (cell.isRegistered) "registered" else "neighbor"} · ${signal?.let { "$it dBm" } ?: "signal unknown"}",
                "dBm", now, at, if (signal == null) "unavailable" else status, number = signal?.toDouble(), metric = "signal", fields = fields))
            if ("cellular.identifiers" in keys) add(SignalObservation("cellular.identifiers", "phone", identifiers.entries.joinToString { "${it.key}=${it.value}" },
                collectedAt = now, measuredAt = at, status = status, fields = identifiers + mapOf("slot" to "$subscription:$index", "registered" to cell.isRegistered.toString())))
        }
    }
}
