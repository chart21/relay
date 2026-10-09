package dev.relay.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Recognition vocabulary from the Desktop: [terms] bias the recogniser, [corrections] (heard, meant) fix known mishearings. */
data class VocabData(val terms: List<String> = emptyList(), val corrections: List<Pair<String, String>> = emptyList()) {
    fun toJson() = JSONObject().put("terms", JSONArray(terms)).put("corrections", JSONArray(corrections.map { JSONArray(listOf(it.first, it.second)) }))

    companion object {
        fun fromJson(o: JSONObject?): VocabData {
            o ?: return VocabData()
            val t = o.optJSONArray("terms")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty().filter { it.isNotEmpty() }
            val c = o.optJSONArray("corrections")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) } }.orEmpty()
                .map { it.optString(0).trim() to it.optString(1).trim() }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            return VocabData(t, c)
        }
    }
}

/** Pure logic of the speech post-processing: biasing list, choosing among the recogniser's hypotheses, known-mishearing fixes. */
object SpeechVocab {
    private val splitter = Regex("[^\\p{L}\\p{N}.+#]+")

    /** Terms and the words of split titles ("work/api-merge" -> work, api, merge), short words dropped, deduplicated, capped. */
    fun biasing(terms: List<String>, cap: Int = 100): List<String> {
        val out = LinkedHashMap<String, String>()
        fun add(s: String) { val t = s.trim('.', '+', '#'); if (t.length >= 3) out.putIfAbsent(t.lowercase(), t) }
        terms.forEach { t ->
            val parts = t.split(splitter).filter { it.isNotEmpty() }
            if (parts.size == 1 || (t.length <= 24 && !t.contains('/'))) add(t.trim())
            if (parts.size > 1) parts.forEach(::add)
        }
        return out.values.take(cap)
    }

    private fun hasWord(text: String, w: String): Boolean {
        var i = text.indexOf(w, ignoreCase = true)
        while (i >= 0) {
            val before = text.getOrNull(i - 1); val after = text.getOrNull(i + w.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            i = text.indexOf(w, i + 1, ignoreCase = true)
        }
        return false
    }

    /** Confidence (rank-based when the recogniser gives none or -1) plus [bonus] per distinct vocabulary term in the hypothesis. */
    fun pick(hyps: List<String>, conf: FloatArray?, terms: Collection<String>, bonus: Float = 0.12f): String? {
        if (hyps.isEmpty()) return null
        val vocab = terms.filter { it.length >= 3 }.distinct()
        var best = 0; var bestScore = Float.NEGATIVE_INFINITY
        hyps.forEachIndexed { i, h ->
            val c = conf?.getOrNull(i)?.takeIf { it >= 0f } ?: (1f - 0.05f * i)
            val s = c + bonus * vocab.count { hasWord(h, it) }
            if (s > bestScore + 1e-6f) { best = i; bestScore = s }
        }
        return hyps[best]
    }

    /** Whole-word, case-insensitive, longest phrase first, single pass (a replacement is never corrected again). */
    fun correct(text: String, corrections: List<Pair<String, String>>): String {
        val map = corrections.associate { it.first.trim().lowercase().replace(Regex("\\s+"), " ") to it.second }.filterKeys { it.isNotEmpty() }
        if (map.isEmpty()) return text
        val alt = map.keys.sortedByDescending { it.length }.joinToString("|") { k -> k.split(' ').joinToString("\\s+") { Regex.escape(it) } }
        val re = Regex("(?<![\\p{L}\\p{N}])(?:$alt)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return re.replace(text) { m -> map[m.value.lowercase().replace(Regex("\\s+"), " ")] ?: m.value }
    }

    /** Terms used to rate hypotheses: the biasing words plus what the corrections turn into. */
    fun scoringTerms(v: VocabData) = biasing(v.terms) + v.corrections.map { it.second }
}

/** Installed offline models per language, as reported by the recogniser. */
data class ModelState(val installed: Set<String> = emptySet(), val pending: Set<String> = emptySet(), val supported: Set<String> = emptySet()) {
    private fun has(s: Set<String>, l: String) = s.any { it.startsWith(l, ignoreCase = true) }
    fun status(l: String) = when {
        has(installed, l) -> "installed"
        has(pending, l) -> "downloading …"
        has(supported, l) -> "missing"
        else -> "not offered"
    }
    fun missing(l: String) = status(l) == "missing"
    fun summary() = "Offline recognition: German ${status("de")}, English ${status("en")}"
}

/** The Desktop's vocabulary, kept in memory and cached in app storage so it works offline. */
object Vocab {
    val data = MutableStateFlow(VocabData())
    private fun file(ctx: Context) = File(ctx.filesDir, "voice_vocab.json")

    fun load(ctx: Context) { runCatching { data.value = VocabData.fromJson(JSONObject(file(ctx).readText())) } }

    suspend fun refresh() {
        val o = Relay.client.rpc("voice_vocab")
        data.value = VocabData.fromJson(o)
        runCatching { file(Relay.app).writeText(data.value.toJson().toString()) }
    }

    suspend fun add(heard: String, meant: String) {
        val p = JSONObject().put("meant", meant.trim())
        if (heard.isNotBlank()) p.put("heard", heard.trim())
        Relay.client.rpc("voice_vocab_add", p)
        refresh()
    }
}
