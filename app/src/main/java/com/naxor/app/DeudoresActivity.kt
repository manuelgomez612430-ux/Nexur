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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.DebtorEntity
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.databinding.ActivityDeudoresBinding
import com.naxor.app.databinding.ItemDeudorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.*

class DeudoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeudoresBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: DeudoresAdapter

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
                        // If dialog wasn't open, open it now with data
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
                color = androidx.core.content.ContextCompat.getColor(this, R.color.white)
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
            onAumentar = { deudor -> showIncreaseDebtDialog(deudor) }
        )
        binding.rvDeudores.layoutManager = LinearLayoutManager(this)
        binding.rvDeudores.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackDeudores.setOnClickListener { finish() }
        binding.btnHelpDeudores.setOnClickListener { showHelpDialog() }
        binding.fabAddDeudor.setOnClickListener { showAddOptions() }
    }

    private fun showAddOptions() {
        val options = arrayOf("Agregar Manualmente ✍️", "Importar de Contactos 👥")
        AlertDialog.Builder(this)
            .setTitle("Nuevo Deudor")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddDebtorDialog()
                    1 -> {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
                    "• COBRAR: Toca el icono de WhatsApp para enviar un recordatorio automático con el monto de la deuda.\n" +
                    "• PAGAR: Toca el icono de check para registrar un abono o liquidar la deuda total.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadDebtors() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.debtorDao().getAllDebtors()
            }
            adapter.submitList(list)
            binding.layoutEmptyDeudores.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            
            val total = list.sumOf { it.deudaTotal }
            binding.tvTotalDeudaGlobal.text = String.format(Locale.getDefault(), "Total por cobrar: S/ %.2f", total)
        }
    }

    private fun showAddDebtorDialog(preName: String = "", prePhone: String = "") {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Agregar Deudor")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val etNombre = EditText(this).apply { 
            hint = "Nombre del cliente"
            setText(preName)
        }
        val etTelefono = EditText(this).apply { 
            hint = "Teléfono (Opcional)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prePhone)
        }
        val etMonto = EditText(this).apply { 
            hint = "Monto inicial de la deuda"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL 
        }
        
        currentEtNombre = etNombre
        currentEtTelefono = etTelefono

        val btnImportContact = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cambiar/Importar de Contactos 👥"
            setOnClickListener {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@DeudoresActivity, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val telf = etTelefono.text.toString()
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            
            if (nombre.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val debtor = DebtorEntity(nombre = nombre, telefono = telf, deudaTotal = monto, isSynced = false)
                    database.debtorDao().insertDebtor(debtor)
                    
                    val log = MovementLogEntity(
                        type = "DEBTOR_ADDED",
                        title = "Nuevo Deudor",
                        description = "Cliente: $nombre",
                        value = "S/ ${String.format(Locale.getDefault(), "%.2f", monto)}",
                        colorHex = "#3B82F6", // Azul para deudores
                        iconRes = android.R.drawable.ic_input_add
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
        builder.setNegativeButton("Cancelar") { _, _ -> }
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
                        deudor.copy(isDeleted = true, isSynced = false)
                    } else {
                        deudor.copy(deudaTotal = nuevoTotal, isSynced = false)
                    }
                    database.debtorDao().updateDebtor(updated)
                    
                    val log = MovementLogEntity(
                        type = "DEBTOR_PAYMENT",
                        title = "Pago de Deuda",
                        description = "Cliente: ${deudor.nombre}",
                        value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", pago)}",
                        colorHex = "#10B981", // Esmeralda para ingresos
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
                        colorHex = "#F43F5E", // Rosa/Rojo para salidas/deudas
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

        val mensaje = "Hola ${deudor.nombre}, te saludo de *Naxor*. 👋 Te escribo para recordarte que tienes un saldo pendiente de *S/ ${String.format("%.2f", deudor.deudaTotal)}*. ¡Muchas gracias! 😊"
        
        try {
            val url = "https://api.whatsapp.com/send?phone=51${deudor.telefono}&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    inner class DeudoresAdapter(
        private val onPagar: (DebtorEntity) -> Unit,
        private val onWhatsApp: (DebtorEntity) -> Unit,
        private val onAumentar: (DebtorEntity) -> Unit
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
            
            holder.binding.btnPagarDeuda.setOnClickListener { onPagar(d) }
            holder.binding.btnCobrarWhatsApp.setOnClickListener { onWhatsApp(d) }
            holder.binding.btnAumentarDeuda.setOnClickListener { onAumentar(d) }
        }

        override fun getItemCount() = list.size
    }
}
