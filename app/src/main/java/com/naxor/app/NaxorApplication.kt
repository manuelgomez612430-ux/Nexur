package com.naxor.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.naxor.app.BuildConfig

class NaxorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        
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
