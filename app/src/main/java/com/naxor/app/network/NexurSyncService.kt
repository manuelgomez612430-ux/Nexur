package com.naxor.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.naxor.app.SyncManager

class NexurSyncService : Service() {

    private val CHANNEL_ID = "sync_service_channel"
    private lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()
        syncManager = SyncManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexur: Monitoreo Activo")
            .setContentText("Sincronizando ventas en tiempo real...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // Iniciar el monitoreo en tiempo real (Ventas e Inventario)
        syncManager.startRealtimeSalesSync { }
        syncManager.startRealtimeInventorySync { }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Nexur Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
