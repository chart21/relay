package dev.relay.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Stages of one voice turn in ms since [t0] (the wake word, the mic tap, or the start of a follow-up listen), sent to
 * the gateway's `voice_timing` log (`notifyd timing`) to compare latency before and after changes.
 */
class TurnTiming(val trigger: String, private val t0: Long = System.currentTimeMillis()) {
    val stages = LinkedHashMap<String, Long>()
    var stt = ""
    @Synchronized fun mark(stage: String) { stages.putIfAbsent(stage, System.currentTimeMillis() - t0) }
    fun send() {
        if (stages.isEmpty()) return
        val o = JSONObject().put("trigger", trigger).put("stt", stt).put("stages", JSONObject(synchronized(this) { stages.toMap() }))
        Relay.scope.launch { runCatching { Relay.client.rpc("voice_timing", o, 10_000) } }
    }
}

/** The overview agent: text/voice requests (`ask`), spoken replies, and the hands-free conversation loop. */
object Assistant {
    val busy = MutableStateFlow(false)
    val conv = MutableStateFlow(ConvState.Off)
    private var convJob: Job? = null

    private suspend fun sys(text: String) { Relay.db.msgs().insert(Msg(outgoing = false, text = text, kind = "system")) }

    /**
     * Sends [text] to the overview agent and stores both sides. Null (and a system line) on failure. With [speak] the
     * reply is streamed and queued for speech sentence by sentence while it is written; await [Speech.drain] after.
     */
    suspend fun ask(text: String, source: String = "text", speak: Boolean = false, timing: TurnTiming? = null): String? {
        Relay.db.msgs().insert(Msg(outgoing = true, text = text, kind = source))
        busy.value = true
        val spoken = Channel<String>(Channel.UNLIMITED)
        val sentences = Sentences { spoken.trySend(it) }
        val speaker = if (speak) Relay.scope.launch { for (s in spoken) Relay.speech.add(s) } else null
        if (speak && timing != null) Relay.speech.onFirstStart = { timing.mark("first_audio") }
        return try {
            val params = JSONObject().put("text", text).put("source", source).put("stream", speak)
            val res = Relay.client.rpc("ask", params, 180_000) { d -> timing?.mark("first_text"); sentences.add(d) }
            val reply = res.optString("text")
            timing?.mark("reply")
            sentences.end()
            if (speak && sentences.emitted == 0) spoken.trySend(reply) // nothing streamed (fast path, older gateway)
            spoken.close(); speaker?.join()
            Relay.db.msgs().insert(Msg(outgoing = false, text = reply))
            if (!Relay.foreground) Notifier.router(Relay.app, SpeechText.strip(reply).take(300))
            reply
        } catch (e: CancellationException) { speaker?.cancel(); Relay.speech.stop(); throw e
        } catch (e: Exception) { speaker?.cancel(); AppLog.w("assistant", "ask failed", e); sys("⚠ ${e.message}"); null
        } finally { spoken.close(); busy.value = false }
    }

    /** Typed request. */
    suspend fun typed(text: String) {
        val speak = Relay.settings.flow.first().speakReplies
        if (ask(text, speak = speak) != null && speak) Relay.speech.drain()
    }

    /** One voice request (mic button). */
    suspend fun voiceTurn() {
        val tm = TurnTiming("mic")
        when (val h = Stt.listen(tm)) {
            is Heard.Text -> {
                val speak = Relay.settings.flow.first().speakReplies
                if (ask(h.text, "voice", speak, tm) != null && speak) { Relay.speech.drain(); tm.mark("done") }
            }
            Heard.Silence -> sys("Didn't catch that")
            is Heard.Failed -> sys("⚠ ${h.msg}")
        }
        tm.send()
    }

    /**
     * Hands-free: listen -> answer (spoken while it streams) -> listen again. From the button it ends after two silent
     * turns; after "Hey Jarvis" ([trigger] wake) already after one, so the wake word listens again soon.
     */
    fun startConversation(ctx: Context, trigger: String = "button", wakeAt: Long = System.currentTimeMillis()): Job {
        convJob?.takeIf { it.isActive }?.let { return it }
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java)) }
        conv.value = ConvState.Listening
        var turn: TurnTiming? = null
        val job = Relay.scope.launch {
            if (trigger != "wake") delay(400) // let the service switch to the microphone type
            val loop = ConversationLoop(
                listen = {
                    turn?.send()
                    val tm = if (turn == null) TurnTiming(trigger, wakeAt) else TurnTiming("follow_up")
                    turn = tm
                    Stt.listen(tm).also { if (it is Heard.Failed) sys("⚠ ${it.msg}") }
                },
                ask = { ask(it, "voice", speak = true, timing = turn) },
                speak = { Relay.speech.drain(); turn?.mark("done") },
                onState = { conv.value = it },
                maxSilent = if (trigger == "wake") 1 else 2,
            )
            try {
                when (loop.run()) {
                    ConversationLoop.End.Silent -> if (trigger != "wake") sys("Conversation ended (no speech)")
                    ConversationLoop.End.Stopped -> sys("Conversation ended")
                    else -> {}
                }
            } finally { turn?.send() }
        }
        convJob = job
        job.invokeOnCompletion { conv.value = ConvState.Off }
        return job
    }

    fun stopConversation() {
        convJob?.cancel()
        Relay.speech.stop()
        conv.value = ConvState.Off
    }
}
