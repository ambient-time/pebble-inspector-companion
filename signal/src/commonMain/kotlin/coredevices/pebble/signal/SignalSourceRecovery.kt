package coredevices.pebble.signal

object SignalSourceRecovery {
    fun remedy(status: String): String = when (status) {
        "permission_denied" -> "grant_permission"
        "location_services_disabled", "radio_disabled" -> "open_settings"
        "not_sampled", "cached", "timeout", "unavailable", "no_samples", "invalid_samples" -> "retry"
        else -> "none"
    }
    fun reason(status: String): String = when (status) {
        "permission_denied" -> "Permission is needed for this source."
        "location_services_disabled" -> "Android location services are off."
        "radio_disabled" -> "The radio is off."
        "hardware_unavailable" -> "This source is unavailable on this phone."
        "cached", "passive_cached" -> "Previously measured readings; check their age."
        "timeout" -> "The collection window ended before a reading arrived."
        "rate_limited", "deferred" -> "Waiting before another request to conserve battery and respect Android limits."
        "no_samples" -> "No sensor events arrived during the collection window."
        "invalid_samples" -> "The sensor did not return a usable measurement."
        "not_sampled" -> "No saved reading yet."
        else -> ""
    }
}
