package dev.relay.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Pick account -> folder (browser, recents, favourites) -> optional first prompt -> launch -> chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(onBack: () -> Unit, onLaunched: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs by Relay.settings.flow.collectAsState(Prefs())
    val conn by Relay.client.state.collectAsState()
    val accounts by Relay.accounts.collectAsState()
    val usage by UsageModel.accounts.collectAsState()
    LaunchedEffect(Unit) { UsageModel.load() } // show each account's headroom while choosing
    var alias by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("~") }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var recents by remember { mutableStateOf<List<RecentDir>>(emptyList()) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var launching by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(conn) {
        if (conn != Conn.Connected) return@LaunchedEffect
        runCatching { Relay.loadAccounts() }
        runCatching {
            recents = Relay.client.rpc("recent_dirs", JSONObject().put("limit", 30)).optJSONArray("dirs").objects()
                .map { RecentDir(it.getString("path"), it.optDouble("last_used", 0.0), it.optJSONArray("tools")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()) }
        }
    }
    var picked by rememberSaveable { mutableStateOf(false) } // the user tapped an account
    LaunchedEffect(accounts, prefs.lastAlias) { if (alias.isBlank()) alias = accounts.firstOrNull { it.alias == prefs.lastAlias }?.alias ?: accounts.firstOrNull()?.alias.orEmpty() }
    LaunchedEffect(usage, accounts) { // spare Claude account with quota first (c2-c4 before c1)
        if (!picked) UsageText.spare(usage, accounts.firstOrNull { it.tool == "claude" }?.alias)?.let { alias = it }
    }
    LaunchedEffect(path, hidden, conn) {
        if (conn != Conn.Connected) return@LaunchedEffect
        try {
            listing = Listing.fromJson(Relay.client.rpc("list_dirs", JSONObject().put("path", path).put("show_hidden", hidden)))
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val cwd = listing?.path ?: path

    Scaffold(topBar = { TopAppBar(title = { Text("New chat", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = { BackButton(onBack) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = { ConnBadge() }) },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComposeBar(prompt, { prompt = it }, "First prompt (optional)")
                    Button({
                        launching = true
                        scope.launch {
                            try {
                                Relay.settings.update { it.copy(lastAlias = alias) }
                                onLaunched(Relay.launch(alias, cwd, prompt.trim().ifBlank { null }))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack.showSnackbar("Launch failed: ${e.message}") }
                            launching = false
                        }
                    }, Modifier.fillMaxWidth(), enabled = alias.isNotBlank() && listing != null && !launching) {
                        if (launching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Start in ${shortPath(cwd)}")
                    }
                }
            }
        }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner()
            SectionLabel("Account")
            AccountPicker(accounts, usage, alias) { alias = it; picked = true }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            // breadcrumb + actions
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ listing?.parent?.let { path = it } }, enabled = listing?.parent != null) { Icon(Icons.Default.ArrowUpward, "up") }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true), verticalAlignment = Alignment.CenterVertically) {
                    val segs = cwd.trim('/').split('/').filter { it.isNotEmpty() }
                    TextButton({ path = "/" }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("/") }
                    segs.forEachIndexed { i, s ->
                        TextButton({ path = "/" + segs.take(i + 1).joinToString("/") }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(s, fontFamily = FontFamily.Monospace) }
                        if (i < segs.lastIndex) Text("/")
                    }
                }
                val fav = cwd in prefs.favDirs
                IconButton({ scope.launch { Relay.settings.update { it.copy(favDirs = if (fav) it.favDirs - cwd else it.favDirs + cwd) } } }) {
                    Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, "favourite")
                }
                IconButton({ newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "new folder") }
                IconButton({ hidden = !hidden }) { Icon(if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, "hidden folders") }
            }
            LazyColumn(Modifier.weight(1f)) {
                error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (prefs.favDirs.isNotEmpty()) {
                    item { SectionLabel("Favourites") }
                    items(prefs.favDirs.sorted(), key = { "f$it" }) { d -> PathRow(Icons.Default.Star, shortPath(d), d) { path = d } }
                }
                if (recents.isNotEmpty()) {
                    item { SectionLabel("Recent") }
                    items(recents.take(10), key = { "r${it.path}" }) { r -> PathRow(Icons.Default.History, shortPath(r.path), r.tools.joinToString(" · ")) { path = r.path } }
                }
                item { SectionLabel("Folders") }
                items(listing?.dirs.orEmpty(), key = { "d${it.path}" }) { d ->
                    PathRow(if (d.isGit) Icons.Default.Source else Icons.Default.Folder, d.name, if (d.isGit) "git" else "") { path = d.path }
                }
                if (listing != null && listing!!.dirs.isEmpty()) item { Text("No subfolders.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("New folder in ${shortPath(cwd)}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton({
                    val n = name.trim().trim('/')
                    newFolder = false
                    if (n.isNotEmpty()) scope.launch {
                        runCatching { Relay.client.rpc("mkdir", JSONObject().put("path", cwd.trimEnd('/') + "/" + n)).getString("path") }
                            .onSuccess { path = it }.onFailure { snack.showSnackbar("mkdir failed: ${it.message}") }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun SectionLabel(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun PathRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
        supportingContent = if (sub.isNotBlank()) ({ Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Account chips with each account's usage headroom; the tool decides the tint like the tag in the overview. */
@Composable
fun AccountPicker(accounts: List<Account>, usage: List<AccountUsage>, selected: String, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            val hint = UsageText.short(usage.firstOrNull { it.alias == a.alias })
            val on = selected == a.alias
            androidx.compose.material3.Surface({ onPick(a.alias) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = if (on) cs.primaryContainer.copy(alpha = 0.6f) else cs.background,
                border = androidx.compose.foundation.BorderStroke(if (on) 1.5.dp else 1.dp, if (on) cs.primary else cs.outlineVariant)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountTag(a.alias)
                        Text(a.label + if (!a.loggedIn) " (not logged in)" else "", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (accounts.isEmpty()) Text("No accounts loaded yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
    }
}
