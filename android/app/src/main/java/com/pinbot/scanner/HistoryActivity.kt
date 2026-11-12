package com.pinbot.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinbot.scanner.data.HistoryRepository
import com.pinbot.scanner.data.ScanRecord
import com.pinbot.scanner.databinding.ActivityHistoryBinding
import com.pinbot.scanner.databinding.ItemScanBinding
import com.pinbot.scanner.util.Exporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local scan history with the one-click `.txt` export.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var repository: HistoryRepository
    private val adapter = ScanAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val records = repository.all()
            val written = Exporter.write(this, uri, Exporter.buildText(records))
            Toast.makeText(
                this,
                if (written) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = HistoryRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.exportButton.setOnClickListener {
            if (repository.all().isEmpty()) {
                Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show()
            } else {
                exportLauncher.launch(Exporter.suggestedName())
            }
        }

        binding.clearButton.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val records = repository.all()
        adapter.submit(records)

        val empty = records.isEmpty()
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.exportButton.isEnabled = !empty
        binding.clearButton.isEnabled = !empty
        binding.summary.text = getString(R.string.history_count, records.size)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.clear()
                reload()
            }
            .show()
    }

    private class ScanAdapter : RecyclerView.Adapter<ScanAdapter.Holder>() {

        private val items = ArrayList<ScanRecord>()
        private val format = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

        fun submit(records: List<ScanRecord>) {
            items.clear()
            items.addAll(records.sortedByDescending { it.timestamp })
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], format)
        }

        class Holder(private val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(record: ScanRecord, format: SimpleDateFormat) {
                binding.pin.text = record.formatted
                binding.meta.text = format.format(Date(record.timestamp))
            }
        }
    }
}
