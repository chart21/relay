package dev.relay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude-Code-style chat for one agent session. The view is built from the transcript; everything typed (text and
 * `/` commands) goes into the real terminal (tmux) behind it. When the TUI shows something only a terminal can
 * (pickers, questions, menus), the live screen panel shows the bottom of the pane with keys to answer.
 */
@Composable
fun ChatPane(key: String, session: Session?, tool: String, searching: Boolean = false, onCloseSearch: () -> Unit = {}, summary: SummaryState? = null) {
    val model = remember(key) { TranscriptModel(key) }
    val scope = rememberCoroutineScope()
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val state = session?.state ?: "unknown"
    val io = remember(key, session?.tmuxSession) { ChatIo(key, session?.tmuxSession) }

    LaunchedEffect(key) {
        Relay.client.state.collectLatest { st ->
            if (st == Conn.Connected && key.isNotBlank() && !key.startsWith("tmux:")) {
                runCatching { Relay.client.rpc("watch", JSONObject().put("key", key)) }
                model.loadLatest()
            }
        }
    }
    LaunchedEffect(key) { Relay.transcriptAppends.collect { if (it.sessionKey == key) model.append(it.messages) } }
    // catch up whenever the session changes state (a missed append must not leave the chat stale)
    LaunchedEffect(key, state, session?.lastActivity) { if (Relay.client.connected() && !key.startsWith("tmux:")) model.loadLatest() }
    DisposableEffect(key) { onDispose { Relay.scope.launch { runCatching { Relay.client.rpc("unwatch", JSONObject().put("key", key), 5_000) } } } }

    val pending = remember(key) { mutableStateListOf<Pending>() }
    LaunchedEffect(model.msgs.size, pending.size) {
        pending.removeAll { p -> ChatText.delivered(p, model.msgs) || System.currentTimeMillis() - p.at > 120_000 }
    }

    var peekOpen by rememberSaveable(key) { mutableStateOf(false) }
    var lastSlash by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(state) { if (state == "needs_input") peekOpen = true }
    var error by remember(key) { mutableStateOf<String?>(null) }

    val list = rememberLazyListState()
    val rows = remember(model.msgs.size, pending.size) { ChatText.rows(model.msgs, pending) } // newest first
    val rowsNow by rememberUpdatedState(rows)
    // Follow the newest message unless the user scrolled up. Decided only when a user scroll ends: inserting rows makes
    // the list keep its current item in view, which must not count as "the user left the bottom".
    var stick by remember(key) { mutableStateOf(true) }
    var positioned by remember(key) { mutableStateOf(false) }
    var newWhileAway by remember(key) { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && positioned) { stick = list.firstVisibleItemIndex == 0; if (stick) newWhileAway = false }
        }
    }
    LaunchedEffect(rows.firstOrNull()?.id) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (stick) { list.scrollToItem(0); positioned = true } else newWhileAway = true
    }
    LaunchedEffect(list) { // older pages only once the chat sits at the bottom, and only near the top of what is loaded
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (positioned && last >= rowsNow.size - 3 && model.more && !model.loading && model.msgs.isNotEmpty()) model.loadOlder()
        }
    }
    val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    val search = remember(key) { ChatSearchState() }
    var query by rememberSaveable(key) { mutableStateOf("") }
    var flash by remember(key) { mutableStateOf<String?>(null) }
    val q = if (searching) query else ""
    LaunchedEffect(query, searching) { if (searching) { delay(300); search.run(key, query) } }
    fun jump(i: Int) {
        val m = search.matches.getOrNull(i) ?: return
        search.current = i; search.listOpen = false
        scope.launch {
            model.loadTo(m.id, m.index)
            val at = withTimeoutOrNull(4_000) { snapshotFlow { ChatSearch.rowIndex(rowsNow, model.msgs, m.id) }.first { it >= 0 } } ?: return@launch
            stick = false
            list.scrollToItem(at + if (state == "busy") 1 else 0, -list.layoutInfo.viewportSize.height / 3)
            flash = m.id; delay(1_800); if (flash == m.id) flash = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            val n = search.matches.size
            ChatSearchBar(query, { query = it }, when {
                !ChatSearch.active(query) -> ""; search.busy -> "…"; n == 0 -> "0"; search.current >= 0 -> "${search.current + 1}/$n"; else -> "$n"
            }, { jump(ChatSearch.step(if (search.current < 0) -1 else search.current, 1, n)) }, { jump(ChatSearch.step(if (search.current < 0) 1 else search.current, -1, n)) }, onCloseSearch)
            if (ChatSearch.active(query) && !search.busy) {
                val cs = MaterialTheme.colorScheme
                when {
                    search.error != null -> Text(search.error ?: "", Modifier.padding(16.dp), color = cs.error, style = MaterialTheme.typography.bodySmall)
                    search.matches.isEmpty() -> Text("No matches", Modifier.padding(16.dp), color = cs.outline, style = MaterialTheme.typography.bodyMedium)
                    search.listOpen -> ChatSearchResults(search.matches, query, search.current, ::jump)
                    else -> TextButton({ search.listOpen = true }, Modifier.padding(start = 8.dp)) { Text("${search.matches.size} matches in ${search.total} messages · show list", style = MaterialTheme.typography.labelMedium) }
                }
            }
            HorizontalDivider()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            run {
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state == "busy") item(key = "working") { WorkingRow { scope.launch { runCatching { io.keys(listOf("Escape")) } } } }
                    items(rows, key = { it.id }) { r ->
                        val hit = flash == r.id || (flash != null && r.id == "${flash}-out")
                        val bg by animateColorAsState(if (hit) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(400), label = "flash")
                        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(bg)) { ChatRow(r, query = q) { t -> scope.launch { Relay.speech.say(t) } } }
                    }
                    item(key = "status") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            when {
                                model.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                model.error != null -> Text(model.error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                !model.more && model.msgs.isNotEmpty() -> Text("Start of the conversation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
            if (model.msgs.isEmpty() && pending.isEmpty() && !model.loading && model.error == null)
                Text("No messages yet.", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
            if (away && positioned) BadgedBox({ if (newWhileAway) Badge { Text("new") } }, Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                SmallFloatingActionButton({ scope.launch { list.animateScrollToItem(0); stick = true; newWhileAway = false } }) {
                    Icon(Icons.Default.KeyboardArrowDown, "jump to the newest message")
                }
            }
        }

        if (summary?.open == true) SummaryCard(summary) { t -> scope.launch { Relay.speech.say(t) } }
        StatusBar(state, peekOpen, { peekOpen = !peekOpen })
        if (peekOpen && io.canPeek) ScreenPeek(io, poll = System.currentTimeMillis() - lastSlash < 60_000 || state != "idle")
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        var text by rememberSaveable(key) { mutableStateOf("") }
        val ctx = LocalContext.current
        val attach = remember(key) { AttachState() }
        fun sendFiles(t: String) {
            val now = System.currentTimeMillis()
            stick = true
            scope.launch {
                var body: String? = null
                try {
                    val paths = attach.upload(ctx) { m, p -> Relay.client.rpc(m, p, 60_000) }
                    body = Attach.body(t, paths)
                    attach.sending(true)
                    pending.add(Pending(body, now))
                    io.text(body)
                    error = null; text = ""; attach.items.clear()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    AppLog.w("attach", "send with files failed", e)
                    error = "Not sent: ${e.message ?: e.javaClass.simpleName}"; body?.let { b -> pending.removeAll { p -> p.text == b } }
                } finally { attach.sending(false) }
            }
        }
        val commands = remember(tool, prefs) { SlashCatalog.forTool(tool, prefs.chipsFor(tool)) }
        val matches = SlashCatalog.matches(commands, text)
        if (matches.isNotEmpty()) LazyRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(matches) { c -> SuggestionChip({ text = "$c " }, label = { Text(c, fontFamily = FontFamily.Monospace) }) }
        }
        if (attach.items.isNotEmpty()) AttachChips(attach.items, !attach.busy) { attach.items.remove(it) }
        if (attach.busy) AttachProgress(attach.label, attach.fraction)
        ComposeBar(text, { text = it }, if (session?.attachable == false) "Reply (resumes the session headless)…" else "Message or /command…",
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp), leading = { AttachButton(!attach.busy) { attach.items.addAll(it.filter { a -> attach.items.none { x -> x.uri == a.uri } }) } },
            canSend = Attach.canSend(text, attach.items.size, attach.busy)) { raw ->
            val t = raw.trim()
            if (attach.busy) return@ComposeBar
            if (attach.items.isNotEmpty()) { sendFiles(t); return@ComposeBar }
            if (t.isEmpty()) return@ComposeBar
            text = ""
            val slash = t.startsWith("/")
            val now = System.currentTimeMillis()
            stick = true // sending brings the chat back to the newest message
            if (slash) { lastSlash = now; peekOpen = true; model.msgs.add(ChatText.localCommand(t, now)) } else pending.add(Pending(t, now))
            scope.launch {
                runCatching { io.text(t) }.onSuccess { error = null }.onFailure {
                    error = it.message ?: "send failed"; pending.removeAll { p -> p.text == t }; text = t
                }
            }
        }
    }
}

/** Where chat input and keys go: the agent's tmux session when there is one, else the session key (headless resume). */
class ChatIo(private val key: String, private val tmuxSession: String?) {
    val canPeek get() = tmuxSession != null || key.startsWith("tmux:")
    private fun base() = JSONObject().apply {
        when {
            tmuxSession != null -> put("target", "tmux:$tmuxSession")
            key.startsWith("tmux:") -> put("target", key)
            else -> put("key", key)
        }
    }
    suspend fun text(t: String) { Relay.client.rpc("send", base().put("text", t).put("enter", true).put("force", true)) }
    suspend fun keys(k: List<String>) { Relay.client.rpc("send", base().put("keys", JSONArray(k)).put("force", true)) }
    suspend fun screen(): String = Relay.client.rpc("screen", base().put("lines", 60).put("escapes", false), 10_000).optString("text")
}

@Composable
private fun StatusBar(state: String, peekOpen: Boolean, togglePeek: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            "needs_input" -> Text("Waiting for your answer", style = MaterialTheme.typography.labelMedium, color = cs.error)
            "exited" -> Text("Not running: a message resumes it", style = MaterialTheme.typography.labelMedium, color = cs.outline)
            else -> {}
        }
        Spacer(Modifier.weight(1f))
        TextButton(togglePeek) {
            Text("Screen", style = MaterialTheme.typography.labelMedium)
            Icon(if (peekOpen) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null, Modifier.size(18.dp))
        }
    }
}

/** The bottom of the real terminal, refreshed while open, with keys for pickers, questions and menus. */
@Composable
private fun ScreenPeek(io: ChatIo, poll: Boolean) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(io, poll, tick) {
        while (true) {
            if (Relay.client.connected()) runCatching { screen = ChatText.tail(io.screen(), 16) }
            if (!poll) break
            delay(1_200)
        }
    }
    fun press(vararg k: String) = scope.launch { runCatching { io.keys(k.toList()) }; delay(250); tick++ }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(screen.ifEmpty { "…" }, Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp, softWrap = false)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("↑" to "Up", "↓" to "Down", "⏎" to "Enter", "Esc" to "Escape", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
                    "←" to "Left", "→" to "Right", "Tab" to "Tab", "⇧Tab" to "BTab", "y" to "y", "n" to "n", "^C" to "C-c").forEach { (label, k) ->
                    FilledTonalButton({ press(k) }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.heightIn(min = 34.dp)) { Text(label) }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
internal fun ChatRow(r: ChatText.Row, query: String = "", speak: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mark = cs.primary.copy(alpha = 0.3f)
    when (r.kind) {
        ChatText.Kind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = cs.surfaceContainerHigh.copy(alpha = if (r.pending) 0.5f else 1f), shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    var open by remember(r.id) { mutableStateOf(false) }
                    val (head, more) = remember(r.text) { ChatText.fold(r.text) }
                    if (r.files.isNotEmpty()) SentFiles(r.files)
                    if (r.text.isNotBlank()) SelectionContainer { Text(ChatSearch.highlight(if (open) r.text else head, query, mark), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = if (r.files.isEmpty()) 0.dp else 6.dp)) }
                    if (more > 0) Text(if (open) "Show less" else "Show more ($more lines)", Modifier.clickable { open = !open }.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    if (r.pending) Text("sending…", style = MaterialTheme.typography.labelSmall, color = cs.outline)
                }
            }
        }
        ChatText.Kind.COMMAND -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.widthIn(max = 320.dp)) {
                Text(r.text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = cs.onSurface)
            }
        }
        ChatText.Kind.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            if (r.text.isNotBlank()) ReplyMarkdown(r.text, query = query)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.ts > 0) Text(timeFmt.format(Date((r.ts * 1000).toLong())), style = MaterialTheme.typography.labelSmall, color = cs.outline)
                Spacer(Modifier.weight(1f))
                if (r.text.isNotBlank()) IconButton({ speak(r.text) }, Modifier.size(28.dp)) { Icon(Icons.Default.VolumeUp, "read aloud", Modifier.size(18.dp), tint = cs.outline) }
            }
        }
        ChatText.Kind.OUTPUT -> Collapsed(r.text, r.lines)
        ChatText.Kind.STEPS -> Steps(r.tools)
        ChatText.Kind.EVENT -> EventRow(r)
        ChatText.Kind.NOTE -> Text(r.text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = cs.outline)
    }
}

/** A background task or agent finished (or failed): one line, the result as markdown on tap. */
@Composable
private fun EventRow(r: ChatText.Row) {
    val cs = MaterialTheme.colorScheme
    var open by remember(r.id) { mutableStateOf(false) }
    val failed = r.status.lowercase().let { it == "failed" || it == "error" }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Column(Modifier.clickable(enabled = r.detail.isNotBlank()) { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ChatText.statusIcon(r.status), color = if (failed) cs.error else cs.tertiary, style = MaterialTheme.typography.titleSmall)
                Text(r.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
                if (r.detail.isNotBlank()) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
            if (open) Box(Modifier.padding(top = 8.dp)) { ReplyMarkdown(r.detail) }
        }
    }
}

/** Folded tool calls: one line ("Ran 3 commands, read a file"), the single calls on tap. */
@Composable
internal fun Steps(tools: List<ToolCall>) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(enabled = tools.isNotEmpty()) { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ChatText.stepsLabel(tools), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (tools.isNotEmpty()) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(start = 4.dp)) { tools.forEach { ToolRow(it) } }
    }
}

/** The agent is busy: a quiet line at the bottom of the chat, like the Claude app's "thinking" row. */
@Composable
internal fun WorkingRow(onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Text("  Working…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        TextButton(onStop) { Text("Stop") }
    }
}

@Composable
internal fun ToolRow(t: ToolCall) {
    var open by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), onClick = { open = !open }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text(ChatText.toolIcon(t.name) + " ", style = MaterialTheme.typography.labelMedium)
            Text(t.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            if (t.summary.isNotBlank()) Text("  " + t.summary, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Collapsed(text: String, lines: Int) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clickable { open = !open }, shape = RoundedCornerShape(8.dp), color = cs.surfaceContainerLow.copy(alpha = 0.7f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            maxLines = if (open) Int.MAX_VALUE else lines, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
    }
}
