package dev.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private val laptop = Prefs(host = "laptop.example", port = 22, user = "dev", hostFingerprint = "aa:bb")

    @Test fun newHostDropsPinnedKey() =
        assertEquals("", Settings.repin(laptop, laptop.copy(host = "desktop.example", port = 2222)).hostFingerprint)

    @Test fun sameHostKeepsPinnedKey() =
        assertEquals("aa:bb", Settings.repin(laptop, laptop.copy(user = "other")).hostFingerprint)

    @Test fun explicitNewPinIsKept() =
        assertEquals("cc:dd", Settings.repin(laptop, laptop.copy(host = "x", hostFingerprint = "cc:dd")).hostFingerprint)
}
