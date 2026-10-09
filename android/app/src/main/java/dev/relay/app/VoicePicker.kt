package dev.relay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

private const val GOOGLE_TTS = "com.google.android.tts"

/** Everything the picker draws; the stateful [VoiceSettings] fills it from the TTS engine and Prefs. */
data class VoiceUi(val engines: List<Pair<String, String>>, val engine: String, val voices: Map<String, List<VoiceInfo>>, val chosen: Map<String, String>,
                   val rate: Float, val googleMissing: Boolean, val testing: String? = null)

@Composable
fun VoiceSettings(prefs: Prefs, save: ((Prefs) -> Prefs) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var engines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var voices by remember { mutableStateOf<Map<String, List<VoiceInfo>>>(emptyMap()) }
    var testing by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { reload++; onPauseOrDispose {} }
    LaunchedEffect(prefs.ttsEngine, reload) {
        engines = Relay.speech.engines()
        voices = listOf("de", "en").associateWith { runCatching { Relay.speech.voices(it) }.getOrDefault(emptyList()) }
    }
    fun test(lang: String, voice: String) {
        scope.launch { testing = "$lang/$voice"; try { runCatching { Relay.speech.test(lang, voice) } } finally { testing = null } }
    }
    VoicePicker(VoiceUi(engines, prefs.ttsEngine, voices, mapOf("de" to prefs.voiceDe, "en" to prefs.voiceEn), prefs.ttsRate,
        googleMissing = engines.isNotEmpty() && engines.none { it.first == GOOGLE_TTS }, testing = testing),
        onEngine = { e -> save { it.copy(ttsEngine = e, voiceDe = "", voiceEn = "") } },
        onVoice = { lang, name -> save { if (lang == "de") it.copy(voiceDe = name) else it.copy(voiceEn = name) } },
        onTest = ::test, onRate = { r -> save { it.copy(ttsRate = r) } },
        onMore = { openVoiceInstall(ctx, prefs.ttsEngine.ifEmpty { engines.firstOrNull()?.first.orEmpty() }) },
        onGoogle = { openPlay(ctx, GOOGLE_TTS) })
}

/** Engine choice, voice list per language with Test buttons, speech rate and the links to get more voices. */
@Composable
fun VoicePicker(ui: VoiceUi, onEngine: (String) -> Unit, onVoice: (String, String) -> Unit, onTest: (String, String) -> Unit, onRate: (Float) -> Unit,
                onMore: () -> Unit, onGoogle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice engine", style = MaterialTheme.typography.bodyMedium)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(ui.engine.isEmpty(), { onEngine("") }, label = { Text("System default") })
            ui.engines.forEach { (pkg, label) -> FilterChip(ui.engine == pkg, { onEngine(pkg) }, label = { Text(label) }) }
        }
        listOf("de" to "German", "en" to "English").forEach { (lang, name) ->
            var all by remember(lang) { mutableStateOf(false) }
            val list = ui.voices[lang].orEmpty()
            val chosen = ui.chosen[lang].orEmpty()
            val effective = Voices.pick(list, lang, chosen)?.name.orEmpty()
            Text("$name voice", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            VoiceRow("Automatic (best)", chosen.isEmpty() || effective != chosen, ui.testing == "$lang/", { onVoice(lang, "") }, { onTest(lang, "") })
            Voices.shown(list, chosen, all).forEach { v ->
                VoiceRow(Voices.label(v), chosen == v.name, ui.testing == "$lang/${v.name}", { onVoice(lang, v.name) }, { onTest(lang, v.name) })
            }
            if (list.isEmpty()) Text("No offline $name voice installed.", style = MaterialTheme.typography.bodySmall, color = cs.outline)
            else if (list.size > 6) TextButton({ all = !all }) { Text(if (all) "Show fewer" else "Show all ${list.size}") }
        }
        var rate by remember(ui.rate) { mutableFloatStateOf(ui.rate) }
        Text("Speech rate %.2f×".format(rate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(rate, { rate = (Math.round(it * 20) / 20f) }, Modifier.weight(1f), valueRange = 0.7f..1.4f, onValueChangeFinished = { onRate(rate) })
            IconButton({ onRate(rate); onTest("de", ui.chosen["de"].orEmpty()) }) { Icon(Icons.Default.PlayArrow, "test the rate") }
        }
        OutlinedButton(onMore) { Text("Get more voices") }
        if (ui.googleMissing) OutlinedButton(onGoogle) { Text("Google speech engine on Google Play") }
        Text("Only offline voices are listed: replies can contain health and message data, which a network voice would send to the engine's servers.",
            style = MaterialTheme.typography.bodySmall, color = cs.outline)
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, playing: Boolean, onSelect: () -> Unit, onTest: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (playing) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
        else IconButton(onTest) { Icon(Icons.Default.PlayArrow, "test this voice") }
    }
}

private fun openVoiceInstall(ctx: Context, engine: String) {
    fun go(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (engine.isNotEmpty()) setPackage(engine) }
    if (!go(install)) go(Intent("com.android.settings.TTS_SETTINGS"))
}

private fun openPlay(ctx: Context, pkg: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
