package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.DebtorEntity
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.databinding.ActivityDeudoresBinding
import com.naxor.app.databinding.ItemDeudorBinding
import com.naxor.app.util.DebtorPdfGenerator
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class DeudoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeudoresBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: DeudoresAdapter
    private var allDebtorsList: List<DebtorEntity> = emptyList()
    private var isFilterOverdue = false

    // Temp references for dialog
    private var currentEtNombre: EditText? = null
    private var currentEtTelefono: EditText? = null

    private val pickContactLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val phone = cursor.getString(1).replace(" ", "").replace("-", "")
                    
                    if (currentEtNombre != null && currentEtTelefono != null) {
                        currentEtNombre?.setText(name)
                        currentEtTelefono?.setText(phone)
                    } else {
                        showAddDebtorDialog(name, phone)
                    }
                }
            }
        }
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            openContactPicker()
        } else {
            Toast.makeText(this, "Permiso denegado para acceder a contactos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        pickContactLauncher.launch(intent)
    }

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeudoresBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupNetworkMonitoring()
        setupSyncIndicator()
        loadDebtors()
        
        SyncManager(this).startRealtimeDebtorsSync { loadDebtors() }
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
        database.debtorDao().getUnsyncedDebtorsCount().observe(this) { count ->
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
                color = ContextCompat.getColor(this, R.color.white)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = android.graphics.Color.parseColor("#FFCDD2")
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#FFF9C4")
                iconRes = android.R.drawable.stat_notify_sync
                animation = null
            }
            else -> {
                syncStatusText = "Todo sincronizado"
                color = android.graphics.Color.WHITE
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
        adapter = DeudoresAdapter(
            onPagar = { deudor -> showPaymentDialog(deudor) },
            onWhatsApp = { deudor -> sendWhatsAppReminder(deudor) },
            onAumentar = { deudor -> showIncreaseDebtDialog(deudor) },
            onPdf = { deudor -> shareDebtorPdf(deudor) },
            onEdit = { deudor -> showAddDebtorDialog(existingDebtor = deudor) }
        )
        binding.rvDeudores.layoutManager = LinearLayoutManager(this)
        binding.rvDeudores.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackDeudores.setOnClickListener { finish() }
        binding.btnHelpDeudores.setOnClickListener { showHelpDialog() }
        binding.fabAddDeudor.setOnClickListener { showAddOptions() }

        binding.chipFilterAll.setOnClickListener {
            isFilterOverdue = false
            applyFilters()
        }
        binding.chipFilterOverdue.setOnClickListener {
            isFilterOverdue = true
            applyFilters()
        }

        binding.btnMassReminder.setOnClickListener {
            sendMassReminders()
        }
    }

    private fun applyFilters() {
        val filtered = if (isFilterOverdue) {
            val now = Calendar.getInstance().timeInMillis
            allDebtorsList.filter { it.fechaCobro in 1..now && !it.isDeleted }
        } else {
            allDebtorsList
        }
        adapter.submitList(filtered)
        binding.layoutEmptyDeudores.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.btnMassReminder.visibility = if (isFilterOverdue && filtered.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendMassReminders() {
        val now = Calendar.getInstance().timeInMillis
        val overdue = allDebtorsList.filter { it.fechaCobro in 1..now && !it.isDeleted }
        
        if (overdue.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Recordatorio Masivo 👥")
            .setMessage("Se abrirán los chats de WhatsApp uno por uno para enviar los recordatorios a los ${overdue.size} clientes vencidos.\n\n¿Deseas comenzar?")
            .setPositiveButton("Comenzar") { _, _ ->
                if (overdue.isNotEmpty()) sendWhatsAppReminder(overdue[0])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddOptions() {
        val options = arrayOf("Agregar Manualmente ✍️", "Importar de Contactos 👥")
        AlertDialog.Builder(this)
            .setTitle("Nuevo Deudor")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddDebtorDialog()
                    1 -> {
                        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            openContactPicker()
                        } else {
                            requestContactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                        }
                    }
                }
            }
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Deudores")
            .setMessage("• REGISTRO: Usa '+' para añadir clientes que te deben.\n" +
                    "• COBRAR: Toca el icono de WhatsApp para enviar un recordatorio automático.\n" +
                    "• PAGAR: Registra abonos o liquida deudas.\n" +
                    "• EDITAR: Toca el lápiz para corregir nombre o fecha.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadDebtors() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.debtorDao().getAllDebtors()
            }
            allDebtorsList = list
            applyFilters()
            
            val total = list.sumOf { it.deudaTotal }
            binding.tvTotalDeudaGlobal.text = String.format(Locale.getDefault(), "S/ %.2f", total)
        }
    }

    private fun showAddDebtorDialog(preName: String = "", prePhone: String = "", existingDebtor: DebtorEntity? = null) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (existingDebtor == null) "Agregar Deudor" else "Editar Deudor")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val etNombre = EditText(this).apply { 
            hint = "Nombre del cliente"
            setText(existingDebtor?.nombre ?: preName)
        }
        val etTelefono = EditText(this).apply { 
            hint = "Teléfono (Opcional)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(existingDebtor?.telefono ?: prePhone)
        }
        val etMonto = EditText(this).apply { 
            hint = "Monto de la deuda"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL 
            if (existingDebtor != null) setText(existingDebtor.deudaTotal.toString())
        }

        var selectedFechaCobro = existingDebtor?.fechaCobro ?: 0L
        val btnFechaCobro = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            text = if (selectedFechaCobro > 0) "📅 Cobrar el: ${sdf.format(Date(selectedFechaCobro))}" else "📅 Programar Fecha de Cobro"
            setOnClickListener {
                val cal = Calendar.getInstance()
                if (selectedFechaCobro > 0) cal.timeInMillis = selectedFechaCobro
                android.app.DatePickerDialog(this@DeudoresActivity, { _, y, m, d ->
                    val selCal = Calendar.getInstance()
                    selCal.set(y, m, d, 9, 0, 0)
                    selectedFechaCobro = selCal.timeInMillis
                    this.text = "📅 Cobrar el: ${sdf.format(Date(selectedFechaCobro))}"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 8)
            }
        }
        
        currentEtNombre = etNombre
        currentEtTelefono = etTelefono

        val btnImportContact = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cambiar/Importar de Contactos 👥"
            visibility = if (existingDebtor == null) View.VISIBLE else View.GONE
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@DeudoresActivity, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    openContactPicker()
                } else {
                    requestContactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                }
            }
        }

        layout.addView(btnImportContact)
        layout.addView(etNombre)
        layout.addView(etTelefono)
        layout.addView(etMonto)
        layout.addView(btnFechaCobro)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val telf = etTelefono.text.toString()
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            
            if (nombre.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val debtor = existingDebtor?.copy(
                        nombre = nombre,
                        telefono = telf,
                        deudaTotal = monto,
                        fechaCobro = selectedFechaCobro,
                        isSynced = false,
                        ultimaActualizacion = System.currentTimeMillis()
                    ) ?: DebtorEntity(nombre = nombre, telefono = telf, deudaTotal = monto, fechaCobro = selectedFechaCobro, isSynced = false)
                    
                    database.debtorDao().insertDebtor(debtor)
                    
                    val log = MovementLogEntity(
                        type = if (existingDebtor == null) "DEBTOR_ADDED" else "DEBTOR_UPDATED",
                        title = if (existingDebtor == null) "Nuevo Deudor" else "Deudor Editado",
                        description = "Cliente: $nombre",
                        value = "S/ ${String.format(Locale.getDefault(), "%.2f", monto)}",
                        colorHex = "#3B82F6",
                        iconRes = if (existingDebtor == null) android.R.drawable.ic_input_add else android.R.drawable.ic_menu_edit
                    )
                    database.movementLogDao().insert(log)
                    
                    val sm = SyncManager(this@DeudoresActivity)
                    sm.syncDebtorToCloud(debtor)
                    sm.syncLogToCloud(log)
                    sm.scheduleOfflineSync()
                    
                    withContext(Dispatchers.Main) { 
                        loadDebtors()
                        currentEtNombre = null
                        currentEtTelefono = null
                    }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        val dialog = builder.show()
        dialog.setOnDismissListener {
            currentEtNombre = null
            currentEtTelefono = null
        }
    }

    private fun showPaymentDialog(deudor: DebtorEntity) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Registrar Pago de ${deudor.nombre}")
        builder.setMessage("¿Cuánto va a pagar el cliente?\nSaldo actual: S/ ${deudor.deudaTotal}")
        
        val etMonto = EditText(this).apply { 
            hint = "Monto a pagar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        builder.setView(etMonto)

        builder.setPositiveButton("Pagar") { _, _ ->
            val pago = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (pago > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val nuevoTotal = deudor.deudaTotal - pago
                    val updated = if (nuevoTotal <= 0) {
                        deudor.copy(deudaTotal = 0.0, isDeleted = true, isSynced = false)
                    } else {
                        deudor.copy(deudaTotal = nuevoTotal, isSynced = false)
                    }
                    database.debtorDao().updateDebtor(updated)
                    
                    val log = MovementLogEntity(
                        type = "DEBTOR_PAYMENT",
                        title = "Pago de Deuda",
                        description = "Cliente: ${deudor.nombre}",
                        value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", pago)}",
                        colorHex = "#10B981",
                        iconRes = android.R.drawable.checkbox_on_background
                    )
                    database.movementLogDao().insert(log)
                    
                    val sm = SyncManager(this@DeudoresActivity)
                    sm.syncDebtorToCloud(updated)
                    sm.syncLogToCloud(log)
                    sm.scheduleOfflineSync()
                    
                    withContext(Dispatchers.Main) { loadDebtors() }
                }
            }
        }
        builder.setNeutralButton("Liquidar Total") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                val montoLiquidado = deudor.deudaTotal
                deudor.isDeleted = true
                deudor.isSynced = false
                database.debtorDao().updateDebtor(deudor)
                
                val log = MovementLogEntity(
                    type = "DEBTOR_PAYMENT",
                    title = "Deuda Liquidada",
                    description = "Cliente: ${deudor.nombre}",
                    value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", montoLiquidado)}",
                    colorHex = "#10B981",
                    iconRes = android.R.drawable.checkbox_on_background
                )
                database.movementLogDao().insert(log)
                
                val sm = SyncManager(this@DeudoresActivity)
                sm.syncLogToCloud(log)
                sm.scheduleOfflineSync()
                
                withContext(Dispatchers.Main) { loadDebtors() }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun showIncreaseDebtDialog(deudor: DebtorEntity) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Aumentar Deuda de ${deudor.nombre}")
        builder.setMessage("¿Cuánto dinero más se le va a fiar?")
        
        val etMonto = EditText(this).apply { 
            hint = "Monto a aumentar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        builder.setView(etMonto)

        builder.setPositiveButton("Aumentar") { _, _ ->
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (monto > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val nuevoTotal = deudor.deudaTotal + monto
                    val updated = deudor.copy(deudaTotal = nuevoTotal, isSynced = false)
                    database.debtorDao().updateDebtor(updated)
                    
                    val log = MovementLogEntity(
                        type = "DEBTOR_DEBT_INCREASE",
                        title = "Deuda Aumentada",
                        description = "Cliente: ${deudor.nombre}",
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", monto)}",
                        colorHex = "#F43F5E",
                        iconRes = android.R.drawable.ic_input_add
                    )
                    database.movementLogDao().insert(log)
                    
                    val sm = SyncManager(this@DeudoresActivity)
                    sm.syncDebtorToCloud(updated)
                    sm.syncLogToCloud(log)
                    sm.scheduleOfflineSync()
                    
                    withContext(Dispatchers.Main) { loadDebtors() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun sendWhatsAppReminder(deudor: DebtorEntity) {
        if (deudor.telefono.isBlank()) {
            Toast.makeText(this, "Este deudor no tiene teléfono registrado", Toast.LENGTH_SHORT).show()
            return
        }

        val bizName = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_name", "Naxor") ?: "Naxor"
        val montoStr = String.format("%.2f", deudor.deudaTotal)
        
        val mensaje = if (deudor.fechaCobro > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            "Hola *${deudor.nombre}*, te saluda *${bizName}*. 👋 Te escribo para recordarte tu saldo pendiente de *S/ $montoStr*. La fecha programada para el pago es el *${sdf.format(Date(deudor.fechaCobro))}*. ¡Gracias! 😊"
        } else {
            "Hola *${deudor.nombre}*, te saluda *${bizName}*. 👋 Te escribo para recordarte que tienes un saldo pendiente de *S/ $montoStr*. ¡Muchas gracias! 😊"
        }
        
        try {
            var cleanNumber = deudor.telefono.replace(" ", "").replace("-", "").replace("+", "")
            val finalNumber = if (cleanNumber.startsWith("51")) cleanNumber else "51$cleanNumber"
            val url = "https://api.whatsapp.com/send?phone=$finalNumber&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDebtorPdf(deudor: DebtorEntity) {
        val pdfFile = DebtorPdfGenerator(this).generateDebtorReport(deudor)
        if (pdfFile != null && pdfFile.exists()) {
            val intent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra("PDF_PATH", pdfFile.absolutePath)
                putExtra("GUEST_PHONE", deudor.telefono)
                putExtra("GUEST_NAME", deudor.nombre)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    }

    inner class DeudoresAdapter(
        private val onPagar: (DebtorEntity) -> Unit,
        private val onWhatsApp: (DebtorEntity) -> Unit,
        private val onAumentar: (DebtorEntity) -> Unit,
        private val onPdf: (DebtorEntity) -> Unit,
        private val onEdit: (DebtorEntity) -> Unit
    ) : RecyclerView.Adapter<DeudoresAdapter.ViewHolder>() {
        
        private var list: List<DebtorEntity> = emptyList()
        
        fun submitList(newList: List<DebtorEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemDeudorBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemDeudorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val d = list[position]
            holder.binding.tvDeudorNombre.text = d.nombre
            holder.binding.tvDeudorTelefono.text = if(d.telefono.isBlank()) "Sin número" else d.telefono
            holder.binding.tvDeudorMonto.text = String.format("S/ %.2f", d.deudaTotal)
            
            if (d.fechaCobro > 0) {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                holder.binding.tvFechaCobro.text = "Cobrar: ${sdf.format(Date(d.fechaCobro))}"
                holder.binding.tvFechaCobro.visibility = View.VISIBLE
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 23)
                if (d.fechaCobro <= cal.timeInMillis) {
                    holder.binding.tvFechaCobro.setTextColor(android.graphics.Color.RED)
                } else {
                    holder.binding.tvFechaCobro.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.purple_600))
                }
            } else {
                holder.binding.tvFechaCobro.visibility = View.GONE
            }
            
            holder.binding.btnPagarDeuda.setOnClickListener { onPagar(d) }
            holder.binding.btnCobrarWhatsApp.setOnClickListener { onWhatsApp(d) }
            holder.binding.btnAumentarDeuda.setOnClickListener { onAumentar(d) }
            holder.binding.btnGeneratePdf.setOnClickListener { onPdf(d) }
            holder.binding.btnEditDebtor.setOnClickListener { onEdit(d) }
        }

        override fun getItemCount() = list.size
    }
}
