package com.gomezmart.app.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.R
import com.gomezmart.app.SyncManager

class GomezmartSyncService : Service() {

    private val CHANNEL_ID = "sync_service_channel"
    private var syncManager: SyncManager? = null
    private val registrations = mutableListOf<ListenerRegistration>()
    private var isListenersActive = false
    
    // Rastreo de ubicación
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var activeOrdersListener: ListenerRegistration? = null
    private var isTrackingLocation = false

    override fun onCreate() {
        super.onCreate()
        try {
            syncManager = SyncManager(this)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        } catch (e: Exception) {
            Log.e("SyncService", "Initialization failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // Ejecutar startForeground inmediatamente para evitar ForegroundServiceDidNotStartInTimeException
        showServiceNotification()

        if (action == "STOP_LISTENERS") {
            stopAllListeners()
            stopSelf()
        } else {
            startAllListeners()
            checkAndStartLocationTracking()
        }

        return START_STICKY
    }

    private fun showServiceNotification() {
        try {
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicio Gomezmart")
                .setContentText("Sincronización y rastreo de entregas activo")
                .setSmallIcon(R.drawable.ic_notification_gomezmart)
                .setColor(ContextCompat.getColor(this, R.color.purple_600))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .build()

            val hasLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                  ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (hasLocationPerm) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(1, notification, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (hasLocationPerm) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(1, notification, 0)
                }
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("SyncService", "Error starting foreground service: ${e.message}")
            try {
                stopSelf()
            } catch (ex: Exception) {}
        }
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

    private fun checkAndStartLocationTracking() {
        val myUid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (e: Exception) { null }
        if (myUid.isNullOrEmpty()) return
        
        activeOrdersListener?.remove()
        try {
            activeOrdersListener = FirebaseFirestore.getInstance().collection("orders")
                .whereEqualTo("deliveryPersonId", myUid)
                .whereEqualTo("status", "EN_ROUTE")
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) return@addSnapshotListener
                    
                    if (!snapshot.isEmpty) {
                        // Si necesitamos rastrear, intentar "promover" el servicio a tipo location si ya tenemos el permiso
                        showServiceNotification() 
                        startLocationUpdates()
                    } else {
                        stopLocationUpdates()
                    }
                }
        } catch (e: Exception) {
            Log.e("SyncService", "Error in checkAndStartLocationTracking: ${e.message}")
        }
    }

    private fun startLocationUpdates() {
        if (isTrackingLocation) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val myUid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (e: Exception) { null }
                if (myUid.isNullOrEmpty()) return
                
                val locData = mapOf(
                    "liveLat" to location.latitude,
                    "liveLng" to location.longitude,
                    "liveTimestamp" to FieldValue.serverTimestamp()
                )
                
                try {
                    FirebaseFirestore.getInstance().collection("users").document(myUid)
                        .collection("profile").document("info")
                        .set(locData, SetOptions.merge())
                } catch (e: Exception) {}
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
            isTrackingLocation = true
            Log.d("SyncService", "Location tracking started")
        } catch (e: Exception) {
            Log.e("SyncService", "Error requestLocationUpdates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {}
            locationCallback = null
        }
        isTrackingLocation = false
        Log.d("SyncService", "Location tracking stopped")
    }

    private fun stopAllListeners() {
        try {
            registrations.forEach { it.remove() }
            registrations.clear()
            activeOrdersListener?.remove()
            activeOrdersListener = null
            stopLocationUpdates()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
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
                "Gomezmart Sync Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
