package com.gomezmart.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.ActivityMovementsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MovementsActivity : BaseActivity() {

    private lateinit var binding: ActivityMovementsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: MovementsAdapter
    
    private var allMovements = mutableListOf<UnifiedMovement>()
    private var currentFilter = "Todos"

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"
    private var logsRegistration: ListenerRegistration? = null

    data class UnifiedMovement(
        val id: String,
        val type: String, // SALE, EXPENSE, PRODUCT, MODIFICATION
        val title: String,
        val desc: String,
        val value: String,
        val timestamp: Long,
        val colorHex: String,
        val iconRes: Int,
        val referenceId: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMovementsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupAdaptiveInsets()

            setupRecyclerView()
            setupListeners()
            setupNetworkMonitoring()
            setupSyncIndicator()
            loadMovements()
            
            logsRegistration = SyncManager(this).startRealtimeLogsSync { loadMovements() }
        } catch (e: Exception) {
            e.printStackTrace()
            setContentView(R.layout.activity_movements)
            Toast.makeText(this, "Cargando historial...", Toast.LENGTH_SHORT).show()
            setupRecyclerViewManual()
            setupListenersManual()
            loadMovements()
        }
    }

    override fun onStop() {
        super.onStop()
        logsRegistration?.remove()
    }

    /**
     * Ajusta la interfaz de forma adaptativa a cualquier tamaño de pantalla,
     * evitando que las barras del sistema de Android tapen el contenido.
     */
    private fun setupAdaptiveInsets() {
        applyWindowInsetsMargins(binding.appBarMovements, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.rvMovements, applyTop = false, applyBottom = true)
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

    /**
     * Confirma y ejecuta la anulación de un cobro registrado por error.
     */
    private fun showReversePaymentDialog(item: UnifiedMovement) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("↩️ ¿Deshacer este cobro?")
            .setMessage(
                "Se anulará el cobro de ${item.value}.\n\n" +
                "• El pago se eliminará del historial de pagos.\n" +
                "• La cuota volverá a estar pendiente o parcial.\n" +
                "• Si el préstamo estaba cancelado, se reabrirá."
            )
            .setPositiveButton("Sí, deshacer") { _, _ -> performReversePayment(item) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performReversePayment(item: UnifiedMovement) {
        lifecycleScope.launch(Dispatchers.IO) {
            val paymentId = resolvePaymentId(item)
            if (paymentId == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MovementsActivity,
                        "No se encontró el cobro original",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val ok = try {
                database.loanDao().reversePayment(paymentId)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            if (ok) {
                // Marcar el movimiento como revertido (para que deje de ofrecer "deshacer")
                val log = database.movementLogDao().getLogById(item.id)
                log?.let {
                    database.movementLogDao().insert(it.copy(
                        type = "PAYMENT_REVERSED",
                        title = "Cobro Deshecho",
                        description = "Se anuló: ${it.description}",
                        colorHex = "#DC2626",
                        iconRes = android.R.drawable.ic_menu_revert,
                        referenceId = null,
                        isSynced = false
                    ))
                }
                SyncManager(this@MovementsActivity).scheduleOfflineSync()
            }

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@MovementsActivity,
                    if (ok) "Cobro deshecho ✅" else "No se pudo deshacer el cobro",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                loadMovements()
            }
        }
    }

    /**
     * Permite borrar un registro del historial (manteniendo pulsado el movimiento).
     */
    private fun showDeleteMovementDialog(item: UnifiedMovement) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🗑️ ¿Borrar este registro?")
                .setMessage(
                "Se eliminará ${item.title} del historial de movimientos.\n\n" +
                "• El movimiento desaparecerá de esta lista.\n" +
                "• No se modifican los saldos ni los cobros ya registrados."
            )
            .setPositiveButton("Borrar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        database.movementLogDao().markLogDeleted(item.id)
                        SyncManager(this@MovementsActivity).scheduleOfflineSync()
                    } catch (e: Exception) { }
                    withContext(Dispatchers.Main) { loadMovements() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Obtiene el id del pago original. Primero usa la referencia directa y, si no existe
     * (movimientos antiguos), busca el pago más cercano en tiempo con el mismo monto.
     */
    private suspend fun resolvePaymentId(item: UnifiedMovement): String? {
        if (!item.referenceId.isNullOrEmpty()) return item.referenceId

        val amount = item.value.replace("S/", "", ignoreCase = true)
            .replace(",", "").trim().toDoubleOrNull() ?: return null

        val candidates = database.loanDao().getAllPaymentsOnce()
            .filter { !it.isDeleted && it.type != 1 }
            .filter { Math.abs((it.amount + it.lateFeeAmount) - amount) < 0.01 }

        return candidates.minByOrNull { Math.abs(it.timestamp - item.timestamp) }?.id
    }

    private fun setupNetworkMonitoring() {
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    isNetworkAvailable = true
                    updateSyncIconState()
                }
            }

            override fun onLost(network: android.net.Network) {
                runOnUiThread {
                    isNetworkAvailable = false
                    updateSyncIconState()
                }
            }
        })
        
        val activeInfo = connectivityManager.activeNetworkInfo
        isNetworkAvailable = activeInfo != null && activeInfo.isConnected
    }

    private fun setupSyncIndicator() {
        if (isGuestUser()) {
            binding.btnSyncIndicator.visibility = View.GONE
            return
        }
        database.movementLogDao().getUnsyncedCount().observe(this) { count ->
            lastUnsyncedCount = count ?: 0
            updateSyncIconState()
        }

        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("offline_sync")
            .observe(this) { infoList ->
                val isSyncing = infoList != null && infoList.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                updateSyncIconState(isSyncing)
            }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            AlertDialog.Builder(this)
                .setTitle("Sincronización")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus\n\n¿Deseas forzar la subida de todos los datos ahora?")
                .setPositiveButton("Sincronizar Todo") { _, _ -> forceFullUpload() }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState(isSyncing: Boolean = currentIsSyncing) {
        if (isGuestUser()) {
            binding.btnSyncIndicator.visibility = View.GONE
            return
        }
        currentIsSyncing = isSyncing
        val color: Int
        val animation: android.view.animation.Animation?
        val iconRes: Int
        
        when {
            isSyncing && isNetworkAvailable -> {
                syncStatusText = "Sincronizando..."
                color = androidx.core.content.ContextCompat.getColor(this, R.color.sky_600)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = Color.parseColor("#F59E0B")
                iconRes = android.R.drawable.stat_notify_sync
                animation = null
            }
            else -> {
                syncStatusText = "Todo sincronizado"
                color = Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
                animation = null
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
            clearAnimation()
            if (animation != null) startAnimation(animation)
        }
    }

    private fun forceFullUpload() {
        Toast.makeText(this, "Iniciando subida forzada...", Toast.LENGTH_SHORT).show()
        SyncManager(this).uploadAllLocalToCloud {
            Toast.makeText(this, "Sincronización finalizada", Toast.LENGTH_SHORT).show()
            updateSyncIconState()
        }
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
        popup.menu.add("Préstamos")
        popup.menu.add("Cobros")
        popup.menu.add("Clientes")
        popup.menu.add("Compromisos")
        
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
            "Gastos" -> allMovements.filter { it.type == "EXPENSE" || it.type.startsWith("EXPENSE_") }
            "Inventario" -> allMovements.filter { it.type.startsWith("PRODUCT") }
            "Préstamos" -> allMovements.filter { it.type.startsWith("LOAN_") }
            "Cobros" -> allMovements.filter { it.type.startsWith("PAYMENT_") }
            "Clientes" -> allMovements.filter { it.type.startsWith("CLIENT_") }
            "Compromisos" -> allMovements.filter { it.type.startsWith("COMMITMENT_") }
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
                // Limitamos a los últimos 100 para evitar bloqueos del hilo principal en DBs grandes
                try {
                    val logs = database.movementLogDao().getRecentLogsActive(100)
                    
                    // Si no hay ningún log (primera vez), hacemos un "backfill" de datos básicos
                    if (logs.isEmpty()) {
                        backfillInitialLogs()
                        return@withContext database.movementLogDao().getRecentLogsActive(100).map { l ->
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
                            iconRes = l.iconRes,
                            referenceId = l.referenceId
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
        withContext(Dispatchers.IO) {
            val batchLogs = mutableListOf<MovementLogEntity>()

            // Ventas antiguas
            val sales = database.saleDao().allSales
            sales.forEach { s ->
                batchLogs.add(
                    MovementLogEntity(
                        type = "SALE",
                        title = "Venta Realizada",
                        description = "${s.cantidad} x ${s.nombreProducto}",
                        value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", s.total)}",
                        timestamp = s.timestamp,
                        colorHex = "#059669",
                        iconRes = android.R.drawable.ic_menu_add
                    )
                )
            }

            // Gastos antiguos
            val expenses = database.expenseDao().getAllExpenses()
            expenses.forEach { e ->
                batchLogs.add(
                    MovementLogEntity(
                        type = "EXPENSE",
                        title = "Gasto Registrado",
                        description = "${e.categoria}: ${e.concepto}",
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", e.monto)}",
                        timestamp = e.fecha,
                        colorHex = "#DC2626",
                        iconRes = android.R.drawable.ic_menu_send
                    )
                )
            }

            // Productos actuales
            val products = database.productDao().allProducts
            products.forEach { p ->
                batchLogs.add(
                    MovementLogEntity(
                        type = "PRODUCT_CREATED",
                        title = "Nuevo Producto",
                        description = "${p.categoria}: ${p.nombre}",
                        value = "Stock: ${p.stock}",
                        timestamp = p.timestamp,
                        colorHex = "#8E44AD",
                        iconRes = android.R.drawable.ic_menu_edit
                    )
                )
            }

            if (batchLogs.isNotEmpty()) {
                // Usamos insertAll si está disponible o Room en transacción
                database.movementLogDao().insertAll(batchLogs)
            }
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
            val btnReverse = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnReverseMovement)
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

            // Botón para deshacer un cobro registrado por error
            holder.btnReverse?.setOnClickListener(null)
            val canReverse = item.type == "PAYMENT_REGISTERED"
            if (canReverse) {
                holder.btnReverse?.visibility = View.VISIBLE
                holder.btnReverse?.setOnClickListener {
                    showReversePaymentDialog(item)
                }
            } else {
                holder.btnReverse?.visibility = View.GONE
            }

            // Mantener pulsado permite borrar el registro del historial
            holder.itemView.setOnLongClickListener {
                showDeleteMovementDialog(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
