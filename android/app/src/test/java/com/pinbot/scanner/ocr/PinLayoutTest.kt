package com.pinbot.scanner.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLayoutTest {

    /** Builds digit boxes laid out like a printed code. */
    private fun row(
        groups: List<Int>,
        pitch: Int = 14,
        separator: Int = 8,
        width: Int = 12,
        height: Int = 20,
        top: Int = 100,
    ): List<Glyph> {
        val result = ArrayList<Glyph>()
        var x = 20
        groups.forEachIndexed { index, size ->
            repeat(size) {
                result += Glyph('5', x, top, x + width, top + height)
                x += pitch
            }
            if (index < groups.size - 1) x += separator
        }
        return result
    }

    @Test
    fun acceptsMobilisLayout() {
        val candidate = PinLayout.validateLine(row(listOf(4, 4, 4, 3)), imageWidth = 400)
        assertNotNull(candidate)
        assertEquals(15, candidate!!.digits.length)
        assertEquals(listOf(4, 4, 4, 3), candidate.blocks.map { it.length })
        assertEquals("5555 5555 5555 555", candidate.formatted)
    }

    @Test
    fun acceptsSixteenDigitLayout() {
        val candidate = PinLayout.validateLine(row(listOf(4, 4, 4, 4)), imageWidth = 400)
        assertNotNull(candidate)
        assertEquals(16, candidate!!.digits.length)
    }

    @Test
    fun rejectsSerialNumberWithoutSeparators() {
        // The 15-digit serial under the barcode is evenly spaced.
        val serial = row(listOf(15), separator = 0)
        assertNull(PinLayout.validateLine(serial, imageWidth = 400))
    }

    @Test
    fun rejectsWrongGroupSizes() {
        assertNull(PinLayout.validateLine(row(listOf(2, 5, 4, 4)), imageWidth = 400))
    }

    @Test
    fun rejectsShortRows() {
        assertNull(PinLayout.validateLine(row(listOf(3, 3, 3, 3)), imageWidth = 400))
    }

    @Test
    fun rejectsRowsThatAreTooSmallForTheFrame() {
        // A perfectly shaped row, but only ~230 px wide in a 6000 px frame.
        assertNull(PinLayout.validateLine(row(listOf(4, 4, 4, 3)), imageWidth = 6000))
    }

    @Test
    fun splitsTwoLinesAndPicksThePin() {
        val pin = row(listOf(4, 4, 4, 3), top = 300, height = 40, width = 24, pitch = 28, separator = 18)
        val serial = row(listOf(15), top = 600, height = 14, width = 8, pitch = 9, separator = 0)
        val lines = PinLayout.clusterLines(pin + serial)
        assertEquals(2, lines.size)

        val candidates = PinLayout.candidates(pin + serial, imageWidth = 1000)
        assertTrue(candidates.isNotEmpty())
        assertEquals(15, candidates.first().digits.length)
        assertTrue(candidates.first().box.top >= 280)
    }

    @Test
    fun confidenceStaysInRange() {
        val candidate = PinLayout.validateLine(row(listOf(4, 4, 4, 3)), imageWidth = 400)
        assertNotNull(candidate)
        assertTrue(candidate!!.confidence in 0.0..1.0)
    }
}
