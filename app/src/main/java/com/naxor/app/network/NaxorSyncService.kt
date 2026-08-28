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
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.SyncManager

class GoMezMartSyncService : Service() {

    private val CHANNEL_ID = "sync_service_channel"
    private lateinit var syncManager: SyncManager
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreate() {
        super.onCreate()
        syncManager = SyncManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Eliminada la notificación persistente para mejorar la comodidad del usuario
        // El servicio ahora funcionará en segundo plano mientras el sistema lo permita
        
        // Limpiar registros previos si existen
        registrations.forEach { it.remove() }
        registrations.clear()

        // Iniciar el monitoreo en tiempo real y guardar registros
        syncManager.startRealtimeSalesSync { }?.let { registrations.add(it) }
        syncManager.startRealtimeInventorySync { }?.let { registrations.add(it) }
        syncManager.startRealtimeRequestsSync()?.let { registrations.add(it) }
        registrations.addAll(syncManager.startRealtimeMessagesSync())

        return START_STICKY
    }

    override fun onDestroy() {
        registrations.forEach { it.remove() }
        registrations.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Ya no es necesario crear el canal de notificación para el monitoreo persistente
    private fun createNotificationChannel() {}
}
