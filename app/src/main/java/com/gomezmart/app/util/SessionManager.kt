package com.gomezmart.app.util

import android.content.Context
import android.util.Patterns
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.CartManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

object SessionManager {

    fun isPlaceholderName(name: String?): Boolean {
        val cleanName = name?.trim() ?: ""
        if (cleanName.isBlank()) return true
        return cleanName.equals("Usuario", ignoreCase = true) ||
            cleanName.equals("Usuario Gomezmart", ignoreCase = true) ||
            cleanName.equals("Usuario GoMezMart", ignoreCase = true)
    }

    /**
     * Verifica si el nombre completo y correo cumplen los requisitos mínimos para considerar
     * el perfil completo (correo válido + al menos un nombre real).
     */
    fun isProfileComplete(fullName: String?, email: String?): Boolean {
        val cleanEmail = email?.trim() ?: ""
        if (isPlaceholderName(fullName) || cleanEmail.isBlank()) return false
        return Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()
    }

    /**
     * Marca si el usuario se encuentra en medio del proceso de inicio de sesión o registro.
     * Si la app se cierra mientras está en true, SplashActivity reiniciará desde LoginActivity.
     */
    fun setLoginInProgress(context: Context, inProgress: Boolean) {
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("login_in_progress", inProgress)
            .commit()
    }

    fun isLoginInProgress(context: Context): Boolean {
        return context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("login_in_progress", false)
    }

    /**
     * Guarda los datos del perfil en AppPrefs de manera consistente.
     */
    fun saveProfileLocally(
        context: Context,
        fullName: String,
        email: String,
        phone: String = "",
        dni: String = "",
        address: String = "",
        photoUrl: String = "",
        allowDirectorySearch: Boolean = true,
        shareContactWithFriends: Boolean = false,
        messagePrivacy: String? = null,
        verified: Boolean = true
    ) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val wasVerified = prefs.getBoolean("is_profile_verified", false)
        prefs.edit().apply {
            putBoolean("is_guest", false)
            if (!isPlaceholderName(fullName)) putString("user_full_name", fullName.trim())
            if (email.isNotBlank()) putString("user_email", email.trim())
            if (phone.isNotBlank()) putString("user_phone", phone.trim())
            if (dni.isNotBlank()) putString("user_dni", dni.trim())
            if (address.isNotBlank()) putString("user_address", address.trim())
            if (photoUrl.isNotBlank()) putString("user_photo_url", photoUrl.trim())
            putBoolean("allow_directory_search", allowDirectorySearch)
            putBoolean("share_contact_with_friends", shareContactWithFriends)
            if (!messagePrivacy.isNullOrBlank()) putString("message_privacy", messagePrivacy)
            putBoolean("is_profile_verified", verified || wasVerified)
            commit()
        }
    }

    /**
     * Cierra completamente la sesión de Firebase Auth, Google Sign-In y limpia toda la base
     * de datos local, caché y SharedPreferences de forma atómica y con timeouts para evitar bloqueos.
     */
    suspend fun signOutCompletely(context: Context) = withContext(Dispatchers.IO) {
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        try {
            withTimeoutOrNull(1500.milliseconds) {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(context.applicationContext, gso).signOut().await()
            }
        } catch (_: Exception) {}

        try {
            CartManager.clear()
        } catch (_: Exception) {}

        try {
            withTimeoutOrNull(2500.milliseconds) {
                clearSessionData(context)
            }
        } catch (_: Exception) {}
    }

    /**
     * Limpia absolutamente todos los datos locales de la aplicación.
     * Útil al cerrar sesión para que el próximo usuario encuentre una cuenta vacía.
     */
    suspend fun clearSessionData(context: Context) = withContext(Dispatchers.IO) {
        // 1. Limpiar Base de Datos Room
        try {
            withTimeoutOrNull(1500.milliseconds) {
                val database = AppDatabase.getDatabase(context)
                database.clearAllTables()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Limpiar todos los SharedPreferences conocidos (con commit para garantizar persistencia inmediata)
        val sharedPrefs = listOf(
            "BusinessPrefs",
            "AppPrefs",
            "CommitmentNotifs",
            "HotelToolsPrefs",
            "DocumentPrefs",
            "CollabPermissions",
            "BuyerPrefs",
            "HotelPrefs",
            "DynamicModulesPrefs"
        )

        sharedPrefs.forEach { prefName ->
            try {
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            } catch (_: Exception) {}
        }

        // 3. Limpiar archivos de usuario (sin borrar directorios internos de Firebase/Firestore/WorkManager/OsmDroid)
        try {
            context.filesDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val lower = file.name.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".webp") ||
                        lower.endsWith(".pdf") || lower.startsWith("logo_") ||
                        lower.startsWith("camera_capture_")
                    ) {
                        file.delete()
                    }
                }
            }
            context.cacheDir.listFiles()?.forEach { child ->
                if (child.name != "osmdroid") {
                    child.deleteRecursively()
                }
            }
            context.getExternalFilesDir(null)?.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
