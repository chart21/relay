package dev.relay.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_READY = "ready"
    const val CH_INPUT = "input"
    const val CH_ENDED = "ended"
    const val CH_DIGEST = "digest"
    const val CH_CONFIRM = "confirm"
    const val CH_ROUTER = "router"
    const val CH_UPDATE = "update"
    const val CH_NOTICE = "notice"
    const val CH_SEND = "send"
    const val CH_QUIET = "quiet" // low importance: the silent mode of "ready" and "needs input" (a channel's importance cannot change later)
    const val ACT_SEND = "dev.relay.app.SEND"
    const val EXTRA_UPDATE = "update"
    private const val NID_UPDATE = 0x7e1a
    const val ACT_CONFIRM = "dev.relay.app.CONFIRM"
    const val ACT_REPLY = "dev.relay.app.REPLY"
    const val KEY_REPLY = "reply"
    const val EXTRA_SESSION = "session_key"
    private const val GROUP = "relay_sessions"
    private const val ID_DIGEST = 5
    private const val ID_ROUTER = 4

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int) = nm.createNotificationChannel(NotificationChannel(id, name, imp))
        ch(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW)
        ch(CH_READY, "Session ready for input", NotificationManager.IMPORTANCE_HIGH)
        ch(CH_INPUT, "Session needs input", NotificationManager.IMPORTANCE_HIGH)
        ch(CH_ENDED, "Session ended unexpectedly", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_DIGEST, "While you were away", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_CONFIRM, "Approvals", NotificationManager.IMPORTANCE_HIGH)
        ch(CH_ROUTER, "Jarvis replies", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_NOTICE, "Messages from the Desktop", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_SEND, "Send approvals", NotificationManager.IMPORTANCE_HIGH)
        ch(CH_QUIET, "Session updates (silent)", NotificationManager.IMPORTANCE_LOW)
    }

    /** A plain message from a desktop job or agent (`notifyd phone notify`); the same tag replaces the earlier one. */
    fun notice(ctx: Context, n: NoticeEv) {
        val id = Notices.id(n.tag, n.meta.seq)
        post(ctx, id, NotificationCompat.Builder(ctx, CH_NOTICE).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(n.title.ifBlank { "Relay" }).setContentText(n.text).setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .apply { if (n.source.isNotBlank()) setSubText(n.source) }
            .setContentIntent(open(ctx, null, id)).setAutoCancel(true).build())
    }

    fun update(ctx: Context, b: AppBuild) {
        val pi = PendingIntent.getActivity(ctx, NID_UPDATE, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, NID_UPDATE, NotificationCompat.Builder(ctx, CH_UPDATE).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Relay update available").setContentText("Version ${b.versionName} (%.0f MB): tap to install".format(b.gzSize / 1e6))
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    fun updateConfirm(ctx: Context, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, NID_UPDATE + 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, NID_UPDATE, NotificationCompat.Builder(ctx, CH_UPDATE).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Finish the Relay update").setContentText("Tap to confirm the installation")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    /** Opens the app, optionally deep-linking to a session (key or `tmux:<name>`). */
    private fun open(ctx: Context, sessionKey: String?, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (sessionKey != null) putExtra(EXTRA_SESSION, sessionKey) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) } // POST_NOTIFICATIONS may be denied
    }

    fun service(ctx: Context, text: String) = NotificationCompat.Builder(ctx, CH_SERVICE)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Relay").setContentText(text)
        .setOngoing(true).setContentIntent(open(ctx, null, 1)).build()

    /** One notification per session (stable id), so a newer state replaces the older one. */
    fun session(ctx: Context, s: Session, kind: Kind, detail: String, silent: Boolean = false) {
        PhoneStats.notifications++
        val id = s.key.hashCode()
        val who = "${s.alias} · ${s.name}"
        val (channel, title, icon) = when (kind) {
            Kind.READY -> Triple(CH_READY, "$who is ready", android.R.drawable.ic_dialog_info)
            Kind.NEEDS_INPUT -> Triple(CH_INPUT, "$who needs input", android.R.drawable.ic_dialog_alert)
            Kind.ENDED -> Triple(CH_ENDED, "$who ended", android.R.drawable.ic_delete)
        }
        val body = detail.ifBlank { s.lastMessage }.ifBlank { when (kind) { Kind.READY -> "Waiting for input"; Kind.NEEDS_INPUT -> "Permission or input needed"; Kind.ENDED -> "The session stopped" } }
        val b = NotificationCompat.Builder(ctx, if (silent) CH_QUIET else channel).setSmallIcon(icon).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open(ctx, s.key, id))
            .setAutoCancel(true).setGroup(GROUP).setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (kind == Kind.NEEDS_INPUT) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
        if (kind != Kind.ENDED) {
            val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to ${s.name}").build()
            val replyPi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, ActionReceiver::class.java).setAction(ACT_REPLY)
                .putExtra(EXTRA_SESSION, s.key).putExtra("nid", id), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPi).addRemoteInput(reply).build())
        }
        post(ctx, id, b.build())
    }

    fun replyFailed(ctx: Context, key: String, id: Int, error: String) = post(ctx, id,
        NotificationCompat.Builder(ctx, CH_INPUT).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Reply not sent")
            .setContentText(error).setContentIntent(open(ctx, key, id)).setAutoCancel(true).setGroup(GROUP).build())

    /** The single summary for events replayed after a reconnect. */
    fun digest(ctx: Context, text: String, count: Int) = post(ctx, ID_DIGEST,
        NotificationCompat.Builder(ctx, CH_DIGEST).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (count == 1) "1 update" else "$count updates").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open(ctx, null, ID_DIGEST)).setAutoCancel(true).build())

    fun confirm(ctx: Context, c: Confirm) {
        fun pi(ok: Boolean) = PendingIntent.getBroadcast(ctx, c.id.hashCode() + if (ok) 1 else 0,
            Intent(ctx, ActionReceiver::class.java).setAction(ACT_CONFIRM).putExtra("action_id", c.id).putExtra("ok", ok)
                .putExtra("nid", c.id.hashCode()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, c.id.hashCode(), NotificationCompat.Builder(ctx, CH_CONFIRM).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Approve?").setContentText(c.description).setStyle(NotificationCompat.BigTextStyle().bigText(c.description))
            .setContentIntent(open(ctx, null, 3))
            .addAction(0, "Approve", pi(true)).addAction(0, "Deny", pi(false)).setAutoCancel(true).build())
    }

    fun sendId(requestId: String) = ("send:" + requestId).hashCode()

    /** Approval for a request to send something (nothing leaves the phone or the Desktop without this tap). */
    fun sendRequest(ctx: Context, r: SendReq, appLabel: String) {
        val id = sendId(r.id)
        fun pi(ok: Boolean) = PendingIntent.getBroadcast(ctx, id + if (ok) 1 else 0,
            Intent(ctx, ActionReceiver::class.java).setAction(ACT_SEND).putExtra("request_id", r.id).putExtra("ok", ok).putExtra("nid", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, id, NotificationCompat.Builder(ctx, CH_SEND).setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(SendRules.title(r, appLabel)).setContentText(SendRules.body(r)).setStyle(NotificationCompat.BigTextStyle().bigText(SendRules.body(r)))
            .setContentIntent(open(ctx, null, id)).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(0, if (r.byPhone) "Send" else "Approve", pi(true)).addAction(0, if (r.byPhone) "Cancel" else "Deny", pi(false)).build())
    }

    fun router(ctx: Context, text: String) = post(ctx, ID_ROUTER, NotificationCompat.Builder(ctx, CH_ROUTER)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Relay").setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open(ctx, null, ID_ROUTER)).setAutoCancel(true).build())

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)
    fun cancelSession(ctx: Context, key: String) = NotificationManagerCompat.from(ctx).cancel(key.hashCode())
}
