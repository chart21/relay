package dev.relay.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the share sheet handed over: file uris and/or plain text. */
class ShareInput(val uris: List<Uri>, val text: String?)

/** Target of the Android share sheet: pick a running session, add a message, upload the files and send paths (or the text) into it. */
class ShareActivity : ComponentActivity() {
    private fun parse(i: Intent): ShareInput {
        @Suppress("DEPRECATION")
        val uris = when (i.action) {
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        val clipUris = if (uris.isEmpty()) i.clipData?.let { c: ClipData -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty() else emptyList()
        return ShareInput(uris + clipUris, i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    }

    private fun items(uris: List<Uri>): List<UploadItem> = uris.mapIndexed { n, u ->
        val a = AttachIo.describe(contentResolver, u, n + 1)
        UploadItem(a.name, a.size) { contentResolver.openInputStream(u) ?: throw java.io.IOException("cannot read ${a.name}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Relay.startConnection()
        startService(this)
        val input = parse(intent)
        val items = items(input.uris)
        setContent { RelayTheme { ShareScreen(input, items, ::finish) } }
    }

    @Composable
    private fun ShareScreen(input: ShareInput, items: List<UploadItem>, close: () -> Unit) {
        val live by Relay.sessions.collectAsState()
        val prefs by Relay.settings.flow.collectAsState(Prefs())
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableStateOf<String?>(null) }
        val targets = remember(live, prefs.lastSession, query) { ShareLogic.targets(live, prefs.lastSession, query) }
        val target = targets.firstOrNull { it.key == chosen } ?: if (chosen == null) targets.firstOrNull() else null
        fun send() {
            val s = target ?: return
            items.firstOrNull { it.size > ShareLogic.MAX_BYTES }?.let { error = "Not sent: ${it.name} is larger than 50 MB"; return }
            error = null
            scope.launch {
                try {
                    val paths = Uploads.all(items, cacheDir, { m, p -> Relay.client.rpc(m, p, 60_000) }) { label, _ -> progress = label }
                    progress = "Sending…"
                    ChatIo(s.key, s.tmuxSession).text(ShareLogic.body(message, paths, input.text))
                    Relay.settings.update { it.copy(lastSession = s.key) }
                    Toast.makeText(this@ShareActivity, "Sent to ${s.name}", Toast.LENGTH_SHORT).show()
                    close()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    AppLog.w("share", "share failed", e)
                    error = "Not sent: ${e.message ?: e.javaClass.simpleName}"; progress = null
                }
            }
        }
        ShareContent(items.map { it.name to ShareLogic.sizeLabel(it.size) }, input.text, targets, target?.key, query, { query = it }, message, { message = it },
            { chosen = it }, progress, error, target != null && ShareLogic.canSend(items.size, input.text, message), ::send, close)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContent(files: List<Pair<String, String>>, text: String?, targets: List<Session>, selected: String?, query: String, onQuery: (String) -> Unit,
                 message: String, onMessage: (String) -> Unit, onSelect: (String) -> Unit, progress: String?, error: String?, canSend: Boolean,
                 onSend: () -> Unit, onClose: () -> Unit, chrome: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val sending = progress != null
    Scaffold(containerColor = cs.background, topBar = {
        TopAppBar(title = { Text("Send to a session", style = MaterialTheme.typography.headlineSmall) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            navigationIcon = { IconButton(onClose) { Icon(Icons.Default.Close, "cancel") } }, actions = { if (chrome) ConnBadge() })
    }, bottomBar = {
        Surface(color = cs.background, tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) Text(error, color = cs.error, style = MaterialTheme.typography.bodySmall)
                if (progress != null) {
                    Text(progress, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Button(onSend, Modifier.fillMaxWidth(), enabled = canSend && !sending) { Text("Send") }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (chrome) ConnectionBanner()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            files.forEach { (n, sz) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp), tint = cs.primary)
                                    Text(n, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    if (sz.isNotBlank()) Text(sz, style = MaterialTheme.typography.labelSmall, color = cs.outline)
                                }
                            }
                            if (!text.isNullOrBlank()) Text(text.trim(), maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                            if (files.isEmpty() && text.isNullOrBlank()) Text("Nothing to send but your message.", style = MaterialTheme.typography.bodyMedium, color = cs.outline)
                        }
                    }
                }
                item {
                    OutlinedTextField(message, onMessage, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Message (optional)") }, minLines = 1, maxLines = 4, shape = RoundedCornerShape(16.dp))
                }
                item {
                    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search sessions…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(28.dp))
                }
                if (targets.isEmpty()) item { Text(if (query.isBlank()) "No running sessions. Start one in Relay first." else "Nothing matches.", Modifier.padding(16.dp), color = cs.outline) }
                items(targets, key = { it.key }) { s ->
                    val on = s.key == selected
                    Surface({ onSelect(s.key) }, shape = RoundedCornerShape(14.dp), color = if (on) cs.primaryContainer.copy(alpha = 0.6f) else cs.background,
                        border = if (on) androidx.compose.foundation.BorderStroke(1.5.dp, cs.primary) else androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StateDot(s.state)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(shortPath(s.cwd), style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            AccountTag(s.alias)
                            RadioButton(on, { onSelect(s.key) })
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
