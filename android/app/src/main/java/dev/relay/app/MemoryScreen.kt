package dev.relay.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** RAM monitor: what the Desktop's memory is used for, with close/reopen for sessions. Refreshes every 10 s while visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val info by MemoryModel.info.collectAsState()
    val error by MemoryModel.error.collectAsState()
    val loading by MemoryModel.loading.collectAsState()
    val conn by Relay.client.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conn) {
        if (conn != Conn.Connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { MemoryModel.load(); delay(10_000) } }
    }
    var closeTarget by remember { mutableStateOf<MemSession?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Memory", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton({ scope.launch { MemoryModel.load() } }) { Icon(Icons.Default.Refresh, "refresh") }
            })
    }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner()
            PullToRefreshBox(false, { scope.launch { MemoryModel.load() } }, Modifier.weight(1f)) {
                val i = info
                if (i == null) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(error ?: if (conn == Conn.Connected) "Measuring… (takes a moment)" else "Not connected.", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                } else MemoryContent(i, System.currentTimeMillis() / 1000.0, error, { closeTarget = it }, { c ->
                    scope.launch {
                        runCatching { Relay.reopen(c.key) }.onSuccess { onOpen(it) }.onFailure { snack.showSnackbar("Reopen failed: ${it.message}") }
                    }
                })
            }
        }
    }
    closeTarget?.let { s ->
        CloseDialog(s.name, s.mb, s.state, { closeTarget = null }) {
            closeTarget = null
            scope.launch {
                runCatching { Relay.closeSession(s.key, s.mb) }.onSuccess { MemoryModel.load() }.onFailure { snack.showSnackbar("Close failed: ${it.message}") }
            }
        }
    }
}

@Composable
fun CloseDialog(name: String, mb: Double, state: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Close session?") }, text = { Text(MemText.closeText(name, mb, state)) },
        confirmButton = { TextButton(onConfirm) { Text("Close", color = if (state == "busy") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun MemoryContent(i: MemoryInfo, now: Double, error: String?, onClose: (MemSession) -> Unit, onReopen: (MemClosed) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val max = i.sessions.maxOfOrNull { it.mb } ?: 0.0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MeterRow("RAM", i.usedMb, i.totalMb, "${MemText.size(i.usedMb)} of ${MemText.size(i.totalMb)} used · ${MemText.size(i.availableMb)} free", MemText.low(i))
                    if (i.swapTotalMb > 0) MeterRow("Swap", i.swapUsedMb, i.swapTotalMb, "${MemText.size(i.swapUsedMb)} of ${MemText.size(i.swapTotalMb)} used", i.swapUsedMb > i.swapTotalMb * 0.8)
                    if (MemText.low(i)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = cs.error)
                        Text("Less than 2 GB available: close a session below to free memory.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                }
            }
        }
        error?.let { e -> item { Text("Refresh failed: $e", color = cs.error, style = MaterialTheme.typography.bodySmall) } }
        item { Head("Sessions", "${i.sessions.size}") }
        if (i.sessions.isEmpty()) item { Text("No live sessions.", style = MaterialTheme.typography.bodyMedium, color = cs.outline) }
        items(i.sessions, key = { "s" + it.key }) { SessionMemRow(it, max, onClose) }
        if (i.others.isNotEmpty()) {
            item { Head("Other programs", "read-only") }
            items(i.others, key = { "o" + it.name }) { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name + if (o.count > 1) " ×${o.count}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(MemText.size(o.mb) + MemText.swap(o.swapMb), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        if (i.closed.isNotEmpty()) {
            item { Head("Closed", "${i.closed.size}") }
            items(i.closed, key = { "c" + it.key }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AccountTag(c.alias)
                        }
                        Text(MemText.closedLine(c, now), style = MaterialTheme.typography.labelSmall, color = cs.outline)
                    }
                    FilledTonalButton({ onReopen(c) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Reopen") }
                }
            }
        }
    }
}

@Composable
private fun Head(t: String, n: String) = Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.weight(1f))
    Text(n, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun MeterRow(label: String, used: Double, total: Double, text: String, warn: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelMedium, color = if (warn) cs.error else cs.onSurfaceVariant)
        }
        LinearProgressIndicator({ if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().height(10.dp),
            color = if (warn) cs.error else cs.primary, trackColor = cs.surfaceContainerHighest, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
private fun SessionMemRow(s: MemSession, max: Double, onClose: (MemSession) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StateDot(s.state)
                Text(s.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AccountTag(s.alias)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator({ MemText.fraction(s.mb, max) }, Modifier.weight(1f).height(5.dp), color = cs.primary.copy(alpha = 0.8f),
                    trackColor = cs.surfaceContainerHighest, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                Text(MemText.size(s.mb) + if (s.swapMb >= 1) " (+${MemText.size(s.swapMb)})" else "", Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Text(listOf(Overview.statusOf(s.state), shortPath(s.cwd)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.closable) OutlinedButton({ onClose(s) }, Modifier.padding(start = 10.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Close") }
    }
}
