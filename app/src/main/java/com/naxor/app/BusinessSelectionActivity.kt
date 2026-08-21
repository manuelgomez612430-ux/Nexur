package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.naxor.app.databinding.ActivityBusinessSelectionBinding

class BusinessSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBusinessSelectionBinding
    private var selectedType = "PRODUCTS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusinessSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardProducts.setOnClickListener {
            selectedType = "PRODUCTS"
            binding.cardHotel.visibility = android.view.View.GONE
            updateUI()
        }

        binding.cardServices.setOnClickListener {
            selectedType = "SERVICES"
            binding.cardHotel.visibility = android.view.View.VISIBLE
            updateUI()
        }

        binding.cardHotel.setOnClickListener {
            selectedType = "HOTEL"
            updateUI()
        }

        binding.btnConfirmSelection.setOnClickListener {
            if (selectedType == "SERVICES") {
                android.widget.Toast.makeText(this, "⚠️ Selecciona una especialidad (Hotelería)", android.widget.Toast.LENGTH_LONG).show()
            } else {
                saveAndContinue()
            }
        }
        
        updateUI()
    }

    private fun updateUI() {
        binding.cardProducts.strokeWidth = if (selectedType == "PRODUCTS") 6 else 0
        binding.cardServices.strokeWidth = if (selectedType == "SERVICES") 6 else 0
        binding.cardHotel.strokeWidth = if (selectedType == "HOTEL") 6 else 0
    }

    private fun saveAndContinue() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("business_type", selectedType).apply()
        
        // Sincronizar con la nube
        SyncManager(this).syncBusinessSettingsToCloud()
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
