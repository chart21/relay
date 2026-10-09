package dev.relay.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paged transcript: newest page first, older pages on demand ("before" cursor), live appends while watched. */
class TranscriptModel(private val key: String) {
    val msgs = mutableStateListOf<TMessage>() // oldest first
    var more by mutableStateOf(true)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var cursor: String? = null
    private var anon = 0

    private fun norm(l: List<TMessage>) = l.map { if (it.id.isBlank()) it.copy(id = "~${anon++}") else it }

    suspend fun loadLatest() {
        loading = true
        try {
            val r = Relay.client.rpc("transcript", JSONObject().put("key", key).put("limit", 50))
            val list = r.optJSONArray("messages").objects().map(TMessage::fromJson)
            if (msgs.isEmpty()) { msgs.addAll(norm(list)); cursor = r.str("before"); more = cursor != null }
            else msgs.addAll(norm(TranscriptLogic.fresh(msgs, list)))
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    suspend fun loadOlder() {
        val c = cursor ?: return
        if (loading || !more) return
        loading = true
        try {
            val r = Relay.client.rpc("transcript", JSONObject().put("key", key).put("before", c).put("limit", 50))
            val list = norm(TranscriptLogic.fresh(msgs, r.optJSONArray("messages").objects().map(TMessage::fromJson)))
            msgs.addAll(0, list)
            cursor = r.str("before"); more = cursor != null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    /** Loads back to a search hit (`from` = [index] minus a little context) unless message [id] is loaded already. */
    suspend fun loadTo(id: String, index: Int) {
        val c = cursor
        if (ChatSearch.loaded(msgs, id) || c == null) return
        while (loading) delay(50)
        loading = true
        try {
            val r = Relay.client.rpc("transcript", JSONObject().put("key", key).put("before", c).put("from", ChatSearch.fromIndex(index)), 30_000)
            msgs.addAll(0, norm(TranscriptLogic.fresh(msgs, r.optJSONArray("messages").objects().map(TMessage::fromJson))))
            cursor = r.str("before"); more = cursor != null
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    fun append(list: List<TMessage>) { msgs.addAll(norm(TranscriptLogic.fresh(msgs, list))) }
}
