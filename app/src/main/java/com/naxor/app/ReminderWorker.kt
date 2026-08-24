package com.naxor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naxor.app.data.AppDatabase
import java.util.*

class ReminderWorker(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(context)
        
        // Consultar deudas que vencen hoy (hasta el final del día)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        val timeLimit = cal.timeInMillis
        
        val pendingDebtors = database.debtorDao().getPendingCollections(timeLimit)
        
        if (pendingDebtors.isNotEmpty()) {
            val totalAmount = pendingDebtors.sumOf { it.deudaTotal }
            showReminderNotification(pendingDebtors.size, totalAmount, "COBRAR", 2002)
        }

        // Consultar deudas propias por pagar hoy
        val pendingBusinessDebts = database.businessDebtDao().getUpcomingPayments(timeLimit)
        if (pendingBusinessDebts.isNotEmpty()) {
            val totalAmount = pendingBusinessDebts.sumOf { it.montoTotal - it.montoPagado }
            showReminderNotification(pendingBusinessDebts.size, totalAmount, "PAGAR_BIZ", 2003)
        }

        // Consultar gastos programados para hoy
        val pendingExpenses = database.expenseDao().getPendingExpenses(timeLimit)
        if (pendingExpenses.isNotEmpty()) {
            val totalAmount = pendingExpenses.sumOf { it.monto }
            showReminderNotification(pendingExpenses.size, totalAmount, "PAGAR_GASTO", 2004)
        }
        
        return Result.success()
    }

    private fun showReminderNotification(count: Int, total: Double, type: String, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "debt_reminders"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Recordatorios de Cobro/Pago", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val activityClass = when(type) {
            "COBRAR" -> DeudoresActivity::class.java
            "PAGAR_BIZ" -> BusinessDebtsActivity::class.java
            else -> GastosActivity::class.java
        }
        val intent = android.content.Intent(context, activityClass).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(context, notificationId, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val title = if (type == "COBRAR") "📅 ¡Hoy toca cobrar!" else "💸 ¡Hoy toca pagar!"
        val content = if (type == "COBRAR") {
            "Tienes $count clientes con pagos programados por un total de S/ ${String.format(Locale.getDefault(), "%.2f", total)}."
        } else {
            "Tienes $count compromisos de pago hoy por un total de S/ ${String.format(Locale.getDefault(), "%.2f", total)}."
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.logo_naxor_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }
}
