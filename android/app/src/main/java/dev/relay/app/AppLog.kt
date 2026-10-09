package dev.relay.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Small append-only file of JSON lines (one entry each), capped in size. Plain JVM, unit-tested.
 * Entries: {ts, level, tag, message, stack, app_version}.
 */
class AppLogStore(private val file: File, private val maxEntries: Int = 300, private val dedupeSeconds: Double = 60.0) {
    private var last: JSONObject? = null

    @Synchronized fun add(level: String, tag: String, message: String, stack: String, version: String, now: Double = System.currentTimeMillis() / 1000.0): Boolean {
        val msg = message.take(400)
        last?.let { if (it.optString("tag") == tag && it.optString("message") == msg && it.optString("level") == level && now - it.optDouble("ts") < dedupeSeconds) return false }
        val e = JSONObject().put("ts", now).put("level", level).put("tag", tag).put("message", msg).put("stack", stack.take(6000)).put("app_version", version)
        last = e
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(e.toString() + "\n")
            if (file.length() > 400_000) { val keep = lines().takeLast(maxEntries); file.writeText(keep.joinToString("") { it + "\n" }) }
        }
        return true
    }

    private fun lines(): List<String> = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** The oldest [max] entries (what the next upload carries); unreadable lines are skipped. */
    @Synchronized fun pending(max: Int = 200): List<JSONObject> = lines().take(max).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    /** Removes the first [n] lines (after the Desktop confirmed them). */
    @Synchronized fun drop(n: Int) {
        val rest = lines().drop(n)
        runCatching { if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("") { it + "\n" }) }
    }

    @Synchronized fun count() = lines().size
}

/** The app's own error log: uncaught crashes and swallowed errors, sent to the Desktop after the next hello. Never log message contents. */
object AppLog {
    private var store: AppLogStore? = null

    fun init(ctx: Context) {
        if (store != null) return
        val s = AppLogStore(File(ctx.filesDir, "applog.jsonl")).also { store = it }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { s.add("crash", "crash", "${t.name}: ${e.javaClass.name}: ${e.message}", e.stackTraceToString(), Protocol.appVersion) }
            prev?.uncaughtException(t, e)
        }
    }

    fun w(tag: String, msg: String, t: Throwable? = null) { store?.add("warn", tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}", t?.stackTraceToString() ?: "", Protocol.appVersion) }

    /** Sends what is pending (max 200) with rpc `app_log` and deletes it after the ok. */
    suspend fun flush() {
        val s = store ?: return
        val entries = s.pending(200)
        if (entries.isEmpty()) return
        runCatching {
            Relay.client.rpc("app_log", JSONObject().put("entries", org.json.JSONArray(entries)), 20_000)
            s.drop(entries.size)
        }
    }
}
