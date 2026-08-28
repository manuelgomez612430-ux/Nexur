package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Pequeña pausa para mostrar la marca (reducida para mejor experiencia)
        Handler(Looper.getMainLooper()).postDelayed({
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                // Usuario logueado, verificar si ya tiene rol y rubro configurado
                checkPreferencesAndNavigate()
            } else {
                // No logueado, ir al Login
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 1000) // Reducido de 2000ms a 1000ms para un inicio más ágil
    }

    private fun checkPreferencesAndNavigate() {
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val role = appPrefs.getString("user_role", null)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bizType = bizPrefs.getString("business_type", null)
        
        val startUpdate = intent.getBooleanExtra("START_UPDATE", false)

        when {
            // Caso 1: Vendedor con rubro ya activo
            role == "SELLER" && bizType != null -> {
                val intent = Intent(this, MainActivity::class.java)
                if (startUpdate) intent.putExtra("START_UPDATE", true)
                startActivity(intent)
            }
            
            // Caso 2: Comprador activo
            role == "BUYER" -> {
                val intent = Intent(this, BuyerHomeActivity::class.java)
                if (startUpdate) intent.putExtra("START_UPDATE", true)
                startActivity(intent)
            }
            
            // Caso 3: Colaborador activo
            role == "COLLABORATOR" && bizType != null -> {
                val intent = Intent(this, MainActivity::class.java)
                if (startUpdate) intent.putExtra("START_UPDATE", true)
                startActivity(intent)
            }

            // Fallback: Ir al portal de bienvenida/detección de datos
            else -> {
                val intent = Intent(this, WelcomeRoleActivity::class.java)
                if (startUpdate) intent.putExtra("START_UPDATE", true)
                startActivity(intent)
            }
        }
    }
}
