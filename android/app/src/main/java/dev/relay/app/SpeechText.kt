package dev.relay.app

/** Text preparation for TTS and the conversation-mode stop words. Pure Kotlin. */
object SpeechText {
    private val code = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val marks = Regex("""[#*`>_]+|\[([^\]]*)]\([^)]*\)""")
    private val ws = Regex("\\s+")
    private val sentenceEnd = Regex("(?<=[.!?:;])\\s+|\\n+")

    /** Port of the gateway's monitor.last_assistant_text cleaning: drop fenced code, strip markdown marks, keep link text. */
    fun strip(text: String): String {
        val noCode = code.replace(text, " ")
        val plain = marks.replace(noCode) { it.groupValues.getOrNull(1).orEmpty() }
        return ws.replace(plain, " ").trim()
    }

    /** Splits into pieces of at most [max] chars, preferring sentence boundaries, then spaces, then hard cuts. */
    fun chunks(text: String, max: Int): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        require(max > 0)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur.setLength(0) }
        for (sentence in t.split(sentenceEnd).filter { it.isNotBlank() }) {
            val s = sentence.trim()
            if (s.length > max) {
                flush()
                var rest = s
                while (rest.length > max) {
                    val cut = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                cur.append(rest)
            } else if (cur.length + s.length + 1 > max) { flush(); cur.append(s) }
            else { if (cur.isNotEmpty()) cur.append(' '); cur.append(s) }
        }
        flush()
        return out
    }

    /** The first sentences of [text] that fit into [max] chars (spoken fallback when no summary is available). */
    fun brief(text: String, max: Int = 220): String {
        val t = strip(text)
        val out = StringBuilder()
        for (s in t.split(Regex("(?<=[.!?])\\s+"))) {
            if (out.isNotEmpty() && out.length + s.length + 1 > max) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
            if (out.length >= max) return out.substring(0, max).substringBeforeLast(' ') + " …"
        }
        return out.toString()
    }

    private val stopWords = setOf("stop", "stopp", "ende", "beenden", "danke", "dankeschön", "tschüss", "tschüs", "thanks", "bye")

    /** "stop" / "danke" / "tschüss" (optionally with a word or two around it), not a long sentence that merely contains it. */
    fun isStopPhrase(heard: String): Boolean {
        val words = heard.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        return words.isNotEmpty() && words.size <= 3 && words.any { it in stopWords }
    }
}

/**
 * Collects streamed reply text and hands out whole sentences as soon as they are complete, so speaking starts while
 * the reply is still being written. A sentence ends at a newline, or at . ! ? followed by whitespace once it has at
 * least [minLen] chars ("am 4. Oktober": no break after a digit). Pure Kotlin.
 */
class Sentences(private val minLen: Int = 12, private val emit: (String) -> Unit) {
    private val buf = StringBuilder()
    var emitted = 0
        private set

    fun add(delta: String) { buf.append(delta); flush() }
    fun end() { flush(); out(buf.toString()); buf.setLength(0) }

    private fun flush() {
        while (true) {
            val i = boundary() ?: return
            out(buf.substring(0, i)); buf.delete(0, i)
        }
    }

    private fun out(s: String) { val t = s.trim(); if (t.isNotEmpty()) { emitted++; emit(t) } }

    private fun boundary(): Int? {
        for (i in buf.indices) {
            val c = buf[i]
            if (c == '\n') return i + 1
            if (c in ".!?" && i + 1 < buf.length && buf[i + 1].isWhitespace() && i + 1 >= minLen && !(i > 0 && buf[i - 1].isDigit())) return i + 1
        }
        return null
    }
}
