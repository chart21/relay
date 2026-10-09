package dev.relay.app

/** Live session state, fed by the hello_ok snapshot and events. Thread-safe; pure Kotlin. */
class SessionStore {
    private val map = LinkedHashMap<String, Session>()

    @Synchronized fun snapshot(list: List<Session>) { map.clear(); list.forEach { map[it.key] = it } }
    @Synchronized fun upsert(s: Session) { map[s.key] = s }
    @Synchronized fun upsertAll(list: List<Session>) { list.forEach { map[it.key] = it } }
    @Synchronized fun get(key: String): Session? = map[key]
    @Synchronized fun find(ref: String): Session? = map[ref] ?: map.values.firstOrNull { it.matches(ref) }
    @Synchronized fun list(): List<Session> = map.values.toList()

    /** Marks a session exited (kept in the list greyed until the next snapshot). */
    @Synchronized fun markEnded(key: String, last: Session?): Session? {
        val cur = map[key] ?: last ?: return null
        return cur.copy(state = "exited", alive = false).also { map[key] = it }
    }
    @Synchronized fun remove(key: String) { map.remove(key) }
}

enum class SessionFilter { LIVE, RECENT }

object Grouping {
    private val rank = mapOf("needs_input" to 0, "busy" to 1, "idle" to 2, "exited" to 3)

    fun stateRank(state: String) = rank[state] ?: 2

    /** needs_input first, then busy, idle, exited; newest activity first within a state. */
    val order: Comparator<Session> = compareBy<Session> { stateRank(it.state) }.thenByDescending { it.lastActivity }

    fun filter(sessions: List<Session>, f: SessionFilter) = when (f) {
        SessionFilter.LIVE -> sessions.filter { !it.exited }
        SessionFilter.RECENT -> sessions
    }

    /** Live state wins over the (possibly older) recent list; sessions only the recent list knows are appended. */
    fun merge(live: List<Session>, recent: List<Session>): List<Session> {
        val keys = live.mapTo(HashSet()) { it.key }
        return live + recent.filter { it.key !in keys }
    }

    /** One flat list, not grouped by account (the row shows the account). Live: sessions waiting for the user first,
     *  then newest activity; Recent: newest activity only. */
    fun list(sessions: List<Session>, f: SessionFilter = SessionFilter.LIVE): List<Session> = filter(sessions, f).let { l ->
        if (f == SessionFilter.RECENT) l.sortedByDescending { it.lastActivity }
        else l.sortedWith(compareBy<Session> { if (it.state == "needs_input") 0 else 1 }.thenByDescending { it.lastActivity })
    }
}

/** Search and pins on top of [Grouping.list]: pinned sessions first, then the rest; the query matches title, folder, account and last message. */
object Overview {
    fun matches(s: Session, q: String): Boolean = q.isBlank() || q.trim().lowercase().split(Regex("\\s+")).all { w ->
        listOf(s.name, s.cwd, s.alias, s.label, s.lastMessage).any { it.contains(w, ignoreCase = true) }
    }

    fun view(sessions: List<Session>, f: SessionFilter, pinned: Set<String>, query: String, showBackground: Boolean = false): List<Session> {
        val l = Grouping.list(sessions.filter { showBackground || !it.background }, f).filter { matches(it, query) }
        return l.filter { it.key in pinned } + l.filter { it.key !in pinned }
    }

    /** Short status word under a row: what the session is doing, or why it is greyed. */
    fun status(s: Session): String = when {
        s.exited -> "ended"
        s.state == "needs_input" -> "needs your input"
        s.state == "busy" -> "working"
        !s.attachable -> "read-only"
        else -> "idle"
    }

    fun statusOf(state: String): String = when (state) { "needs_input" -> "needs your input"; "busy" -> "working"; "exited" -> "ended"; else -> "idle" }
}

/** "3 min ago" style label; `nowS`/`thenS` in unix seconds. */
fun agoLabel(thenS: Double, nowS: Double): String {
    if (thenS <= 0) return ""
    val d = (nowS - thenS).toLong().coerceAtLeast(0)
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60} min"
        d < 86_400 -> "${d / 3600} h"
        else -> "${d / 86_400} d"
    }
}

fun shortPath(path: String): String {
    val m = Regex("^/home/[^/]+").find(path) ?: return path
    return "~" + path.removePrefix(m.value)
}

/** Merging of paged/live transcript messages (oldest first), de-duplicated by id. */
object TranscriptLogic {
    /** Messages in [incoming] that [existing] does not have yet (blank ids always count as new). */
    fun fresh(existing: List<TMessage>, incoming: List<TMessage>): List<TMessage> {
        val ids = existing.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id.isNotBlank() } }
        return incoming.filter { it.id.isBlank() || ids.add(it.id) }
    }
}
