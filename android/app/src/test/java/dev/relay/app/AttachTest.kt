package dev.relay.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AttachTest {
    private fun a(name: String, mime: String? = null, photo: Boolean = true, size: Long = 1) = Attachment("content://x/$name", name, size, mime, photo)

    @Test fun imageDetection() {
        assertTrue(Attach.isImage("IMG.JPG", null)); assertTrue(Attach.isImage("x", "image/png")); assertTrue(Attach.isImage("a.heic", null))
        assertFalse(Attach.isImage("report.pdf", "application/pdf")); assertFalse(Attach.isImage("noext", null))
    }
    @Test fun reencodeRules() {
        assertTrue(Attach.needsReencode(3_000_000, 1000, 800)); assertTrue(Attach.needsReencode(100_000, 3000, 1000))
        assertFalse(Attach.needsReencode(2_500_000, 2560, 1000)); assertFalse(Attach.needsReencode(9_000_000, 0, 0))
        assertTrue(Attach.reencodable(a("p.jpg", "image/jpeg")))
        assertFalse(Attach.reencodable(a("p.jpg", "image/jpeg", photo = false))); assertFalse(Attach.reencodable(a("anim.gif", "image/gif"))); assertFalse(Attach.reencodable(a("v.mp4", "video/mp4")))
    }
    @Test fun sizes() {
        assertEquals(2048 to 1536, Attach.targetSize(4000, 3000)); assertEquals(1536 to 2048, Attach.targetSize(3000, 4000)); assertEquals(1000 to 500, Attach.targetSize(1000, 500))
        assertEquals(1, Attach.sampleSize(3000, 2000)); assertEquals(2, Attach.sampleSize(4096, 3000)); assertEquals(4, Attach.sampleSize(8192, 6000)); assertEquals(1, Attach.sampleSize(100, 100))
        assertEquals("IMG_1.jpg", Attach.jpegName("IMG_1.heic")); assertEquals("noext.jpg", Attach.jpegName("noext"))
    }
    @Test fun exif() {
        assertEquals(90 to false, Attach.exifTransform(6)); assertEquals(270 to false, Attach.exifTransform(8)); assertEquals(0 to true, Attach.exifTransform(2)); assertEquals(0 to false, Attach.exifTransform(1))
    }
    @Test fun sendEnabled() {
        assertFalse(Attach.canSend("", 0, false)); assertTrue(Attach.canSend("", 1, false)); assertTrue(Attach.canSend(" hi ", 0, false)); assertFalse(Attach.canSend("hi", 2, true))
    }
    @Test fun bodyHasTextThenPaths() {
        assertEquals("look\n\n/p/a.png\n/p/b.pdf", Attach.body("look", listOf("/p/a.png", "/p/b.pdf"))); assertEquals("/p/a.png", Attach.body("  ", listOf("/p/a.png")))
    }
    @Test fun localMapOnlyForImages() {
        LocalAttachments.record(listOf("/up/a.png", "/up/b.pdf"), listOf(a("a.png", "image/png"), a("b.pdf", "application/pdf", photo = false)))
        assertEquals("content://x/a.png", LocalAttachments.uri("/up/a.png")); assertNull(LocalAttachments.uri("/up/b.pdf"))
    }

    private fun fake(log: MutableList<Pair<String, JSONObject>> = ArrayList()): suspend (String, JSONObject) -> JSONObject = { m, p ->
        log += m to p
        when (m) { "upload_begin" -> JSONObject().put("upload_id", "u-${p.getString("name")}"); "upload_end" -> JSONObject().put("path", "/up/" + p.getString("upload_id").drop(2)); else -> JSONObject().put("received", 1) }
    }
    @Test fun uploadAllKeepsOrderAndReportsProgress() = runBlocking {
        val log = ArrayList<Pair<String, JSONObject>>(); val labels = ArrayList<String>(); var last = 0f
        val tmp = createTempDir()
        val paths = Uploads.all(listOf(UploadItem("a.png", 3) { ByteArrayInputStream(ByteArray(3)) }, UploadItem("b.pdf", -1) { ByteArrayInputStream(ByteArray(10)) }), tmp, fake(log), chunk = 4) { l, f -> labels += l; last = f }
        assertEquals(listOf("/up/a.png", "/up/b.pdf"), paths)
        assertEquals(10L, log.first { it.second.optString("name") == "b.pdf" }.second.getLong("size"))
        assertEquals("Uploading 1/2 a.png", labels.first()); assertTrue(labels.any { it == "Uploading 2/2 · 100%" }); assertEquals(1f, last, 0.001f)
        assertEquals(0, tmp.listFiles()!!.size)
    }
    @Test fun uploadAllFailureThrowsNoPaths() {
        val r = runCatching { runBlocking { Uploads.all(listOf(UploadItem("big", ShareLogic.MAX_BYTES + 1) { ByteArrayInputStream(ByteArray(0)) }), createTempDir(), fake()) } }
        assertTrue(r.exceptionOrNull()?.message!!.contains("50 MB"))
    }
    @Test fun labels() { assertEquals("Uploading 1/3 x.jpg", Uploads.label(1, 3, "x.jpg", 0, 10)); assertEquals("Uploading 2/3 · 50%", Uploads.label(2, 3, "x", 5, 10)) }
}

class ChatAttachmentsTest {
    private val dir = "/home/dev/.local/share/notifyd/uploads/2026-10-08"

    @Test fun splitsPathsFromText() {
        val (t, f) = ChatText.attachments("see these\n\n$dir/shot.png\n$dir/tariffs.pdf")
        assertEquals("see these", t)
        assertEquals(listOf("shot.png" to true, "tariffs.pdf" to false), f.map { it.name to it.image }); assertEquals("$dir/shot.png", f[0].path)
    }
    @Test fun onlyPathsAndTilde() {
        val (t, f) = ChatText.attachments("~/.local/share/notifyd/uploads/2026-10-08/a.jpg")
        assertEquals("", t); assertEquals(1, f.size)
    }
    @Test fun otherPathsStayText() {
        val s = "edit /home/dev/workspace/a.kt and /etc/uploads/x.png\nnot /home/x/.local/share/notifyd/uploads/y.png in a sentence"
        assertEquals(s to emptyList<ChatText.Attached>(), ChatText.attachments(s))
    }
    @Test fun userRowAndPendingShowChips() {
        val body = "hi\n\n$dir/a name.png"
        val m = TMessage("u", "user", body, emptyList(), 1.0)
        val r = ChatText.rowsOf(m).single()
        assertEquals("hi", r.text); assertEquals("a name.png", r.files.single().name)
        val p = ChatText.rows(emptyList(), listOf(Pending(body, 5))).single()
        assertTrue(p.pending); assertEquals("hi", p.text); assertEquals(1, p.files.size)
        assertTrue(ChatText.delivered(Pending(body, 5), listOf(m)))
    }
}
