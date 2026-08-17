package com.naxor.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.SaleAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityEmitirComprobanteBinding
import com.naxor.app.util.ComprobantePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class EmitirComprobanteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmitirComprobanteBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: SaleAdapter

    private var startDate: Long? = null
    private var endDate: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmitirComprobanteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
    }

    private fun setupRecyclerView() {
        adapter = SaleAdapter(
            items = emptyList(),
            onShare = { items -> sharePdf(items) },
            onEmit = { items -> sharePdf(items) }, // TambiÃ©n permite emitir/re-emitir desde aquÃ­
            onLongClick = { /* No op */ }
        )
        binding.rvSalesEmitir.layoutManager = LinearLayoutManager(this)
        binding.rvSalesEmitir.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackEmitir.setOnClickListener { finish() }

        binding.btnSelectDateStart.setOnClickListener {
            showDatePicker { date ->
                startDate = date.timeInMillis
                binding.tvDateStart.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date.time)
                loadFilteredSales()
            }
        }

        binding.btnSelectDateEnd.setOnClickListener {
            showDatePicker { date ->
                // Ajustar al final del dia
                date.set(Calendar.HOUR_OF_DAY, 23)
                date.set(Calendar.MINUTE, 59)
                date.set(Calendar.SECOND, 59)
                endDate = date.timeInMillis
                binding.tvDateEnd.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date.time)
                loadFilteredSales()
            }
        }
    }

    private fun showDatePicker(onDateSelected: (Calendar) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val result = Calendar.getInstance()
            result.set(year, month, day, 0, 0, 0)
            onDateSelected(result)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun loadFilteredSales() {
        val start = startDate ?: return
        val end = endDate ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val allSales = database.saleDao().allSales
            val filtered = allSales.filter { it.timestamp in start..end }
            
            // Agrupar por transacciÃ³n y luego convertir a SaleListItem
            val grouped = filtered.groupBy { it.transactionId }.values.toList()
                .sortedByDescending { it[0].timestamp }

            val listItems = mutableListOf<SaleAdapter.SaleListItem>()
            grouped.forEach { trans ->
                listItems.add(SaleAdapter.SaleListItem.Transaction(trans))
            }

            withContext(Dispatchers.Main) {
                adapter.updateList(listItems)
                binding.layoutEmptyEmitir.visibility = if (listItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun sharePdf(items: List<SaleEntity>) {
        val pdfFile = ComprobantePdfGenerator(this).generateComprobantePdf(items)
        if (pdfFile != null && pdfFile.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar Comprobante PDF"))
        } else {
            Toast.makeText(this, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    }
}
