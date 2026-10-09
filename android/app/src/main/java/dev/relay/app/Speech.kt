package dev.relay.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-speech: strips markdown/code, chunks to the engine's limit, queues, holds transient (ducking) audio focus.
 * Engine, voice per language and rate come from Settings; with language "auto" each sentence is spoken by the German
 * or English voice it is written in (Voices.detect). Only offline voices: the text may hold health or message data.
 */
class Speech(private val ctx: Context, private val prefs: suspend () -> Prefs) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var init: CompletableDeferred<TextToSpeech?>? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val seq = AtomicLong()
    private var engineName: String? = null // engine package of the current instance ("" = system default)
    private var voiceCache: List<Voice>? = null
    private val initLock = Mutex()
    @Volatile private var lastLang: String? = null // ambiguous sentences ("Okay.") keep the reply's language
    /** Runs once when the next utterance actually starts playing (voice-turn timing). */
    @Volatile var onFirstStart: (() -> Unit)? = null

    private suspend fun engine(): TextToSpeech? = initLock.withLock {
        val want = prefs().ttsEngine
        if (init != null && engineName != want) { stop(); tts?.shutdown(); tts = null; init = null; voiceCache = null } // changed in Settings
        init?.let { return@withLock it.await() }
        engineName = want
        val d = CompletableDeferred<TextToSpeech?>().also { init = it }
        var ref: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            val t = ref
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) { onFirstStart?.let { onFirstStart = null; it() } }
                    override fun onDone(id: String) { waiting.remove(id)?.complete(Unit) }
                    @Deprecated("deprecated") override fun onError(id: String) { waiting.remove(id)?.complete(Unit) }
                    override fun onStop(id: String, interrupted: Boolean) { waiting.remove(id)?.complete(Unit) }
                })
                tts = t; d.complete(t)
            } else d.complete(null)
        }
        ref = if (want.isBlank()) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, want)
        d.await()
    }

    private fun locale(l: String) = when (l) { "de" -> Locale.GERMANY; "en" -> Locale.US; else -> Locale.getDefault() }

    private fun Voice.info() = VoiceInfo(name, locale.language, locale.country, quality, latency, isNetworkConnectionRequired,
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty())

    private fun allVoices(t: TextToSpeech): List<Voice> =
        voiceCache ?: runCatching { t.voices?.toList() }.getOrNull().orEmpty().also { voiceCache = it }

    /** Sets the voice (or at least the language) and rate for the next utterance: the engine reads both at speak(). */
    private fun apply(t: TextToSpeech, lang: String, chosen: String, rate: Float) {
        val all = allVoices(t)
        val pick = Voices.pick(all.map { it.info() }, lang, chosen)?.let { p -> all.firstOrNull { it.name == p.name } }
        if (pick != null) { if (t.voice?.name != pick.name) runCatching { t.voice = pick } }
        else if (t.voice?.locale?.language != lang) runCatching { t.language = locale(lang) }
        t.setSpeechRate(rate)
    }

    /** Installed TTS engines (package, label) for Settings. */
    suspend fun engines(): List<Pair<String, String>> = engine()?.engines?.map { it.name to it.label }.orEmpty()

    /** Offline voices of the current engine for [lang], best first (re-read: the user may have installed some). */
    suspend fun voices(lang: String): List<VoiceInfo> {
        val t = engine() ?: return emptyList()
        voiceCache = null
        return Voices.usable(allVoices(t).map { it.info() }, lang)
    }

    /** A sample sentence with this voice ("" = the automatic choice), for the Test buttons in Settings. */
    suspend fun test(lang: String, voice: String) {
        val text = if (lang == "de") "Hallo, ich bin Jarvis. Die Sitzung auf c2 ist fertig und wartet auf deine Freigabe."
        else "Hi, I'm Jarvis. The session on c2 is done and waiting for your approval."
        val done = enqueue(text, flush = true, force = lang to voice) ?: return
        try { done.await() } finally { if (waiting.isEmpty()) abandon() }
    }

    /** Speaks [text] and returns when finished (or stopped). With [queue] it waits behind anything already speaking. */
    suspend fun say(text: String, queue: Boolean = false) {
        val done = enqueue(text, flush = !queue) ?: return
        try { done.await() } finally { if (waiting.isEmpty()) abandon() }
    }

    /** Queues [text] behind what is already speaking and returns at once (streamed replies, sentence by sentence). */
    suspend fun add(text: String) { enqueue(text, flush = false) }

    /** Waits until everything queued with [add] has been spoken (or [stop] was called). */
    suspend fun drain() {
        try { while (true) { val w = waiting.values.toList(); if (w.isEmpty()) break; w.awaitAll() } }
        finally { if (waiting.isEmpty()) abandon() }
    }

    private suspend fun enqueue(text: String, flush: Boolean, force: Pair<String, String>? = null): CompletableDeferred<Unit>? {
        val t = engine() ?: return null
        val p = prefs()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(200)
        val chunks = SpeechText.chunks(SpeechText.strip(text), max)
        if (chunks.isEmpty()) return null
        focus()
        val device = Locale.getDefault().language.takeIf { it == "de" || it == "en" } ?: "en"
        val done = CompletableDeferred<Unit>()
        chunks.forEachIndexed { i, c ->
            val lang = force?.first ?: Voices.langFor(c, p.ttsLang, lastLang ?: device).also { lastLang = it }
            apply(t, lang, force?.second ?: if (lang == "de") p.voiceDe else p.voiceEn, p.ttsRate)
            val id = "u${seq.incrementAndGet()}"
            if (i == chunks.lastIndex) waiting[id] = done
            t.speak(c, if (i == 0 && flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        return done
    }

    fun stop() {
        tts?.stop()
        waiting.values.forEach { it.complete(Unit) }
        waiting.clear()
        abandon()
    }

    private fun focus() {
        if (focus != null) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
        am.requestAudioFocus(r)
        focus = r
    }

    private fun abandon() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    fun shutdown() { stop(); tts?.shutdown(); tts = null; init = null; voiceCache = null }

    /** Headphones, headset or Bluetooth audio attached (used for "read finished sessions aloud"). */
    fun headsetConnected(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}
