package app.easepod.ui

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun multiTimestampFractionAndOffsetAreApplied() {
        val lines = parseLyrics("[ar:Artist]\n[offset:-100]\n[00:02.50][00:01.234]line\n[00:05]next")
        assertEquals(listOf(1134L, 2400L, 4900L), lines.map { it.timeMs })
        assertEquals(listOf("line", "line", "next"), lines.map { it.text })
    }
    @Test fun plainTextIsReadableWithoutInventedTiming() {
        val lines = parseLyrics("[ar:Artist]\nFirst line\n\nSecond line")
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == null })
    }
    @Test fun invalidSecondsAndEmptyInputDoNotCreateTimedRows() {
        assertTrue(parseLyrics("").isEmpty())
        assertTrue(parseLyrics("[00:99]invalid").isEmpty())
    }
}
