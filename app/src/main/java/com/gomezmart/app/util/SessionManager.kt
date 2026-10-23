package com.gomezmart.app.util

import android.content.Context
import com.gomezmart.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SessionManager {

    /**
     * Limpia absolutamente todos los datos locales de la aplicación.
     * Útil al cerrar sesión para que el próximo usuario encuentre una cuenta vacía.
     */
    suspend fun clearSessionData(context: Context) = withContext(Dispatchers.IO) {
        // 1. Limpiar Base de Datos Room
        try {
            val database = AppDatabase.getDatabase(context)
            database.clearAllTables()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Limpiar todos los SharedPreferences conocidos
        val sharedPrefs = listOf(
            "BusinessPrefs",
            "AppPrefs",
            "CommitmentNotifs",
            "HotelToolsPrefs",
            "DocumentPrefs",
            "CollabPermissions",
            "BuyerPrefs",
            "HotelPrefs"
        )

        sharedPrefs.forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }

        // 3. Limpiar archivos internos (Logos, PDFs temporales, etc.)
        try {
            context.filesDir.deleteRecursively()
            context.cacheDir.deleteRecursively()
            context.getExternalFilesDir(null)?.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
