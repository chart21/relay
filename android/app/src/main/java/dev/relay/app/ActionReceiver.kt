package dev.relay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Relay.init(ctx)
        Relay.startConnection()
        val pending = goAsync()
        Relay.scope.launch {
            val nid = intent.getIntExtra("nid", 0)
            try {
                if (intent.action == Notifier.ACT_SEND) { // the decision is kept and sent later when the Desktop is out of reach
                    Bridge.decide(intent.getStringExtra("request_id")!!, intent.getBooleanExtra("ok", false)); Notifier.cancel(ctx, nid)
                    return@launch
                }
                val up = withTimeoutOrNull(10_000) { Relay.client.state.first { it == Conn.Connected } }
                when (intent.action) {
                    Notifier.ACT_CONFIRM -> if (up != null) { Relay.confirm(intent.getStringExtra("action_id")!!, intent.getBooleanExtra("ok", false)); Notifier.cancel(ctx, nid) }
                    Notifier.ACT_REPLY -> {
                        val key = intent.getStringExtra(Notifier.EXTRA_SESSION)!!
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                        if (text.isNullOrBlank()) Notifier.cancel(ctx, nid)
                        else runCatching { Relay.sendToSession(key, text) }
                            .onSuccess { Notifier.cancel(ctx, nid) }
                            .onFailure { Notifier.replyFailed(ctx, key, nid, it.message ?: "failed") }
                    }
                }
            } finally { pending.finish() }
        }
    }
}
