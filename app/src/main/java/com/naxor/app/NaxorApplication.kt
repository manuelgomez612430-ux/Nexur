package com.naxor.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.naxor.app.BuildConfig
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.Configuration
import android.os.Build
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.naxor.app.network.GoMezMartSyncService
import org.osmdroid.config.Configuration as OSMConfig

class GoMezMartApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            // Configuración de OpenStreetMap
            OSMConfig.getInstance().userAgentValue = packageName
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Forzar modo claro en toda la aplicación (Safe point)
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Solo instalamos el proveedor de depuración si estamos en modo desarrollo
        try {
            if (BuildConfig.DEBUG) {
                val firebaseAppCheck = FirebaseAppCheck.getInstance()
                firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Iniciar servicio de sincronización al arrancar la app
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val intent = Intent(this, GoMezMartSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
