package dev.relay.app

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Paperclip with the menu: photos and videos, camera, files, clipboard image. */
@Composable
fun AttachButton(enabled: Boolean, onAdd: (List<Attachment>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var hasClipImage by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    fun add(uris: List<Uri>, photo: Boolean) { if (uris.isNotEmpty()) scope.launch {
        onAdd(withContext(Dispatchers.IO) { uris.mapIndexed { n, u -> AttachIo.describe(ctx.contentResolver, u, n + 1, photo && (ctx.contentResolver.getType(u)?.startsWith("video") != true)) } })
    } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it, true) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it, false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        shot?.let { (u, f) -> if (ok) add(listOf(u), true) else f.delete() }; shot = null
    }
    Box {
        IconButton({ hasClipImage = clipImage(ctx) != null; open = true }, Modifier.size(44.dp), enabled = enabled) {
            Icon(Icons.Default.AttachFile, "attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Photos and videos") }, { open = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) })
            DropdownMenuItem({ Text("Camera") }, {
                open = false
                val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "IMG_${System.currentTimeMillis()}.jpg")
                val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                shot = u to f; camera.launch(u)
            }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) })
            DropdownMenuItem({ Text("Files") }, { open = false; docs.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) })
            if (hasClipImage) DropdownMenuItem({ Text("Paste image") }, { open = false; clipImage(ctx)?.let { add(listOf(it), true) } }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) })
        }
    }
}

private fun clipImage(ctx: Context): Uri? {
    val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return null
    if (clip.description.hasMimeType("image/*").not()) return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
}

/** A thumbnail of [uri] (local image), or the image icon while it loads or when it can't be read. */
@Composable
fun Thumb(uri: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, uri, px) { value = Thumbs.get(ctx, uri, px) }
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** The picked files above the compose bar: thumbnails for images, icon + name + size for the rest, each removable. */
@Composable
fun AttachChips(items: List<Attachment>, enabled: Boolean, onRemove: (Attachment) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyRow(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(items, key = { it.uri }) { a ->
            Box {
                if (a.image) Thumb(a.uri, 64.dp, Modifier.padding(top = 6.dp, end = 6.dp))
                else Surface(Modifier.padding(top = 6.dp, end = 6.dp).height(64.dp).widthIn(max = 200.dp), shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = cs.primary)
                        Column {
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            ShareLogic.sizeLabel(a.size).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.outline) }
                        }
                    }
                }
                if (enabled) Surface({ onRemove(a) }, Modifier.align(Alignment.TopEnd).size(22.dp), shape = androidx.compose.foundation.shape.CircleShape, color = cs.inverseSurface) { Icon(Icons.Default.Close, "remove ${a.name}", Modifier.padding(4.dp), tint = cs.inverseOnSurface) }
            }
        }
    }
}

/** One progress line (upload or send) above the compose bar. */
@Composable
fun AttachProgress(label: String?, fraction: Float) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (fraction in 0.001f..0.999f) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Uploaded files inside a user message: the local thumbnail for images this phone sent, else a name chip. */
@Composable
fun SentFiles(files: List<ChatText.Attached>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            val local = if (f.image) LocalAttachments.uri(f.path) else null
            if (local != null) Thumb(local, 140.dp)
            else Surface(shape = RoundedCornerShape(10.dp), color = cs.surfaceContainerLowest, border = BorderStroke(1.dp, cs.outlineVariant)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (f.image) Icons.Default.Image else Icons.Default.InsertDriveFile, null, Modifier.size(18.dp), tint = cs.primary)
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
