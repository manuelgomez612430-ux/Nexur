package com.naxor.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.naxor.app.R

object NotificationHelper {
    private const val CHANNEL_ID = "sales_notifications"
    private const val CHANNEL_NAME = "Ventas Nexur"
    private const val CHANNEL_DESC = "Notificaciones al realizar ventas con éxito"

    fun showSaleNotification(context: Context, total: String, businessName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Crear canal para Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableLights(true)
                lightColor = android.graphics.Color.MAGENTA
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("¡Venta Realizada!")
            .setContentText("Se registró una venta por $total en $businessName")
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Subir prioridad para que aparezca arriba
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        // Usar un ID basado en el tiempo para que no se sobrepongan
        val notificationId = (System.currentTimeMillis() % 1000000).toInt()
        notificationManager.notify(notificationId, builder.build())
    }
}
