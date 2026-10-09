package dev.relay.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchTest {
    private fun m(id: String, role: String, text: String, tools: List<ToolCall> = emptyList()) = TMessage(id, role, text, tools, 1.0)

    @Test fun rangesAreCaseInsensitiveAndNonOverlapping() {
        assertEquals(listOf(0..2, 8..10), ChatSearch.ranges("Foo bar foo", "FOO"))
        assertEquals(listOf(0..1, 2..3), ChatSearch.ranges("aaaa", "aa"))
        assertEquals(listOf(4..6), ChatSearch.ranges("Der Käse", "käs"))
        assertEquals(listOf(4..6), ChatSearch.ranges("Der KÄSE", "käs"))
    }

    @Test fun shortOrBlankQueryMatchesNothing() {
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("abc", "a"))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("abc", "  "))
        assertEquals(false, ChatSearch.active(" a "))
        assertEquals(true, ChatSearch.active(" ab "))
    }

    @Test fun queryIsTrimmed() = assertEquals(listOf(4..6), ChatSearch.ranges("one two", "  two "))

    @Test fun rowIndexFindsTextAndFoldedRows() {
        val msgs = listOf(m("u1", "user", "hello"), m("a1", "assistant", "", listOf(ToolCall("Bash", "ls"))), m("t1", "tool", "out"), m("a2", "assistant", "done"))
        val rows = ChatText.rows(msgs, emptyList()) // newest first: a2, steps-a1, u1
        assertEquals(listOf("a2", "steps-a1", "u1"), rows.map { it.id })
        assertEquals(0, ChatSearch.rowIndex(rows, msgs, "a2"))
        assertEquals(2, ChatSearch.rowIndex(rows, msgs, "u1"))
        assertEquals(1, ChatSearch.rowIndex(rows, msgs, "a1"))
        assertEquals(-1, ChatSearch.rowIndex(rows, msgs, "zzz"))
    }

    @Test fun rowIndexFallsBackToANeighbour() {
        val msgs = listOf(m("u1", "user", "hello"), m("r", "user", "<system-reminder>only a reminder</system-reminder>"), m("a2", "assistant", "done"))
        val rows = ChatText.rows(msgs, emptyList())
        assertEquals(0, ChatSearch.rowIndex(rows, msgs, "r"))
    }

    @Test fun pendingRowsComeFirstAndShiftIndexes() {
        val msgs = listOf(m("u1", "user", "hello"))
        val rows = ChatText.rows(msgs, listOf(Pending("sending", 5)))
        assertEquals(1, ChatSearch.rowIndex(rows, msgs, "u1"))
    }

    @Test fun stepWrapsAround() {
        assertEquals(1, ChatSearch.step(0, 1, 3)); assertEquals(0, ChatSearch.step(2, 1, 3))
        assertEquals(2, ChatSearch.step(0, -1, 3)); assertEquals(-1, ChatSearch.step(0, 1, 0))
    }

    @Test fun loadsAFewMessagesBeforeAHit() {
        assertEquals(97, ChatSearch.fromIndex(100)); assertEquals(0, ChatSearch.fromIndex(2))
        assertEquals(true, ChatSearch.loaded(listOf(m("a", "user", "x")), "a")); assertEquals(false, ChatSearch.loaded(emptyList(), "a"))
    }
}
