package dev.relay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phone bridge, pure part (PROTOCOL.md "Phone bridge"): item JSON, allowlist, reply matching, request rules, queue draining. No Android deps. */

data class BMsg(val sender: String, val text: String, val tsMs: Long)

/** What the listener extracts from a StatusBarNotification. */
data class NotifData(
    val pkg: String, val label: String, val key: String, val postTime: Long, val title: String?, val text: String?,
    val bigText: String?, val subText: String?, val conversationTitle: String?, val messages: List<BMsg>, val category: String?, val hasReply: Boolean,
) {
    /** Chat or group name: the MessagingStyle conversation title, else the title of a messaging notification. */
    val conversation get() = conversationTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { messages.isNotEmpty() && it.isNotBlank() }
    /** Key for the reply-action store: the conversation, else the plain title. */
    val replyName get() = conversation ?: title.orEmpty()
}

data class HStage(val stage: String, val startMs: Long, val endMs: Long)

/** One Health Connect NutritionRecord: start time, nutrient amounts (keys like `protein_g`, `zinc_mg`), writing app. */
data class NRec(val startMs: Long, val nutrients: Map<String, Double>, val source: String?)

object Items {
    private fun JSONObject.putIf(name: String, v: String?) = apply { if (!v.isNullOrBlank()) put(name, v) }

    fun notification(d: NotifData): JSONObject = JSONObject().put("id", "${d.pkg}|${d.key}|${d.postTime}").put("ts", d.postTime / 1000.0)
        .put("app", d.pkg).put("app_label", d.label).put("title", d.title.orEmpty()).put("text", d.text.orEmpty())
        .putIf("big_text", d.bigText).putIf("sub_text", d.subText).putIf("conversation", d.conversation).putIf("category", d.category)
        .put("has_reply", d.hasReply).also { o ->
            if (d.messages.isNotEmpty()) o.put("messages", JSONArray(d.messages.sortedBy { it.tsMs }
                .map { JSONObject().put("sender", it.sender).put("text", it.text).put("ts", it.tsMs / 1000.0) }))
        }

    /** `type` is the provider's `type` column: 1 inbox, 2 sent; other boxes are not forwarded. */
    fun sms(rowId: Long, dateMs: Long, address: String?, body: String?, type: Int, name: String?): JSONObject? {
        val box = when (type) { 1 -> "inbox"; 2 -> "sent"; else -> return null }
        return JSONObject().put("id", "sms-$rowId").put("ts", dateMs / 1000.0).put("address", address.orEmpty()).putIf("name", name)
            .put("body", body.orEmpty()).put("box", box)
    }

    private fun health(type: String, startMs: Long, endMs: Long, value: Double, unit: String, source: String?) =
        JSONObject().put("id", "$type-$startMs-$endMs").put("type", type).put("start", startMs / 1000.0).put("end", endMs / 1000.0)
            .put("value", value).put("unit", unit).putIf("source", source)

    fun steps(startMs: Long, endMs: Long, count: Long, source: String?) = health("steps", startMs, endMs, count.toDouble(), "count", source)

    fun sleep(startMs: Long, endMs: Long, stages: List<HStage>, source: String?) = health("sleep", startMs, endMs, (endMs - startMs) / 60_000.0, "min", source).also { o ->
        if (stages.isNotEmpty()) o.put("stages", JSONArray(stages.map { JSONObject().put("stage", it.stage).put("start", it.startMs / 1000.0).put("end", it.endMs / 1000.0) }))
    }

    /** One record: value = average of its samples, plus min, max and the sample count; null without samples. */
    fun heartRate(startMs: Long, endMs: Long, bpm: List<Long>, source: String?): JSONObject? =
        if (bpm.isEmpty()) null else health("heart_rate", startMs, endMs, bpm.average(), "bpm", source).put("min", bpm.min()).put("max", bpm.max()).put("samples", bpm.size)

    fun weight(timeMs: Long, kg: Double, source: String?) = health("weight", timeMs, timeMs, kg, "kg", source)

    /** One local day's food totals. The id carries a hash of the totals, so a corrected day arrives as a new item
     *  (the gateway drops repeated ids); consumers keep the newest `received_at` per (type, day). */
    fun nutrition(day: String, startMs: Long, endMs: Long, totals: Map<String, Double>, entries: Int, sources: Collection<String>): JSONObject {
        val sorted = totals.mapValues { Math.round(it.value * 100) / 100.0 }.toSortedMap()
        val sig = sorted.entries.joinToString(";") { "${it.key}=${it.value}" } + ";n=$entries"
        val nutrients = JSONObject().also { o -> sorted.forEach { (k, v) -> o.put(k, v) } }
        return health("nutrition", startMs, endMs, sorted["energy_kcal"] ?: 0.0, "kcal", sources.toSortedSet().joinToString(","))
            .put("id", "nutrition-$day-${Integer.toHexString(sig.hashCode())}").put("day", day).put("entries", entries).put("nutrients", nutrients)
    }

    /** Sum the records per local day, one item for every day from [first] to [last], empty days included so a day whose
     *  entries were all deleted is updated too. */
    fun nutritionDays(recs: List<NRec>, first: LocalDate, last: LocalDate, zone: ZoneId): List<JSONObject> {
        val byDay = recs.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        return generateSequence(first) { d -> d.plusDays(1).takeIf { !it.isAfter(last) } }.map { day ->
            val dayRecs = byDay[day].orEmpty()
            val totals = HashMap<String, Double>()
            dayRecs.forEach { r -> r.nutrients.forEach { (k, v) -> totals.merge(k, v, Double::plus) } }
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            nutrition(day.toString(), start, end, totals, dayRecs.size, dayRecs.mapNotNull { it.source })
        }.toList()
    }

    /** Health Connect SleepSessionRecord.STAGE_TYPE_* */
    fun stageName(type: Int) = when (type) {
        1 -> "awake"; 2 -> "sleeping"; 3 -> "out_of_bed"; 4 -> "light"; 5 -> "deep"; 6 -> "rem"; 7 -> "awake_in_bed"; else -> "unknown"
    }
}

object Allowlist {
    val packages = setOf(
        "com.whatsapp", "ch.threema.app", "ch.threema.app.libre", "org.thoughtcrime.securesms", "org.telegram.messenger",
        "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.amazon.mShop.android.shopping", "de.dhl.paket",
        "com.google.android.calendar", "com.google.android.gm",
    )
    val keywords = listOf("Bank", "PayPal", "Revolut", "Amazon", "DHL", "Threema", "WhatsApp", "Signal", "Telegram", "Calendar", "Gmail")

    fun default(pkg: String, label: String) = pkg in packages || keywords.any { label.contains(it, ignoreCase = true) }

    /** The user's explicit choice wins over the default. */
    fun allowed(pkg: String, label: String, overrides: Map<String, Boolean>) = overrides[pkg] ?: default(pkg, label)
}

object NotifRules {
    const val FLAG_ONGOING = 0x2
    const val FLAG_GROUP_SUMMARY = 0x200

    fun skip(flags: Int, pkg: String, own: String) = pkg == own || flags and (FLAG_ONGOING or FLAG_GROUP_SUMMARY) != 0
}

/** Which stored reply action answers a request for `wanted`: exact name (ignoring case), else a unique "contains" match, else none. */
object ReplyMatch {
    fun pick(names: List<String>, wanted: String): Int? {
        val w = wanted.trim()
        if (w.isEmpty()) return null
        names.indexOfFirst { it.trim().equals(w, ignoreCase = true) }.let { if (it >= 0) return it }
        val hits = names.indices.filter { names[it].contains(w, ignoreCase = true) }
        return hits.singleOrNull()
    }
}

/** A `send_request` event. `executor` "phone" sends through a notification reply action, "desktop" only needs the approval. */
data class SendReq(
    val id: String, val kind: String, val executor: String, val app: String, val conversation: String, val text: String,
    val summary: String, val expiresAt: Double,
) {
    val byPhone get() = executor == "phone"
    fun toJson() = JSONObject().put("request_id", id).put("kind", kind).put("executor", executor).put("app", app).put("conversation", conversation)
        .put("text", text).put("summary", summary).put("expires_at", expiresAt)

    companion object {
        fun fromJson(o: JSONObject) = SendReq(o.getString("request_id"), o.str("kind") ?: "message", o.str("executor") ?: "desktop", o.str("app") ?: "",
            o.str("conversation") ?: "", o.str("text") ?: "", o.str("summary") ?: "", o.optDouble("expires_at", 0.0))
    }
}

object SendRules {
    /** Show a request only while it is unexpired, not already on screen and not already answered. */
    fun accept(nowSec: Double, r: SendReq, pending: Collection<String>, handled: Collection<String>) =
        r.expiresAt > nowSec && r.id !in pending && r.id !in handled

    fun title(r: SendReq, appLabel: String) =
        if (r.byPhone) "Send via $appLabel to ${r.conversation.ifBlank { "?" }}?" else "Approve ${r.kind}?"
    fun body(r: SendReq) = if (r.byPhone) r.text else listOf(r.summary, r.text).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
}

data class QRow(val seq: Long, val json: String)

interface QueueStore {
    suspend fun kinds(): List<String>
    suspend fun peek(kind: String, n: Int): List<QRow>
    suspend fun delete(seqs: List<Long>)
}

object Drain {
    /** Sends every queued row in batches of at most `batch`; a batch is deleted only after `put` returned, a failure keeps it. Returns the rows sent. */
    suspend fun run(q: QueueStore, batch: Int = 200, put: suspend (String, JSONArray) -> Unit): Int {
        var sent = 0
        for (kind in q.kinds()) while (true) {
            val rows = q.peek(kind, batch)
            if (rows.isEmpty()) break
            put(kind, JSONArray(rows.map { JSONObject(it.json) }))
            q.delete(rows.map { it.seq })
            sent += rows.size
        }
        return sent
    }
}
