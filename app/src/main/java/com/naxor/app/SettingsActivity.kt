package com.naxor.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.naxor.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()

        binding.btnBackSettings.setOnClickListener { finish() }
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        binding.etBusinessName.setText(prefs.getString("business_name", "Mi Negocio"))
        binding.etBusinessAddress.setText(prefs.getString("business_address", ""))
        binding.etBusinessPhone.setText(prefs.getString("business_phone", ""))
        binding.etBusinessRuc.setText(prefs.getString("business_ruc", ""))
        binding.etCurrencySymbol.setText(prefs.getString("currency_symbol", "S/"))
        binding.etSecurityPin.setText(prefs.getString("user_pin", ""))
    }

    private fun saveSettings() {
        val name = binding.etBusinessName.text.toString().trim()
        val address = binding.etBusinessAddress.text.toString().trim()
        val phone = binding.etBusinessPhone.text.toString().trim()
        val ruc = binding.etBusinessRuc.text.toString().trim()
        val currency = binding.etCurrencySymbol.text.toString().trim()
        val pin = binding.etSecurityPin.text.toString().trim()

        if (name.isNotEmpty() && currency.isNotEmpty()) {
            val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("business_name", name)
                putString("business_address", address)
                putString("business_phone", phone)
                putString("business_ruc", ruc)
                putString("currency_symbol", currency)
                putString("user_pin", pin)
                apply()
            }
            Toast.makeText(this, "Ajustes guardados correctamente", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
        }
    }
}
