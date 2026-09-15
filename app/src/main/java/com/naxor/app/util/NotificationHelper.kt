package com.naxor.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.naxor.app.R
import com.naxor.app.SplashActivity

object NotificationHelper {
    private const val CHANNEL_ID = "gomezmart_notifications"
    private const val CHANNEL_NAME = "Avisos de GoMezMart"
    private const val CHANNEL_DESC = "Notificaciones de ventas, mensajes y solicitudes"

    private fun getManager(context: Context): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableLights(true)
                lightColor = Color.MAGENTA
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
        return manager
    }

    fun showSaleNotification(context: Context, total: String, businessName: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.gomezmart_icon)
            .setContentTitle("¡Venta Realizada!")
            .setContentText("Se registró una venta por $total en $businessName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        getManager(context).notify((System.currentTimeMillis() % 1000000).toInt(), builder.build())
    }

    fun showMessageNotification(context: Context, title: String, message: String) {
        Log.d("NotificationHelper", "Mostrando notificación: $title - $message")
        val notificationId = (title.hashCode() + message.hashCode() + System.currentTimeMillis().toInt()) % 1000000
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.gomezmart_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        getManager(context).notify(notificationId, builder.build())
    }

    fun showUpdateNotification(context: Context, latestVersionName: String, releaseNotes: String) {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("START_UPDATE", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 2024, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.gomezmart_icon)
            .setContentTitle("¡Nueva Versión $latestVersionName! 🚀")
            .setContentText("Toca para descargar y actualizar ahora.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Novedades:\n$releaseNotes"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        getManager(context).notify(2024, builder.build())
    }

    fun showRequestNotification(context: Context, name: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.gomezmart_icon)
            .setContentTitle("Nueva Solicitud 🤝")
            .setContentText("$name desea colaborar con tu negocio.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        getManager(context).notify(2002, builder.build())
    }

    fun showOrderNotification(context: Context, name: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.gomezmart_icon)
            .setContentTitle("¡Nuevo Pedido! 🛵")
            .setContentText("$name ha realizado un pedido en tu tienda.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        getManager(context).notify(3003, builder.build())
    }
}
