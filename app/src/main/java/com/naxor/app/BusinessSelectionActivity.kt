package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import com.naxor.app.databinding.ActivityBusinessSelectionBinding

class BusinessSelectionActivity : BaseActivity() {

    private lateinit var binding: ActivityBusinessSelectionBinding
    private var selectedType = ""
    private var currentStep = 1 // 1: Category, 2: Specialty

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusinessSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        setupListeners()
        updateUI()
    }

    private fun handleBackNavigation() {
        if (currentStep == 2) {
            goBackToStep1()
        } else {
            // Si es la actividad raíz (venimos del Splash o Login), permitir volver al selector de roles
            if (isTaskRoot) {
                startActivity(Intent(this, WelcomeRoleActivity::class.java))
            }
            finish()
        }
    }

    private fun setupListeners() {
        // STEP 1
        binding.cardProductsCategory.setOnClickListener {
            checkUserAndProceed {
                selectedType = "PRODUCTS"
                saveAndContinue()
            }
        }

        binding.cardServicesCategory.setOnClickListener {
            checkUserAndProceed {
                currentStep = 2
                updateUI()
            }
        }

        // STEP 2
        binding.cardLoans.setOnClickListener {
            selectedType = "LOANS"
            updateUI()
        }

        binding.cardHotel.setOnClickListener {
            selectedType = "HOTEL"
            updateUI()
        }

        binding.btnBackSelection.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnConfirmSelection.setOnClickListener {
            if (selectedType.isNotEmpty()) {
                saveAndContinue()
            }
        }
    }

    private fun checkUserAndProceed(onSuccess: () -> Unit) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (user == null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cuenta Obligatoria 💼")
                .setMessage("Para gestionar un negocio en GoMezMart es necesario crear una cuenta. Esto asegura que tus productos, préstamos y ventas estén siempre a salvo en la nube.")
                .setPositiveButton("Crear mi Cuenta") { _, _ ->
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.putExtra("FORCE_REGISTER", true)
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Luego", null)
                .show()
        } else {
            onSuccess()
        }
    }

    private fun goBackToStep1() {
        currentStep = 1
        selectedType = ""
        updateUI()
    }

    private fun updateUI() {
        if (currentStep == 1) {
            binding.layoutStep1.visibility = View.VISIBLE
            binding.layoutStep2Services.visibility = View.GONE
            binding.btnBackSelection.visibility = View.VISIBLE
            binding.btnConfirmSelection.visibility = View.GONE
            binding.tvSelectionTitle.text = "¿Qué vamos a gestionar hoy?"
            binding.tvSelectionSubtitle.text = "Selecciona el tipo de rubro para tu cuenta"
        } else {
            binding.layoutStep1.visibility = View.GONE
            binding.layoutStep2Services.visibility = View.VISIBLE
            binding.btnBackSelection.visibility = View.VISIBLE
            binding.btnConfirmSelection.visibility = View.VISIBLE
            binding.tvSelectionTitle.text = "Servicios Especializados"
            binding.tvSelectionSubtitle.text = "¿En qué área se especializa tu negocio?"
            
            // Highlight specialty cards
            binding.cardLoans.strokeWidth = if (selectedType == "LOANS") 6 else 0
            binding.cardHotel.strokeWidth = if (selectedType == "HOTEL") 6 else 0
        }
    }

    private fun saveAndContinue() {
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            proceedToMain()
            return
        }

        // Diálogo de verificación
        val loading = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Verificando datos en la nube...")
            .setCancelable(false)
            .show()

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val collectionName = when(selectedType) {
            "LOANS" -> "loans"
            "HOTEL" -> "hotel_rooms"
            else -> "inventory"
        }

        db.collection("users").document(userId).collection(collectionName).limit(1).get()
            .addOnSuccessListener { snapshot ->
                loading.dismiss()
                if (!snapshot.isEmpty) {
                    // Hay datos existentes, preguntar si desea restaurar
                    showRestoreDataDialog()
                } else {
                    // No hay datos, continuar normal
                    proceedToMain()
                }
            }
            .addOnFailureListener {
                loading.dismiss()
                proceedToMain()
            }
    }

    private fun showRestoreDataDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¡Datos Encontrados! ☁️")
            .setMessage("Hemos detectado información guardada en tu cuenta para este rubro. ¿Deseas restaurar tus datos ahora?")
            .setPositiveButton("Sí, Restaurar") { _, _ ->
                performFullSync()
            }
            .setNegativeButton("Empezar de cero") { _, _ ->
                proceedToMain()
            }
            .show()
    }

    private fun performFullSync() {
        val loading = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Descargando toda tu información...")
            .setCancelable(false)
            .show()

        SyncManager(this).downloadEverythingFromCloud {
            loading.dismiss()
            proceedToMain()
        }
    }

    private fun proceedToMain() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("business_type", selectedType).apply()
        
        SyncManager(this).syncBusinessSettingsToCloud()
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
