package dev.relay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

/** Chat with the overview agent: text, one-shot voice, and the hands-free conversation mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val msgs by Relay.db.msgs().all().collectAsState(emptyList())
    val confirms by Relay.confirms.collectAsState()
    val busy by Assistant.busy.collectAsState()
    val conv by Assistant.conv.collectAsState()
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<String?>(null) }
    fix?.let { TeachDialog(it) { fix = null } }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Jarvis", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), navigationIcon = { BackButton(onBack) }, actions = {
            if (conv != ConvState.Off) {
                Text(conv.name.lowercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                IconButton({ Assistant.stopConversation() }) { Icon(Icons.Default.Stop, "stop conversation", tint = MaterialTheme.colorScheme.error) }
            } else IconButton({ Assistant.startConversation(ctx) }) { Icon(Icons.Default.RecordVoiceOver, "conversation mode") }
            IconButton({ Relay.speech.stop() }) { Icon(Icons.Default.VolumeOff, "stop speaking") }
            ConnBadge()
        })
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            ConnectionBanner()
            JarvisMessages(msgs, list, Modifier.weight(1f), onFix = { fix = it }) { input = it }
            val live by Voice.partial.collectAsState()
            LiveText(live)
            SendApprovals()
            confirms.forEach { c ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Approve?", style = MaterialTheme.typography.titleSmall)
                        Text(c.description)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ Relay.confirm(c.id, true) }) { Text("Approve") }
                            OutlinedButton({ Relay.confirm(c.id, false) }) { Text("Deny") }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ComposeBar(input, { input = it }, "Message Jarvis…", Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { raw ->
                val t = raw.trim()
                if (t.isNotEmpty()) { input = ""; scope.launch { Assistant.typed(t) } }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(prefs.speakReplies, { v -> scope.launch { Relay.settings.update { it.copy(speakReplies = v) } } }, Modifier.scale(0.8f))
                Text("Speak replies", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton({
                    if (!listening && conv == ConvState.Off) scope.launch { listening = true; try { Assistant.voiceTurn() } finally { listening = false } }
                }) { Icon(Icons.Default.GraphicEq, "talk", tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton({ scope.launch { Relay.db.msgs().clear() } }) { Text("Clear chat") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarvisMessages(msgs: List<Msg>, list: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier, onFix: ((String) -> Unit)? = null, onSuggest: (String) -> Unit = {}) {
        LazyColumn(modifier.padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (msgs.isEmpty()) item { JarvisEmpty(onSuggest) }
            items(msgs, key = { it.id }) { m ->
                val mine = m.outgoing
                val cs = MaterialTheme.colorScheme
                if (mine) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 320.dp)
                            .then(if (onFix != null && m.kind == "voice") Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier)) {
                            Text((if (m.kind == "voice") "🎤 " else "") + m.text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                        DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Fix recognition") }, { menu = false; onFix?.invoke(m.text) }) }
                    }
                } else if (m.kind == "text") SelectionContainer {
                    Markdown(m.text, typography = markdownTypography(paragraph = ReplyStyle, bullet = ReplyStyle, ordered = ReplyStyle, list = ReplyStyle,
                        text = ReplyStyle, quote = ReplyStyle.copy(fontStyle = FontStyle.Italic)))
                } else Surface(color = if (m.kind == "system") cs.errorContainer.copy(alpha = .5f) else cs.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
}

@Composable
private fun JarvisEmpty(onSuggest: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(30.dp), tint = cs.onPrimaryContainer) }
        }
        Text("Hi, I'm Jarvis", style = MaterialTheme.typography.headlineSmall)
        Text("Ask about your sessions, mail, calendar or training, or tap the voice button to talk.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        listOf("What is running right now?", "How much protein is left today?", "Any new mail from my team?").forEach {
            Surface({ onSuggest(it) }, shape = RoundedCornerShape(50), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
                Text(it, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}
