package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.ActivitySystemSettingsBinding
import com.gomezmart.app.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.*

class SystemSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySystemSettingsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSystemSettings.setNavigationOnClickListener { finish() }

        setupHealthStatus()
        setupBackupAction()
        setupRestoreAction()
        setupAppearanceActions()
        setupNotificationAction()
        setupFaqAction()
        setupSupportAction()
        setupTutorialActions()
        setupDangerZoneExpandable()
        setupClearDataActions()
    }

    private fun setupTutorialActions() {
        // Reiniciar Tutoriales
        binding.cardResetTutorials.setOnClickListener {
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("tutorial_shown_v5", false).apply()
            Toast.makeText(this, "Las guías aparecerán al volver al inicio", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupDangerZoneExpandable() {
        binding.cardHeaderDangerZone.setOnClickListener {
            val isVisible = binding.layoutClearDataOptions.visibility == android.view.View.VISIBLE
            binding.layoutClearDataOptions.visibility = if (isVisible) android.view.View.GONE else android.view.View.VISIBLE
            binding.ivArrowDangerZone.animate()
                .rotation(if (isVisible) 0f else 180f)
                .setDuration(300)
                .start()
        }
    }

    private fun setupNotificationAction() {
        binding.switchNotifications.isChecked = areNotificationsEnabled()
        
        binding.switchNotifications.setOnClickListener {
            if (binding.switchNotifications.isChecked) {
                requestNotificationPermission()
            } else {
                openAppNotificationSettings()
            }
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        return androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            
            when {
                androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    Toast.makeText(this, "Las notificaciones ya están activas", Toast.LENGTH_SHORT).show()
                    binding.switchNotifications.isChecked = true
                }
                else -> {
                    // Pedir el permiso oficialmente al sistema
                    androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
                }
            }
        } else {
            // En versiones anteriores a Android 13
            if (!areNotificationsEnabled()) {
                showSettingsRationale()
            } else {
                Toast.makeText(this, "Las notificaciones ya están activas", Toast.LENGTH_SHORT).show()
                binding.switchNotifications.isChecked = true
            }
        }
    }

    private fun showSettingsRationale() {
        AlertDialog.Builder(this)
            .setTitle("Activar Notificaciones")
            .setMessage("Para recibir alertas de cobro, debes permitir las notificaciones desde los ajustes de tu teléfono.")
            .setPositiveButton("Ir a Ajustes") { _, _ -> openAppNotificationSettings() }
            .setNegativeButton("Cancelar") { _, _ -> binding.switchNotifications.isChecked = false }
            .setCancelable(false)
            .show()
    }

    private fun openAppNotificationSettings() {
        val intent = Intent().apply {
            when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
                    action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                }
                else -> {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("app_package", packageName)
                    putExtra("app_uid", applicationInfo.uid)
                }
            }
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (areNotificationsEnabled()) {
                binding.switchNotifications.isChecked = true
                Toast.makeText(this, "✅ Notificaciones activadas", Toast.LENGTH_SHORT).show()
            } else {
                // El permiso fue denegado o el diálogo no se mostró (denegación permanente).
                // Mostramos un aviso propio para que el usuario sepa que DEBE ir a ajustes.
                showSettingsRationale()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Actualizar el estado del switch al volver de los ajustes del sistema
        if (::binding.isInitialized) {
            binding.switchNotifications.isChecked = areNotificationsEnabled()
        }
    }

    private fun updateScreenOnFlag(keepOn: Boolean) {
        if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupHealthStatus() {
        val dbFile = getDatabasePath("asistente_comercial_db")
        if (dbFile.exists()) {
            val sizeMB = dbFile.length() / (1024.0 * 1024.0)
            binding.tvStorageUsage.text = "Espacio usado: ${String.format(Locale.US, "%.2f", sizeMB)} MB"
        }
    }

    private fun setupSupportAction() {
        binding.btnCallSupport.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:+51987654321"))
            startActivity(intent)
        }
    }

    private fun setupClearDataActions() {
        binding.cardClearHotel.setOnClickListener {
            showClearConfirmation("Hotelería") {
                lifecycleScope.launch {
                    val loading = AlertDialog.Builder(this@SystemSettingsActivity).setMessage("Borrando datos del Hotel...").setCancelable(false).show()
                    
                    // 1. Borrar en la nube
                    val syncManager = SyncManager(this@SystemSettingsActivity)
                    val hotelCollections = listOf("hotel_rooms", "hotel_bookings", "hotel_room_layouts", "hotel_charges", "hotel_payments", "hotel_maintenance")
                    hotelCollections.forEach { syncManager.deleteCollectionFromCloud(it) }

                    // 2. Borrar localmente
                    database.systemMaintenanceDao().clearHotelRooms()
                    database.systemMaintenanceDao().clearHotelBookings()
                    database.systemMaintenanceDao().clearHotelLayouts()
                    database.systemMaintenanceDao().clearHotelCharges()
                    database.systemMaintenanceDao().clearHotelPayments()
                    database.systemMaintenanceDao().clearHotelMaintenance()
                    database.systemMaintenanceDao().clearHotelTools()
                    
                    // 3. Limpiar configuraciones
                    val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                    prefs.edit().apply {
                        val keysToRemove = listOf(
                            "HOTEL_name", "HOTEL_ruc", "HOTEL_address", "HOTEL_phone", 
                            "HOTEL_owner_name", "HOTEL_owner_dni", "HOTEL_show_owner", 
                            "HOTEL_show_business", "HOTEL_show_address", "HOTEL_show_phone",
                            "HOTEL_description", "HOTEL_cover_local", "HOTEL_logo_local",
                            "HOTEL_category"
                        )
                        keysToRemove.forEach { remove(it) }
                        
                        if (prefs.getString("business_type", "") == "HOTEL") {
                            putString("business_name", "")
                            putString("business_address", "")
                            putString("business_phone", "")
                            putString("business_ruc", "")
                            putString("business_description", "")
                            putString("business_owner_name", "")
                            putString("business_category", "")
                            putFloat("business_capital", 0f)
                            putBoolean("is_online_store", false)
                        }
                        apply()
                    }
                    
                    // Sincronizar reseteo a la nube y BORRAR el documento para evitar duplicados vacíos
                    try {
                        val userId = FirebaseAuth.getInstance().currentUser?.uid
                        if (userId != null) {
                            FirebaseFirestore.getInstance()
                                .collection("users").document(userId)
                                .collection("settings").document("HOTEL")
                                .delete().await()
                        }
                    } catch (e: Exception) { }

                    getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("HotelPrefs", MODE_PRIVATE).edit().clear().apply()
                    
                    loading.dismiss()
                    Toast.makeText(this@SystemSettingsActivity, "Sistema de Hotelería eliminado por completo 🗑️", Toast.LENGTH_LONG).show()
                    
                    handleSystemExit()
                }
            }
        }

        binding.cardClearProducts.setOnClickListener {
            showClearConfirmation("Productos y Ventas") {
                lifecycleScope.launch {
                    val loading = AlertDialog.Builder(this@SystemSettingsActivity).setMessage("Borrando inventario y ventas...").setCancelable(false).show()

                    // 1. Borrar en la nube
                    val syncManager = SyncManager(this@SystemSettingsActivity)
                    val productCollections = listOf("inventory", "sales", "price_history", "debtors", "debt_details", "cash_sessions")
                    productCollections.forEach { syncManager.deleteCollectionFromCloud(it) }

                    // 2. Borrar localmente
                    database.systemMaintenanceDao().clearProducts()
                    database.systemMaintenanceDao().clearSales()
                    database.systemMaintenanceDao().clearPriceHistory()
                    database.systemMaintenanceDao().clearFiados()
                    database.systemMaintenanceDao().clearDebtDetails()
                    database.systemMaintenanceDao().clearLogs()
                    database.systemMaintenanceDao().clearCashSessions()
                    
                    // 3. Limpiar configuraciones
                    val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                    val rubros = listOf("PRODUCTS", "SERVICES")
                    
                    // 4. Borrar documentos en la nube
                    try {
                        val userId = FirebaseAuth.getInstance().currentUser?.uid
                        if (userId != null) {
                            val db = FirebaseFirestore.getInstance()
                            rubros.forEach { r ->
                                db.collection("users").document(userId)
                                    .collection("settings").document(r)
                                    .delete().await()
                            }
                        }
                    } catch (e: Exception) { }

                    prefs.edit().apply {
                        rubros.forEach { r ->
                            val keys = listOf(
                                "name", "ruc", "address", "phone", "owner_name", "owner_dni", 
                                "show_owner", "show_business", "show_address", "show_phone",
                                "description", "cover_local", "logo_local", "category"
                            )
                            keys.forEach { remove("${r}_$it") }
                        }
                        
                        val current = prefs.getString("business_type", "")
                        if (current == "PRODUCTS" || current == "SERVICES") {
                            putString("business_name", "")
                            putString("business_address", "")
                            putString("business_phone", "")
                            putString("business_ruc", "")
                            putString("business_description", "")
                            putString("business_owner_name", "")
                            putString("business_category", "")
                            putFloat("business_capital", 0f)
                            putBoolean("is_online_store", false)
                        }
                        apply()
                    }
                    
                    loading.dismiss()
                    Toast.makeText(this@SystemSettingsActivity, "Sistema de Productos eliminado ✅", Toast.LENGTH_SHORT).show()
                    
                    handleSystemExit()
                }
            }
        }

        binding.cardClearLoans.setOnClickListener {
            showClearConfirmation("Préstamos") {
                lifecycleScope.launch {
                    val loading = AlertDialog.Builder(this@SystemSettingsActivity).setMessage("Borrando cartera de préstamos...").setCancelable(false).show()

                    // 1. Borrar en la nube
                    val syncManager = SyncManager(this@SystemSettingsActivity)
                    val loanCollections = listOf("loan_clients", "loans", "loan_installments", "loan_expenses_system", "loan_payments", "loan_budgets", "commitments")
                    
                    loanCollections.forEach { coll ->
                        syncManager.deleteCollectionFromCloud(coll)
                    }

                    // 2. Borrar localmente
                    database.systemMaintenanceDao().clearLoanClients()
                    database.systemMaintenanceDao().clearLoans()
                    database.systemMaintenanceDao().clearLoanInstallments()
                    database.systemMaintenanceDao().clearLoanExpenses()
                    database.systemMaintenanceDao().clearLoanPayments()
                    database.systemMaintenanceDao().clearLoanBudgets()
                    database.systemMaintenanceDao().clearCommitments()
                    
                    // 3. Borrar documento en la nube
                    try {
                        val userId = FirebaseAuth.getInstance().currentUser?.uid
                        if (userId != null) {
                            FirebaseFirestore.getInstance()
                                .collection("users").document(userId)
                                .collection("settings").document("LOANS")
                                .delete().await()
                        }
                    } catch (e: Exception) { }

                    // 4. Limpiar configuraciones personales del rubro Préstamos
                    val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                    prefs.edit().apply {
                        val keysToRemove = listOf(
                            "LOANS_name", "LOANS_ruc", "LOANS_address", "LOANS_phone",
                            "LOANS_owner_name", "LOANS_owner_dni", "LOANS_show_owner",
                            "LOANS_show_business", "LOANS_show_address", "LOANS_show_phone",
                            "LOANS_description", "LOANS_cover_local", "LOANS_logo_local",
                            "LOANS_category"
                        )
                        keysToRemove.forEach { remove(it) }
                        
                        // Si el sistema actual es Préstamos, limpiar los campos globales visibles
                        if (prefs.getString("business_type", "") == "LOANS") {
                            putString("business_name", "")
                            putString("business_address", "")
                            putString("business_phone", "")
                            putString("business_ruc", "")
                            putString("business_description", "")
                            putString("business_owner_name", "")
                            putString("business_category", "")
                            putFloat("business_capital", 0f)
                            putBoolean("is_online_store", false)
                        }
                        apply()
                    }
                    
                    loading.dismiss()
                    Toast.makeText(this@SystemSettingsActivity, "Sistema de Préstamos eliminado ✅", Toast.LENGTH_SHORT).show()
                    
                    handleSystemExit()
                }
            }
        }

        binding.cardClearEverything.setOnClickListener {
            showClearEverythingConfirmation()
        }
    }

    private fun showClearEverythingConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ ¡ATENCIÓN: BORRADO TOTAL!")
            .setMessage("Esta acción eliminará ABSOLUTAMENTE TODOS los datos del sistema y de la NUBE (Productos, Préstamos, Hotel, Clientes y Configuraciones). ¿Estás seguro?")
            .setPositiveButton("Sí, Borrar TODO") { _, _ ->
                lifecycleScope.launch {
                    val loading = AlertDialog.Builder(this@SystemSettingsActivity)
                        .setMessage("Limpiando cuenta en la nube y local...")
                        .setCancelable(false)
                        .show()

                    // 1. Borrar en la nube de forma asíncrona pero esperar el fin
                    com.gomezmart.app.SyncManager(this@SystemSettingsActivity).deleteEverythingFromCloud {
                        lifecycleScope.launch {
                            // 2. Limpiar local
                            SessionManager.clearSessionData(this@SystemSettingsActivity)
                            
                            loading.dismiss()
                            Toast.makeText(this@SystemSettingsActivity, "Sistema reseteado a cero ✅", Toast.LENGTH_LONG).show()
                            restartApp()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun setupBackupAction() {
        binding.cardBackupData.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val dbFile = getDatabasePath("asistente_comercial_db")
                    if (dbFile.exists()) {
                        val backupFile = File(getExternalFilesDir(null), "Respaldo_Gomezmart_${System.currentTimeMillis()}.db")
                        dbFile.copyTo(backupFile, overwrite = true)
                        
                        val uri = FileProvider.getUriForFile(this@SystemSettingsActivity, "$packageName.provider", backupFile)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Guardar Respaldo en WhatsApp"))
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SystemSettingsActivity, "Error al crear respaldo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val restoreFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { restoreFromUri(it) }
    }

    private fun setupRestoreAction() {
        binding.cardRestoreData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🔄 ¿Restaurar información?")
                .setMessage("Se reemplazarán todos los datos actuales por los del archivo de respaldo. ¿Deseas continuar?")
                .setPositiveButton("Seleccionar Archivo") { _, _ -> restoreFileLauncher.launch("application/octet-stream") }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun restoreFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dbFile = getDatabasePath("asistente_comercial_db")
                contentResolver.openInputStream(uri)?.use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SystemSettingsActivity, "¡Datos restaurados! Reiniciando...", Toast.LENGTH_LONG).show()
                    val intent = Intent(this@SystemSettingsActivity, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SystemSettingsActivity, "Error al restaurar archivo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupAppearanceActions() {
        // Mantener Pantalla Encendida
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        binding.switchKeepScreenOn.isChecked = prefs.getBoolean("keep_screen_on", false)
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keep_screen_on", checked).apply()
            updateScreenOnFlag(checked)
        }

        val savedSize = prefs.getFloat("font_scale", 1.0f)
        when(savedSize) {
            0.85f -> binding.toggleTextSize.check(R.id.btnTextSmall)
            1.15f -> binding.toggleTextSize.check(R.id.btnTextLarge)
            else -> binding.toggleTextSize.check(R.id.btnTextNormal)
        }

        binding.toggleTextSize.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val scale = when(checkedId) {
                    R.id.btnTextSmall -> 0.85f
                    R.id.btnTextLarge -> 1.15f
                    else -> 1.0f
                }
                prefs.edit().putFloat("font_scale", scale).apply()
                Toast.makeText(this, "El tamaño cambiará al reiniciar la app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFaqAction() {
        binding.cardHelpFaq.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Cómo funciona Gomezmart?")
                .setMessage("1. ¿Cómo cobro?\nVe a 'Cobros del día', toca en un cliente y presiona 'Pagar'.\n\n2. ¿Cómo registro gastos?\nUsa el botón 'Gastos Ruta' en el inicio para anotar gasolina o pasajes.\n\n3. ¿Mis datos están seguros?\nSí, se guardan en tu celular y en la nube. ¡Usa la Copia de Seguridad para estar más tranquilo!")
                .setPositiveButton("Entendido", null)
                .show()
        }
    }

    private fun showClearConfirmation(systemName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Confirmar Eliminación")
            .setMessage("¿Estás seguro de que deseas borrar TODOS los datos de $systemName? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, Borrar todo") { _, _ -> onConfirm() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun handleSystemExit() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
        // Limpiar el rubro activo localmente para forzar selección
        prefs.edit().remove("business_type").apply()

        lifecycleScope.launch {
            try {
                val userId = FirebaseAuth.getInstance().currentUser?.uid
                if (userId != null) {
                    val db = FirebaseFirestore.getInstance()
                    val settingsSnap = db.collection("users").document(userId).collection("settings").get().await()
                    
                    // Contar negocios válidos (que no sean el genérico y tengan nombre)
                    val ownBusinesses = settingsSnap.documents.filter { doc: DocumentSnapshot ->
                        if (doc.id == "business") return@filter false
                        val name = doc.getString("business_name") ?: doc.getString("${doc.id}_name") ?: ""
                        name.isNotEmpty() && !name.equals("Mi Negocio", ignoreCase = true)
                    }

                    if (ownBusinesses.isEmpty()) {
                        // No le quedan negocios: Volver a ser comprador por defecto
                        appPrefs.edit().putString("user_role", "BUYER").apply()
                        val intent = Intent(this@SystemSettingsActivity, BuyerHomeActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        // Le quedan otros negocios: Ir a la pantalla de "Mis Sistemas" para que elija uno
                        val intent = Intent(this@SystemSettingsActivity, MyBusinessesActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                } else {
                    restartApp()
                }
            } catch (e: Exception) {
                // Fallback de seguridad: Ir al selector de rol
                val intent = Intent(this@SystemSettingsActivity, WelcomeRoleActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }
}
