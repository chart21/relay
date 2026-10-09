package dev.relay.app

import org.junit.Assert.*
import org.junit.Test

class WakeGateTest {
    private fun chunk(amp: Int) = ShortArray(1280) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }

    /** Fake models: mel passes the input's loudness through, the "wake word" scores 0.9 once anything loud was heard. */
    private class Fakes {
        val melInputs = ArrayList<Float>() // peak amplitude of every mel call
        val mel = Net { x, _ ->
            val peak = x.maxOf { kotlin.math.abs(it) }
            melInputs += peak
            FloatArray(maxOf(1, (x.size - 400) / 160 + 1) * 32) { peak / 100f }
        }
        val emb = Net { x, _ -> FloatArray(96) { x.max() } }
        val ww = Net { x, _ -> floatArrayOf(if (x.max() > 3f) 0.9f else 0.1f) }
        fun pipeline() = WakePipeline(mel, emb, ww)
    }

    @Test fun silenceRunsNoModel() {
        val f = Fakes(); val g = WakeGate(f::pipeline)
        val calls = f.melInputs.size
        repeat(200) { assertEquals(0f, g.process(chunk(0))) }
        assertFalse(g.open); assertEquals(0L, g.run); assertEquals(200L, g.skipped); assertEquals(calls, f.melInputs.size)
    }

    @Test fun preRollReachesTheModelAndCoversTheWarmUp() {
        val f = Fakes(); val g = WakeGate(f::pipeline)
        repeat(50) { g.process(chunk(0)) }
        g.process(chunk(60)) // the soft "Hey": below the gate's threshold
        val score = g.process(chunk(3000)) // "Jarvis": opens the gate
        assertTrue(g.open)
        assertTrue("the soft onset was fed through the pipeline", f.melInputs.any { it in 50f..70f })
        assertEquals(0.9f, score, 1e-6f) // detected at once: the pre-roll used up the pipeline's 5-frame warm-up
        assertEquals(21L, g.run) // 20 pre-roll chunks + the loud one
    }

    @Test fun closesAfterTwoSecondsOfQuiet() {
        val f = Fakes(); val g = WakeGate(f::pipeline)
        g.process(chunk(3000)); assertTrue(g.open)
        repeat(24) { g.process(chunk(0)) }; assertTrue(g.open)
        g.process(chunk(0)); assertFalse(g.open)
    }

    @Test fun steadyNoiseClosesTheGateAgain() {
        val f = Fakes(); val g = WakeGate(f::pipeline)
        repeat(300) { g.process(chunk(400)) } // a fan or traffic: the noise floor rises to it
        assertFalse(g.open)
        val skipped = g.skipped
        repeat(10) { g.process(chunk(400)) }
        assertEquals(skipped + 10, g.skipped)
        g.process(chunk(4000)); assertTrue(g.open) // speech over the noise still opens it
    }
}
