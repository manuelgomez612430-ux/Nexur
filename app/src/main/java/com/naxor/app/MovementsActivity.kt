package com.naxor.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityMovementsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MovementsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMovementsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: MovementsAdapter
    
    private var allMovements = mutableListOf<UnifiedMovement>()
    private var currentFilter = "Todos"

    data class UnifiedMovement(
        val id: String,
        val type: String, // SALE, EXPENSE, PRODUCT, MODIFICATION
        val title: String,
        val desc: String,
        val value: String,
        val timestamp: Long,
        val colorHex: String,
        val iconRes: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMovementsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupRecyclerView()
            setupListeners()
            loadMovements()
        } catch (e: Exception) {
            e.printStackTrace()
            setContentView(R.layout.activity_movements)
            Toast.makeText(this, "Cargando historial...", Toast.LENGTH_SHORT).show()
            setupRecyclerViewManual()
            setupListenersManual()
            loadMovements()
        }
    }

    private fun setupRecyclerView() {
        adapter = MovementsAdapter()
        binding.rvMovements.layoutManager = LinearLayoutManager(this)
        binding.rvMovements.adapter = adapter
    }

    private fun setupRecyclerViewManual() {
        adapter = MovementsAdapter()
        findViewById<RecyclerView>(R.id.rvMovements)?.let {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
    }

    private fun setupListeners() {
        binding.btnBackMovements.setOnClickListener { finish() }
        binding.btnFilterMovements.setOnClickListener { showFilterMenu() }
    }

    private fun setupListenersManual() {
        findViewById<View>(R.id.btnBackMovements)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnFilterMovements)?.setOnClickListener { showFilterMenu() }
    }

    private fun showFilterMenu() {
        val anchor = findViewById<View>(R.id.btnFilterMovements) ?: binding.btnFilterMovements
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add("Todos")
        popup.menu.add("Ventas")
        popup.menu.add("Gastos")
        popup.menu.add("Inventario")
        
        popup.setOnMenuItemClickListener { item ->
            currentFilter = item.title.toString()
            applyFilter()
            true
        }
        popup.show()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            "Ventas" -> allMovements.filter { it.type == "SALE" }
            "Gastos" -> allMovements.filter { it.type == "EXPENSE" }
            "Inventario" -> allMovements.filter { it.type.startsWith("PRODUCT") }
            else -> allMovements
        }
        
        val subtitle = findViewById<TextView>(R.id.tvCurrentFilter) ?: binding.tvCurrentFilter
        subtitle.text = "Mostrando: $currentFilter"
        
        adapter.submitList(filtered)
        val emptyView = findViewById<View>(R.id.layoutEmptyMovements) ?: binding.layoutEmptyMovements
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadMovements() {
        lifecycleScope.launch {
            val movements = withContext(Dispatchers.IO) {
                val list = mutableListOf<UnifiedMovement>()

                // 1. Logs de Movimientos (ÚNICA FUENTE DE VERDAD)
                // Esto incluye Ventas, Gastos, Creaciones, Modificaciones y Eliminaciones
                try {
                    val logs = database.movementLogDao().getAllLogs()
                    
                    // Si no hay ningún log (primera vez), hacemos un "backfill" de datos básicos
                    if (logs.isEmpty()) {
                        backfillInitialLogs()
                        return@withContext database.movementLogDao().getAllLogs().map { l ->
                            UnifiedMovement(l.id, l.type, l.title, l.description, l.value, l.timestamp, l.colorHex, l.iconRes)
                        }
                    }

                    logs.forEach { l ->
                        list.add(UnifiedMovement(
                            id = l.id,
                            type = l.type,
                            title = l.title,
                            desc = l.description,
                            value = l.value,
                            timestamp = l.timestamp,
                            colorHex = l.colorHex,
                            iconRes = l.iconRes
                        ))
                    }
                } catch (e: Exception) { e.printStackTrace() }

                list.sortedByDescending { it.timestamp }
            }

            allMovements.clear()
            allMovements.addAll(movements)
            applyFilter()
        }
    }

    private suspend fun backfillInitialLogs() {
        // Ventas antiguas
        val sales = database.saleDao().allSales
        sales.forEach { s ->
            database.movementLogDao().insert(com.naxor.app.data.MovementLogEntity(
                type = "SALE",
                title = "Venta Realizada",
                description = "${s.cantidad} x ${s.nombreProducto}",
                value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", s.total)}",
                timestamp = s.timestamp,
                colorHex = "#059669",
                iconRes = android.R.drawable.ic_menu_add
            ))
        }
        // Gastos antiguos
        val expenses = database.expenseDao().getAllExpenses()
        expenses.forEach { e ->
            database.movementLogDao().insert(com.naxor.app.data.MovementLogEntity(
                type = "EXPENSE",
                title = "Gasto Registrado",
                description = "${e.categoria}: ${e.concepto}",
                value = "- S/ ${String.format(Locale.getDefault(), "%.2f", e.monto)}",
                timestamp = e.fecha,
                colorHex = "#DC2626",
                iconRes = android.R.drawable.ic_menu_send
            ))
        }
        // Productos actuales
        val products = database.productDao().allProducts
        products.forEach { p ->
            database.movementLogDao().insert(com.naxor.app.data.MovementLogEntity(
                type = "PRODUCT_CREATED",
                title = "Nuevo Producto",
                description = "${p.categoria}: ${p.nombre}",
                value = "Stock: ${p.stock}",
                timestamp = p.timestamp,
                colorHex = "#8E44AD",
                iconRes = android.R.drawable.ic_menu_edit
            ))
        }
    }

    inner class MovementsAdapter : RecyclerView.Adapter<MovementsAdapter.ViewHolder>() {
        private var items: List<UnifiedMovement> = emptyList()
        private val timeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        private val dateSdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        fun submitList(newList: List<UnifiedMovement>) {
            items = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title = view.findViewById<TextView>(R.id.tvMovementTitle)
            val desc = view.findViewById<TextView>(R.id.tvMovementDesc)
            val value = view.findViewById<TextView>(R.id.tvMovementValue)
            val time = view.findViewById<TextView>(R.id.tvMovementTime)
            val icon = view.findViewById<android.widget.ImageView>(R.id.ivMovementIcon)
            val cardIcon = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMovementIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_movement, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title?.text = item.title
            holder.desc?.text = item.desc
            holder.value?.text = item.value
            
            if (item.type == "SALE") holder.value?.setTextColor(Color.parseColor("#059669"))
            else if (item.type == "EXPENSE") holder.value?.setTextColor(Color.parseColor("#DC2626"))
            else if (item.type == "PRODUCT_DELETED") holder.value?.setTextColor(Color.LTGRAY)
            else holder.value?.setTextColor(Color.parseColor("#2C3E50"))

            holder.icon?.setImageResource(item.iconRes)
            holder.icon?.rotation = if (item.type == "EXPENSE") 90f else 0f
            holder.cardIcon?.setCardBackgroundColor(Color.parseColor(item.colorHex))

            val date = Date(item.timestamp)
            val timeStr = timeSdf.format(date)
            val dateStr = if (android.text.format.DateUtils.isToday(item.timestamp)) "Hoy" else dateSdf.format(date)
            holder.time?.text = "$timeStr - $dateStr"
        }

        override fun getItemCount() = items.size
    }
}
