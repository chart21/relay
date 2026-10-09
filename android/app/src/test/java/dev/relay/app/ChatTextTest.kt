package dev.relay.app

import org.junit.Assert.*
import org.junit.Test

class ChatTextTest {
    private fun m(id: String, role: String, text: String, tools: List<ToolCall> = emptyList()) = TMessage(id, role, text, tools, 1.0)

    @Test fun slashCommandsBecomeChips() {
        val r = ChatText.row(m("1", "user", "<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>sonnet</command-args>"))!!
        assertEquals(ChatText.Kind.COMMAND, r.kind); assertEquals("/model sonnet", r.text)
    }

    @Test fun commandOutputAndCaveats() {
        val out = ChatText.row(m("2", "user", "<local-command-stdout>Set model to \u001B[1mSonnet\u001B[22m</local-command-stdout>"))!!
        assertEquals(ChatText.Kind.OUTPUT, out.kind); assertEquals("Set model to Sonnet", out.text)
        assertNull(ChatText.row(m("3", "user", "<local-command-caveat>Caveat: the messages below…</local-command-caveat>")))
        assertNull(ChatText.row(m("4", "user", "<local-command-stdout></local-command-stdout>")))
    }

    @Test fun assistantKeepsToolsEvenWithoutText() {
        val r = ChatText.row(m("5", "assistant", "", listOf(ToolCall("Bash", "pytest -q"))))!!
        assertEquals(ChatText.Kind.ASSISTANT, r.kind); assertEquals(1, r.tools.size)
        assertNull(ChatText.row(m("6", "assistant", "  ")))
    }

    @Test fun rowsNewestFirstWithPendingAtTheBottom() {
        val rows = ChatText.rows(listOf(m("a", "user", "hi"), m("b", "assistant", "hello")), listOf(Pending("next", 5)))
        assertEquals(listOf(true, false, false), rows.map { it.pending })
        assertEquals(listOf("next", "hello", "hi"), rows.map { it.text })
    }

    @Test fun pendingIsDeliveredOnceTheTranscriptHasIt() {
        assertTrue(ChatText.delivered(Pending("fix it", 0), listOf(m("a", "user", "fix it"))))
        assertFalse(ChatText.delivered(Pending("fix it", 0), listOf(m("a", "assistant", "fix it"))))
    }

    @Test fun slashMatching() {
        val all = SlashCatalog.forTool("claude", listOf("/compact"))
        assertTrue(SlashCatalog.matches(all, "/mo").contains("/model"))
        assertTrue(SlashCatalog.matches(all, "hello").isEmpty())
        assertTrue(SlashCatalog.matches(all, "/model x").isEmpty())
        assertEquals(1, all.count { it == "/compact" })
    }

    @Test fun sentCommandsShowAsChips() {
        val r = ChatText.row(ChatText.localCommand("/effort high", 1000))!!
        assertEquals(ChatText.Kind.COMMAND, r.kind); assertEquals("/effort high", r.text)
    }

    @Test fun toolOutputFoldsToOneLine() {
        val r = ChatText.row(m("7", "tool", "line1\nline2"))!!
        assertEquals(1, r.lines); assertTrue(r.text.startsWith("↳ "))
    }

    @Test fun toolCallsFoldIntoOneStepsRowBetweenTexts() {
        val msgs = listOf(m("u", "user", "fix it"), m("a1", "assistant", "Looking.", listOf(ToolCall("Read", "a.kt"))),
            m("t1", "tool", "file body"), m("a2", "assistant", "", listOf(ToolCall("Bash", "pytest"), ToolCall("Bash", "git diff"))),
            m("t2", "tool", "ok"), m("a3", "assistant", "Fixed."))
        val rows = ChatText.rows(msgs, emptyList()).reversed() // oldest first for reading
        assertEquals(listOf(ChatText.Kind.USER, ChatText.Kind.ASSISTANT, ChatText.Kind.STEPS, ChatText.Kind.ASSISTANT), rows.map { it.kind })
        assertEquals(3, rows[2].tools.size)
        assertEquals("Read 1 file, ran 2 commands", ChatText.stepsLabel(rows[2].tools))
        assertEquals("steps-a1", rows[2].id) // stable while more calls are appended
    }

    private val taskNotice = "<task-notification>\n<task-id>a542f4248797ae252</task-id>\n<tool-use-id>toolu_0177</tool-use-id>\n<output-file>/tmp/x.output</output-file>\n<status>completed</status>\n<summary>Agent \"3PC write-up\" completed</summary>\n<result>## Result\n\n| a | b |\n|---|---|\n| 1 | 2 |</result>\n<note>ignore</note>\n</task-notification>"

    @Test fun taskNotificationIsAnEventNotAUserBubble() {
        val r = ChatText.rowsOf(m("n", "user", taskNotice)).single()
        assertEquals(ChatText.Kind.EVENT, r.kind); assertEquals("Agent \"3PC write-up\" completed", r.text)
        assertEquals("completed", r.status); assertTrue(r.detail.startsWith("## Result")); assertEquals("✓", ChatText.statusIcon(r.status))
        val failed = ChatText.row(m("f", "user", "<task-notification><status>failed</status></task-notification>"))!!
        assertEquals("Background task failed", failed.text); assertEquals("✗", ChatText.statusIcon(failed.status))
    }

    @Test fun systemRemindersAreStrippedAndEmptyMessagesDropped() {
        assertNull(ChatText.row(m("r", "user", "<system-reminder>\nUse the tool.\n</system-reminder>")))
        assertEquals("hello", ChatText.row(m("r2", "assistant", "<system-reminder>x</system-reminder>hello"))!!.text.trim())
        assertEquals("fix it", ChatText.row(m("r3", "user", "fix it\n<system-reminder>a\nb</system-reminder>"))!!.text)
    }

    @Test fun interruptedIsAMutedNote() {
        for ((role, t) in listOf("system" to "[Request interrupted by user]", "user" to "[Request interrupted by user for tool use]")) {
            val r = ChatText.row(m("i", role, t))!!
            assertEquals(ChatText.Kind.NOTE, r.kind); assertTrue(r.text.startsWith("Request interrupted"))
        }
    }

    @Test fun bashEscapesShowCommandAndCollapsedOutput() {
        val c = ChatText.row(m("b1", "user", "<bash-input>ls -la</bash-input>"))!!
        assertEquals(ChatText.Kind.COMMAND, c.kind); assertEquals("! ls -la", c.text)
        val o = ChatText.row(m("b2", "user", "<bash-stdout>a\nb</bash-stdout><bash-stderr></bash-stderr>"))!!
        assertEquals(ChatText.Kind.OUTPUT, o.kind); assertEquals("a\nb", o.text)
        assertNull(ChatText.row(m("b3", "user", "<bash-stdout></bash-stdout><bash-stderr></bash-stderr>")))
        assertEquals(listOf(ChatText.Kind.COMMAND, ChatText.Kind.OUTPUT), ChatText.rowsOf(m("b4", "user", "<bash-input>pwd</bash-input><bash-stdout>/tmp</bash-stdout>")).map { it.kind })
    }

    @Test fun longPastedMessagesFold() {
        val long = (1..30).joinToString("\n") { "line $it" }
        val (head, more) = ChatText.fold(long)
        assertEquals(18, more); assertEquals(12, head.lines().size)
        assertEquals(0, ChatText.fold("a\nb").second)
    }

    @Test fun previewIsPlainText() {
        assertEquals("Total cost is 12 EUR, see the docs", ChatText.preview("## Total\n**cost** is `12` EUR, see [the docs](http://x.y)"))
        assertEquals("Tariff · Price Basic · 12", ChatText.preview("| Tariff | Price |\n|---|---|\n| Basic | 12 |"))
        assertEquals("one two three", ChatText.preview("- one\n- two\n```\n```\n3. three"))
        assertEquals("Agent \"3PC write-up\" completed", ChatText.preview(taskNotice))
        assertEquals("", ChatText.preview("<system-reminder>x</system-reminder>"))
    }

    @Test fun screenTail() = assertEquals("b\nc", ChatText.tail("a\nb  \nc\n\n\n", 2))
}

class UpdaterTest {
    @Test fun onlyNewerVerifiedBuildsAreOffered() {
        val b = AppBuild.fromJson(org.json.JSONObject("""{"version_code":412000,"version_name":"2.0-x","size":10,"gz_size":5,"sha256":"ab"}"""))!!
        assertEquals(b, AppBuild.newer(1, b))
        assertNull(AppBuild.newer(412000, b))
        assertNull(AppBuild.newer(1, b.copy(sha256 = "")))
        assertNull(AppBuild.fromJson(org.json.JSONObject("{}")))
        assertNull(AppBuild.fromJson(null))
    }
}

class UsageTextTest {
    private fun u(vararg w: Pair<String, Double>, error: String? = null) =
        AccountUsage("c1", "claude", "C1", "pro", w.map { UsageWindow(it.first, it.second, null) }, error, 0.0)

    @Test fun shortHints() {
        assertEquals("5 h 43% · week 51%", UsageText.short(u("5 h" to 43.4, "week" to 51.0)))
        assertEquals("token expired", UsageText.short(u(error = "token expired")))
        assertEquals("", UsageText.short(null))
    }

    @Test fun levelsAndResets() {
        assertEquals(listOf(0, 1, 2), listOf(69.9, 70.0, 95.0).map { UsageText.level(it) })
        val now = 1_000_000_000_000L
        assertTrue(UsageText.reset((now + 2 * 3_600_000L + 600_000L) / 1000.0, now).startsWith("resets in 2 h 10 min"))
        assertEquals("no reset pending", UsageText.reset(null, now))
        assertTrue(UsageText.reset((now + 3 * 86_400_000L) / 1000.0, now).startsWith("resets ") )
    }
}

class WakePipelineTest {
    private val melInputs = mutableListOf<Int>()
    private val mel = Net { x, _ -> melInputs += x.size; FloatArray(maxOf(0, (x.size - 400) / 160) * 32) { 0f } }
    private val emb = Net { x, shape -> assertEquals(listOf(1L, 76L, 32L, 1L), shape.toList()); assertEquals(76 * 32, x.size); FloatArray(96) { 0.1f } }
    private var nextScore = 0.9f
    private val ww = Net { x, shape -> assertEquals(listOf(1L, 16L, 96L), shape.toList()); assertEquals(16 * 96, x.size); floatArrayOf(nextScore) }

    @Test fun streamsEightyMsChunksLikeTheReference() {
        val p = WakePipeline(mel, emb, ww)
        assertEquals(listOf(160 * 76 + 400), melInputs) // warm-up on silence
        val chunk = ShortArray(1280)
        val scores = List(7) { p.process(chunk) }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f), scores) // first 5 frames ignored, as in openWakeWord
        assertEquals(listOf(1280, 1760, 1760), melInputs.drop(1).take(3)) // mel over the last 1280 + 480 samples
    }
}

class SparePickTest {
    private fun u(alias: String, h5: Double, wk: Double, tool: String = "claude", error: String? = null) =
        AccountUsage(alias, tool, alias, "pro", listOf(UsageWindow("5 h", h5, null), UsageWindow("week", wk, null)), error, 0.0)

    @Test fun prefersSpareAccountWithQuota() {
        val all = listOf(u("c1", 10.0, 10.0), u("c2", 0.0, 100.0), u("c3", 40.0, 9.0), u("c4", 20.0, 52.0), u("x1", 1.0, 1.0, "codex"))
        assertEquals("c4", UsageText.spare(all, "c1"))
        assertNull(UsageText.spare(listOf(u("c1", 1.0, 1.0), u("c2", 90.0, 1.0), u("c3", 1.0, 95.0)), "c1"))
        assertNull(UsageText.spare(listOf(u("c2", 1.0, 1.0, error = "token expired")), "c1"))
    }
}

class NoticeTest {
    @Test fun parsedAndShownWithin24h() {
        val ev = Protocol.parse("""{"type":"notice","seq":7,"ts":1000.0,"title":"MFP needs a new login","text":"Cookie expired","source":"mfp","tag":"mfp-login"}""")
        assertTrue(ev is NoticeEv); ev as NoticeEv
        assertEquals("mfp-login", ev.tag); assertEquals("MFP needs a new login", ev.title)
        assertTrue(Notices.show(1000.0, 1000.0 + 3600)); assertFalse(Notices.show(1000.0, 1000.0 + 90_000))
        assertEquals(Notices.id("mfp-login", 1), Notices.id("mfp-login", 2)) // same tag replaces
        assertNotEquals(Notices.id(null, 1), Notices.id(null, 2))
    }
}

class MdBlocksTest {
    private val md = """Intro **text**

| Tariff | Price | Note |
|:--|--:|---|
| Basic | 12 | a \| b |
| Plus | 20 |

```kotlin
val x = "| not | a table |"
```
Done"""

    @Test fun splitsProseTablesAndCode() {
        val b = MdBlocks.split(md)
        assertEquals(listOf("Prose", "Table", "Code", "Prose"), b.map { it::class.simpleName })
        val t = b[1] as MdBlock.Table
        assertEquals(listOf("Tariff", "Price", "Note"), t.header)
        assertEquals(listOf(false, true, false), t.right)
        assertEquals(listOf("Basic", "12", "a | b"), t.rows[0])
        assertEquals(listOf("Plus", "20", ""), t.rows[1]) // short rows are padded
        assertEquals("kotlin", (b[2] as MdBlock.Code).lang)
        assertTrue((b[2] as MdBlock.Code).body.contains("| not |"))
    }

    @Test fun pipesWithoutSeparatorAreProse() = assertEquals(1, MdBlocks.split("a | b\nc | d").size)

    @Test fun unclosedFenceRunsToTheEnd() {
        val b = MdBlocks.split("```\nls\nmore")
        assertEquals("ls\nmore", (b.single() as MdBlock.Code).body)
    }

    @Test fun wideTablesGetBoundedColumns() {
        val t = MdBlocks.split("| " + (1..12).joinToString(" | ") { "col$it" } + " |\n|" + "---|".repeat(12) + "\n| " + (1..12).joinToString(" | ") { "x".repeat(80) } + " |").single() as MdBlock.Table
        assertEquals(12, t.header.size)
        assertTrue(MdBlocks.colChars(t).all { it in 4..34 })
    }

    @Test fun plainRemovesInlineMarks() = assertEquals("bold code link", MdBlocks.plain("**bold** `code` [link](http://x)"))
}

class OverviewTest {
    private fun s(key: String, title: String, state: String = "idle", at: Double = 1.0, cwd: String = "/home/c/proj", bg: Boolean = false, alias: String = "c1") =
        Session(key, "claude", alias, key, alias, cwd, title, state, state != "exited", true, "t-$key", at, "", 1, background = bg)

    @Test fun pinsFirstThenWaitingThenNewest() {
        val l = listOf(s("a", "A", at = 5.0), s("b", "B", "needs_input", 1.0), s("c", "C", at = 9.0), s("d", "D", at = 2.0))
        assertEquals(listOf("d", "b", "c", "a"), Overview.view(l, SessionFilter.LIVE, setOf("d"), "").map { it.key })
    }

    @Test fun searchMatchesTitleFolderAccountAndAllWords() {
        val l = listOf(s("a", "Tariffs", cwd = "/home/c/billing", alias = "c2"), s("b", "Tests", cwd = "/home/c/notify-app"))
        assertEquals(listOf("a"), Overview.view(l, SessionFilter.LIVE, emptySet(), "tarif billing").map { it.key })
        assertEquals(listOf("b"), Overview.view(l, SessionFilter.LIVE, emptySet(), "notify").map { it.key })
        assertEquals(listOf("a"), Overview.view(l, SessionFilter.LIVE, emptySet(), "c2").map { it.key })
        assertTrue(Overview.view(l, SessionFilter.LIVE, emptySet(), "zzz").isEmpty())
    }

    @Test fun backgroundRunsOnlyWhenAsked() {
        val l = listOf(s("a", "A"), s("b", "B", bg = true))
        assertEquals(listOf("a"), Overview.view(l, SessionFilter.RECENT, emptySet(), "").map { it.key })
        assertEquals(2, Overview.view(l, SessionFilter.RECENT, emptySet(), "", true).size)
        assertTrue(Session.fromJson(org.json.JSONObject("""{"key":"claude:c1:x","background":true}""")).background)
    }

    @Test fun statusWords() {
        assertEquals("ended", Overview.status(s("a", "A", "exited"))); assertEquals("working", Overview.status(s("a", "A", "busy")))
        assertEquals("needs your input", Overview.status(s("a", "A", "needs_input"))); assertEquals("idle", Overview.status(s("a", "A")))
    }
}
