package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import com.gomezmart.app.databinding.ActivityBusinessSelectionBinding

class BusinessSelectionActivity : BaseActivity() {

    private lateinit var binding: ActivityBusinessSelectionBinding
    private var selectedType = ""
    private var currentStep = 1 // 1: Category, 2: Specialty

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusinessSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.root, applyTop = true, applyBottom = true)

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
        if (isGuestUser()) {
            AlertDialog.Builder(this)
                .setTitle("Acceso a Sistema en Modo Invitado ⚡")
                .setMessage("Estás intentando entrar a un sistema en modo invitado. Podrás usarlos, pero tus datos se guardarán en tu teléfono y no tendrás acceso a copia de seguridad, sincronización con tus otros dispositivos, ni podrás vender en el mercado en línea.")
                .setPositiveButton("Iniciar Sesión") { _, _ ->
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.putExtra("FORCE_REGISTER", true)
                    startActivity(intent)
                    finish()
                }
                .setNeutralButton("Entrar de todas maneras") { _, _ ->
                    onSuccess()
                }
                .setNegativeButton("Cerrar", null)
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
            val isLoans = selectedType == "LOANS"
            val isHotel = selectedType == "HOTEL"

            val purpleColor = androidx.core.content.ContextCompat.getColor(this, R.color.purple_600)
            val slateBorderColor = androidx.core.content.ContextCompat.getColor(this, R.color.slate_200)

            binding.cardLoans.setStrokeColor(android.content.res.ColorStateList.valueOf(if (isLoans) purpleColor else slateBorderColor))
            binding.cardLoans.strokeWidth = if (isLoans) (resources.displayMetrics.density * 2.5f).toInt() else (resources.displayMetrics.density * 1.5f).toInt()
            binding.cardLoans.cardElevation = if (isLoans) resources.displayMetrics.density * 6f else resources.displayMetrics.density * 2f
            binding.ivCheckLoans.visibility = if (isLoans) View.VISIBLE else View.GONE

            binding.cardHotel.setStrokeColor(android.content.res.ColorStateList.valueOf(if (isHotel) purpleColor else slateBorderColor))
            binding.cardHotel.strokeWidth = if (isHotel) (resources.displayMetrics.density * 2.5f).toInt() else (resources.displayMetrics.density * 1.5f).toInt()
            binding.cardHotel.cardElevation = if (isHotel) resources.displayMetrics.density * 6f else resources.displayMetrics.density * 2f
            binding.ivCheckHotel.visibility = if (isHotel) View.VISIBLE else View.GONE
        }
    }

    private fun saveAndContinue() {
        ensureModuleInstalled(selectedType) {
            if (isGuestUser()) {
                proceedToMain()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Cambio de Perfil ⚠️")
                    .setMessage("Al entrar en un sistema de gestión, tu cuenta cambiará al rol de 'Dueño de Negocio'.\n\nEsto aplicará cambios automáticos en tu interfaz y habilitará funciones administrativas. Ten en cuenta que estamos optimizando el sistema y corrigiendo posibles errores en cada actualización.\n\n¿Deseas proceder?")
                    .setPositiveButton("Sí, Aceptar") { _, _ ->
                        checkCloudDataAndProceed()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun checkCloudDataAndProceed() {
        if (isGuestUser()) {
            proceedToMain()
            return
        }
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
        com.gomezmart.app.util.SessionManager.setLoginInProgress(this, false)
        if (!isGuestUser()) {
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            appPrefs.edit()
                .putString("user_role", "SELLER")
                .putBoolean("is_profile_verified", true)
                .apply()
        }

        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("business_type", selectedType).apply()
        
        if (!isGuestUser()) {
            SyncManager(this).syncBusinessSettingsToCloud()
        }
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
