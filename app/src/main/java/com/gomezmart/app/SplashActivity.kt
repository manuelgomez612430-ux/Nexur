package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : BaseActivity() {

    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing || isDestroyed) return

        try {
            val user = try { FirebaseAuth.getInstance().currentUser } catch (e: Exception) { null }
            if (user != null) {
                checkPreferencesAndNavigate()
            } else {
                navigateToLogin()
            }
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error in onCreate: ${e.message}")
            navigateToLogin()
        }
    }

    private fun navigateToLogin() {
        if (hasNavigated) return
        hasNavigated = true
        try {
            startActivity(Intent(this, LoginActivity::class.java))
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error navigateToLogin: ${e.message}")
        }
        finish()
    }

    private fun checkPreferencesAndNavigate() {
        if (hasNavigated) return
        hasNavigated = true

        try {
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val isVerified = appPrefs.getBoolean("is_profile_verified", false)
            val userPhone = appPrefs.getString("user_phone", "") ?: ""

            // REGLA STRICTA: Si no tiene teléfono de 9 dígitos o no está verificado el perfil -> ProfileSetupActivity OBLIGATORIO
            if (!isVerified || userPhone.length != 9) {
                startActivity(Intent(this, ProfileSetupActivity::class.java))
                finish()
                return
            }

            decideNextScreen()
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error checkPreferencesAndNavigate: ${e.message}")
            startActivity(Intent(this, ProfileSetupActivity::class.java))
            finish()
        }
    }

    private fun decideNextScreen() {
        try {
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val role = appPrefs.getString("user_role", null)
            val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            val bizType = bizPrefs.getString("business_type", null)

            val startUpdate = intent.getBooleanExtra("START_UPDATE", false)
            val openChatId = intent.getStringExtra("OPEN_CHAT_ID")
            val openSenderId = intent.getStringExtra("OPEN_SENDER_ID")

            val nextIntent = when {
                // Vendedor o Colaborador con rubro activo -> MainActivity
                (role == "SELLER" || role == "COLLABORATOR") && bizType != null -> {
                    Intent(this, MainActivity::class.java)
                }

                // Fallback o sin rubro activo -> Verificar datos de sistema en WelcomeRoleActivity
                else -> {
                    Intent(this, WelcomeRoleActivity::class.java)
                }
            }

            if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
            
            // Si hay una instrucción de abrir chat, pasarla adelante
            if (openChatId != null) {
                val chatIntent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("ORDER_ID", openChatId)
                    putExtra("OTHER_USER_ID", openSenderId)
                    putExtra("IS_DIRECT", true)
                }
                startActivity(chatIntent)
            } else {
                startActivity(nextIntent)
            }
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error decideNextScreen: ${e.message}")
            try { startActivity(Intent(this, WelcomeRoleActivity::class.java)) } catch (ex: Exception) {}
        }
        finish()
    }
}
