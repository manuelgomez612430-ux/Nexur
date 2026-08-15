package com.naxor.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.naxor.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val localDb = AppDatabase.getDatabase(appContext)

    override suspend fun doWork(): Result {
        val userId = auth.currentUser?.uid ?: return Result.success()
        val userRef = db.collection("users").document(userId)

        return try {
            // 1. Productos
            val products = localDb.productDao().allUnsyncedProducts
            for (p in products) {
                userRef.collection("inventory").document(p.id.toString()).set(p, SetOptions.merge()).await()
                p.isSynced = true
                localDb.productDao().update(p)
            }

            // 2. Ventas
            val sales = localDb.saleDao().allUnsyncedSales
            for (s in sales) {
                userRef.collection("sales").document(s.id.toString()).set(s, SetOptions.merge()).await()
                s.isSynced = true
                localDb.saleDao().update(s)
            }

            // 3. Deudores
            val debtors = localDb.debtorDao().getAllUnsyncedDebtors()
            for (d in debtors) {
                val updatedD = d.copy(isSynced = true)
                userRef.collection("debtors").document(d.id.toString()).set(updatedD, SetOptions.merge()).await()
                localDb.debtorDao().updateDebtor(updatedD)
            }
            
            val debts = localDb.debtorDao().getAllUnsyncedDebtDetails()
            for (dd in debts) {
                val updatedDD = dd.copy(isSynced = true)
                userRef.collection("debt_details").document(dd.id.toString()).set(updatedDD, SetOptions.merge()).await()
                localDb.debtorDao().updateDebtDetail(updatedDD)
            }

            // 4. Gastos
            val expenses = localDb.expenseDao().getAllUnsyncedExpenses()
            for (e in expenses) {
                val updatedE = e.copy(isSynced = true)
                userRef.collection("expenses").document(e.id.toString()).set(updatedE, SetOptions.merge()).await()
                localDb.expenseDao().update(updatedE)
            }

            // 5. Clientes
            val customers = localDb.customerDao().getAllUnsyncedCustomers()
            for (c in customers) {
                val updatedC = c.copy(isSynced = true)
                userRef.collection("customers").document(c.id.toString()).set(updatedC, SetOptions.merge()).await()
                localDb.customerDao().update(updatedC)
            }

            // 6. Proveedores
            val providers = localDb.providerDao().getAllUnsyncedProviders()
            for (p in providers) {
                val updatedP = p.copy(isSynced = true)
                userRef.collection("providers").document(p.id.toString()).set(updatedP, SetOptions.merge()).await()
                localDb.providerDao().update(updatedP)
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("SyncWorker", "Error during sync: ${e.message}")
            Result.retry()
        }
    }
}
