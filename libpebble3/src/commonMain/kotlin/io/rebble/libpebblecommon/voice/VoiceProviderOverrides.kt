package io.rebble.libpebblecommon.voice

import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic

/** The host may override one installed watchapp's recognition without changing other callers. */
object VoiceProviderOverrides {
    private val resolver = atomic<(Uuid) -> TranscriptionProvider?>({ null })
    var resolve: (Uuid) -> TranscriptionProvider?
        get() = resolver.value
        set(value) { resolver.value = value }

    fun select(appUuid: Uuid, default: TranscriptionProvider): TranscriptionProvider = resolve(appUuid) ?: default
}
