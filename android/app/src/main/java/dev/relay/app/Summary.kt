package dev.relay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** The 1-3 sentence summary of a session (rpc `speak_summary`), shown as a card at the bottom of its chat. */
class SummaryState {
    var open by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var text by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    suspend fun load(s: Session) {
        open = true; loading = true; error = null; text = ""
        try {
            val p = Relay.settings.flow.first()
            val r = Relay.client.rpc("speak_summary", JSONObject().put("key", s.key).put("name", s.name).put("lang", p.ttsLang), 90_000)
            text = r.optString("text").trim().ifBlank { throw IllegalStateException("empty summary") }
            if (p.speakReplies) Relay.speech.say(text)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w("summary", "summarize failed", e); error = e.message ?: "summary failed" } finally { loading = false }
    }
}

@Composable
fun SummaryCard(st: SummaryState, onSpeak: (String) -> Unit) = SummaryCardContent(st.loading, st.text, st.error, { st.open = false }, onSpeak)

@Composable
fun SummaryCardContent(loading: Boolean, text: String, error: String?, onClose: () -> Unit, onSpeak: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = cs.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.35f))) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Summary", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                if (text.isNotBlank()) IconButton({ onSpeak(text) }, Modifier.size(36.dp)) { Icon(Icons.Default.VolumeUp, "read aloud", Modifier.size(18.dp), tint = cs.outline) }
                IconButton(onClose, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "close", Modifier.size(18.dp), tint = cs.outline) }
            }
            Box(Modifier.padding(end = 10.dp)) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Summarizing…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    else -> Text(text, style = ReplyStyle)
                }
            }
        }
    }
}
