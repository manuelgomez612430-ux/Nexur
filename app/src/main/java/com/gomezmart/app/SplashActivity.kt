package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.util.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class SplashActivity : BaseActivity() {

    private var hasNavigated = false
    private var startupJob: Job? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        if (!hasNavigated && !isFinishing && !isDestroyed) {
            Log.w("SplashActivity", "Watchdog triggered after 3s on SplashActivity; forcing safe navigation")
            startupJob?.cancel()
            forceSafeFallbackNavigation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing || isDestroyed) return

        // Garantía estricta: la pantalla Splash nunca puede quedarse congelada más de 3 segundos
        watchdogHandler.postDelayed(watchdogRunnable, 3000L)

        startupJob = lifecycleScope.launch {
            try {
                val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
                val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val isGuest = appPrefs.getBoolean("is_guest", false)

                // Si el proceso de inicio de sesión o registro quedó realmente inconcluso al cerrar la app
                // (sin haber completado perfil ni entrado aún a una interfaz activa), limpiar y ir a LoginActivity.
                if (SessionManager.isLoginInProgress(this@SplashActivity)) {
                    if (hasCompletedOnboarding(user, appPrefs)) {
                        SessionManager.setLoginInProgress(this@SplashActivity, false)
                    } else {
                        withTimeoutOrNull(2000.milliseconds) {
                            SessionManager.signOutCompletely(this@SplashActivity)
                        }
                        navigateToLogin()
                        return@launch
                    }
                }

                // 1. Si el usuario tiene una sesión formal activa (cuenta real) -> verificar perfil y navegar
                if (user != null && !user.isAnonymous) {
                    if (isGuest) {
                        appPrefs.edit().putBoolean("is_guest", false).apply()
                    }
                    checkPreferencesAndNavigate(user)
                    return@launch
                }

                // 2. Si el usuario seleccionó explícitamente el Modo Invitado (is_guest == true)
                if (isGuest) {
                    decideNextScreen()
                    return@launch
                }

                // 3. Usuario nuevo, sin autenticar, cerró sesión o sin modo invitado -> LoginActivity
                navigateToLogin()
            } catch (t: Throwable) {
                Log.e("SplashActivity", "Error in onCreate: ${t.message}")
                forceSafeFallbackNavigation()
            }
        }
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
    }

    private fun hasCompletedOnboarding(
        user: FirebaseUser?,
        appPrefs: android.content.SharedPreferences
    ): Boolean {
        if (user == null || user.isAnonymous) return false
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val isVerified = appPrefs.getBoolean("is_profile_verified", false)
        val lastInterface = appPrefs.getString("last_active_interface", null)
        val role = appPrefs.getString("user_role", null)
        val bizType = bizPrefs.getString("business_type", null)
        return isVerified && (!lastInterface.isNullOrEmpty() || !role.isNullOrEmpty() || !bizType.isNullOrEmpty())
    }

    private fun navigateToLogin() {
        if (hasNavigated) return
        hasNavigated = true
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            startActivity(Intent(this, LoginActivity::class.java))
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error navigateToLogin: ${e.message}")
        }
        finish()
    }

    private fun forceSafeFallbackNavigation() {
        if (hasNavigated) return
        try {
            val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val isGuest = appPrefs.getBoolean("is_guest", false)
            if ((user != null && !user.isAnonymous) || isGuest) {
                decideNextScreen()
            } else {
                navigateToLogin()
            }
        } catch (_: Throwable) {
            navigateToLogin()
        }
    }

    private fun checkPreferencesAndNavigate(user: FirebaseUser) {
        if (hasNavigated) return

        try {
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            val isVerified = appPrefs.getBoolean("is_profile_verified", false)
            val isExplicitGuest = appPrefs.getBoolean("is_guest", false)

            if (!isExplicitGuest && !isVerified) {
                val lastInterface = appPrefs.getString("last_active_interface", null)
                val role = appPrefs.getString("user_role", null)
                val bizType = bizPrefs.getString("business_type", null)
                val savedName = appPrefs.getString("user_full_name", null) ?: user.displayName
                val savedEmail = appPrefs.getString("user_email", null) ?: user.email

                // Si el usuario ya había usado la app o tiene datos válidos (por ej. una sincronización
                // en segundo plano sobreescribió is_profile_verified), reparar el estado sin cerrar sesión.
                val hasActiveUsage = !lastInterface.isNullOrEmpty() || !role.isNullOrEmpty() || !bizType.isNullOrEmpty()
                if (hasActiveUsage || SessionManager.isProfileComplete(savedName, savedEmail)) {
                    appPrefs.edit().apply {
                        putBoolean("is_profile_verified", true)
                        if (!SessionManager.isPlaceholderName(savedName)) {
                            putString("user_full_name", savedName!!.trim())
                        }
                        if (!savedEmail.isNullOrBlank()) {
                            putString("user_email", savedEmail.trim())
                        }
                        commit()
                    }
                    decideNextScreen()
                    return
                }

                // Si la cuenta real nunca terminó de configurar su perfil, llevar a ProfileSetupActivity o Login
                hasNavigated = true
                watchdogHandler.removeCallbacks(watchdogRunnable)
                startActivity(Intent(this, ProfileSetupActivity::class.java))
                finish()
                return
            }

            decideNextScreen()
        } catch (t: Throwable) {
            Log.e("SplashActivity", "Error checkPreferencesAndNavigate: ${t.message}")
            decideNextScreen()
        }
    }

    private fun decideNextScreen() {
        if (hasNavigated) return
        hasNavigated = true
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val lastInterface = appPrefs.getString("last_active_interface", null)
            val role = appPrefs.getString("user_role", null)
            val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            val bizType = bizPrefs.getString("business_type", null)

            val startUpdate = intent.getBooleanExtra("START_UPDATE", false)
            val openChatId = intent.getStringExtra("OPEN_CHAT_ID")
            val openSenderId = intent.getStringExtra("OPEN_SENDER_ID")

            if (openChatId != null) {
                val chatIntent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("ORDER_ID", openChatId)
                    putExtra("OTHER_USER_ID", openSenderId)
                    putExtra("IS_DIRECT", true)
                }
                startActivity(chatIntent)
                finish()
                return
            }

            // Respetar la última interfaz activa (Mercado vs Gestión de Negocio)
            if (lastInterface == "MARKET") {
                val nextIntent = Intent(this, BuyerHomeActivity::class.java)
                if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
                startActivity(nextIntent)
                finish()
                return
            }

            val module = when (bizType) {
                "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
                "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
                "PRODUCTS" -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
                else -> null
            }

            val isModuleReady = try {
                module != null && com.gomezmart.app.utils.DynamicModuleManager(this).isModuleInstalled(module)
            } catch (_: Throwable) {
                false
            }

            if (lastInterface == "BUSINESS" && !bizType.isNullOrEmpty()) {
                val nextIntent = if (isModuleReady) {
                    Intent(this, MainActivity::class.java)
                } else {
                    Intent(this, BuyerHomeActivity::class.java)
                }
                if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
                startActivity(nextIntent)
                finish()
                return
            }

            if (isModuleReady) {
                val nextIntent = Intent(this, MainActivity::class.java)
                if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
                startActivity(nextIntent)
                finish()
                return
            }

            val nextIntent = when (role) {
                "BUYER" -> Intent(this, BuyerHomeActivity::class.java)
                else -> Intent(this, WelcomeRoleActivity::class.java)
            }

            if (startUpdate) nextIntent.putExtra("START_UPDATE", true)
            startActivity(nextIntent)
            finish()
        } catch (t: Throwable) {
            Log.e("SplashActivity", "Error decideNextScreen: ${t.message}")
            try { startActivity(Intent(this, BuyerHomeActivity::class.java)) } catch (_: Exception) {}
            finish()
        }
    }
}
