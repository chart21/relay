package dev.relay.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.FloatBuffer

/** One ONNX model: flat float input + shape -> flat float output. An interface so the pipeline is unit-testable. */
fun interface Net { fun run(input: FloatArray, shape: LongArray): FloatArray }

/**
 * "Hey Jarvis" scores from openWakeWord's streaming pipeline: melspectrogram -> speech embedding -> wake word model.
 * A step-by-step port of the Python package (identical scores on test recordings). Feed 80 ms chunks
 * (1280 samples of 16 kHz mono PCM16); returns 0..1, where 0.5 is the package's default threshold.
 */
class WakePipeline(private val mel: Net, private val emb: Net, private val ww: Net) {
    private val melBuf = ArrayDeque<FloatArray>()   // last 76 mel frames (32 bins)
    private val feat = ArrayDeque<FloatArray>()     // last 16 embeddings (96)
    private var raw = ShortArray(0)                 // last 1280 + 480 samples
    private var calls = 0

    init {
        repeat(76) { melBuf.addLast(FloatArray(32) { 1f }) }        // as the reference starts
        val silence = embed(melspec(ShortArray(160 * 76 + 400)))   // = the reference's zero-audio warm-up
        repeat(16) { feat.addLast(silence) }
    }

    private fun melspec(x: ShortArray): List<FloatArray> {
        val out = mel.run(FloatArray(x.size) { x[it].toFloat() }, longArrayOf(1, x.size.toLong()))
        return List(out.size / 32) { f -> FloatArray(32) { out[f * 32 + it] / 10f + 2f } }
    }

    private fun embed(frames: List<FloatArray>): FloatArray {
        val inp = FloatArray(76 * 32)
        frames.takeLast(76).forEachIndexed { i, r -> r.copyInto(inp, i * 32) }
        return emb.run(inp, longArrayOf(1, 76, 32, 1))
    }

    fun process(chunk: ShortArray): Float {
        raw = (raw + chunk).let { if (it.size > 1760) it.copyOfRange(it.size - 1760, it.size) else it }
        melspec(raw).forEach { melBuf.addLast(it) }
        while (melBuf.size > 76) melBuf.removeFirst()
        feat.addLast(embed(melBuf))
        while (feat.size > 16) feat.removeFirst()
        val inp = FloatArray(16 * 96)
        feat.forEachIndexed { i, r -> r.copyInto(inp, i * 96) }
        val score = ww.run(inp, longArrayOf(1, 16, 96))[0]
        calls++
        return if (calls <= 5) 0f else score // the reference ignores the first 5 frames
    }
}

/**
 * Energy gate in front of [WakePipeline] (battery): while it is quiet no model runs, only the last [preRoll] chunks
 * are kept. When a chunk is louder than an adaptive noise floor, a fresh pipeline gets the pre-roll first (so the
 * soft "Hey" before the loud part and the pipeline's 5-frame warm-up are covered), then the live chunks, until
 * [hold] quiet chunks in a row close the gate again. Returns the best score of what it processed.
 */
class WakeGate(private val newPipeline: () -> WakePipeline, private val preRoll: Int = 20, private val hold: Int = 25,
               private val minRms: Double = 100.0, private val factor: Double = 3.0) {
    private val ring = ArrayDeque<ShortArray>()
    private var pipe: WakePipeline? = null
    private var quiet = 0
    var noise = minRms / factor
        private set
    var run = 0L
        private set
    var skipped = 0L
        private set
    val open get() = pipe != null

    fun process(chunk: ShortArray): Float {
        val rms = rms(chunk)
        noise = if (rms < noise) noise * 0.9 + rms * 0.1 else noise * 0.995 + rms * 0.005 // falls fast, rises slowly
        val loud = rms > maxOf(minRms, noise * factor)
        var p = pipe
        var best = 0f
        if (p == null) {
            if (!loud) { ring.addLast(chunk.copyOf()); while (ring.size > preRoll) ring.removeFirst(); skipped++; return 0f }
            p = newPipeline().also { pipe = it }
            for (c in ring) { best = maxOf(best, p.process(c)); run++ }
            ring.clear()
            quiet = 0
        }
        best = maxOf(best, p.process(chunk)); run++
        if (loud) quiet = 0 else if (++quiet >= hold) pipe = null
        return best
    }

    companion object {
        fun rms(x: ShortArray): Double { var sum = 0.0; for (v in x) sum += v * v.toDouble(); return Math.sqrt(sum / maxOf(1, x.size)) }
    }
}

/** Wake-word counters since the app started, for the battery report (`phone_stats` via voice_timing). */
object WakeStats {
    @Volatile var listenMs = 0L
    @Volatile var run = 0L
    @Volatile var skipped = 0L
    @Volatile var detections = 0L
}

class OrtNet(private val env: OrtEnvironment, model: ByteArray) : Net, AutoCloseable {
    private val session = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) })
    private val inputName = session.inputNames.first()

    override fun run(input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                val b = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(b.remaining()).also { b.get(it) }
            }
        }

    override fun close() = session.close()
}

/**
 * Listens for "Hey Jarvis" on a background thread (models fetched into assets/wake by scripts/fetch-wake-models.sh, openWakeWord, CC BY-NC-SA 4.0).
 * After a detection the microphone is released, [held] is set and [onWake] runs with the detection time; clear
 * [held] and call [resume] when the conversation is over.
 * [pause]/[resume] are also used by Mic.exclusive while speech recognition needs the microphone; [idle] is set by
 * the service while listening is not wanted (screen off, with "Listen when" = screen on / headset / charging).
 * Battery: the microphone is read in 320 ms blocks and the models only run while it is not quiet ([WakeGate]).
 */
private const val BLOCK = 4 // 80 ms chunks per microphone read: the thread wakes ~3x a second, not 12.5x

class WakeListener(private val ctx: Context, private val threshold: () -> Float, private val onWake: (Long) -> Unit) {
    @Volatile private var running = false
    @Volatile private var paused = false
    private val lock = Object()
    /** Held while a conversation runs: Mic.exclusive's resume() between turns must not reopen the microphone. */
    @Volatile var held = false
        set(v) { field = v; wakeUp() }
    @Volatile var idle = false
        set(v) { field = v; wakeUp() }
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "wake-word").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; wakeUp(); thread?.join(1000); thread = null }
    fun pause() { paused = true }
    fun resume() { paused = false; wakeUp() }
    private fun wakeUp() = synchronized(lock) { lock.notifyAll() }
    private fun blocked() = paused || held || idle

    private fun loop() {
        try { listen() } catch (t: Throwable) { // incl. native-library errors: a broken wake word must never crash the app
            Relay.wakeStatus.value = "Failed: ${t.javaClass.simpleName}: ${t.message}"
            running = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val env = OrtEnvironment.getEnvironment()
        val have = ctx.assets.list("wake")?.toSet() ?: emptySet()
        check(listOf("melspectrogram", "embedding_model", "hey_jarvis_v0.1").all { "$it.onnx" in have }) {
            "wake word models missing: run scripts/fetch-wake-models.sh and rebuild"
        }
        val nets = listOf("melspectrogram", "embedding_model", "hey_jarvis_v0.1").map { OrtNet(env, ctx.assets.open("wake/$it.onnx").use { s -> s.readBytes() }) }
        val block = ShortArray(1280 * BLOCK)
        val chunk = ShortArray(1280)
        var lastWake = 0L
        var peakAt = 0L
        try {
            while (running) {
                if (blocked()) {
                    if (idle && !paused && !held) Relay.wakeStatus.value = "Paused (screen off; listens with the screen on, a headset or while charging)"
                    synchronized(lock) { while (running && blocked()) lock.wait() }
                    continue
                }
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000 * 2 * 2)) // 2 s: room for the gate's pre-roll burst
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release(); Relay.wakeStatus.value = "Microphone busy, retrying…"; Thread.sleep(2000); continue
                }
                val gate = WakeGate({ WakePipeline(nets[0], nets[1], nets[2]) }) // fresh context after every pause
                rec.startRecording()
                var counted = 0L to 0L // gate.run, gate.skipped already added to WakeStats
                Relay.wakeStatus.value = "Listening for \"Hey Jarvis\""
                var woke = false
                try {
                    while (running && !blocked()) {
                        var off = 0
                        while (off < block.size) { val k = rec.read(block, off, block.size - off); if (k <= 0) break; off += k }
                        if (off < block.size) break
                        WakeStats.listenMs += 80L * BLOCK
                        for (i in 0 until BLOCK) {
                            block.copyInto(chunk, 0, i * 1280, (i + 1) * 1280)
                            val s = gate.process(chunk)
                            val now = System.currentTimeMillis()
                            if (s > Relay.wakeLevel.value || now - peakAt > 3000) { Relay.wakeLevel.value = s; peakAt = now }
                            if (s >= threshold() && now - lastWake > 2000) {
                                lastWake = now
                                paused = true
                                held = true
                                woke = true
                                WakeStats.detections++
                                break
                            }
                        }
                        WakeStats.run += gate.run - counted.first; WakeStats.skipped += gate.skipped - counted.second
                        counted = gate.run to gate.skipped
                    }
                } finally { runCatching { rec.stop() }; rec.release() }
                if (woke) onWake(lastWake) // microphone released first: the conversation needs it
            }
        } finally { nets.forEach { it.close() } }
    }
}
