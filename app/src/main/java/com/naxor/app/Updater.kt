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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.*

class Updater(private val context: Context) {

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var tvProgressPercent: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1

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

    @SuppressLint("InflateParams")
    private fun startDownload(downloadUrl: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
        progressBar = dialogView.findViewById<ProgressBar>(R.id.pbDownload)
        tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        
        progressDialog = AlertDialog.Builder(context)
            .setTitle("Descargando Actualización")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        progressDialog?.show()

        val fileName = "Naxor_v_Update.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()
        
        val request = try {
            DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("Actualizando Naxor 🚀")
                .setDescription("Descargando la última versión...")
                .setDestinationUri(Uri.fromFile(file))
                .setMimeType("application/vnd.android.package-archive")
                // Re-intentar automáticamente si hay fallo de red
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
        } catch (e: Exception) {
            progressDialog?.dismiss()
            Toast.makeText(context, "Error: URL de descarga no válida", Toast.LENGTH_SHORT).show()
            return
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)

        startProgressUpdateTask(manager)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    handler.removeCallbacksAndMessages(null)
                    progressDialog?.dismiss()
                    
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = manager.query(query)
                    if (cursor.moveToFirst()) {
                        @SuppressLint("Range")
                        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Toast.makeText(context, "✅ Descarga completa", Toast.LENGTH_SHORT).show()
                            installApk(file)
                        } else {
                            @SuppressLint("Range")
                            val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                            Toast.makeText(context, "❌ Error en descarga: $reason", Toast.LENGTH_LONG).show()
                        }
                    }
                    cursor.close()
                    try { context.unregisterReceiver(this) } catch (e: Exception) {}
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, filter)
        }
    }

    private fun startProgressUpdateTask(manager: DownloadManager) {
        handler.post(object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = manager.query(query)
                if (cursor.moveToFirst()) {
                    @SuppressLint("Range")
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    @SuppressLint("Range")
                    val bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    if (bytesTotal > 0) {
                        val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                        progressBar?.progress = progress
                        tvProgressPercent?.text = "$progress%"
                    }
                }
                cursor.close()
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "⚠️ Permita que Naxor instale actualizaciones en la siguiente pantalla", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al abrir instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
