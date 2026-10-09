package dev.relay.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Heard -> meant" (a correction) or just a term (heard empty); saved on the Desktop, which the app reloads. */
@Composable
fun TeachDialog(initialHeard: String = "", onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var heard by remember { mutableStateOf(initialHeard) }
    var meant by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Teach a word") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Heard wrongly (leave empty to just add a term)", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(heard, { heard = it }, Modifier.fillMaxWidth(), label = { Text("Heard") }, minLines = 1, maxLines = 4)
            OutlinedTextField(meant, { meant = it }, Modifier.fillMaxWidth(), label = { Text("Meant") }, singleLine = true)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton({
            scope.launch { runCatching { Vocab.add(heard, meant) }.onSuccess { onDismiss() }.onFailure { err = it.message ?: "failed (offline?)" } }
        }, enabled = meant.isNotBlank()) { Text("Add") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun SpeechRecognitionSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vocab by Vocab.data.collectAsState()
    var models by remember { mutableStateOf<ModelState?>(null) }
    var poll by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(poll) {
        models = Voice.checkModels(ctx)
        if (models?.pending?.isNotEmpty() == true) { delay(4_000); poll++ }
    }
    SpeechRecognitionContent(models, vocab, { l -> Voice.downloadModel(ctx, l); scope.launch { delay(1_500); poll++ } }) { adding = true }
    if (adding) TeachDialog { adding = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechRecognitionContent(m: ModelState?, vocab: VocabData, onDownload: (String) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speech recognition", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(m?.summary() ?: "Offline recognition: state unknown on this phone", style = MaterialTheme.typography.bodyMedium)
        if (m != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("de" to "German", "en" to "English").filter { m.missing(it.first) }.forEach { (l, n) -> OutlinedButton({ onDownload(l) }) { Text("Download $n") } }
        }
        Text("${vocab.terms.size} terms from the Desktop bias the recogniser; known mishearings are fixed afterwards.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
        vocab.corrections.forEach { (h, t) -> Text("$h  →  $t", style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onAdd) { Text("Add") }
    }
}

@Composable
fun LiveText(live: String) {
    if (live.isNotBlank()) Text("🎤 $live", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
