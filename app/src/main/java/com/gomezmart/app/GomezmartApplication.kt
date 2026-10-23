package com.gomezmart.app

import android.app.Application
import android.preference.PreferenceManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.gomezmart.app.data.CartManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OSMConfig
import java.io.File

class GomezmartApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e("GomezmartApp", "FirebaseApp init error: ${e.message}")
        }

        try {
            // Cargar carrito persistido
            CartManager.load(this)
        } catch (e: Exception) {
            Log.e("GomezmartApp", "CartManager load error: ${e.message}")
        }

        try {
            // Configuración de OpenStreetMap compatible con Xiaomi/Redmi (Scoped Storage y MIUI)
            val osmConfig = OSMConfig.getInstance()
            osmConfig.userAgentValue = packageName
            
            @Suppress("DEPRECATION")
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            osmConfig.load(this, prefs)

            // Redirigir el almacenamiento de mapas a la caché interna para evitar bloqueos en Redmi/MIUI
            val basePath = File(cacheDir, "osmdroid")
            if (!basePath.exists()) basePath.mkdirs()
            osmConfig.osmdroidBasePath = basePath

            val tilePath = File(basePath, "tiles")
            if (!tilePath.exists()) tilePath.mkdirs()
            osmConfig.osmdroidTileCache = tilePath
        } catch (e: Exception) {
            Log.e("GomezmartApp", "OSMConfig error: ${e.message}")
        }

        // Forzar modo claro en toda la aplicación (Safe point)
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } catch (e: Exception) {
            Log.e("GomezmartApp", "NightMode error: ${e.message}")
        }

        // Firebase App Check se deja SIN instalar de forma intencionada: su token de
        // depuración devolvía 403 y bloqueaba todas las consultas del Asesor IA.

        // Limpiar y sanidar la base de datos (se ejecuta en cada arranque para
        // eliminar cuotas duplicadas que pueda haber dejado la sincronización en la nube)
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.gomezmart.app.data.AppDatabase.getDatabase(applicationContext)
                    db.loanDao().sanitizeAllLoans()
                    SyncManager(applicationContext).scheduleOfflineSync()
                } catch (ignored: Exception) {}

                // Si la base local tuvo que ser recreada (actualización desde una
                // versión muy antigua), la nube conserva la copia: se avisa al
                // usuario para que no se preocupe y no vuelva a perder datos.
                try {
                    val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    if (prefs.getBoolean("db_was_recreated", false)) {
                        prefs.edit().putBoolean("db_was_recreated", false).apply()
                        SyncManager(applicationContext).downloadEverythingFromCloud { }
                    }
                } catch (ignored: Exception) {}
            }
        } catch (ignored: Exception) {}
    }
}
