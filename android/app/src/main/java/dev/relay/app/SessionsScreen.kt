package dev.relay.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onAssistant: () -> Unit, onSettings: () -> Unit, onAccounts: () -> Unit = {}, onMemory: () -> Unit = {}) {
    val live by Relay.sessions.collectAsState()
    val recent by Relay.recent.collectAsState()
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val conn by Relay.client.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(SessionFilter.LIVE) }
    var refreshing by remember { mutableStateOf(false) }
    var killTarget by remember { mutableStateOf<Session?>(null) }
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var closeTarget by remember { mutableStateOf<Session?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var background by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val sessions = remember(live, recent, filter, prefs.pinned, query, background) {
        Overview.view(if (filter == SessionFilter.RECENT) Grouping.merge(live, recent) else live, filter, prefs.pinned, query, background)
    }
    LaunchedEffect(filter, conn, background) { if (filter == SessionFilter.RECENT && conn == Conn.Connected) runCatching { Relay.refreshSessions(true, background) } }
    val now = System.currentTimeMillis() / 1000.0

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Chats", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
                ConnBadge()
                IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Default.SearchOff else Icons.Default.Search, "search") }
                IconButton(onMemory) { Icon(Icons.Default.Memory, "memory (RAM monitor)") }
                IconButton(onAccounts) { Icon(Icons.Default.DataUsage, "accounts and usage limits") }
                IconButton(onAssistant) { Icon(Icons.Default.RecordVoiceOver, "Jarvis") }
                IconButton(onSettings) { Icon(Icons.Default.Settings, "settings") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onNew, shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New chat")
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner()
            SendApprovals()
            if (searching) SearchField(query, { query = it })
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(filter == SessionFilter.LIVE, { filter = SessionFilter.LIVE }, label = { Text("Live") })
                FilterChip(filter == SessionFilter.RECENT, { filter = SessionFilter.RECENT }, label = { Text("Recent") })
                if (filter == SessionFilter.RECENT) FilterChip(background, { background = !background }, label = { Text("Background runs") })
            }
            PullToRefreshBox(refreshing, {
                scope.launch { refreshing = true; runCatching { Relay.refreshSessions(filter == SessionFilter.RECENT, background) }.onFailure { snack.showSnackbar(it.message ?: "refresh failed") }; refreshing = false }
            }, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
                    if (sessions.isEmpty()) item { EmptyState(filter, query.isNotBlank(), conn != Conn.Connected, onNew) }
                    items(sessions, key = { it.key }) { s ->
                        SessionRow(s, s.key in prefs.unread, s.key in prefs.pinned, now, onOpen = { onOpen(s.key) }, onKill = { killTarget = s },
                            onPin = { scope.launch { Relay.togglePin(s.key) } }, onRename = { renameTarget = s },
                            onClose = { closeTarget = s }, onReopen = {
                                scope.launch { runCatching { Relay.reopen(s.key) }.onSuccess(onOpen).onFailure { snack.showSnackbar("Reopen failed: ${it.message}") } }
                            }, muted = s.key in prefs.muted, onMute = { scope.launch { Relay.toggleMute(s.key) } })
                    }
                }
            }
        }
    }
    killTarget?.let { s ->
        AlertDialog(onDismissRequest = { killTarget = null }, title = { Text("Kill session?") },
            text = { Text("${s.name}\nThe agent's tmux session ends and its work stops.") },
            confirmButton = {
                TextButton({
                    killTarget = null
                    scope.launch { runCatching { Relay.kill(s.key) }.onFailure { snack.showSnackbar("Kill failed: ${it.message}") } }
                }) { Text("Kill", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ killTarget = null }) { Text("Cancel") } })
    }
    closeTarget?.let { s ->
        CloseDialog(s.name, MemoryModel.info.value?.sessions?.firstOrNull { it.key == s.key }?.mb ?: 0.0, s.state, { closeTarget = null }) {
            closeTarget = null
            scope.launch { runCatching { Relay.closeSession(s.key, MemoryModel.info.value?.sessions?.firstOrNull { it.key == s.key }?.mb ?: 0.0) }.onFailure { snack.showSnackbar("Close failed: ${it.message}") } }
        }
    }
    renameTarget?.let { s ->
        var name by remember(s.key) { mutableStateOf(s.name) }
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename session") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton({
                    val n = name.trim().replace('\n', ' ')
                    renameTarget = null
                    if (n.isNotEmpty()) scope.launch { runCatching { ChatIo(s.key, s.tmuxSession).text("/rename $n") }.onFailure { snack.showSnackbar("Rename failed: ${it.message}") } }
                }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus), singleLine = true,
        placeholder = { Text("Search title, folder, account…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(28.dp),
        trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Default.Close, "clear") } })
}

@Composable
internal fun EmptyState(filter: SessionFilter, searching: Boolean, offline: Boolean, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (offline) Icons.Default.CloudOff else if (searching) Icons.Default.SearchOff else Icons.Default.ChatBubbleOutline, null, Modifier.size(40.dp), tint = cs.outline)
        Text(when {
            searching -> "Nothing matches your search."
            offline -> "Can't reach the PC. Sessions appear when the link is back."
            filter == SessionFilter.LIVE -> "No live sessions."
            else -> "No sessions found."
        }, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        if (!searching && !offline) {
            if (filter == SessionFilter.LIVE) FilledTonalButton(onNew) { Text("Start a chat") }
            else Text("Pull down to refresh.", style = MaterialTheme.typography.bodySmall, color = cs.outline)
        }
    }
}

/** State dot at the left of a row: spinner while busy, red when it waits for you, green when idle, hollow when ended. */
@Composable
internal fun StateDot(state: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (state) {
            "busy" -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp, color = cs.primary)
            "needs_input" -> Box(Modifier.size(11.dp).background(cs.error, CircleShape))
            "exited" -> Box(Modifier.size(10.dp).border(1.5.dp, cs.outline, CircleShape))
            else -> Box(Modifier.size(9.dp).background(cs.tertiary, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRow(s: Session, unread: Boolean, pinned: Boolean, now: Double, onOpen: () -> Unit, onKill: () -> Unit, onPin: () -> Unit = {}, onRename: () -> Unit = {},
                       onClose: () -> Unit = {}, onReopen: () -> Unit = {}, muted: Boolean = false, onMute: () -> Unit = {}) {
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) onKill(); false })
    val canKill = s.attachable && !s.exited
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = canKill, backgroundContent = {
        if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) Box(Modifier.fillMaxSize().background(cs.errorContainer, RoundedCornerShape(16.dp)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Default.Delete, "kill", tint = cs.onErrorContainer)
        }
    }) {
        val needs = s.state == "needs_input"
        Surface(onOpen, Modifier.fillMaxWidth().alpha(if (s.exited) 0.62f else 1f), shape = RoundedCornerShape(16.dp),
            color = if (needs) cs.errorContainer.copy(alpha = 0.35f) else cs.background) {
            Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp, end = 10.dp)) { StateDot(s.state) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (muted) Icon(Icons.Default.NotificationsOff, "muted", Modifier.size(14.dp), tint = cs.outline)
                        if (pinned) Icon(Icons.Default.PushPin, "pinned", Modifier.size(14.dp), tint = cs.outline)
                        Text(agoLabel(s.lastActivity, now), style = MaterialTheme.typography.labelSmall, color = cs.outline)
                    }
                    val preview = remember(s.lastMessage) { ChatText.preview(s.lastMessage) }
                    if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountTag(s.alias)
                        val status = Overview.status(s)
                        if (status != "idle") Text(status, style = MaterialTheme.typography.labelSmall, color = if (needs) cs.error else cs.onSurfaceVariant)
                        Text(shortPath(s.cwd), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.outline)
                    }
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Open") }, { menu = false; onOpen() })
                        DropdownMenuItem({ Text(if (pinned) "Unpin" else "Pin to top") }, { menu = false; onPin() })
                        if (s.tool == "claude" && canKill) DropdownMenuItem({ Text("Rename…") }, { menu = false; onRename() })
                        if (s.remoteUrl.isNotBlank()) DropdownMenuItem({ Text("Open in Claude app") }, { menu = false; openRemote(ctx, s.remoteUrl) })
                        DropdownMenuItem({ Text(if (muted) "Unmute notifications" else "Mute notifications") }, { menu = false; onMute() })
                        if (canKill) DropdownMenuItem({ Text("Close (free memory)") }, { menu = false; onClose() })
                        if (s.exited && s.tool == "claude") DropdownMenuItem({ Text("Reopen") }, { menu = false; onReopen() })
                        if (canKill) DropdownMenuItem({ Text("Kill session") }, { menu = false; onKill() })
                    }
                }
            }
        }
    }
}

/** The account a session runs on (c1, c2, x1, …), as a small tag next to its title; tinted by tool (Claude, Codex, agy). */
@Composable
fun AccountTag(alias: String) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (alias.firstOrNull()) {
        'c' -> cs.primaryContainer to cs.onPrimaryContainer
        'x' -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(alias, Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Opens the session's Remote Control page: the Claude app when installed (it handles claude.ai links), else the browser. */
fun openRemote(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        .onFailure { android.widget.Toast.makeText(ctx, "No app for $url", android.widget.Toast.LENGTH_SHORT).show() }
}
