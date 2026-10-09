package dev.relay.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

enum class ConvState { Off, Listening, Thinking, Speaking }

sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Silence : Heard
    data class Failed(val msg: String) : Heard
}

/** listen -> ask -> speak -> listen, until a stop word, two silent turns in a row, a failure, or cancellation. */
class ConversationLoop(
    private val listen: suspend () -> Heard,
    private val ask: suspend (String) -> String?,
    private val speak: suspend (String) -> Unit,
    private val onState: (ConvState) -> Unit = {},
    private val maxSilent: Int = 2,
) {
    enum class End { Stopped, Silent, Failed, Cancelled }

    suspend fun run(): End {
        var silent = 0
        while (currentCoroutineContext().isActive) {
            onState(ConvState.Listening)
            when (val h = listen()) {
                Heard.Silence -> { if (++silent >= maxSilent) return End.Silent }
                is Heard.Failed -> return End.Failed
                is Heard.Text -> {
                    silent = 0
                    if (SpeechText.isStopPhrase(h.text)) return End.Stopped
                    onState(ConvState.Thinking)
                    val reply = ask(h.text) ?: return End.Failed
                    onState(ConvState.Speaking)
                    speak(reply)
                }
            }
        }
        return End.Cancelled
    }
}
