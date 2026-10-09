package dev.relay.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** SMS inbox and sent box, incrementally by the provider's _id. First run: the last 30 days. */
object SmsReader {
    private const val CURSOR = "sms_cursor"
    private val URI: Uri = Uri.parse("content://sms")

    suspend fun read(ctx: Context): List<JSONObject> = withContext(Dispatchers.IO) {
        if (!ctx.granted(Manifest.permission.READ_SMS)) return@withContext emptyList()
        val store = Bridge.store
        var cursor = store.getLong(CURSOR, -1)
        val first = cursor < 0
        val names = HashMap<String, String?>()
        val out = ArrayList<JSONObject>()
        while (true) {
            val where = if (first) "_id > ? and date >= ?" else "_id > ?"
            val whereArgs = if (first) arrayOf(maxOf(cursor, 0).toString(), (System.currentTimeMillis() - 30 * 86_400_000L).toString()) else arrayOf(cursor.toString())
            var n = 0
            ctx.contentResolver.query(URI, arrayOf("_id", "date", "address", "body", "type"), where, whereArgs, "_id ASC LIMIT 500")?.use { c ->
                while (c.moveToNext()) {
                    n++
                    val id = c.getLong(0)
                    cursor = maxOf(cursor, id)
                    val address = c.getString(2)
                    Items.sms(id, c.getLong(1), address, c.getString(3), c.getInt(4), address?.let { names.getOrPut(it) { contactName(ctx, it) } })?.let(out::add)
                }
            }
            if (n < 500) break
        }
        Bridge.enqueue("sms", out)
        if (cursor < 0) cursor = newestId(ctx) // nothing in the first window: start after the existing history
        store.putLong(CURSOR, cursor)
        out
    }

    private fun newestId(ctx: Context): Long =
        ctx.contentResolver.query(URI, arrayOf("_id"), null, null, "_id DESC LIMIT 1")?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    /** Only when READ_CONTACTS happens to be granted (it is not requested). */
    private fun contactName(ctx: Context, address: String): String? {
        if (!ctx.granted(Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}

/** Steps, sleep, heart rate and weight from Health Connect, incrementally per type by time cursor (first run: the last
 *  7 days); nutrition as daily totals of the last 7 days, re-totalled on every read (late entries, corrections). */
object HealthReader {
    val types = listOf(StepsRecord::class, SleepSessionRecord::class, HeartRateRecord::class, WeightRecord::class, NutritionRecord::class)
    private const val NUTRITION_DAYS = 7L
    val readPermissions = types.map { HealthPermission.getReadPermission(it) }.toSet()
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    private const val OVERLAP_MS = 3_600_000L // records sync late; ids make resends harmless

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    fun client(ctx: Context): HealthConnectClient? = if (available(ctx)) runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull() else null

    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun bgAvailable(ctx: Context) = runCatching {
        client(ctx)?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrNull() == true

    suspend fun grantedPermissions(ctx: Context): Set<String> = runCatching { client(ctx)?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()

    suspend fun read(ctx: Context): List<JSONObject> {
        val hc = client(ctx) ?: return emptyList()
        val granted = grantedPermissions(ctx)
        val store = Bridge.store
        val now = System.currentTimeMillis()
        val out = ArrayList<JSONObject>()
        suspend fun <T : Record> pull(type: kotlin.reflect.KClass<T>, name: String, end: (T) -> Long, items: (T) -> JSONObject?) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            val since = store.getLong("health_$name", now - 7 * 86_400_000L) - OVERLAP_MS
            var latest = 0L
            var page: String? = null
            do {
                val r = hc.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(Instant.ofEpochMilli(since), Instant.ofEpochMilli(now)), pageSize = 1000, pageToken = page))
                r.records.forEach { rec -> items(rec)?.let(out::add); latest = maxOf(latest, end(rec)) }
                page = r.pageToken
            } while (page != null)
            if (latest > 0) store.putLong("health_$name", latest) else if (store.getLong("health_$name", -1) < 0) store.putLong("health_$name", now)
        }
        pull(StepsRecord::class, "steps", { it.endTime.toEpochMilli() }) { Items.steps(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count, it.metadata.dataOrigin.packageName) }
        pull(SleepSessionRecord::class, "sleep", { it.endTime.toEpochMilli() }) { r ->
            Items.sleep(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.stages.map { HStage(Items.stageName(it.stage), it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }, r.metadata.dataOrigin.packageName)
        }
        pull(HeartRateRecord::class, "heart_rate", { it.endTime.toEpochMilli() }) { r ->
            Items.heartRate(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.samples.map { it.beatsPerMinute }, r.metadata.dataOrigin.packageName)
        }
        pull(WeightRecord::class, "weight", { it.time.toEpochMilli() }) { Items.weight(it.time.toEpochMilli(), it.weight.inKilograms, it.metadata.dataOrigin.packageName) }
        if (HealthPermission.getReadPermission(NutritionRecord::class) in granted) {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val first = today.minusDays(NUTRITION_DAYS - 1)
            val recs = ArrayList<NRec>()
            var page: String? = null
            do {
                val r = hc.readRecords(ReadRecordsRequest(NutritionRecord::class, TimeRangeFilter.between(first.atStartOfDay(zone).toInstant(), Instant.ofEpochMilli(now)), pageSize = 1000, pageToken = page))
                r.records.forEach { recs += nrec(it) }
                page = r.pageToken
            } while (page != null)
            out += Items.nutritionDays(recs, first, today, zone)
        }
        Bridge.enqueue("health", out)
        return out
    }

    private fun nrec(r: NutritionRecord): NRec {
        val m = LinkedHashMap<String, Double>()
        fun g(k: String, v: Mass?) { if (v != null) m[k] = v.inGrams }
        fun mg(k: String, v: Mass?) { if (v != null) m[k] = v.inMilligrams }
        fun mcg(k: String, v: Mass?) { if (v != null) m[k] = v.inMicrograms }
        r.energy?.let { m["energy_kcal"] = it.inKilocalories }
        g("protein_g", r.protein); g("fat_g", r.totalFat); g("saturated_fat_g", r.saturatedFat); g("carbs_g", r.totalCarbohydrate)
        g("fiber_g", r.dietaryFiber); g("sugar_g", r.sugar)
        mg("sodium_mg", r.sodium); mg("potassium_mg", r.potassium); mg("calcium_mg", r.calcium); mg("iron_mg", r.iron)
        mg("magnesium_mg", r.magnesium); mg("zinc_mg", r.zinc); mg("vitamin_c_mg", r.vitaminC); mg("cholesterol_mg", r.cholesterol)
        mg("caffeine_mg", r.caffeine)
        mcg("vitamin_a_mcg", r.vitaminA); mcg("vitamin_d_mcg", r.vitaminD); mcg("vitamin_b12_mcg", r.vitaminB12); mcg("folate_mcg", r.folate)
        mcg("selenium_mcg", r.selenium); mcg("iodine_mcg", r.iodine)
        return NRec(r.startTime.toEpochMilli(), m, r.metadata.dataOrigin.packageName)
    }
}

/** One location fix on request: fused if available, else GPS, else network. Never continuous. */
object LocationFix {
    fun hasPermission(ctx: Context) = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) || ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    suspend fun get(ctx: Context): Pair<Location, String>? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val fine = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = buildList {
            add(LocationManager.FUSED_PROVIDER to 20_000L)
            if (fine) add(LocationManager.GPS_PROVIDER to 30_000L)
            add(LocationManager.NETWORK_PROVIDER to 10_000L)
        }.filter { (p, _) -> runCatching { p in lm.getProviders(true) }.getOrDefault(false) }
        for ((p, ms) in providers) {
            val loc = withTimeoutOrNull(ms) {
                suspendCancellableCoroutine<Location?> { c ->
                    val sig = CancellationSignal()
                    c.invokeOnCancellation { sig.cancel() }
                    runCatching { lm.getCurrentLocation(p, sig, Dispatchers.Default.asExecutor()) { c.resume(it) } }.onFailure { if (c.isActive) c.resume(null) }
                }
            }
            if (loc != null) return loc to p
        }
        return null
    }
}
