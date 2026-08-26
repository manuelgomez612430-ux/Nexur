package com.naxor.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

        binding.toolbarSettings.setNavigationOnClickListener { finish() }
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.cardBusinessLogo.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnInfoCapital.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Qué es el Capital?")
                .setMessage("Es el dinero total que has invertido en tu negocio o el dinero que tienes disponible para trabajar.")
                .setPositiveButton("Entendido", null).show()
        }
        binding.btnSupportCreator.setOnClickListener {
            startActivity(Intent(this, SupportCreatorActivity::class.java))
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        binding.etBusinessName.setText(prefs.getString("business_name", "Mi Negocio"))
        binding.etBusinessAddress.setText(prefs.getString("business_address", ""))
        binding.etBusinessPhone.setText(prefs.getString("business_phone", ""))
        binding.etBusinessRuc.setText(prefs.getString("business_ruc", ""))
        binding.etCurrencySymbol.setText(prefs.getString("currency_symbol", "S/"))
        binding.etBusinessCapital.setText(prefs.getFloat("business_capital", 0f).toString())
        binding.etSecurityPin.setText(prefs.getString("user_pin", ""))
        binding.etApiToken.setText(prefs.getString("api_token", ""))

        val businessType = prefs.getString("business_type", "PRODUCTS")
        when(businessType) {
            "PRODUCTS" -> {
                binding.toggleBusinessType.check(R.id.btnTypeProducts)
                binding.layoutHotelOption.visibility = android.view.View.GONE
            }
            "SERVICES", "HOTEL", "LOANS" -> {
                binding.toggleBusinessType.check(R.id.btnTypeServices)
                binding.layoutHotelOption.visibility = android.view.View.VISIBLE
                if (businessType == "HOTEL") {
                    binding.btnSelectHotel.setBackgroundColor(getColor(R.color.purple_600))
                    binding.btnSelectHotel.setTextColor(getColor(R.color.white))
                    binding.btnSelectHotel.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
                } else if (businessType == "LOANS") {
                    binding.btnSelectLoans.setBackgroundColor(getColor(R.color.purple_600))
                    binding.btnSelectLoans.setTextColor(getColor(R.color.white))
                    binding.btnSelectLoans.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
                }
            }
        }

        binding.toggleBusinessType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTypeServices) {
                    binding.layoutHotelOption.visibility = android.view.View.VISIBLE
                } else {
                    binding.layoutHotelOption.visibility = android.view.View.GONE
                    // Resetear selección al cambiar a Productos
                    resetSpecialtyButtons()
                }
                binding.btnChangeSystem.visibility = android.view.View.VISIBLE
            }
        }

        binding.btnSelectHotel.setOnClickListener {
            selectSpecialty("HOTEL", binding.btnSelectHotel)
        }

        binding.btnSelectLoans.setOnClickListener {
            selectSpecialty("LOANS", binding.btnSelectLoans)
        }

        binding.btnChangeSystem.setOnClickListener {
            saveSettings()
        }

        binding.btnChangeSystem.setOnClickListener {
            saveSettings()
        }

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

    private fun selectSpecialty(type: String, button: com.google.android.material.button.MaterialButton) {
        resetSpecialtyButtons()
        button.setTag(R.id.btnSelectHotel, "SELECTED") // Reusamos tag para validación
        button.setBackgroundColor(getColor(R.color.purple_600))
        button.setTextColor(getColor(R.color.white))
        button.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
        binding.btnChangeSystem.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Rubro seleccionado: ${button.text}", Toast.LENGTH_SHORT).show()
    }

    private fun resetSpecialtyButtons() {
        val buttons = listOf(binding.btnSelectHotel, binding.btnSelectLoans)
        buttons.forEach { btn ->
            btn.setTag(R.id.btnSelectHotel, null)
            btn.setBackgroundColor(getColor(android.R.color.transparent))
            btn.setTextColor(getColor(R.color.slate_900))
            btn.iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.purple_600))
        }
    }

    private fun saveSettings() {
        val name = binding.etBusinessName.text.toString().trim()
        val address = binding.etBusinessAddress.text.toString().trim()
        val phone = binding.etBusinessPhone.text.toString().trim()
        val ruc = binding.etBusinessRuc.text.toString().trim()
        val currency = binding.etCurrencySymbol.text.toString().trim()
        val capital = binding.etBusinessCapital.text.toString().toFloatOrNull() ?: 0f
        val pin = binding.etSecurityPin.text.toString().trim()
        val token = binding.etApiToken.text.toString().trim()
        
        val isHotelSelected = binding.btnSelectHotel.getTag(R.id.btnSelectHotel) == "SELECTED"
        val isLoansSelected = binding.btnSelectLoans.getTag(R.id.btnSelectHotel) == "SELECTED"
        
        // Validación: Si elige Servicios, debe elegir una especialidad
        if (binding.toggleBusinessType.checkedButtonId == R.id.btnTypeServices && !isHotelSelected && !isLoansSelected) {
            Toast.makeText(this, "⚠️ Debes seleccionar una especialidad (ej: Hotelería o Préstamos)", Toast.LENGTH_LONG).show()
            return
        }

        val newType = when {
            binding.toggleBusinessType.checkedButtonId == R.id.btnTypeProducts -> "PRODUCTS"
            isHotelSelected && binding.toggleBusinessType.checkedButtonId == R.id.btnTypeServices -> "HOTEL"
            isLoansSelected && binding.toggleBusinessType.checkedButtonId == R.id.btnTypeServices -> "LOANS"
            else -> "SERVICES"
        }

        if (name.isNotEmpty() && currency.isNotEmpty()) {
            val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            val oldType = prefs.getString("business_type", "PRODUCTS")
            
            // Guardar imagen localmente si se seleccionó una nueva
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
                putFloat("business_capital", capital)
                putString("user_pin", pin)
                putString("api_token", token)
                putString("business_type", newType)
                putString("business_logo_local", localPath)
                apply()
            }
            
            // Sincronizar con la nube
            SyncManager(this).syncBusinessSettingsToCloud()
            
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()

            if (oldType != newType) {
                // Reiniciar App para aplicar cambios de interfaz radicalmente
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
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
