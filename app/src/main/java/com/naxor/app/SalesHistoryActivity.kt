package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.SaleAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivitySalesHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SalesHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySalesHistoryBinding
    private lateinit var adapter: SaleAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySalesHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = SaleAdapter(
            transactions = emptyList(),
            onShare = { items -> shareTicket(items) },
            onLongClick = { items -> showDeleteConfirmation(items) }
        )
        binding.rvSalesHistory.layoutManager = LinearLayoutManager(this)
        binding.rvSalesHistory.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackSalesHistory.setOnClickListener { finish() }
        binding.btnOpenMenuSalesHistory.setOnClickListener {
            binding.drawerLayoutSalesHistory.openDrawer(GravityCompat.END)
        }

        binding.navigationViewSalesHistory.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutSalesHistory.closeDrawer(GravityCompat.END)
            when (menuItem.itemId) {
                R.id.menu_clear_history -> {
                    showDeleteAllConfirmation()
                    true
                }
                else -> false
            }
        }

        binding.etSearchSalesHistory.addTextChangedListener { text ->
            searchData(text.toString())
        }
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allSales = database.saleDao().allSales
            // Agrupar por transactionId manteniendo el orden de timestamp (descendente)
            val grouped = allSales.groupBy { it.transactionId }.values.toList()
            withContext(Dispatchers.Main) {
                updateUI(grouped)
            }
        }
    }

    private fun searchData(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val allSales = database.saleDao().allSales
            val filtered = if (query.isEmpty()) {
                allSales
            } else {
                allSales.filter { 
                    it.nombreProducto.contains(query, ignoreCase = true) || 
                    it.transactionId.contains(query, ignoreCase = true)
                }
            }
            val grouped = filtered.groupBy { it.transactionId }.values.toList()
            withContext(Dispatchers.Main) {
                updateUI(grouped)
            }
        }
    }

    private fun updateUI(data: List<List<SaleEntity>>) {
        adapter.updateList(data)
        binding.layoutEmptySalesHistory.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun shareTicket(items: List<SaleEntity>) {
        val businessPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessName = businessPrefs.getString("business_name", "Mi Negocio")
        val currency = businessPrefs.getString("currency_symbol", "S/")
        val locale = java.util.Locale.getDefault()
        
        val sb = StringBuilder()
        sb.append("--- $businessName ---\n")
        sb.append("Ticket de Venta\n")
        sb.append("Fecha: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", locale).format(java.util.Date(items[0].timestamp))}\n")
        sb.append("----------------------------\n")
        
        items.forEach { 
            sb.append("${it.nombreProducto}\n")
            sb.append("${it.cantidad} x $currency${String.format(locale, "%.2f", it.precioVenta)} = $currency${String.format(locale, "%.2f", it.total)}\n")
        }
        
        val total = items.sumOf { it.total }
        sb.append("----------------------------\n")
        sb.append("TOTAL: $currency${String.format(locale, "%.2f", total)}\n")
        sb.append("¡Gracias por su compra!\n")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Compartir Ticket"))
    }

    private fun showDeleteConfirmation(items: List<SaleEntity>) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Venta")
            .setMessage("¿Deseas eliminar este registro de venta? El stock NO se restaurará automáticamente.")
            .setPositiveButton("Eliminar") { _, _ ->
                deleteTransaction(items)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteTransaction(items: List<SaleEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            items.forEach { database.saleDao().delete(it) }
            loadData()
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Vaciar Historial")
            .setMessage("¿Estás seguro de que deseas borrar TODAS las ventas? Esta acción no se puede deshacer.")
            .setPositiveButton("Vaciar Todo") { _, _ ->
                deleteAllSales()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteAllSales() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.saleDao().deleteAllSales()
            loadData()
        }
    }
}
