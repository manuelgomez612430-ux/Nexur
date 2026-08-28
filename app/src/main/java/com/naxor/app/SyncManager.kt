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
import com.naxor.app.data.*
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
        Log.d("SyncManager", "Intentando subir producto: ${product.nombre}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Subir imagen a Supabase Storage si no tiene URL o se forzó reseteo
                if (!product.photoPath.isNullOrEmpty() && product.photoUrl.isNullOrEmpty()) {
                    val file = File(product.photoPath)
                    if (file.exists()) {
                        val path = "users/$userId/products/${product.id}_${System.currentTimeMillis()}.jpg"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            product.photoUrl = downloadUrl
                        }
                    }
                }

                // 2. Subir datos a Firestore (Sobreescribimos para asegurar actualización total)
                db.collection("users").document(userId)
                    .collection("inventory").document(product.id)
                    .set(product).await()
                
                Log.d("SyncManager", "Producto subido con éxito: ${product.nombre}")
                product.isSynced = true
                localDb.productDao().update(product)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir producto: ${e.message}")
            }
        }
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = getEffectiveUserId() ?: return
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
        val userId = getEffectiveUserId() ?: return
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
        val userId = getEffectiveUserId() ?: return
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
        val userId = getEffectiveUserId() ?: return
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
        val userId = getEffectiveUserId() ?: return
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

    fun syncBusinessDebtToCloud(debt: BusinessDebtEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("business_debts").document(debt.id)
                    .set(debt, SetOptions.merge()).await()
                debt.isSynced = true
                localDb.businessDebtDao().update(debt)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error al subir deuda negocio: ${e.message}")
            }
        }
    }

    fun syncDebtDetailToCloud(debt: DebtDetailEntity) {
        val userId = getEffectiveUserId() ?: return
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
        val userId = getEffectiveUserId() ?: return
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

    // --- SINCRONIZACIÓN DE PRÉSTAMOS (SISTEMA NUEVO) ---

    fun syncLoanClientToCloud(client: LoanClientEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("loan_clients").document(client.id)
                    .set(client, SetOptions.merge()).await()
                client.isSynced = true
                localDb.loanDao().updateClient(client)
            } catch (e: Exception) {}
        }
    }

    fun syncLoanToCloud(loan: LoanEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("loans").document(loan.id)
                    .set(loan, SetOptions.merge()).await()
                loan.isSynced = true
                localDb.loanDao().updateLoan(loan)
            } catch (e: Exception) {}
        }
    }

    fun syncLoanInstallmentToCloud(installment: LoanInstallmentEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("loan_installments").document(installment.id)
                    .set(installment, SetOptions.merge()).await()
            } catch (e: Exception) {}
        }
    }

    fun syncLoanPaymentToCloud(payment: LoanPaymentEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("loan_payments").document(payment.id)
                    .set(payment, SetOptions.merge()).await()
            } catch (e: Exception) {}
        }
    }

    fun syncCommitmentToCloud(commitment: CommitmentEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("commitments").document(commitment.id)
                    .set(commitment, SetOptions.merge()).await()
                commitment.isSynced = true
                localDb.commitmentDao().updateCommitment(commitment)
            } catch (e: Exception) {}
        }
    }

    // --- SINCRONIZACIÓN DE HOTEL ---

    fun syncHotelRoomToCloud(room: HotelRoomEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Subir imagen si existe localmente y no en la nube
                if (!room.photoPath.isNullOrEmpty() && room.photoUrl.isNullOrEmpty()) {
                    val file = File(room.photoPath)
                    if (file.exists()) {
                        val path = "users/$userId/hotel_rooms/${room.id}.jpg"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            // No podemos editar el objeto 'room' directamente si es inmutable o viene de Room
                            val updatedRoom = room.copy(photoUrl = downloadUrl)
                            db.collection("users").document(userId)
                                .collection("hotel_rooms").document(room.id)
                                .set(updatedRoom, SetOptions.merge()).await()
                            
                            localDb.hotelDao().updateRoom(updatedRoom)
                            return@launch
                        }
                    }
                }

                db.collection("users").document(userId)
                    .collection("hotel_rooms").document(room.id)
                    .set(room, SetOptions.merge()).await()
                
                room.isSynced = true
                localDb.hotelDao().updateRoom(room)
            } catch (e: Exception) {
                Log.e("SyncManager", "Error syncing hotel room: ${e.message}")
            }
        }
    }

    fun syncHotelBookingToCloud(booking: HotelBookingEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_bookings").document(booking.id)
                    .set(booking, SetOptions.merge()).await()
                booking.isSynced = true
                localDb.hotelDao().updateBooking(booking)
            } catch (e: Exception) {}
        }
    }

    fun syncHotelChargeToCloud(charge: HotelChargeEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_charges").document(charge.id)
                    .set(charge, SetOptions.merge()).await()
                charge.isSynced = true
                localDb.hotelDao().insertCharge(charge)
            } catch (e: Exception) {}
        }
    }

    fun syncHotelPaymentToCloud(payment: HotelPaymentEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_payments").document(payment.id)
                    .set(payment, SetOptions.merge()).await()
                payment.isSynced = true
                localDb.hotelDao().insertPayment(payment)
            } catch (e: Exception) {}
        }
    }

    fun syncHotelMaintenanceToCloud(report: HotelMaintenanceEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_maintenance").document(report.id)
                    .set(report, SetOptions.merge()).await()
                report.isSynced = true
                localDb.hotelDao().updateMaintenanceReport(report)
            } catch (e: Exception) {}
        }
    }

    fun syncHotelToolToCloud(tool: HotelToolEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_tools").document(tool.id)
                    .set(tool, SetOptions.merge()).await()
                tool.isSynced = true
                localDb.hotelToolDao().updateTool(tool)
            } catch (e: Exception) {}
        }
    }

    fun syncHotelLayoutToCloud(layout: HotelRoomLayoutEntity) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(userId)
                    .collection("hotel_room_layouts").document(layout.id)
                    .set(layout, SetOptions.merge()).await()
                layout.isSynced = true
                localDb.hotelDao().updateLayout(layout)
            } catch (e: Exception) {}
        }
    }

    fun deleteExpenseFromCloud(expenseId: String) {
        val userId = getEffectiveUserId() ?: return
        db.collection("users").document(userId)
            .collection("expenses").document(expenseId)
            .delete()
    }

    // --- AJUSTES DEL NEGOCIO ---

    fun syncBusinessSettingsToCloud() {
        val userId = getEffectiveUserId() ?: return
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        val settings = mutableMapOf<String, Any?>(
            "business_name" to prefs.getString("business_name", "Mi Negocio"),
            "business_address" to prefs.getString("business_address", ""),
            "business_phone" to prefs.getString("business_phone", ""),
            "business_ruc" to prefs.getString("business_ruc", ""),
            "business_type" to bType,
            "currency_symbol" to prefs.getString("currency_symbol", "S/"),
            "user_pin" to prefs.getString("user_pin", ""),
            "business_description" to prefs.getString("${bType}_description", ""),
            "business_owner_name" to prefs.getString("${bType}_owner_name", ""),
            
            "${bType}_name" to prefs.getString("${bType}_name", ""),
            "${bType}_ruc" to prefs.getString("${bType}_ruc", ""),
            "${bType}_address" to prefs.getString("${bType}_address", ""),
            "${bType}_phone" to prefs.getString("${bType}_phone", ""),
            "${bType}_owner_name" to prefs.getString("${bType}_owner_name", ""),
            "${bType}_owner_dni" to prefs.getString("${bType}_owner_dni", ""),
            
            "${bType}_show_owner" to prefs.getBoolean("${bType}_show_owner", false),
            "${bType}_show_business" to prefs.getBoolean("${bType}_show_business", true),
            "${bType}_show_address" to prefs.getBoolean("${bType}_show_address", true),
            "${bType}_show_phone" to prefs.getBoolean("${bType}_show_phone", true),
            "is_online" to prefs.getBoolean("is_online_store", false),
            "creation_date" to (prefs.getLong("${bType}_creation_date", 0L).let { if (it == 0L) System.currentTimeMillis().also { now -> prefs.edit().putLong("${bType}_creation_date", now).apply() } else it })
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Subir Logo si existe localmente para este rubro
                val logoPath = prefs.getString("${bType}_logo_local", null)
                if (!logoPath.isNullOrEmpty()) {
                    val file = File(logoPath)
                    if (file.exists()) {
                        val path = "users/$userId/logo_$bType.png"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            settings["business_logo_url"] = downloadUrl
                        }
                    }
                }

                // 1.5 Subir Portada si existe localmente para este rubro
                val coverPath = prefs.getString("${bType}_cover_local", null)
                if (!coverPath.isNullOrEmpty()) {
                    val file = File(coverPath)
                    if (file.exists()) {
                        val path = "users/$userId/cover_$bType.png"
                        val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                        if (downloadUrl != null) {
                            settings["business_cover_url"] = downloadUrl
                        }
                    }
                }

                // 2. Guardar en Firestore usando el rubro como ID del documento (SEPARACIÓN TOTAL)
                db.collection("users").document(userId)
                    .collection("settings").document(bType)
                    .set(settings, SetOptions.merge()).await()
                
                // Mantenemos 'business' por compatibilidad temporal si es necesario, 
                // pero lo ideal es que todo migre a bType
                Log.d("SyncManager", "Configuración de $bType sincronizada")
            } catch (e: Exception) {
                Log.e("SyncManager", "Error sincronizando perfil: ${e.message}")
            }
        }
    }

    suspend fun downloadBusinessSettingsFromCloud(customRubro: String? = null) {
        val userId = getEffectiveUserId() ?: return
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = customRubro ?: prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        try {
            val doc = db.collection("users").document(userId)
                .collection("settings").document(bType)
                .get().await()

            if (doc.exists()) {
                val editor = prefs.edit()
                
                editor.putString("business_name", doc.getString("business_name"))
                editor.putString("business_address", doc.getString("business_address"))
                editor.putString("business_phone", doc.getString("business_phone"))
                editor.putString("business_ruc", doc.getString("business_ruc"))
                editor.putString("business_type", bType)
                editor.putString("currency_symbol", doc.getString("currency_symbol"))
                editor.putString("user_pin", doc.getString("user_pin"))
                
                // Descargar campos dinámicos
                editor.putString("${bType}_name", doc.getString("${bType}_name") ?: doc.getString("business_name"))
                editor.putString("${bType}_ruc", doc.getString("${bType}_ruc"))
                editor.putString("${bType}_address", doc.getString("${bType}_address"))
                editor.putString("${bType}_phone", doc.getString("${bType}_phone"))
                editor.putString("${bType}_owner_name", doc.getString("${bType}_owner_name") ?: doc.getString("business_owner_name"))
                editor.putString("${bType}_owner_dni", doc.getString("${bType}_owner_dni"))
                
                editor.putBoolean("${bType}_show_owner", doc.getBoolean("${bType}_show_owner") ?: false)
                editor.putBoolean("${bType}_show_business", doc.getBoolean("${bType}_show_business") ?: true)
                editor.putBoolean("${bType}_show_address", doc.getBoolean("${bType}_show_address") ?: true)
                editor.putBoolean("${bType}_show_phone", doc.getBoolean("${bType}_show_phone") ?: true)
                editor.putBoolean("is_online_store", doc.getBoolean("is_online") ?: false)
                
                // Descargar logo si hay URL
                val logoUrl = doc.getString("business_logo_url")
                if (!logoUrl.isNullOrEmpty()) {
                    downloadLogoFromUrl(logoUrl, bType)
                }
                
                editor.apply()
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error downloading settings: ${e.message}")
        }
    }

    private fun downloadLogoFromUrl(url: String, bType: String) {
        val file = File(context.filesDir, "business_logo_$bType.png")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                URL(url).openStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("${bType}_logo_local", file.absolutePath)
                    .putString("business_logo_local", file.absolutePath).apply()
            } catch (e: Exception) {
                Log.e("SyncManager", "Error downloading logo file: ${e.message}")
            }
        }
    }

    // --- ESCUCHA EN TIEMPO REAL ---

    fun startRealtimeRequestsSync(): ListenerRegistration? {
        val userId = auth.currentUser?.uid ?: return null
        var initialLoaded = false
        return db.collection("users").document(userId)
            .collection("collaborator_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                if (!initialLoaded) {
                    initialLoaded = true
                    return@addSnapshotListener
                }

                syncScope.launch {
                    for (docChange in snapshot.documentChanges) {
                        if (docChange.type == DocumentChange.Type.ADDED) {
                            val name = docChange.document.getString("requesterName") ?: "Alguien"
                            withContext(Dispatchers.Main) {
                                NotificationHelper.showRequestNotification(context, name)
                                // Activar punto rojo en la UI
                                context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("has_new_request", true).apply()
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeMessagesSync(): List<ListenerRegistration> {
        val userId = auth.currentUser?.uid ?: return emptyList()
        val registrations = mutableListOf<ListenerRegistration>()
        
        var initialGlobalLoaded = false
        var initialPrivateLoaded = false

        // 1. Mensajes Globales
        registrations.add(db.collection("app_messages")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                if (!initialGlobalLoaded) {
                    initialGlobalLoaded = true
                    return@addSnapshotListener
                }

                syncScope.launch {
                    for (docChange in snapshot.documentChanges) {
                        if (docChange.type == DocumentChange.Type.ADDED) {
                            val title = docChange.document.getString("title") ?: "Aviso GoMezMart"
                            val content = docChange.document.getString("content") ?: ""
                            withContext(Dispatchers.Main) {
                                NotificationHelper.showMessageNotification(context, title, content.take(60) + "...")
                                context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("has_new_message", true).apply()
                            }
                        }
                    }
                }
            })

        // 2. Mensajes Privados
        registrations.add(db.collection("users").document(userId).collection("messages")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                if (!initialPrivateLoaded) {
                    initialPrivateLoaded = true
                    return@addSnapshotListener
                }

                syncScope.launch {
                    for (docChange in snapshot.documentChanges) {
                        if (docChange.type == DocumentChange.Type.ADDED) {
                            val title = docChange.document.getString("title") ?: "Mensaje Directo"
                            val content = docChange.document.getString("content") ?: ""
                            withContext(Dispatchers.Main) {
                                NotificationHelper.showMessageNotification(context, title, content.take(60) + "...")
                                context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("has_new_message", true).apply()
                            }
                        }
                    }
                }
            })
            
        return registrations
    }

    /**
     * Escucha cambios en la nube y los baja al celular al instante
     */
    fun startRealtimeInventorySync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        
        return db.collection("users").document(userId)
            .collection("inventory")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    
                    localDb.withTransaction {
                        for (docChange in changes) {
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
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeExpensesSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudExpense = docChange.document.toObject(ExpenseEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloudExpense.isSynced = true
                                    localDb.expenseDao().insert(cloudExpense)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.expenseDao().delete(cloudExpense)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeProvidersSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("providers")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudProvider = docChange.document.toObject(ProviderEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloudProvider.isSynced = true
                                    localDb.providerDao().insert(cloudProvider)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.providerDao().delete(cloudProvider)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeDebtorsSync(onDataChanged: () -> Unit): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val registrations = mutableListOf<ListenerRegistration>()
        
        // Sincronizar deudores
        registrations.add(db.collection("users").document(userId).collection("debtors")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudDebtor = docChange.document.toObject(DebtorEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloudDebtor.isSynced = true
                                    localDb.debtorDao().insertDebtor(cloudDebtor)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.debtorDao().deleteDebtor(cloudDebtor)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            })
            
        // Sincronizar detalles de deuda
        registrations.add(db.collection("users").document(userId).collection("debt_details")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudDebt = docChange.document.toObject(DebtDetailEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloudDebt.isSynced = true
                                    localDb.debtorDao().insertDebtDetail(cloudDebt)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.debtorDao().deleteDebtDetail(cloudDebt)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            })
            
        return registrations
    }

    fun startRealtimeLogsSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("movement_logs")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudLog = docChange.document.toObject(MovementLogEntity::class.java) ?: continue
                            if (docChange.type == DocumentChange.Type.ADDED) {
                                cloudLog.isSynced = true
                                localDb.movementLogDao().insert(cloudLog)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeCashSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("cash_sessions")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudSession = docChange.document.toObject(CashSessionEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloudSession.isSynced = true
                                    localDb.cashDao().update(cloudSession)
                                }
                                else -> { /* No se borran sesiones por ahora */ }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeSalesSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("sales")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch

                    val addedChanges = changes.filter { it.type == DocumentChange.Type.ADDED }
                    val existingIds = if (addedChanges.isNotEmpty()) {
                        localDb.saleDao().getExistingIds(addedChanges.map { it.document.id }).toSet()
                    } else emptySet()

                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloudSale = docChange.document.toObject(SaleEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED -> {
                                    val existsLocally = existingIds.contains(cloudSale.id)
                                    cloudSale.isSynced = true
                                    localDb.saleDao().insert(cloudSale)
                                    
                                    // Si es nueva y reciente, notificar
                                    val isRecent = (System.currentTimeMillis() - cloudSale.timestamp) < 120000
                                    if (!existsLocally && isRecent && !snapshot.metadata.hasPendingWrites()) {
                                        withContext(Dispatchers.Main) {
                                            val totalVal = cloudSale.total ?: 0.0
                                            val totalStr = String.format(Locale.getDefault(), "S/ %.2f", totalVal)
                                            val bizName = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                                                .getString("business_name", "GoMezMart") ?: "GoMezMart"
                                            NotificationHelper.showSaleNotification(context, totalStr, bizName)
                                        }
                                    }
                                }
                                DocumentChange.Type.MODIFIED -> {
                                    cloudSale.isSynced = true
                                    localDb.saleDao().insert(cloudSale)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.saleDao().delete(cloudSale)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    // --- SINCRONIZACIÓN TIEMPO REAL HOTEL ---

    fun startRealtimeHotelSync(onDataChanged: () -> Unit): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val hotelCollections = listOf(
            "hotel_rooms", "hotel_bookings", "hotel_charges", 
            "hotel_payments", "hotel_maintenance", "hotel_room_layouts", "hotel_tools",
            "business_debts"
        )
        
        val registrations = mutableListOf<ListenerRegistration>()

        hotelCollections.forEach { coll ->
            registrations.add(db.collection("users").document(userId).collection(coll)
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) return@addSnapshotListener
                    syncScope.launch {
                        val changes = snapshot.documentChanges
                        if (changes.isEmpty()) return@launch
                        localDb.withTransaction {
                            for (docChange in changes) {
                                val doc = docChange.document
                                when(coll) {
                                    "hotel_rooms" -> {
                                        val cloud = doc.toObject(HotelRoomEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.hotelDao().updateRoom(fixed.copy(isDeleted = true))
                                        else localDb.hotelDao().insertRoom(fixed)
                                    }
                                    "hotel_bookings" -> {
                                        val cloud = doc.toObject(HotelBookingEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.hotelDao().updateBooking(fixed.copy(isDeleted = true))
                                        else localDb.hotelDao().insertBooking(fixed)
                                    }
                                    "hotel_charges" -> {
                                        val cloud = doc.toObject(HotelChargeEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type != DocumentChange.Type.REMOVED) localDb.hotelDao().insertCharge(fixed)
                                    }
                                    "hotel_payments" -> {
                                        val cloud = doc.toObject(HotelPaymentEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type != DocumentChange.Type.REMOVED) localDb.hotelDao().insertPayment(fixed)
                                    }
                                    "hotel_maintenance" -> {
                                        val cloud = doc.toObject(HotelMaintenanceEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.hotelDao().updateMaintenanceReport(fixed.copy(isDeleted = true))
                                        else localDb.hotelDao().insertMaintenanceReport(fixed)
                                    }
                                    "hotel_room_layouts" -> {
                                        val cloud = doc.toObject(HotelRoomLayoutEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.hotelDao().deleteLayout(fixed)
                                        else localDb.hotelDao().insertLayout(fixed)
                                    }
                                    "hotel_tools" -> {
                                        val cloud = doc.toObject(HotelToolEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.hotelToolDao().updateTool(fixed.copy(isDeleted = true))
                                        else localDb.hotelToolDao().insertTool(fixed)
                                    }
                                    "business_debts" -> {
                                        val cloud = doc.toObject(BusinessDebtEntity::class.java) ?: continue
                                        val fixed = cloud.copy(id = doc.id, isSynced = true)
                                        if (docChange.type == DocumentChange.Type.REMOVED) localDb.businessDebtDao().update(fixed.copy(isDeleted = true))
                                        else localDb.businessDebtDao().insert(fixed)
                                    }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) { onDataChanged() }
                    }
                })
        }
        return registrations
    }

    // --- SINCRONIZACIÓN TIEMPO REAL PRÉSTAMOS ---

    fun startRealtimeLoanClientsSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_clients")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloud = docChange.document.toObject(LoanClientEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloud.isSynced = true
                                    localDb.loanDao().insertClient(cloud)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.loanDao().deleteClient(cloud)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeLoansSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loans")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloud = docChange.document.toObject(LoanEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloud.isSynced = true
                                    localDb.loanDao().insertLoan(cloud)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.loanDao().deleteLoan(cloud)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeInstallmentsSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_installments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        val list = mutableListOf<LoanInstallmentEntity>()
                        for (docChange in changes) {
                            val cloud = docChange.document.toObject(LoanInstallmentEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloud.isSynced = true
                                    list.add(cloud)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.loanDao().deleteInstallment(cloud)
                                }
                            }
                        }
                        if (list.isNotEmpty()) localDb.loanDao().insertInstallments(list)
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeLoanPaymentsSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_payments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloud = docChange.document.toObject(LoanPaymentEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloud.isSynced = true
                                    localDb.loanDao().insertPayment(cloud)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.loanDao().deletePayment(cloud)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    fun startRealtimeLoanExpensesSystemSync(onDataChanged: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        return db.collection("users").document(userId).collection("loan_expenses_system")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val changes = snapshot.documentChanges
                    if (changes.isEmpty()) return@launch
                    localDb.withTransaction {
                        for (docChange in changes) {
                            val cloud = docChange.document.toObject(LoanExpenseEntity::class.java) ?: continue
                            when (docChange.type) {
                                DocumentChange.Type.ADDED,
                                DocumentChange.Type.MODIFIED -> {
                                    cloud.isSynced = true
                                    localDb.loanDao().insertExpense(cloud)
                                }
                                DocumentChange.Type.REMOVED -> {
                                    localDb.loanDao().deleteExpense(cloud)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onDataChanged() }
                }
            }
    }

    // --- BORRADOS ---

    fun deleteProductFromCloud(productId: String) {
        val userId = getEffectiveUserId() ?: return
        db.collection("users").document(userId)
            .collection("inventory").document(productId)
            .delete()
    }

    fun deleteLoanClientFromCloud(clientId: String) {
        val userId = getEffectiveUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userRef = db.collection("users").document(userId)
                
                // 1. Borrar cliente
                userRef.collection("loan_clients").document(clientId).delete().await()
                
                // 2. Borrar préstamos asociados y sus dependencias
                val loans = userRef.collection("loans").whereEqualTo("clientId", clientId).get().await()
                for (loanDoc in loans.documents) {
                    val loanId = loanDoc.id
                    
                    // Borrar cuotas
                    val installments = userRef.collection("loan_installments").whereEqualTo("loanId", loanId).get().await()
                    for (instDoc in installments.documents) instDoc.reference.delete()
                    
                    // Borrar pagos
                    val payments = userRef.collection("loan_payments").whereEqualTo("loanId", loanId).get().await()
                    for (payDoc in payments.documents) payDoc.reference.delete()
                    
                    // Borrar el préstamo en sí
                    loanDoc.reference.delete()
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error deleting loan client from cloud: ${e.message}")
            }
        }
    }

    // --- BORRADOS MASIVOS (CLOUD) ---

    fun deleteCollectionFromCloud(collectionName: String) {
        val userId = getEffectiveUserId() ?: return
        db.collection("users").document(userId)
            .collection(collectionName)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = db.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit()
            }
    }

    fun deleteEverythingFromCloud(onComplete: () -> Unit) {
        val userId = getEffectiveUserId() ?: return
        val collections = listOf(
            "inventory", "sales", "expenses", "debtors", "debt_details",
            "loan_clients", "loans", "loan_installments", "loan_payments",
            "loan_expenses_system", "commitments", "business_debts", "cash_sessions",
            "movement_logs", "loan_budgets", "settings", "messages",
            "hotel_rooms", "hotel_bookings", "hotel_charges", "hotel_payments",
            "hotel_maintenance", "hotel_room_layouts", "hotel_tools",
            "customers", "providers", "price_history"
        )
        
        var completedCount = 0
        val totalToComplete = collections.size
        
        collections.forEach { coll ->
            db.collection("users").document(userId)
                .collection(coll)
                .get()
                .addOnCompleteListener { snapshotTask ->
                    if (snapshotTask.isSuccessful && snapshotTask.result != null && !snapshotTask.result!!.isEmpty) {
                        val batch = db.batch()
                        for (doc in snapshotTask.result!!) {
                            batch.delete(doc.reference)
                        }
                        batch.commit().addOnCompleteListener {
                            synchronized(this) {
                                completedCount++
                                if (completedCount == totalToComplete) onComplete()
                            }
                        }
                    } else {
                        synchronized(this) {
                            completedCount++
                            if (completedCount == totalToComplete) onComplete()
                        }
                    }
                }
        }
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
        val userId = getEffectiveUserId() ?: return
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

                // 3.5 Deudas del Negocio
                val businessDebts = localDb.businessDebtDao().getPendingDebts() + localDb.businessDebtDao().getPaidDebts()
                businessDebts.forEach { 
                    userRef.collection("business_debts").document(it.id).set(it, SetOptions.merge()).await()
                    it.isSynced = true
                    localDb.businessDebtDao().update(it)
                }

                // 3.6 Préstamos (Sistema Nuevo)
                val lClients = localDb.loanDao().getAllClientsOnce()
                for (client in lClients) {
                    userRef.collection("loan_clients").document(client.id).set(client, SetOptions.merge()).await()
                }
                
                val loansList = localDb.loanDao().getAllLoansOnce()
                for (loan in loansList) {
                    userRef.collection("loans").document(loan.id).set(loan, SetOptions.merge()).await()
                }
                
                val installments = localDb.loanDao().getAllInstallmentsOnce()
                for (inst in installments) {
                    userRef.collection("loan_installments").document(inst.id).set(inst, SetOptions.merge()).await()
                }
                
                // 3.7 Pagos y Gastos de Préstamos
                val loanPayments = localDb.loanDao().getAllPaymentsOnce()
                for (pay in loanPayments) {
                    userRef.collection("loan_payments").document(pay.id).set(pay, SetOptions.merge()).await()
                }
                val loanExpenses = localDb.loanDao().getAllLoanExpensesOnce()
                for (lexp in loanExpenses) {
                    userRef.collection("loan_expenses_system").document(lexp.id).set(lexp, SetOptions.merge()).await()
                }

                // 3.8 Compromisos
                val commitments = localDb.commitmentDao().getActiveCommitmentsOnce()
                for (com in commitments) {
                    userRef.collection("commitments").document(com.id).set(com, SetOptions.merge()).await()
                    com.isSynced = true
                    localDb.commitmentDao().updateCommitment(com)
                }

                // 3.9 Hotel
                val hotelRooms = localDb.hotelDao().getUnsyncedRooms()
                hotelRooms.forEach { 
                    userRef.collection("hotel_rooms").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markRoomSynced(it.id)
                }
                val hotelBookings = localDb.hotelDao().getUnsyncedBookings()
                hotelBookings.forEach { 
                    userRef.collection("hotel_bookings").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markBookingSynced(it.id)
                }
                val hotelCharges = localDb.hotelDao().getUnsyncedCharges()
                hotelCharges.forEach { 
                    userRef.collection("hotel_charges").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markChargeSynced(it.id)
                }
                val hotelPayments = localDb.hotelDao().getUnsyncedPayments()
                hotelPayments.forEach { 
                    userRef.collection("hotel_payments").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markPaymentSynced(it.id)
                }
                val hotelMaint = localDb.hotelDao().getUnsyncedMaintenance()
                hotelMaint.forEach { 
                    userRef.collection("hotel_maintenance").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markMaintenanceSynced(it.id)
                }
                val hotelLayouts = localDb.hotelDao().getUnsyncedLayouts()
                hotelLayouts.forEach { 
                    userRef.collection("hotel_room_layouts").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelDao().markLayoutSynced(it.id)
                }
                val hotelTools = localDb.hotelToolDao().getUnsyncedTools()
                hotelTools.forEach { 
                    userRef.collection("hotel_tools").document(it.id).set(it, SetOptions.merge()).await()
                    localDb.hotelToolDao().markToolSynced(it.id)
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
        val userId = getEffectiveUserId() ?: return
        
        // Bloquear múltiples ejecuciones simultáneas de descarga total
        if (syncScope.coroutineContext[Job]?.isActive == true) {
            // syncScope.coroutineContext[Job]?.cancel() // Opcional: cancelar previa si es muy pesada
        }

        syncScope.launch {
            try {
                // 0. Limpiar base de datos local antes de restaurar para evitar duplicados y mezcla de datos
                // Usamos clearAllTables pero con cuidado de no romper el flujo
                localDb.withTransaction {
                    localDb.clearAllTables()
                }
                
                val userRef = db.collection("users").document(userId)

                // 1. Descargar Productos
                val inventorySnap = userRef.collection("inventory").get().await()
                for (doc in inventorySnap.documents) {
                    val p = doc.toObject(ProductEntity::class.java)
                    if (p != null) {
                        p.id = doc.id
                        p.isSynced = true
                        localDb.productDao().insert(p)
                    }
                }

                // 2. Descargar Ventas
                val salesSnap = userRef.collection("sales").get().await()
                for (doc in salesSnap.documents) {
                    val s = doc.toObject(SaleEntity::class.java)
                    if (s != null) {
                        s.id = doc.id
                        s.isSynced = true
                        localDb.saleDao().insert(s)
                    }
                }

                // 3. Descargar Gastos (Tienda)
                val expensesSnap = userRef.collection("expenses").get().await()
                for (doc in expensesSnap.documents) {
                    val e = doc.toObject(ExpenseEntity::class.java)
                    if (e != null) {
                        val fixedE = e.copy(id = doc.id, isSynced = true)
                        localDb.expenseDao().insert(fixedE)
                    }
                }

                // 4. Descargar Préstamos (Sistema Nuevo)
                // --- Clientes ---
                val lClientsSnap = userRef.collection("loan_clients").get().await()
                for (doc in lClientsSnap.documents) {
                    val c = doc.toObject(LoanClientEntity::class.java)
                    if (c != null) {
                        val fixedC = c.copy(id = doc.id, isSynced = true)
                        localDb.loanDao().insertClient(fixedC)
                    }
                }
                // --- Préstamos ---
                val loansSnap = userRef.collection("loans").get().await()
                for (doc in loansSnap.documents) {
                    val l = doc.toObject(LoanEntity::class.java)
                    if (l != null) {
                        val fixedL = l.copy(id = doc.id, isSynced = true)
                        localDb.loanDao().insertLoan(fixedL)
                    }
                }
                // --- Cuotas ---
                val installmentsSnap = userRef.collection("loan_installments").get().await()
                val instList = mutableListOf<LoanInstallmentEntity>()
                for (doc in installmentsSnap.documents) {
                    val i = doc.toObject(LoanInstallmentEntity::class.java)
                    if (i != null) instList.add(i.copy(id = doc.id, isSynced = true))
                }
                if (instList.isNotEmpty()) localDb.loanDao().insertInstallments(instList)
                // --- Pagos ---
                val lPaymentsSnap = userRef.collection("loan_payments").get().await()
                for (doc in lPaymentsSnap.documents) {
                    val p = doc.toObject(LoanPaymentEntity::class.java)
                    if (p != null) {
                        val fixedP = p.copy(id = doc.id, isSynced = true)
                        localDb.loanDao().insertPayment(fixedP)
                    }
                }
                // --- Gastos de Préstamos ---
                val lExpensesSnap = userRef.collection("loan_expenses_system").get().await()
                for (doc in lExpensesSnap.documents) {
                    val le = doc.toObject(LoanExpenseEntity::class.java)
                    if (le != null) {
                        val fixedLE = le.copy(id = doc.id, isSynced = true)
                        localDb.loanDao().insertExpense(fixedLE)
                    }
                }

                // --- Compromisos ---
                val commSnap = userRef.collection("commitments").get().await()
                for (doc in commSnap.documents) {
                    val comm = doc.toObject(CommitmentEntity::class.java)
                    if (comm != null) {
                        val fixedComm = comm.copy(id = doc.id, isSynced = true)
                        localDb.commitmentDao().insertCommitment(fixedComm)
                    }
                }

                // --- Presupuestos ---
                val budgetSnap = userRef.collection("loan_budgets").get().await()
                for (doc in budgetSnap.documents) {
                    val b = doc.toObject(LoanBudgetEntity::class.java)
                    if (b != null) {
                        val fixedB = b.copy(id = doc.id, isSynced = true)
                        localDb.loanDao().insertBudget(fixedB)
                    }
                }

                // 5. Descargar Datos de Tienda (Deudores, Caja, etc.)
                // --- Deudores ---
                val debtorsSnap = userRef.collection("debtors").get().await()
                for (doc in debtorsSnap.documents) {
                    val d = doc.toObject(DebtorEntity::class.java)
                    if (d != null) {
                        val fixedD = d.copy(id = doc.id, isSynced = true)
                        localDb.debtorDao().insertDebtor(fixedD)
                    }
                }
                val debtDetailsSnap = userRef.collection("debt_details").get().await()
                for (doc in debtDetailsSnap.documents) {
                    val dd = doc.toObject(DebtDetailEntity::class.java)
                    if (dd != null) {
                        val fixedDD = dd.copy(id = doc.id, isSynced = true)
                        localDb.debtorDao().insertDebtDetail(fixedDD)
                    }
                }
                // --- Caja ---
                val cashSnap = userRef.collection("cash_sessions").get().await()
                for (doc in cashSnap.documents) {
                    val cs = doc.toObject(CashSessionEntity::class.java)
                    if (cs != null) {
                        val sessionId = doc.id.toIntOrNull() ?: 0
                        val fixedCS = cs.copy(id = sessionId, isSynced = true)
                        localDb.cashDao().insert(fixedCS)
                    }
                }
                // --- Clientes y Proveedores ---
                val custSnap = userRef.collection("customers").get().await()
                for (doc in custSnap.documents) {
                    val cu = doc.toObject(CustomerEntity::class.java)
                    if (cu != null) {
                        val fixedCU = cu.copy(id = doc.id, isSynced = true)
                        localDb.customerDao().insert(fixedCU)
                    }
                }
                val provSnap = userRef.collection("providers").get().await()
                for (doc in provSnap.documents) {
                    val pr = doc.toObject(ProviderEntity::class.java)
                    if (pr != null) {
                        val fixedPR = pr.copy(id = doc.id, isSynced = true)
                        localDb.providerDao().insert(fixedPR)
                    }
                }
                // --- Logs ---
                val logsSnap = userRef.collection("movement_logs").get().await()
                for (doc in logsSnap.documents) {
                    val log = doc.toObject(MovementLogEntity::class.java)
                    if (log != null) {
                        val fixedLog = log.copy(id = doc.id, isSynced = true)
                        localDb.movementLogDao().insert(fixedLog)
                    }
                }

                // 5.5 Descargar Deudas del Negocio
                val bDebtsSnap = userRef.collection("business_debts").get().await()
                for (doc in bDebtsSnap.documents) {
                    val d = doc.toObject(BusinessDebtEntity::class.java)
                    if (d != null) {
                        val fixedD = d.copy(id = doc.id, isSynced = true)
                        localDb.businessDebtDao().insert(fixedD)
                    }
                }

                // 6. Descargar Datos de Hotel
                // --- Habitaciones ---
                val roomsSnap = userRef.collection("hotel_rooms").get().await()
                for (doc in roomsSnap.documents) {
                    val r = doc.toObject(HotelRoomEntity::class.java)
                    if (r != null) {
                        val fixedR = r.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertRoom(fixedR)
                    }
                }
                // --- Reservas ---
                val bookingsSnap = userRef.collection("hotel_bookings").get().await()
                for (doc in bookingsSnap.documents) {
                    val b = doc.toObject(HotelBookingEntity::class.java)
                    if (b != null) {
                        val fixedB = b.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertBooking(fixedB)
                    }
                }
                // --- Cargos y Pagos ---
                val hChargesSnap = userRef.collection("hotel_charges").get().await()
                for (doc in hChargesSnap.documents) {
                    val hc = doc.toObject(HotelChargeEntity::class.java)
                    if (hc != null) {
                        val fixedHC = hc.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertCharge(fixedHC)
                    }
                }
                val hPaymentsSnap = userRef.collection("hotel_payments").get().await()
                for (doc in hPaymentsSnap.documents) {
                    val hp = doc.toObject(HotelPaymentEntity::class.java)
                    if (hp != null) {
                        val fixedHP = hp.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertPayment(fixedHP)
                    }
                }
                // --- Mantenimiento y Layouts ---
                val hMaintSnap = userRef.collection("hotel_maintenance").get().await()
                for (doc in hMaintSnap.documents) {
                    val hm = doc.toObject(HotelMaintenanceEntity::class.java)
                    if (hm != null) {
                        val fixedHM = hm.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertMaintenanceReport(fixedHM)
                    }
                }
                val hLayoutsSnap = userRef.collection("hotel_room_layouts").get().await()
                for (doc in hLayoutsSnap.documents) {
                    val hl = doc.toObject(HotelRoomLayoutEntity::class.java)
                    if (hl != null) {
                        val fixedHL = hl.copy(id = doc.id, isSynced = true)
                        localDb.hotelDao().insertLayout(fixedHL)
                    }
                }

                // --- Herramientas ---
                val hToolsSnap = userRef.collection("hotel_tools").get().await()
                for (doc in hToolsSnap.documents) {
                    val ht = doc.toObject(HotelToolEntity::class.java)
                    if (ht != null) {
                        val fixedHT = ht.copy(id = doc.id, isSynced = true)
                        localDb.hotelToolDao().insertTool(fixedHT)
                    }
                }

                // 7. Descargar Ajustes
                downloadBusinessSettingsFromCloud()

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error downloading everything: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
