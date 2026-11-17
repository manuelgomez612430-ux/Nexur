package com.gomezmart.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.CommitmentEntity
import com.gomezmart.app.data.HotelToolEntity
import java.util.*

class ReminderWorker(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(context)
        
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        val timeLimit = cal.timeInMillis
        
        // 1. Deudores
        val pendingDebtors = database.debtorDao().getPendingCollections(timeLimit)
        if (pendingDebtors.isNotEmpty()) {
            val totalAmount = pendingDebtors.sumOf { it.deudaTotal }
            showReminderNotification(pendingDebtors.size, totalAmount, "COBRAR", 2002)
        }

        // 2. Deudas Propias
        val pendingBusinessDebts = database.businessDebtDao().getUpcomingPayments(timeLimit)
        if (pendingBusinessDebts.isNotEmpty()) {
            val totalAmount = pendingBusinessDebts.sumOf { it.montoTotal - it.montoPagado }
            showReminderNotification(pendingBusinessDebts.size, totalAmount, "PAGAR_BIZ", 2003)
        }

        // 3. Gastos
        val pendingExpenses = database.expenseDao().getPendingExpenses(timeLimit)
        if (pendingExpenses.isNotEmpty()) {
            val totalAmount = pendingExpenses.sumOf { it.monto }
            showReminderNotification(pendingExpenses.size, totalAmount, "PAGAR_GASTO", 2004)
        }

        // 4. Préstamos
        val pendingLoans = database.loanDao().getPendingCollections(timeLimit)
        if (pendingLoans.isNotEmpty()) {
            val totalAmount = pendingLoans.sumOf { it.amount - it.amountPaid }
            if (hour == 8) showMorningSummary(pendingLoans.size, totalAmount)
            else showLoanNotification(pendingLoans.size, totalAmount)
        }

        // 5. Herramientas (Activos)
        checkToolExpirations(database)
        
        // 6. Compromisos (NUEVO)
        checkCommitments(database)

        return Result.success()
    }

    private suspend fun checkCommitments(database: AppDatabase) {
        val commitments = database.commitmentDao().getActiveCommitmentsOnce()
        val prefs = context.getSharedPreferences("CommitmentNotifs", Context.MODE_PRIVATE)
        val now = Calendar.getInstance()
        
        val currentMonth = now.get(Calendar.MONTH)
        val currentYear = now.get(Calendar.YEAR)
        
        for (c in commitments) {
            // Logic for MONTH START (First run of the month)
            val monthKey = "month_start_${c.id}_${currentMonth}_${currentYear}"
            if (!prefs.getBoolean(monthKey, false)) {
                showCommitmentNotification(c, "Este mes toca pagar: ${c.description}", 10000 + c.id.hashCode())
                prefs.edit().putBoolean(monthKey, true).apply()
            }

            // Logic for 7 DAYS BEFORE and SAME DAY
            when(c.frequency) {
                "FIXED" -> {
                    // Normalizar ambos a las 00:00 para comparar días exactos
                    val todayNormalized = Calendar.getInstance().apply { 
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val dueNormalized = Calendar.getInstance().apply {
                        timeInMillis = c.dueDate
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    
                    val diffMs = dueNormalized.timeInMillis - todayNormalized.timeInMillis
                    val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()
                    
                    if (diffDays == 7) {
                        val key = "7days_${c.id}_${currentYear}"
                        if (!prefs.getBoolean(key, false)) {
                            showCommitmentNotification(c, "Faltan 7 días para: ${c.description}", 20000 + c.id.hashCode())
                            prefs.edit().putBoolean(key, true).apply()
                        }
                    } else if (diffDays == 0) {
                        val key = "today_${c.id}_${currentYear}"
                        if (!prefs.getBoolean(key, false)) {
                            showCommitmentNotification(c, "¡Hoy vence! ${c.description}", 30000 + c.id.hashCode())
                            prefs.edit().putBoolean(key, true).apply()
                        }
                    }
                }
                "MONTHLY" -> {
                    val todayNormalized = Calendar.getInstance().apply { 
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val dueInCurrentMonth = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, c.recurringDay)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    
                    val diffMs = dueInCurrentMonth.timeInMillis - todayNormalized.timeInMillis
                    val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()

                    if (diffDays == 7) {
                        val key = "7days_${c.id}_${currentMonth}_${currentYear}"
                        if (!prefs.getBoolean(key, false)) {
                            showCommitmentNotification(c, "Faltan 7 días para: ${c.description}", 20000 + c.id.hashCode())
                            prefs.edit().putBoolean(key, true).apply()
                        }
                    } else if (diffDays == 0) {
                        val key = "today_${c.id}_${currentMonth}_${currentYear}"
                        if (!prefs.getBoolean(key, false)) {
                            showCommitmentNotification(c, "¡Hoy vence! ${c.description}", 30000 + c.id.hashCode())
                            prefs.edit().putBoolean(key, true).apply()
                        }
                    }
                }
            }
        }
    }

    private fun showCommitmentNotification(commitment: CommitmentEntity, message: String, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "commitment_reminders"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Compromisos Personales", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(context, CommitmentsActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(context, notificationId, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_gomezmart)
            .setContentTitle("🤝 Recordatorio de Compromiso")
            .setContentText(message + " (S/ ${String.format(Locale.getDefault(), "%.2f", commitment.amount)})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun showMorningSummary(count: Int, total: Double) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "morning_summary"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Resumen Matutino", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent().setClassName(context.packageName, "com.gomezmart.feature_loans.LoansCollectionsActivity")
        val pendingIntent = android.app.PendingIntent.getActivity(context, 5001, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_gomezmart)
            .setContentTitle("🌅 ¡Buen día! Resumen de hoy")
            .setContentText("Hoy tienes $count cobros pendientes (S/ ${String.format(Locale.getDefault(), "%.2f", total)}).")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(5001, builder.build())
    }

    private fun showLoanNotification(count: Int, total: Double) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "loan_collections"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Cobros de Préstamos", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent().setClassName(context.packageName, "com.gomezmart.feature_loans.LoansCollectionsActivity")
        val pendingIntent = android.app.PendingIntent.getActivity(context, 4001, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_gomezmart)
            .setContentTitle("💰 Cobros de Préstamos")
            .setContentText("Tienes $count cuotas por cobrar hoy. Total: S/ ${String.format(Locale.getDefault(), "%.2f", total)}.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(4001, builder.build())
    }

    private suspend fun checkToolExpirations(database: AppDatabase) {
        val activeTools = database.hotelToolDao().getAllActiveToolsOnce()
        val prefs = context.getSharedPreferences("ToolNotifs", Context.MODE_PRIVATE)
        val now = Calendar.getInstance().apply { 
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val currentYear = now.get(Calendar.YEAR)
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR)

        for (tool in activeTools) {
            if (tool.expiryDate > 0L) {
                val expiryNormalized = Calendar.getInstance().apply {
                    timeInMillis = tool.expiryDate
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }

                val diffMs = expiryNormalized.timeInMillis - now.timeInMillis
                val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()

                // Intervalos: 7, 5, 2, 1 y hoy (0)
                val intervals = listOf(7, 5, 2, 1, 0)
                if (diffDays in intervals) {
                    val notifKey = "tool_${tool.id}_${diffDays}_${currentYear}_${dayOfYear}"
                    if (!prefs.getBoolean(notifKey, false)) {
                        val message = when (diffDays) {
                            0 -> "¡Hoy vence la vida útil de: ${tool.name}! (#${tool.code})"
                            1 -> "Mañana vence la vida útil de: ${tool.name}"
                            else -> "Faltan $diffDays días para renovar: ${tool.name}"
                        }
                        showToolNotification(message, 35000 + tool.id.hashCode() + diffDays)
                        prefs.edit().putBoolean(notifKey, true).apply()
                    }
                }
            }
        }
    }

    private fun showToolNotification(message: String, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "tool_alerts"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alertas de Activos Hotel", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent().setClassName(context.packageName, "com.gomezmart.feature_hotel.HotelToolsActivity")
        val pendingIntent = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_gomezmart)
            .setContentTitle("🛠️ Control de Vida Útil")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
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
        val content = "Tienes $count compromisos hoy por un total de S/ ${String.format(Locale.getDefault(), "%.2f", total)}."

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_gomezmart)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }
}
