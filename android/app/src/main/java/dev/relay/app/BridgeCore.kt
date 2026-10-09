package dev.relay.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Phone bridge: queue upload, SMS/Health readers, send approvals, location on request. Features are off until switched on in Settings. */
object Bridge {
    const val NOTIFICATIONS = "notification"
    const val SMS = "sms"
    const val HEALTH = "health"
    const val LOCATION = "location"
    private const val TAG = "Bridge"
    private const val NO_REPLY = "no reply action; open the chat on the phone"
    private const val READ_EVERY_MS = 30 * 60_000L

    private lateinit var ctx: Context
    lateinit var store: BridgeStore
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sendLock = Mutex()
    private val uploadLock = Mutex()
    private var loop: Job? = null

    fun init(c: Context) {
        if (::store.isInitialized) return
        ctx = c.applicationContext
        store = BridgeStore(ctx)
    }

    fun appLabel(c: Context, pkg: String): String =
        runCatching { c.packageManager.getApplicationInfo(pkg, 0).let { c.packageManager.getApplicationLabel(it).toString() } }.getOrDefault(pkg)

    suspend fun enqueue(kind: String, items: List<JSONObject>) {
        if (items.isEmpty()) return
        store.queue.add(items.map { QItem(kind = kind, itemId = it.getString("id"), json = it.toString()) })
        wake.trySend(Unit)
    }

    // -- upload loop -------------------------------------------------------
    /** After every hello_ok: deliver pending answers, read SMS/Health, upload; then while connected upload ~20 s after new items. */
    fun onHello() {
        loop?.cancel()
        loop = Relay.scope.launch {
            var lastRead = 0L
            while (isActive && Relay.client.connected()) {
                runCatching { flushOutbox() }
                sweepExpired()
                if (System.currentTimeMillis() - lastRead >= READ_EVERY_MS) {
                    lastRead = System.currentTimeMillis(); readAll(); PhoneStats.report(ctx)
                }
                val ok = runCatching { upload() }.onFailure { Log.w(TAG, "upload failed: ${it.javaClass.simpleName}") }.isSuccess
                // battery: sleep until new items arrive or the next read is due (was: wake up every minute)
                val due = (READ_EVERY_MS - (System.currentTimeMillis() - lastRead)).coerceAtLeast(60_000)
                if (!ok) delay(60_000)
                else if (withTimeoutOrNull(due) { wake.receive() } != null) delay(20_000)
            }
        }
    }

    suspend fun upload(): Int = uploadLock.withLock {
        if (!Relay.client.connected()) return 0
        val n = Drain.run(store.queue) { kind, items -> Relay.client.rpc("inbox_put", JSONObject().put("kind", kind).put("items", items), 60_000) }
        if (n > 0) store.setLastUpload(System.currentTimeMillis())
        n
    }

    /** Right after a feature was switched on, a permission granted or "Sync now": read and upload now, not in 30 min. */
    fun syncNow() = Relay.scope.launch {
        BridgeListener.backfill()
        readAll()
        wake.trySend(Unit)
        runCatching { upload() }
    }

    private suspend fun readAll() {
        suspend fun run(name: String, f: suspend () -> Unit) = try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "$name read failed: ${e.javaClass.simpleName}"); store.status.value = "$name: ${e.message ?: e.javaClass.simpleName}"
        }
        if (store.enabled(SMS)) run("SMS") { SmsReader.read(ctx) }
        if (store.enabled(HEALTH)) run("Health Connect") { HealthReader.read(ctx) }
    }

    /** Sends answers that could not be delivered earlier; one the Desktop rejects while connected is dropped. */
    private suspend fun flushOutbox() {
        for (o in store.outbox()) {
            if (System.currentTimeMillis() - o.optLong("ts") > 24 * 3_600_000L) { store.outboxRemove(o); continue }
            try { Relay.client.rpc(o.getString("method"), o.getJSONObject("params")); store.outboxRemove(o) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { if (Relay.client.connected()) store.outboxRemove(o) else return }
        }
    }

    private suspend fun answer(method: String, params: JSONObject) {
        try { Relay.client.rpc(method, params) } catch (e: CancellationException) { throw e } catch (e: Exception) { store.outboxAdd(method, params) }
    }

    // -- events ------------------------------------------------------------
    suspend fun onEvent(ev: Ev) {
        when (ev) {
            is SendRequestEv -> sendLock.withLock {
                val r = ev.req
                if (SendRules.accept(System.currentTimeMillis() / 1000.0, r, store.sendRequests.value.map { it.id }, store.handled())) {
                    store.setSendRequests(store.sendRequests.value + r)
                    Notifier.sendRequest(ctx, r, if (r.byPhone) appLabel(ctx, r.app) else r.kind)
                }
            }
            is SendResolved -> sendLock.withLock {
                store.markHandled(ev.requestId)
                store.setSendRequests(store.sendRequests.value.filterNot { it.id == ev.requestId })
                Notifier.cancel(ctx, Notifier.sendId(ev.requestId))
            }
            is LocationRequestEv -> Relay.scope.launch { locate(ev) }
            else -> {}
        }
    }

    private suspend fun sweepExpired() = sendLock.withLock {
        val now = System.currentTimeMillis() / 1000.0
        val (live, dead) = store.sendRequests.value.partition { it.expiresAt > now }
        if (dead.isNotEmpty()) { store.setSendRequests(live); dead.forEach { Notifier.cancel(ctx, Notifier.sendId(it.id)) } }
    }

    /** The user's tap on an approval card or notification. Nothing is sent without it; the answer is kept if the Desktop is unreachable. */
    suspend fun decide(id: String, ok: Boolean) {
        val r = sendLock.withLock {
            store.sendRequests.value.find { it.id == id }?.also {
                store.setSendRequests(store.sendRequests.value - it); store.markHandled(id); Notifier.cancel(ctx, Notifier.sendId(id))
            }
        } ?: return
        if (r.expiresAt <= System.currentTimeMillis() / 1000.0) return
        val res = JSONObject().put("request_id", id)
        when {
            !ok -> res.put("ok", false).put("error", "denied")
            !r.byPhone -> res.put("ok", true)
            else -> try {
                val e = Replies.find(r.app, r.conversation) ?: throw IllegalStateException(NO_REPLY)
                Replies.send(ctx, e, r.text)
                res.put("ok", true)
            } catch (e: Exception) { res.put("ok", false).put("error", e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName) }
        }
        answer("send_result", res)
    }

    private suspend fun locate(ev: LocationRequestEv) {
        val id = ev.requestId
        if (ev.expiresAt <= System.currentTimeMillis() / 1000.0 || id in store.handled()) return
        store.markHandled(id)
        suspend fun fail(msg: String) = answer("location_result", JSONObject().put("request_id", id).put("ok", false).put("error", msg))
        if (!store.enabled(LOCATION)) return fail("location sharing is switched off on the phone")
        if (!LocationFix.hasPermission(ctx)) return fail("location permission missing on the phone")
        val (loc, provider) = try { LocationFix.get(ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { return fail(e.message ?: e.javaClass.simpleName) }
            ?: return fail("no location fix")
        val ts = loc.time / 1000.0
        enqueue("location", listOf(JSONObject().put("id", "loc-$id").put("ts", ts).put("lat", loc.latitude).put("lon", loc.longitude)
            .put("accuracy_m", loc.accuracy.toDouble()).put("provider", provider).put("request_id", id)))
        runCatching { upload() }
        answer("location_result", JSONObject().put("request_id", id).put("ok", true).put("lat", loc.latitude).put("lon", loc.longitude)
            .put("accuracy_m", loc.accuracy.toDouble()).put("ts", ts))
    }
}
