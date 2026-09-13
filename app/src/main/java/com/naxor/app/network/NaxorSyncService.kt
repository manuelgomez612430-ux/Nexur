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
    private var isListenersActive = false

    override fun onCreate() {
        super.onCreate()
        syncManager = SyncManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == "STOP_LISTENERS") {
            stopAllListeners()
        } else {
            startAllListeners()
        }

        return START_STICKY
    }

    private fun startAllListeners() {
        if (isListenersActive) return
        
        registrations.forEach { it.remove() }
        registrations.clear()

        syncManager.startRealtimeSalesSync { }?.let { registrations.add(it) }
        syncManager.startRealtimeInventorySync { }?.let { registrations.add(it) }
        syncManager.startRealtimeRequestsSync()?.let { registrations.add(it) }
        registrations.addAll(syncManager.startRealtimeMessagesSync())
        
        isListenersActive = true
    }

    private fun stopAllListeners() {
        registrations.forEach { it.remove() }
        registrations.clear()
        isListenersActive = false
    }

    override fun onDestroy() {
        stopAllListeners()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Ya no es necesario crear el canal de notificación para el monitoreo persistente
    private fun createNotificationChannel() {}
}
