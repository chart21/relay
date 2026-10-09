package dev.relay.app

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Keeps the SSH link to the PC alive. Foreground type remoteMessaging (dataSync before API 34); the microphone type
 * is added only while the wake word or conversation mode is active. Holds no wakelock.
 * Battery: the notification is re-posted only when its text or type changes (not on every saved event cursor), and
 * with "Listen when" = active the wake word stops capturing while the screen is off, no headset is on and it isn't
 * charging (the microphone type stays, so listening can resume from the background).
 */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: WakeListener? = null
    @Volatile private var threshold = 0.5f
    private var micType = false
    private var posted: Pair<String, Boolean>? = null
    private val active = MutableStateFlow(true) // screen on, headset or charging
    private val retry = MutableStateFlow(0) // bumped when the app comes to the front: a refused microphone type can be granted now
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = updateActive() }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = updateActive()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = updateActive()
    }

    private data class WakeCfg(val on: Boolean, val sensitivity: Float, val whenActive: Boolean)

    override fun onBind(i: Intent?): IBinder? = null

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateActive() {
        val screen = getSystemService(PowerManager::class.java).isInteractive
        val charging = getSystemService(BatteryManager::class.java).isCharging
        active.value = screen || charging || Relay.speech.headsetConnected()
    }

    private fun fgs(text: String, mic: Boolean): Boolean {
        var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (mic && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            ServiceCompat.startForeground(this, 1, Notifier.service(this, text), type)
            micType = mic && hasMic(); true
        } catch (e: Exception) { // e.g. microphone type refused while started from the background
            if (mic) runCatching { ServiceCompat.startForeground(this, 1, Notifier.service(this, text),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            micType = false; false
        }
    }

    /**
     * Updates the foreground notification only when it changes. Only a change of the microphone type restarts the
     * foreground service; a text change (e.g. screen off -> "Jarvis paused") just updates the notification, because
     * a microphone-type startForeground() from the background may be refused, which would stop the wake word.
     */
    private fun post(text: String, mic: Boolean) {
        val granted = mic && hasMic()
        if (posted == text to mic && micType == granted) return
        val typeChange = posted == null || posted?.second != mic || micType != granted
        posted = text to mic
        if (typeChange) fgs(text, mic)
        else runCatching { getSystemService(NotificationManager::class.java).notify(1, Notifier.service(this, text)) }
    }

    override fun onCreate() {
        super.onCreate()
        Relay.init(this)
        fgs("Starting…", false)
        Relay.startConnection()
        Mic.pause = { wake?.pause() }
        Mic.resume = { wake?.resume() }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        updateActive()
        val cfg = Relay.settings.flow.map { WakeCfg(it.wakeWord, it.sensitivity, it.wakeWhen == "active") }.distinctUntilChanged()
        scope.launch {
            combine(Relay.client.state, cfg, Assistant.conv, active, retry) { c, w, v, a, _ -> arrayOf(c, w, v, a) }.collect { (c, w, v, a) ->
                c as Conn; w as WakeCfg; v as ConvState; a as Boolean
                val conn = when (c) {
                    Conn.Connected -> "Connected"
                    Conn.Connecting -> "Connecting…"
                    Conn.Off -> "Not configured"
                    Conn.NoNetwork -> "Offline (waiting for network)"
                    is Conn.Error -> "Offline: ${c.msg}".take(80)
                }
                val mic = w.on || v != ConvState.Off
                fun text() = conn + when {
                    wake != null && wake?.idle == true -> " · Jarvis paused (screen off)"
                    wake != null -> " · listening for Jarvis"
                    v != ConvState.Off -> " · conversation"
                    else -> ""
                }
                post(text(), mic)
                configureWake(w, a)
                post(text(), mic) // the wake listener may have started or paused just now
            }
        }
    }

    /** "Hey Jarvis" (openWakeWord, on the phone, no account or key): wake -> conversation (follow-ups without the wake
     *  word until silence or "stop"/"danke") -> listening for the wake word again. */
    private fun configureWake(w: WakeCfg, active: Boolean) {
        threshold = (1f - w.sensitivity).coerceIn(0.1f, 0.95f)
        val want = w.on && micType
        if (want != (wake != null)) {
            if (!want) {
                wake?.stop(); wake = null
                Relay.wakeStatus.value = when {
                    !w.on -> "Off"
                    !hasMic() -> "Failed: microphone permission missing"
                    else -> "Failed: open the app once so the microphone service can start"
                }
                return
            }
            Relay.wakeStatus.value = "Starting…"
            wake = WakeListener(this, { threshold }) { at ->
                scope.launch(Dispatchers.Default) {
                    try { Assistant.startConversation(this@RelayService, "wake", at).join() }
                    finally { wake?.let { it.held = false; it.resume() } }
                }
            }.also { it.start() }
        }
        wake?.idle = w.whenActive && !active
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() needs startForeground() again within 5 s; from the app (in front) this also
        // gets a microphone type that was refused while starting in the background
        val (t, m) = posted ?: ("Starting…" to false)
        val had = micType
        fgs(t, m)
        if (micType != had) retry.value++
        return START_STICKY
    }

    override fun onDestroy() {
        Mic.pause = null; Mic.resume = null
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(devices) }
        wake?.stop(); wake = null
        scope.cancel()
        super.onDestroy()
    }
}
