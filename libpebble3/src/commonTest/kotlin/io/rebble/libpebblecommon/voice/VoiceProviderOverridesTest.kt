package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.SystemAppIDs
import kotlinx.coroutines.flow.Flow
import kotlin.test.*
import kotlin.uuid.Uuid

class VoiceProviderOverridesTest {
    @OptIn(ExperimentalUnsignedTypes::class)
    private class Provider : TranscriptionProvider {
        override suspend fun canServeSession() = true
        override suspend fun transcribe(encoderInfo: VoiceEncoderInfo, audioFrames: Flow<UByteArray>, isNotificationReply: Boolean) = TranscriptionResult.Disabled
    }

    @Test fun onlyAuthorizedAppUsesOverrideAndStockRestoresWithoutFallback() {
        val original = VoiceProviderOverrides.resolve
        val app = Uuid.parse("e2fd86ec-dfb8-460c-afc1-ebe4d071657a")
        val stock = Provider()
        val selected = Provider()
        try {
            VoiceProviderOverrides.resolve = { if (it == app) selected else null }
            assertSame(selected, VoiceProviderOverrides.select(app, stock))
            assertSame(stock, VoiceProviderOverrides.select(Uuid.NIL, stock))
            assertSame(stock, VoiceProviderOverrides.select(SystemAppIDs.NOTIFICATIONS_APP_UUID, stock))
            assertSame(stock, VoiceProviderOverrides.select(Uuid.parse("e2fd86ec-dfb8-460c-afc1-ebe4d071657b"), stock))
            VoiceProviderOverrides.resolve = { null }
            assertSame(stock, VoiceProviderOverrides.select(app, stock))
        } finally { VoiceProviderOverrides.resolve = original }
    }
}
