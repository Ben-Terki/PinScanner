package com.pinbot.scanner.ocr

import com.google.mlkit.vision.text.Text

/**
 * Turns ML Kit output into individual digit boxes.
 *
 * ML Kit groups characters into "elements" (roughly words).  For layout
 * validation we need one box per digit, so we read the per-symbol boxes and
 * split any element that came back as a single blob.
 */
object FrameText {

    fun glyphs(text: Text): List<Glyph> {
        val out = ArrayList<Glyph>(64)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val symbols = element.symbols
                    if (symbols.isNotEmpty()) {
                        for (symbol in symbols) {
                            val box = symbol.boundingBox ?: continue
                            val digits = symbol.text.filter { it.isDigit() }
                            if (digits.isEmpty()) continue
                            if (digits.length == 1) {
                                out += Glyph(digits[0], box.left, box.top, box.right, box.bottom)
                            } else {
                                split(out, digits, box.left, box.right, box.top, box.bottom)
                            }
                        }
                    } else {
                        val box = element.boundingBox ?: continue
                        val digits = element.text.filter { it.isDigit() }
                        if (digits.isEmpty()) continue
                        split(out, digits, box.left, box.right, box.top, box.bottom)
                    }
                }
            }
        }
        return out
    }

    private fun split(
        target: MutableList<Glyph>,
        digits: String,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ) {
        val step = (right - left).toFloat() / digits.length
        if (step < 2f) return
        digits.forEachIndexed { index, ch ->
            val x0 = left + (step * index).toInt()
            val x1 = left + (step * (index + 1)).toInt()
            target += Glyph(ch, x0, top, x1, bottom)
        }
    }
}
