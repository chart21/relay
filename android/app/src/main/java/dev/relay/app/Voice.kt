package dev.relay.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Only one thing may use the microphone: pauses the wake word detector while we capture. */
object Mic {
    @Volatile var pause: (() -> Unit)? = null
    @Volatile var resume: (() -> Unit)? = null
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock {
        pause?.invoke()
        try { block() } finally { resume?.invoke() }
    }
}

/** error == null: recognised or plain silence; otherwise the SpeechRecognizer error code. */
data class Recognition(val text: String?, val error: Int? = null)

object Voice {
    fun recognizerAvailable(ctx: Context) =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) || SpeechRecognizer.isRecognitionAvailable(ctx)

    fun beep() = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 120) }

    /** Live text of the running recognition (empty when idle); only shown on screen, never stored or logged. */
    val partial = MutableStateFlow("")

    private fun deviceLang() = if (java.util.Locale.getDefault().language == "de") "de-DE" else "en-US"

    fun intent(lang: String, terms: List<String>, sdk: Int = Build.VERSION.SDK_INT) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // audio stays on the phone: commands contain health data
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        if (terms.isNotEmpty() && sdk >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(terms))
        val tag = langTag(lang)
        if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        else if (sdk >= 34) {
            val both = arrayListOf("de-DE", "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, deviceLang())
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
        }
    }

    private fun newRecognizer(ctx: Context) =
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)

    /** Android's recognizer (on-device when available): n-best, biased by the Desktop's vocabulary, then corrected. Must run on the main thread. */
    suspend fun recognize(ctx: Context, lang: String = "auto", onEnd: () -> Unit = {}): Recognition = suspendCancellableCoroutine { cont ->
        val rec = newRecognizer(ctx)
        val vocab = Vocab.data.value
        val scoring = SpeechVocab.scoringTerms(vocab)
        partial.value = ""
        fun finish(v: Recognition) { partial.value = ""; if (cont.isActive) cont.resume(v); rec.destroy() }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle) {
                val hyps = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val best = SpeechVocab.pick(hyps, r.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES), scoring)
                finish(Recognition(best?.let { SpeechVocab.correct(it, vocab.corrections) }))
            }
            override fun onError(error: Int) = finish(Recognition(null, error.takeUnless { it == SpeechRecognizer.ERROR_NO_MATCH || it == SpeechRecognizer.ERROR_SPEECH_TIMEOUT }))
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() = onEnd()
            override fun onPartialResults(p: Bundle?) { partial.value = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec.startListening(intent(lang, SpeechVocab.biasing(vocab.terms)))
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { partial.value = ""; rec.destroy() } }
    }

    /** Which offline models the recogniser has (API 33+; null if it cannot tell). Main thread. */
    suspend fun checkModels(ctx: Context): ModelState? {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return null
        return suspendCancellableCoroutine { cont ->
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            fun done(v: ModelState?) { if (cont.isActive) cont.resume(v); rec.destroy() }
            runCatching {
                rec.checkRecognitionSupport(intent("auto", emptyList()), ctx.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) = done(ModelState(s.installedOnDeviceLanguages.toSet(), s.pendingOnDeviceLanguages.toSet(),
                        s.supportedOnDeviceLanguages.toSet()))
                    override fun onError(error: Int) = done(null)
                })
            }.onFailure { done(null) }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { rec.destroy() } }
        }
    }

    /** Asks the system to download the offline model of [lang] ("de" | "en"); progress shows up in [checkModels] as pending. */
    fun downloadModel(ctx: Context, lang: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        val i = intent(lang, emptyList())
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) rec.triggerModelDownload(i, ctx.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {}
                override fun onSuccess() = rec.destroy()
                override fun onScheduled() {}
                override fun onError(error: Int) = rec.destroy()
            }) else { rec.triggerModelDownload(i); Handler(Looper.getMainLooper()).postDelayed({ rec.destroy() }, 60_000) }
        }.onFailure { rec.destroy(); AppLog.w("voice", "model download failed", it) }
    }

    fun langTag(lang: String): String? = when (lang) { "de" -> "de-DE"; "en" -> "en-US"; else -> null }

    /** Records 16 kHz mono WAV until ~1.2 s of silence after speech (or 30 s). Null if nothing was said. */
    @SuppressLint("MissingPermission")
    suspend fun recordWav(ctx: Context): File? = withContext(Dispatchers.IO) {
        val rate = 16000
        val buf = ShortArray(rate / 10)
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, buf.size * 4))
        val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
        val f = File(dir, "req-${System.currentTimeMillis()}.wav")
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(ByteArray(44))
            rec.startRecording()
            var spoke = false; var quiet = 0; var total = 0
            val start = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - start < 30_000) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val bytes = ByteArray(n * 2)
                    var sum = 0.0
                    for (i in 0 until n) { bytes[2 * i] = buf[i].toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte(); sum += buf[i] * buf[i].toDouble() }
                    raf.write(bytes); total += bytes.size
                    val rms = sqrt(sum / n)
                    if (rms > 1200) { spoke = true; quiet = 0 } else quiet++
                    if (spoke && quiet >= 12) break
                    if (!spoke && quiet >= 60) break // 6 s of nothing
                }
            } finally { rec.stop(); rec.release() }
            if (!spoke) { raf.close(); f.delete(); return@withContext null }
            raf.seek(0); raf.write(wavHeader(total, rate))
        }
        f
    }

    private fun wavHeader(dataLen: Int, rate: Int): ByteArray {
        fun le(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        return "RIFF".toByteArray() + le(36 + dataLen, 4) + "WAVEfmt ".toByteArray() + le(16, 4) + le(1, 2) + le(1, 2) +
            le(rate, 4) + le(rate * 2, 4) + le(2, 2) + le(16, 2) + "data".toByteArray() + le(dataLen, 4)
    }
}

/** One listening turn with the configured engine: the phone's recogniser, PC Whisper as fallback or by choice. */
object Stt {
    suspend fun listen(tm: TurnTiming? = null): Heard = Mic.exclusive {
        val ctx = Relay.app
        val p = Relay.settings.flow.first()
        var usePc = p.stt == "pc"
        if (!usePc) {
            if (!Voice.recognizerAvailable(ctx)) usePc = true
            else {
                tm?.stt = "device"
                Voice.beep()
                tm?.mark("listen")
                val r = withContext(Dispatchers.Main) { Voice.recognize(ctx, p.voiceLang) { tm?.mark("speech_end") } }
                tm?.mark("heard")
                if (!r.text.isNullOrBlank()) return@exclusive Heard.Text(r.text)
                if (r.error == null) return@exclusive Heard.Silence // silence / no match
                usePc = true // recogniser failed: fall back to Whisper on the PC
                Voice.beep()
            }
        }
        tm?.stt = "pc"
        // Whisper loads lazily and unloads after 5 idle minutes: start loading it while the user is still talking
        Relay.scope.launch { runCatching { Relay.client.rpc("stt_warm", org.json.JSONObject(), 10_000) } }
        tm?.mark("listen")
        val wav = try { Voice.recordWav(ctx) } catch (e: Exception) { AppLog.w("voice", "recordWav failed", e); return@exclusive Heard.Failed("Microphone unavailable: ${e.message}") }
            ?: return@exclusive Heard.Silence
        tm?.mark("speech_end")
        try {
            val b64 = android.util.Base64.encodeToString(wav.readBytes(), android.util.Base64.NO_WRAP)
            val text = Relay.client.rpc("transcribe", org.json.JSONObject().put("audio_b64", b64), 60_000).optString("text").trim()
            tm?.mark("heard")
            if (text.isBlank()) Heard.Silence else Heard.Text(text)
        } catch (e: Exception) { AppLog.w("voice", "transcribe failed", e); Heard.Failed(e.message ?: "transcription failed") } finally { wav.delete() }
    }
}
