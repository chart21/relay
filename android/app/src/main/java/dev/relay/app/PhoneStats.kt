package dev.relay.app

import android.content.Context
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Battery report, every 30 min while connected (on the bridge loop's read tick, no extra wake-up): what the app did
 * since the last report. Stored by the gateway's `voice_timing` log as trigger "stats"; `notifyd timing --stats`.
 */
object PhoneStats {
    @Volatile var events = 0L
    @Volatile var notifications = 0L

    private data class Snap(val wall: Long, val cpu: Long, val listen: Long, val run: Long, val skipped: Long, val wakes: Long,
                            val events: Long, val notifications: Long, val rx: Long, val tx: Long)

    private var last: Snap? = null

    private fun snap() = Snap(System.currentTimeMillis(), Process.getElapsedCpuTime(), WakeStats.listenMs, WakeStats.run,
        WakeStats.skipped, WakeStats.detections, events, notifications,
        TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0), TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0))

    /** The wake word's state as a number for the report: 0 off, 1 listening, 2 paused (screen off), 3 failed, 4 other. */
    fun wakeCode(status: String) = when {
        status == "Off" -> 0
        status.startsWith("Listening") -> 1
        status.startsWith("Paused") -> 2
        status.startsWith("Failed") -> 3
        else -> 4
    }

    fun report(ctx: Context) {
        val now = snap()
        val prev = last.also { last = now } ?: return // the first call only sets the baseline
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val stages = JSONObject()
            .put("wall_ms", now.wall - prev.wall).put("cpu_ms", now.cpu - prev.cpu)
            .put("listen_ms", now.listen - prev.listen).put("run", now.run - prev.run).put("skipped", now.skipped - prev.skipped)
            .put("detections", now.wakes - prev.wakes).put("events", now.events - prev.events)
            .put("notifications", now.notifications - prev.notifications)
            .put("rx_kb", (now.rx - prev.rx) / 1024).put("tx_kb", (now.tx - prev.tx) / 1024)
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", if (bm.isCharging) 1 else 0)
            .put("screen", if (ctx.getSystemService(PowerManager::class.java).isInteractive) 1 else 0)
            .put("wake", wakeCode(Relay.wakeStatus.value))
        val o = JSONObject().put("trigger", "stats").put("stt", "").put("stages", stages)
        Relay.scope.launch { runCatching { Relay.client.rpc("voice_timing", o, 10_000) } }
    }
}
