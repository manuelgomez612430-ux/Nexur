package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.gomezmart.app.databinding.ActivitySettingsBinding
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedLogoUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedLogoUri = it
            showSelectedLogo(it)
            Toast.makeText(this, "Foto seleccionada 📸", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSelectedLogo(uri: Any) {
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(binding.ivBusinessLogo)
        
        binding.ivBusinessLogo.alpha = 1.0f
        binding.ivBusinessLogo.setPadding(0, 0, 0, 0) // Quitar padding para que se vea redondeado completo
        binding.ivBusinessLogo.imageTintList = null
    }

    private fun showPlaceholderLogo() {
        binding.ivBusinessLogo.setImageResource(android.R.drawable.ic_menu_gallery)
        binding.ivBusinessLogo.alpha = 0.5f
        val padding = (40 * resources.displayMetrics.density).toInt()
        binding.ivBusinessLogo.setPadding(padding, padding, padding, padding)
        binding.ivBusinessLogo.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.slate_400))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()

        binding.toolbarSettings.setNavigationOnClickListener { finish() }
        binding.btnSaveSettingsToolbar.setOnClickListener { saveSettings() }
        
        val pickAction = { pickImageLauncher.launch("image/*") }
        binding.cardBusinessLogo.setOnClickListener { pickAction() }
        binding.btnChangeLogoFab.setOnClickListener { pickAction() }
        
        binding.btnInfoCapital.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Qué es el Capital?")
                .setMessage("Es el dinero total que has invertido en tu negocio o el dinero que tienes disponible para trabajar.")
                .setPositiveButton("Entendido", null).show()
        }

    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        // --- REDISEÑO CONTEXTUAL PREMIUM ---
        val systemLabel = when(businessType) {
            "HOTEL" -> "Hospedaje"
            "LOANS" -> "Préstamos"
            else -> "Negocio"
        }
        
        binding.toolbarSettings.title = ""
        binding.txtToolbarTitle.text = "Configuración de Negocio Virtual"
        binding.tvSystemTypeLabel.text = "PANEL DE CONFIGURACIÓN PÚBLICA: $systemLabel"
        
        // Cargar datos EXCLUSIVOS
        binding.etBusinessName.setText(prefs.getString("${businessType}_name", ""))
        binding.etBusinessDescription.setText(prefs.getString("${businessType}_description", ""))
        binding.etBusinessRuc.setText(prefs.getString("${businessType}_ruc", ""))
        binding.etBusinessAddress.setText(prefs.getString("${businessType}_address", ""))
        binding.etBusinessPhone.setText(prefs.getString("${businessType}_phone", ""))
        
        binding.etOwnerName.setText(prefs.getString("${businessType}_owner_name", ""))
        binding.etOwnerDni.setText(prefs.getString("${businessType}_owner_dni", ""))
        
        // Estos se mantienen globales o automáticos
        binding.etCurrencySymbol.setText(prefs.getString("${businessType}_currency", "S/"))
        binding.etSecurityPin.setText(prefs.getString("user_pin", ""))
        binding.switchPublishStore.isChecked = prefs.getBoolean("${businessType}_is_published", false)

        val logoPath = prefs.getString("${businessType}_logo_local", null)
        if (!logoPath.isNullOrEmpty()) {
            val file = File(logoPath)
            if (file.exists()) showSelectedLogo(file)
            else showPlaceholderLogo()
        } else {
            showPlaceholderLogo()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        val name = binding.etBusinessName.text.toString().trim()
        val description = binding.etBusinessDescription.text.toString().trim()
        val ruc = binding.etBusinessRuc.text.toString().trim()
        val address = binding.etBusinessAddress.text.toString().trim()
        val phone = binding.etBusinessPhone.text.toString().trim()
        
        val ownerName = binding.etOwnerName.text.toString().trim()
        val ownerDni = binding.etOwnerDni.text.toString().trim()
        
        val currency = binding.etCurrencySymbol.text.toString().trim()
        val pin = binding.etSecurityPin.text.toString().trim()
        val isPublished = binding.switchPublishStore.isChecked

        if (name.isNotEmpty()) {
            
            var localPath = prefs.getString("${businessType}_logo_local", null)
            selectedLogoUri?.let { uri ->
                localPath = saveImageToInternalStorage(uri, businessType)
            }

            prefs.edit().apply {
                // Guardar en el perfil EXCLUSIVO del sistema actual
                putString("${businessType}_name", name)
                putString("${businessType}_description", description)
                putString("${businessType}_ruc", ruc)
                putString("${businessType}_address", address)
                putString("${businessType}_phone", phone)
                putString("${businessType}_owner_name", ownerName)
                putString("${businessType}_owner_dni", ownerDni)
                putString("${businessType}_currency", currency)
                putBoolean("${businessType}_is_published", isPublished)
                putString("${businessType}_logo_local", localPath)

                // Sincronizar campos globales
                putString("business_name", name)
                putString("business_address", address)
                putString("business_phone", phone)
                putString("business_ruc", ruc)
                putString("currency_symbol", currency)
                putBoolean("is_online_store", isPublished)
                putString("user_pin", pin)
                
                apply()
            }
            
            // Sincronizar con la nube
            SyncManager(this).syncBusinessSettingsToCloud()
            
            Toast.makeText(this, "¡Perfil de negocio actualizado con éxito! ✨", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Por favor, cuéntanos el nombre de tu negocio para poder continuar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToInternalStorage(uri: Uri, type: String): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, "logo_${type.lowercase()}.png")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
