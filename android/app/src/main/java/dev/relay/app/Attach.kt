package dev.relay.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One file picked for the chat: [photo] = from the photo picker or the camera (large ones are re-encoded before upload). */
data class Attachment(val uri: String, val name: String, val size: Long, val mime: String?, val photo: Boolean) {
    val image get() = Attach.isImage(name, mime)
}

/** Pure logic of chat attachments (JVM-tested). */
object Attach {
    const val REENCODE_BYTES = 2_500_000L
    const val MAX_EDGE = 2560
    const val OUT_EDGE = 2048
    const val QUALITY = 85
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String, mime: String?) = mime?.startsWith("image/") == true || ext(name) in imageExt

    /** Photos are re-encoded; GIFs (animation) and non-images never. */
    fun reencodable(a: Attachment) = a.photo && a.image && a.mime?.endsWith("gif") != true && ext(a.name) != "gif"
    fun needsReencode(size: Long, w: Int, h: Int) = w > 0 && h > 0 && (size > REENCODE_BYTES || maxOf(w, h) > MAX_EDGE)

    /** Size with the long edge at [OUT_EDGE]; never enlarged. */
    fun targetSize(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= OUT_EDGE) return w to h
        val f = OUT_EDGE.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /** Power-of-two decoder subsampling that keeps the long edge at or above [target]. */
    fun sampleSize(w: Int, h: Int, target: Int = OUT_EDGE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    fun jpegName(name: String) = (name.substringBeforeLast('.', name).ifBlank { "photo" }) + ".jpg"

    /** EXIF orientation -> (clockwise degrees, mirrored horizontally first). */
    fun exifTransform(orientation: Int): Pair<Int, Boolean> = when (orientation) {
        2 -> 0 to true; 3 -> 180 to false; 4 -> 180 to true; 5 -> 90 to true; 6 -> 90 to false; 7 -> 270 to true; 8 -> 270 to false
        else -> 0 to false
    }

    fun canSend(text: String, files: Int, busy: Boolean) = !busy && (text.isNotBlank() || files > 0)

    /** The message that goes into the session: typed text, then one Desktop path per line. */
    fun body(text: String, paths: List<String>) = ShareLogic.body(text, paths, null)
}

/** Desktop path -> local image uri for files this phone uploaded in this app run (chat thumbnails). */
object LocalAttachments {
    private val uris = ConcurrentHashMap<String, String>()
    fun record(paths: List<String>, files: List<Attachment>) { paths.zip(files).forEach { (p, a) -> if (a.image) uris[p] = a.uri } }
    fun uri(path: String): String? = uris[path]
}

/** Composer state of one chat: picked files, upload progress. */
class AttachState {
    val items = mutableStateListOf<Attachment>()
    var busy by mutableStateOf(false); private set
    var label by mutableStateOf<String?>(null); private set
    var fraction by mutableFloatStateOf(0f); private set

    /** Prepares (re-encodes big photos) and uploads every attachment; returns the Desktop paths in order. Nothing is sent here. */
    suspend fun upload(ctx: Context, call: suspend (String, JSONObject) -> JSONObject): List<String> {
        val files = items.toList()
        val temps = ArrayList<File>()
        busy = true; fraction = 0f
        try {
            label = if (files.any { Attach.reencodable(it) }) "Preparing photos…" else null
            val prepared = withContext(Dispatchers.IO) { files.map { a -> AttachIo.prepare(ctx, a).also { (_, f) -> f?.let(temps::add) }.first } }
            val paths = Uploads.all(prepared, ctx.cacheDir, call) { l, f -> label = l; fraction = f }
            LocalAttachments.record(paths, files)
            return paths
        } finally { busy = false; label = null; temps.forEach { it.delete() } }
    }

    fun sending(on: Boolean) { busy = on; label = if (on) "Sending…" else null; fraction = 1f }
}

/** Android side: reading metadata, re-encoding, thumbnails. */
object AttachIo {
    fun describe(cr: ContentResolver, u: Uri, n: Int, photo: Boolean = false): Attachment {
        var name: String? = null
        var size = -1L
        runCatching { cr.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        } }
        val mime = cr.getType(u)
        return Attachment(u.toString(), ShareLogic.cleanName(name ?: u.lastPathSegment, mime, n), size, mime, photo)
    }

    /** The file as it will be uploaded; a temp file for a re-encoded photo (caller deletes it). */
    fun prepare(ctx: Context, a: Attachment): Pair<UploadItem, File?> {
        val cr = ctx.contentResolver
        val uri = Uri.parse(a.uri)
        fun open() = cr.openInputStream(uri) ?: throw IOException("cannot read ${a.name}")
        val plain = UploadItem(a.name, a.size) { open() } to null
        if (!Attach.reencodable(a)) return plain
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (!Attach.needsReencode(a.size, bounds.outWidth, bounds.outHeight)) return plain
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = Attach.sampleSize(bounds.outWidth, bounds.outHeight) }) } ?: return plain
        val (rot, mirror) = Attach.exifTransform(runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1))
        val (tw, th) = Attach.targetSize(bounds.outWidth, bounds.outHeight)
        val m = Matrix().apply {
            postScale(tw.toFloat() / decoded.width, th.toFloat() / decoded.height)
            if (mirror) postScale(-1f, 1f)
            postRotate(rot.toFloat())
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val f = File.createTempFile("photo", ".jpg", ctx.cacheDir)
        try { f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, Attach.QUALITY, it) } } catch (e: Exception) { f.delete(); throw e }
        return UploadItem(Attach.jpegName(a.name), f.length()) { f.inputStream() } to f
    }
}

/** Small bitmaps for chips and chat; [load] is replaceable for screenshots. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(40) {}
    var load: (Context, String, Int) -> Bitmap? = { ctx, uri, px -> decode(ctx, uri, px) }

    suspend fun get(ctx: Context, uri: String, px: Int): Bitmap? {
        cache.get("$uri@$px")?.let { return it }
        return withContext(Dispatchers.IO) { try { load(ctx, uri, px) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
            ?.also { cache.put("$uri@$px", it) }
    }

    private fun decode(ctx: Context, uri: String, px: Int): Bitmap? {
        val cr = ctx.contentResolver
        val u = Uri.parse(uri)
        runCatching { return cr.loadThumbnail(u, Size(px, px), null) }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, b) }
        return cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = Attach.sampleSize(b.outWidth, b.outHeight, px) }) }
    }
}
