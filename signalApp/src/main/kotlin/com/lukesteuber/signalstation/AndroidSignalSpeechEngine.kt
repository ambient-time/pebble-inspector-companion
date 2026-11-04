package com.lukesteuber.signalstation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import coredevices.pebble.signal.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Offline selection and cancellation patterns adapted from Luke Steuber's MIT-licensed Dick Tracy Speech.kt.
internal class AndroidSignalSpeechEngine(private val context: Context, private val interrupted: () -> Unit) : SignalSpeechEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (!closed && change < 0) interrupted() }, handler).build()
    private var engine: TextToSpeech? = null
    private var closed = false
    private var registered = false
    private var waiting: CompletableDeferred<Unit>? = null
    private var utterance = ""
    private var started: (() -> Unit)? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupted()
        }
    }
    override val maxInputLength get() = TextToSpeech.getMaxSpeechInputLength()

    override suspend fun prepare(): String {
        check(!closed)
        suspendCancellableCoroutine<Unit> { continuation ->
            engine = TextToSpeech(context) { code -> handler.post {
                if (continuation.isActive && !closed) {
                    if (code == TextToSpeech.SUCCESS) continuation.resume(Unit)
                    else continuation.resumeWithException(SignalSpeechFailure("Android speech could not start. Check text-to-speech settings, then try Listen again."))
                }
            } }
        }
        val current = checkNotNull(engine)
        val voices = current.voices.orEmpty()
        val choice = SignalSpeechPolicy.voice(voices.map {
            SignalSpeechVoice(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty())
        }, current.defaultVoice?.name, Locale.getDefault().toLanguageTag())
            ?: throw SignalSpeechFailure("No installed offline voice is available. Install voice data in Android text-to-speech settings, then try Listen again.")
        val voice = voices.first { it.name == choice.id }
        if (current.setVoice(voice) != TextToSpeech.SUCCESS || current.voice?.name != voice.name ||
            current.voice?.isNetworkConnectionRequired != false || TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in current.voice?.features.orEmpty())
            throw SignalSpeechFailure("This offline voice is unavailable. Check Android text-to-speech settings, then try Listen again.")
        if (current.setSpeechRate(1f) != TextToSpeech.SUCCESS || current.setAudioAttributes(attributes) != TextToSpeech.SUCCESS)
            throw SignalSpeechFailure("Android speech output could not be configured. Check text-to-speech settings, then try Listen again.")
        if (manager.isStreamMute(AudioManager.STREAM_MUSIC) || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            throw SignalSpeechFailure("Media audio is muted. Raise media volume with the phone's volume buttons, then try Listen again.")
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw SignalSpeechFailure("Audio is busy. Try Listen again when other audio has finished.")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        registered = true
        if (current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun callback(id: String?, action: () -> Unit) { handler.post { if (!closed && id == utterance) action() } }
            override fun onStart(id: String?) = callback(id) { started?.invoke() }
            override fun onDone(id: String?) = callback(id) { waiting?.complete(Unit) }
            override fun onStop(id: String?, interrupted: Boolean) = callback(id) { waiting?.cancel() }
            @Deprecated("Legacy Android callback") override fun onError(id: String?) = onError(id, TextToSpeech.ERROR)
            override fun onError(id: String?, errorCode: Int) = callback(id) {
                val message = when (errorCode) {
                    TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "The installed engine requested network access. Check its offline voice data. No other service was selected."
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Voice data is not installed. Finish installing an offline Android voice, then try Listen again."
                    TextToSpeech.ERROR_OUTPUT -> "Android audio output is unavailable. Check Sound settings and connected audio devices, then try Listen again."
                    else -> "The Android speech engine stopped. Check text-to-speech settings, then try Listen again."
                }
                waiting?.completeExceptionally(SignalSpeechFailure(message))
            }
        }) != TextToSpeech.SUCCESS) throw SignalSpeechFailure("Android speech callbacks are unavailable. Try Listen again.")
        return voice.locale.displayName
    }

    override suspend fun speak(text: String, onStart: () -> Unit) {
        check(!closed)
        val current = checkNotNull(engine)
        val done = CompletableDeferred<Unit>()
        waiting = done; started = onStart; utterance = UUID.randomUUID().toString()
        try {
            @Suppress("DEPRECATION")
            val options = Bundle().apply { putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true") }
            if (current.speak(text, TextToSpeech.QUEUE_FLUSH, options, utterance) != TextToSpeech.SUCCESS)
                throw SignalSpeechFailure("Speech could not start. Check installed offline voice data, then try Listen again.")
            done.await()
        } finally { waiting = null; started = null; utterance = "" }
    }

    override fun close() {
        if (closed) return
        closed = true
        waiting?.cancel(); waiting = null; started = null; utterance = ""
        engine?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }; engine = null
        if (registered) { runCatching { context.unregisterReceiver(noisy) }; registered = false }
        manager.abandonAudioFocusRequest(focus)
    }
}
