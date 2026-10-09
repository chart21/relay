package dev.relay.app.shots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.relay.app.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots and animation frames for the public README, built ONLY from invented demo data (generic projects,
 * user "Alex"). Output: app/build/shots/demo/<name>-<suffix>.png; scripts/make-demo-video.sh assembles the video.
 */
private const val T0 = 1_800_000_000.0
private const val HOME = "/home/alex"
private const val UP = "$HOME/.local/share/notifyd/uploads/2026-10-08"

private fun sess(key: String, title: String, state: String, ago: Int, alias: String, msg: String, dir: String, attachable: Boolean = true) =
    Session(key, if (alias.startsWith("x")) "codex" else "claude", alias, key, alias, "$HOME/projects/$dir", title, state, state != "exited", attachable, "t-$key", T0 - ago, msg, 1)

private fun tm(id: String, role: String, text: String, tools: List<ToolCall> = emptyList()) = TMessage(id, role, text, tools, T0)

private val demoSessions = listOf(
    sess("1", "api-server: rate limiting", "needs_input", 40, "c1", "Which limit should /login use: **5 per minute** or 20 per minute?", "api-server"),
    sess("2", "website redesign", "busy", 15, "c2", "## Progress\n- new hero section\n- dark mode tokens", "website"),
    sess("3", "data-pipeline: nightly job", "idle", 3600 * 2, "c3", "Backfill finished: 1.2M rows, 0 errors.", "data-pipeline"),
    sess("4", "mobile app: onboarding", "idle", 3600 * 5, "x1", "Renamed 14 files and updated the previews.", "mobile-app"),
    sess("5", "docs: getting started", "idle", 86_400, "c2", "Added a quick-start page; links checked.", "docs"),
    sess("6", "api-server: CI cleanup", "exited", 86_400 * 3, "c1", "Pipeline is down to 4 minutes.", "api-server"),
)

/** An invented "error screenshot": dark window with a red banner and a few grey text lines. */
private fun fakeBitmap(px: Int) = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
    val c = android.graphics.Canvas(b); val p = android.graphics.Paint()
    fun r(col: Int, x: Float, y: Float, w: Float, h: Float) { p.color = col; c.drawRect(x * px, y * px, (x + w) * px, (y + h) * px, p) }
    c.drawColor(0xFF1F2430.toInt()); r(0xFFD9534F.toInt(), 0.08f, 0.12f, 0.84f, 0.16f)
    listOf(0.40f to 0.7f, 0.50f to 0.55f, 0.60f to 0.78f, 0.70f to 0.4f, 0.80f to 0.62f).forEach { (y, w) -> r(0xFF8A93A6.toInt(), 0.08f, y, w * 0.84f, 0.04f) }
}

private val demoTable = """
| Route | Limit | Window |
|---|---|---|
| /login | 5 | 1 min |
| /signup | 3 | 10 min |
| /api/* | 120 | 1 min |
""".trim()

private val demoReply1 = "I added **rate limiting** to the `/login` route. All 42 tests pass."
private val demoReply2 = "\n\n```python\n@limiter.limit(\"5/minute\")\nasync def login(req: LoginRequest):\n    return await auth.sign_in(req)\n```"
private val demoReply3 = "\n\nLimits per route:\n\n$demoTable\n\nWant the same on `/signup`?"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class DemoBase(private val suffix: String) {
    @get:Rule val rule = createComposeRule()
    private var started = false

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { RelayTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } } }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("${System.getProperty("shots.dir")}/demo/$name-$suffix.png")
    }

    @Composable @OptIn(ExperimentalMaterial3Api::class)
    private fun Bar(title: String, back: Boolean = false, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
        val big = !back
        TopAppBar(title = {
            if (sub == null) Text(title, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
            else Column { Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.labelSmall) }
        }, navigationIcon = { if (back) IconButton({}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = actions)
    }

    @Composable private fun Online() = Text("online", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

    @Composable private fun Screen(bar: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxSize().statusBarsPadding()) { bar(); content() }

    // ---------- overview ----------
    @Composable private fun Overview(query: String = "") {
        val rows = if (query.isEmpty()) demoSessions.take(5) else demoSessions.filter { query in it.name.lowercase() || query in it.cwd }
        Box(Modifier.fillMaxSize()) {
            Screen({
                Bar("Chats") {
                    Online()
                    IconButton({}) { Icon(Icons.Default.Search, "search") }
                    IconButton({}) { Icon(Icons.Default.Memory, "memory") }
                    IconButton({}) { Icon(Icons.Default.DataUsage, "usage") }
                    IconButton({}) { Icon(Icons.Default.RecordVoiceOver, "Jarvis") }
                }
            }) {
                if (query.isNotEmpty()) OutlinedTextField(query, {}, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(28.dp),
                    trailingIcon = { IconButton({}) { Icon(Icons.Default.Close, "clear") } })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(true, {}, label = { Text("Live") }); FilterChip(false, {}, label = { Text("Recent") })
                }
                Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { n, se -> SessionRow(se, unread = n == 0 || n == 2, pinned = n == 0, now = T0, onOpen = {}, onKill = {}) }
                }
            }
            ExtendedFloatingActionButton({}, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New chat")
            }
        }
    }

    @Test fun overview() = shot("overview") { Overview() }
    @Test fun overviewSearch() = shot("overview-search") { Overview("api") }

    // ---------- chat ----------
    private val files = listOf(Attachment("content://x/1", "login-error.png", 640_000, "image/png", true), Attachment("content://x/2", "limits.md", 2_100, "text/markdown", false))

    @Composable private fun Chat(step: Int, typing: String = "", panel: Boolean = false) {
        Thumbs.load = { _, _, px -> fakeBitmap(px) }
        LocalAttachments.record(listOf("$UP/login-error.png"), listOf(Attachment("content://x/1", "login-error.png", 1, "image/png", true)))
        val msgs = ArrayList<TMessage>()
        msgs += tm("u0", "user", "Users can hammer the login endpoint. Can you rate-limit it? Here is what the logs show:\n\n$UP/login-error.png\n$UP/limits.md")
        if (step >= 1) {
            msgs += tm("a0", "assistant", "Looking at the router first.", listOf(ToolCall("Read", "api/routes/auth.py"), ToolCall("Grep", "limiter"), ToolCall("Edit", "api/routes/auth.py"), ToolCall("Bash", "pytest -q")))
            msgs += tm("t0", "tool", "42 passed")
        }
        if (step >= 2) msgs += tm("n0", "user", "<task-notification>\n<task-id>a1</task-id>\n<status>completed</status>\n<summary>Agent \"Run the full test suite\" completed</summary>\n<result>All **42 tests** passed in 8.1 s.</result>\n</task-notification>")
        if (step >= 3) msgs += tm("a1", "assistant", demoReply1 + (if (step >= 4) demoReply2 else "") + (if (step >= 5) demoReply3 else ""))
        Screen({
            Bar("api-server: rate limiting", back = true, sub = "c1 · ~/projects/api-server") {
                IconButton({}) { Icon(Icons.Default.Search, "search") }
                StateChip(if (step >= 3) "idle" else "busy", Modifier.padding(end = 8.dp))
            }
        }) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (step in 0..2) item { Text("Working…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline) }
                items(ChatText.rows(msgs, emptyList())) { ChatRow(it) {} }
            }
            if (panel) DemoScreenPanel()
            ComposeBar(typing, {}, "Message or /command…", Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp), leading = {
                IconButton({}, Modifier.size(44.dp)) { Icon(Icons.Default.AttachFile, "attach") } }, canSend = typing.isNotBlank()) {}
        }
    }

    @Composable private fun DemoScreenPanel() {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Screen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("Do you want to run this command?\n\n  pytest -q tests/test_auth.py\n\n❯ 1. Yes\n  2. Yes, and don't ask again\n  3. No",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("↑", "↓", "⏎", "Esc", "1", "2", "3", "y", "n").forEach { FilledTonalButton({}, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.heightIn(min = 34.dp)) { Text(it) } }
                }
            }
        }
    }

    @Test fun chat0() = shot("chat-0") { Chat(0, "Add rate limiting to /login") }
    @Test fun chat1() = shot("chat-1") { Chat(1) }
    @Test fun chat2() = shot("chat-2") { Chat(2) }
    @Test fun chat3() = shot("chat-3") { Chat(3) }
    @Test fun chat4() = shot("chat-4") { Chat(4) }
    @Test fun chat5() = shot("chat-5") { Chat(5) }

    @Test fun screenPanel() = shot("screen") { Chat(1, panel = true) }

    // ---------- Jarvis ----------
    @Composable private fun Jarvis(msgs: List<Msg>, live: String = "") {
        Screen({ Bar("Jarvis", back = true) { Online() } }) {
            JarvisMessages(msgs, rememberLazyListState(), Modifier.weight(1f))
            LiveText(live)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                FilledIconButton({}, Modifier.size(64.dp)) { Icon(Icons.Default.Mic, "talk", Modifier.size(30.dp)) }
            }
        }
    }

    private val q1 = Msg(1, outgoing = true, text = "What is running right now?", kind = "voice")
    private val a1 = Msg(2, outgoing = false, text = "Three sessions: **api-server: rate limiting** is waiting for you, *website redesign* is busy, and data-pipeline is idle.")
    private val q2 = Msg(3, outgoing = true, text = "Tell api-server to use five per minute", kind = "voice")
    private val a2 = Msg(4, outgoing = false, text = "Done. I told the api-server session to use **5 per minute** for /login.")

    @Test fun jarvis0() = shot("jarvis-0") { Jarvis(listOf(Msg(0, outgoing = false, text = "Good morning, Alex. Ask me about your sessions.")), "What is running right") }
    @Test fun jarvis1() = shot("jarvis-1") { Jarvis(listOf(q1)) }
    @Test fun jarvis2() = shot("jarvis-2") { Jarvis(listOf(q1, a1)) }
    @Test fun jarvis3() = shot("jarvis-3") { Jarvis(listOf(q1, a1), "Tell api-server to use") }
    @Test fun jarvis4() = shot("jarvis-4") { Jarvis(listOf(q1, a1, q2, a2)) }

    // ---------- other screens ----------
    @Test fun memory() = shot("memory") {
        val ss = listOf(
            MemSession("1", "website redesign", "c2", "claude", "$HOME/projects/website", "busy", true, 1630.0, 120.0),
            MemSession("2", "api-server: rate limiting", "c1", "claude", "$HOME/projects/api-server", "needs_input", true, 940.0, 0.0),
            MemSession("3", "data-pipeline: nightly job", "c3", "claude", "$HOME/projects/data-pipeline", "idle", true, 410.0, 0.0),
            MemSession("4", "mobile app: onboarding", "x1", "codex", "$HOME/projects/mobile-app", "idle", true, 280.0, 0.0),
        )
        val i = MemoryInfo(15900.0, 5450.0, 8192.0, 600.0, ss, listOf(MemOther("browser", 2400.0, 300.0, 14), MemOther("Xorg", 380.0, 0.0, 1)),
            listOf(MemClosed("5", "docs: getting started", "c2", "claude", "x", "$HOME/projects/docs", T0 - 900, 780.0), MemClosed("6", "api-server: CI cleanup", "c1", "claude", "y", "$HOME/projects/api-server", T0 - 86_400, 0.0)))
        Screen({ Bar("Memory", back = true) }) { MemoryContent(i, T0, null, {}, {}) }
    }

    @Test fun share() = shot("share") {
        Thumbs.load = { _, _, px -> fakeBitmap(px) }
        ShareContent(listOf("login-error.png" to "640 KB", "limits.md" to "2 KB"), "Can you take a look at these?", demoSessions.take(3), "1", "", {},
            "What do you make of these?", {}, {}, null, null, true, {}, {}, chrome = false)
    }

    @Test fun notifications() = shot("notifications") {
        Screen({ Bar("Settings", back = true) }) { Column(Modifier.padding(16.dp)) { NotificationsSection(Prefs(notifyReady = "sound")) {} } }
    }

    @Test fun voicePicker() = shot("voice-picker") {
        fun v(n: String, c: String, q: Int) = VoiceInfo(n, n.take(2), c, q, 200, false, true)
        val de = listOf("deb" to 500, "nfh" to 400, "dea" to 400).map { (c, q) -> v("de-de-x-$c-local", "DE", q) }
        val en = listOf("tpf" to 400, "iob" to 400, "sfg" to 300).map { (c, q) -> v("en-us-x-$c-local", "US", q) }
        Screen({ Bar("Jarvis voice", back = true) }) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                VoicePicker(VoiceUi(listOf("com.google.android.tts" to "Speech Services by Google"), "com.google.android.tts", mapOf("de" to de, "en" to en),
                    mapOf("de" to "de-de-x-nfh-local", "en" to "en-us-x-tpf-local"), 1.0f, googleMissing = false, testing = ""), {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
            }
        }
    }

    @Test fun accounts() = shot("accounts") {
        fun u(a: String, t: String, l: String, plan: String?, w: List<UsageWindow>) = AccountUsage(a, t, l, plan, w, null, T0)
        val l = listOf(u("c1", "claude", "Main", "Max", listOf(UsageWindow("5h", 38.0, T0 + 8000), UsageWindow("week", 21.0, T0 + 86_400 * 3))),
            u("c2", "claude", "Spare", "Pro", listOf(UsageWindow("5h", 72.0, T0 + 4000), UsageWindow("week", 55.0, T0 + 86_400 * 4))),
            u("c3", "claude", "Spare", "Pro", listOf(UsageWindow("5h", 9.0, T0 + 12_000), UsageWindow("week", 30.0, T0 + 86_400 * 5))),
            u("x1", "codex", "Codex", "Plus", listOf(UsageWindow("5h", 15.0, T0 + 6000), UsageWindow("week", 44.0, T0 + 86_400 * 2))))
        Screen({ Bar("Accounts", back = true) }) { AccountsContent(l, null, false, { if (it == "c1") 2 else if (it == "c2") 1 else 0 }, {}) }
    }
}

@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DemoLight : DemoBase("light")

@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
class DemoDark : DemoBase("dark")
