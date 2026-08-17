package com.naxor.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.naxor.app.databinding.ActivitySettingsBinding
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedLogoUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedLogoUri = it
            Glide.with(this).load(it).circleCrop().into(binding.ivBusinessLogo)
            binding.ivBusinessLogo.alpha = 1.0f
            binding.ivBusinessLogo.setPadding(0, 0, 0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()

        binding.btnBackSettings.setOnClickListener { finish() }
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.cardBusinessLogo.setOnClickListener { pickImageLauncher.launch("image/*") }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        binding.etBusinessName.setText(prefs.getString("business_name", "Mi Negocio"))
        binding.etBusinessAddress.setText(prefs.getString("business_address", ""))
        binding.etBusinessPhone.setText(prefs.getString("business_phone", ""))
        binding.etBusinessRuc.setText(prefs.getString("business_ruc", ""))
        binding.etCurrencySymbol.setText(prefs.getString("currency_symbol", "S/"))
        binding.etSecurityPin.setText(prefs.getString("user_pin", ""))
        binding.etApiToken.setText(prefs.getString("api_token", ""))

        val logoPath = prefs.getString("business_logo_local", null)
        if (!logoPath.isNullOrEmpty()) {
            val file = File(logoPath)
            if (file.exists()) {
                Glide.with(this).load(file).circleCrop().into(binding.ivBusinessLogo)
                binding.ivBusinessLogo.alpha = 1.0f
                binding.ivBusinessLogo.setPadding(0, 0, 0, 0)
            }
        }
    }

    private fun saveSettings() {
        val name = binding.etBusinessName.text.toString().trim()
        val address = binding.etBusinessAddress.text.toString().trim()
        val phone = binding.etBusinessPhone.text.toString().trim()
        val ruc = binding.etBusinessRuc.text.toString().trim()
        val currency = binding.etCurrencySymbol.text.toString().trim()
        val pin = binding.etSecurityPin.text.toString().trim()
        val token = binding.etApiToken.text.toString().trim()

        if (name.isNotEmpty() && currency.isNotEmpty()) {
            val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            
            // Guardar imagen localmente si se seleccionÃ³ una nueva
            var localPath = prefs.getString("business_logo_local", null)
            selectedLogoUri?.let { uri ->
                localPath = saveImageToInternalStorage(uri)
            }

            prefs.edit().apply {
                putString("business_name", name)
                putString("business_address", address)
                putString("business_phone", phone)
                putString("business_ruc", ruc)
                putString("currency_symbol", currency)
                putString("user_pin", pin)
                putString("api_token", token)
                putString("business_logo_local", localPath)
                apply()
            }
            
            // Sincronizar con la nube (Incluyendo intento de subida de imagen)
            SyncManager(this).syncBusinessSettingsToCloud()
            
            Toast.makeText(this, "Ajustes guardados correctamente", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, "business_logo.png")
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
