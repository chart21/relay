package dev.relay.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val conn by Relay.client.state.collectAsState()
    val accounts by Relay.accounts.collectAsState()
    val overview by Relay.overview.collectAsState()
    val wake by Relay.wakeStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    fun save(f: (Prefs) -> Prefs) { scope.launch { Relay.settings.update(f) } }

    var host by remember(prefs.host) { mutableStateOf(prefs.host) }
    var user by remember(prefs.user) { mutableStateOf(prefs.user) }
    var port by remember(prefs.port) { mutableStateOf(prefs.port.toString()) }
    var model by remember(overview?.model) { mutableStateOf(overview?.model.orEmpty()) }
    var chipsClaude by remember(prefs.slashClaude) { mutableStateOf(prefs.slashClaude) }
    var chipsCodex by remember(prefs.slashCodex) { mutableStateOf(prefs.slashCodex) }
    var chipsAgy by remember(prefs.slashAgy) { mutableStateOf(prefs.slashAgy) }
    LaunchedEffect(conn) { if (conn == Conn.Connected) { runCatching { Relay.loadConfig() }; runCatching { Relay.loadAccounts() } } }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }, actions = { ConnBadge() }) },
        snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppUpdateSection()
            HorizontalDivider()
            Section("PC (SSH over LAN or WireGuard)")
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("User") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, modifier = Modifier.width(100.dp), singleLine = true)
            }
            Button({
                save { it.copy(host = host.trim(), user = user.trim(), port = port.toIntOrNull() ?: 22, configured = true) }
                Relay.client.reconnect()
            }) { Text("Save & reconnect") }
            Text("Phone public key. Register it on the PC with: notifyd setup key", style = MaterialTheme.typography.bodySmall)
            Text(Relay.identity.publicLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("key", Relay.identity.publicLine))
            }) { Text("Copy public key") }
            if (prefs.hostFingerprint.isNotBlank()) {
                Text("Pinned host key: ${prefs.hostFingerprint}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ save { it.copy(hostFingerprint = "") }; Relay.client.reconnect() }) { Text("Reset pinned host key") }
            }

            HorizontalDivider()
            Section("Overview agent")
            if (conn != Conn.Connected) Text("Connect to the PC to change this.", style = MaterialTheme.typography.bodySmall)
            else {
                Text("Account (usage is billed to it)", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.filter { it.tool == "claude" }.forEach { a ->
                        FilterChip(overview?.alias == a.alias, { scope.launch { runCatching { Relay.setOverview(a.alias, null) }.onFailure { snack.showSnackbar(it.message ?: "failed") } } },
                            label = { Text("${a.alias} · ${a.label}") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.weight(1f), singleLine = true)
                    Button({ scope.launch { runCatching { Relay.setOverview(null, model.trim()) }.onFailure { snack.showSnackbar(it.message ?: "failed") } } }) { Text("Set") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("haiku", "sonnet", "opus").forEach { m -> AssistChip({ model = m; scope.launch { runCatching { Relay.setOverview(null, m) } } }, label = { Text(m) }) }
                }
            }

            HorizontalDivider()
            Section("Jarvis (voice)")
            Text("Speech to text", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(prefs.stt == "device", { save { it.copy(stt = "device") } }, label = { Text("On phone") })
                FilterChip(prefs.stt == "pc", { save { it.copy(stt = "pc") } }, label = { Text("On PC (Whisper)") })
            }
            LangRow("Recognition language", prefs.voiceLang) { l -> save { it.copy(voiceLang = l) } }
            LangRow("Spoken language (TTS)", prefs.ttsLang) { l -> save { it.copy(ttsLang = l) } }
            VoiceSettings(prefs) { f -> save(f) }
            SpeechRecognitionSection()
            SwitchRow("Speak assistant replies", prefs.speakReplies) { v -> save { it.copy(speakReplies = v) } }
            SwitchRow("Read finished sessions aloud (headset or conversation mode only)", prefs.readFinished) { v -> save { it.copy(readFinished = v) } }
            SwitchRow("Listen for \"Hey Jarvis\" (on the phone, no account needed)", prefs.wakeWord) { v -> save { it.copy(wakeWord = v) } }
            Text("Wake word: $wake", style = MaterialTheme.typography.bodyMedium,
                color = if (wake.startsWith("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (prefs.wakeWord) {
                Text("Listen when", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(prefs.wakeWhen == "always", { save { it.copy(wakeWhen = "always") } }, label = { Text("Always") })
                    FilterChip(prefs.wakeWhen == "active", { save { it.copy(wakeWhen = "active") } }, label = { Text("Screen on, headset or charging") })
                }
                Text("Listening keeps the microphone on. The second option saves the most battery: no listening while the phone is locked without a headset.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                val level by Relay.wakeLevel.collectAsState()
                var sens by remember(prefs.sensitivity) { mutableFloatStateOf(prefs.sensitivity) }
                Text("Sensitivity %.2f (triggers at a score of %.2f; recent score %.2f)".format(sens, 1 - sens, level), style = MaterialTheme.typography.bodySmall)
                Slider(sens, { sens = it }, valueRange = 0.2f..0.8f, onValueChangeFinished = { save { p -> p.copy(sensitivity = sens) } })
                Text("Higher reacts more easily but may trigger by mistake. Say \"Hey Jarvis\" and watch the recent score.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            HorizontalDivider()
            NotificationsSection(prefs) { f -> save(f) }

            HorizontalDivider()
            Section("Sessions")
            Text("Slash-command chips, space separated", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(chipsClaude, { chipsClaude = it; save { p -> p.copy(slashClaude = it) } }, label = { Text("claude") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(chipsCodex, { chipsCodex = it; save { p -> p.copy(slashCodex = it) } }, label = { Text("codex") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(chipsAgy, { chipsAgy = it; save { p -> p.copy(slashAgy = it) } }, label = { Text("agy") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val canLock = (ctx as? FragmentActivity)?.let { Biometrics.available(it) } ?: false
            SwitchRow(if (canLock) "Require biometrics / device lock to open a session" else "Biometric lock (no screen lock or biometrics set up)", prefs.biometric && canLock, enabled = canLock) { v -> save { it.copy(biometric = v) } }

            HorizontalDivider()
            PhoneBridgeSection()

            HorizontalDivider()
            Section("Background")
            OutlinedButton({
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                else scope.launch { snack.showSnackbar("Already exempt from battery optimisation") }
            }) { Text("Allow background running (battery)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ startService(ctx) }) { Text("Start service") }
                OutlinedButton({ ctx.stopService(Intent(ctx, RelayService::class.java)) }) { Text("Stop service") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSection(prefs: Prefs, save: ((Prefs) -> Prefs) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Notifications")
        Text("Session ready", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("sound" to "Sound and pop-up", "silent" to "Silent", "off" to "Off").forEach { (k, l) -> FilterChip(prefs.notifyReady == k, { save { it.copy(notifyReady = k) } }, label = { Text(l) }) }
        }
        Text("Needs input", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("popup" to "Pop-up", "silent" to "Silent").forEach { (k, l) -> FilterChip(prefs.notifyInput == k, { save { it.copy(notifyInput = k) } }, label = { Text(l) }) }
        }
        Text("Silent notifications only appear in the shade. Mute a single session from the menu of its row" + if (prefs.muted.isNotEmpty()) " (${prefs.muted.size} muted)." else ".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange, enabled = enabled)
        Text("  $label", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LangRow(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Auto (device)", "de" to "Deutsch", "en" to "English").forEach { (k, l) -> FilterChip(value == k, { onChange(k) }, label = { Text(l) }) }
        }
    }
}


/** Installed version, the build the Desktop offers, and the update button (self-update over the SSH link). */
@Composable
private fun AppUpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val available by Updater.available.collectAsState()
    val status by Updater.status.collectAsState()
    val (code, name) = remember { Updater.installed(ctx) }
    Section("App")
    Text("Installed: $name ($code)", style = MaterialTheme.typography.bodyMedium)
    available?.let { b -> Text("Available on the Desktop: ${b.versionName} (%.0f MB download)".format(b.gzSize / 1e6), style = MaterialTheme.typography.bodyMedium) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (available != null) Button({ scope.launch { Updater.run(ctx.applicationContext) } }) { Text("Update") }
        OutlinedButton({
            scope.launch {
                runCatching { Relay.client.rpc("app_update").optJSONObject("app") }
                    .onSuccess { Updater.offer(ctx, AppBuild.fromJson(it)); if (Updater.available.value == null) Updater.status.value = "Up to date" }
                    .onFailure { Updater.status.value = it.message ?: "check failed" }
            }
        }) { Text("Check") }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
}
