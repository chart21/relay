package dev.relay.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Multi-line text field with dictation (mic), an optional [leading] slot (attach) and send; shared by the chat reply bar, Jarvis and the new-session prompt. */
@Composable
fun ComposeBar(text: String, onText: (String) -> Unit, hint: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
               canSend: Boolean = text.isNotBlank(), onSend: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val cs = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerLowest, tonalElevation = 0.dp,
        shadowElevation = 2.dp, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            leading?.invoke()
            TextField(text, onText, Modifier.weight(1f), placeholder = { Text(hint, color = cs.outline) }, minLines = 1, maxLines = 6,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
            IconButton({
                if (listening) { job?.cancel(); return@IconButton }
                job = scope.launch {
                    listening = true
                    try {
                        when (val h = Stt.listen()) {
                            is Heard.Text -> onText((text + " " + h.text).trim())
                            Heard.Silence -> Toast.makeText(ctx, "Didn't catch that", Toast.LENGTH_SHORT).show()
                            is Heard.Failed -> Toast.makeText(ctx, h.msg, Toast.LENGTH_LONG).show()
                        }
                    } finally { listening = false }
                }
            }, Modifier.size(44.dp)) { Icon(Icons.Default.Mic, "dictate", tint = if (listening) cs.error else cs.onSurfaceVariant) }
            if (onSend != null) {
                val ready = canSend
                FilledIconButton({ onSend(text) }, Modifier.padding(bottom = 2.dp).size(40.dp), enabled = ready,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary, contentColor = cs.onPrimary,
                        disabledContainerColor = cs.primary.copy(alpha = 0.3f), disabledContentColor = cs.onPrimary)) {
                    Icon(Icons.Default.ArrowUpward, "send")
                }
            }
        }
    }
}
