package com.gomezmart.app.network

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gomezmart.app.util.NotificationHelper

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d("FCM", "Mensaje FCM recibido de: ${remoteMessage.from}")

        // 1. Procesar payload de datos en segundo plano (Data Payload)
        if (remoteMessage.data.isNotEmpty()) {
            val type = remoteMessage.data["type"] ?: "sale"
            val title = remoteMessage.data["title"] ?: remoteMessage.data["businessName"] ?: "GoMezMart"
            val message = remoteMessage.data["message"] ?: remoteMessage.data["total"] ?: "Tienes una nueva notificación"
            val chatId = remoteMessage.data["chatId"]
            val senderId = remoteMessage.data["senderId"]
            val storeId = remoteMessage.data["storeId"]
            val storeName = remoteMessage.data["storeName"]
            val storeType = remoteMessage.data["storeType"]

            when (type) {
                "chat", "message" -> NotificationHelper.showMessageNotification(applicationContext, title, message, chatId, senderId)
                "order" -> NotificationHelper.showOrderNotification(applicationContext, title)
                "sale" -> NotificationHelper.showSaleNotification(applicationContext, message, title)
                "request" -> NotificationHelper.showRequestNotification(applicationContext, title)
                "store_update" -> NotificationHelper.showStoreUpdateNotification(applicationContext, title, message, storeId, storeName, storeType)
                else -> NotificationHelper.showMessageNotification(applicationContext, title, message, chatId, senderId)
            }
            return
        }
        
        // 2. Procesar notificación directa de sistema (Notification Payload)
        remoteMessage.notification?.let {
            val title = it.title ?: "GoMezMart"
            val body = it.body ?: "Nueva notificación recibida"
            NotificationHelper.showMessageNotification(applicationContext, title, body)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Nuevo FCM Token generado: $token")
    }
}
