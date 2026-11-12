package com.pinbot.scanner.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Local history, stored as one JSON object per line in the app's private
 * storage.  A plain file is enough for a few thousand cards and keeps the app
 * free of database code and migrations.
 */
class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "scan_history.jsonl")
    private val lock = Any()

    /** Returns false when the scan duplicates the previous one. */
    fun append(record: ScanRecord): Boolean {
        return synchronized(lock) {
            val last = all().lastOrNull()
            val duplicate = last != null &&
                last.digits == record.digits &&
                record.timestamp - last.timestamp < DUPLICATE_WINDOW_MS
            if (duplicate) {
                false
            } else {
                runCatching {
                    file.appendText(record.toJson().toString() + "\n")
                    true
                }.getOrDefault(false)
            }
        }
    }

    fun all(): List<ScanRecord> {
        return synchronized(lock) {
            if (!file.exists()) {
                emptyList()
            } else {
                runCatching {
                    file.readLines().mapNotNull { line -> parse(line) }
                }.getOrDefault(emptyList())
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    private fun parse(line: String): ScanRecord? {
        if (line.isBlank()) return null
        return runCatching {
            val json = JSONObject(line)
            ScanRecord(
                timestamp = json.getLong("ts"),
                digits = json.getString("pin"),
                confidence = json.optDouble("conf", 0.0),
            )
        }.getOrNull()
    }

    private fun ScanRecord.toJson(): JSONObject {
        return JSONObject().apply {
            put("ts", timestamp)
            put("pin", digits)
            put("conf", confidence)
        }
    }

    private companion object {
        /** A card scanned twice within ten seconds is the same card. */
        const val DUPLICATE_WINDOW_MS = 10_000L
    }
}
