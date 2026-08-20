package com.naxor.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class Updater(private val context: Context) {

    fun checkAndDownload() {
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) { 0L }

        FirebaseFirestore.getInstance().collection("app_config").document("updater")
            .get()
            .addOnSuccessListener { doc ->
                val latestVersion = doc.getLong("latest_version") ?: 0L
                val url = doc.getString("download_url") ?: ""
                val notes = doc.getString("release_notes") ?: ""

                if (latestVersion > currentVersion && url.isNotEmpty()) {
                    showUpdateDialog(url, notes)
                } else {
                    Toast.makeText(context, "Ya tienes la última versión instalada ✅", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "No se pudo verificar la actualización", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showUpdateDialog(url: String, notes: String) {
        AlertDialog.Builder(context)
            .setTitle("¡Nueva Versión Disponible! 🚀")
            .setMessage("Novedades:\n$notes\n\n¿Deseas descargar la actualización ahora?")
            .setPositiveButton("Actualizar") { _, _ ->
                startDownload(url)
            }
            .setNegativeButton("Luego", null)
            .show()
    }

    private fun startDownload(downloadUrl: String) {
        Toast.makeText(context, "Iniciando descarga...", Toast.LENGTH_LONG).show()

        val fileName = "Naxor_Update.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()
        
        val request = try {
            DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("Actualizando Naxor")
                .setDescription("Descargando nueva versión...")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
        } catch (e: Exception) {
            Toast.makeText(context, "URL de descarga inválida", Toast.LENGTH_SHORT).show()
            return
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = manager.query(query)
                    if (cursor.moveToFirst()) {
                        @SuppressLint("Range")
                        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(file)
                        } else {
                            Toast.makeText(context, "Error al descargar el archivo", Toast.LENGTH_LONG).show()
                        }
                    }
                    cursor.close()
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_EXPORTED
            } else {
                0
            }
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), flags)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error al abrir instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
