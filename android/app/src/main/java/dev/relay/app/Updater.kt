package dev.relay.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A build the gateway offers (`notifyd publish-apk`), announced in hello_ok. */
data class AppBuild(val versionCode: Long, val versionName: String, val size: Long, val gzSize: Long, val sha256 : String) {
    companion object {
        fun fromJson(o: JSONObject?): AppBuild? = o?.let {
            if (!it.has("version_code")) null else AppBuild(it.optLong("version_code"), it.optString("version_name"),
                it.optLong("size"), it.optLong("gz_size"), it.optString("sha256"))
        }
        fun newer(installed: Long, b: AppBuild?) = b?.takeIf { it.versionCode > installed && it.sha256.isNotBlank() }
    }
}

/**
 * Self-update over the SSH link, so the app can be updated from anywhere the phone reaches the Desktop (WireGuard):
 * download (`apk`, gzip) -> size + sha256 check -> Android's package installer (one confirmation tap).
 */
object Updater {
    val available = MutableStateFlow<AppBuild?>(null)
    val status = MutableStateFlow("")
    @Volatile private var running = false

    fun installed(ctx: Context): Pair<Long, String> =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { it.longVersionCode to (it.versionName ?: "") }

    fun offer(ctx: Context, build: AppBuild?) {
        val b = AppBuild.newer(installed(ctx).first, build)
        available.value = b
        if (b == null) return
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (prefs.getLong("notified", 0) != b.versionCode) {
            Notifier.update(ctx, b)
            prefs.edit().putLong("notified", b.versionCode).apply()
        }
    }

    suspend fun run(ctx: Context) {
        if (running) return
        running = true
        try {
            // the build may have been republished since the last hello_ok: download against the current size and hash
            runCatching { Relay.client.rpc("app_update", JSONObject(), 15_000).optJSONObject("app") }
                .onSuccess { offer(ctx, AppBuild.fromJson(it)) }
            val b = available.value ?: run { status.value = "No update available"; return }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                status.value = "Allow Relay to install updates (switch on), then tap Update again"
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val f = File(ctx.cacheDir, "relay-update.apk")
            status.value = "Downloading…"
            val n = Relay.client.download("apk", f) { done -> status.value = "Downloading ${100 * done / maxOf(1, b.size)}%" }
            if (n != b.size || sha256(f) != b.sha256) throw IOException("download incomplete or corrupted, try again")
            status.value = "Installing…"
            withContext(Dispatchers.IO) { install(ctx, f) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            status.value = "Update failed: ${e.message ?: e.javaClass.simpleName}"
        } finally { running = false }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(f.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // honoured once we installed ourselves
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("relay.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(done.intentSender)
        }
    }
}

/** Package installer results: ask for the confirmation tap, report the outcome. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.status.value = "Confirm the update"
                Notifier.updateConfirm(ctx, confirm) // works even when a background activity start is blocked
                runCatching { ctx.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status.value = "Updated"
            else -> Updater.status.value = "Update failed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installer error")
        }
    }
}

/** After an update the process was replaced: bring the connection (foreground service) back without opening the app. */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Relay.init(ctx)
        startService(ctx)
    }
}
