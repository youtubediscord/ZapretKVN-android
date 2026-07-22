package io.github.zapretkvn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFormattingTest {
    @Test
    fun `bytes and session duration remain compact`() {
        assertEquals("0 Б", formatBytes(0))
        assertEquals("1.0 КБ", formatBytes(1_024))
        assertEquals("1.5 МБ", formatBytes(1_572_864))
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun `ping switches to compact seconds above one second`() {
        assertEquals("—", formatPing(null))
        assertEquals("999 мс", formatPing(999))
        assertEquals("1 с", formatPing(1_000))
        assertEquals("1,98 с", formatPing(1_978))
        assertEquals("2 с", formatPing(1_999))
    }
}
