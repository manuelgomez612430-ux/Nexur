package com.naxor.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.R
import com.naxor.app.SyncManager

class GoMezMartSyncService : Service() {

    private val CHANNEL_ID = "sync_service_channel"
    private var syncManager: SyncManager? = null
    private val registrations = mutableListOf<ListenerRegistration>()
    private var isListenersActive = false

    override fun onCreate() {
        super.onCreate()
        try {
            syncManager = SyncManager(this)
        } catch (e: Exception) {
            Log.e("SyncService", "SyncManager initialization failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // Ejecutar startForeground con diseño mejorado y logo GM
        showServiceNotification()

        if (action == "STOP_LISTENERS") {
            stopAllListeners()
        } else {
            startAllListeners()
        }

        return START_STICKY
    }

    private fun showServiceNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sincronización Nexur")
            .setContentText("Listo para recibir tus mensajes y ventas")
            .setSmallIcon(R.drawable.gomezmart_icon) // Usando el icono oficial GM
            .setColor(resources.getColor(R.color.purple_600, null))
            .setPriority(NotificationCompat.PRIORITY_MIN) // Mínima intrusión visual
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startAllListeners() {
        if (isListenersActive) return
        val manager = syncManager ?: return
        
        try {
            registrations.forEach { it.remove() }
            registrations.clear()

            manager.startRealtimeSalesSync { }?.let { registrations.add(it) }
            manager.startRealtimeInventorySync { }?.let { registrations.add(it) }
            manager.startRealtimeRequestsSync()?.let { registrations.add(it) }
            manager.startRealtimeCollaboratorRequestsSync()?.let { registrations.add(it) }
            manager.startRealtimeOrdersSync { }?.let { registrations.add(it) }
            registrations.addAll(manager.startRealtimeMessagesSync())
            
            isListenersActive = true
        } catch (e: Exception) {
            Log.e("SyncService", "Error starting listeners: ${e.message}")
        }
    }

    private fun stopAllListeners() {
        try {
            registrations.forEach { it.remove() }
            registrations.clear()
            stopForeground(true)
        } catch (e: Exception) {}
        isListenersActive = false
    }

    override fun onDestroy() {
        stopAllListeners()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Nexur Sync Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
