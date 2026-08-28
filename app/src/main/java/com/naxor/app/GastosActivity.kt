package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import com.naxor.app.util.VoiceRecognitionHelper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ExpenseEntity
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.databinding.ActivityGastosBinding
import com.naxor.app.databinding.DialogAddExpenseBinding
import com.naxor.app.databinding.ItemGastoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class GastosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGastosBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: GastosAdapter
    private var currentDialogBinding: DialogAddExpenseBinding? = null
    private val voiceHelper by lazy { VoiceRecognitionHelper(this) }
    private var listeningDialog: AlertDialog? = null

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGastosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupNetworkMonitoring()
        setupSyncIndicator()
        loadExpenses()
        
        SyncManager(this).startRealtimeExpensesSync { loadExpenses() }
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
        database.expenseDao().getUnsyncedCount().observe(this) { count ->
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
        currentIsSyncing = isSyncing
        val color: Int
        val animation: android.view.animation.Animation?
        val iconRes: Int
        
        when {
            isSyncing && isNetworkAvailable -> {
                syncStatusText = "Sincronizando..."
                color = androidx.core.content.ContextCompat.getColor(this, R.color.emerald_50)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = Color.parseColor("#FFCDD2") // Rojo claro
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = Color.parseColor("#FFF9C4") // Ámbar claro
                iconRes = android.R.drawable.stat_notify_sync
                animation = null
            }
            else -> {
                syncStatusText = "Todo sincronizado"
                color = Color.WHITE
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

    private fun setupRecyclerView() {
        adapter = GastosAdapter { expense -> showDeleteConfirmation(expense) }
        binding.rvGastos.layoutManager = LinearLayoutManager(this)
        binding.rvGastos.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackGastos.setOnClickListener { finish() }
        binding.btnHelpGastos.setOnClickListener { showHelpDialog() }
        binding.btnMailboxGastos.setOnClickListener {
            startActivity(Intent(this, MailboxActivity::class.java))
        }
        binding.fabAddGasto.setOnClickListener { showAddExpenseDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Gastos")
            .setMessage("• REGISTRO: Usa '+' para anotar pagos de luz, alquiler, transporte, etc.\n" +
                    "• UTILIDAD: Estos gastos se restarán automáticamente de tus ventas en la pantalla de Resultados.\n" +
                    "• BORRAR: Mantén presionado un gasto para eliminarlo.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadExpenses() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.expenseDao().getAllExpenses()
            }
            adapter.submitList(list)
            binding.layoutEmptyGastos.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            
            val total = list.sumOf { it.monto }
            binding.tvTotalGastosMes.text = String.format(Locale.getDefault(), "S/ %.2f", total)
        }
    }

    private fun showAddExpenseDialog() {
        val db = DialogAddExpenseBinding.inflate(layoutInflater)
        currentDialogBinding = db
        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        
        // CARGAR CATEGORÍAS DINÁMICAS + PREDEFINIDAS
        lifecycleScope.launch {
            val dbCategories = withContext(Dispatchers.IO) { database.expenseDao().getUniqueCategories() }
            val defaultCategories = listOf("Servicios", "Alquiler", "Transporte", "Sueldos", "Mercancía", "Personal", "Otros")
            val allCategories = (dbCategories + defaultCategories).distinct().sorted()
            
            val catAdapter = ArrayAdapter(this@GastosActivity, android.R.layout.simple_dropdown_item_1line, allCategories)
            db.autoExpenseCategoria.setAdapter(catAdapter)
            db.autoExpenseCategoria.threshold = 1 // Mostrar sugerencias desde la primera letra
        }
        
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

        // BOTÓN DESPLEGABLE (FLECHA)
        db.layoutExpenseCategoria.setEndIconOnClickListener {
            // Cerrar teclado al abrir el desplegable
            imm.hideSoftInputFromWindow(db.autoExpenseCategoria.windowToken, 0)
            db.autoExpenseCategoria.clearFocus()
            
            // Forzar el umbral a 0 temporalmente para mostrar todo al pulsar la flecha
            val oldThreshold = db.autoExpenseCategoria.threshold
            db.autoExpenseCategoria.threshold = 0
            db.autoExpenseCategoria.showDropDown()
            db.autoExpenseCategoria.postDelayed({ db.autoExpenseCategoria.threshold = oldThreshold }, 500)
        }

        // AL SELECCIONAR UNA CATEGORÍA DEL MENÚ
        db.autoExpenseCategoria.setOnItemClickListener { _, _, _, _ ->
            // Cerrar teclado automáticamente
            imm.hideSoftInputFromWindow(db.autoExpenseCategoria.windowToken, 0)
            db.autoExpenseCategoria.clearFocus()
        }

        db.layoutExpenseConcepto.setEndIconOnClickListener {
            voiceHelper.startListening { text ->
                db.etExpenseConcepto.setText(text.replaceFirstChar { it.uppercase() })
            }
        }

        var selectedScheduledDate = 0L
        db.switchFutureExpense.setOnCheckedChangeListener { _, isChecked ->
            db.btnExpenseDate.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        db.btnExpenseDate.setOnClickListener {
            val cal = Calendar.getInstance()
            android.app.DatePickerDialog(this, { _, y, m, d ->
                val sel = Calendar.getInstance()
                sel.set(y, m, d, 9, 0, 0)
                selectedScheduledDate = sel.timeInMillis
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                db.btnExpenseDate.text = "📅 Pago el: ${sdf.format(Date(selectedScheduledDate))}"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        db.btnCancelExpense.setOnClickListener { dialog.dismiss() }
        
        db.btnSaveExpense.setOnClickListener {
            val concepto = db.etExpenseConcepto.text.toString().trim()
            val monto = db.etExpenseMonto.text.toString().toDoubleOrNull() ?: 0.0
            val categoria = db.autoExpenseCategoria.text.toString().trim()
            
            if (concepto.isNotBlank() && monto > 0 && categoria.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val isPaid = !db.switchFutureExpense.isChecked
                    val newExpense = ExpenseEntity(
                        concepto = concepto,
                        monto = monto,
                        categoria = categoria,
                        isSynced = false,
                        fechaProgramada = selectedScheduledDate,
                        isPaid = isPaid,
                        fecha = if (isPaid) System.currentTimeMillis() else selectedScheduledDate
                    )
                    database.expenseDao().insert(newExpense)

                    // REGISTRAR EN HISTORIAL
                    val log = MovementLogEntity(
                        type = if (isPaid) "EXPENSE" else "FUTURE_EXPENSE",
                        title = if (isPaid) "Gasto Registrado" else "Gasto Programado",
                        description = "${newExpense.categoria}: ${newExpense.concepto}",
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", newExpense.monto)}",
                        colorHex = if (isPaid) "#DC2626" else "#F59E0B",
                        iconRes = if (isPaid) android.R.drawable.ic_menu_send else android.R.drawable.ic_menu_my_calendar
                    )
                    database.movementLogDao().insert(log)
                    SyncManager(this@GastosActivity).syncLogToCloud(log)

                    SyncManager(this@GastosActivity).syncExpenseToCloud(newExpense)
                    SyncManager(this@GastosActivity).scheduleOfflineSync()
                    
                    withContext(Dispatchers.Main) { 
                        loadExpenses() 
                        dialog.dismiss()
                    }
                }
            } else {
                Toast.makeText(this, "Completa Concepto, Monto y Categoría", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
        dialog.window?.let {
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun showDeleteConfirmation(expense: ExpenseEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Gasto")
            .setMessage("¿Deseas eliminar '${expense.concepto}'?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    expense.isDeleted = true
                    expense.isSynced = false
                    database.expenseDao().update(expense)

                    // REGISTRAR EN HISTORIAL
                    val log = MovementLogEntity(
                        type = "EXPENSE_DELETED",
                        title = "Gasto Eliminado",
                        description = expense.concepto,
                        value = "ELIMINADO",
                        colorHex = "#95A5A6",
                        iconRes = android.R.drawable.ic_menu_delete
                    )
                    database.movementLogDao().insert(log)
                    SyncManager(this@GastosActivity).syncLogToCloud(log)

                    SyncManager(this@GastosActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadExpenses() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMarkAsPaidDialog(expense: ExpenseEntity) {
        AlertDialog.Builder(this)
            .setTitle("Confirmar Pago")
            .setMessage("¿Deseas marcar '${expense.concepto}' como PAGADO?")
            .setPositiveButton("Sí, Pagado") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    expense.isPaid = true
                    expense.fecha = System.currentTimeMillis()
                    expense.isSynced = false
                    database.expenseDao().update(expense)
                    
                    val log = MovementLogEntity(
                        type = "EXPENSE_PAID",
                        title = "Gasto Pagado",
                        description = expense.concepto,
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", expense.monto)}",
                        colorHex = "#DC2626",
                        iconRes = android.R.drawable.checkbox_on_background
                    )
                    database.movementLogDao().insert(log)
                    
                    SyncManager(this@GastosActivity).syncExpenseToCloud(expense)
                    SyncManager(this@GastosActivity).syncLogToCloud(log)
                    SyncManager(this@GastosActivity).scheduleOfflineSync()
                    
                    withContext(Dispatchers.Main) { loadExpenses() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.destroy()
    }

    inner class GastosAdapter(private val onDelete: (ExpenseEntity) -> Unit) : RecyclerView.Adapter<GastosAdapter.ViewHolder>() {
        private var list: List<ExpenseEntity> = emptyList()
        fun submitList(newList: List<ExpenseEntity>) {
            list = newList
            notifyDataSetChanged()
        }
        inner class ViewHolder(val b: ItemGastoBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemGastoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val e = list[position]
            holder.b.tvGastoConcepto.text = e.concepto
            holder.b.tvGastoCategoria.text = e.categoria
            holder.b.tvGastoMonto.text = String.format(Locale.getDefault(), "- S/ %.2f", e.monto)
            
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            holder.b.tvGastoFecha.text = sdf.format(Date(e.fecha))

            if (!e.isPaid) {
                holder.b.cardGastoStatus.visibility = View.VISIBLE
                holder.b.tvGastoStatus.text = "PENDIENTE"
                holder.b.tvGastoMonto.setTextColor(Color.parseColor("#EA580C"))
            } else {
                holder.b.cardGastoStatus.visibility = View.GONE
                holder.b.tvGastoMonto.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.red_600))
            }

            holder.b.root.setOnClickListener {
                if (!e.isPaid) showMarkAsPaidDialog(e)
            }

            holder.b.root.setOnLongClickListener {
                onDelete(e)
                true
            }
        }
        override fun getItemCount() = list.size
    }
}
