package dev.relay.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Newest reply action (a Notification.Action with RemoteInputs) per (package, conversation or title), kept in memory only. */
object Replies {
    class Entry(val name: String, val action: Notification.Action, val key: String, val time: Long)
    private val map = HashMap<Pair<String, String>, Entry>()

    @Synchronized fun put(pkg: String, name: String, action: Notification.Action, key: String, time: Long) {
        if (name.isBlank()) return
        val k = pkg to name.lowercase()
        if ((map[k]?.time ?: 0) <= time) map[k] = Entry(name, action, key, time)
    }

    @Synchronized fun find(pkg: String, conversation: String): Entry? {
        val list = map.filterKeys { it.first == pkg }.values.toList()
        return ReplyMatch.pick(list.map { it.name }, conversation)?.let { list[it] }
    }

    /** Fills every RemoteInput of the action with `text` and fires it, as if typed into the notification's reply box. */
    fun send(ctx: Context, e: Entry, text: String) {
        val inputs = e.action.remoteInputs ?: throw IllegalStateException("no reply action")
        val results = Bundle().also { b -> inputs.forEach { b.putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
        try { e.action.actionIntent.send(ctx, 0, intent) } catch (x: PendingIntent.CanceledException) { throw IllegalStateException("reply action expired") }
    }
}

/** Forwards notifications of allowlisted apps to the Desktop queue. Bound by Android only after the user granted notification access. */
class BridgeListener : NotificationListenerService() {
    companion object {
        @Volatile private var instance: BridgeListener? = null

        /** Forward what is already in the notification shade: only new posts arrive by themselves (ids dedupe). */
        fun backfill() {
            val l = instance ?: return
            runCatching { l.activeNotifications?.forEach { l.onNotificationPosted(it) } }
        }
    }

    override fun onListenerConnected() {
        instance = this
        Relay.init(this)
        Relay.startConnection() // queued items go out as soon as the gateway is reachable
        startService(this)
        backfill()
    }

    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            Relay.init(this)
            val n = sbn.notification
            if (NotifRules.skip(n.flags, sbn.packageName, packageName)) return
            val label = Bridge.appLabel(this, sbn.packageName)
            val now = System.currentTimeMillis()
            Bridge.store.seen(sbn.packageName, label, now)
            if (!Bridge.store.enabled(Bridge.NOTIFICATIONS) || !Allowlist.allowed(sbn.packageName, label, Bridge.store.overrides.value)) return
            val d = extract(sbn, label)
            findReply(n)?.let { Replies.put(d.pkg, d.replyName, it, sbn.key, d.postTime) }
            Relay.scope.launch(Dispatchers.IO) { Bridge.enqueue("notification", listOf(Items.notification(d))) }
        }
    }

    private fun findReply(n: Notification) = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }

    private fun extract(sbn: StatusBarNotification, label: String): NotifData {
        val n = sbn.notification
        val x = n.extras
        fun s(k: String) = x.getCharSequence(k)?.toString()
        val ms = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = ms?.messages.orEmpty().map { BMsg(it.person?.name?.toString().orEmpty(), it.text?.toString().orEmpty(), it.timestamp) }
        return NotifData(
            pkg = sbn.packageName, label = label, key = sbn.key, postTime = sbn.postTime, title = s(Notification.EXTRA_TITLE), text = s(Notification.EXTRA_TEXT),
            bigText = s(Notification.EXTRA_BIG_TEXT), subText = s(Notification.EXTRA_SUB_TEXT),
            conversationTitle = ms?.conversationTitle?.toString() ?: s(Notification.EXTRA_CONVERSATION_TITLE),
            messages = messages, category = n.category, hasReply = findReply(n) != null,
        )
    }
}
