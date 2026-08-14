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
import com.naxor.app.adapter.HistorialAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.CalculationEntity
import com.naxor.app.databinding.ActivityHistorialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistorialBinding
    private lateinit var adapter: HistorialAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityHistorialBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupRecyclerView()
            setupListeners()
            loadData()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al iniciar historial: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = HistorialAdapter(
            items = emptyList(),
            onShare = { calculation -> shareTicket(calculation) },
            onLongClick = { calculation -> showDeleteConfirmation(calculation) }
        )
        binding.rvHistorial.layoutManager = LinearLayoutManager(this)
        binding.rvHistorial.adapter = adapter
    }

    private fun shareTicket(item: CalculationEntity) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "Mi Negocio")
        val currency = prefs.getString("currency_symbol", "S/")

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val fechaStr = sdf.format(Date(item.timestamp))

        val ticketBody = StringBuilder()
        ticketBody.append("📄 *TICKET DE VENTA* 📄\n")
        ticketBody.append("----------------------------\n")
        ticketBody.append("🏪 *$businessName*\n")
        ticketBody.append("📅 Fecha: $fechaStr\n\n")
        ticketBody.append("🛍️ *Producto:* ${item.nombre}\n")
        ticketBody.append("📂 Categoría: ${item.categoria}\n")
        ticketBody.append("🔢 Cantidad: ${item.unidades} uds\n")
        ticketBody.append("----------------------------\n")
        ticketBody.append("💰 *TOTAL: $currency ${String.format(Locale.getDefault(), "%.2f", item.precioSugerido)}*\n")
        ticketBody.append("----------------------------\n")
        ticketBody.append("¡Gracias por su compra!")

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, ticketBody.toString())
        
        // Abrir selector de aplicaciones (WhatsApp, Telegram, etc.)
        startActivity(Intent.createChooser(intent, "Enviar ticket por:"))
    }

    private fun setupListeners() {
        binding.btnBackHistorial.setOnClickListener { finish() }

        // Abrir barra lateral
        binding.btnOpenMenuHistorial.setOnClickListener {
            binding.drawerLayoutHistorial.openDrawer(GravityCompat.END)
        }

        // Manejar clics en el menú de la barra lateral
        binding.navigationViewHistorial.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_clear_history -> {
                    showDeleteAllConfirmation()
                    binding.drawerLayoutHistorial.closeDrawer(GravityCompat.END)
                    true
                }
                else -> false
            }
        }

        binding.etSearchHistorial.addTextChangedListener { text ->
            searchData(text.toString())
        }

        binding.chipGroupCategorias.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty() || checkedIds.contains(R.id.chipTodos)) {
                loadData()
            } else {
                val chipId = checkedIds.first()
                val categoria = when (chipId) {
                    R.id.chipAbarrotes -> "Abarrotes"
                    R.id.chipAlimentos -> "Alimentos"
                    R.id.chipBelleza -> "Belleza y Cosméticos"
                    R.id.chipCalzado -> "Calzado"
                    R.id.chipElectronica -> "Electrónica"
                    R.id.chipFarmacia -> "Farmacia y Salud"
                    R.id.chipFerreteria -> "Ferretería"
                    R.id.chipHogar -> "Hogar"
                    R.id.chipJugueteria -> "Juguetería"
                    R.id.chipLibreria -> "Librería y Útiles"
                    R.id.chipMascotas -> "Mascotas"
                    R.id.chipRegalos -> "Regalos y Novedades"
                    R.id.chipRepuestos -> "Repuestos Automotriz"
                    R.id.chipRopa -> "Ropa"
                    else -> ""
                }
                filterByCategory(categoria)
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    database.calculationDao().allCalculations
                }
                updateUI(list)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HistorialActivity, "Error al cargar datos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun searchData(query: String) {
        if (query.isBlank()) {
            loadData()
            return
        }
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    database.calculationDao().searchCalculations("%$query%")
                }
                updateUI(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun filterByCategory(categoria: String) {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    database.calculationDao().getCalculationsByCategory(categoria)
                }
                updateUI(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateUI(list: List<CalculationEntity>) {
        adapter.updateList(list)
        binding.layoutEmptyHistorial.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDeleteConfirmation(calculation: CalculationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar registro")
            .setMessage("¿Estás seguro de que deseas eliminar el análisis de '${calculation.nombre}'?")
            .setPositiveButton("Eliminar") { _, _ -> deleteItem(calculation) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteItem(calculation: CalculationEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.calculationDao().delete(calculation)
                withContext(Dispatchers.Main) {
                    loadData()
                    Toast.makeText(this@HistorialActivity, "Análisis eliminado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Limpiar Historial")
            .setMessage("¿Estás seguro de que deseas borrar todos los registros? Esta acción no se puede deshacer.")
            .setPositiveButton("Limpiar Todo") { _, _ -> deleteAllItems() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteAllItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.calculationDao().deleteAllCalculations()
                withContext(Dispatchers.Main) {
                    loadData()
                    Toast.makeText(this@HistorialActivity, "Historial vaciado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
