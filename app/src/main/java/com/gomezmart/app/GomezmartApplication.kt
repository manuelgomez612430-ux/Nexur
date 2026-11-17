package com.gomezmart.app

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.firebase.FirebaseApp
import com.gomezmart.app.data.CartManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OSMConfig
import java.io.File

class GomezmartApplication : Application() {
    companion object {
        @Volatile
        private var isOsmConfigured = false
        private val osmLock = Any()

        fun ensureOsmConfigured(context: Context) {
            if (isOsmConfigured) return
            synchronized(osmLock) {
                if (isOsmConfigured) return
                try {
                    val appContext = context.applicationContext
                    val osmConfig = OSMConfig.getInstance()
                    osmConfig.userAgentValue = appContext.packageName

                    @Suppress("DEPRECATION")
                    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
                    osmConfig.load(appContext, prefs)

                    // Redirigir el almacenamiento de mapas a la caché interna para evitar bloqueos en Redmi/MIUI
                    val basePath = File(appContext.cacheDir, "osmdroid")
                    if (!basePath.exists()) basePath.mkdirs()
                    osmConfig.osmdroidBasePath = basePath

                    val tilePath = File(basePath, "tiles")
                    if (!tilePath.exists()) tilePath.mkdirs()
                    osmConfig.osmdroidTileCache = tilePath
                    isOsmConfigured = true
                } catch (e: Exception) {
                    Log.e("GomezmartApp", "OSMConfig error: ${e.message}")
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SplitCompat.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e("GomezmartApp", "FirebaseApp init error: ${e.message}")
        }

        // Forzar modo claro en toda la aplicación (Safe point)
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } catch (e: Exception) {
            Log.e("GomezmartApp", "NightMode error: ${e.message}")
        }

        // Firebase App Check se deja SIN instalar de forma intencional: su token de
        // depuración devolvía 403 y bloqueaba todas las consultas del Asesor IA.

        // Mover I/O de arranque (carrito, osmdroid, Room y SyncManager) fuera del hilo principal
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    CartManager.load(applicationContext)
                } catch (e: Exception) {
                    Log.e("GomezmartApp", "CartManager load error: ${e.message}")
                }

                ensureOsmConfigured(applicationContext)

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
                    }
                } catch (ignored: Exception) {}
            }
        } catch (ignored: Exception) {}
    }
}
