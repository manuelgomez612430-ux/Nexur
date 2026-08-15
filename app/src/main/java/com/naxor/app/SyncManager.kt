package com.naxor.app

import android.content.Context
import android.util.Log
import androidx.work.*
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
        Log.d("SyncManager", "Intentando subir producto: ${product.nombre}")
        db.collection("users").document(userId)
            .collection("inventory").document(product.id.toString())
            .set(product, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("SyncManager", "Producto subido con éxito: ${product.nombre}")
            }
            .addOnFailureListener { e ->
                Log.e("SyncManager", "Error al subir producto: ${e.message}")
            }
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("sales").document(sale.id.toString())
            .set(sale, SetOptions.merge())
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
                            val cloudProduct = docChange.document.toObject(ProductEntity::class.java)
                            when (docChange.type) {
                                com.google.firebase.firestore.DocumentChange.Type.ADDED,
                                com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                    // Marcar como sincronizado al bajarlo
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

    // --- BORRADOS ---

    fun deleteProductFromCloud(productId: Int) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("inventory").document(productId.toString())
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
                    userRef.collection("inventory").document(it.id.toString()).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.productDao().update(it)
                }

                // 2. Ventas
                val sales = localDb.saleDao().allSales
                sales.forEach { 
                    userRef.collection("sales").document(it.id.toString()).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.saleDao().update(it)
                }

                // Otros modelos (Deudores, Gastos, etc. se podrÃ­an aÃ±adir aquÃ­ tambiÃ©n)

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

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error downloading everything: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
