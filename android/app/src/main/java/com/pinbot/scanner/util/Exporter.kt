package com.pinbot.scanner.util

import android.content.Context
import android.net.Uri
import com.pinbot.scanner.data.ScanRecord
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the plain-text export and writes it wherever the user picks.
 *
 * The file is deliberately simple (tab separated, one card per line) so it
 * opens in Notepad, Excel, or gets pasted into WhatsApp as-is.
 */
object Exporter {

    private const val HEADER = "PIN scanner export"

    fun buildText(records: List<ScanRecord>): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val builder = StringBuilder(64 + records.size * 40)
        builder.appendLine(HEADER)
        builder.appendLine("Generated: " + format.format(Date()))
        builder.appendLine("Cards: " + records.size)
        builder.append('\n')
        builder.appendLine("Date\tCode\tDigits")
        for (record in records.sortedBy { it.timestamp }) {
            builder.append(format.format(Date(record.timestamp))).append('\t')
            builder.append(record.formatted).append('\t')
            builder.append(record.digits).append('\n')
        }
        return builder.toString()
    }

    fun suggestedName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "pin-scanner-$stamp.txt"
    }

    fun write(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer -> writer.write(text) }
        } != null
    }.getOrDefault(false)
}
