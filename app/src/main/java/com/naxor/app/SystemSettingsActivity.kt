package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivitySystemSettingsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class SystemSettingsActivity : AppCompatActivity() {

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
        setupFaqAction()
        setupSupportAction()
        setupMaintenanceActions()
        setupClearDataActions()
    }

    private fun setupMaintenanceActions() {
        // Optimizar App
        binding.cardOptimizeApp.setOnClickListener {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    database.openHelper.writableDatabase.execSQL("VACUUM")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@SystemSettingsActivity, "¡Sistema optimizado y acelerado!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@SystemSettingsActivity, "Error al optimizar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Reiniciar Tutoriales
        binding.cardResetTutorials.setOnClickListener {
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("tutorial_shown_v3", false).apply()
            Toast.makeText(this, "Las guías aparecerán al volver al inicio", Toast.LENGTH_LONG).show()
        }

        // Mantener Pantalla Encendida
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        binding.switchKeepScreenOn.isChecked = prefs.getBoolean("keep_screen_on", false)
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keep_screen_on", checked).apply()
            updateScreenOnFlag(checked)
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
                    database.systemMaintenanceDao().clearHotelRooms()
                    database.systemMaintenanceDao().clearHotelBookings()
                    database.systemMaintenanceDao().clearHotelLayouts()
                    database.systemMaintenanceDao().clearHotelCharges()
                    database.systemMaintenanceDao().clearHotelPayments()
                    database.systemMaintenanceDao().clearHotelMaintenance()
                    database.systemMaintenanceDao().clearHotelTools()
                    Toast.makeText(this@SystemSettingsActivity, "Datos de Hotelería borrados", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.cardClearProducts.setOnClickListener {
            showClearConfirmation("Productos") {
                lifecycleScope.launch {
                    database.systemMaintenanceDao().clearProducts()
                    database.systemMaintenanceDao().clearSales()
                    database.systemMaintenanceDao().clearPriceHistory()
                    database.systemMaintenanceDao().clearFiados()
                    database.systemMaintenanceDao().clearDebtDetails()
                    Toast.makeText(this@SystemSettingsActivity, "Datos de Productos borrados", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.cardClearLoans.setOnClickListener {
            showClearConfirmation("Préstamos") {
                lifecycleScope.launch {
                    database.systemMaintenanceDao().clearLoanClients()
                    database.systemMaintenanceDao().clearLoans()
                    database.systemMaintenanceDao().clearLoanInstallments()
                    database.systemMaintenanceDao().clearLoanExpenses()
                    Toast.makeText(this@SystemSettingsActivity, "Datos de Préstamos borrados", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupBackupAction() {
        binding.cardBackupData.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val dbFile = getDatabasePath("asistente_comercial_db")
                    if (dbFile.exists()) {
                        val backupFile = File(getExternalFilesDir(null), "Respaldo_Naxor_${System.currentTimeMillis()}.db")
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
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
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

        val isDark = prefs.getBoolean("dark_mode", false)
        binding.switchDarkMode.isChecked = isDark
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_mode", checked).apply()
            if (checked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun setupFaqAction() {
        binding.cardHelpFaq.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Cómo funciona Naxor?")
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
}
