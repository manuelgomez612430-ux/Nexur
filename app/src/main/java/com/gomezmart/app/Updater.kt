package com.gomezmart.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class Updater(private val context: Context) {

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var tvProgressPercent: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1

    fun checkAndDownload(autoDownload: Boolean = false) {
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) { 0L }

        try {
            FirebaseFirestore.getInstance().collection("app_config").document("updater")
                .get()
                .addOnSuccessListener { doc ->
                    if (context is Activity && (context.isFinishing || context.isDestroyed)) return@addOnSuccessListener
                    val latestVersion = doc.getLong("latest_version") ?: 0L
                    val url = doc.getString("download_url") ?: ""
                    val notes = doc.getString("release_notes") ?: ""

                    if (latestVersion > currentVersion && url.isNotEmpty()) {
                        showUpdateDialog(url, notes)
                    } else {
                        if (!autoDownload) {
                            Toast.makeText(context, "Ya tienes la última versión instalada ✅", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    if (!autoDownload) {
                        Toast.makeText(context, "No se pudo verificar la actualización", Toast.LENGTH_SHORT).show()
                    }
                }
        } catch (e: Exception) {
            Log.e("Updater", "Error checkAndDownload: ${e.message}")
        }
    }

    private fun showUpdateDialog(url: String, notes: String) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return
        try {
            AlertDialog.Builder(context)
                .setTitle("¡Nueva Versión Disponible! 🚀")
                .setMessage("Novedades:\n$notes\n\n¿Deseas descargar la actualización ahora?")
                .setPositiveButton("Actualizar") { _, _ ->
                    startDownload(url)
                }
                .setNegativeButton("Luego", null)
                .show()
        } catch (e: Exception) {
            Log.e("Updater", "Error showUpdateDialog: ${e.message}")
        }
    }

    @SuppressLint("InflateParams")
    private fun startDownload(downloadUrl: String) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return
        try {
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
            progressBar = dialogView.findViewById<ProgressBar>(R.id.pbDownload)
            tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
            
            progressDialog = AlertDialog.Builder(context)
                .setTitle("Descargando Actualización")
                .setView(dialogView)
                .setCancelable(false)
                .create()
            
            progressDialog?.show()

            val fileName = "GoMezMart_v_Update.apk"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (file.exists()) file.delete()
            
            val request = try {
                DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle("Actualizando GoMezMart 🚀")
                    .setDescription("Descargando la última versión...")
                    .setDestinationUri(Uri.fromFile(file))
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
            } catch (e: Exception) {
                progressDialog?.dismiss()
                Toast.makeText(context, "Error: URL de descarga no válida", Toast.LENGTH_SHORT).show()
                return
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (manager == null) {
                progressDialog?.dismiss()
                return
            }
            downloadId = manager.enqueue(request)

            startProgressUpdateTask(manager)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        handler.removeCallbacksAndMessages(null)
                        try { progressDialog?.dismiss() } catch (e: Exception) {}
                        
                        try {
                            val query = DownloadManager.Query().setFilterById(downloadId)
                            val cursor = manager.query(query)
                            if (cursor != null && cursor.moveToFirst()) {
                                @SuppressLint("Range")
                                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    Toast.makeText(ctx, "✅ Descarga completa", Toast.LENGTH_SHORT).show()
                                    installApk(file)
                                } else {
                                    @SuppressLint("Range")
                                    val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                                    Toast.makeText(ctx, "❌ Error en descarga: $reason", Toast.LENGTH_LONG).show()
                                }
                                cursor.close()
                            }
                        } catch (e: Exception) {
                            Log.e("Updater", "Error querying download: ${e.message}")
                        }
                        try { ctx.unregisterReceiver(this) } catch (e: Exception) {}
                    }
                }
            }
            
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            // ContextCompat.registerReceiver añade el flag RECEIVER_EXPORTED/NOT_EXPORTED
            // de forma compatible con todas las versiones (obligatorio desde API 33).
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                onComplete,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e("Updater", "Error startDownload: ${e.message}")
        }
    }

    private fun startProgressUpdateTask(manager: DownloadManager) {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = manager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        @SuppressLint("Range")
                        val bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        @SuppressLint("Range")
                        val bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        if (bytesTotal > 0) {
                            val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                            progressBar?.progress = progress
                            tvProgressPercent?.text = "$progress%"
                        }
                        cursor.close()
                    }
                    handler.postDelayed(this, 500)
                } catch (e: Exception) {
                    Log.e("Updater", "Error progress update: ${e.message}")
                }
            }
        })
    }

    private fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "⚠️ Permita que GoMezMart instale actualizaciones en la siguiente pantalla", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al abrir instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
