package com.naxor.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.naxor.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.naxor.app.data.*
import com.naxor.app.util.NotificationHelper
import kotlinx.coroutines.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class SyncManager(private val context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val localDb = AppDatabase.getDatabase(context)

    // --- SUBIDAS INDIVIDUALES ---

    fun syncProductToCloud(product: ProductEntity) {
        val userId = auth.currentUser?.uid ?: return
        Log.d("SyncManager", "Intentando subir producto: ${product.nombre}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 2. Subir datos a Firestore
                db.collection("users").document(userId)
                    .collection("inventory").document(product.id)
                    .set(product, SetOptions.merge()).await()
                
                Log.d("SyncManager", "Producto subido con éxito: ${product.nombre}")
                product.isSynced = true
                localDb.productDao().update(product)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir producto: ${e.message}")
            }
        }
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("sales").document(sale.id)
            .set(sale, SetOptions.merge())
            .addOnSuccessListener {
                CoroutineScope(Dispatchers.IO).launch {
                    sale.isSynced = true
                    localDb.saleDao().update(sale)
                }
            }
    }

    fun syncExpenseToCloud(expense: ExpenseEntity) {
        val userId = auth.currentUser?.uid ?: return
        Log.d("SyncManager", "Intentando subir gasto: ${expense.concepto}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("expenses").document(expense.id)
                    .set(expense, SetOptions.merge()).await()
                
                Log.d("SyncManager", "Gasto subido con éxito: ${expense.concepto}")
                expense.isSynced = true
                localDb.expenseDao().update(expense)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir gasto: ${e.message}")
            }
        }
    }

    fun syncLogToCloud(log: MovementLogEntity) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("movement_logs").document(log.id)
                    .set(log, SetOptions.merge()).await()
                log.isSynced = true
                localDb.movementLogDao().insert(log)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir log: ${e.message}")
            }
        }
    }

    fun syncProviderToCloud(provider: ProviderEntity) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("providers").document(provider.id)
                    .set(provider, SetOptions.merge()).await()
                provider.isSynced = true
                localDb.providerDao().update(provider)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir proveedor: ${e.message}")
            }
        }
    }

    fun syncDebtorToCloud(debtor: DebtorEntity) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("debtors").document(debtor.id)
                    .set(debtor, SetOptions.merge()).await()
                debtor.isSynced = true
                localDb.debtorDao().updateDebtor(debtor)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir deudor: ${e.message}")
            }
        }
    }

    fun syncDebtDetailToCloud(debt: DebtDetailEntity) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("debt_details").document(debt.id)
                    .set(debt, SetOptions.merge()).await()
                debt.isSynced = true
                localDb.debtorDao().updateDebtDetail(debt)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir detalle deuda: ${e.message}")
            }
        }
    }

    fun syncCashSessionToCloud(session: CashSessionEntity) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("cash_sessions").document(session.id.toString())
                    .set(session, SetOptions.merge()).await()
                session.isSynced = true
                localDb.cashDao().update(session)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir sesión caja: ${e.message}")
            }
        }
    }

    fun deleteExpenseFromCloud(expenseId: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("expenses").document(expenseId)
            .delete()
    }

    // --- AJUSTES DEL NEGOCIO ---

    fun syncBusinessSettingsToCloud() {
        val userId = auth.currentUser?.uid ?: return
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        
        val settings = mutableMapOf(
            "business_name" to prefs.getString("business_name", "Mi Negocio"),
            "business_address" to prefs.getString("business_address", ""),
            "business_phone" to prefs.getString("business_phone", ""),
            "business_ruc" to prefs.getString("business_ruc", ""),
            "currency_symbol" to prefs.getString("currency_symbol", "S/"),
            "user_pin" to prefs.getString("user_pin", "")
        )

        // Subir Logo si existe
        val logoPath = prefs.getString("business_logo_local", null)
        if (!logoPath.isNullOrEmpty()) {
            val file = File(logoPath)
            if (file.exists()) {
                val logoRef = storage.reference.child("users/$userId/logo.png")
                logoRef.putFile(Uri.fromFile(file)).addOnSuccessListener {
                    logoRef.downloadUrl.addOnSuccessListener { uri: Uri ->
                        settings["business_logo_url"] = uri.toString()
                        db.collection("users").document(userId)
                            .collection("settings").document("business")
                            .set(settings, SetOptions.merge())
                    }
                }
            }
        }

        db.collection("users").document(userId)
            .collection("settings").document("business")
            .set(settings, SetOptions.merge())
    }

    suspend fun downloadBusinessSettingsFromCloud() {
        val userId = auth.currentUser?.uid ?: return
        try {
            val doc = db.collection("users").document(userId)
                .collection("settings").document("business")
                .get().await()

            if (doc.exists()) {
                val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                
                editor.putString("business_name", doc.getString("business_name"))
                editor.putString("business_address", doc.getString("business_address"))
                editor.putString("business_phone", doc.getString("business_phone"))
                editor.putString("business_ruc", doc.getString("business_ruc"))
                editor.putString("currency_symbol", doc.getString("currency_symbol"))
                editor.putString("user_pin", doc.getString("user_pin"))
                
                // Descargar logo si hay URL
                val logoUrl = doc.getString("business_logo_url")
                if (!logoUrl.isNullOrEmpty()) {
                    downloadLogoFromUrl(logoUrl)
                }
                
                editor.apply()
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error downloading settings: ${e.message}")
        }
    }

    private fun downloadLogoFromUrl(url: String) {
        val file = File(context.filesDir, "business_logo.png")
        try {
            storage.getReferenceFromUrl(url).getFile(file).addOnSuccessListener {
                val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("business_logo_local", file.absolutePath).apply()
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error downloading logo file: ${e.message}")
        }
    }

    // --- ESCUCHA EN TIEMPO REAL ---

    /**
     * Escucha cambios en la nube y los baja al celular al instante
     */
    fun startRealtimeInventorySync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        
        db.collection("users").document(userId)
            .collection("inventory")
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                
                if (snapshot != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        for (docChange in snapshot.documentChanges) {
                            val cloudProduct = docChange.document.toObject(ProductEntity::class.java) ?: continue
                            when (docChange.type) {
                                com.google.firebase.firestore.DocumentChange.Type.ADDED,
                                com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                    cloudProduct.isSynced = true
                                    localDb.productDao().insert(cloudProduct)
                                }
                                com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                    localDb.productDao().delete(cloudProduct)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) { onDataChanged() }
                    }
                }
            }
    }

    fun startRealtimeExpensesSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudExpense = docChange.document.toObject(ExpenseEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudExpense.isSynced = true
                                localDb.expenseDao().insert(cloudExpense)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                localDb.expenseDao().delete(cloudExpense)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeProvidersSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("providers")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudProvider = docChange.document.toObject(ProviderEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudProvider.isSynced = true
                                localDb.providerDao().insert(cloudProvider)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                localDb.providerDao().delete(cloudProvider)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeDebtorsSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        // Sincronizar deudores
        db.collection("users").document(userId).collection("debtors")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudDebtor = docChange.document.toObject(DebtorEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudDebtor.isSynced = true
                                localDb.debtorDao().insertDebtor(cloudDebtor)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                localDb.debtorDao().deleteDebtor(cloudDebtor)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
            
        // Sincronizar detalles de deuda
        db.collection("users").document(userId).collection("debt_details")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudDebt = docChange.document.toObject(DebtDetailEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudDebt.isSynced = true
                                localDb.debtorDao().insertDebtDetail(cloudDebt)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                localDb.debtorDao().deleteDebtDetail(cloudDebt)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeLogsSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("movement_logs")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudLog = docChange.document.toObject(MovementLogEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED -> {
                                cloudLog.isSynced = true
                                localDb.movementLogDao().insert(cloudLog)
                            }
                            else -> { /* No se borran ni editan logs por ahora */ }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeCashSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("cash_sessions")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudSession = docChange.document.toObject(CashSessionEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudSession.isSynced = true
                                localDb.cashDao().update(cloudSession)
                            }
                            else -> { /* No se borran sesiones por ahora */ }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeSalesSync(onDataChanged: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("sales")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                CoroutineScope(Dispatchers.IO).launch {
                    for (docChange in snapshot.documentChanges) {
                        val cloudSale = docChange.document.toObject(SaleEntity::class.java) ?: continue
                        when (docChange.type) {
                            com.google.firebase.firestore.DocumentChange.Type.ADDED -> {
                                val existsLocally = localDb?.saleDao()?.getSaleById(cloudSale.id) != null
                                cloudSale.isSynced = true
                                localDb?.saleDao()?.insert(cloudSale)
                                
                                // Si es nueva (no estaba local) y es reciente (uÌltimos 2 min), notificar
                                val isRecent = (System.currentTimeMillis() - cloudSale.timestamp) < 120000
                                if (!existsLocally && isRecent) {
                                    val totalStr = String.format(java.util.Locale.getDefault(), "S/ %.2f", cloudSale.total)
                                    val bizName = context.getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
                                        .getString("business_name", "Mi Negocio") ?: "Mi Negocio"
                                    NotificationHelper.showSaleNotification(context, totalStr, bizName)
                                }
                            }
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                cloudSale.isSynced = true
                                localDb?.saleDao()?.insert(cloudSale)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                localDb?.saleDao()?.delete(cloudSale)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    // --- BORRADOS ---

    fun deleteProductFromCloud(productId: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("inventory").document(productId)
            .delete()
    }

    // --- WORKMANAGER SYNC ---

    fun scheduleOfflineSync() {
        Log.d("SyncManager", "Programando sincronización...")
        // Sin restricciones de red momentáneamente para forzar ejecución
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag("offline_sync_tag")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_sync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    // --- SINCRONIZACIÓN TOTAL ---

    /**
     * Sube todo lo local a la nube de forma forzada
     */
    fun uploadAllLocalToCloud(onComplete: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userRef = db.collection("users").document(userId)

                // 1. Productos
                val products = localDb.productDao().allProducts
                products.forEach { 
                    userRef.collection("inventory").document(it.id).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.productDao().update(it)
                }

                // 2. Ventas
                val sales = localDb.saleDao().allSales
                sales.forEach { 
                    userRef.collection("sales").document(it.id).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.saleDao().update(it)
                }

                // 3. Gastos
                val expenses = localDb.expenseDao().getAllExpenses()
                expenses.forEach { 
                    userRef.collection("expenses").document(it.id).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.expenseDao().update(it)
                }

                // 4. Ajustes
                syncBusinessSettingsToCloud()

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

                // 1. Descargar Productos
                val inventorySnap = userRef.collection("inventory").get().await()
                for (doc in inventorySnap.documents) {
                    val p = doc.toObject(ProductEntity::class.java)
                    if (p != null) {
                        p.isSynced = true
                        localDb.productDao().insert(p)
                    }
                }

                // 2. Descargar Ventas
                val salesSnap = userRef.collection("sales").get().await()
                for (doc in salesSnap.documents) {
                    val s = doc.toObject(SaleEntity::class.java)
                    if (s != null) {
                        s.isSynced = true
                        localDb.saleDao().insert(s)
                    }
                }

                // 3. Descargar Gastos
                val expensesSnap = userRef.collection("expenses").get().await()
                for (doc in expensesSnap.documents) {
                    val e = doc.toObject(ExpenseEntity::class.java)
                    if (e != null) {
                        e.isSynced = true
                        localDb.expenseDao().insert(e)
                    }
                }

                // 4. Descargar Ajustes
                downloadBusinessSettingsFromCloud()

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error downloading everything: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
