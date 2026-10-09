package dev.relay.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private fun sess(key: String, state: String = "idle", cwd: String = "/home/c/proj", act: Double = 1.0, tmux: String? = null) =
    Session(key, key.substringBefore(':'), key.split(':')[1], key.substringAfterLast(':'), "", cwd, "", state, state != "exited", tmux != null, tmux, act, "", 1)

private fun sj(key: String, state: String) = JSONObject("""{"key":"$key","tool":"claude","alias":"c2","id":"x","state":"$state","cwd":"/home/c/webapp","tmux_session":null}""")

class ProtocolTest {
    @Test fun parsesHelloOk() {
        val ev = Protocol.parse("""{"type":"hello_ok","seq":7,"server_version":2,"sessions":[{"key":"claude:c2:abc","tool":"claude","alias":"c2","id":"abc","state":"busy","cwd":"/a/b","tmux_session":"c2-b-1","attachable":true,"last_activity":5.5}],"accounts":[{"alias":"c2","tool":"claude","label":"Claude #2","home":"~","logged_in":true}],"config":{"overview":{"alias":"c1","model":"haiku"}}}""") as HelloOk
        assertEquals(7, ev.seq)
        assertEquals("tmux:c2-b-1", ev.sessions[0].target)
        assertEquals("busy", ev.sessions[0].state)
        assertEquals("Claude #2", ev.accounts[0].label)
        assertEquals("haiku", OverviewConfig.fromJson(ev.config)?.model)
    }
    @Test fun targetFallsBackToKey() = assertEquals("claude:c1:z", sess("claude:c1:z").target)
    @Test fun replayFlagAndState() {
        val ev = Protocol.parse("""{"type":"session_state","seq":3,"ts":10.0,"replay":true,"session":{"key":"claude:c2:a","state":"idle"},"prev_state":"busy","reason":"hook:Stop"}""") as SessionState
        assertTrue(ev.meta.replay); assertEquals(3, ev.meta.seq); assertEquals("busy", ev.prevState)
    }
    @Test fun rpcResult() {
        val ok = Protocol.parse("""{"type":"rpc_result","id":"1","ok":true,"result":{"a":1}}""") as RpcResult
        assertTrue(ok.ok); assertEquals(1, ok.result!!.getInt("a"))
        val bad = Protocol.parse("""{"type":"rpc_result","id":"2","ok":false,"error":"nope"}""") as RpcResult
        assertEquals("nope", bad.error)
    }
    @Test fun transcriptAppendAndGarbage() {
        val ev = Protocol.parse("""{"type":"transcript_append","session_key":"k","messages":[{"id":"1","role":"assistant","text":"hi","tools":[{"name":"Bash","summary":"ls"}],"ts":1}]}""") as TranscriptAppend
        assertEquals("Bash", ev.messages[0].tools[0].name)
        assertNull(Protocol.parse("not json"))
        assertTrue(Protocol.parse("""{"type":"zzz"}""") is UnknownEv)
    }
    @Test fun helloAndRpcFraming() {
        assertEquals(5, Protocol.hello(5).getLong("since_seq"))
        assertEquals("kill", Protocol.rpc("i", "kill", JSONObject()).getString("method"))
    }
}

class GroupingTest {
    @Test fun oneListNotGroupedByAccount() {
        val l = listOf(sess("codex:x1:1", "idle", act = 9.0), sess("claude:c1:a", "idle", act = 1.0), sess("claude:c1:b", "needs_input", act = 0.5),
            sess("claude:c1:c", "busy", act = 7.0), sess("claude:c1:d", "exited", act = 99.0), sess("agy:g1:1"))
        assertEquals(listOf("b", "1", "c", "a", "1"), Grouping.list(l).map { it.id }) // waiting first, then newest
        assertEquals("d", Grouping.list(l, SessionFilter.RECENT)[0].id)
    }
    @Test fun storeEndAndMerge() {
        val st = SessionStore(); st.snapshot(listOf(sess("claude:c1:a", "busy")))
        assertEquals("exited", st.markEnded("claude:c1:a", null)!!.state)
        assertNull(st.markEnded("nope", null))
        assertEquals(2, Grouping.merge(listOf(sess("claude:c1:a")), listOf(sess("claude:c1:a"), sess("claude:c1:b"))).size)
    }
    @Test fun ago() { assertEquals("3 min", agoLabel(0.1, 181.0)); assertEquals("now", agoLabel(10.0, 20.0)); assertEquals("~/x/y", shortPath("/home/me/x/y")) }
}

class DigestTest {
    private fun st(key: String, state: String, prev: String, ts: Double) =
        SessionState(Meta(1, ts, true), Session.fromJson(sj(key, state)).copy(alias = key.split(':')[1]), prev, "poll", null)
    private val now = 1_000_000_000_000L
    private val ts = now / 1000.0 - 60

    @Test fun oneSummary() {
        val r = Digest.build(listOf(st("claude:c2:a", "idle", "busy", ts), st("codex:x1:b", "needs_input", "busy", ts)), now)!!
        assertEquals(2, r.count)
        assertTrue(r.text, r.text.startsWith("While you were away: c2 · webapp finished; x1 · webapp needs input"))
    }
    @Test fun staleAndSupersededIgnored() {
        assertNull(Digest.build(listOf(st("claude:c2:a", "idle", "busy", now / 1000.0 - 90_000)), now))
        assertNull(Digest.build(listOf(st("claude:c2:a", "idle", "busy", ts), st("claude:c2:a", "busy", "idle", ts)), now))
    }
    @Test fun endedByUserSilent() {
        val s = Session.fromJson(sj("claude:c2:a", "exited"))
        assertNull(Digest.build(listOf(SessionEnded(Meta(1, ts, true), "claude:c2:a", s, true)), now))
        assertEquals(Kind.ENDED, Digest.classify(SessionEnded(Meta(), "k", s, false)))
        assertNull(Digest.classify(SessionEnded(Meta(), "k", s, false), wasAlive = false))
    }
    @Test fun classifyTransitions() {
        assertEquals(Kind.READY, Digest.classify(st("claude:c2:a", "idle", "busy", 0.0)))
        assertNull(Digest.classify(st("claude:c2:a", "idle", "idle", 0.0)))
        assertEquals(Kind.NEEDS_INPUT, Digest.classify(st("claude:c2:a", "needs_input", "busy", 0.0)))
    }
    @Test fun capsItems() {
        val evs = (1..8).map { st("claude:c2:s$it", "idle", "busy", ts) }
        assertTrue(Digest.build(evs, now, 5)!!.text.endsWith("and 3 more"))
    }
}

class SpeechTextTest {
    @Test fun strips() {
        assertEquals("Title Done see docs now", SpeechText.strip("# Title\n```py\nx=1\n```\n**Done** see [docs](http://a) `now`"))
    }
    @Test fun chunking() {
        val t = "One sentence here. Another one follows. " + "word ".repeat(50)
        val c = SpeechText.chunks(t, 60)
        assertTrue(c.all { it.length <= 60 }); assertTrue(c.size > 2)
        assertEquals(emptyList<String>(), SpeechText.chunks("  ", 10))
    }
    @Test fun stopWords() {
        assertTrue(SpeechText.isStopPhrase("Stopp!")); assertTrue(SpeechText.isStopPhrase("ende")); assertTrue(SpeechText.isStopPhrase("ok stop"))
        assertFalse(SpeechText.isStopPhrase("please stop the build and run all the tests"))
        assertTrue(SpeechText.isStopPhrase("Danke Jarvis")); assertTrue(SpeechText.isStopPhrase("tschüss"))
        assertFalse(SpeechText.isStopPhrase("Danke, und wie viel Protein fehlt noch?"))
    }
}

class BriefTest {
    @Test fun firstSentencesOnly() {
        assertEquals("Merged the branch. Tests pass.", SpeechText.brief("**Merged** the branch. Tests pass. " + "Detail. ".repeat(40), 35))
        assertEquals("", SpeechText.brief("  "))
        assertTrue(SpeechText.brief("word ".repeat(100), 50).endsWith(" …"))
    }
}

class SentencesTest {
    private fun split(vararg deltas: String): List<String> {
        val out = ArrayList<String>(); val s = Sentences { out += it }
        deltas.forEach(s::add); s.end(); return out
    }
    @Test fun emitsEachSentenceWhenComplete() {
        val out = ArrayList<String>(); val s = Sentences { out += it }
        s.add("Dir fehlen noch 80 Gramm Prot"); assertEquals(emptyList<String>(), out)
        s.add("ein. Das sind etwa"); assertEquals(listOf("Dir fehlen noch 80 Gramm Protein."), out)
        s.add(" zwei Quark."); s.end()
        assertEquals(listOf("Dir fehlen noch 80 Gramm Protein.", "Das sind etwa zwei Quark."), out)
    }
    @Test fun keepsDatesAndShortBitsTogether() {
        assertEquals(listOf("Ja. Das war am 4. Oktober, ein Sonntag."), split("Ja. Das war am 4. Okto", "ber, ein Sonntag."))
        assertEquals(listOf("z.B. Quark oder Skyr."), split("z.B. Quark oder Skyr."))
    }
    @Test fun newlineAfterToolCallEndsTheLeadIn() =
        assertEquals(listOf("Moment.", "Eingetragen: 250 Gramm Quark."), split("Moment.", "\n", "Eingetragen: 250 Gramm Quark."))
    @Test fun nothingForBlank() = assertEquals(emptyList<String>(), split("", " \n "))
}

class ConversationTest {
    private fun run(script: List<Heard>, reply: String? = "ok"): Pair<ConversationLoop.End, List<String>> {
        val it = script.iterator(); val spoken = ArrayList<String>()
        val end = runBlocking { ConversationLoop({ if (it.hasNext()) it.next() else Heard.Failed("eof") }, { reply }, { s -> spoken += s }).run() }
        return end to spoken
    }
    @Test fun stopsOnWord() { val (e, s) = run(listOf(Heard.Text("hi"), Heard.Text("stopp"))); assertEquals(ConversationLoop.End.Stopped, e); assertEquals(listOf("ok"), s) }
    @Test fun twoSilences() = assertEquals(ConversationLoop.End.Silent, run(listOf(Heard.Silence, Heard.Silence)).first)
    @Test fun silenceResetsOnSpeech() = assertEquals(ConversationLoop.End.Stopped, run(listOf(Heard.Silence, Heard.Text("a"), Heard.Silence, Heard.Text("ende"))).first)
    @Test fun askFailureEnds() = assertEquals(ConversationLoop.End.Failed, run(listOf(Heard.Text("a")), null).first)
    @Test fun wakeConversationEndsAfterOneSilence() {
        val it = listOf(Heard.Text("hi"), Heard.Silence).iterator()
        val end = runBlocking { ConversationLoop({ it.next() }, { "ok" }, {}, maxSilent = 1).run() }
        assertEquals(ConversationLoop.End.Silent, end)
    }
}

class SlashChipsTest {
    @Test fun slashChips() {
        assertEquals(listOf("/model", "/help"), SlashChips.parse(SlashChips.defaults.getValue("agy")))
    }
    @Test fun transcriptDedup() {
        val a = TMessage("1", "user", "x", emptyList(), 0.0); val b = a.copy(id = "2")
        assertEquals(listOf(b), TranscriptLogic.fresh(listOf(a), listOf(a, b)))
    }
}
