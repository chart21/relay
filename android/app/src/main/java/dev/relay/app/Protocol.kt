package dev.relay.app

import org.json.JSONArray
import org.json.JSONObject

/** Plain-Kotlin model of PROTOCOL.md (v2): session objects, events, rpc framing. No Android deps (JVM-testable). */

fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

data class Session(
    val key: String, val tool: String, val alias: String, val id: String, val label: String,
    val cwd: String, val title: String, val state: String, val alive: Boolean, val attachable: Boolean,
    val tmuxSession: String?, val lastActivity: Double, val lastMessage: String, val duplicates: Int,
    val remoteUrl: String = "", // the session in the Claude app (Remote Control), "" when off
    val background: Boolean = false, // headless/scratch run (only listed with include_background)
) {
    val name get() = title.ifBlank { id.take(8) }
    val folder get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
    val target get() = tmuxSession?.let { "tmux:$it" } ?: key
    val exited get() = state == "exited"
    fun matches(ref: String) = key == ref || (tmuxSession != null && "tmux:$tmuxSession" == ref)

    companion object {
        fun fromJson(o: JSONObject): Session {
            val key = o.getString("key")
            val parts = key.split(':', limit = 3)
            return Session(
                key = key, tool = o.str("tool") ?: parts.getOrElse(0) { "" }, alias = o.str("alias") ?: parts.getOrElse(1) { "" },
                id = o.str("id") ?: parts.getOrElse(2) { "" }, label = o.str("label") ?: "", cwd = o.str("cwd") ?: "",
                title = o.str("title") ?: "", state = o.str("state") ?: "idle", alive = o.optBoolean("alive", true),
                attachable = o.optBoolean("attachable", false), tmuxSession = o.str("tmux_session")?.takeIf { it.isNotBlank() },
                lastActivity = o.optDouble("last_activity", 0.0), lastMessage = o.str("last_message") ?: "",
                duplicates = o.optInt("duplicates", 1), remoteUrl = o.str("remote_url") ?: "", background = o.optBoolean("background", false),
            )
        }
    }
}

data class Account(val alias: String, val tool: String, val label: String, val home: String, val loggedIn: Boolean) {
    companion object {
        fun fromJson(o: JSONObject) = Account(o.getString("alias"), o.str("tool") ?: "", o.str("label") ?: o.getString("alias"),
            o.str("home") ?: "", o.optBoolean("logged_in", true))
    }
}

data class ToolCall(val name: String, val summary: String)
data class TMessage(val id: String, val role: String, val text: String, val tools: List<ToolCall>, val ts: Double) {
    companion object {
        fun fromJson(o: JSONObject) = TMessage(
            id = o.str("id") ?: "", role = o.str("role") ?: "assistant", text = o.str("text") ?: "",
            tools = o.optJSONArray("tools").objects().map { ToolCall(it.str("name") ?: "tool", it.str("summary") ?: "") },
            ts = o.optDouble("ts", 0.0),
        )
    }
}

data class DirEntry(val name: String, val path: String, val isGit: Boolean)
data class Listing(val path: String, val parent: String?, val dirs: List<DirEntry>) {
    companion object {
        fun fromJson(o: JSONObject) = Listing(o.getString("path"), o.str("parent"),
            o.optJSONArray("dirs").objects().map { DirEntry(it.getString("name"), it.getString("path"), it.optBoolean("is_git")) })
    }
}
data class RecentDir(val path: String, val lastUsed: Double, val tools: List<String>)

data class Meta(val seq: Long = 0, val ts: Double = 0.0, val replay: Boolean = false)

sealed interface Ev { val meta: Meta }
data object ConnOpened : Ev { override val meta = Meta() }
data class HelloOk(override val meta: Meta, val seq: Long, val sessions: List<Session>, val accounts: List<Account>, val config: JSONObject?,
                   val app: AppBuild? = null) : Ev
data class SessionStarted(override val meta: Meta, val session: Session) : Ev
data class SessionState(override val meta: Meta, val session: Session, val prevState: String?, val reason: String, val message: String?) : Ev
data class SessionEnded(override val meta: Meta, val sessionKey: String, val session: Session?, val byUser: Boolean) : Ev
data class NeedsConfirmation(override val meta: Meta, val actionId: String, val description: String) : Ev
data class ConfirmationResolved(override val meta: Meta, val actionId: String, val result: String) : Ev
data class MonitorError(override val meta: Meta, val error: String) : Ev
data class TranscriptAppend(override val meta: Meta, val sessionKey: String, val messages: List<TMessage>) : Ev
data class SendRequestEv(override val meta: Meta, val req: SendReq) : Ev
data class SendResolved(override val meta: Meta, val requestId: String, val ok: Boolean, val error: String?) : Ev
data class NoticeEv(override val meta: Meta, val title: String, val text: String, val source: String, val tag: String?) : Ev
data class LocationRequestEv(override val meta: Meta, val requestId: String, val reason: String, val expiresAt: Double) : Ev
data class RpcResult(val id: String, val ok: Boolean, val result: JSONObject?, val error: String?) : Ev { override val meta = Meta() }
data class RpcProgress(val id: String, val delta: String) : Ev { override val meta = Meta() }
data class AppUpdateEv(val app: AppBuild?) : Ev { override val meta = Meta() }
data class UnknownEv(val type: String, override val meta: Meta = Meta()) : Ev

object Protocol {
    const val VERSION = 2

    @Volatile var appVersion = "" // "<versionName> (<versionCode>)", set at startup; lets the Desktop see which build runs
    fun hello(sinceSeq: Long) = JSONObject().put("type", "hello").put("since_seq", sinceSeq).put("client", "relay-android").put("version", VERSION)
        .put("app_version", appVersion)
    fun rpc(id: String, method: String, params: JSONObject) = JSONObject().put("type", "rpc").put("id", id).put("method", method).put("params", params)

    fun parse(line: String): Ev? = runCatching { parse(JSONObject(line)) }.getOrNull()

    fun parse(o: JSONObject): Ev {
        val meta = Meta(o.optLong("seq", 0), o.optDouble("ts", 0.0), o.optBoolean("replay", false))
        return when (val type = o.optString("type")) {
            "hello_ok" -> HelloOk(meta, o.optLong("seq", 0), o.optJSONArray("sessions").objects().map(Session::fromJson),
                o.optJSONArray("accounts").objects().map(Account::fromJson), o.optJSONObject("config"), AppBuild.fromJson(o.optJSONObject("app")))
            "session_started" -> SessionStarted(meta, Session.fromJson(o.getJSONObject("session")))
            "session_state" -> SessionState(meta, Session.fromJson(o.getJSONObject("session")), o.str("prev_state"), o.str("reason") ?: "", o.str("message"))
            "session_ended" -> {
                val s = o.optJSONObject("session")?.let(Session::fromJson)
                SessionEnded(meta, o.str("session_key") ?: s?.key ?: "", s, o.optBoolean("by_user", false))
            }
            "needs_confirmation" -> NeedsConfirmation(meta, o.getString("action_id"), o.str("description") ?: "")
            "confirmation_resolved" -> ConfirmationResolved(meta, o.getString("action_id"), o.str("result") ?: "")
            "monitor_error" -> MonitorError(meta, o.str("error") ?: "")
            "transcript_append" -> TranscriptAppend(meta, o.getString("session_key"), o.optJSONArray("messages").objects().map(TMessage::fromJson))
            "send_request" -> SendRequestEv(meta, SendReq.fromJson(o))
            "send_resolved" -> SendResolved(meta, o.getString("request_id"), o.optBoolean("ok"), o.str("error"))
            "notice" -> NoticeEv(meta, o.str("title") ?: "", o.str("text") ?: "", o.str("source") ?: "", o.str("tag"))
            "location_request" -> LocationRequestEv(meta, o.getString("request_id"), o.str("reason") ?: "", o.optDouble("expires_at", 0.0))
            "rpc_result" -> RpcResult(o.getString("id"), o.optBoolean("ok"), o.optJSONObject("result"), o.str("error"))
            "rpc_progress" -> RpcProgress(o.getString("id"), o.optString("delta"))
            "app_update" -> AppUpdateEv(AppBuild.fromJson(o.optJSONObject("app")))
            else -> UnknownEv(type, meta)
        }
    }
}

/** Overview agent config (`get_config`). */
data class OverviewConfig(val alias: String, val model: String) {
    companion object {
        fun fromJson(o: JSONObject?): OverviewConfig? = o?.optJSONObject("overview")?.let { OverviewConfig(it.str("alias") ?: "", it.str("model") ?: "") }
    }
}
