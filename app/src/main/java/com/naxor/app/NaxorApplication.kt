package com.naxor.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.naxor.app.BuildConfig
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.Configuration
import org.osmdroid.config.Configuration as OSMConfig

class GoMezMartApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        // Configuración de OpenStreetMap
        OSMConfig.getInstance().userAgentValue = packageName

        // Forzar modo claro en toda la aplicación
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Aplicar bloqueo de escala de fuente global (Protección para accesibilidad extrema)
        val config = resources.configuration
        if (config.fontScale != 1.0f) {
            config.fontScale = 1.0f
            val metrics = resources.displayMetrics
            metrics.scaledDensity = config.fontScale * metrics.density
            resources.updateConfiguration(config, metrics)
        }
        
        // Solo instalamos el proveedor de depuración si estamos en modo desarrollo
        // Para los usuarios finales (APK), Firebase verá que no hay proveedor 
        // y dejará pasar la petición porque desactivamos el "Enforcement" en la consola.
        if (BuildConfig.DEBUG) {
            val firebaseAppCheck = FirebaseAppCheck.getInstance()
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        }
    }
}
