package dev.relay.app

/** One TTS voice as plain data (from android.speech.tts.Voice), so the choice is unit-testable. */
data class VoiceInfo(val name: String, val lang: String, val country: String, val quality: Int, val latency: Int,
                     val network: Boolean, val installed: Boolean) {
    /** Google's names look like "de-de-x-deb-local": the short code ("deb") tells the voices apart. */
    val code: String get() = name.substringAfter("-x-", "").substringBefore('-').ifBlank { name }
    val qualityLabel: String get() = when {
        quality >= 500 -> "very high"; quality >= 400 -> "high"; quality >= 300 -> "normal"; else -> "low"
    }
}

/** Voice choice and per-sentence language for speaking. Pure Kotlin. */
object Voices {
    private val home = mapOf("de" to setOf("DE"), "en" to setOf("US", "GB"))

    /**
     * Voices offered for [lang]: installed, offline (a network voice would send the text, which may hold health or
     * message data, to the engine's servers), in the language's main countries; best first.
     */
    fun usable(voices: List<VoiceInfo>, lang: String): List<VoiceInfo> {
        val countries = home[lang].orEmpty()
        return voices.filter { it.lang == lang && !it.network && it.installed && (countries.isEmpty() || it.country in countries) }
            .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { it.latency }
                .thenBy { countries.indexOf(it.country).let { i -> if (i < 0) 99 else i } }.thenBy { it.name })
    }

    /** The user's [chosen] voice if it is still usable, else the best usable one (null: let the engine decide). */
    fun pick(voices: List<VoiceInfo>, lang: String, chosen: String = ""): VoiceInfo? {
        val u = usable(voices, lang)
        return u.firstOrNull { it.name == chosen } ?: u.firstOrNull()
    }

    /** Entry text in the picker: "deb · DE · high". */
    fun label(v: VoiceInfo) = "${v.code} · ${v.country.ifBlank { v.lang.uppercase() }} · ${v.qualityLabel}"

    /** The first [max] voices, plus the chosen one if it sits further down; everything when [all]. */
    fun shown(list: List<VoiceInfo>, chosen: String, all: Boolean, max: Int = 6): List<VoiceInfo> {
        if (all || list.size <= max) return list
        val top = list.take(max)
        return if (chosen.isEmpty() || top.any { it.name == chosen }) top else top + listOfNotNull(list.firstOrNull { it.name == chosen })
    }

    private val de = setOf("der", "die", "das", "und", "ist", "nicht", "ich", "du", "ein", "eine", "mit", "für", "auf",
        "noch", "heute", "sind", "hat", "dir", "dein", "deine", "zu", "von", "es", "wir", "sie", "auch", "wie", "was",
        "wartet", "fertig", "gerade", "sitzung")
    private val en = setOf("the", "and", "is", "not", "you", "your", "a", "with", "for", "on", "still", "today", "are",
        "has", "to", "of", "it", "we", "they", "was", "this", "that", "what", "how", "done", "waiting", "session")

    /** "de" or "en" for one sentence by common words (umlauts count double), null when it can't tell. */
    fun detect(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        val d = words.count { it in de } + 2 * Regex("[äöüß]").findAll(text.lowercase()).count()
        val e = words.count { it in en }
        return when { d > e -> "de"; e > d -> "en"; else -> null }
    }

    /** Language to speak [sentence] in: a fixed setting wins; "auto" detects, falling back to [fallback]. */
    fun langFor(sentence: String, setting: String, fallback: String): String =
        if (setting == "de" || setting == "en") setting else detect(sentence) ?: fallback
}
