package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.endpointmanager.audio.VoiceSessionManager
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.SessionSetupCommand
import io.rebble.libpebblecommon.packets.VoiceAttribute
import io.rebble.libpebblecommon.packets.VoiceAttributeType
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.AudioStreamService
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

@OptIn(ExperimentalUnsignedTypes::class)
class VoiceSessionCancellationTest {
    @Test fun disconnectClearsPublishedSessionAndCancelsItsDeferred() = runBlocking {
        withTimeout(5000) {
            val incoming = MutableSharedFlow<PebblePacket>()
            val protocol = object : PebbleProtocolHandler {
                override val inboundMessages = incoming
                override val rawInboundMessages = emptyFlow<ByteArray>()
                override suspend fun send(message: PebblePacket, priority: PacketPriority) = Unit
                override suspend fun send(message: ByteArray, priority: PacketPriority) = Unit
            }
            val active = CompletableDeferred<Unit>()
            val provider = object : TranscriptionProvider {
                override suspend fun canServeSession() = true
                override suspend fun transcribe(encoderInfo: VoiceEncoderInfo, audioFrames: Flow<UByteArray>, isNotificationReply: Boolean): TranscriptionResult {
                    active.complete(Unit)
                    awaitCancellation()
                }
            }
            val connection = Job()
            val manager = VoiceSessionManager(VoiceService(protocol), AudioStreamService(protocol),
                ConnectionCoroutineScope(coroutineContext + connection), provider)
            try {
                manager.init()
                incoming.subscriptionCount.first { it > 0 }
                val info = VoiceAttribute.SpeexEncoderInfo().apply {
                    sampleRate.set(16000u); frameSize.set(320u); bitRate.set(8000u)
                }
                val attribute = VoiceAttribute(VoiceAttributeType.SpeexEncoderInfo.value, info).toBytes()
                // Command, flags (4), session type, id (2), attribute count, then attributes.
                val packet = SessionSetupCommand().apply {
                    m.fromBytes(DataBuffer(ubyteArrayOf(1u, 0u, 0u, 0u, 0u, 1u, 1u, 0u, 1u) + attribute))
                }
                incoming.emit(packet)
                active.await()
                val session = assertNotNull(manager.currentSession.value)
                connection.cancelAndJoin()
                assertNull(manager.currentSession.value)
                assertTrue(session.result.isCancelled)
            } finally { connection.cancelAndJoin() }
        }
    }
}
