package dev.relay.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UsageWindow(val name: String, val pct: Double, val resetsAt: Double?)
data class AccountUsage(val alias: String, val tool: String, val label: String, val plan: String?, val windows: List<UsageWindow>,
                        val error: String?, val checkedAt: Double) {
    companion object {
        fun fromJson(o: JSONObject) = AccountUsage(o.optString("alias"), o.optString("tool"), o.optString("label"), o.str("plan"),
            o.optJSONArray("windows").objects().map { UsageWindow(it.optString("name"), it.optDouble("pct", 0.0),
                it.optDouble("resets_at").takeIf { v -> !v.isNaN() && v > 0 }) },
            o.str("error"), o.optDouble("checked_at", 0.0))
    }
}

/** Usage limits per account (gateway `usage`: the optional pool helper for Claude, Codex's own logs). */
object UsageModel {
    val accounts = MutableStateFlow<List<AccountUsage>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    suspend fun load(refresh: Boolean = false, renew: String? = null) {
        loading.value = true
        try {
            val p = JSONObject().put("refresh", refresh).apply { renew?.let { put("renew", it) } }
            accounts.value = Relay.client.rpc("usage", p, 120_000).optJSONArray("accounts").objects().map(AccountUsage::fromJson)
            error.value = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.message } finally { loading.value = false }
    }
}

/** Pure formatting (unit-tested). */
object UsageText {
    /** "43 % · 51 %" for pickers. */
    fun short(u: AccountUsage?): String = when {
        u == null -> ""
        u.error != null -> u.error.substringBefore(" (")
        else -> u.windows.joinToString(" · ") { "${it.name} ${it.pct.toInt()}%" }
    }
    fun level(pct: Double) = when { pct >= 90 -> 2; pct >= 70 -> 1; else -> 0 }

    /** Policy: start Claude work on a spare account (not the main one) while it has 5-hour quota left. */
    fun spare(usage: List<AccountUsage>, main: String?): String? = usage
        .filter { it.tool == "claude" && it.alias != main && it.error == null && it.windows.size >= 2 }
        .filter { it.windows[0].pct < 85 && it.windows[1].pct < 90 }
        .minByOrNull { it.windows[0].pct }?.alias
    fun reset(at: Double?, now: Long = System.currentTimeMillis()): String {
        if (at == null) return "no reset pending"
        val ms = (at * 1000).toLong()
        val left = ms - now
        if (left <= 0) return "resets now"
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        if (left < 24 * 3_600_000L) {
            val h = left / 3_600_000; val m = (left / 60_000) % 60
            return "resets in " + (if (h > 0) "$h h $m min" else "$m min") + " ($clock)"
        }
        return "resets " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    val accounts by UsageModel.accounts.collectAsState()
    val error by UsageModel.error.collectAsState()
    val loading by UsageModel.loading.collectAsState()
    val live by Relay.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { UsageModel.load(); delay(120_000) } } // only while the screen is open

    Scaffold(topBar = {
        TopAppBar(title = { Text("Accounts", style = MaterialTheme.typography.headlineSmall) }, navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton({ scope.launch { UsageModel.load(refresh = true) } }) { Icon(Icons.Default.Refresh, "refresh") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner()
            AccountsContent(accounts, error, loading, { a -> live.count { it.alias == a && it.alive } }) { a -> scope.launch { UsageModel.load(renew = a) } }
        }
    }
}

@Composable
fun AccountsContent(accounts: List<AccountUsage>, error: String?, loading: Boolean, running: (String) -> Int, renew: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        if (accounts.isEmpty() && !loading && error == null) item { Text("No data yet.", color = MaterialTheme.colorScheme.outline) }
        items(accounts, key = { it.alias }) { u -> AccountCard(u, running(u.alias)) { renew(u.alias) } }
        if (accounts.isNotEmpty()) item {
            Text("Claude: usage helper (same numbers as /usage). Codex: its latest session log. Checked " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((accounts.maxOf { it.checkedAt } * 1000).toLong())),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AccountCard(u: AccountUsage, running: Int, renew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = cs.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountTag(u.alias)
                Text(u.label + (u.plan?.let { " · $it" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (running > 0) Text("$running running", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }
            u.error?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.outline)
                    if (e.contains("expired")) TextButton(renew) { Text("Refresh login") }
                }
            }
            u.windows.forEach { w ->
                val color = when (UsageText.level(w.pct)) { 2 -> cs.error; 1 -> Color(0xFFE0A030); else -> cs.primary }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.name, Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator({ (w.pct / 100).toFloat().coerceIn(0f, 1f) }, Modifier.weight(1f).height(8.dp), color = color, trackColor = cs.surfaceContainerHighest,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                    Text("  ${w.pct.toInt()}%", Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge, color = color)
                }
                Text(UsageText.reset(w.resetsAt), Modifier.padding(start = 64.dp), style = MaterialTheme.typography.labelSmall, color = cs.outline)
            }
        }
    }
}
