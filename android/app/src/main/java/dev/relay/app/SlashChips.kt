package dev.relay.app

/** Default slash-command chips per tool; stored space-separated in settings. */
object SlashChips {
    val defaults = mapOf(
        "claude" to "/compact /clear /model /usage /resume /cost",
        "codex" to "/model /status /compact /new",
        "agy" to "/model /help",
    )
    fun parse(s: String) = s.split(Regex("\\s+")).filter { it.isNotBlank() }
}
