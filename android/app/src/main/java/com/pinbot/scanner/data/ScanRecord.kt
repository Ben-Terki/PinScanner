package com.pinbot.scanner.data

/** One scanned top-up code. */
data class ScanRecord(
    val timestamp: Long,
    val digits: String,
    val confidence: Double,
) {
    /** Printed grouping, e.g. `8503 0422 6422 626`. */
    val formatted: String
        get() = digits.chunked(4).joinToString(" ")
}
