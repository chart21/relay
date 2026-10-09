package dev.relay.app

import org.junit.Assert.*
import org.junit.Test

class VoicesTest {
    private fun v(name: String, lang: String, country: String, q: Int, net: Boolean = false, installed: Boolean = true, latency: Int = 200) =
        VoiceInfo(name, lang, country, q, latency, net, installed)

    private val all = listOf(
        v("de-de-x-nfh-network", "de", "DE", 500, net = true), // would send the text to the engine's servers
        v("de-de-x-deb-local", "de", "DE", 400),
        v("de-de-x-deg-local", "de", "DE", 500, installed = false),
        v("de-de-x-dea-local", "de", "DE", 500, latency = 300),
        v("de-at-x-ata-local", "de", "AT", 500),
        v("en-in-x-ena-local", "en", "IN", 500),
        v("en-us-x-sfg-local", "en", "US", 400),
    )

    @Test fun picksTheBestInstalledOfflineHomeVoice() {
        assertEquals("de-de-x-dea-local", Voices.pick(all, "de")?.name)
        assertEquals("en-us-x-sfg-local", Voices.pick(all, "en")?.name)
        assertEquals(listOf("dea", "deb"), Voices.usable(all, "de").map { it.code })
    }

    @Test fun keepsTheUsersChoiceOnlyWhileUsable() {
        assertEquals("de-de-x-deb-local", Voices.pick(all, "de", "de-de-x-deb-local")?.name)
        assertEquals("de-de-x-dea-local", Voices.pick(all, "de", "de-de-x-nfh-network")?.name)
        assertNull(Voices.pick(emptyList(), "de"))
    }

    @Test fun detectsTheLanguagePerSentence() {
        assertEquals("de", Voices.detect("Die Sitzung auf c2 ist fertig und wartet auf dich."))
        assertEquals("en", Voices.detect("The session on c2 is done and waiting for you."))
        assertEquals("de", Voices.detect("Schöne Grüße"))
        assertNull(Voices.detect("Okay."))
        assertEquals("en", Voices.langFor("Okay.", "auto", "en"))
        assertEquals("de", Voices.langFor("The build is done.", "de", "en"))
    }

    @Test fun pickerListShowsTopSixPlusTheChosenOne() {
        val l = (1..9).map { v("de-de-x-v$it-local", "de", "DE", 500 - it) }
        assertEquals(l.take(6), Voices.shown(l, "", false))
        assertEquals(l.take(6) + l[7], Voices.shown(l, l[7].name, false))
        assertEquals(l.take(6), Voices.shown(l, l[2].name, false))
        assertEquals(l, Voices.shown(l, "", true))
        assertEquals(l.take(3), Voices.shown(l.take(3), "", false))
        assertEquals("v1 · DE · high", Voices.label(l[0]))
    }
}
