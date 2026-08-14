package com.naxor.app

import android.content.Context
import android.util.Log
import com.naxor.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SyncManager(private val context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val localDb = AppDatabase.getDatabase(context)

    // --- SUBIDAS INDIVIDUALES ---

    fun syncProductToCloud(product: ProductEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("inventory").document(product.id.toString())
            .set(product, SetOptions.merge())
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("sales").document(sale.id.toString())
            .set(sale, SetOptions.merge())
    }

    fun syncDebtorToCloud(debtor: DebtorEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("debtors").document(debtor.id.toString())
            .set(debtor, SetOptions.merge())
    }

    fun syncExpenseToCloud(expense: ExpenseEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("expenses").document(expense.id.toString())
            .set(expense, SetOptions.merge())
    }

    fun syncProviderToCloud(provider: ProviderEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("providers").document(provider.id.toString())
            .set(provider, SetOptions.merge())
    }

    fun syncCustomerToCloud(customer: CustomerEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("customers").document(customer.id.toString())
            .set(customer, SetOptions.merge())
    }

    // --- BORRADOS ---

    fun deleteProductFromCloud(productId: Int) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("inventory").document(productId.toString())
            .delete()
    }

    // --- SINCRONIZACIÓN TOTAL ---

    /**
     * Sube todo lo local a la nube (Útil después de loguearse por primera vez)
     */
    fun uploadAllLocalToCloud(onComplete: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userRef = db.collection("users").document(userId)

                // 1. Productos
                val products = localDb.productDao().allProducts
                products.forEach { userRef.collection("inventory").document(it.id.toString()).set(it) }

                // 2. Ventas
                val sales = localDb.saleDao().allSales
                sales.forEach { userRef.collection("sales").document(it.id.toString()).set(it) }

                // 3. Deudores
                val debtors = localDb.debtorDao().getAllDebtors()
                debtors.forEach { userRef.collection("debtors").document(it.id.toString()).set(it) }

                // 4. Gastos
                val expenses = localDb.expenseDao().getAllExpenses()
                expenses.forEach { userRef.collection("expenses").document(it.id.toString()).set(it) }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error uploading all: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    /**
     * Descarga todo desde la nube a lo local
     */
    fun downloadEverythingFromCloud(onComplete: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userRef = db.collection("users").document(userId)

                // Descargar Productos
                val inventorySnap = userRef.collection("inventory").get().await()
                for (doc in inventorySnap.documents) {
                    val p = doc.toObject(ProductEntity::class.java)
                    if (p != null) localDb.productDao().insert(p)
                }

                // Descargar Ventas
                val salesSnap = userRef.collection("sales").get().await()
                for (doc in salesSnap.documents) {
                    val s = doc.toObject(SaleEntity::class.java)
                    if (s != null) localDb.saleDao().insert(s)
                }

                // Descargar Deudores
                val debtorsSnap = userRef.collection("debtors").get().await()
                for (doc in debtorsSnap.documents) {
                    val d = doc.toObject(DebtorEntity::class.java)
                    if (d != null) localDb.debtorDao().insertDebtor(d)
                }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error downloading everything: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
