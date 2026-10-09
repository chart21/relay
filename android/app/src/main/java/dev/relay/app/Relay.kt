package dev.relay.app

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class Confirm(val id: String, val description: String)

/** Process-wide state shared by the UI, the foreground service and notification actions. */
object Relay {
    lateinit var app: Context
    lateinit var settings: Settings
    lateinit var db: AppDb
    lateinit var client: GatewayClient
    lateinit var identity: Identity
    lateinit var speech: Speech
    lateinit var net: NetworkMonitor
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = SessionStore()
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val recent = MutableStateFlow<List<Session>>(emptyList())
    val accounts = MutableStateFlow<List<Account>>(emptyList())
    val overview = MutableStateFlow<OverviewConfig?>(null)
    val confirms = MutableStateFlow<List<Confirm>>(emptyList())
    val transcriptAppends = MutableSharedFlow<TranscriptAppend>(extraBufferCapacity = 64)
    val wakeStatus = MutableStateFlow("Off")
    val wakeLevel = MutableStateFlow(0f) // recent "Hey Jarvis" score, shown in Settings for tuning
    /** Session key (or tmux ref) a notification asked us to open; consumed by the nav host. */
    val openRequest = MutableStateFlow<String?>(null)
    @Volatile var foreground = false
    /** Session currently on screen (key or `tmux:<name>`); its notifications are suppressed while the app is in the foreground. */
    @Volatile var visibleRef: String? = null

    private var started = false
    @Volatile private var lastSeq = 0L
    private val seqFlow = MutableStateFlow(0L)
    private val replay = ArrayList<Ev>()
    private val replayConfirms = LinkedHashMap<String, Pair<Confirm, Long>>()
    private val endedSeen = HashSet<String>()
    private val killedByUs = HashSet<String>()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        settings = Settings(app)
        db = Room.databaseBuilder(app, AppDb::class.java, "relay.db").build()
        identity = Identity.loadOrCreate(app)
        net = NetworkMonitor(app)
        client = GatewayClient(identity, net.network) { fp -> settings.update { it.copy(hostFingerprint = fp) } }
        speech = Speech(app) { settings.flow.first() }
        Vocab.load(app)
        Bridge.init(app)
        Notifier.createChannels(app)
        runCatching { Updater.installed(app).let { (code, name) -> Protocol.appVersion = "$name ($code)" } }
    }

    @Synchronized fun startConnection() {
        if (started) return
        started = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.incoming.collect { runCatching { handle(it) }.onFailure { e -> AppLog.w("events", "handling ${it.javaClass.simpleName}", e) } } }
        scope.launch { lastSeq = settings.flow.first().lastSeq; seqFlow.value = lastSeq; client.run(settings.flow) { lastSeq } }
        scope.launch { seqFlow.debounce(1_000).collect { s -> settings.update { it.copy(lastSeq = maxOf(it.lastSeq, s)) } } }
    }

    private fun advance(seq: Long) { if (seq > lastSeq) { lastSeq = seq; seqFlow.value = seq } }
    private fun publish() { sessions.value = store.list() }
    private suspend fun sys(text: String) { db.msgs().insert(Msg(outgoing = false, text = text, kind = "system")) }

    private fun onScreen(s: Session) = foreground && visibleRef?.let { s.matches(it) } == true

    suspend fun markUnread(keys: Collection<String>) {
        if (keys.isEmpty()) return
        settings.update { it.copy(unread = (it.unread + keys).toList().takeLast(200).toSet()) }
    }
    suspend fun togglePin(key: String) { settings.update { p -> p.copy(pinned = if (key in p.pinned) p.pinned - key else p.pinned + key) } }
    suspend fun toggleMute(key: String) { settings.update { p -> p.copy(muted = if (key in p.muted) p.muted - key else p.muted + key) } }
    suspend fun markRead(key: String) { settings.update { p -> if (key in p.unread) p.copy(unread = p.unread - key) else p } }

    // -- incoming events ---------------------------------------------------
    suspend fun handle(ev: Ev) {
        PhoneStats.events++
        if (!ev.meta.replay) advance(ev.meta.seq)
        when (ev) {
            ConnOpened -> { replay.clear(); replayConfirms.clear() }
            is HelloOk -> onHello(ev)
            is TranscriptAppend -> transcriptAppends.emit(ev)
            is SendRequestEv, is SendResolved, is LocationRequestEv -> Bridge.onEvent(ev)
            is NoticeEv -> if (Notices.show(ev.meta.ts, System.currentTimeMillis() / 1000.0)) Notifier.notice(app, ev)
            is AppUpdateEv -> runCatching { Updater.offer(app, ev.app) }
            else -> if (ev.meta.replay) onReplay(ev) else onLive(ev)
        }
    }

    private suspend fun onHello(ev: HelloOk) {
        store.snapshot(ev.sessions); publish()
        accounts.value = ev.accounts
        OverviewConfig.fromJson(ev.config)?.let { overview.value = it }
        runCatching { Updater.offer(app, ev.app) }
        Bridge.onHello()
        scope.launch { AppLog.flush() }
        scope.launch { runCatching { Vocab.refresh() } }
        advance(ev.seq)
        endedSeen.clear()
        val digest = Digest.build(replay.toList(), System.currentTimeMillis())
        replay.clear()
        if (digest != null) { Notifier.digest(app, digest.text, digest.count); markUnread(digest.keys) }
        val now = System.currentTimeMillis()
        replayConfirms.values.filter { now - it.second < 30 * 60_000 }.forEach { (c, _) -> addConfirm(c) }
        replayConfirms.clear()
    }

    private fun addConfirm(c: Confirm) {
        if (confirms.value.none { it.id == c.id }) { confirms.update { it + c }; Notifier.confirm(app, c) }
    }

    /** Replayed events are collected and summarised once at hello_ok instead of notifying one by one. */
    private fun onReplay(ev: Ev) {
        when (ev) {
            is NeedsConfirmation -> replayConfirms[ev.actionId] = Confirm(ev.actionId, ev.description) to (ev.meta.ts * 1000).toLong()
            is ConfirmationResolved -> replayConfirms.remove(ev.actionId)
            is SessionState, is SessionEnded, is SessionStarted -> replay += ev
            else -> {}
        }
    }

    private suspend fun onLive(ev: Ev) {
        when (ev) {
            is SessionStarted -> { endedSeen.remove(ev.session.key); store.upsert(ev.session); publish() }
            is SessionState -> {
                val known = store.get(ev.session.key)?.state
                store.upsert(ev.session); publish()
                if (ev.session.state != "exited") endedSeen.remove(ev.session.key)
                Digest.classify(ev, knownState = known)?.let { notify(it, ev.session, ev.message ?: "") }
            }
            is SessionEnded -> {
                val key = ev.sessionKey
                val known = store.get(key)
                val first = endedSeen.add(key) && !killedByUs.remove(key)
                store.markEnded(key, ev.session); publish()
                val s = ev.session ?: known
                if (s != null) Digest.classify(ev, first)?.let { notify(it, s, "") }
            }
            is NeedsConfirmation -> addConfirm(Confirm(ev.actionId, ev.description))
            is ConfirmationResolved -> { confirms.update { l -> l.filterNot { it.id == ev.actionId } }; Notifier.cancel(app, ev.actionId.hashCode()) }
            is MonitorError -> sys("⚠ ${ev.error}")
            else -> {}
        }
    }

    private suspend fun notify(kind: Kind, s: Session, detail: String) {
        val p = settings.flow.first()
        val alert = NotifyPolicy.alert(kind, p, s.key)
        if (!onScreen(s)) { markUnread(listOf(s.key)); if (alert != Alert.NONE) Notifier.session(app, s, kind, detail, silent = alert == Alert.SILENT) }
        if (kind == Kind.READY && s.key !in p.muted) {
            if (p.readFinished && (speech.headsetConnected() || Assistant.conv.value != ConvState.Off)) scope.launch {
                // only now (headset or conversation mode): a 1-3 sentence summary from the Desktop; the full text stays on screen
                val brief = runCatching {
                    client.rpc("speak_summary", JSONObject().put("key", s.key).put("name", s.name).put("lang", p.ttsLang), 60_000).optString("text")
                }.onFailure { AppLog.w("summary", "speak_summary", it) }.getOrNull()?.takeIf { it.isNotBlank() } ?: SpeechText.brief(s.lastMessage).let { if (it.isBlank()) "${s.name} is ready." else "${s.name}: $it" }
                speech.say(brief, queue = true)
            }
        }
    }

    // -- rpc helpers -------------------------------------------------------
    suspend fun refreshSessions(dormant: Boolean, background: Boolean = false) {
        val r = client.rpc("list_sessions", JSONObject().put("include_dormant", dormant).put("limit", 100).apply { if (background) put("include_background", true) })
        val list = r.optJSONArray("sessions").objects().map(Session::fromJson)
        if (dormant) recent.value = list else { store.snapshot(list + store.list().filter { it.exited && list.none { n -> n.key == it.key } }); publish() }
    }

    suspend fun loadAccounts(): List<Account> {
        val l = client.rpc("accounts").optJSONArray("accounts").objects().map(Account::fromJson)
        accounts.value = l
        return l
    }

    suspend fun sendToSession(key: String, text: String, force: Boolean = false) {
        client.rpc("send", JSONObject().put("key", key).put("text", text).put("enter", true).put("force", force))
    }

    suspend fun kill(key: String) {
        killedByUs += key
        try { client.rpc("kill", JSONObject().put("key", key)) } catch (e: Exception) { killedByUs -= key; throw e }
        store.markEnded(key, null); publish()
    }

    /** Stops a live session to free its memory (no "ended" alert); it stays resumable and is listed under Closed in the RAM monitor. */
    suspend fun closeSession(key: String, mb: Double = 0.0) {
        killedByUs += key
        try { client.rpc("close", JSONObject().put("key", key).apply { if (mb > 0) put("mb", mb) }, 30_000) } catch (e: Exception) { killedByUs -= key; throw e }
        store.markEnded(key, null); publish()
    }

    /** Resumes a closed or finished session in a new tmux session; returns the attach target (`tmux:<name>`). */
    suspend fun reopen(key: String): String {
        val r = client.rpc("reopen", JSONObject().put("key", key), 60_000)
        runCatching { refreshSessions(false) }
        return r.optString("target").ifBlank { "tmux:" + r.optString("tmux_session") }
    }

    /** Starts a session in a new agent tmux session; returns the attach target (`tmux:<name>`). */
    suspend fun launch(alias: String, cwd: String, prompt: String?): String {
        val p = JSONObject().put("alias", alias).put("cwd", cwd)
        if (!prompt.isNullOrBlank()) p.put("prompt", prompt)
        val r = client.rpc("launch", p, 60_000)
        return r.optString("target").ifBlank { "tmux:" + r.optString("tmux_session") }
    }

    fun confirm(actionId: String, ok: Boolean) {
        confirms.update { l -> l.filterNot { it.id == actionId } }
        Notifier.cancel(app, actionId.hashCode())
        scope.launch { runCatching { client.rpc("confirm", JSONObject().put("action_id", actionId).put("ok", ok)) } }
    }

    suspend fun loadConfig() { OverviewConfig.fromJson(client.rpc("get_config"))?.let { overview.value = it } }
    suspend fun setOverview(alias: String?, model: String?) {
        val p = JSONObject()
        if (alias != null) p.put("overview_alias", alias)
        if (model != null) p.put("overview_model", model)
        OverviewConfig.fromJson(client.rpc("set_config", p))?.let { overview.value = it }
    }
}
