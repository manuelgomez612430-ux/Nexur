package com.naxor.app.network

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.naxor.app.util.NotificationHelper

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d("FCM", "Mensaje recibido de: ${remoteMessage.from}")

        // Verificar si el mensaje contiene datos
        if (remoteMessage.data.isNotEmpty()) {
            val total = remoteMessage.data["total"] ?: "S/ 0.00"
            val businessName = remoteMessage.data["businessName"] ?: "Mi Negocio"
            
            // Mostrar la notificación usando el helper existente
            NotificationHelper.showSaleNotification(applicationContext, total, businessName)
        }
        
        // Si el mensaje tiene una notificación de sistema (opcional)
        remoteMessage.notification?.let {
            val title = it.title ?: "¡Venta Realizada!"
            val body = it.body ?: "Se ha registrado una nueva venta."
            NotificationHelper.showSaleNotification(applicationContext, body, title)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Nuevo Token generado: $token")
        // En el futuro podemos enviar este token al servidor para notificaciones dirigidas
    }
}
