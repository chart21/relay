package dev.relay.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One hit of `transcript_search`; [index] works with `transcript`'s `from`. */
data class SearchMatch(val id: String, val index: Int, val role: String, val ts: Double, val snippet: String) {
    companion object {
        fun fromJson(o: JSONObject) = SearchMatch(o.str("id") ?: "", o.optInt("index", 0), o.str("role") ?: "assistant", o.optDouble("ts", 0.0), o.str("snippet") ?: "")
    }
}

/** Pure parts of the in-chat search (unit-tested). */
object ChatSearch {
    const val MIN = 2

    fun active(query: String) = query.trim().length >= MIN

    /** Case-insensitive, non-overlapping hits of [query] (trimmed) in [text]; empty when the query is too short. */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.length < MIN) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            out.add(i until i + q.length)
            from = i + q.length
        }
        return out
    }

    fun highlight(text: String, query: String, bg: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        ranges(text, query).forEach { addStyle(SpanStyle(background = bg), it.first, it.last + 1) }
    }

    /** Loading starts this many messages before a hit, so the hit has context above it. */
    fun fromIndex(index: Int) = (index - 3).coerceAtLeast(0)

    /** Next/previous hit, wrapping around; -1 when there are none. */
    fun step(cur: Int, delta: Int, n: Int) = if (n <= 0) -1 else ((cur + delta) % n + n) % n

    /**
     * Position in [rows] (newest first, as [ChatText.rows] returns it) of the row showing message [id]. A message without a
     * row of its own (blank after stripping) falls back to the nearest newer, then older, message that has one; -1 if none.
     */
    fun rowIndex(rows: List<ChatText.Row>, msgs: List<TMessage>, id: String): Int {
        val at = HashMap<String, Int>()
        rows.forEachIndexed { i, r -> at.putIfAbsent(r.id, i) }
        fun find(mid: String) = at[mid] ?: at["$mid-out"] ?: at["steps-$mid"] ?: -1
        val p = msgs.indexOfFirst { it.id == id }
        if (p < 0) return -1
        for (k in p until msgs.size) find(msgs[k].id).let { if (it >= 0) return it }
        for (k in p - 1 downTo 0) find(msgs[k].id).let { if (it >= 0) return it }
        return -1
    }

    fun loaded(msgs: List<TMessage>, id: String) = msgs.any { it.id == id }
}

/** Search state of one chat: debounced query, hits (newest first) and the hit being shown. */
class ChatSearchState {
    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
    var total by mutableIntStateOf(0)
    var current by mutableIntStateOf(-1)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var listOpen by mutableStateOf(true)

    suspend fun run(key: String, query: String) {
        if (!ChatSearch.active(query)) { matches = emptyList(); total = 0; current = -1; error = null; busy = false; return }
        busy = true
        try {
            val r = Relay.client.rpc("transcript_search", JSONObject().put("key", key).put("query", query.trim()).put("roles", "user,assistant").put("limit", 100))
            matches = r.optJSONArray("matches").objects().map(SearchMatch::fromJson)
            total = r.optInt("total_messages", 0); current = -1; error = null; listOpen = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "search failed" } finally { busy = false }
    }
}

private val dayFmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

/** The search field with the hit counter and previous/next/close. */
@Composable
fun ChatSearchBar(query: String, onQuery: (String) -> Unit, count: String, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit, focus: Boolean = true) {
    val req = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
    BackHandler(true, onClose)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, onQuery, Modifier.weight(1f).focusRequester(req).onPreviewKeyEvent { if (it.key == Key.Escape) { onClose(); true } else false },
            placeholder = { Text("Search this chat") }, singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            trailingIcon = { Text(count, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline) })
        IconButton(onPrev) { Icon(Icons.Default.KeyboardArrowUp, "older match") }
        IconButton(onNext) { Icon(Icons.Default.KeyboardArrowDown, "newer match") }
        IconButton(onClose) { Icon(Icons.Default.Close, "close search") }
    }
}

/** Hits as a short list: role, time and the snippet with the query marked. */
@Composable
fun ChatSearchResults(matches: List<SearchMatch>, query: String, current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val mark = cs.primary.copy(alpha = 0.28f)
    LazyColumn(modifier.fillMaxWidth().heightIn(max = 260.dp)) {
        itemsIndexed(matches, key = { i, m -> "${m.id}-$i" }) { i, m ->
            Column(Modifier.fillMaxWidth().clickable { onPick(i) }.background(if (i == current) cs.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text((if (m.role == "user") "You" else "Assistant") + (if (m.ts > 0) " · " + dayFmt.format(Date((m.ts * 1000).toLong())) else ""),
                    style = MaterialTheme.typography.labelSmall, color = cs.outline)
                Text(ChatSearch.highlight(m.snippet.trim(), query, mark), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = cs.outlineVariant)
        }
    }
}
