package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : BaseActivity() {

    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Pequeño delay de seguridad para que el sistema y Firebase se asienten
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            
            val user = try { FirebaseAuth.getInstance().currentUser } catch (e: Exception) { null }
            if (user != null) {
                checkPreferencesAndNavigate()
            } else {
                navigateToLogin()
            }
        }, 1000) // 1 segundo de splash para todos asegura estabilidad
    }

    private fun navigateToLogin() {
        if (hasNavigated) return
        hasNavigated = true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun checkPreferencesAndNavigate() {
        if (hasNavigated) return
        hasNavigated = true

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val role = appPrefs.getString("user_role", null)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bizType = bizPrefs.getString("business_type", null)
        
        val startUpdate = intent.getBooleanExtra("START_UPDATE", false)

        val nextIntent = when {
            // Caso 1: Vendedor con rubro ya activo
            role == "SELLER" && bizType != null -> {
                Intent(this, MainActivity::class.java)
            }
            
            // Caso 2: Comprador activo
            role == "BUYER" -> {
                Intent(this, BuyerHomeActivity::class.java)
            }
            
            // Caso 3: Colaborador activo
            role == "COLLABORATOR" && bizType != null -> {
                Intent(this, MainActivity::class.java)
            }

            // Fallback: Ir al portal de bienvenida/detección de datos
            else -> {
                Intent(this, WelcomeRoleActivity::class.java)
            }
        }

        if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
        startActivity(nextIntent)
        finish()
    }
}
