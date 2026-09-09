package coredevices.pebble.signal

import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import io.rebble.libpebblecommon.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Only selected by the native caller-UUID override; ordinary watch dictation stays upstream. */
class SignalWatchTranscription(
    private val providers: SignalProviders,
    private val enabled: suspend () -> Boolean,
) : TranscriptionProvider {
    override suspend fun canServeSession(): Boolean = enabled() && providers.transcriptionConfigured()

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun transcribe(encoderInfo: VoiceEncoderInfo, audioFrames: Flow<UByteArray>, isNotificationReply: Boolean): TranscriptionResult {
        if (!canServeSession()) return TranscriptionResult.Disabled
        val info = encoderInfo as? VoiceEncoderInfo.Speex ?: return TranscriptionResult.Error("Unsupported watch audio format.")
        if (info.frameSize !in 1..8192 || info.sampleRate !in 8000..48000)
            return TranscriptionResult.Error("Unsupported watch audio format.")
        return try {
            val pcm = withContext(Dispatchers.Default) {
                val codec = SpeexCodec(info.sampleRate, info.bitRate, info.frameSize)
                val frame = ByteArray(info.frameSize * 2)
                // Bound memory and recording duration independently of provider upload timeout.
                val buffer = ByteArray(2 * 1024 * 1024)
                var size = 0
                withTimeout(60_000) {
                    audioFrames.collect { encoded ->
                        ensureActive()
                        if (size + frame.size > buffer.size) throw SignalProviderException("Recording is too long.")
                        if (codec.decodeFrame(encoded.asByteArray(), frame, hasHeaderByte = true) != SpeexDecodeResult.Success)
                            throw SignalProviderException("Watch audio could not be decoded.")
                        frame.copyInto(buffer, size); size += frame.size
                    }
                }
                SignalAudio.wave(buffer.copyOf(size), info.sampleRate.toInt())
            }
            val text = providers.transcribe(pcm)
            TranscriptionResult.Success(text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { TranscriptionWord(it, 1f) })
        } catch (_: TimeoutCancellationException) { TranscriptionResult.Error("Recording timed out.")
        } catch (e: CancellationException) { throw e
        } catch (e: SignalProviderException) { TranscriptionResult.Error(e.message ?: "Transcription failed.")
        } catch (_: Exception) { TranscriptionResult.Error("Transcription failed.") }
    }

}
