package dev.relay.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/** Session detail: the chat, behind the optional biometric lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(ref: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val live by Relay.sessions.collectAsState()
    val recent by Relay.recent.collectAsState()
    val accounts by Relay.accounts.collectAsState()
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val session = remember(live, recent, ref) { live.firstOrNull { it.matches(ref) } ?: recent.firstOrNull { it.matches(ref) } }
    val key = session?.key ?: ref
    val tool = session?.tool ?: accounts.firstOrNull { ref.removePrefix("tmux:").startsWith(it.alias + "-") }?.tool ?: "claude"
    val summary = remember(ref) { SummaryState() }
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable(ref) { mutableStateOf(false) }

    // optional biometric lock
    var unlocked by remember { mutableStateOf(!prefs.biometric || Biometrics.unlockedThisRun()) }
    var lockError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prefs.biometric) {
        val act = ctx as? FragmentActivity
        if (prefs.biometric && !unlocked && act != null) {
            if (Biometrics.available(act)) Biometrics.prompt(act, "Unlock session", { unlocked = true }, { lockError = it })
            else unlocked = true // nothing enrolled: don't lock the user out
        } else if (!prefs.biometric) unlocked = true
    }

    LifecycleResumeEffect(ref) {
        Relay.visibleRef = ref
        onPauseOrDispose { if (Relay.visibleRef == ref) Relay.visibleRef = null }
    }
    LaunchedEffect(key) { if (session != null && prefs.lastSession != key) Relay.settings.update { it.copy(lastSession = key) } }
    LaunchedEffect(key) { Relay.markRead(key); Notifier.cancelSession(Relay.app, key) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(session?.name ?: ref.removePrefix("tmux:"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    session?.let { Text("${it.label.ifBlank { it.alias }} · ${shortPath(it.cwd)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton({ searching = !searching }) { Icon(Icons.Default.Search, "search in chat") }
                session?.remoteUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    IconButton({ openRemote(ctx, url) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "open in the Claude app") }
                }
                session?.let { StateChip(it.state, Modifier.padding(end = 2.dp)) }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Summarize") }, { menu = false; session?.let { s -> scope.launch { summary.load(s) } } }, enabled = session != null && !summary.loading)
                    }
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            ConnectionBanner()
            if (!unlocked) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, Modifier.size(40.dp))
                    Text(lockError ?: "Locked", Modifier.padding(top = 8.dp))
                    Button({ lockError = null; (ctx as? FragmentActivity)?.let { Biometrics.prompt(it, "Unlock session", { unlocked = true }, { e -> lockError = e }) } }, Modifier.padding(top = 12.dp)) { Text("Unlock") }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth()) {
                Surface(Modifier.fillMaxSize()) { ChatPane(key, session, tool, searching, { searching = false }, summary) }
            }
        }
    }
}

