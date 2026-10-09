package dev.relay.app

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeTest {
    private val chat = NotifData("com.whatsapp", "WhatsApp", "0|com.whatsapp|1|null|10", 1_700_000_000_500, "Anna", "hi", "hi there", null, null,
        listOf(BMsg("Anna", "second", 1_700_000_000_400), BMsg("Anna", "first", 1_700_000_000_100)), "msg", true)

    @Test fun notificationItem() {
        val o = Items.notification(chat)
        assertEquals("com.whatsapp|0|com.whatsapp|1|null|10|1700000000500", o.getString("id"))
        assertEquals(1_700_000_000.5, o.getDouble("ts"), 1e-6)
        assertEquals("com.whatsapp", o.getString("app")); assertEquals("WhatsApp", o.getString("app_label"))
        assertEquals("Anna", o.getString("conversation")) // 1:1 messaging notification: the title
        assertEquals("hi there", o.getString("big_text")); assertFalse(o.has("sub_text")); assertEquals("msg", o.getString("category"))
        assertTrue(o.getBoolean("has_reply"))
        val m = o.getJSONArray("messages")
        assertEquals("first", m.getJSONObject(0).getString("text")); assertEquals("second", m.getJSONObject(1).getString("text")) // oldest first
        assertEquals(1_700_000_000.1, m.getJSONObject(0).getDouble("ts"), 1e-6)
    }

    @Test fun plainNotificationHasNoConversationOrMessages() {
        val o = Items.notification(NotifData("com.example.bank", "Example Bank", "k", 5000, "Card payment", "12.50 EUR", null, null, null, emptyList(), null, false))
        assertEquals("", o.optString("conversation")); assertFalse(o.has("conversation")); assertFalse(o.has("messages")); assertFalse(o.getBoolean("has_reply"))
        assertEquals("12.50 EUR", o.getString("text"))
    }

    @Test fun groupConversationTitleWins() {
        val d = chat.copy(title = "Anna: hi", conversationTitle = "Family")
        assertEquals("Family", Items.notification(d).getString("conversation")); assertEquals("Family", d.replyName)
    }

    @Test fun allowlistDefaults() {
        assertTrue(Allowlist.default("com.whatsapp", "x")); assertTrue(Allowlist.default("ch.threema.app.libre", "Threema Libre"))
        assertTrue(Allowlist.default("com.some.bank", "Example Banking")); assertTrue(Allowlist.default("a.b", "my paypal"))
        assertTrue(Allowlist.default("a.b", "Calendar")); assertTrue(Allowlist.default("a.b", "Gmail"))
        assertFalse(Allowlist.default("com.spotify.music", "Spotify")); assertFalse(Allowlist.default("com.android.chrome", "Chrome"))
    }

    @Test fun allowlistOverridesWin() {
        assertFalse(Allowlist.allowed("com.whatsapp", "WhatsApp", mapOf("com.whatsapp" to false)))
        assertTrue(Allowlist.allowed("com.spotify.music", "Spotify", mapOf("com.spotify.music" to true)))
        assertTrue(Allowlist.allowed("com.whatsapp", "WhatsApp", emptyMap()))
    }

    @Test fun skipRules() {
        assertTrue(NotifRules.skip(NotifRules.FLAG_GROUP_SUMMARY, "a", "dev.relay.app"))
        assertTrue(NotifRules.skip(NotifRules.FLAG_ONGOING, "a", "dev.relay.app"))
        assertTrue(NotifRules.skip(0, "dev.relay.app", "dev.relay.app"))
        assertFalse(NotifRules.skip(0x10, "a", "dev.relay.app"))
    }

    @Test fun replyMatching() {
        val names = listOf("Anna", "Anna Meier", "Family chat", "Bob")
        assertEquals(0, ReplyMatch.pick(names, "anna")) // exact beats contains
        assertEquals(1, ReplyMatch.pick(names, "MEIER")) // unique contains
        assertEquals(3, ReplyMatch.pick(names, " bob "))
        assertNull(ReplyMatch.pick(listOf("Anna Meier", "Anna Schmidt"), "anna")) // ambiguous
        assertNull(ReplyMatch.pick(names, "Carl")); assertNull(ReplyMatch.pick(names, "  "))
    }

    @Test fun smsItems() {
        val o = Items.sms(42, 1_700_000_000_000, "+49151", "Hallo", 1, "Anna")!!
        assertEquals("sms-42", o.getString("id")); assertEquals("inbox", o.getString("box")); assertEquals("Anna", o.getString("name"))
        assertEquals(1_700_000_000.0, o.getDouble("ts"), 1e-6)
        assertEquals("sent", Items.sms(43, 0, "x", "y", 2, null)!!.getString("box")); assertFalse(Items.sms(43, 0, "x", "y", 2, null)!!.has("name"))
        assertNull(Items.sms(44, 0, "x", "y", 3, null)) // draft
    }

    @Test fun healthItems() {
        val hr = Items.heartRate(1000, 61_000, listOf(60, 70, 80, 90), "com.watch")!!
        assertEquals("heart_rate-1000-61000", hr.getString("id")); assertEquals("bpm", hr.getString("unit"))
        assertEquals(75.0, hr.getDouble("value"), 1e-9); assertEquals(60, hr.getInt("min")); assertEquals(90, hr.getInt("max")); assertEquals(4, hr.getInt("samples"))
        assertEquals(1.0, hr.getDouble("start"), 1e-9); assertEquals("com.watch", hr.getString("source"))
        assertNull(Items.heartRate(0, 1, emptyList(), null))
        val st = Items.steps(0, 3_600_000, 1234, null)
        assertEquals("steps-0-3600000", st.getString("id")); assertEquals(1234.0, st.getDouble("value"), 0.0); assertEquals("count", st.getString("unit"))
        val sl = Items.sleep(0, 28_800_000, listOf(HStage(Items.stageName(5), 0, 3_600_000)), null)
        assertEquals(480.0, sl.getDouble("value"), 1e-9); assertEquals("min", sl.getString("unit")); assertEquals("deep", sl.getJSONArray("stages").getJSONObject(0).getString("stage"))
        val w = Items.weight(5000, 81.4, null)
        assertEquals("weight-5000-5000", w.getString("id")); assertEquals("kg", w.getString("unit")); assertEquals(81.4, w.getDouble("value"), 1e-9)
    }

    @Test fun nutritionDailyTotals() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val day1 = java.time.LocalDate.of(2026, 10, 5)
        fun at(d: java.time.LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val recs = listOf(
            NRec(at(day1, 8), mapOf("energy_kcal" to 400.0, "protein_g" to 30.0, "zinc_mg" to 2.5), "com.myfitnesspal.android"),
            NRec(at(day1, 19), mapOf("energy_kcal" to 700.123, "protein_g" to 45.0), "com.myfitnesspal.android"),
            NRec(at(day1.plusDays(2), 12), mapOf("energy_kcal" to 500.0), null),
        )
        val items = Items.nutritionDays(recs, day1, day1.plusDays(2), zone)
        assertEquals(3, items.size) // the empty middle day is sent too
        val d1 = items[0]
        assertEquals("nutrition", d1.getString("type")); assertEquals("2026-10-05", d1.getString("day")); assertEquals("kcal", d1.getString("unit"))
        assertEquals(1100.12, d1.getDouble("value"), 1e-9); assertEquals(2, d1.getInt("entries"))
        assertEquals(75.0, d1.getJSONObject("nutrients").getDouble("protein_g"), 1e-9); assertEquals(2.5, d1.getJSONObject("nutrients").getDouble("zinc_mg"), 1e-9)
        assertEquals("com.myfitnesspal.android", d1.getString("source"))
        assertEquals(at(day1, 0) / 1000.0, d1.getDouble("start"), 1e-9); assertEquals(at(day1.plusDays(1), 0) / 1000.0, d1.getDouble("end"), 1e-9)
        assertTrue(d1.getString("id").startsWith("nutrition-2026-10-05-"))
        assertEquals(0, items[1].getInt("entries")); assertEquals(0.0, items[1].getDouble("value"), 0.0); assertFalse(items[1].has("source"))
        // same totals -> same id (the gateway drops the repeat); a correction -> a new id
        assertEquals(d1.getString("id"), Items.nutritionDays(recs, day1, day1, zone)[0].getString("id"))
        val corrected = recs.toMutableList().apply { removeAt(1) }
        assertNotEquals(d1.getString("id"), Items.nutritionDays(corrected, day1, day1, zone)[0].getString("id"))
    }

    private class FakeQueue(n: Int) : QueueStore {
        val rows = ArrayList((1..n).map { QRow(it.toLong(), JSONObject().put("id", "i$it").toString()) })
        override suspend fun kinds() = if (rows.isEmpty()) emptyList() else listOf("sms")
        override suspend fun peek(kind: String, n: Int) = rows.take(n)
        override suspend fun delete(seqs: List<Long>) { rows.removeAll { it.seq in seqs } }
    }

    @Test fun drainBatchesOf200() = runTest {
        val q = FakeQueue(450)
        val sizes = ArrayList<Int>()
        assertEquals(450, Drain.run(q) { k, items -> assertEquals("sms", k); sizes += items.length() })
        assertEquals(listOf(200, 200, 50), sizes); assertTrue(q.rows.isEmpty())
    }

    @Test fun drainKeepsFailedBatch() = runTest {
        val q = FakeQueue(250)
        var calls = 0
        try { Drain.run(q) { _, _ -> if (++calls == 2) throw java.io.IOException("offline") } } catch (e: java.io.IOException) {}
        assertEquals(50, q.rows.size); assertEquals(201L, q.rows.first().seq) // first batch deleted after ok, second kept
    }

    private val req = SendReq("r1", "message", "phone", "com.whatsapp", "Anna", "Bin gleich da", "Anna: Bin gleich da", 2000.0)

    @Test fun sendRequestRules() {
        assertTrue(SendRules.accept(1000.0, req, emptyList(), emptyList()))
        assertFalse(SendRules.accept(2000.0, req, emptyList(), emptyList())) // expired
        assertFalse(SendRules.accept(1000.0, req, listOf("r1"), emptyList())) // already shown
        assertFalse(SendRules.accept(1000.0, req, emptyList(), listOf("r1"))) // already answered
        assertTrue(SendRules.accept(1000.0, req, listOf("r2"), listOf("r3")))
    }

    @Test fun sendRequestParsingAndTexts() {
        val r = SendReq.fromJson(JSONObject(req.toJson().toString()))
        assertEquals(req, r); assertTrue(r.byPhone)
        assertEquals("Send via WhatsApp to Anna?", SendRules.title(r, "WhatsApp")); assertEquals("Bin gleich da", SendRules.body(r))
        val mail = SendReq("r2", "mail", "desktop", "", "", "", "Mail to boss: report", 5.0)
        assertEquals("Approve mail?", SendRules.title(mail, "mail")); assertEquals("Mail to boss: report", SendRules.body(mail))
        val ev = Protocol.parse(JSONObject().put("type", "send_request").put("seq", 3).put("replay", true).put("request_id", "r1").put("kind", "message")
            .put("executor", "phone").put("app", "com.whatsapp").put("conversation", "Anna").put("text", "x").put("summary", "s").put("expires_at", 9.0))
        assertTrue(ev is SendRequestEv); assertEquals("Anna", (ev as SendRequestEv).req.conversation); assertTrue(ev.meta.replay)
        assertTrue(Protocol.parse(JSONObject().put("type", "send_resolved").put("request_id", "r1").put("ok", false).put("error", "denied")) is SendResolved)
        assertEquals(9.0, (Protocol.parse(JSONObject().put("type", "location_request").put("request_id", "l").put("reason", "r").put("expires_at", 9.0)) as LocationRequestEv).expiresAt, 0.0)
    }
}
