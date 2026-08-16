package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import java.util.Locale

class GastosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGastosBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: GastosAdapter
    private var currentDialogBinding: DialogAddExpenseBinding? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningDialog: AlertDialog? = null

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
            currentDialogBinding?.etExpenseConcepto?.setText(spokenText.replaceFirstChar { it.uppercase() })
        }
    }

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
                color = getColor(R.color.emerald_50) // Usar un color claro sobre el fondo morado
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
            startVoiceRecognition()
        }

        db.btnCancelExpense.setOnClickListener { dialog.dismiss() }
        
        db.btnSaveExpense.setOnClickListener {
            val concepto = db.etExpenseConcepto.text.toString().trim()
            val monto = db.etExpenseMonto.text.toString().toDoubleOrNull() ?: 0.0
            val categoria = db.autoExpenseCategoria.text.toString().trim()
            
            if (concepto.isNotBlank() && monto > 0 && categoria.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val newExpense = ExpenseEntity(
                        concepto = concepto,
                        monto = monto,
                        categoria = categoria,
                        isSynced = false
                    )
                    database.expenseDao().insert(newExpense)

                    // REGISTRAR EN HISTORIAL
                    val log = MovementLogEntity(
                        type = "EXPENSE",
                        title = "Gasto Registrado",
                        description = "${newExpense.categoria}: ${newExpense.concepto}",
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", newExpense.monto)}",
                        colorHex = "#DC2626",
                        iconRes = android.R.drawable.ic_menu_send
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

    private fun startVoiceRecognition() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        // PREPARAR INTENT COMÚN
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga el concepto claramente...")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // FALLBACK DIRECTO AL DIÁLOGO DE GOOGLE
            try { voiceLauncher.launch(intent) } catch (e: Exception) { Toast.makeText(this, "Voz no disponible", Toast.LENGTH_SHORT).show() }
            return
        }

        showListeningDialog()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                listeningDialog?.findViewById<View>(R.id.viewPulse)?.let { pulse ->
                    // Sensibilidad aumentada: divisor más pequeño = más movimiento
                    val scale = 1.0f + (rmsdB / 5f).coerceAtLeast(0f)
                    pulse.scaleX = scale
                    pulse.scaleY = scale
                    // La opacidad también reacciona a la voz
                    pulse.alpha = (0.5f + (rmsdB / 20f)).coerceIn(0.5f, 0.9f)
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listeningDialog?.dismiss()
            }
            override fun onError(error: Int) {
                listeningDialog?.dismiss()
                Log.e("Speech", "Error: $error")
                // FALLBACK AL DIÁLOGO DE GOOGLE SI EL SERVICIO INTERNO FALLA (Ej. Busy)
                try { voiceLauncher.launch(intent) } catch (e: Exception) {}
            }
            override fun onResults(results: Bundle?) {
                listeningDialog?.dismiss()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    currentDialogBinding?.etExpenseConcepto?.setText(matches[0].replaceFirstChar { it.uppercase() })
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun showListeningDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_listening, null)
        listeningDialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { speechRecognizer?.stopListening() }
            .create()
        
        listeningDialog?.show()
        
        // Animación suave infinita por si no hay voz todavía
        val pulseView = dialogView.findViewById<View>(R.id.viewPulse)
        pulseView.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(0.5f)
            .setDuration(800)
            .withEndAction {
                pulseView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0.3f)
                    .setDuration(800)
                    .start()
            }.start()
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

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
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
            holder.b.root.setOnLongClickListener {
                onDelete(e)
                true
            }
        }
        override fun getItemCount() = list.size
    }
}
