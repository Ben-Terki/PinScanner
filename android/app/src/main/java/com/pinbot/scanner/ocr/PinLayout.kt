package com.pinbot.scanner.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Layout validation for prepaid top-up cards.
 *
 * This is a Kotlin port of the server pipeline in `pinbot/ocr/detect.py`: the
 * OCR engine (ML Kit) gives us character boxes, but it does not know that a
 * Mobilis code is `4-4-4-3` digits with three clearly wider gaps. That shape is
 * what separates the recharge code from the 15-digit serial printed under the
 * barcode — and it is the only reliable check, because these codes carry no
 * checksum.
 */
data class Glyph(
    val ch: Char,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

data class LayoutBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class PinCandidate(
    val digits: String,
    val blocks: List<String>,
    val box: LayoutBox,
    val score: Double,
    /** 0..1, derived from how cleanly the groups separate. */
    val confidence: Double,
) {
    val formatted: String get() = blocks.joinToString(" ")
}

object PinLayout {

    /** Accepted layouts: Mobilis prints 15 digits, other operators 16. */
    private val CUTS = intArrayOf(4, 8, 12)
    private val TOTALS = intArrayOf(16, 15)
    private const val MIN_LINE_GLYPHS = 10
    private const val SPAN_RATIO = 0.12

    fun candidates(glyphs: List<Glyph>, imageWidth: Int, limit: Int = 3): List<PinCandidate> =
        clusterLines(glyphs)
            .mapNotNull { validateLine(it, imageWidth) }
            .sortedByDescending { it.score }
            .take(limit)

    /**
     * Group character boxes into text lines.  Uses a running median per line so
     * a slightly tilted row still lands in one cluster.
     */
    fun clusterLines(glyphs: List<Glyph>): List<List<Glyph>> {
        val lines = mutableListOf<MutableList<Glyph>>()
        for (glyph in glyphs.sortedBy { it.cy }) {
            val match = lines.firstOrNull { line ->
                val reference = line[line.size / 2]
                val tallest = max(reference.height, glyph.height).toFloat()
                val shortest = min(reference.height, glyph.height).toFloat()
                abs(reference.cy - glyph.cy) <= tallest * 0.6f && shortest * 1.8f >= tallest
            }
            if (match == null) lines += mutableListOf(glyph) else match += glyph
        }
        return lines
            .filter { it.size >= MIN_LINE_GLYPHS }
            .map { it.sortedBy(Glyph::left) }
    }

    fun validateLine(line: List<Glyph>, imageWidth: Int): PinCandidate? {
        if (line.size < 15) return null

        val medianHeight = median(line.map { it.height })
        if (medianHeight <= 0) return null

        // Drop specks and any glyph from a neighbouring, smaller line.
        val glyphs = line.filter { it.height >= medianHeight * 0.6 }
        if (glyphs.size < 15) return null

        var best: PinCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (total in TOTALS) {
            if (glyphs.size < total) continue
            for (start in 0..glyphs.size - total) {
                val window = glyphs.subList(start, start + total)
                if (window.any { it.left < 0 }) continue
                val gaps = IntArray(total - 1) { window[it + 1].left - window[it].right }
                if (gaps.any { it < 0 }) continue

                // The three group separators sit after the 4th, 8th and 12th digit.
                val separators = intArrayOf(gaps[3], gaps[7], gaps[11])

                val inside = ArrayList<Int>(gaps.size - 3)
                for (index in gaps.indices) {
                    if (index != 3 && index != 7 && index != 11) inside += gaps[index]
                }
                if (inside.isEmpty()) continue

                val widestInside = inside.maxOrNull() ?: continue
                val medianInside = median(inside)
                val threshold = max(widestInside + 3, (widestInside * 1.5).toInt())
                if ((separators.minOrNull() ?: continue) < threshold) continue
                if ((separators.minOrNull() ?: continue) < (medianInside * 1.8).toInt()) continue

                val heights = window.map { it.height }
                val widths = window.map { it.width }
                val medianGlyphHeight = median(heights)
                val medianWidth = median(widths)
                if (medianGlyphHeight <= 0 || medianWidth <= 0) continue
                val tallest = heights.maxOrNull() ?: continue
                val shortest = heights.minOrNull() ?: continue
                if (tallest - shortest > medianGlyphHeight * 0.28) continue
                val narrowest = widths.minOrNull() ?: continue
                val widest = widths.maxOrNull() ?: continue
                if (narrowest < medianWidth * 0.33 || widest > medianWidth * 1.65) continue

                // Digits inside a block must be evenly pitched; separators are
                // wider by design, so they are excluded from this check.
                val pitches = ArrayList<Double>()
                var cursor = 0
                for (blockSize in CUTS.toList() + (total - CUTS.last())) {
                    for (offset in 0 until blockSize - 1) {
                        val first = window[cursor + offset]
                        val second = window[cursor + offset + 1]
                        pitches += (second.cx - first.cx).toDouble()
                    }
                    cursor += blockSize
                }
                if (pitches.size > 1) {
                    val meanPitch = pitches.average()
                    if (meanPitch <= 0.0) continue
                    if (sqrt(pitches.map { (it - meanPitch) * (it - meanPitch) }.average()) / meanPitch > 0.18) {
                        continue
                    }
                }

                val score = separators.sum().toDouble() - stdDev(separators)
                if (score <= bestScore) continue

                val blocks = ArrayList<String>(4)
                cursor = 0
                for (blockSize in CUTS.toList() + (total - CUTS.last())) {
                    blocks += window.subList(cursor, cursor + blockSize).joinToString("") { it.ch.toString() }
                    cursor += blockSize
                }

                val span = window.last().right - window.first().left
                if (span < imageWidth * SPAN_RATIO) continue

                // Confidence: how far the separators clear the in-block gaps,
                // squeezed into 0.6..0.95. Two matching reads are still required
                // before the code is shown, so this is a hint, not a guarantee.
                val smallestSeparator = separators.minOrNull() ?: continue
                val density = smallestSeparator.toDouble() / max(1.0, medianInside.toDouble())
                val t = ((density - 1.8) / 3.2).coerceIn(0.0, 1.0)
                val confidence = 0.6 + 0.35 * t

                bestScore = score
                best = PinCandidate(
                    digits = blocks.joinToString(""),
                    blocks = blocks,
                    box = LayoutBox(
                        left = window.first().left,
                        top = window.minOf { it.top },
                        right = window.last().right,
                        bottom = window.maxOf { it.bottom },
                    ),
                    score = score,
                    confidence = confidence,
                )
            }
        }
        return best
    }

    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun stdDev(values: IntArray): Double {
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }
}
