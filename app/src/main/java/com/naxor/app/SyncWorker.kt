package com.naxor.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.naxor.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentReference
import com.naxor.app.network.SupabaseManager
import kotlinx.coroutines.tasks.await
import java.io.File

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Firestore init failed: ${e.message}")
            null
        }
    }
    
    private val auth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Auth init failed: ${e.message}")
            null
        }
    }
    
    private val localDb = try { AppDatabase.getDatabase(appContext) } catch (e: Exception) { null }

    private fun getEffectiveUserId(): String? {
        try {
            val prefs = applicationContext.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            val isCollaborator = prefs.getBoolean("is_collaborator_mode", false)
            return if (isCollaborator) {
                prefs.getString("collaborator_owner_id", null)
            } else {
                auth?.currentUser?.uid
            }
        } catch (e: Exception) { return null }
    }

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Iniciando sincronización en segundo plano...")
        
        val firestore = db ?: return Result.success()
        val userId = getEffectiveUserId() ?: return Result.success()
        val userRef = firestore.collection("users").document(userId)

        return try {
            processAllDeletions(userRef)
            processAllUpserts(userRef, userId)
            Log.d("SyncWorker", "Sincronización finalizada con éxito.")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error durante la sincronización: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun processAllDeletions(userRef: DocumentReference) {
        val ldb = localDb ?: return
        deleteProducts(ldb, userRef)
        deleteSales(ldb, userRef)
        deleteDebtors(ldb, userRef)
        deleteExpenses(ldb, userRef)
        deleteContacts(ldb, userRef)
        deleteLoans(ldb, userRef)
        deleteCommitments(ldb, userRef)
        deleteHotelData(ldb, userRef)
    }

    private suspend fun deleteProducts(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.productDao().deletedProducts.forEach {
            userRef.collection("inventory").document(it.id).delete().await()
            ldb.productDao().delete(it)
        }
    }

    private suspend fun deleteSales(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.saleDao().deletedSales.forEach {
            userRef.collection("sales").document(it.id).delete().await()
            ldb.saleDao().delete(it)
        }
    }

    private suspend fun deleteDebtors(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.debtorDao().getDeletedDebtors().forEach {
            userRef.collection("debtors").document(it.id).delete().await()
            ldb.debtorDao().deleteDebtor(it)
        }
        ldb.debtorDao().getDeletedDebtDetails().forEach {
            userRef.collection("debt_details").document(it.id).delete().await()
            ldb.debtorDao().deleteDebtDetail(it)
        }
    }

    private suspend fun deleteExpenses(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.expenseDao().getDeletedExpenses().forEach {
            userRef.collection("expenses").document(it.id).delete().await()
            ldb.expenseDao().delete(it)
        }
    }

    private suspend fun deleteContacts(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.customerDao().getDeletedCustomers().forEach {
            userRef.collection("customers").document(it.id).delete().await()
            ldb.customerDao().delete(it)
        }
        ldb.providerDao().getDeletedProviders().forEach {
            userRef.collection("providers").document(it.id).delete().await()
            ldb.providerDao().delete(it)
        }
    }

    private suspend fun deleteLoans(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.loanDao().getDeletedClients().forEach {
            userRef.collection("loan_clients").document(it.id).delete().await()
            ldb.loanDao().deleteClient(it)
        }
        ldb.loanDao().getDeletedLoans().forEach {
            userRef.collection("loans").document(it.id).delete().await()
            ldb.loanDao().deleteLoan(it)
        }
        ldb.loanDao().getDeletedInstallments().forEach {
            userRef.collection("loan_installments").document(it.id).delete().await()
            ldb.loanDao().deleteInstallment(it)
        }
        ldb.loanDao().getDeletedLoanExpenses().forEach {
            userRef.collection("loan_expenses_system").document(it.id).delete().await()
            ldb.loanDao().deleteExpense(it)
        }
        ldb.loanDao().getDeletedPayments().forEach {
            userRef.collection("loan_payments").document(it.id).delete().await()
            ldb.loanDao().deletePayment(it)
        }
    }

    private suspend fun deleteCommitments(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.commitmentDao().getDeletedCommitments().forEach {
            userRef.collection("commitments").document(it.id).delete().await()
            ldb.commitmentDao().delete(it)
        }
    }

    private suspend fun deleteHotelData(ldb: AppDatabase, userRef: DocumentReference) {
        val hotel = ldb.hotelDao()
        hotel.getDeletedRooms().forEach {
            userRef.collection("hotel_rooms").document(it.id).delete().await()
            hotel.deleteRoomHard(it.id)
        }
        hotel.getDeletedBookings().forEach {
            userRef.collection("hotel_bookings").document(it.id).delete().await()
            hotel.deleteBookingHard(it.id)
        }
        hotel.getDeletedCharges().forEach {
            userRef.collection("hotel_charges").document(it.id).delete().await()
            hotel.deleteChargeHard(it.id)
        }
        hotel.getDeletedPayments().forEach {
            userRef.collection("hotel_payments").document(it.id).delete().await()
            hotel.deletePaymentHard(it.id)
        }
        hotel.getDeletedMaintenance().forEach {
            userRef.collection("hotel_maintenance").document(it.id).delete().await()
            hotel.deleteMaintenanceHard(it.id)
        }
        hotel.getDeletedLayouts().forEach {
            userRef.collection("hotel_room_layouts").document(it.id).delete().await()
            hotel.deleteLayoutHard(it.id)
        }
        ldb.hotelToolDao().getDeletedTools().forEach {
            userRef.collection("hotel_tools").document(it.id).delete().await()
            ldb.hotelToolDao().deleteToolHard(it.id)
        }
    }

    private suspend fun processAllUpserts(userRef: DocumentReference, userId: String) {
        val ldb = localDb ?: return
        syncInventory(ldb, userRef, userId)
        syncSalesAndExpenses(ldb, userRef)
        syncBusinessContactsAndLogs(ldb, userRef)
        syncLoansSystem(ldb, userRef)
        syncHotelSystem(ldb, userRef)
    }

    private suspend fun syncInventory(ldb: AppDatabase, userRef: DocumentReference, userId: String) {
        val products = ldb.productDao().allUnsyncedProducts
        for (p in products) {
            if (!p.photoPath.isNullOrEmpty() && p.photoUrl.isNullOrEmpty()) {
                try {
                    val file = File(p.photoPath)
                    if (file.exists()) {
                        val path = "users/$userId/products/${p.id}.jpg"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            p.photoUrl = downloadUrl
                            ldb.productDao().update(p)
                        }
                    }
                } catch (e: Exception) {}
            }
            userRef.collection("inventory").document(p.id).set(p, SetOptions.merge()).await()
            ldb.productDao().markSynced(p.id)
        }
    }

    private suspend fun syncSalesAndExpenses(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.saleDao().allUnsyncedSales.forEach {
            userRef.collection("sales").document(it.id).set(it, SetOptions.merge()).await()
            ldb.saleDao().markSynced(it.id)
        }
        ldb.expenseDao().getAllUnsyncedExpenses().forEach {
            userRef.collection("expenses").document(it.id).set(it, SetOptions.merge()).await()
            ldb.expenseDao().markSynced(it.id)
        }
        ldb.debtorDao().getAllUnsyncedDebtors().forEach {
            userRef.collection("debtors").document(it.id).set(it, SetOptions.merge()).await()
            ldb.debtorDao().markDebtorSynced(it.id)
        }
        ldb.debtorDao().getAllUnsyncedDebtDetails().forEach {
            userRef.collection("debt_details").document(it.id).set(it, SetOptions.merge()).await()
            ldb.debtorDao().markDebtDetailSynced(it.id)
        }
    }

    private suspend fun syncBusinessContactsAndLogs(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.customerDao().getAllUnsyncedCustomers().forEach {
            userRef.collection("customers").document(it.id).set(it, SetOptions.merge()).await()
            ldb.customerDao().markSynced(it.id)
        }
        ldb.providerDao().getAllUnsyncedProviders().forEach {
            userRef.collection("providers").document(it.id).set(it, SetOptions.merge()).await()
            ldb.providerDao().markSynced(it.id)
        }
        ldb.movementLogDao().getUnsyncedLogs().forEach {
            userRef.collection("movement_logs").document(it.id).set(it, SetOptions.merge()).await()
            ldb.movementLogDao().markSynced(it.id)
        }
        ldb.cashDao().getAllUnsyncedSessions().forEach {
            userRef.collection("cash_sessions").document(it.id.toString()).set(it, SetOptions.merge()).await()
            ldb.cashDao().markSynced(it.id.toString())
        }
        ldb.businessDebtDao().getPendingDebts().filter { !it.isSynced }.forEach {
            userRef.collection("business_debts").document(it.id).set(it, SetOptions.merge()).await()
            ldb.businessDebtDao().markSynced(it.id)
        }
        ldb.businessDebtDao().getPaidDebts().filter { !it.isSynced }.forEach {
            userRef.collection("business_debts").document(it.id).set(it, SetOptions.merge()).await()
            ldb.businessDebtDao().markSynced(it.id)
        }
    }

    private suspend fun syncLoansSystem(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.loanDao().getUnsyncedClients().forEach {
            userRef.collection("loan_clients").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markLoanClientSynced(it.id)
        }
        ldb.loanDao().getUnsyncedLoans().forEach {
            userRef.collection("loans").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markLoanSynced(it.id)
        }
        ldb.loanDao().getUnsyncedInstallments().forEach {
            userRef.collection("loan_installments").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markInstallmentSynced(it.id)
        }
        ldb.loanDao().getUnsyncedLoanExpenses().forEach {
            userRef.collection("loan_expenses_system").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markLoanExpenseSynced(it.id)
        }
        ldb.loanDao().getUnsyncedPayments().forEach {
            userRef.collection("loan_payments").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markLoanPaymentSynced(it.id)
        }
        ldb.loanDao().getUnsyncedBudgets().forEach {
            userRef.collection("loan_budgets").document(it.id).set(it, SetOptions.merge()).await()
            ldb.loanDao().markLoanBudgetSynced(it.id)
        }
        ldb.commitmentDao().getUnsynced().forEach {
            val updated = it.copy(isSynced = true)
            userRef.collection("commitments").document(it.id).set(updated, SetOptions.merge()).await()
            ldb.commitmentDao().updateCommitment(updated)
        }
    }

    private suspend fun syncHotelSystem(ldb: AppDatabase, userRef: DocumentReference) {
        ldb.hotelDao().getUnsyncedRooms().forEach {
            userRef.collection("hotel_rooms").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markRoomSynced(it.id)
        }
        ldb.hotelDao().getUnsyncedBookings().forEach {
            userRef.collection("hotel_bookings").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markBookingSynced(it.id)
        }
        ldb.hotelDao().getUnsyncedCharges().forEach {
            userRef.collection("hotel_charges").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markChargeSynced(it.id)
        }
        ldb.hotelDao().getUnsyncedPayments().forEach {
            userRef.collection("hotel_payments").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markPaymentSynced(it.id)
        }
        ldb.hotelDao().getUnsyncedMaintenance().forEach {
            userRef.collection("hotel_maintenance").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markMaintenanceSynced(it.id)
        }
        ldb.hotelDao().getUnsyncedLayouts().forEach {
            userRef.collection("hotel_room_layouts").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelDao().markLayoutSynced(it.id)
        }
        ldb.hotelToolDao().getUnsyncedTools().forEach {
            userRef.collection("hotel_tools").document(it.id).set(it, SetOptions.merge()).await()
            ldb.hotelToolDao().markToolSynced(it.id)
        }
    }
}
