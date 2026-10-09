package dev.relay.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Upload queue (survives process death); one row per item, unique per (kind, item id). */
@Entity(indices = [Index(value = ["kind", "itemId"], unique = true)])
data class QItem(@PrimaryKey(autoGenerate = true) val seq: Long = 0, val kind: String, val itemId: String, val json: String)

@Dao
interface QueueDao : QueueStore {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(items: List<QItem>)
    @Query("select distinct kind from QItem") override suspend fun kinds(): List<String>
    @Query("select seq, json from QItem where kind=:kind order by seq limit :n") override suspend fun peek(kind: String, n: Int): List<QRow>
    @Query("delete from QItem where seq in (:seqs)") override suspend fun delete(seqs: List<Long>)
    @Query("select count(*) from QItem") fun size(): Flow<Int>
}

@Database(entities = [QItem::class], version = 1, exportSchema = false)
abstract class BridgeDb : RoomDatabase() { abstract fun queue(): QueueDao }

/** Settings and cursors of the phone bridge (everything off until the user switches a feature on). */
class BridgeStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    val db = Room.databaseBuilder(ctx, BridgeDb::class.java, "bridge.db").build()
    val queue get() = db.queue()

    val features = MutableStateFlow(sp.getStringSet("features", emptySet()).orEmpty().toSet())
    val overrides = MutableStateFlow(JSONObject(sp.getString("allow", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } })
    /** package -> (label, last seen ms) of apps that posted notifications recently */
    val known = MutableStateFlow(JSONObject(sp.getString("known", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it).let { k -> k.getString("label") to k.getLong("ts") } } })
    val lastUpload = MutableStateFlow(sp.getLong("last_upload", 0))
    val sendRequests = MutableStateFlow(JSONArray(sp.getString("send_requests", "[]")!!).objects().map(SendReq::fromJson))
    val status = MutableStateFlow("")

    fun enabled(f: String) = f in features.value
    fun setFeature(f: String, on: Boolean) {
        features.update { if (on) it + f else it - f }
        sp.edit().putStringSet("features", features.value).apply()
    }

    fun setAllowed(pkg: String, on: Boolean) {
        overrides.update { it + (pkg to on) }
        sp.edit().putString("allow", JSONObject(overrides.value as Map<*, *>).toString()).apply()
    }

    @Synchronized fun seen(pkg: String, label: String, nowMs: Long) {
        val old = known.value[pkg]
        if (old != null && old.first == label && nowMs - old.second < 60_000) return
        known.update { m -> (m + (pkg to (label to nowMs))).entries.sortedByDescending { it.value.second }.take(200).associate { it.key to it.value } }
        sp.edit().putString("known", JSONObject().also { o -> known.value.forEach { (k, v) -> o.put(k, JSONObject().put("label", v.first).put("ts", v.second)) } }.toString()).apply()
    }

    fun setLastUpload(ms: Long) { lastUpload.value = ms; sp.edit().putLong("last_upload", ms).apply() }

    fun getLong(k: String, d: Long = 0) = sp.getLong(k, d)
    fun putLong(k: String, v: Long) { sp.edit().putLong(k, v).apply() }

    @Synchronized fun setSendRequests(l: List<SendReq>) {
        sendRequests.value = l
        sp.edit().putString("send_requests", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    /** Ids of requests already decided or resolved (newest last), so replayed events do not bring them back. */
    @Synchronized fun handled(): List<String> = JSONArray(sp.getString("handled", "[]")!!).let { a -> (0 until a.length()).map { a.getString(it) } }
    @Synchronized fun markHandled(id: String) {
        sp.edit().putString("handled", JSONArray((handled() + id).distinct().takeLast(200)).toString()).apply()
    }

    /** Answers (send_result, location_result) that could not be delivered yet: sent after the next hello_ok. */
    @Synchronized fun outbox(): List<JSONObject> = JSONArray(sp.getString("outbox", "[]")!!).objects()
    @Synchronized fun outboxAdd(method: String, params: JSONObject) {
        val l = outbox() + JSONObject().put("method", method).put("params", params).put("ts", System.currentTimeMillis())
        sp.edit().putString("outbox", JSONArray(l.takeLast(100)).toString()).apply()
    }
    @Synchronized fun outboxRemove(o: JSONObject) {
        sp.edit().putString("outbox", JSONArray(outbox().filterNot { it.toString() == o.toString() }).toString()).apply()
    }
}
