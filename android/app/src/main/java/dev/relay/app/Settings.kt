package dev.relay.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("relay")

data class Prefs(
    val host: String = "",
    val port: Int = 22,
    val user: String = "user",
    val hostFingerprint: String = "",
    val configured: Boolean = false, // false until saved in the UI or provisioned; gates the adb provisioning extras
    val wakeWord: Boolean = false,
    val sensitivity: Float = 0.5f, // wake word: triggers at a score of 1 - sensitivity
    val stt: String = "device", // device | pc
    val lastSeq: Long = 0,
    val speakReplies: Boolean = true,
    val ttsLang: String = "auto", // auto | de | en
    val voiceLang: String = "auto", // speech recognition language: auto | de | en
    val readFinished: Boolean = false, // read finished sessions aloud (headset or conversation mode only)
    val slashClaude: String = SlashChips.defaults.getValue("claude"),
    val slashCodex: String = SlashChips.defaults.getValue("codex"),
    val slashAgy: String = SlashChips.defaults.getValue("agy"),
    val biometric: Boolean = false,
    val favDirs: Set<String> = emptySet(),
    val unread: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    val lastAlias: String = "",
    val ttsEngine: String = "", // TTS engine package, "" = the system default
    val voiceDe: String = "", // chosen voice names, "" = the best offline voice (Voices.pick)
    val voiceEn: String = "",
    val ttsRate: Float = 1.0f,
    val wakeWhen: String = "always", // always | active (screen on, headset or charging: saves battery)
    val notifyReady: String = "sound", // "session ready": sound (pop-up with sound) | silent | off
    val notifyInput: String = "popup", // "needs input": popup | silent
    val muted: Set<String> = emptySet(), // session keys without notifications
    val lastSession: String = "", // key of the session opened (or shared into) last: default target of the share sheet
) {
    fun chipsFor(tool: String) = SlashChips.parse(when (tool) { "claude" -> slashClaude; "codex" -> slashCodex; "agy" -> slashAgy; else -> "" })
}

class Settings(private val ctx: Context) {
    private object K {
        val host = stringPreferencesKey("host"); val port = intPreferencesKey("port"); val user = stringPreferencesKey("user")
        val fp = stringPreferencesKey("fp"); val configured = booleanPreferencesKey("configured")
        val wake = booleanPreferencesKey("wake"); val sens = floatPreferencesKey("sens")
        val stt = stringPreferencesKey("stt"); val seq = longPreferencesKey("seq")
        val speak = booleanPreferencesKey("speak"); val ttsLang = stringPreferencesKey("tts_lang"); val voiceLang = stringPreferencesKey("voice_lang")
        val readFinished = booleanPreferencesKey("read_finished")
        val slashClaude = stringPreferencesKey("slash_claude"); val slashCodex = stringPreferencesKey("slash_codex"); val slashAgy = stringPreferencesKey("slash_agy")
        val biometric = booleanPreferencesKey("biometric"); val favDirs = stringSetPreferencesKey("fav_dirs")
        val unread = stringSetPreferencesKey("unread"); val pinned = stringSetPreferencesKey("pinned"); val lastAlias = stringPreferencesKey("last_alias")
        val ttsEngine = stringPreferencesKey("tts_engine"); val voiceDe = stringPreferencesKey("voice_de"); val voiceEn = stringPreferencesKey("voice_en")
        val ttsRate = floatPreferencesKey("tts_rate"); val wakeWhen = stringPreferencesKey("wake_when")
        val notifyReady = stringPreferencesKey("notify_ready"); val notifyInput = stringPreferencesKey("notify_input"); val muted = stringSetPreferencesKey("muted"); val lastSession = stringPreferencesKey("last_session")
    }

    private fun Preferences.toPrefs(): Prefs {
        val d = Prefs()
        return Prefs(
            host = this[K.host] ?: d.host, port = this[K.port] ?: d.port, user = this[K.user] ?: d.user,
            hostFingerprint = this[K.fp] ?: "", configured = this[K.configured] ?: false,
            wakeWord = this[K.wake] ?: false, sensitivity = this[K.sens] ?: d.sensitivity,
            stt = this[K.stt] ?: d.stt, lastSeq = this[K.seq] ?: 0, speakReplies = this[K.speak] ?: d.speakReplies,
            ttsLang = this[K.ttsLang] ?: d.ttsLang, voiceLang = this[K.voiceLang] ?: d.voiceLang, readFinished = this[K.readFinished] ?: false,
            slashClaude = this[K.slashClaude] ?: d.slashClaude, slashCodex = this[K.slashCodex] ?: d.slashCodex, slashAgy = this[K.slashAgy] ?: d.slashAgy,
            biometric = this[K.biometric] ?: false, favDirs = this[K.favDirs] ?: emptySet(), unread = this[K.unread] ?: emptySet(), pinned = this[K.pinned] ?: emptySet(),
            lastAlias = this[K.lastAlias] ?: "",
            ttsEngine = this[K.ttsEngine] ?: "", voiceDe = this[K.voiceDe] ?: "", voiceEn = this[K.voiceEn] ?: "", ttsRate = this[K.ttsRate] ?: d.ttsRate,
            wakeWhen = this[K.wakeWhen] ?: d.wakeWhen, notifyReady = this[K.notifyReady] ?: d.notifyReady, notifyInput = this[K.notifyInput] ?: d.notifyInput,
            muted = this[K.muted] ?: emptySet(), lastSession = this[K.lastSession] ?: "",
        )
    }

    private fun MutablePreferences.write(n: Prefs) {
        this[K.host] = n.host; this[K.port] = n.port; this[K.user] = n.user; this[K.fp] = n.hostFingerprint; this[K.configured] = n.configured
        this[K.wake] = n.wakeWord; this[K.sens] = n.sensitivity; this[K.stt] = n.stt; this[K.seq] = n.lastSeq
        this[K.speak] = n.speakReplies; this[K.ttsLang] = n.ttsLang; this[K.voiceLang] = n.voiceLang; this[K.readFinished] = n.readFinished
        this[K.slashClaude] = n.slashClaude; this[K.slashCodex] = n.slashCodex; this[K.slashAgy] = n.slashAgy
        this[K.biometric] = n.biometric; this[K.favDirs] = n.favDirs; this[K.unread] = n.unread; this[K.pinned] = n.pinned; this[K.lastAlias] = n.lastAlias
        this[K.ttsEngine] = n.ttsEngine; this[K.voiceDe] = n.voiceDe; this[K.voiceEn] = n.voiceEn; this[K.ttsRate] = n.ttsRate; this[K.wakeWhen] = n.wakeWhen
        this[K.notifyReady] = n.notifyReady; this[K.notifyInput] = n.notifyInput; this[K.muted] = n.muted; this[K.lastSession] = n.lastSession
    }

    val flow: Flow<Prefs> = ctx.store.data.map { it.toPrefs() }

    suspend fun update(f: (Prefs) -> Prefs) { ctx.store.edit { it.write(repin(it.toPrefs(), f(it.toPrefs()))) } }

    companion object {
        /** A different host or port is a different machine: drop its pinned key so TOFU pins the new one. */
        fun repin(old: Prefs, new: Prefs): Prefs =
            if ((new.host != old.host || new.port != old.port) && new.hostFingerprint == old.hostFingerprint) new.copy(hostFingerprint = "")
            else new
    }
}
