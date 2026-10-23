package com.gomezmart.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.CommitmentEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.ActivityCommitmentsBinding
import com.gomezmart.app.databinding.ItemCommitmentBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class CommitmentsActivity : BaseActivity() {

    private lateinit var binding: ActivityCommitmentsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CommitmentsAdapter
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommitmentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para la cabecera y el botón flotante (+)
        applyWindowInsets(binding.toolbarCommitments, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.fabAddCommitment, applyTop = false, applyBottom = true)

        binding.toolbarCommitments.setNavigationOnClickListener { finish() }
        
        setupSyncIndicator()
        setupRecyclerView()
        loadCommitments()
        
        binding.fabAddCommitment.setOnClickListener { showAddCommitmentDialog() }
    }

    private fun setupSyncIndicator() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                runOnUiThread { updateSyncIconState() }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback)

        lifecycleScope.launch {
            database.commitmentDao().getUnsyncedCountFlow().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sincronización de Compromisos")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus")
                .setPositiveButton("Sincronizar ahora") { _, _ -> 
                    SyncManager(this).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
        val color: Int
        val iconRes: Int
        
        when {
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión"
                color = android.graphics.Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6")
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun setupRecyclerView() {
        adapter = CommitmentsAdapter(
            onStatusChanged = { commitment, isPaid ->
                lifecycleScope.launch {
                    database.commitmentDao().updateCommitment(commitment.copy(isPaid = isPaid, isSynced = false))
                    SyncManager(this@CommitmentsActivity).scheduleOfflineSync()
                }
            },
            onDelete = { commitment ->
                lifecycleScope.launch {
                    database.commitmentDao().softDelete(commitment.id)
                    SyncManager(this@CommitmentsActivity).scheduleOfflineSync()
                }
            }
        )
        binding.rvCommitments.layoutManager = LinearLayoutManager(this)
        binding.rvCommitments.adapter = adapter
    }

    private fun loadCommitments() {
        lifecycleScope.launch {
            database.commitmentDao().getAllCommitments().collectLatest { list ->
                adapter.submitList(list)
                binding.layoutEmptyCommitments.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddCommitmentDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_commitment, null)
        val etDesc = dialogView.findViewById<EditText>(R.id.etCommitmentDesc)
        val etAmount = dialogView.findViewById<EditText>(R.id.etCommitmentAmount)
        val autoFreq = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteCommitmentFreq)
        val btnDate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCommitmentDate)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelCommitment)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveCommitment)
        
        val freqOptions = arrayOf("Un solo pago", "Diario", "Mensual (Día fijo)")
        val freqAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, freqOptions)
        autoFreq.setAdapter(freqAdapter)
        autoFreq.setText(freqOptions[0], false)
        
        var selectedDate = System.currentTimeMillis()
        var selectedDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        var selectedFreq = "FIXED"

        autoFreq.setOnItemClickListener { _, _, position, _ ->
            selectedFreq = when(position) {
                0 -> "FIXED"
                1 -> "DAILY"
                else -> "MONTHLY"
            }
            btnDate.visibility = if (selectedFreq == "DAILY") View.GONE else View.VISIBLE
            btnDate.text = if (selectedFreq == "MONTHLY") "📅 Día del mes: $selectedDay" else "📅 Fecha: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(selectedDate))}"
        }
        
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            if (selectedFreq == "MONTHLY") {
                // Selector simple de día o reusar DatePicker y extraer solo el día
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    selectedDay = d
                    btnDate.text = "📅 Día del mes: $d"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), selectedDay).show()
            } else {
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    cal.set(y, m, d)
                    selectedDate = cal.timeInMillis
                    btnDate.text = "📅 Fecha: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(selectedDate))}"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val desc = etDesc.text.toString().trim()
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            
            if (desc.isEmpty()) {
                etDesc.error = "Ingresa una descripción"
                return@setOnClickListener
            }
            if (amount <= 0) {
                etAmount.error = "Monto inválido"
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val commitment = CommitmentEntity(
                    description = desc,
                    amount = amount,
                    dueDate = if (selectedFreq == "FIXED") selectedDate else 0L,
                    recurringDay = if (selectedFreq == "MONTHLY") selectedDay else 0,
                    frequency = selectedFreq
                )
                database.commitmentDao().insertCommitment(commitment)

                // REGISTRAR EN HISTORIAL DE MOVIMIENTOS
                val commitmentLog = MovementLogEntity(
                    type = "COMMITMENT_CREATED",
                    title = "Compromiso Anotado",
                    description = commitment.description,
                    value = "S/ ${String.format(Locale.US, "%.2f", commitment.amount)}",
                    colorHex = "#F59E0B",
                    iconRes = android.R.drawable.ic_menu_agenda
                )
                database.movementLogDao().insert(commitmentLog)
                SyncManager(this@CommitmentsActivity).syncLogToCloud(commitmentLog)

                SyncManager(this@CommitmentsActivity).scheduleOfflineSync()
                Toast.makeText(this@CommitmentsActivity, "Compromiso anotado", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        dialog.show()
    }

    inner class CommitmentsAdapter(
        private val onStatusChanged: (CommitmentEntity, Boolean) -> Unit,
        private val onDelete: (CommitmentEntity) -> Unit
    ) : RecyclerView.Adapter<CommitmentsAdapter.ViewHolder>() {
        
        private var items = emptyList<CommitmentEntity>()
        fun submitList(newList: List<CommitmentEntity>) { items = newList; notifyDataSetChanged() }
        
        inner class ViewHolder(val b: ItemCommitmentBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemCommitmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val currency = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).getString("currency_symbol", "S/")
            
            with(holder.b) {
                tvCommitmentDesc.text = item.description
                tvCommitmentAmount.text = "$currency ${String.format(Locale.US, "%.2f", item.amount)}"
                
                val info = when(item.frequency) {
                    "DAILY" -> "Pago diario"
                    "MONTHLY" -> "Cada día ${item.recurringDay} del mes"
                    else -> "Vence el ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(item.dueDate))}"
                }
                tvCommitmentInfo.text = info
                
                cbCommitmentStatus.setOnCheckedChangeListener(null)
                cbCommitmentStatus.isChecked = item.isPaid
                
                if (item.isPaid) {
                    tvCommitmentDesc.paintFlags = tvCommitmentDesc.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    tvCommitmentDesc.alpha = 0.5f
                } else {
                    tvCommitmentDesc.paintFlags = tvCommitmentDesc.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    tvCommitmentDesc.alpha = 1.0f
                }

                cbCommitmentStatus.setOnCheckedChangeListener { _, isChecked ->
                    onStatusChanged(item, isChecked)
                }

                root.setOnLongClickListener {
                    AlertDialog.Builder(root.context)
                        .setTitle("Eliminar compromiso")
                        .setMessage("¿Estás seguro de eliminar '${item.description}'?")
                        .setPositiveButton("Eliminar") { _, _ -> onDelete(item) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    true
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
