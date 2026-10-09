package dev.relay.app

import org.junit.Assert.*
import org.junit.Test

class SpeechVocabTest {
    private val fixes = listOf("cloud code" to "Claude Code", "zen pass" to "Zenpass", "zen pas" to "Zenpass", "zen paz" to "Zenpass", "study perks" to "Studyperks", "studyperx" to "Studyperks",
        "fernwood" to "Fernwald")

    @Test fun fixesRealMishearings() {
        assertEquals("Open a Claude Code session", SpeechVocab.correct("Open a cloud code session", fixes))
        assertEquals("It's Zenpass classes are tomorrow", SpeechVocab.correct("It's zen pas classes are tomorrow", fixes))
        assertEquals("Search for Zenpass sessions", SpeechVocab.correct("Search for zen paz sessions", fixes))
        assertEquals("Check Zenpass", SpeechVocab.correct("Check ZEN  PASS", fixes))
        assertEquals("Studyperks and Studyperks", SpeechVocab.correct("study perks and studyperx", fixes))
        assertEquals("Asap Studyperks 1:00 p.m. At Fernwald on", SpeechVocab.correct("Asap studyperx 1:00 p.m. At Fernwood on", fixes))
    }

    @Test fun onlyWholeWordsAndNoDoubleCorrection() {
        assertEquals("the studyperxx and cloud codes", SpeechVocab.correct("the studyperxx and cloud codes", fixes))
        assertEquals("Claude Code", SpeechVocab.correct("Claude Code", listOf("claude code" to "Claude Code")))
        assertEquals("b", SpeechVocab.correct("a", listOf("a" to "b", "b" to "c")))
    }

    @Test fun longestPhraseWins() {
        val c = listOf("zen" to "Zen", "zen pass classes" to "Zenpass classes")
        assertEquals("Zenpass classes today", SpeechVocab.correct("zen pass classes today", c))
    }

    @Test fun noCorrectionsKeepsText() = assertEquals("hello", SpeechVocab.correct("hello", emptyList()))

    @Test fun picksByConfidenceWithVocabularyBonus() {
        val terms = listOf("Zenpass", "Claude Code")
        assertEquals("a", SpeechVocab.pick(listOf("a", "b"), floatArrayOf(0.9f, 0.5f), terms))
        assertEquals("show Zenpass classes", SpeechVocab.pick(listOf("show zen pas classes", "show Zenpass classes"), floatArrayOf(0.80f, 0.74f), terms))
        assertEquals("high", SpeechVocab.pick(listOf("high", "Zenpass low"), floatArrayOf(0.95f, 0.5f), terms))
    }

    @Test fun missingConfidenceFallsBackToRank() {
        assertEquals("first", SpeechVocab.pick(listOf("first", "second"), null, listOf("zzz")))
        assertEquals("first", SpeechVocab.pick(listOf("first", "second"), floatArrayOf(-1f, -1f), emptyList()))
        assertEquals("second Zenpass", SpeechVocab.pick(listOf("first", "second Zenpass"), floatArrayOf(), listOf("Zenpass")))
        assertNull(SpeechVocab.pick(emptyList(), null, emptyList()))
    }

    @Test fun bonusNeedsWholeWord() = assertEquals("one", SpeechVocab.pick(listOf("one", "Zenpasses two"), floatArrayOf(0.8f, 0.78f), listOf("Zenpass")))

    @Test fun biasingSplitsTitlesDedupsAndCaps() {
        val b = SpeechVocab.biasing(listOf("Zenpass", "work/api-merge", "zenpass", "go", "Studyperks"))
        assertEquals(listOf("Zenpass", "work", "api", "merge", "Studyperks"), b)
        assertEquals(100, SpeechVocab.biasing((1..300).map { "term$it" }).size)
    }

    @Test fun vocabJsonRoundTrip() {
        val v = VocabData(listOf("Zenpass"), listOf("zen pass" to "Zenpass"))
        assertEquals(v, VocabData.fromJson(org.json.JSONObject(v.toJson().toString())))
        assertEquals(VocabData(), VocabData.fromJson(null))
    }

    @Test fun modelSummary() {
        val s = ModelState(installed = setOf("de-DE"), pending = setOf("en-US"), supported = setOf("de-DE", "en-US"))
        assertEquals("Offline recognition: German installed, English downloading …", s.summary())
        assertTrue(ModelState(supported = setOf("en-US")).missing("en"))
        assertEquals("not offered", ModelState().status("de"))
    }
}
