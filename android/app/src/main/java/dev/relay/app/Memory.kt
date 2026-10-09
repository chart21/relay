package dev.relay.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Locale

data class MemSession(val key: String, val title: String, val alias: String, val tool: String, val cwd: String, val state: String,
                      val closable: Boolean, val mb: Double, val swapMb: Double) {
    val name get() = title.ifBlank { key.substringAfterLast(':').take(8) }
}
data class MemOther(val name: String, val mb: Double, val swapMb: Double, val count: Int)
data class MemClosed(val key: String, val title: String, val alias: String, val tool: String, val id: String, val cwd: String, val closedAt: Double, val mb: Double) {
    val name get() = title.ifBlank { id.take(8) }
}

/** Reply of the gateway's `memory` rpc: the RAM monitor. */
data class MemoryInfo(val totalMb: Double, val availableMb: Double, val swapTotalMb: Double, val swapUsedMb: Double,
                      val sessions: List<MemSession>, val others: List<MemOther>, val closed: List<MemClosed>) {
    val usedMb get() = (totalMb - availableMb).coerceAtLeast(0.0)

    companion object {
        fun fromJson(o: JSONObject) = MemoryInfo(
            o.optDouble("total_mb", 0.0), o.optDouble("available_mb", 0.0), o.optDouble("swap_total_mb", 0.0), o.optDouble("swap_used_mb", 0.0),
            o.optJSONArray("sessions").objects().map { MemSession(it.optString("key"), it.str("title") ?: "", it.str("alias") ?: "", it.str("tool") ?: "", it.str("cwd") ?: "",
                it.str("state") ?: "idle", it.optBoolean("closable", true), it.optDouble("mb", 0.0), it.optDouble("swap_mb", 0.0)) },
            o.optJSONArray("others").objects().map { MemOther(it.optString("name"), it.optDouble("mb", 0.0), it.optDouble("swap_mb", 0.0), it.optInt("count", 1)) },
            o.optJSONArray("closed").objects().map { MemClosed(it.optString("key"), it.str("title") ?: "", it.str("alias") ?: "", it.str("tool") ?: "", it.str("id") ?: "",
                it.str("cwd") ?: "", it.optDouble("closed_at", 0.0), it.optDouble("mb", 0.0)) },
        )
    }
}

/** Pure formatting and thresholds of the RAM monitor (unit-tested). */
object MemText {
    const val LOW_MB = 2048.0

    /** "1.2 GB", "340 MB", "–" for nothing measured. */
    fun size(mb: Double): String = when {
        mb <= 0.0 -> "–"
        mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024)
        else -> "${mb.toInt().coerceAtLeast(1)} MB"
    }
    fun swap(mb: Double) = if (mb >= 1) " + ${size(mb)} swap" else ""
    fun low(i: MemoryInfo) = i.totalMb > 0 && i.availableMb < LOW_MB

    /** Bar length 0..1 relative to the biggest entry; tiny non-zero values stay visible. */
    fun fraction(mb: Double, max: Double) = if (mb <= 0 || max <= 0) 0f else (mb / max).toFloat().coerceIn(0.03f, 1f)

    fun closeText(name: String, mb: Double, state: String): String {
        val frees = if (mb > 0) "frees about ${size(mb)}" else "frees its memory"
        val base = "Close $name? It stops now and $frees; the conversation stays and can be reopened."
        return if (state == "busy") "$base\n\nIt is working right now: that work is interrupted and may be lost." else base
    }

    /** "closed 5 min ago · 1.2 GB" */
    fun closedLine(c: MemClosed, now: Double): String {
        val ago = agoLabel(c.closedAt, now).let { if (it.isBlank()) "" else if (it == "now") "just now" else "$it ago" }
        return listOf(if (ago.isBlank()) "closed" else "closed $ago", size(c.mb).takeIf { c.mb > 0 }).filterNotNull().joinToString(" · ")
    }
}

object MemoryModel {
    val info = MutableStateFlow<MemoryInfo?>(null)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    /** The rpc takes ~1.5 s on the Desktop: a call while one is running is skipped. */
    suspend fun load() {
        if (loading.value) return
        loading.value = true
        try {
            info.value = MemoryInfo.fromJson(Relay.client.rpc("memory", timeoutMs = 30_000))
            error.value = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.message ?: "memory failed" } finally { loading.value = false }
    }
}
