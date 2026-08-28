package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.databinding.ActivityWelcomeRoleBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WelcomeRoleActivity : BaseActivity() {

    private lateinit var binding: ActivityWelcomeRoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityWelcomeRoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkExistingData()
        setupListeners()
    }

    private fun checkExistingData() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) return

        lifecycleScope.launch {
            try {
                // 1. Verificar si tiene rubros configurados o es colaborador
                val settings = FirebaseFirestore.getInstance().collection("users")
                    .document(user.uid).collection("settings").get().await()
                
                val collaborations = FirebaseFirestore.getInstance().collection("access_keys")
                    .whereEqualTo("email", user.email?.lowercase()).get().await()

                if (!settings.isEmpty || !collaborations.isEmpty) {
                    // Si ya tiene algún dato, llevar a "Mis Sistemas" para que elija
                    startActivity(Intent(this@WelcomeRoleActivity, MyBusinessesActivity::class.java))
                    finish()
                } else {
                    // Si no tiene nada, pero ha entrado antes, quizás ya tiene un rol guardado
                    val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    val role = appPrefs.getString("user_role", null)
                    if (role == "BUYER") {
                        startActivity(Intent(this@WelcomeRoleActivity, BuyerHomeActivity::class.java))
                        finish()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun setupListeners() {
        binding.cardSellerRole.setOnClickListener {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                showMandatoryAccountDialog()
            } else {
                saveRoleAndProceed("SELLER")
            }
        }

        binding.cardBuyerRole.setOnClickListener {
            saveRoleAndProceed("BUYER")
        }

        binding.cardCollabRole.setOnClickListener {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                showMandatoryAccountDialog()
            } else {
                saveRoleAndProceed("COLLABORATOR")
            }
        }

        binding.btnBackToLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        binding.btnSkipToMarketWelcome.setOnClickListener {
            saveRoleAndProceed("BUYER")
        }
    }

    private fun showMandatoryAccountDialog() {
        AlertDialog.Builder(this)
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
    }

    private fun saveRoleAndProceed(role: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_role", role)
            .apply()
        
        proceedWithRole(role)
    }

    private fun proceedWithRole(role: String) {
        val startUpdate = intent.getBooleanExtra("START_UPDATE", false)
        val intent = when (role) {
            "SELLER" -> Intent(this, BusinessSelectionActivity::class.java)
            "COLLABORATOR" -> Intent(this, SearchBusinessActivity::class.java)
            else -> Intent(this, BuyerHomeActivity::class.java)
        }
        if (startUpdate) intent.putExtra("START_UPDATE", true)
        startActivity(intent)
        finish()
    }
}
