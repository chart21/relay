package dev.relay.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile "Jarvis": a tap starts (or, while it runs, ends) a hands-free conversation. */
class JarvisTile : TileService() {
    private var watch: Job? = null

    override fun onStartListening() {
        Relay.init(applicationContext)
        watch = Relay.scope.launch { Assistant.conv.collect { c -> qsTile?.apply { state = if (c == ConvState.Off) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() } } }
    }

    override fun onStopListening() { watch?.cancel(); watch = null }

    /** Starting goes through the app (like the "Jarvis" shortcut): Android lets the microphone start only from the
     *  foreground, and a tile tap alone does not bring the app there. Stopping works directly. */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        Relay.init(applicationContext)
        if (Assistant.conv.value != ConvState.Off) { Assistant.stopConversation(); return }
        val open = android.content.Intent(this, MainActivity::class.java).setAction(ACT_JARVIS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        unlockAndRun {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
        }
    }
}
