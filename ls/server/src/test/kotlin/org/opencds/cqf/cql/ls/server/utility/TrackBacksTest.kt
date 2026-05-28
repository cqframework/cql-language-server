package org.opencds.cqf.cql.ls.server.utility

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackBacksTest {
    @Test
    fun toRange_fullLocator_returnsCorrectRange() {
        val range = TrackBacks.toRange("5:3-10:20")!!
        assertEquals(4, range.start.line, "start line: 5→0-indexed 4")
        assertEquals(2, range.start.character, "start char: 3→max(3-1,0)=2")
        assertEquals(9, range.end.line, "end line: 10→0-indexed 9")
        assertEquals(20, range.end.character, "end char: 20 (exclusive, no -1)")
    }

    @Test
    fun toRange_singlePosition_returnsZeroLengthRange() {
        val range = TrackBacks.toRange("5:3")!!
        assertEquals(4, range.start.line)
        assertEquals(2, range.start.character)
        assertEquals(4, range.end.line, "single-position: end line = start line")
        assertEquals(3, range.end.character, "single-position: end char = start char (exclusive)")
    }

    @Test
    fun toRange_locatorWithLibraryPrefix_parsesCorrectly() {
        val range = TrackBacks.toRange("MyLib:5:3-10:20")!!
        assertEquals(4, range.start.line)
        assertEquals(2, range.start.character)
        assertEquals(9, range.end.line)
        assertEquals(20, range.end.character)
    }

    @Test
    fun toRange_libraryPrefixWithSinglePosition_parsesCorrectly() {
        val range = TrackBacks.toRange("SomeLib:12:7")!!
        assertEquals(11, range.start.line)
        assertEquals(6, range.start.character)
        assertEquals(11, range.end.line)
        assertEquals(7, range.end.character)
    }

    @Test
    fun toRange_zeroBasedStart_adjustsCorrectly() {
        val range = TrackBacks.toRange("1:0-2:0")!!
        assertEquals(0, range.start.line, "line 1 → 0-indexed 0")
        assertEquals(0, range.start.character, "char 0 → max(0-1,0)=0")
        assertEquals(1, range.end.line, "line 2 → 0-indexed 1")
        assertEquals(0, range.end.character, "end char 0 (exclusive)")
    }

    @Test
    fun toRange_startCharZero_minClampsToZero() {
        val range = TrackBacks.toRange("3:0-5:5")!!
        assertEquals(0, range.start.character, "start char 0 → max(0-1,0)=0")
    }

    @Test
    fun toRange_nonMatchingString_returnsNull() {
        assertNull(TrackBacks.toRange("not-a-locator"))
    }

    @Test
    fun toRange_emptyString_returnsNull() {
        assertNull(TrackBacks.toRange(""))
    }

    @Test
    fun toRange_trailingColonOnly_returnsNull() {
        assertNull(TrackBacks.toRange("5:"))
    }

    @Test
    fun containsPosition_withinRange_returnsTrue() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(6, 5))
        assertTrue(result)
    }

    @Test
    fun containsPosition_onStartBoundary_returnsTrue() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(4, 2))
        assertTrue(result)
    }

    @Test
    fun containsPosition_onEndExclusiveBoundary_returnsFalse() {
        // LSP4J Ranges.containsPosition treats end as inclusive, so test one char past end.
        val result = TrackBacks.containsPosition("5:3-10:20", Position(9, 21))
        assertFalse(result)
    }

    @Test
    fun containsPosition_beforeStartLine_returnsFalse() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(3, 0))
        assertFalse(result)
    }

    @Test
    fun containsPosition_afterEndLine_returnsFalse() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(11, 0))
        assertFalse(result)
    }

    @Test
    fun containsPosition_beforeStartChar_returnsFalse() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(4, 0))
        assertFalse(result)
    }

    @Test
    fun containsPosition_afterEndChar_returnsFalse() {
        val result = TrackBacks.containsPosition("5:3-10:20", Position(9, 25))
        assertFalse(result)
    }

    @Test
    fun containsPosition_invalidLocator_returnsFalse() {
        val result = TrackBacks.containsPosition("bad-locator", Position(0, 0))
        assertFalse(result)
    }
}
