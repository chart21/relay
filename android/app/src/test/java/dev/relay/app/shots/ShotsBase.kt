package dev.relay.app.shots

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.relay.app.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val NOW = 1_800_000_000.0
private fun s(key: String, title: String, state: String = "idle", ago: Int = 120, alias: String = "c1", msg: String = "", cwd: String = "/home/dev/workspace/relay",
              attachable: Boolean = true) =
    Session(key, if (alias.startsWith("x")) "codex" else if (alias.startsWith("g")) "agy" else "claude", alias, key, alias, cwd, title, state, state != "exited", attachable, "t-$key", NOW - ago, msg, 1)

private fun m(id: String, role: String, text: String, tools: List<ToolCall> = emptyList()) = TMessage(id, role, text, tools, NOW)

val sampleTable = (1..11).joinToString(" | ", "| ", " |") { if (it == 1) "Tariff" else "Spalte $it" } + "\n|" + "---|".repeat(11) + "\n" +
    listOf("Basic Plan", "Plus Plan").joinToString("\n") { n -> (1..11).joinToString(" | ", "| ", " |") { if (it == 1) n else "${it * 17},50 €" } }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ShotsBase(private val suffix: String) {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { RelayTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("${System.getProperty("shots.dir")}/$name-$suffix.png")
    }

    @Test fun overview() = shot("overview") {
        Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
            val rows = listOf(
                Triple(s("1", "Tariff comparison", "needs_input", 40, "c2", "**Which tariff** do you want? | Basic | Plus |", "/home/dev/workspace/billing"), true, true),
                Triple(s("2", "Relay design pass", "busy", 15, "c3", "## Done\n- table rendering\n- `ChatText` split"), false, false),
                Triple(s("3", "A very long session title that keeps going and going until it has to be cut off at the edge", "idle", 3600 * 3, "c1", "All set, tests pass."), true, false),
                Triple(s("4", "Codex refactor", "idle", 86_400 * 2, "x1", "Renamed 14 files", "/home/dev/workspace/backend"), false, false),
                Triple(s("5", "agy scratch", "idle", 600, "g1", "", "/tmp/scratch", attachable = false), false, false),
                Triple(s("6", "Old run", "exited", 86_400 * 5, "c4", "| a | b |\n|---|---|\n| 1 | 2 |"), false, false),
                Triple(s("7", "dev (login needed)", "idle", 5, "c2"), false, false),
            )
            rows.forEachIndexed { n, (se, unread, pinned) -> SessionRow(se, unread, pinned, NOW, {}, {}, muted = n == 2) }
        }
    }

    @Test fun chatReply() = shot("chat-reply") {
        val msgs = listOf(
            m("u1", "user", "Compare the tariffs and show me the config."),
            m("a1", "assistant", "Here is the **comparison**:\n\n- first point with `inline code`\n- second point\n\n1. step one\n2. step two\n\n```kotlin\nfun main() {\n    println(\"a rather long line that needs sideways scrolling to be read in full\")\n}\n```\nThat is all."),
        )
        ChatColumn(msgs)
    }

    @Test fun chatTable() = shot("chat-table") {
        ChatColumn(listOf(m("a", "assistant", "Tariff overview:\n\n$sampleTable\n\nThe cheapest is **Plus Plan**.")))
    }

    @Test fun chatEvents() = shot("chat-events") {
        val long = (1..20).joinToString("\n") { "pasted line $it" }
        ChatColumn(listOf(
            m("u", "user", "Run it in the background"), m("a1", "assistant", "Starting.", listOf(ToolCall("Bash", "pytest -q"), ToolCall("Read", "a.kt"))),
            m("t", "tool", "ok"),
            m("n", "user", "<task-notification>\n<task-id>a1</task-id>\n<status>completed</status>\n<summary>Agent \"3PC write-up\" completed</summary>\n<result>## Result\n\nAll **done**.</result>\n</task-notification>"),
            m("n2", "user", "<task-notification><status>failed</status><summary>Agent \"build\" failed</summary></task-notification>"),
            m("i", "system", "[Request interrupted by user]"),
            m("b", "user", "<bash-input>git status -sb</bash-input>"), m("b2", "user", "<bash-stdout>## rework\n M android/app/build.gradle.kts</bash-stdout><bash-stderr></bash-stderr>"),
            m("c", "user", "<command-name>/model</command-name><command-args>sonnet</command-args>"),
            m("p", "user", long),
        ), listOf(Pending("this one is still sending", 1)))
    }

    @Test fun voicePicker() = shot("voice-picker") {
        fun v(n: String, c: String, q: Int) = VoiceInfo(n, n.take(2), c, q, 200, false, true)
        val de = listOf("deb" to 500, "nfh" to 400, "dea" to 400, "dec" to 300, "ded" to 300, "def" to 300, "deg" to 200, "deh" to 200).map { (c, q) -> v("de-de-x-$c-local", "DE", q) }
        val en = listOf("tpf" to 400, "iob" to 400, "sfg" to 300).map { (c, q) -> v("en-us-x-$c-local", "US", q) }
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            VoicePicker(VoiceUi(listOf("com.google.android.tts" to "Speech Services by Google", "com.samsung.SMT" to "Samsung TTS"), "com.google.android.tts",
                mapOf("de" to de, "en" to en), mapOf("de" to "de-de-x-nfh-local", "en" to ""), 1.1f, googleMissing = true, testing = "en/"), {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
        }
    }

    @Test fun chatSearch() = shot("chat-search") {
        val msgs = listOf(
            m("u1", "user", "Why does the Tariff table look broken?"),
            m("a1", "assistant", "The **tariff** table had 11 columns. Fixed by scrolling sideways.\n\n```\ntariff = load()\n```"),
            m("u2", "user", "And the tariff names?"), m("a2", "assistant", "They are in the first column."),
        )
        val q = "tariff"
        val matches = listOf(SearchMatch("u2", 12, "user", NOW, "And the tariff names?"), SearchMatch("a1", 9, "assistant", NOW - 3600, "…The tariff table had 11 columns. Fixed by scrolling sideways."),
            SearchMatch("u1", 8, "user", NOW - 7200, "Why does the Tariff table look broken?"))
        Column(Modifier.fillMaxSize()) {
            ChatSearchBar(q, {}, "2/3", {}, {}, {}, focus = false)
            ChatSearchResults(matches, q, 1, {})
            HorizontalDivider()
            val rows = ChatText.rows(msgs, emptyList())
            Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)) {
                rows.asReversed().forEach { ChatRow(it, query = q) {} }
            }
        }
    }


    @Test fun memory() = shot("memory") {
        val ss = listOf(
            MemSession("1", "Relay round 3", "c3", "claude", "/home/dev/workspace/relay", "busy", true, 1630.0, 120.0),
            MemSession("2", "Tariff comparison", "c2", "claude", "/home/dev/workspace/billing", "needs_input", true, 940.0, 0.0),
            MemSession("3", "Backend merge", "c1", "claude", "/home/dev/workspace/backend", "idle", true, 410.0, 0.0),
            MemSession("4", "agy scratch", "g1", "agy", "/tmp/scratch", "idle", true, 0.0, 0.0),
        )
        val i = MemoryInfo(15900.0, 1450.0, 8192.0, 2300.0, ss, listOf(MemOther("firefox", 2400.0, 300.0, 14), MemOther("Xorg", 380.0, 0.0, 1)),
            listOf(MemClosed("5", "Conference slides", "c4", "claude", "x", "/x", NOW - 900, 780.0), MemClosed("6", "Old experiment", "c2", "claude", "y", "/y", NOW - 86_400, 0.0)))
        Column(Modifier.fillMaxSize()) { MemoryContent(i, NOW, null, {}, {}) }
    }

    @Test fun share() = shot("share") {
        val l = listOf(s("1", "Relay round 3", "busy", 15, "c3"), s("2", "Tariff comparison", "needs_input", 40, "c2", cwd = "/home/dev/workspace/billing"), s("3", "Backend merge", "idle", 3600, "c1"))
        ShareContent(listOf("IMG_20261008_1412.jpg" to "2.4 MB", "tariffs.pdf" to "310 KB"), "Look at the second page", l, "1", "", {}, "What do you make of these?", {}, {}, "Uploading 1/2 · 40%", null, true, {}, {}, chrome = false)
    }

    @Test fun attachments() = shot("attachments") {
        val dir = "/home/dev/.local/share/notifyd/uploads/2026-10-08"
        Thumbs.load = { _, _, px -> android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888).also { b -> android.graphics.Canvas(b).drawColor(0xFFC96442.toInt()) } }
        LocalAttachments.record(listOf("$dir/IMG_1412.jpg"), listOf(Attachment("content://x/1", "IMG_1412.jpg", 1, "image/jpeg", true)))
        val picked = listOf(Attachment("content://x/1", "IMG_1412.jpg", 2_400_000, "image/jpeg", true), Attachment("content://x/2", "tariffs-2026-final-version.pdf", 310_000, "application/pdf", false),
            Attachment("content://x/3", "notes.txt", 900, "text/plain", false))
        val msgs = listOf(m("u1", "user", "What do you make of these?\n\n$dir/IMG_1412.jpg\n$dir/tariffs.pdf"), m("a1", "assistant", "The photo shows the **tariff table**; the PDF has the same numbers."),
            m("u2", "user", "$dir/other.png"))
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)) {
                ChatText.rows(msgs, listOf(Pending("and this one\n\n$dir/IMG_1412.jpg", 1))).asReversed().forEach { ChatRow(it) {} }
            }
            AttachChips(picked, true) {}
            AttachProgress("Uploading 2/3 · 40%", 0.4f)
            ComposeBar("Compare them", {}, "Message or /command…", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), leading = {
                androidx.compose.material3.IconButton({}, Modifier.size(44.dp)) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.AttachFile, "attach") } }, canSend = true) {}
        }
    }

    @Test fun summary() = shot("summary") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            SummaryCardContent(false, "The tariff table is fixed: it now scrolls sideways and keeps the first column. Tests pass; nothing is committed yet.", null, {}, {})
            SummaryCardContent(true, "", null, {}, {})
            SummaryCardContent(false, "", "speak_summary timed out", {}, {})
        }
    }

    @Test fun jarvis() = shot("jarvis") {
        val msgs = listOf(Msg(1, outgoing = true, text = "What is running right now?", kind = "voice"),
            Msg(2, outgoing = false, text = "Three sessions: **Relay round 3** (busy), *Tariff comparison* (waiting for you) and Backend merge (idle)."),
            Msg(3, outgoing = true, text = "Close the idle one"), Msg(4, outgoing = false, text = "⚠ Offline: Connection lost", kind = "system"))
        Column(Modifier.fillMaxSize()) { JarvisMessages(msgs, androidx.compose.foundation.lazy.rememberLazyListState(), Modifier.weight(1f)) }
    }

    @Test fun jarvisEmpty() = shot("jarvis-empty") { Column(Modifier.fillMaxSize()) { JarvisMessages(emptyList(), androidx.compose.foundation.lazy.rememberLazyListState(), Modifier.weight(1f)) } }

    @Test fun speechRecognition() = shot("speech-recognition") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SpeechRecognitionContent(ModelState(setOf("de-DE"), setOf("en-US"), setOf("de-DE", "en-US")), VocabData(List(42) { "term$it" },
                listOf("cloud code" to "Claude Code", "zen pas" to "Zenpass", "study perks" to "Studyperks")), {}, {})
            HorizontalDivider()
            SpeechRecognitionContent(ModelState(setOf("de-DE"), emptySet(), setOf("de-DE", "en-US")), VocabData(), {}, {})
        }
    }

    @Test fun jarvisLive() = shot("jarvis-live") {
        val msgs = listOf(Msg(1, outgoing = true, text = "Which Zenpass classes are tomorrow?", kind = "voice"), Msg(2, outgoing = false, text = "Two yoga classes and a spinning class."))
        Column(Modifier.fillMaxSize()) {
            JarvisMessages(msgs, androidx.compose.foundation.lazy.rememberLazyListState(), Modifier.weight(1f), onFix = {})
            LiveText("Search for Zenpass sessions on")
        }
    }

    @Test fun accounts() = shot("accounts") {
        fun u(a: String, t: String, l: String, plan: String?, w: List<UsageWindow>, err: String? = null) = AccountUsage(a, t, l, plan, w, err, NOW)
        val l = listOf(u("c1", "claude", "Main", "Pro", listOf(UsageWindow("5h", 83.0, NOW + 4000), UsageWindow("week", 41.0, NOW + 86_400 * 2))),
            u("c2", "claude", "Spare", "Pro", listOf(UsageWindow("5h", 12.0, NOW + 9000), UsageWindow("week", 93.0, NOW + 86_400 * 5))),
            u("c3", "claude", "Spare", "Pro", emptyList(), "login expired (401)"), u("x1", "codex", "Codex", "free", listOf(UsageWindow("30d", 55.0, null))))
        AccountsContent(l, null, false, { if (it == "c1") 2 else 0 }, {})
    }

    @Test fun newChat() = shot("new-chat") {
        val acc = listOf(Account("c1", "claude", "Main", "", true), Account("c2", "claude", "Spare", "", true), Account("x1", "codex", "Codex", "", false))
        val us = listOf(AccountUsage("c1", "claude", "Main", "Pro", listOf(UsageWindow("5h", 83.0, null), UsageWindow("week", 41.0, null)), null, NOW),
            AccountUsage("c2", "claude", "Spare", "Pro", listOf(UsageWindow("5h", 12.0, null), UsageWindow("week", 30.0, null)), null, NOW))
        Column(Modifier.fillMaxSize()) { SectionLabel("Account"); AccountPicker(acc, us, "c2") {} }
    }

    @Test fun notifications() = shot("notifications") {
        Column(Modifier.fillMaxSize().padding(16.dp)) { NotificationsSection(Prefs(notifyReady = "silent", muted = setOf("a", "b"))) {} }
    }

    @Composable private fun ChatColumn(msgs: List<TMessage>, pending: List<Pending> = emptyList()) {
        val rows = ChatText.rows(msgs, pending).asReversed()
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEach { ChatRow(it) {} }
        }
    }
}
