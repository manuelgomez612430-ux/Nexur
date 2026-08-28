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

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val localDb = AppDatabase.getDatabase(appContext)

    private fun getEffectiveUserId(): String? {
        val prefs = applicationContext.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollaborator = prefs.getBoolean("is_collaborator_mode", false)
        return if (isCollaborator) {
            prefs.getString("collaborator_owner_id", null)
        } else {
            auth.currentUser?.uid
        }
    }

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Iniciando sincronización en segundo plano...")
        val userId = getEffectiveUserId() ?: return Result.success()
        val userRef = db.collection("users").document(userId)

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
        deleteProducts(userRef)
        deleteSales(userRef)
        deleteDebtors(userRef)
        deleteExpenses(userRef)
        deleteContacts(userRef)
        deleteLoans(userRef)
        deleteCommitments(userRef)
        deleteHotelData(userRef)
    }

    private suspend fun deleteProducts(userRef: DocumentReference) {
        localDb.productDao().deletedProducts.forEach {
            userRef.collection("inventory").document(it.id).delete().await()
            localDb.productDao().delete(it)
        }
    }

    private suspend fun deleteSales(userRef: DocumentReference) {
        localDb.saleDao().deletedSales.forEach {
            userRef.collection("sales").document(it.id).delete().await()
            localDb.saleDao().delete(it)
        }
    }

    private suspend fun deleteDebtors(userRef: DocumentReference) {
        localDb.debtorDao().getDeletedDebtors().forEach {
            userRef.collection("debtors").document(it.id).delete().await()
            localDb.debtorDao().deleteDebtor(it)
        }
        localDb.debtorDao().getDeletedDebtDetails().forEach {
            userRef.collection("debt_details").document(it.id).delete().await()
            localDb.debtorDao().deleteDebtDetail(it)
        }
    }

    private suspend fun deleteExpenses(userRef: DocumentReference) {
        localDb.expenseDao().getDeletedExpenses().forEach {
            userRef.collection("expenses").document(it.id).delete().await()
            localDb.expenseDao().delete(it)
        }
    }

    private suspend fun deleteContacts(userRef: DocumentReference) {
        localDb.customerDao().getDeletedCustomers().forEach {
            userRef.collection("customers").document(it.id).delete().await()
            localDb.customerDao().delete(it)
        }
        localDb.providerDao().getDeletedProviders().forEach {
            userRef.collection("providers").document(it.id).delete().await()
            localDb.providerDao().delete(it)
        }
    }

    private suspend fun deleteLoans(userRef: DocumentReference) {
        localDb.loanDao().getDeletedClients().forEach {
            userRef.collection("loan_clients").document(it.id).delete().await()
            localDb.loanDao().deleteClient(it)
        }
        localDb.loanDao().getDeletedLoans().forEach {
            userRef.collection("loans").document(it.id).delete().await()
            localDb.loanDao().deleteLoan(it)
        }
        localDb.loanDao().getDeletedInstallments().forEach {
            userRef.collection("loan_installments").document(it.id).delete().await()
            localDb.loanDao().deleteInstallment(it)
        }
        localDb.loanDao().getDeletedLoanExpenses().forEach {
            userRef.collection("loan_expenses_system").document(it.id).delete().await()
            localDb.loanDao().deleteExpense(it)
        }
        localDb.loanDao().getDeletedPayments().forEach {
            userRef.collection("loan_payments").document(it.id).delete().await()
            localDb.loanDao().deletePayment(it)
        }
    }

    private suspend fun deleteCommitments(userRef: DocumentReference) {
        localDb.commitmentDao().getDeletedCommitments().forEach {
            userRef.collection("commitments").document(it.id).delete().await()
            localDb.commitmentDao().delete(it)
        }
    }

    private suspend fun deleteHotelData(userRef: DocumentReference) {
        val hotel = localDb.hotelDao()
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
        localDb.hotelToolDao().getDeletedTools().forEach {
            userRef.collection("hotel_tools").document(it.id).delete().await()
            localDb.hotelToolDao().deleteToolHard(it.id)
        }
    }

    private suspend fun processAllUpserts(userRef: DocumentReference, userId: String) {
        syncInventory(userRef, userId)
        syncSalesAndExpenses(userRef)
        syncBusinessContactsAndLogs(userRef)
        syncLoansSystem(userRef)
        syncHotelSystem(userRef)
    }

    private suspend fun syncInventory(userRef: DocumentReference, userId: String) {
        val products = localDb.productDao().allUnsyncedProducts
        for (p in products) {
            if (!p.photoPath.isNullOrEmpty() && p.photoUrl.isNullOrEmpty()) {
                try {
                    val file = File(p.photoPath)
                    if (file.exists()) {
                        val path = "users/$userId/products/${p.id}.jpg"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            p.photoUrl = downloadUrl
                            localDb.productDao().update(p)
                        }
                    }
                } catch (e: Exception) {}
            }
            userRef.collection("inventory").document(p.id).set(p, SetOptions.merge()).await()
            localDb.productDao().markSynced(p.id)
        }
    }

    private suspend fun syncSalesAndExpenses(userRef: DocumentReference) {
        localDb.saleDao().allUnsyncedSales.forEach {
            userRef.collection("sales").document(it.id).set(it, SetOptions.merge()).await()
            localDb.saleDao().markSynced(it.id)
        }
        localDb.expenseDao().getAllUnsyncedExpenses().forEach {
            userRef.collection("expenses").document(it.id).set(it, SetOptions.merge()).await()
            localDb.expenseDao().markSynced(it.id)
        }
        localDb.debtorDao().getAllUnsyncedDebtors().forEach {
            userRef.collection("debtors").document(it.id).set(it, SetOptions.merge()).await()
            localDb.debtorDao().markDebtorSynced(it.id)
        }
        localDb.debtorDao().getAllUnsyncedDebtDetails().forEach {
            userRef.collection("debt_details").document(it.id).set(it, SetOptions.merge()).await()
            localDb.debtorDao().markDebtDetailSynced(it.id)
        }
    }

    private suspend fun syncBusinessContactsAndLogs(userRef: DocumentReference) {
        localDb.customerDao().getAllUnsyncedCustomers().forEach {
            userRef.collection("customers").document(it.id).set(it, SetOptions.merge()).await()
            localDb.customerDao().markSynced(it.id)
        }
        localDb.providerDao().getAllUnsyncedProviders().forEach {
            userRef.collection("providers").document(it.id).set(it, SetOptions.merge()).await()
            localDb.providerDao().markSynced(it.id)
        }
        localDb.movementLogDao().getUnsyncedLogs().forEach {
            userRef.collection("movement_logs").document(it.id).set(it, SetOptions.merge()).await()
            localDb.movementLogDao().markSynced(it.id)
        }
        localDb.cashDao().getAllUnsyncedSessions().forEach {
            userRef.collection("cash_sessions").document(it.id.toString()).set(it, SetOptions.merge()).await()
            localDb.cashDao().markSynced(it.id.toString())
        }
        localDb.businessDebtDao().getPendingDebts().filter { !it.isSynced }.forEach {
            userRef.collection("business_debts").document(it.id).set(it, SetOptions.merge()).await()
            localDb.businessDebtDao().markSynced(it.id)
        }
        localDb.businessDebtDao().getPaidDebts().filter { !it.isSynced }.forEach {
            userRef.collection("business_debts").document(it.id).set(it, SetOptions.merge()).await()
            localDb.businessDebtDao().markSynced(it.id)
        }
    }

    private suspend fun syncLoansSystem(userRef: DocumentReference) {
        localDb.loanDao().getUnsyncedClients().forEach {
            userRef.collection("loan_clients").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markLoanClientSynced(it.id)
        }
        localDb.loanDao().getUnsyncedLoans().forEach {
            userRef.collection("loans").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markLoanSynced(it.id)
        }
        localDb.loanDao().getUnsyncedInstallments().forEach {
            userRef.collection("loan_installments").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markInstallmentSynced(it.id)
        }
        localDb.loanDao().getUnsyncedLoanExpenses().forEach {
            userRef.collection("loan_expenses_system").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markLoanExpenseSynced(it.id)
        }
        localDb.loanDao().getUnsyncedPayments().forEach {
            userRef.collection("loan_payments").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markLoanPaymentSynced(it.id)
        }
        localDb.loanDao().getUnsyncedBudgets().forEach {
            userRef.collection("loan_budgets").document(it.id).set(it, SetOptions.merge()).await()
            localDb.loanDao().markLoanBudgetSynced(it.id)
        }
        localDb.commitmentDao().getUnsynced().forEach {
            val updated = it.copy(isSynced = true)
            userRef.collection("commitments").document(it.id).set(updated, SetOptions.merge()).await()
            localDb.commitmentDao().updateCommitment(updated)
        }
    }

    private suspend fun syncHotelSystem(userRef: DocumentReference) {
        localDb.hotelDao().getUnsyncedRooms().forEach {
            userRef.collection("hotel_rooms").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markRoomSynced(it.id)
        }
        localDb.hotelDao().getUnsyncedBookings().forEach {
            userRef.collection("hotel_bookings").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markBookingSynced(it.id)
        }
        localDb.hotelDao().getUnsyncedCharges().forEach {
            userRef.collection("hotel_charges").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markChargeSynced(it.id)
        }
        localDb.hotelDao().getUnsyncedPayments().forEach {
            userRef.collection("hotel_payments").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markPaymentSynced(it.id)
        }
        localDb.hotelDao().getUnsyncedMaintenance().forEach {
            userRef.collection("hotel_maintenance").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markMaintenanceSynced(it.id)
        }
        localDb.hotelDao().getUnsyncedLayouts().forEach {
            userRef.collection("hotel_room_layouts").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelDao().markLayoutSynced(it.id)
        }
        localDb.hotelToolDao().getUnsyncedTools().forEach {
            userRef.collection("hotel_tools").document(it.id).set(it, SetOptions.merge()).await()
            localDb.hotelToolDao().markToolSynced(it.id)
        }
    }
}
