package dev.relay.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class MemoryTest {
    private val json = """{"total_mb":15900,"available_mb":1500.5,"swap_total_mb":8000,"swap_used_mb":2100,
        "sessions":[{"key":"claude:c1:a","title":"Relay","alias":"c1","tool":"claude","cwd":"/home/x","state":"busy","closable":true,"mb":1250.4,"swap_mb":30},
                    {"key":"claude:c2:b","title":"","alias":"c2","tool":"claude","cwd":"/tmp","state":"idle","closable":false,"mb":0,"swap_mb":0}],
        "others":[{"name":"firefox","mb":900,"swap_mb":0,"count":12}],
        "closed":[{"key":"claude:c3:c","title":"Old","alias":"c3","tool":"claude","id":"c","cwd":"/x","closed_at":1000.0,"mb":800}]}"""

    @Test fun parses() {
        val i = MemoryInfo.fromJson(JSONObject(json))
        assertEquals(2, i.sessions.size); assertEquals(1250.4, i.sessions[0].mb, 0.01); assertFalse(i.sessions[1].closable)
        assertEquals("b", i.sessions[1].name); assertEquals("firefox", i.others[0].name); assertEquals(12, i.others[0].count)
        assertEquals("Old", i.closed[0].name); assertEquals(14399.5, i.usedMb, 0.01)
        assertTrue(MemText.low(i))
    }
    @Test fun emptyReplyIsSafe() { val i = MemoryInfo.fromJson(JSONObject("{}")); assertTrue(i.sessions.isEmpty()); assertFalse(MemText.low(i)) }
    @Test fun sizes() {
        assertEquals("–", MemText.size(0.0)); assertEquals("340 MB", MemText.size(340.7)); assertEquals("1.2 GB", MemText.size(1250.0)); assertEquals("1 MB", MemText.size(0.4 + 0.2))
        assertEquals("", MemText.swap(0.0)); assertEquals(" + 512 MB swap", MemText.swap(512.0))
    }
    @Test fun fractions() {
        assertEquals(0f, MemText.fraction(0.0, 100.0)); assertEquals(1f, MemText.fraction(100.0, 100.0)); assertEquals(0.5f, MemText.fraction(50.0, 100.0)); assertEquals(0.03f, MemText.fraction(0.1, 100.0))
    }
    @Test fun closeTextWarnsWhenBusy() {
        val idle = MemText.closeText("Relay", 1250.0, "idle")
        assertTrue(idle.contains("Close Relay?") && idle.contains("frees about 1.2 GB") && idle.contains("can be reopened")); assertFalse(idle.contains("interrupted"))
        assertTrue(MemText.closeText("Relay", 0.0, "busy").let { it.contains("frees its memory") && it.contains("interrupted") })
    }
    @Test fun closedLine() {
        val c = MemClosed("k", "t", "c1", "claude", "id", "/x", 1000.0, 800.0)
        assertEquals("closed 5 min ago · 800 MB", MemText.closedLine(c, 1300.0)); assertEquals("closed just now · 800 MB", MemText.closedLine(c, 1010.0))
        assertEquals("closed 2 h ago", MemText.closedLine(c.copy(mb = 0.0), 1000.0 + 7300))
    }
}

class AppLogTest {
    private fun store(): AppLogStore = AppLogStore(File.createTempFile("applog", ".jsonl").also { it.delete() })

    @Test fun addPendingDrop() {
        val s = store()
        assertTrue(s.add("warn", "a", "one", "", "v1", 100.0)); assertTrue(s.add("crash", "crash", "two", "stack\nline", "v1", 200.0)); assertTrue(s.add("warn", "b", "three", "", "v1", 300.0))
        val p = s.pending(2)
        assertEquals(listOf("one", "two"), p.map { it.getString("message") }); assertEquals("stack\nline", p[1].getString("stack")); assertEquals("v1", p[0].getString("app_version"))
        s.drop(2)
        assertEquals(listOf("three"), s.pending().map { it.getString("message") })
        s.drop(5); assertEquals(0, s.count())
    }
    @Test fun repeatsWithinAMinuteAreDropped() {
        val s = store()
        assertTrue(s.add("warn", "t", "same", "", "v", 100.0)); assertFalse(s.add("warn", "t", "same", "", "v", 130.0)); assertTrue(s.add("warn", "t", "same", "", "v", 200.0))
        assertEquals(2, s.count())
    }
    @Test fun capsMessageAndStack() {
        val s = store(); s.add("warn", "t", "x".repeat(1000), "y".repeat(10_000), "v")
        val e = s.pending()[0]; assertEquals(400, e.getString("message").length); assertEquals(6000, e.getString("stack").length)
    }
    @Test fun brokenLinesAreSkipped() {
        val f = File.createTempFile("applog", ".jsonl"); f.writeText("not json\n{\"message\":\"ok\"}\n")
        assertEquals(listOf("ok"), AppLogStore(f).pending().map { it.getString("message") })
    }
}

class ShareTest {
    @Test fun body() {
        assertEquals("look\n\n/up/a.png\n/up/b.png", ShareLogic.body(" look ", listOf("/up/a.png", "/up/b.png"), null))
        assertEquals("/up/a.png", ShareLogic.body("", listOf("/up/a.png"), "ignored caption"))
        assertEquals("https://x.y", ShareLogic.body("", emptyList(), " https://x.y "))
        assertEquals("what is this?\n\nhttps://x.y", ShareLogic.body("what is this?", emptyList(), "https://x.y"))
    }
    @Test fun canSend() { assertFalse(ShareLogic.canSend(0, "  ", "")); assertTrue(ShareLogic.canSend(1, null, "")); assertTrue(ShareLogic.canSend(0, "t", "")) }
    @Test fun names() {
        assertEquals("a.pdf", ShareLogic.cleanName("x/y/a.pdf", null, 1)); assertEquals("shared-2.png", ShareLogic.cleanName(null, "image/png", 2)); assertEquals("shared-3", ShareLogic.cleanName(" ", "*/*", 3))
        assertEquals("3 KB", ShareLogic.sizeLabel(3500)); assertEquals("1.5 MB", ShareLogic.sizeLabel(1_572_864)); assertEquals("", ShareLogic.sizeLabel(-1))
    }
    private fun s(key: String, state: String = "idle", tmux: String? = "t-$key", act: Double = 1.0) =
        Session(key, "claude", "c1", key, "c1", "/w", "T $key", state, state != "exited", true, tmux, act, "", 1)
    @Test fun targetsLastOpenedFirstAndNoEnded() {
        val l = listOf(s("a", act = 3.0), s("b", act = 2.0), s("c", state = "exited"), s("d", state = "needs_input", act = 1.0))
        assertEquals(listOf("b", "d", "a"), ShareLogic.targets(l, "b", "").map { it.key })
        assertEquals(listOf("d", "a", "b"), ShareLogic.targets(l, "", "").map { it.key })
        assertEquals(listOf("b"), ShareLogic.targets(l, "tmux:t-d", "T b").map { it.key })
    }

    @Test fun uploadsInOrderInChunks() = runBlocking {
        val data = ByteArray(2_500) { (it % 251).toByte() }
        val calls = ArrayList<Pair<String, JSONObject>>()
        val path = Uploads.upload(UploadItem("a.bin", data.size.toLong()) { ByteArrayInputStream(data) }, { m, p ->
            calls += m to p
            when (m) { "upload_begin" -> JSONObject().put("upload_id", "u1"); "upload_end" -> JSONObject().put("path", "/up/a.bin"); else -> JSONObject().put("received", 1) }
        }, chunk = 1_000)
        assertEquals("/up/a.bin", path)
        assertEquals(listOf("upload_begin", "upload_chunk", "upload_chunk", "upload_chunk", "upload_end"), calls.map { it.first })
        assertEquals("a.bin", calls[0].second.getString("name")); assertEquals(2500L, calls[0].second.getLong("size"))
        assertEquals(listOf(0, 1, 2), calls.filter { it.first == "upload_chunk" }.map { it.second.getInt("seq") })
        val back = calls.filter { it.first == "upload_chunk" }.flatMap { java.util.Base64.getDecoder().decode(it.second.getString("data_b64")).toList() }
        assertEquals(data.toList(), back); assertEquals("u1", calls[4].second.getString("upload_id"))
    }
    @Test fun exactMultipleAndEmpty() = runBlocking {
        for ((n, chunks) in listOf(2000 to 2, 0 to 0)) {
            val seen = ArrayList<String>()
            Uploads.upload(UploadItem("f", n.toLong()) { ByteArrayInputStream(ByteArray(n)) }, { m, _ -> seen += m; JSONObject().put("upload_id", "u").put("path", "p") }, chunk = 1000)
            assertEquals(chunks, seen.count { it == "upload_chunk" })
        }
    }
    @Test fun failureStopsAndThrows() {
        var chunks = 0
        val r = runCatching { runBlocking {
            Uploads.upload(UploadItem("f", 3000) { ByteArrayInputStream(ByteArray(3000)) }, { m, _ ->
                if (m == "upload_chunk" && ++chunks == 2) throw RpcException("disk full"); JSONObject().put("upload_id", "u").put("path", "p") }, chunk = 1000)
        } }
        assertEquals("disk full", r.exceptionOrNull()?.message); assertEquals(2, chunks)
    }
    @Test fun tooBigIsRefusedBeforeAnyCall() {
        var calls = 0
        val r = runCatching { runBlocking { Uploads.upload(UploadItem("big", ShareLogic.MAX_BYTES + 1) { ByteArrayInputStream(ByteArray(0)) }, { _, _ -> calls++; JSONObject() }) } }
        assertTrue(r.isFailure); assertEquals(0, calls)
    }
}

class NotifyPolicyTest {
    @Test fun defaultsPopUp() {
        val p = Prefs()
        assertEquals(Alert.POPUP, NotifyPolicy.alert(Kind.READY, p, "k")); assertEquals(Alert.POPUP, NotifyPolicy.alert(Kind.NEEDS_INPUT, p, "k")); assertEquals(Alert.POPUP, NotifyPolicy.alert(Kind.ENDED, p, "k"))
    }
    @Test fun readyModes() {
        assertEquals(Alert.SILENT, NotifyPolicy.alert(Kind.READY, Prefs(notifyReady = "silent"), "k")); assertEquals(Alert.NONE, NotifyPolicy.alert(Kind.READY, Prefs(notifyReady = "off"), "k"))
        assertEquals(Alert.POPUP, NotifyPolicy.alert(Kind.NEEDS_INPUT, Prefs(notifyReady = "off"), "k"))
    }
    @Test fun inputSilent() = assertEquals(Alert.SILENT, NotifyPolicy.alert(Kind.NEEDS_INPUT, Prefs(notifyInput = "silent"), "k"))
    @Test fun mutedSessionGetsNothing() {
        val p = Prefs(muted = setOf("k"))
        Kind.values().forEach { assertEquals(Alert.NONE, NotifyPolicy.alert(it, p, "k")); assertEquals(Alert.POPUP, NotifyPolicy.alert(it, p, "other")) }
    }
}
