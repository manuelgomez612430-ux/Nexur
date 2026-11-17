package com.gomezmart.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

class Updater(private val context: Context) {

    companion object {
        const val UPDATE_REQUEST_CODE = 999

        /**
         * Abre la página de la aplicación directamente en la app de Google Play Store.
         * Si no tiene la app de Play Store instalada, abre el navegador web.
         */
        fun openPlayStore(context: Context) {
            val packageName = context.packageName
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(webIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "No se pudo abrir Play Store", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Comprueba si hay conexión a internet disponible en el dispositivo.
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifica de manera exclusiva a través de Google Play Store (In-App Updates API).
     * @param autoCheck Si es verdadero (al entrar a la app), solo notificará si HAY actualización en Play Store.
     *                  Si no hay internet, no hace nada y permite usar la app normalmente.
     */
    fun checkAndDownload(autoCheck: Boolean = true) {
        // 1. Si el usuario entra sin internet, la app funciona con total normalidad sin interrumpirlo
        if (!isNetworkAvailable()) {
            if (!autoCheck) {
                Toast.makeText(context, "Sin conexión a internet para verificar actualizaciones", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val activity = context as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return

        try {
            val appUpdateManager = AppUpdateManagerFactory.create(context)
            val appUpdateInfoTask = appUpdateManager.appUpdateInfo

            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@addOnSuccessListener

                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                    // SE DETECTÓ NUEVA VERSIÓN EN GOOGLE PLAY: Mostrar ventana informativa
                    showUpdateAvailableDialog()
                } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE) {
                    if (!autoCheck) {
                        Toast.makeText(context, "Tienes la última versión de la app instalada ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }.addOnFailureListener { e ->
                Log.e("Updater", "Play Store check failed: ${e.message}")
                if (!autoCheck) {
                    Toast.makeText(context, "No se pudo verificar en Play Store", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Updater", "Error checkAndDownload: ${e.message}")
        }
    }

    /**
     * Muestra la ventana de aviso cuando hay una nueva versión disponible en Google Play Store.
     * Incluye las opciones "CERRAR" (para seguir usando la app) y "ACTUALIZAR" (para ir a Play Store).
     */
    private fun showUpdateAvailableDialog() {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            AlertDialog.Builder(activity)
                .setTitle("¡Nueva Versión Disponible! 🚀")
                .setMessage("Hay una nueva versión disponible, actualiza y ten las últimas mejoras y funciones de la app")
                .setPositiveButton("ACTUALIZAR") { _, _ ->
                    openPlayStore(activity)
                }
                .setNegativeButton("CERRAR", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e("Updater", "Error showUpdateAvailableDialog: ${e.message}")
        }
    }
}
