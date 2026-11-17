package com.gomezmart.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.gomezmart.app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.network.SupabaseManager
import com.gomezmart.app.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestoreSettings
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.BillingManager
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncManager(private val context: Context) {

    private val db: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e("SyncManager", "Firestore initialization failed: ${e.message}")
            null
        }
    }
    
    private val auth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e("SyncManager", "FirebaseAuth initialization failed: ${e.message}")
            null
        }
    }
    
    private val localDb = try { AppDatabase.getDatabase(context) } catch (e: Exception) { null }
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val roomPhotoUploadMutex = Mutex()
        private val uploadedRoomPhotosByPath = ConcurrentHashMap<String, String>()
    }

    fun isGuestUser(): Boolean {
        return try {
            val user = auth?.currentUser
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            if (user != null && !user.isAnonymous) {
                if (prefs.getBoolean("is_guest", false)) {
                    prefs.edit().putBoolean("is_guest", false).apply()
                }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun getEffectiveUserId(): String? {
        if (isGuestUser()) return null
        try {
            val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            val isCollaborator = prefs.getBoolean("is_collaborator_mode", false)
            return if (isCollaborator) {
                prefs.getString("collaborator_owner_id", null)
            } else {
                auth?.currentUser?.uid
            }
        } catch (e: Exception) { return null }
    }

    // --- SUBIDAS INDIVIDUALES (OPTIMIZADAS CON SCOPE ÚNICO) ---

    fun syncProductToCloud(product: ProductEntity, notifyFollowers: Boolean = false) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                if (!product.photoPath.isNullOrEmpty() && product.photoUrl.isNullOrEmpty()) {
                    if (BillingManager.hasCloudSpaceAvailable(context)) {
                        val file = File(product.photoPath)
                        if (file.exists()) {
                            val path = "users/$userId/products/${product.id}_${System.currentTimeMillis()}.jpg"
                            val downloadUrl = SupabaseManager.uploadImage("naxor-imagenes-usuarios", path, file)
                            if (downloadUrl != null) product.photoUrl = downloadUrl
                        }
                    } else {
                        Log.w("SyncManager", "Límite de almacenamiento en la nube alcanzado. Foto del producto ${product.id} conservada localmente.")
                    }
                }
                firestore.collection("users").document(userId).collection("inventory").document(product.id).set(product).await()
                product.isSynced = true
                localDb?.productDao()?.update(product)

                if (notifyFollowers) {
                    val settingsDocs = runCatching {
                        firestore.collection("users").document(userId).collection("settings").get().await().documents
                    }.getOrNull().orEmpty()
                    val storeDoc = settingsDocs.firstOrNull { it.id == "PRODUCTS" || it.id == "business" }
                        ?: settingsDocs.firstOrNull()
                    val storeName = storeDoc?.getString("business_name") ?: "Tienda GoMezMart"
                    val storeLogo = storeDoc?.getString("business_logo_url")
                    val storeType = storeDoc?.getString("business_type") ?: "PRODUCTS"
                    val priceStr = String.format(Locale.US, "%.2f", product.precioVenta)
                    val catText = product.categoria.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                    SocialRepository.publishStoreUpdate(
                        db = firestore,
                        storeId = userId,
                        storeName = storeName,
                        storeLogoUrl = storeLogo,
                        storeType = storeType,
                        type = "NEW_PRODUCT",
                        title = product.nombre.ifBlank { "Nuevo producto" },
                        message = "Nuevo en catálogo por S/ $priceStr$catText",
                        productId = product.id,
                        productPrice = product.precioVenta,
                        imageUrl = product.photoUrl
                    )
                }
            } catch (e: Exception) { Log.e("SyncManager", "Error product: ${e.message}") }
        }
    }

    fun syncSaleToCloud(sale: SaleEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("sales").document(sale.id).set(sale, SetOptions.merge()).await()
                sale.isSynced = true
                localDb?.saleDao()?.update(sale)
            } catch (e: Exception) { Log.e("SyncManager", "Error sale: ${e.message}") }
        }
    }

    fun syncExpenseToCloud(expense: ExpenseEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("expenses").document(expense.id).set(expense, SetOptions.merge()).await()
                expense.isSynced = true
                localDb?.expenseDao()?.update(expense)
            } catch (e: Exception) { Log.e("SyncManager", "Error expense: ${e.message}") }
        }
    }

    fun syncLogToCloud(log: MovementLogEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("movement_logs").document(log.id).set(log, SetOptions.merge()).await()
                log.isSynced = true
                localDb?.movementLogDao()?.insert(log)
            } catch (e: Exception) { Log.e("SyncManager", "Error log: ${e.message}") }
        }
    }

    fun syncProviderToCloud(provider: ProviderEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("providers").document(provider.id).set(provider, SetOptions.merge()).await()
                provider.isSynced = true
                localDb?.providerDao()?.update(provider)
            } catch (e: Exception) { Log.e("SyncManager", "Error provider: ${e.message}") }
        }
    }

    fun syncDebtorToCloud(debtor: DebtorEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("debtors").document(debtor.id).set(debtor, SetOptions.merge()).await()
                debtor.isSynced = true
                localDb?.debtorDao()?.updateDebtor(debtor)
            } catch (e: Exception) { Log.e("SyncManager", "Error debtor: ${e.message}") }
        }
    }

    fun syncBusinessDebtToCloud(debt: BusinessDebtEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("business_debts").document(debt.id).set(debt, SetOptions.merge()).await()
                debt.isSynced = true
                localDb?.businessDebtDao()?.update(debt)
            } catch (e: Exception) { Log.e("SyncManager", "Error business debt: ${e.message}") }
        }
    }

    fun syncCashSessionToCloud(session: CashSessionEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("cash_sessions").document(session.id.toString()).set(session, SetOptions.merge()).await()
                session.isSynced = true
                localDb?.cashDao()?.update(session)
            } catch (e: Exception) { Log.e("SyncManager", "Error cash session: ${e.message}") }
        }
    }

    fun syncHotelBookingToCloud(booking: HotelBookingEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_bookings").document(booking.id).set(booking, SetOptions.merge()).await()
                booking.isSynced = true
                localDb?.hotelDao()?.updateBooking(booking)
            } catch (e: Exception) { Log.e("SyncManager", "Error hotel booking: ${e.message}") }
        }
    }

    private suspend fun resolveAndUploadRoomPhotoUrl(userId: String, room: HotelRoomEntity): String? {
        if (!room.photoUrl.isNullOrEmpty()) return room.photoUrl
        val localPath = room.photoPath?.takeIf { it.isNotBlank() } ?: return null

        uploadedRoomPhotosByPath[localPath]?.let { return it }

        return roomPhotoUploadMutex.withLock {
            uploadedRoomPhotosByPath[localPath]?.let { return@withLock it }

            val existingUrl = runCatching {
                localDb?.hotelDao()?.getAllRoomsOnce()
                    ?.firstOrNull { it.photoPath == localPath && !it.photoUrl.isNullOrEmpty() }
                    ?.photoUrl
            }.getOrNull()
            if (!existingUrl.isNullOrEmpty()) {
                uploadedRoomPhotosByPath[localPath] = existingUrl
                return@withLock existingUrl
            }

            if (!BillingManager.hasCloudSpaceAvailable(context)) {
                Log.w("SyncManager", "Límite de almacenamiento en la nube alcanzado. Foto de habitación ${room.id} conservada localmente.")
                return@withLock null
            }

            val file = File(localPath)
            if (!file.exists()) return@withLock null

            val storagePath = "users/$userId/hotel_rooms/${room.id}_${System.currentTimeMillis()}.jpg"
            val downloadUrl = SupabaseManager.uploadImage("naxor-imagenes-usuarios", storagePath, file)
            if (!downloadUrl.isNullOrEmpty()) {
                uploadedRoomPhotosByPath[localPath] = downloadUrl
            }
            downloadUrl
        }
    }

    fun syncHotelRoomToCloud(room: HotelRoomEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                val resolvedPhotoUrl = resolveAndUploadRoomPhotoUrl(userId, room)
                val syncedRoom = room.copy(
                    photoUrl = resolvedPhotoUrl ?: room.photoUrl,
                    isSynced = true
                )
                firestore.collection("users").document(userId).collection("hotel_rooms").document(syncedRoom.id)
                    .set(syncedRoom, SetOptions.merge()).await()
                localDb?.hotelDao()?.updateRoom(syncedRoom)
            } catch (e: Exception) { Log.e("SyncManager", "Error hotel room: ${e.message}") }
        }
    }

    fun syncHotelRoomsBatchToCloud(rooms: List<HotelRoomEntity>) {
        if (rooms.isEmpty()) return
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            rooms.forEach { room ->
                try {
                    val resolvedPhotoUrl = resolveAndUploadRoomPhotoUrl(userId, room)
                    val syncedRoom = room.copy(
                        photoUrl = resolvedPhotoUrl ?: room.photoUrl,
                        isSynced = true
                    )
                    firestore.collection("users").document(userId).collection("hotel_rooms").document(syncedRoom.id)
                        .set(syncedRoom, SetOptions.merge()).await()
                    localDb?.hotelDao()?.updateRoom(syncedRoom)
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error hotel room batch (${room.number}): ${e.message}")
                }
            }
        }
    }

    fun syncHotelMaintenanceToCloud(report: HotelMaintenanceEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_maintenance").document(report.id).set(report, SetOptions.merge()).await()
                report.isSynced = true
                localDb?.hotelDao()?.updateMaintenanceReport(report)
            } catch (e: Exception) { Log.e("SyncManager", "Error maintenance: ${e.message}") }
        }
    }

    fun syncHotelChargeToCloud(charge: HotelChargeEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_charges").document(charge.id).set(charge, SetOptions.merge()).await()
                charge.isSynced = true
                localDb?.hotelDao()?.insertCharge(charge)
            } catch (e: Exception) { Log.e("SyncManager", "Error charge: ${e.message}") }
        }
    }

    fun syncHotelPaymentToCloud(payment: HotelPaymentEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_payments").document(payment.id).set(payment, SetOptions.merge()).await()
                payment.isSynced = true
                localDb?.hotelDao()?.insertPayment(payment)
            } catch (e: Exception) { Log.e("SyncManager", "Error payment: ${e.message}") }
        }
    }

    fun syncHotelLayoutToCloud(layout: HotelRoomLayoutEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_layouts").document(layout.id).set(layout, SetOptions.merge()).await()
                layout.isSynced = true
                localDb?.hotelDao()?.updateLayout(layout)
            } catch (e: Exception) { Log.e("SyncManager", "Error layout: ${e.message}") }
        }
    }

    fun syncHotelToolToCloud(tool: HotelToolEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("hotel_tools").document(tool.id).set(tool, SetOptions.merge()).await()
                tool.isSynced = true
                localDb?.hotelToolDao()?.updateTool(tool)
            } catch (e: Exception) { Log.e("SyncManager", "Error tool: ${e.message}") }
        }
    }

    fun syncBusinessSettingsToCloud() {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val settings = mapOf(
            "business_name" to prefs.getString("business_name", ""),
            "business_type" to prefs.getString("business_type", ""),
            "currency" to prefs.getString("currency", "$")
        )
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("settings").document("business_info").set(settings, SetOptions.merge()).await()
            } catch (e: Exception) { Log.e("SyncManager", "Error settings: ${e.message}") }
        }
    }

    // --- SINCRONIZACIÓN COMPLETA ---

    fun downloadEverythingFromCloud(onComplete: () -> Unit) {
        if (isGuestUser()) {
            onComplete()
            return
        }
        val userId = getEffectiveUserId() ?: run { onComplete(); return }
        val firestore = db ?: run { onComplete(); return }
        syncScope.launch {
            try {
                // 0. Descargar Perfil del Usuario (Intentar primero en subcolección, luego en raíz)
                var profileDoc = firestore.collection("users").document(userId).collection("profile").document("info").get().await()
                
                if (!profileDoc.exists()) {
                    profileDoc = firestore.collection("users").document(userId).get().await()
                }

                if (profileDoc.exists()) {
                    val name = profileDoc.getString("name") ?: profileDoc.getString("user_full_name") ?: profileDoc.getString("fullName") ?: profileDoc.getString("displayName") ?: ""
                    val photo = profileDoc.getString("photoUrl") ?: profileDoc.getString("user_photo_url") ?: profileDoc.getString("profilePhoto") ?: profileDoc.getString("photo") ?: ""
                    val email = profileDoc.getString("email") ?: profileDoc.getString("user_email") ?: auth?.currentUser?.email ?: ""
                    val phone = profileDoc.getString("phone") ?: profileDoc.getString("user_phone") ?: ""
                    val dni = profileDoc.getString("dni") ?: profileDoc.getString("user_dni") ?: ""
                    val address = profileDoc.getString("address") ?: profileDoc.getString("user_address") ?: ""
                    val allowDir = profileDoc.getBoolean("allowDirectorySearch") ?: true
                    val shareContact = profileDoc.getBoolean("shareContactWithFriends") ?: false
                    val msgPrivacy = profileDoc.getString("messagePrivacy")

                    val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val wasVerified = appPrefs.getBoolean("is_profile_verified", false)
                    com.gomezmart.app.util.SessionManager.saveProfileLocally(
                        context = context,
                        fullName = name,
                        email = email,
                        phone = phone,
                        dni = dni,
                        address = address,
                        photoUrl = photo,
                        allowDirectorySearch = allowDir,
                        shareContactWithFriends = shareContact,
                        messagePrivacy = msgPrivacy,
                        verified = wasVerified || com.gomezmart.app.util.SessionManager.isProfileComplete(name, email)
                    )

                    // Asegurar consistencia en raíz (sin escribir nombres placeholder)
                    val updates = mutableMapOf<String, Any>()
                    if (!com.gomezmart.app.util.SessionManager.isPlaceholderName(name)) updates["name"] = name
                    if (photo.isNotEmpty()) updates["photoUrl"] = photo
                    if (email.isNotEmpty()) updates["email"] = email
                    if (updates.isNotEmpty()) firestore.collection("users").document(userId).set(updates, SetOptions.merge())
                    
                    // [NUEVO] Recuperar Rol sugerido solo si no se ha establecido uno previamente
                    val title = profileDoc.getString("businessTitle") ?: ""
                    val recoveredRole = when {
                        title.contains("Dueño", true) || title.contains("Gestor", true) -> "SELLER"
                        title.contains("Colaborador", true) -> "COLLABORATOR"
                        title.contains("Comprador", true) -> "BUYER"
                        else -> null
                    }
                    val currentRole = appPrefs.getString("user_role", null)
                    if (recoveredRole != null && currentRole == null) {
                        appPrefs.edit().putString("user_role", recoveredRole).apply()
                    }
                }

                // 0.2 Descargar Favoritos
                FavoritesManager.loadFromFirebase(context)

                // 0.3 Recuperar Configuraciones de Negocio (Preservando estrictamente el rubro activo)
                val currentType = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).getString("business_type", null)
                val settings = firestore.collection("users").document(userId).collection("settings").get().await()
                if (!settings.isEmpty) {
                    val bizPrefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).edit()
                    settings.documents.forEach { doc ->
                        doc.data?.forEach { (k, v) -> 
                            if (k != "business_type") {
                                when (v) {
                                    is String -> bizPrefs.putString(k, v)
                                    is Boolean -> bizPrefs.putBoolean(k, v)
                                    is Number -> bizPrefs.putFloat(k, v.toFloat())
                                }
                            }
                        }
                    }
                    if (currentType.isNullOrEmpty()) {
                        val loansDoc = settings.documents.find { it.id == "LOANS" }
                        val firstValidType = loansDoc?.id ?: settings.documents.firstOrNull { it.id != "business" }?.id ?: "LOANS"
                        bizPrefs.putString("business_type", firstValidType)
                    } else {
                        bizPrefs.putString("business_type", currentType)
                    }
                    bizPrefs.apply()
                }

                // 1. Descargar Inventario, Ventas y Gastos
                // IMPORTANTE: Los registros que el usuario creó/editó SIN conexión (isSynced = 0)
                // NUNCA deben ser sobrescritos por la versión de la nube.
                val inventory = firestore.collection("users").document(userId).collection("inventory").get().await()
                val pendingProductIds = localDb?.productDao()?.allUnsyncedProducts?.map { it.id }?.toSet() ?: emptySet()
                val productList = inventory.documents
                    .mapNotNull { it.toObject(ProductEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingProductIds }
                if (productList.isNotEmpty()) localDb?.productDao()?.insertAll(productList)

                val sales = firestore.collection("users").document(userId).collection("sales").get().await()
                val pendingSaleIds = localDb?.saleDao()?.allUnsyncedSales?.map { it.id }?.toSet() ?: emptySet()
                val saleList = sales.documents
                    .mapNotNull { it.toObject(SaleEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingSaleIds }
                if (saleList.isNotEmpty()) localDb?.saleDao()?.insertAll(saleList)

                val expenses = firestore.collection("users").document(userId).collection("expenses").get().await()
                val pendingExpenseIds = localDb?.expenseDao()?.getAllUnsyncedExpenses()?.map { it.id }?.toSet() ?: emptySet()
                val expenseList = expenses.documents
                    .mapNotNull { it.toObject(ExpenseEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingExpenseIds }
                if (expenseList.isNotEmpty()) localDb?.expenseDao()?.insertAll(expenseList)

                // 2. Descargar Deudores (Fiados), Clientes, Proveedores, Caja, Deudas de Negocio e Historial
                try {
                    val debtorsSnap = firestore.collection("users").document(userId).collection("debtors").get().await()
                    val pendingDebtorIds = localDb?.debtorDao()?.getAllUnsyncedDebtors()?.map { it.id }?.toSet() ?: emptySet()
                    debtorsSnap.documents
                        .mapNotNull { it.toObject(DebtorEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingDebtorIds }
                        .forEach { localDb?.debtorDao()?.insertDebtor(it) }

                    val debtDetailsSnap = firestore.collection("users").document(userId).collection("debt_details").get().await()
                    val pendingDebtDetailIds = localDb?.debtorDao()?.getAllUnsyncedDebtDetails()?.map { it.id }?.toSet() ?: emptySet()
                    debtDetailsSnap.documents
                        .mapNotNull { it.toObject(DebtDetailEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingDebtDetailIds }
                        .forEach { localDb?.debtorDao()?.insertDebtDetail(it) }

                    val customersSnap = firestore.collection("users").document(userId).collection("customers").get().await()
                    val pendingCustomerIds = localDb?.customerDao()?.getAllUnsyncedCustomers()?.map { it.id }?.toSet() ?: emptySet()
                    customersSnap.documents
                        .mapNotNull { it.toObject(CustomerEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingCustomerIds }
                        .forEach { localDb?.customerDao()?.insert(it) }

                    val providersSnap = firestore.collection("users").document(userId).collection("providers").get().await()
                    val pendingProviderIds = localDb?.providerDao()?.getAllUnsyncedProviders()?.map { it.id }?.toSet() ?: emptySet()
                    providersSnap.documents
                        .mapNotNull { it.toObject(ProviderEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingProviderIds }
                        .forEach { localDb?.providerDao()?.insert(it) }

                    val cashSnap = firestore.collection("users").document(userId).collection("cash_sessions").get().await()
                    val pendingCashIds = localDb?.cashDao()?.getAllUnsyncedSessions()?.map { it.id }?.toSet() ?: emptySet()
                    cashSnap.documents
                        .mapNotNull { it.toObject(CashSessionEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingCashIds }
                        .forEach { localDb?.cashDao()?.insert(it) }

                    val bizDebtsSnap = firestore.collection("users").document(userId).collection("business_debts").get().await()
                    val pendingBizDebtIds = localDb?.businessDebtDao()?.getUnsynced()?.map { it.id }?.toSet() ?: emptySet()
                    bizDebtsSnap.documents
                        .mapNotNull { it.toObject(BusinessDebtEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingBizDebtIds }
                        .forEach { localDb?.businessDebtDao()?.insert(it) }

                    val logsSnap = firestore.collection("users").document(userId).collection("movement_logs").get().await()
                    val pendingLogIds = localDb?.movementLogDao()?.getUnsyncedLogs()?.map { it.id }?.toSet() ?: emptySet()
                    val logsList = logsSnap.documents
                        .mapNotNull { it.toObject(MovementLogEntity::class.java)?.apply { isSynced = true } }
                        .filter { it.id !in pendingLogIds }
                    if (logsList.isNotEmpty()) localDb?.movementLogDao()?.insertAll(logsList)
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error downloading secondary collections: ${e.message}")
                }

                // 3. Descargar Cuentas Bancarias / Billeteras y Compromisos
                try {
                    val accountsSnap = firestore.collection("users").document(userId).collection("accounts").get().await()
                    val pendingAccountIds = localDb?.accountDao()?.getUnsyncedAccounts()?.map { it.id }?.toSet() ?: emptySet()
                    accountsSnap.documents
                        .mapNotNull { it.toObject(AccountEntity::class.java)?.copy(isSynced = true) }
                        .filter { it.id !in pendingAccountIds }
                        .forEach { localDb?.accountDao()?.insert(it) }

                    val commitmentsSnap = firestore.collection("users").document(userId).collection("commitments").get().await()
                    val pendingCommitmentIds = localDb?.commitmentDao()?.getUnsynced()?.map { it.id }?.toSet() ?: emptySet()
                    commitmentsSnap.documents
                        .mapNotNull { it.toObject(CommitmentEntity::class.java)?.copy(isSynced = true) }
                        .filter { it.id !in pendingCommitmentIds }
                        .forEach { localDb?.commitmentDao()?.insertCommitment(it) }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error downloading accounts/commitments: ${e.message}")
                }

                // 4. Descargar Préstamos, Presupuestos y Gastos de Préstamos
                val loanBudgetsSnap = firestore.collection("users").document(userId).collection("loan_budgets").get().await()
                val pendingBudgetIds = localDb?.loanDao()?.getUnsyncedBudgets()?.map { it.id }?.toSet() ?: emptySet()
                val loanBudgetsList = loanBudgetsSnap.documents
                    .mapNotNull { it.toObject(LoanBudgetEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingBudgetIds }
                loanBudgetsList.forEach { localDb?.loanDao()?.insertBudget(it) }

                val pendingLoanExpenseIds = localDb?.loanDao()?.getUnsyncedLoanExpenses()?.map { it.id }?.toSet() ?: emptySet()
                val loanExpensesSnap = firestore.collection("users").document(userId).collection("loan_expenses").get().await()
                val loanExpensesSysSnap = firestore.collection("users").document(userId).collection("loan_expenses_system").get().await()
                val allLoanExpenseDocs = (loanExpensesSnap.documents + loanExpensesSysSnap.documents).distinctBy { it.id }
                val loanExpensesList = allLoanExpenseDocs
                    .mapNotNull { it.toObject(LoanExpenseEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingLoanExpenseIds }
                loanExpensesList.forEach { localDb?.loanDao()?.insertExpense(it) }

                val loanClientsSnap = firestore.collection("users").document(userId).collection("loan_clients").get().await()
                val pendingClientIds = localDb?.loanDao()?.getUnsyncedClients()?.map { it.id }?.toSet() ?: emptySet()
                val loanClientsList = loanClientsSnap.documents
                    .mapNotNull { it.toObject(LoanClientEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingClientIds }
                loanClientsList.forEach { localDb?.loanDao()?.insertClient(it) }

                val loansSnap = firestore.collection("users").document(userId).collection("loans").get().await()
                val pendingLoanIds = localDb?.loanDao()?.getUnsyncedLoans()?.map { it.id }?.toSet() ?: emptySet()
                val loansList = loansSnap.documents
                    .mapNotNull { it.toObject(LoanEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingLoanIds }
                mergeLoansFromCloud(loansList)

                val loanInstallmentsSnap = firestore.collection("users").document(userId).collection("loan_installments").get().await()
                val pendingInstallmentIds = localDb?.loanDao()?.getUnsyncedInstallments()?.map { it.id }?.toSet() ?: emptySet()
                val loanInstallmentsList = loanInstallmentsSnap.documents
                    .mapNotNull { it.toObject(LoanInstallmentEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingInstallmentIds }
                if (loanInstallmentsList.isNotEmpty()) localDb?.loanDao()?.insertInstallments(loanInstallmentsList)

                val loanPaymentsSnap = firestore.collection("users").document(userId).collection("loan_payments").get().await()
                val pendingPaymentIds = localDb?.loanDao()?.getUnsyncedPayments()?.map { it.id }?.toSet() ?: emptySet()
                val loanPaymentsList = loanPaymentsSnap.documents
                    .mapNotNull { it.toObject(LoanPaymentEntity::class.java)?.apply { isSynced = true } }
                    .filter { it.id !in pendingPaymentIds }
                loanPaymentsList.forEach { localDb?.loanDao()?.insertPayment(it) }

                // 5. Descargar Sistema de Hotelería (Habitaciones, Reservas, Cargos, Pagos, Mantenimiento, Planos y Herramientas)
                try {
                    val hotelDao = localDb?.hotelDao()
                    val hotelToolDao = localDb?.hotelToolDao()
                    if (hotelDao != null) {
                        val pendingRoomIds = hotelDao.getUnsyncedRooms().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_rooms").get().await().documents
                            .mapNotNull { it.toObject(HotelRoomEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingRoomIds }
                            .forEach { hotelDao.insertRoom(it) }

                        val pendingBookingIds = hotelDao.getUnsyncedBookings().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_bookings").get().await().documents
                            .mapNotNull { it.toObject(HotelBookingEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingBookingIds }
                            .forEach { hotelDao.insertBooking(it) }

                        val pendingChargeIds = hotelDao.getUnsyncedCharges().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_charges").get().await().documents
                            .mapNotNull { it.toObject(HotelChargeEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingChargeIds }
                            .forEach { hotelDao.insertCharge(it) }

                        val pendingHotelPaymentIds = hotelDao.getUnsyncedPayments().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_payments").get().await().documents
                            .mapNotNull { it.toObject(HotelPaymentEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingHotelPaymentIds }
                            .forEach { hotelDao.insertPayment(it) }

                        val pendingMaintIds = hotelDao.getUnsyncedMaintenance().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_maintenance").get().await().documents
                            .mapNotNull { it.toObject(HotelMaintenanceEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingMaintIds }
                            .forEach { hotelDao.insertMaintenanceReport(it) }

                        val pendingLayoutIds = hotelDao.getUnsyncedLayouts().map { it.id }.toSet()
                        val layouts1 = firestore.collection("users").document(userId).collection("hotel_room_layouts").get().await().documents
                        val layouts2 = firestore.collection("users").document(userId).collection("hotel_layouts").get().await().documents
                        (layouts1 + layouts2).distinctBy { it.id }
                            .mapNotNull { it.toObject(HotelRoomLayoutEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingLayoutIds }
                            .forEach { hotelDao.insertLayout(it) }
                    }
                    if (hotelToolDao != null) {
                        val pendingToolIds = hotelToolDao.getUnsyncedTools().map { it.id }.toSet()
                        firestore.collection("users").document(userId).collection("hotel_tools").get().await().documents
                            .mapNotNull { it.toObject(HotelToolEntity::class.java)?.apply { isSynced = true } }
                            .filter { it.id !in pendingToolIds }
                            .forEach { hotelToolDao.insertTool(it) }
                    }
                } catch (e: Exception) {
                    Log.e("SyncManager", "Error downloading hotel data: ${e.message}")
                }

                // Sanitizar localmente y subir estado limpio a la nube
                localDb?.loanDao()?.sanitizeAllLoans()
                scheduleOfflineSync()

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
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("sales")
            .whereGreaterThan("timestamp", System.currentTimeMillis() - 86400000)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val sale = change.document.toObject(SaleEntity::class.java)
                            if (localDb?.saleDao()?.getSaleById(sale.id) == null) {
                                localDb?.saleDao()?.insert(sale)
                                withContext(Dispatchers.Main) { onNewSale() }
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeInventorySync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("inventory")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val product = change.document.toObject(ProductEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                if (product.isDeleted) {
                                    localDb?.productDao()?.deleteById(product.id)
                                    localDb?.productDao()?.delete(product)
                                } else {
                                    localDb?.productDao()?.insert(product)
                                }
                            }
                            DocumentChange.Type.REMOVED -> {
                                localDb?.productDao()?.deleteById(product.id)
                                localDb?.productDao()?.delete(product)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLogsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("movement_logs")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val log = change.document.toObject(MovementLogEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.movementLogDao()?.insert(log)
                            DocumentChange.Type.REMOVED -> localDb?.movementLogDao()?.delete(log)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeExpensesSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val expense = change.document.toObject(ExpenseEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.expenseDao()?.insert(expense)
                            DocumentChange.Type.REMOVED -> localDb?.expenseDao()?.delete(expense)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeDebtorsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("debtors")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val debtor = change.document.toObject(DebtorEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.debtorDao()?.insertDebtor(debtor)
                            DocumentChange.Type.REMOVED -> localDb?.debtorDao()?.deleteDebtor(debtor)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeProvidersSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("providers")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val provider = change.document.toObject(ProviderEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.providerDao()?.insert(provider)
                            DocumentChange.Type.REMOVED -> localDb?.providerDao()?.delete(provider)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeCashSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("cash_sessions")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val session = change.document.toObject(CashSessionEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.cashDao()?.insert(session)
                            DocumentChange.Type.REMOVED -> localDb?.cashDao()?.delete(session)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeHotelSync(onUpdate: () -> Unit): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val firestore = db ?: return emptyList()
        val regs = mutableListOf<ListenerRegistration>()
        
        val r1 = firestore.collection("users").document(userId).collection("hotel_bookings")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val booking = change.document.toObject(HotelBookingEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.hotelDao()?.insertBooking(booking)
                            DocumentChange.Type.REMOVED -> localDb?.hotelDao()?.deleteBooking(booking)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
        regs.add(r1)

        val r2 = firestore.collection("users").document(userId).collection("hotel_rooms")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val room = change.document.toObject(HotelRoomEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.hotelDao()?.insertRoom(room)
                            DocumentChange.Type.REMOVED -> localDb?.hotelDao()?.deleteRoom(room)
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
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loan_clients")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val toUpsert = mutableListOf<LoanClientEntity>()
                    snapshot.documentChanges.forEach { change ->
                        val client = change.document.toObject(LoanClientEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> toUpsert.add(client)
                            DocumentChange.Type.REMOVED -> localDb?.loanDao()?.deleteClient(client)
                        }
                    }
                    if (toUpsert.isNotEmpty()) {
                        localDb?.loanDao()?.insertClients(toUpsert)
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoansSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loans")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val toUpsert = mutableListOf<LoanEntity>()
                    snapshot.documentChanges.forEach { change ->
                        val loan = change.document.toObject(LoanEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> toUpsert.add(loan)
                            DocumentChange.Type.REMOVED -> localDb?.loanDao()?.deleteLoan(loan)
                        }
                    }
                    if (toUpsert.isNotEmpty()) {
                        mergeLoansFromCloud(toUpsert)
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeInstallmentsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loan_installments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val toUpsert = mutableListOf<LoanInstallmentEntity>()
                    snapshot.documentChanges.forEach { change ->
                        val installment = change.document.toObject(LoanInstallmentEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> toUpsert.add(installment)
                            DocumentChange.Type.REMOVED -> localDb?.loanDao()?.deleteInstallment(installment)
                        }
                    }
                    if (toUpsert.isNotEmpty()) {
                        localDb?.loanDao()?.insertInstallments(toUpsert)
                        // Sanitizar: la nube puede traer cuotas duplicadas y hay que
                        // consolidarlas para que los saldos por cliente sean correctos.
                        localDb?.loanDao()?.sanitizeAllLoans()
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoanPaymentsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loan_payments")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    val toUpsert = mutableListOf<LoanPaymentEntity>()
                    snapshot.documentChanges.forEach { change ->
                        val payment = change.document.toObject(LoanPaymentEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> toUpsert.add(payment)
                            DocumentChange.Type.REMOVED -> localDb?.loanDao()?.deletePayment(payment)
                        }
                    }
                    if (toUpsert.isNotEmpty()) {
                        localDb?.loanDao()?.insertPayments(toUpsert)
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoanExpensesSystemSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loan_expenses")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val expense = change.document.toObject(LoanExpenseEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.loanDao()?.insertExpense(expense)
                            DocumentChange.Type.REMOVED -> localDb?.loanDao()?.deleteExpense(expense)
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeLoanBudgetsSync(onUpdate: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        return firestore.collection("users").document(userId).collection("loan_budgets")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val budget = change.document.toObject(LoanBudgetEntity::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> localDb?.loanDao()?.insertBudget(budget)
                            DocumentChange.Type.REMOVED -> {}
                        }
                    }
                    withContext(Dispatchers.Main) { onUpdate() }
                }
            }
    }

    fun startRealtimeRequestsSync(): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        val startTime = System.currentTimeMillis()
        
        return firestore.collection("users").document(userId).collection("booking_requests")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val req = change.document.toObject(HotelBookingRequest::class.java)
                            
                            val ts = when (val t = req.timestamp) {
                                is Timestamp -> t.toDate().time
                                is Long -> t
                                else -> System.currentTimeMillis()
                            }

                            if (ts >= startTime - 10000) {
                                NotificationHelper.showRequestNotification(context, req.guestName)
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeCollaboratorRequestsSync(): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        val startTime = System.currentTimeMillis()
        
        return firestore.collection("users").document(userId).collection("collaborator_requests")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val name = change.document.getString("requesterName") ?: "Alguien"
                            val ts = change.document.get("timestamp") as? Long ?: System.currentTimeMillis()

                            if (ts >= startTime - 10000) {
                                NotificationHelper.showRequestNotification(context, name)
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeOrdersSync(onNewOrder: () -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null
        val startTime = System.currentTimeMillis()
        
        return firestore.collection("orders")
            .whereEqualTo("storeId", userId)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SyncManager", "Error orders listener: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            try {
                                val order = change.document.toObject(OrderModel::class.java)
                                if (order != null) {
                                    // Solo notificar si el pedido es nuevo (creado después de iniciar la escucha)
                                    // O si no tiene timestamp (recién creado en el cliente)
                                    val orderTime = when (val t = order.timestamp) {
                                        is Timestamp -> t.toDate().time
                                        is Long -> t
                                        else -> System.currentTimeMillis()
                                    }
                                    
                                    if (orderTime >= startTime - 5000) { // Margen de 5 seg
                                        try {
                                            NotificationHelper.showOrderNotification(context.applicationContext, order.buyerName)
                                        } catch (err: Exception) { Log.e("SyncManager", "Error notification: ${err.message}") }
                                    }
                                    withContext(Dispatchers.Main) { onNewOrder() }
                                }
                            } catch (e: Exception) {
                                Log.e("SyncManager", "Error deserializing order: ${e.message}")
                            }
                        }
                    }
                }
            }
    }

    fun startRealtimeMessagesSync(): List<ListenerRegistration> {
        val userId = getEffectiveUserId() ?: return emptyList()
        val firestore = db ?: return emptyList()
        val regs = mutableListOf<ListenerRegistration>()
        
        val startTime = System.currentTimeMillis()

        val r = firestore.collection("users").document(userId).collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(5)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                syncScope.launch {
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == DocumentChange.Type.ADDED) {
                            val doc = change.document
                            val msg = doc.toObject(AppMessage::class.java)
                            
                            val ts = when (val t = msg.timestamp) {
                                is Timestamp -> t.toDate().time
                                is Long -> t
                                else -> 0L
                            }
                            
                            // 1. Verificar que sea un mensaje realmente nuevo
                            // Usamos un margen de 10 segundos para cubrir posibles desincronizaciones de reloj
                            if (ts > startTime - 10000) {
                                // 2. Verificar si el usuario está ya dentro de ese chat específico
                                val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                                val currentChatId = prefs.getString("current_chat_id", null)
                                val senderId = doc.getString("senderId") ?: ""
                                
                                // ID de chat determinista para comparar
                                val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                val deterministicChatId = if (myUid < senderId) "${myUid}_$senderId" else "${senderId}_$myUid"

                                Log.d("SyncManager", "Comparando: currentChat=$currentChatId, deterministic=$deterministicChatId, sender=$senderId")

                                if (currentChatId != deterministicChatId && currentChatId != senderId) {
                                    Log.d("SyncManager", "Disparando notificación...")
                                    NotificationHelper.showMessageNotification(
                                        context, msg.title, msg.content, 
                                        chatId = deterministicChatId,
                                        senderId = senderId
                                    )
                                }
                            }
                        }
                    }
                }
            }
        regs.add(r)
        return regs
    }

    fun startRealtimeUnreadCountSync(onCountChanged: (Int) -> Unit): ListenerRegistration? {
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null

        return firestore.collection("direct_chats")
            .whereArrayContains("participants", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                var totalUnread = 0
                snapshot.documents.forEach { doc ->
                    val unreadMap = doc.get("unreadCount") as? Map<String, Long>
                    totalUnread += unreadMap?.get(userId)?.toInt() ?: 0
                }
                onCountChanged(totalUnread)
            }
    }

    fun startRealtimeBuyerActiveOrdersCountSync(onCountChanged: (Int) -> Unit): ListenerRegistration? {
        if (isGuestUser()) return null
        val userId = getEffectiveUserId() ?: return null
        val firestore = db ?: return null

        return firestore.collection("orders")
            .whereEqualTo("buyerId", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                // Contar pedidos que NO estén entregados ni cancelados
                val activeCount = snapshot.documents.count { doc ->
                    val status = doc.getString("status") ?: ""
                    status == "PENDING" || status == "ACCEPTED" || status == "EN_ROUTE"
                }
                onCountChanged(activeCount)
            }
    }

    fun scheduleOfflineSync() {
        if (isGuestUser()) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork("offline_sync")
                WorkManager.getInstance(context).cancelUniqueWork("periodic_offline_sync")
                WorkManager.getInstance(context).cancelUniqueWork("periodic_sync")
            } catch (e: Exception) {}
            return
        }
        try {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("offline_sync", ExistingWorkPolicy.KEEP, syncRequest)
        } catch (e: Exception) {
            Log.e("SyncManager", "Error scheduling offline sync: ${e.message}")
        }
    }

    fun deleteProductFromCloud(productId: String, photoUrl: String? = null) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("inventory").document(productId).delete().await()
                firestore.collection("users").document(userId).collection("products").document(productId).delete().await()
                
                if (!photoUrl.isNullOrEmpty() && photoUrl.contains("naxor-imagenes-usuarios")) {
                    val path = photoUrl.substringAfter("naxor-imagenes-usuarios/")
                    if (path.isNotBlank()) {
                        SupabaseManager.deleteImage("naxor-imagenes-usuarios", path)
                    }
                }
            } catch (e: Exception) { Log.e("SyncManager", "Error delete product: ${e.message}") }
        }
    }

    fun cleanTempCacheFiles() {
        try {
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".jpg") || file.name.endsWith(".png") || file.name.contains("tmp")) {
                    file.delete()
                }
            }
            context.externalCacheDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".jpg") || file.name.endsWith(".png") || file.name.contains("tmp")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "Error cleaning temp cache: ${e.message}")
        }
    }

    fun deleteLoanClientFromCloud(clientId: String) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                firestore.collection("users").document(userId).collection("loan_clients").document(clientId).delete().await()
            } catch (e: Exception) { Log.e("SyncManager", "Error delete loan client: ${e.message}") }
        }
    }

    fun downloadBusinessSettingsFromCloud(businessType: String) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                val doc = firestore.collection("users").document(userId).collection("settings").document(businessType).get().await()
                if (doc.exists()) {
                    val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    doc.data?.forEach { (key, value) -> 
                        if (value is String) editor.putString(key, value)
                        else if (value is Boolean) editor.putBoolean(key, value)
                        else if (value is Long) editor.putLong(key, value)
                    }
                    editor.apply()
                }
            } catch (e: Exception) { Log.e("SyncManager", "Error download settings: ${e.message}") }
        }
    }

    fun deleteCollectionFromCloud(collectionName: String, onComplete: (() -> Unit)? = null) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        syncScope.launch {
            try {
                val snapshot = firestore.collection("users").document(userId).collection(collectionName).get().await()
                val batch = firestore.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onComplete?.invoke() } }
        }
    }

    fun deleteEverythingFromCloud(onComplete: () -> Unit) {
        if (isGuestUser()) {
            onComplete()
            return
        }
        val userId = getEffectiveUserId() ?: run {
            onComplete()
            return
        }
        val firestore = db ?: run {
            onComplete()
            return
        }
        syncScope.launch {
            try {
                val collections = listOf("inventory", "sales", "expenses", "debtors", "customers", "providers", "movement_logs", "cash_sessions", "hotel_bookings", "hotel_rooms", "hotel_maintenance", "loan_clients", "loans")
                for (coll in collections) {
                    val snapshot = firestore.collection("users").document(userId).collection(coll).get().await()
                    val batch = firestore.batch()
                    snapshot.documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onComplete() } }
        }
    }

    fun syncLoanBudgetToCloud(budget: LoanBudgetEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        try {
            firestore.collection("users").document(userId).collection("loan_budgets")
                .document(budget.id).set(budget, SetOptions.merge())
            syncScope.launch {
                localDb?.loanDao()?.markLoanBudgetSynced(budget.id)
            }
        } catch (e: Exception) { Log.e("SyncManager", "Error loan_budget: ${e.message}") }
    }

    /**
     * Sube un préstamo concreto a la nube de forma inmediata.
     * Se usa cuando el usuario cambia el ORIGEN DE FONDOS (personal / presupuesto),
     * para que la nube quede al día y ninguna sincronización posterior lo revierta.
     */
    fun syncLoanToCloud(loan: LoanEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        try {
            firestore.collection("users").document(userId).collection("loans")
                .document(loan.id).set(loan, SetOptions.merge())
            syncScope.launch {
                localDb?.loanDao()?.markLoanSynced(loan.id)
            }
        } catch (e: Exception) { Log.e("SyncManager", "Error loan: ${e.message}") }
    }

    /**
     * Fusiona los préstamos que llegan de la nube con los locales SIN sobrescribir
     * el ORIGEN DE FONDOS (personal / presupuesto) cuando el registro local tiene
     * cambios pendientes de subir. Esto evita que una versión antigua de la nube
     * revierta el origen que el usuario acaba de configurar.
     *
     * Los préstamos con cambios locales pendientes ya están filtrados por el
     * llamador, por lo que aquí solo se conservan los que están realmente limpios.
     */
    private suspend fun mergeLoansFromCloud(cloudLoans: List<LoanEntity>) {
        val db = localDb ?: return
        val localById = db.loanDao().getAllLoansOnce().associateBy { it.id }
        val merged = cloudLoans.map { cloudLoan ->
            val local = localById[cloudLoan.id]
            if (local != null && !local.isSynced && local.isPersonal != cloudLoan.isPersonal) {
                // El local tiene cambios sin subir: se conserva el origen local
                cloudLoan.copy(isPersonal = local.isPersonal, isSynced = false)
            } else {
                cloudLoan.copy(isSynced = true)
            }
        }
        db.loanDao().insertLoans(merged)
    }

    fun syncLoanExpenseToCloud(expense: LoanExpenseEntity) {
        val userId = getEffectiveUserId() ?: return
        val firestore = db ?: return
        try {
            firestore.collection("users").document(userId).collection("loan_expenses")
                .document(expense.id).set(expense, SetOptions.merge())
            syncScope.launch {
                localDb?.loanDao()?.markLoanExpenseSynced(expense.id)
            }
        } catch (e: Exception) { Log.e("SyncManager", "Error loan_expense: ${e.message}") }
    }

    fun uploadAllLocalToCloud(onComplete: () -> Unit) {
        if (isGuestUser()) {
            onComplete()
            return
        }
        getEffectiveUserId() ?: run { onComplete(); return }
        syncScope.launch {
            try {
                // Sincronizar productos no sincronizados
                val unsyncedProducts = localDb?.productDao()?.allUnsyncedProducts
                unsyncedProducts?.forEach { syncProductToCloud(it) }

                // Sincronizar deudores no sincronizados
                val unsyncedDebtors = localDb?.debtorDao()?.getAllUnsyncedDebtors()
                unsyncedDebtors?.forEach { syncDebtorToCloud(it) }

                // Sincronizar gastos no sincronizados
                val unsyncedExpenses = localDb?.expenseDao()?.getAllUnsyncedExpenses()
                unsyncedExpenses?.forEach { syncExpenseToCloud(it) }

                // Sincronizar presupuestos y gastos de préstamos no sincronizados
                val unsyncedBudgets = localDb?.loanDao()?.getUnsyncedBudgets()
                unsyncedBudgets?.forEach { syncLoanBudgetToCloud(it) }

                val unsyncedLoanExpenses = localDb?.loanDao()?.getUnsyncedLoanExpenses()
                unsyncedLoanExpenses?.forEach { syncLoanExpenseToCloud(it) }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error uploading all: ${e.message}")
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
