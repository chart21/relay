package dev.relay.app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session as SshSession
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface Conn {
    data object Off : Conn // not configured
    data object NoNetwork : Conn // waiting for a network, no retries
    data object Connecting : Conn
    data object Connected : Conn
    data class Error(val msg: String) : Conn
}

class RpcException(msg: String) : IOException(msg)

/**
 * SSH link to the Desktop: one `serve` exec channel (JSON lines, hello/replay, events, rpc). Reconnects with a modest backoff only while a network exists;
 * offline it waits for the ConnectivityManager callback.
 */
private const val TAG = "Relay"

class GatewayClient(
    private val identity: Identity,
    private val network: StateFlow<Any?>,
    private val onNewFingerprint: suspend (String) -> Unit,
) {
    val incoming = MutableSharedFlow<Ev>(extraBufferCapacity = 1024)
    val state = MutableStateFlow<Conn>(Conn.Off)
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResult>>()
    private val progress = ConcurrentHashMap<String, (String) -> Unit>()
    @Volatile private var out: Channel<String>? = null
    @Volatile private var ssh: SSHClient? = null

    /** Drop the current connection (if any) and connect again right away. */
    fun reconnect() { kick.tryEmit(Unit) }

    fun connected() = state.value == Conn.Connected

    suspend fun run(prefs: Flow<Prefs>, sinceSeq: () -> Long) {
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val p = prefs.first()
            if (p.host.isBlank() || p.user.isBlank()) {
                state.value = Conn.Off
                prefs.first { it.host.isNotBlank() && it.user.isNotBlank() }
                continue
            }
            val net = network.value
            if (net == null) { // flight mode / no coverage: zero retries until the OS reports a network
                state.value = Conn.NoNetwork
                network.first { it != null }
                backoff = 2_000L
                continue
            }
            state.value = Conn.Connecting
            Log.i(TAG, "connecting to ${p.user}@${p.host}:${p.port}")
            val started = System.currentTimeMillis()
            var up = false
            try {
                coroutineScope {
                    val current: suspend () -> Boolean = { prefs.first().let { it.host == p.host && it.port == p.port && it.user == p.user } }
                    val job = launch(Dispatchers.IO) { session(p, sinceSeq(), current) { up = true } }
                    val watcher = launch {
                        val why = merge(
                            prefs.filter { it.host != p.host || it.port != p.port || it.user != p.user }.map { "settings changed" },
                            network.filter { it != net }.map { "network changed" }, kick.map { "reconnect requested" },
                        ).first()
                        Log.i(TAG, "dropping connection: $why")
                        job.cancel()
                    }
                    job.join()
                    watcher.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connection to ${p.host}:${p.port} failed: $e")
                state.value = Conn.Error(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            out = null; ssh = null
            val gone = RpcException("Connection lost")
            pending.values.forEach { it.completeExceptionally(gone) }
            pending.clear()
            if (state.value == Conn.Connected || state.value == Conn.Connecting) state.value = Conn.Error("Disconnected")
            if (up && System.currentTimeMillis() - started > 20_000) backoff = 2_000L
            if (network.value == null) continue
            // modest backoff, only while a network exists; a network switch, settings change or manual retry cuts it short
            withTimeoutOrNull(backoff) {
                merge(network.filter { it != net }.map { }, kick, prefs.filter { it.host != p.host || it.port != p.port || it.user != p.user }.map { }).first()
            }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun session(p: Prefs, since: Long, current: suspend () -> Boolean, onUp: () -> Unit): Unit = coroutineScope {
        var pinned = p.hostFingerprint
        val client = SSHClient()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(h: String, port: Int, key: PublicKey): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                if (pinned.isEmpty()) { pinned = fp; runBlocking { onNewFingerprint(fp) }; return true } // TOFU
                return pinned == fp
            }
            override fun findExistingAlgorithms(h: String, port: Int) = emptyList<String>()
        })
        client.connectTimeout = 10_000
        try {
            client.connect(p.host, p.port)
            client.connection.keepAlive.keepAliveInterval = 60
            client.authPublickey(p.user, identity.keyProvider)
            val cmd = client.startSession().exec("serve")
            val ch = Channel<String>(Channel.UNLIMITED)
            val writer = cmd.outputStream
            val reader = BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8))
            val errText = StringBuilder()
            val errJob = launch(Dispatchers.IO) {
                runCatching { InputStreamReader(cmd.errorStream, Charsets.UTF_8).use { r ->
                    val b = CharArray(512)
                    while (true) { val n = runInterruptible { r.read(b) }; if (n < 0) break; if (errText.length < 1000) errText.append(b, 0, n) }
                } }
            }
            val w = launch(Dispatchers.IO) {
                for (line in ch) { writer.write((line + "\n").toByteArray(Charsets.UTF_8)); writer.flush() }
            }
            ssh = client
            out = ch
            incoming.emit(ConnOpened)
            ch.trySend(Protocol.hello(since).toString())
            try {
                while (true) {
                    val line = readOrCancel { reader.readLine() } ?: break
                    val ev = Protocol.parse(line) ?: continue
                    when (ev) {
                        is RpcResult -> pending.remove(ev.id)?.complete(ev)
                        is RpcProgress -> progress[ev.id]?.invoke(ev.delta)
                        is HelloOk -> {
                            // provisioning or the settings screen may have switched hosts while this connect was in flight
                            if (!current()) throw IOException("settings changed while connecting")
                            Log.i(TAG, "connected to ${p.host}:${p.port}")
                            state.value = Conn.Connected; onUp(); incoming.emit(ev)
                        }
                        else -> incoming.emit(ev)
                    }
                }
            } finally { w.cancel(); errJob.cancel(); ch.close() }
            delay(50)
            throw IOException(errText.toString().trim().ifBlank { "gateway closed the connection" }.take(160))
        } finally {
            withContext(NonCancellable) { runCatching { client.disconnect() } }
        }
    }

    /** Fire-and-forget line (kept for compatibility); false when not connected. */
    fun send(o: JSONObject): Boolean = connected() && out?.trySend(o.toString())?.isSuccess == true

    /**
     * Calls an RPC; replies may come back out of order. Waits while connecting, fails fast when offline.
     * [onProgress] gets `rpc_progress` deltas (streamed `ask` text) on the reader coroutine: it must not block.
     */
    suspend fun rpc(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000, onProgress: ((String) -> Unit)? = null): JSONObject {
        val r = withTimeoutOrNull(timeoutMs) {
            if (state.value == Conn.Connecting) state.first { it != Conn.Connecting }
            if (!connected()) throw RpcException(when (val s = state.value) {
                Conn.Off -> "Not set up"; Conn.NoNetwork -> "No network"; is Conn.Error -> "Offline: ${s.msg}"; else -> "Not connected" })
            val id = UUID.randomUUID().toString().take(12)
            val d = CompletableDeferred<RpcResult>()
            pending[id] = d
            onProgress?.let { progress[id] = it }
            try {
                if (out?.trySend(Protocol.rpc(id, method, params).toString())?.isSuccess != true) throw RpcException("Not connected")
                d.await()
            } finally { pending.remove(id); progress.remove(id) }
        } ?: throw RpcException("$method timed out")
        if (!r.ok) throw RpcException(r.error ?: "$method failed")
        return r.result ?: JSONObject()
    }

    /** Streams a gzip command's output (the app build: `apk`) into [dest]; returns the uncompressed size. */
    suspend fun download(command: String, dest: java.io.File, onProgress: (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val c = ssh?.takeIf { connected() } ?: throw RpcException("Not connected")
        val sess = c.startSession()
        try {
            val cmd = sess.exec(command)
            var total = 0L
            var shown = 0L
            java.util.zip.GZIPInputStream(cmd.inputStream, 1 shl 16).use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = readOrCancel { inp.read(buf) }
                        if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (total - shown > (1 shl 20)) { shown = total; onProgress(total) }
                    }
                }
            }
            total
        } finally { runCatching { sess.close() } }
    }
}

/**
 * Blocking read that cancellation can interrupt. sshj reports the interrupt as InterruptedIOException, which
 * runInterruptible does not translate: turn it into the cancellation it is (an uncaught one crashes the app).
 */
private suspend fun <T> readOrCancel(block: () -> T): T =
    try { runInterruptible { block() } } catch (e: java.io.InterruptedIOException) {
        currentCoroutineContext().ensureActive(); throw e
    }
