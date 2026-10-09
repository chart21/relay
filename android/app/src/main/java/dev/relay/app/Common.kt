package dev.relay.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@Composable
fun BackButton(onBack: () -> Unit) = IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }

@Composable
fun ConnBadge() {
    val c by Relay.client.state.collectAsState()
    val (text, color) = when (c) {
        Conn.Connected -> "online" to MaterialTheme.colorScheme.primary
        Conn.Connecting -> "connecting" to MaterialTheme.colorScheme.tertiary
        Conn.Off -> "not set up" to MaterialTheme.colorScheme.outline
        Conn.NoNetwork -> "no network" to MaterialTheme.colorScheme.outline
        is Conn.Error -> "offline" to MaterialTheme.colorScheme.error
    }
    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Shown under the app bar while the link to the PC is down. */
@Composable
fun ConnectionBanner() {
    val c by Relay.client.state.collectAsState()
    if (c == Conn.Connected) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val text = when (val s = c) {
                Conn.Off -> "Not set up. Open Settings."
                Conn.NoNetwork -> "No network. Waiting for a connection."
                Conn.Connecting -> "Connecting to the PC…"
                is Conn.Error -> "Offline: ${s.msg}"
                else -> ""
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (c == Conn.Connecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else if (c is Conn.Error) TextButton({ Relay.client.reconnect() }) { Text("Retry") }
        }
    }
}

@Composable
fun StateChip(state: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (label, bg, fg) = when (state) {
        "needs_input" -> Triple("needs input", cs.errorContainer, cs.onErrorContainer)
        "busy" -> Triple("busy", cs.secondaryContainer, cs.onSecondaryContainer)
        "exited" -> Triple("exited", cs.surfaceVariant, cs.onSurfaceVariant)
        else -> Triple(state.ifBlank { "idle" }, cs.tertiaryContainer, cs.onTertiaryContainer)
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier.alpha(if (state == "exited") 0.6f else 1f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state == "busy") CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = fg)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
