package com.naxor.app

import android.content.Context
import android.util.Log
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
        Log.d("SyncWorker", "Iniciando sincronización en segundo plano...")
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.d("SyncWorker", "Usuario no autenticado, cancelando sync.")
            return Result.success()
        }
        
        val userRef = db.collection("users").document(userId)

        return try {
            // --- 1. PROCESAR BORRADOS PRIMERO ---
            
            // Productos borrados
            val deletedProducts = localDb.productDao().deletedProducts
            for (p in deletedProducts) {
                userRef.collection("inventory").document(p.id).delete().await()
                localDb.productDao().delete(p)
            }

            // Ventas borradas
            val deletedSales = localDb.saleDao().deletedSales
            for (s in deletedSales) {
                userRef.collection("sales").document(s.id).delete().await()
                localDb.saleDao().delete(s)
            }

            // Deudores borrados
            val deletedDebtors = localDb.debtorDao().getDeletedDebtors()
            for (d in deletedDebtors) {
                userRef.collection("debtors").document(d.id).delete().await()
                localDb.debtorDao().deleteDebtor(d)
            }
            
            val deletedDebts = localDb.debtorDao().getDeletedDebtDetails()
            for (dd in deletedDebts) {
                userRef.collection("debt_details").document(dd.id).delete().await()
                localDb.debtorDao().deleteDebtDetail(dd)
            }

            // Gastos borrados
            val deletedExpenses = localDb.expenseDao().getDeletedExpenses()
            for (e in deletedExpenses) {
                userRef.collection("expenses").document(e.id).delete().await()
                localDb.expenseDao().delete(e)
            }

            // Clientes borrados
            val deletedCustomers = localDb.customerDao().getDeletedCustomers()
            for (c in deletedCustomers) {
                userRef.collection("customers").document(c.id).delete().await()
                localDb.customerDao().delete(c)
            }

            // Proveedores borrados
            val deletedProviders = localDb.providerDao().getDeletedProviders()
            for (p in deletedProviders) {
                userRef.collection("providers").document(p.id).delete().await()
                localDb.providerDao().delete(p)
            }

            // --- 2. PROCESAR SUBIDAS / ACTUALIZACIONES ---

            // 1. Productos
            val products = localDb.productDao().allUnsyncedProducts
            Log.d("SyncWorker", "Productos pendientes: ${products.size}")
            for (p in products) {
                userRef.collection("inventory").document(p.id).set(p, SetOptions.merge()).await()
                p.isSynced = true
                localDb.productDao().update(p)
                Log.d("SyncWorker", "Producto sincronizado: ${p.nombre}")
            }

            // 2. Ventas
            val sales = localDb.saleDao().allUnsyncedSales
            Log.d("SyncWorker", "Ventas pendientes: ${sales.size}")
            for (s in sales) {
                userRef.collection("sales").document(s.id).set(s, SetOptions.merge()).await()
                s.isSynced = true
                localDb.saleDao().update(s)
                Log.d("SyncWorker", "Venta sincronizada: ${s.nombreProducto}")
            }

            // 3. Deudores
            val debtors = localDb.debtorDao().getAllUnsyncedDebtors()
            Log.d("SyncWorker", "Deudores pendientes: ${debtors.size}")
            for (d in debtors) {
                val updatedD = d.copy(isSynced = true)
                userRef.collection("debtors").document(d.id).set(updatedD, SetOptions.merge()).await()
                localDb.debtorDao().updateDebtor(updatedD)
                Log.d("SyncWorker", "Deudor sincronizado: ${d.nombre}")
            }
            
            val debts = localDb.debtorDao().getAllUnsyncedDebtDetails()
            Log.d("SyncWorker", "Detalles de deuda pendientes: ${debts.size}")
            for (dd in debts) {
                val updatedDD = dd.copy(isSynced = true)
                userRef.collection("debt_details").document(dd.id).set(updatedDD, SetOptions.merge()).await()
                localDb.debtorDao().updateDebtDetail(updatedDD)
                Log.d("SyncWorker", "Detalle de deuda sincronizado: ${dd.concepto}")
            }

            // 4. Gastos
            val expenses = localDb.expenseDao().getAllUnsyncedExpenses()
            Log.d("SyncWorker", "Gastos pendientes: ${expenses.size}")
            for (e in expenses) {
                val updatedE = e.copy(isSynced = true)
                userRef.collection("expenses").document(e.id).set(updatedE, SetOptions.merge()).await()
                localDb.expenseDao().update(updatedE)
                Log.d("SyncWorker", "Gasto sincronizado: ${e.concepto}")
            }

            // 5. Clientes
            val customers = localDb.customerDao().getAllUnsyncedCustomers()
            Log.d("SyncWorker", "Clientes pendientes: ${customers.size}")
            for (c in customers) {
                val updatedC = c.copy(isSynced = true)
                userRef.collection("customers").document(c.id).set(updatedC, SetOptions.merge()).await()
                localDb.customerDao().update(updatedC)
                Log.d("SyncWorker", "Cliente sincronizado: ${c.nombre}")
            }

            // 6. Proveedores
            val providers = localDb.providerDao().getAllUnsyncedProviders()
            Log.d("SyncWorker", "Proveedores pendientes: ${providers.size}")
            for (p in providers) {
                val updatedP = p.copy(isSynced = true)
                userRef.collection("providers").document(p.id).set(updatedP, SetOptions.merge()).await()
                localDb.providerDao().update(updatedP)
                Log.d("SyncWorker", "Proveedor sincronizado: ${p.nombre}")
            }

            // 7. Logs de Movimientos
            val logs = localDb.movementLogDao().getUnsyncedLogs()
            Log.d("SyncWorker", "Logs pendientes: ${logs.size}")
            for (l in logs) {
                val updatedL = l.copy(isSynced = true)
                userRef.collection("movement_logs").document(l.id).set(updatedL, SetOptions.merge()).await()
                localDb.movementLogDao().insert(updatedL)
                Log.d("SyncWorker", "Log sincronizado: ${l.title}")
            }

            Log.d("SyncWorker", "Sincronización finalizada con éxito.")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error durante la sincronización: ${e.message}")
            Result.retry()
        }
    }
}
