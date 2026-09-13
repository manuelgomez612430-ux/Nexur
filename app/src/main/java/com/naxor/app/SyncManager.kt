package com.naxor.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.naxor.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.naxor.app.network.SupabaseManager
import com.naxor.app.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction
import java.io.File
import java.net.URL
import java.util.Locale

class SyncManager(private val context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val localDb = AppDatabase.getDatabase(context)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getEffectiveUserId(): String? {
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollaborator = prefs.getBoolean("is_collaborator_mode", false)
        return if (isCollaborator) {
            prefs.getString("collaborator_owner_id", null)
        } else {
            auth.currentUser?.uid
        }
    }

    // --- SUBIDAS INDIVIDUALES ---

    fun syncProductToCloud(product: ProductEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                if (!product.photoPath.isNullOrEmpty() && product.photoUrl.isNullOrEmpty()) {
                    val file = File(product.photoPath)
                    if (file.exists()) {
                        val path = "users/$userId/products/${product.id}_${System.currentTimeMillis()}.jpg"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) product.photoUrl = downloadUrl
                    }
                }
                db.collection("users").document(userId).collection("inventory").document(product.id).set(product).await()
                product.isSynced = true
                localDb.productDao().update(product)
            } catch (e: Exception) { Log.e("SyncManager", "Error product: ${e.message}") }
        }
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("sales").document(sale.id).set(sale, SetOptions.merge()).await()
                sale.isSynced = true
                localDb.saleDao().update(sale)
            } catch (e: Exception) { Log.e("SyncManager", "Error sale: ${e.message}") }
        }
    }

    fun syncExpenseToCloud(expense: ExpenseEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("expenses").document(expense.id).set(expense, SetOptions.merge()).await()
                expense.isSynced = true
                localDb.expenseDao().update(expense)
            } catch (e: Exception) { Log.e("SyncManager", "Error expense: ${e.message}") }
        }
    }

    fun syncLogToCloud(log: MovementLogEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("movement_logs").document(log.id).set(log, SetOptions.merge()).await()
                log.isSynced = true
                localDb.movementLogDao().insert(log)
            } catch (e: Exception) { Log.e("SyncManager", "Error log: ${e.message}") }
        }
    }

    fun syncProviderToCloud(provider: ProviderEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("providers").document(provider.id).set(provider, SetOptions.merge()).await()
                provider.isSynced = true
                localDb.providerDao().update(provider)
            } catch (e: Exception) { Log.e("SyncManager", "Error provider: ${e.message}") }
        }
    }

    fun syncDebtorToCloud(debtor: DebtorEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("debtors").document(debtor.id).set(debtor, SetOptions.merge()).await()
                debtor.isSynced = true
                localDb.debtorDao().updateDebtor(debtor)
            } catch (e: Exception) { Log.e("SyncManager", "Error debtor: ${e.message}") }
        }
    }

    fun syncBusinessDebtToCloud(debt: BusinessDebtEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("business_debts").document(debt.id).set(debt, SetOptions.merge()).await()
                debt.isSynced = true
                localDb.businessDebtDao().update(debt)
            } catch (e: Exception) { Log.e("SyncManager", "Error business debt: ${e.message}") }
        }
    }

    fun syncCashSessionToCloud(session: CashSessionEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("cash_sessions").document(session.id.toString()).set(session, SetOptions.merge()).await()
                session.isSynced = true
                localDb.cashDao().update(session)
            } catch (e: Exception) { Log.e("SyncManager", "Error cash session: ${e.message}") }
        }
    }

    fun syncHotelBookingToCloud(booking: HotelBookingEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_bookings").document(booking.id).set(booking, SetOptions.merge()).await()
                booking.isSynced = true
                localDb.hotelDao().updateBooking(booking)
            } catch (e: Exception) { Log.e("SyncManager", "Error hotel booking: ${e.message}") }
        }
    }

    fun syncHotelRoomToCloud(room: HotelRoomEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_rooms").document(room.id).set(room, SetOptions.merge()).await()
                room.isSynced = true
                localDb.hotelDao().updateRoom(room)
            } catch (e: Exception) { Log.e("SyncManager", "Error hotel room: ${e.message}") }
        }
    }

    fun syncHotelMaintenanceToCloud(report: HotelMaintenanceEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_maintenance").document(report.id).set(report, SetOptions.merge()).await()
                report.isSynced = true
                localDb.hotelDao().updateMaintenanceReport(report)
            } catch (e: Exception) { Log.e("SyncManager", "Error maintenance: ${e.message}") }
        }
    }

    fun syncHotelChargeToCloud(charge: HotelChargeEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_charges").document(charge.id).set(charge, SetOptions.merge()).await()
                charge.isSynced = true
                localDb.hotelDao().insertCharge(charge)
            } catch (e: Exception) { Log.e("SyncManager", "Error charge: ${e.message}") }
        }
    }

    fun syncHotelPaymentToCloud(payment: HotelPaymentEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_payments").document(payment.id).set(payment, SetOptions.merge()).await()
                payment.isSynced = true
                localDb.hotelDao().insertPayment(payment)
            } catch (e: Exception) { Log.e("SyncManager", "Error payment: ${e.message}") }
        }
    }

    fun syncHotelLayoutToCloud(layout: HotelRoomLayoutEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_layouts").document(layout.id).set(layout, SetOptions.merge()).await()
                layout.isSynced = true
                localDb.hotelDao().updateLayout(layout)
            } catch (e: Exception) { Log.e("SyncManager", "Error layout: ${e.message}") }
        }
    }

    fun syncHotelToolToCloud(tool: HotelToolEntity) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("hotel_tools").document(tool.id).set(tool, SetOptions.merge()).await()
                tool.isSynced = true
                localDb.hotelToolDao().updateTool(tool)
            } catch (e: Exception) { Log.e("SyncManager", "Error tool: ${e.message}") }
        }
    }

    fun syncBusinessSettingsToCloud() {
        val userId = getEffectiveUserId() ?: return
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val settings = mapOf(
            "business_name" to prefs.getString("business_name", ""),
            "business_type" to prefs.getString("business_type", ""),
            "currency" to prefs.getString("currency", "$")
        )
        syncScope.launch {
            try {
                db.collection("users").document(userId).collection("settings").document("business_info").set(settings, SetOptions.merge()).await()
            } catch (e: Exception) { Log.e("SyncManager", "Error settings: ${e.message}") }
        }
    }

    // --- SINCRONIZACIÓN COMPLETA ---

    fun downloadEverythingFromCloud(onComplete: () -> Unit) {
        val userId = getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                val inventory = db.collection("users").document(userId).collection("inventory").get().await()
                inventory.documents.forEach { doc ->
                    val p = doc.toObject(ProductEntity::class.java)
                    if (p != null) { p.isSynced = true; localDb.productDao().insert(p) }
                }

                val sales = db.collection("users").document(userId).collection("sales").get().await()
                sales.documents.forEach { doc ->
                    val s = doc.toObject(SaleEntity::class.java)
                    if (s != null) { s.isSynced = true; localDb.saleDao().insert(s) }
                }

                val expenses = db.collection("users").document(userId).collection("expenses").get().await()
                expenses.documents.forEach { doc ->
                    val ex = doc.toObject(ExpenseEntity::class.java)
                    if (ex != null) { ex.isSynced = true; localDb.expenseDao().insert(ex) }
                }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) { 
                Log.e("SyncManager", "Error full sync: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    // --- REALTIME LISTENERS ---

    fun startRealtimeSalesSync(onNewSale: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("sales")
            .whereGreaterThan("timestamp", System.currentTimeMillis() - 86400000)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val sale = change.document.toObject(SaleEntity::class.java)
                            if (localDb.saleDao().getSaleById(sale.id) == null) {
                                localDb.saleDao().insert(sale)
                                withContext(Dispatchers.Main) { onNewSale() }
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeInventorySync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("inventory")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val product = change.document.toObject(ProductEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.productDao().insert(product)
                            DocumentChange.Type.REMOVED -> localDb.productDao().delete(product)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLogsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("movement_logs")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val log = change.document.toObject(MovementLogEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.movementLogDao().insert(log)
                            DocumentChange.Type.REMOVED -> localDb.movementLogDao().delete(log)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeExpensesSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val expense = change.document.toObject(ExpenseEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.expenseDao().insert(expense)
                            DocumentChange.Type.REMOVED -> localDb.expenseDao().delete(expense)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeDebtorsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("debtors")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val debtor = change.document.toObject(DebtorEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.debtorDao().insertDebtor(debtor)
                            DocumentChange.Type.REMOVED -> localDb.debtorDao().deleteDebtor(debtor)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeProvidersSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("providers")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val provider = change.document.toObject(ProviderEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.providerDao().insert(provider)
                            DocumentChange.Type.REMOVED -> localDb.providerDao().delete(provider)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeCashSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("cash_sessions")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val session = change.document.toObject(CashSessionEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.cashDao().insert(session)
                            DocumentChange.Type.REMOVED -> localDb.cashDao().delete(session)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeHotelSync(onUpdate: () -> Unit): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val regs = mutableListOf<ListenerRegistration>()
        
        val r1 = db.collection("users").document(userId).collection("hotel_bookings")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val booking = change.document.toObject(HotelBookingEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.hotelDao().insertBooking(booking)
                            DocumentChange.Type.REMOVED -> localDb.hotelDao().deleteBooking(booking)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
        regs.add(r1)

        val r2 = db.collection("users").document(userId).collection("hotel_rooms")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val room = change.document.toObject(HotelRoomEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.hotelDao().insertRoom(room)
                            DocumentChange.Type.REMOVED -> localDb.hotelDao().deleteRoom(room)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
        regs.add(r2)
        
        return regs
    }

    fun startRealtimeLoanClientsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_clients")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val client = change.document.toObject(LoanClientEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.loanDao().insertClient(client)
                            DocumentChange.Type.REMOVED -> localDb.loanDao().deleteClient(client)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoansSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loans")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val loan = change.document.toObject(LoanEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.loanDao().insertLoan(loan)
                            DocumentChange.Type.REMOVED -> localDb.loanDao().deleteLoan(loan)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeInstallmentsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_installments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val installment = change.document.toObject(LoanInstallmentEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.loanDao().insertInstallments(listOf(installment))
                            DocumentChange.Type.REMOVED -> localDb.loanDao().deleteInstallment(installment)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoanPaymentsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_payments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val payment = change.document.toObject(LoanPaymentEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.loanDao().insertPayment(payment)
                            DocumentChange.Type.REMOVED -> localDb.loanDao().deletePayment(payment)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoanExpensesSystemSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val expense = change.document.toObject(LoanExpenseEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb.loanDao().insertExpense(expense)
                            DocumentChange.Type.REMOVED -> localDb.loanDao().deleteExpense(expense)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeRequestsSync(): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("booking_requests")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val req = change.document.toObject(HotelBookingRequest::class.java)
                            NotificationHelper.showRequestNotification(context, req.guestName)
                        }
                    }
                }
            }
    }

    fun startRealtimeMessagesSync(): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val regs = mutableListOf<ListenerRegistration>()
        val r = db.collection("users").document(userId).collection("messages")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val msg = change.document.toObject(AppMessage::class.java)
                            NotificationHelper.showMessageNotification(context, msg.title, msg.content)
                        }
                    }
                }
            }
        regs.add(r)
        return regs
    }

    fun scheduleOfflineSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("offline_sync", ExistingWorkPolicy.KEEP, syncRequest)
    }

    fun uploadAllLocalToCloud(onComplete: () -> Unit) {
        getEffectiveUserId() ?: return
        syncScope.launch {
            try {
                // Sincronizar productos no sincronizados
                val unsyncedProducts = localDb.productDao().getAllUnsyncedProducts()
                unsyncedProducts.forEach { syncProductToCloud(it) }

                // Sincronizar deudores no sincronizados
                val unsyncedDebtors = localDb.debtorDao().getAllUnsyncedDebtors()
                unsyncedDebtors.forEach { syncDebtorToCloud(it) }

                // Sincronizar gastos no sincronizados
                val unsyncedExpenses = localDb.expenseDao().getAllUnsyncedExpenses()
                unsyncedExpenses.forEach { syncExpenseToCloud(it) }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error uploading all: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
