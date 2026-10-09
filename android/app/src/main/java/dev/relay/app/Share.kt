package dev.relay.app

import org.json.JSONObject
import java.io.InputStream

/** Pure logic of "share to a session" (JVM-tested). */
object ShareLogic {
    const val CHUNK = 512 * 1024
    const val MAX_BYTES = 50L * 1024 * 1024

    /** "<message>\n\n<path>\n<path>" ; without files the shared text is the body ("<message>\n\n<text>"). */
    fun body(message: String, paths: List<String>, text: String?): String {
        val m = message.trim()
        val rest = if (paths.isNotEmpty()) paths.joinToString("\n") else text?.trim().orEmpty()
        return if (m.isEmpty()) rest else if (rest.isEmpty()) m else "$m\n\n$rest"
    }

    fun canSend(files: Int, text: String?, message: String) = files > 0 || !text.isNullOrBlank() || message.isNotBlank()

    fun cleanName(raw: String?, mime: String?, n: Int): String {
        val base = raw.orEmpty().substringAfterLast('/').trim().ifBlank {
            val ext = mime?.substringAfter('/', "")?.substringBefore(';')?.takeIf { it.isNotBlank() && it != "*" && it.all { c -> c.isLetterOrDigit() } }
            "shared-$n" + (ext?.let { ".$it" } ?: "")
        }
        return base.take(120)
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Sessions that can receive a share: running ones; the last opened one first (then waiting, then newest), filtered by [query]. */
    fun targets(sessions: List<Session>, lastKey: String, query: String): List<Session> {
        val l = Grouping.list(sessions.filter { !it.background }, SessionFilter.LIVE).filter { Overview.matches(it, query) }
        return l.filter { it.matches(lastKey) } + l.filter { !it.matches(lastKey) }
    }
}

/** One file to upload: display name, size (-1 unknown) and a way to read it. */
class UploadItem(val name: String, val size: Long, val open: () -> InputStream)

object Uploads {
    /**
     * `upload_begin` -> in-order `upload_chunk`s of at most [chunk] raw bytes -> `upload_end`; returns the path on the Desktop.
     * [call] is the rpc; [progress] gets the bytes sent so far.
     */
    suspend fun upload(item: UploadItem, call: suspend (String, JSONObject) -> JSONObject, chunk: Int = ShareLogic.CHUNK, progress: (Long) -> Unit = {}): String {
        require(item.size in 0..ShareLogic.MAX_BYTES) { "${item.name} is larger than 50 MB" }
        val id = call("upload_begin", JSONObject().put("name", item.name).put("size", item.size)).getString("upload_id")
        var sent = 0L
        var seq = 0
        item.open().use { input ->
            val buf = ByteArray(chunk)
            while (true) {
                val n = readFully(input, buf)
                if (n <= 0) break
                call("upload_chunk", JSONObject().put("upload_id", id).put("seq", seq++).put("data_b64", java.util.Base64.getEncoder().encodeToString(buf.copyOf(n))))
                sent += n; progress(sent)
                if (n < chunk) break
            }
        }
        return call("upload_end", JSONObject().put("upload_id", id)).getString("path")
    }

    /** Progress text: the file's name at its start, then the percentage. */
    fun label(n: Int, total: Int, name: String, sent: Long, size: Long) =
        if (sent <= 0) "Uploading $n/$total $name" else "Uploading $n/$total · ${if (size > 0) 100 * sent / size else 100}%"

    /**
     * Uploads [items] one after the other and returns their Desktop paths in order. Files of unknown size are copied to [tmp] first
     * (`upload_begin` needs the size). [progress] gets a label and the overall fraction.
     */
    suspend fun all(items: List<UploadItem>, tmp: java.io.File, call: suspend (String, JSONObject) -> JSONObject, chunk: Int = ShareLogic.CHUNK,
                    progress: (String, Float) -> Unit = { _, _ -> }): List<String> {
        val temps = ArrayList<java.io.File>()
        try {
            var total = items.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var done = 0L
            return items.mapIndexed { n, raw ->
                val u = if (raw.size >= 0) raw else java.io.File.createTempFile("share", ".bin", tmp).also { f ->
                    temps += f; raw.open().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }.let { f -> UploadItem(raw.name, f.length()) { f.inputStream() } }
                if (raw.size < 0) total += u.size
                progress(label(n + 1, items.size, u.name, 0, u.size), done.toFloat() / total)
                upload(u, call, chunk) { sent -> progress(label(n + 1, items.size, u.name, sent, u.size), (done + sent).toFloat() / total) }
                    .also { done += u.size }
            }
        } finally { temps.forEach { it.delete() } }
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) { val n = i.read(b, off, b.size - off); if (n < 0) break; off += n }
        return off
    }
}
