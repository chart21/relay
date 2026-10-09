package dev.relay.app

enum class Kind { READY, NEEDS_INPUT, ENDED }

enum class Alert { POPUP, SILENT, NONE }

/** What a session event does to the user, from the notification settings and the per-session mute. */
object NotifyPolicy {
    fun alert(kind: Kind, p: Prefs, key: String): Alert = when {
        key in p.muted -> Alert.NONE
        kind == Kind.READY -> when (p.notifyReady) { "off" -> Alert.NONE; "silent" -> Alert.SILENT; else -> Alert.POPUP }
        kind == Kind.NEEDS_INPUT -> if (p.notifyInput == "silent") Alert.SILENT else Alert.POPUP
        else -> Alert.POPUP
    }
}

/** Classifies events for notifications and builds the single "while you were away" digest for replayed ones. */
object Digest {
    const val MAX_AGE_MS = 24 * 3_600_000L

    /**
     * @param wasAlive false when this session's end was already seen (null = unknown).
     * @param knownState the state the client had before the event, used when the event carries no prev_state.
     */
    fun classify(ev: Ev, wasAlive: Boolean? = null, knownState: String? = null): Kind? = when (ev) {
        is SessionState -> {
            val prev = ev.prevState ?: knownState
            when {
                ev.session.state == "needs_input" && prev != "needs_input" -> Kind.NEEDS_INPUT
                ev.session.state == "idle" && prev == "busy" -> Kind.READY
                else -> null
            }
        }
        is SessionEnded -> if (!ev.byUser && wasAlive != false) Kind.ENDED else null
        else -> null
    }

    fun sessionOf(ev: Ev): Session? = when (ev) {
        is SessionState -> ev.session
        is SessionEnded -> ev.session
        is SessionStarted -> ev.session
        else -> null
    }

    fun phrase(k: Kind) = when (k) { Kind.READY -> "finished"; Kind.NEEDS_INPUT -> "needs input"; Kind.ENDED -> "ended" }

    fun label(s: Session?, key: String): String {
        val alias = s?.alias?.takeIf { it.isNotBlank() } ?: key.split(':').getOrNull(1).orEmpty()
        val what = s?.let { it.folder.takeIf { f -> f.isNotBlank() } ?: it.name } ?: key.substringAfterLast(':').take(8)
        return if (alias.isBlank()) what else "$alias · $what"
    }

    data class Result(val text: String, val count: Int, val keys: List<String>)

    /**
     * @param events replayed events in order. The last relevant event per session wins; going busy again or a
     * deliberate kill clears an earlier entry. Events older than 24 h are ignored.
     */
    fun build(events: List<Ev>, nowMs: Long, maxItems: Int = 5): Result? {
        class Entry(var kind: Kind?, var session: Session?, var order: Int)
        val entries = LinkedHashMap<String, Entry>()
        events.forEachIndexed { i, ev ->
            val ts = ev.meta.ts * 1000
            if (ts > 0 && nowMs - ts > MAX_AGE_MS) return@forEachIndexed
            val s = sessionOf(ev)
            val key = when (ev) { is SessionEnded -> ev.sessionKey; else -> s?.key } ?: return@forEachIndexed
            val e = entries.getOrPut(key) { Entry(null, s, i) }
            if (s != null) e.session = s
            val k = classify(ev, true)
            when {
                k != null -> { e.kind = k; e.order = i }
                ev is SessionState && ev.session.state == "busy" -> e.kind = null
                ev is SessionEnded && ev.byUser -> e.kind = null
                ev is SessionStarted -> e.kind = null
            }
        }
        val items = entries.entries.filter { it.value.kind != null }.sortedBy { it.value.order }
        if (items.isEmpty()) return null
        val parts = items.take(maxItems).map { "${label(it.value.session, it.key)} ${phrase(it.value.kind!!)}" }
        val more = items.size - parts.size
        val text = "While you were away: " + parts.joinToString("; ") + if (more > 0) "; and $more more" else ""
        return Result(text, items.size, items.map { it.key })
    }
}


/** Notices from the Desktop: shown live and when replayed within 24 h; a tag maps to one notification slot. */
object Notices {
    fun show(tsSec: Double, nowSec: Double) = tsSec <= 0 || nowSec - tsSec < 86_400
    fun id(tag: String?, seq: Long) = if (!tag.isNullOrBlank()) ("notice:" + tag).hashCode() else ("notice#" + seq).hashCode()
}
