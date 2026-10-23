package com.gomezmart.app

import android.Manifest
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.gomezmart.app.data.*
import com.gomezmart.app.databinding.*
import com.gomezmart.app.databinding.ItemChatMessageOrderAlertMeBinding
import com.gomezmart.app.databinding.ItemChatMessageOrderAlertOtherBinding
import com.gomezmart.app.network.SupabaseManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.gomezmart.app.util.GlideHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.*
import java.io.File

class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var orderId: String = ""
    private var chatRegistration: ListenerRegistration? = null
    private var chatMetadataRegistration: ListenerRegistration? = null
    private var otherProfileRegistration: ListenerRegistration? = null
    private var myProfileRegistration: ListenerRegistration? = null
    
    private var lastClearedAt: Long = 0L
    private lateinit var adapter: ChatAdapter

    private var isDirectChat: Boolean = false
    private var otherUserId: String = ""
    private var otherUserName: String = ""

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val file = copyUriToFile(uri)
            if (file != null) {
                uploadChatImage(file)
            } else {
                Toast.makeText(this, "Error al procesar la imagen seleccionada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(getExternalFilesDir(null), "chat_gallery_${System.currentTimeMillis()}.jpg")
            val outputStream = tempFile.outputStream()
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error al copiar URI: ${e.message}")
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets de cabecera y teclado (IME)
        applyWindowInsets(binding.appBarChat, applyTop = true, applyBottom = false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutChatInput) { v, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomInset = if (imeHeight > 0) imeHeight else navBarHeight

            val params = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.bottomMargin != bottomInset) {
                params.bottomMargin = bottomInset
                v.layoutParams = params
            }
            insets
        }

        binding.etChatMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.rvChatMessages.postDelayed({
                    if (::adapter.isInitialized && adapter.itemCount > 0) {
                        binding.rvChatMessages.smoothScrollToPosition(adapter.itemCount - 1)
                    }
                }, 200)
            }
        }

        orderId = intent.getStringExtra("ORDER_ID") ?: ""
        otherUserId = intent.getStringExtra("OTHER_USER_ID") ?: ""
        otherUserName = intent.getStringExtra("OTHER_USER_NAME") ?: "Chat"
        val otherPhoto = intent.getStringExtra("OTHER_USER_PHOTO") ?: ""
        val otherTitle = intent.getStringExtra("OTHER_USER_TITLE") ?: ""
        val otherBizLogo = intent.getStringExtra("OTHER_USER_BIZ_LOGO") ?: ""
        isDirectChat = intent.getBooleanExtra("IS_DIRECT", false) || orderId.contains("_")

        binding.tvChatHeaderName.text = otherUserName
        binding.tvChatHeaderRole.text = if (otherTitle.isNotEmpty()) otherTitle else "Usuario"
        updateRoleColor(binding.tvChatHeaderRole.text.toString())
        
        if (otherPhoto.isNotEmpty()) {
            binding.ivChatHeaderPhoto.visibility = View.VISIBLE
            Glide.with(this).load(otherPhoto)
                .apply(GlideHelper.getOptions(this))
                .circleCrop().into(binding.ivChatHeaderPhoto)
        } else {
            binding.ivChatHeaderPhoto.visibility = View.GONE
        }
        
        if (otherBizLogo.isNotEmpty()) {
            binding.cardChatHeaderBizLogo.visibility = View.VISIBLE
            Glide.with(this).load(otherBizLogo)
                .apply(GlideHelper.getOptions(this))
                .circleCrop().into(binding.ivChatHeaderBizLogo)
        } else {
            binding.cardChatHeaderBizLogo.visibility = View.GONE
        }

        val headerClickListener = View.OnClickListener {
            showUserProfilePreviewDialog()
        }
        binding.ivChatHeaderPhoto.setOnClickListener(headerClickListener)
        binding.tvChatHeaderName.setOnClickListener(headerClickListener)
        binding.tvChatHeaderRole.setOnClickListener(headerClickListener)
        binding.cardChatHeaderBizLogo.setOnClickListener(headerClickListener)

        binding.toolbarChat.setNavigationOnClickListener { finish() }

        clearNotificationsFromThisUser()
        checkUserRoleForSharing()
        setupRecyclerView()
        setupListeners()
        
        if (isDirectChat) {
            loadInitialUnreadCountAndStartListener()
            fetchParticipantProfiles()
        } else {
            startChatListener()
        }
    }

    private fun clearNotificationsFromThisUser() {
        if (otherUserId.isNotEmpty()) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(otherUserId.hashCode())
        }
    }

    private fun loadInitialUnreadCountAndStartListener() {
        val myUid = auth.currentUser?.uid ?: return
        
        // OPTIMIZACIÓN: Intentar leer de CACHE primero para velocidad instantánea
        db.collection("direct_chats").document(orderId).get(Source.CACHE)
            .addOnSuccessListener { doc ->
                processInitialUnread(doc, myUid)
            }
            .addOnFailureListener {
                // Si falla cache, ir a servidor
                db.collection("direct_chats").document(orderId).get(Source.SERVER)
                    .addOnSuccessListener { doc -> processInitialUnread(doc, myUid) }
                    .addOnFailureListener { startChatListener() }
            }
    }

    private fun processInitialUnread(doc: DocumentSnapshot, myUid: String) {
        if (doc.exists()) {
            val unreadMap = doc.get("unreadCount") as? Map<String, Long>
            initialUnreadCount = unreadMap?.get(myUid)?.toInt() ?: 0
            Log.d("ChatActivity", "Initial unread count: $initialUnreadCount")
        }
        startChatListener()
        resetUnreadCount()
    }

    private fun resetUnreadCount() {
        val myUid = auth.currentUser?.uid ?: return
        if (orderId.isNotEmpty() && orderId.contains("_")) { // ID de chat directo siempre tiene guion bajo
            db.collection("direct_chats").document(orderId)
                .update("unreadCount.$myUid", 0)
                .addOnSuccessListener {
                    Log.d("ChatActivity", "Unread count reset successful for $myUid")
                }
        }
    }

    private var currentMyProfile: Map<String, Any>? = null
    private var currentOtherProfile: Map<String, Any>? = null

    private fun fetchParticipantProfiles() {
        val myUid = auth.currentUser?.uid ?: return
        
        // 1. Escuchar MI PROPIO perfil (Para inyectar mis cambios en este chat)
        myProfileRegistration = db.collection("users").document(myUid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    currentMyProfile = snapshot.data
                    syncCompleteMetadata()
                }
            }

        // 2. Escuchar el perfil del OTRO usuario
        otherProfileRegistration = db.collection("users").document(otherUserId).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    currentOtherProfile = snapshot.data
                    
                    val oName = snapshot.getString("name") ?: "Usuario"
                    val oPhoto = snapshot.getString("photoUrl") ?: ""
                    val oTitle = snapshot.getString("businessTitle") ?: "Comprador"
                    val oBizLogo = snapshot.getString("businessLogoUrl") ?: ""
                    
                    otherUserName = oName
                    binding.tvChatHeaderName.text = otherUserName
                    binding.tvChatHeaderRole.text = oTitle
                    updateRoleColor(oTitle)
                    
                    if (oPhoto.isNotEmpty()) {
                        binding.ivChatHeaderPhoto.visibility = View.VISIBLE
                        Glide.with(this@ChatActivity).load(oPhoto)
                            .apply(GlideHelper.getOptions(this@ChatActivity))
                            .circleCrop().into(binding.ivChatHeaderPhoto)
                    } else {
                        binding.ivChatHeaderPhoto.visibility = View.GONE
                    }
                    
                    if (oBizLogo.isNotEmpty()) {
                        binding.cardChatHeaderBizLogo.visibility = View.VISIBLE
                        Glide.with(this@ChatActivity).load(oBizLogo)
                            .apply(GlideHelper.getOptions(this@ChatActivity))
                            .circleCrop().into(binding.ivChatHeaderBizLogo)
                    } else {
                        binding.cardChatHeaderBizLogo.visibility = View.GONE
                    }
                    
                    syncCompleteMetadata()
                }
            }
    }

    private fun syncCompleteMetadata() {
        val myUid = auth.currentUser?.uid ?: return
        if (orderId.isEmpty() || !isDirectChat) return
        
        val chatRef = db.collection("direct_chats").document(orderId)
        
        // Extraer datos de mis perfiles locales
        val myName = currentMyProfile?.get("name")?.toString() ?: auth.currentUser?.displayName ?: "Usuario"
        val myPhoto = currentMyProfile?.get("photoUrl")?.toString() ?: auth.currentUser?.photoUrl?.toString() ?: ""
        val myTitle = currentMyProfile?.get("businessTitle")?.toString() ?: "Comprador"
        val myEmail = currentMyProfile?.get("email")?.toString() ?: auth.currentUser?.email ?: ""
        val myPhone = currentMyProfile?.get("phone")?.toString() ?: ""

        val oName = currentOtherProfile?.get("name")?.toString() ?: otherUserName
        val oPhoto = currentOtherProfile?.get("photoUrl")?.toString() ?: ""
        val oTitle = currentOtherProfile?.get("businessTitle")?.toString() ?: "Comprador"
        val oEmail = currentOtherProfile?.get("email")?.toString() ?: ""
        val oPhone = currentOtherProfile?.get("phone")?.toString() ?: ""
        val oBizLogo = currentOtherProfile?.get("businessLogoUrl")?.toString() ?: ""

        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = bizPrefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val myBizLogo = bizPrefs.getString("${businessType}_logo_url", null) ?: bizPrefs.getString("business_logo_url", "") ?: ""

        val names = mapOf(myUid to myName, otherUserId to oName)
        val photos = mapOf(myUid to myPhoto, otherUserId to oPhoto)
        val titles = mapOf(myUid to myTitle, otherUserId to oTitle)
        val emails = mapOf(myUid to myEmail, otherUserId to oEmail)
        val phones = mapOf(myUid to myPhone, otherUserId to oPhone)
        
        val bizLogos = mutableMapOf<String, String>()
        if (myBizLogo.isNotEmpty()) bizLogos[myUid] = myBizLogo
        if (oBizLogo.isNotEmpty()) bizLogos[otherUserId] = oBizLogo

        val data = mapOf(
            "participantNames" to names,
            "participantPhotos" to photos,
            "participantEmails" to emails,
            "participantPhones" to phones,
            "participantBusinessLogos" to bizLogos,
            "participantTitles" to titles,
            "participants" to listOf(myUid, otherUserId).sorted()
        )
        
        chatRef.set(data, SetOptions.merge())
    }

    private fun updateRoleColor(title: String) {
        val color = when {
            title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> Color.parseColor("#FFD700") // Dorado
            title.contains("Colaborador", ignoreCase = true) -> Color.parseColor("#3B82F6") // Azul
            title.contains("Comprador", ignoreCase = true) -> Color.parseColor("#10B981") // Verde
            else -> Color.parseColor("#64748B") // Slate 500 (Gris)
        }
        binding.tvChatHeaderRole.setTextColor(color)
    }

    // Eliminamos la versión antigua de syncChatMetadata


    private fun setupRecyclerView() {
        adapter = ChatAdapter(auth.currentUser?.uid ?: "")
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvChatMessages.layoutManager = layoutManager
        binding.rvChatMessages.adapter = adapter

        // AUTO-SCROLL ROBUSTO: Al añadir nuevos mensajes, bajar al fondo automáticamente
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                binding.rvChatMessages.post {
                    binding.rvChatMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        })
    }

    private fun setupListeners() {
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }
        binding.btnShareOptions.setOnClickListener {
            showShareOptions()
        }
    }

    private fun checkUserRoleForSharing() {
        // La opción de compartir (tomar foto) ahora es para TODOS
        binding.btnShareOptions.visibility = View.VISIBLE
    }

    private fun showShareOptions() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "") ?: ""
        val isCollab = prefs.getBoolean("is_collaborator_mode", false)
        val canShareCatalog = businessType.isNotEmpty() || isCollab

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_chat_share_options, null)
        dialog.setContentView(view)

        val btnCamera = view.findViewById<View>(R.id.btnOptionCamera)
        val btnGallery = view.findViewById<View>(R.id.btnOptionGallery)
        val btnCatalog = view.findViewById<View>(R.id.btnOptionCatalog)

        btnCatalog.visibility = if (canShareCatalog) View.VISIBLE else View.GONE

        btnCamera.setOnClickListener {
            dialog.dismiss()
            checkCameraPermissionAndTake()
        }

        btnGallery.setOnClickListener {
            dialog.dismiss()
            galleryLauncher.launch("image/*")
        }

        btnCatalog.setOnClickListener {
            dialog.dismiss()
            if (isCollab) {
                showCollaborationPicker()
            } else {
                showProductPicker()
            }
        }

        dialog.show()
    }

    private fun showCollaborationPicker() {
        val userEmail = auth.currentUser?.email?.lowercase() ?: return
        lifecycleScope.launch {
            try {
                val accessKeys = db.collection("access_keys")
                    .whereEqualTo("email", userEmail)
                    .get().await()
                
                if (accessKeys.isEmpty) {
                    Toast.makeText(this@ChatActivity, "No tienes colaboraciones activas", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val items = accessKeys.documents.map { "${it.getString("businessName")} (${it.getString("role")})" }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("¿De qué negocio compartir?")
                    .setItems(items) { _, which ->
                        val doc = accessKeys.documents[which]
                        val ownerId = doc.getString("ownerId") ?: ""
                        showProductPicker(ownerId)
                    }
                    .show()
            } catch (e: Exception) {}
        }
    }

    private fun showProductPicker(forcedOwnerId: String? = null) {
        val myUid = auth.currentUser?.uid ?: return
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val ownerId = forcedOwnerId ?: (if (prefs.getBoolean("is_collaborator_mode", false)) prefs.getString("collaborator_owner_id", myUid) else myUid)
        
        lifecycleScope.launch {
            try {
                val productsSnap = db.collection("users").document(ownerId!!).collection("inventory").get().await()
                val products = productsSnap.documents.mapNotNull { doc ->
                    val p = doc.toObject(ProductEntity::class.java)
                    p?.id = doc.id
                    p
                }
                
                if (products.isEmpty()) {
                    Toast.makeText(this@ChatActivity, "No hay productos disponibles", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val productNames = products.map { p -> "${p.nombre} (${p.stock} disp.)" }.toTypedArray()
                val selectedItems = mutableListOf<Int>()

                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Selecciona productos para enviar")
                    .setMultiChoiceItems(productNames, null) { _, which, isChecked ->
                        if (isChecked) selectedItems.add(which) else selectedItems.remove(which)
                    }
                    .setPositiveButton("Enviar") { _, _ ->
                        selectedItems.forEach { idx ->
                            val p = products[idx]
                            sendProductCard(p)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Error al cargar stock", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var photoFile: File? = null
    private fun checkCameraPermissionAndTake() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 301)
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile = File(getExternalFilesDir(null), "chat_photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile!!)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        startActivityForResult(intent, 301)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 301 && resultCode == RESULT_OK) {
            photoFile?.let { uploadChatImage(it) }
        }
    }

    private fun uploadChatImage(file: File) {
        val myUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        
        // 1. CREAR MENSAJE LOCAL INMEDIATO
        val localMessage = ChatMessage(
            id = messageId,
            senderId = myUid,
            message = "📷 Imagen",
            timestamp = Date(), // Timestamp local temporal
            type = "IMAGE",
            localPath = file.absolutePath,
            isUploading = true
        )
        
        // Añadir a la lista visual de inmediato
        adapter.addTempMessage(localMessage)
        binding.rvChatMessages.scrollToPosition(adapter.itemCount - 1)
        
        // Desaparecer separador al primer envío
        initialUnreadCount = 0
        refreshMessagesWithCurrentFilter()

        lifecycleScope.launch {
            try {
                val path = "chat_images/$orderId/${UUID.randomUUID()}.jpg"
                val url = SupabaseManager.uploadImage("naxor-imagenes-usuarios", path, file)
                
                if (url != null) {
                    // 2. ENVIAR A FIRESTORE UNA VEZ SUBIDO
                    val finalMessage = localMessage.copy(
                        imageUrl = url,
                        timestamp = FieldValue.serverTimestamp(),
                        isUploading = false,
                        localPath = null
                    )
                    
                    val collection = if (isDirectChat) "direct_chats" else "orders"
                    db.collection(collection).document(orderId).collection("messages").document(messageId)
                        .set(finalMessage)
                        .addOnSuccessListener {
                            adapter.markAsUploaded(messageId, url)
                            if (isDirectChat) {
                                updateChatMetadata("📷 [Imagen]")
                                sendNotificationToOtherUser("Te ha enviado una foto 📸")
                            }
                        }
                } else {
                    adapter.removeTempMessage(messageId)
                    Toast.makeText(this@ChatActivity, "Error al subir imagen", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                adapter.removeTempMessage(messageId)
                Toast.makeText(this@ChatActivity, "Error crítico: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Ya no usamos sendImageMessage por separado, la lógica se integró arriba


    private fun sendProductCard(p: ProductEntity) {
        if (orderId.isEmpty()) return
        val user = auth.currentUser ?: return
        val messageId = UUID.randomUUID().toString()
        
        val message = ChatMessage(
            id = messageId,
            senderId = user.uid,
            message = p.nombre,
            timestamp = FieldValue.serverTimestamp(),
            type = "PRODUCT_CARD",
            productId = p.id,
            productPhoto = p.photoUrl ?: "",
            productPrice = p.precioVenta,
            productCategory = p.categoria
        )
        
        // Desaparecer separador al primer envío
        initialUnreadCount = 0
        refreshMessagesWithCurrentFilter()

        val collection = if (isDirectChat) "direct_chats" else "orders"
        db.collection(collection).document(orderId).collection("messages").document(messageId)
            .set(message)
            .addOnSuccessListener {
                if (isDirectChat) {
                    updateChatMetadata("📦 [Producto] ${p.nombre}")
                    sendNotificationToOtherUser("Te ha compartido un producto: ${p.nombre}")
                }
            }
    }

    private fun startChatListener() {
        if (orderId.isEmpty()) return
        
        val myUid = auth.currentUser?.uid ?: return
        val collection = if (isDirectChat) "direct_chats" else "orders"

        // 1. Escuchar metadatos de borrado (clearedUntil) de forma INDEPENDIENTE
        chatMetadataRegistration?.remove()
        chatMetadataRegistration = db.collection(collection).document(orderId).addSnapshotListener { chatDoc, _ ->
            if (chatDoc != null && chatDoc.exists()) {
                val clearedMap = chatDoc.get("clearedUntil") as? Map<String, Long>
                val newClearedAt = clearedMap?.get(myUid) ?: 0L
                
                // Si el tiempo de borrado cambió, debemos actualizar la lista actual
                if (newClearedAt != lastClearedAt) {
                    lastClearedAt = newClearedAt
                    // Forzar re-filtrado de lo que ya tenemos si es necesario
                    refreshMessagesWithCurrentFilter()
                }
            }
        }

        // 2. Escuchar mensajes reales (Sin borrar este listener a menos que sea necesario)
        chatRegistration?.remove()
        chatRegistration = db.collection(collection).document(orderId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                lastMessagesSnapshot = snapshot.toObjects(ChatMessage::class.java)
                refreshMessagesWithCurrentFilter()
            }
    }

    private var lastMessagesSnapshot: List<ChatMessage> = emptyList()

    private fun refreshMessagesWithCurrentFilter() {
        val visibleMessages = lastMessagesSnapshot.filter { msg ->
            val msgTs = when (val t = msg.timestamp) {
                is Timestamp -> t.toDate().time
                is Long -> t
                else -> 0L
            }
            // Filtro estricto: solo mensajes posteriores al borrado
            msgTs > lastClearedAt || lastClearedAt == 0L
        }

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isDirectChat) {
            resetUnreadCount()
        }

        adapter.submitList(visibleMessages)
        
        // AUTO-SCROLL INTELIGENTE Y RÁPIDO
        if (visibleMessages.isNotEmpty()) {
            binding.rvChatMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private var initialUnreadCount: Int = 0

    private fun sendMessage() {
        val text = binding.etChatMessage.text.toString().trim()
        if (text.isEmpty() || orderId.isEmpty()) return
        
        val user = auth.currentUser ?: return
        val messageId = UUID.randomUUID().toString()
        val message = ChatMessage(
            id = messageId,
            senderId = user.uid,
            message = text,
            timestamp = FieldValue.serverTimestamp()
        )

        binding.etChatMessage.setText("")
        
        // Al enviar el PRIMER mensaje, el separador de "Nuevos mensajes" debe desaparecer
        initialUnreadCount = 0
        refreshMessagesWithCurrentFilter()

        val collection = if (isDirectChat) "direct_chats" else "orders"
        val myUid = user.uid

        db.collection(collection).document(orderId).collection("messages").document(messageId)
            .set(message)
            .addOnSuccessListener {
                if (isDirectChat) {
                    updateChatMetadata(text)
                    sendNotificationToOtherUser(text)
                    
                    // Incrementar contador de no leídos para el destinatario
                    db.collection("direct_chats").document(orderId)
                        .update("unreadCount.$otherUserId", FieldValue.increment(1))
                } else {
                    db.collection("orders").document(orderId)
                        .update("lastMessage", text, "lastMessageTime", FieldValue.serverTimestamp())
                }
            }
    }

    private fun sendNotificationToOtherUser(lastMessage: String) {
        val myUid = auth.currentUser?.uid ?: return
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val myName = prefs.getString("user_full_name", auth.currentUser?.displayName ?: "Usuario") ?: "Usuario"

        val notificationMsg = mapOf(
            "title" to "Nuevo mensaje de $myName 📬",
            "content" to lastMessage,
            "timestamp" to FieldValue.serverTimestamp(),
            "type" to "CHAT",
            "senderId" to myUid
        )

        db.collection("users").document(otherUserId).collection("messages").add(notificationMsg)
    }

    private fun updateChatMetadata(lastMessage: String) {
        val myUid = auth.currentUser?.uid ?: return
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val myName = prefs.getString("user_full_name", auth.currentUser?.displayName ?: "Usuario") ?: "Usuario"
        val myPhoto = prefs.getString("user_photo_url", "")
        
        // Calcular mi título actual (puedo reutilizar la lógica de MainActivity o sacarlo de un lugar común)
        // Para simplificar, lo sacamos de lo que MainActivity guarda en Firestore, pero aquí necesitamos el valor local
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = bizPrefs.getString("business_type", "") ?: ""
        val bizName = bizPrefs.getString("${businessType}_name", null) ?: bizPrefs.getString("business_name", "") ?: ""
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
        val collabBizName = collabPrefs.getString("collab_business_name", "") ?: ""
        val myTitle = when {
            bizName.isNotEmpty() && collabBizName.isNotEmpty() -> "Gestionando y Colaborando"
            bizName.isNotEmpty() -> "Dueño de $bizName"
            collabBizName.isNotEmpty() -> "Colaborador en $collabBizName"
            else -> "Comprador"
        }

        val otherPhoto = intent.getStringExtra("OTHER_USER_PHOTO") ?: ""
        val otherTitle = intent.getStringExtra("OTHER_USER_TITLE") ?: ""
        
        val chatRef = db.collection("direct_chats").document(orderId)
        
        db.runTransaction { transaction ->
            val snapshot = transaction.get(chatRef)
            
            val participants = listOf(myUid, otherUserId).sorted()
            val names = (snapshot.get("participantNames") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val photos = (snapshot.get("participantPhotos") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val titles = (snapshot.get("participantTitles") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val emails = (snapshot.get("participantEmails") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val phones = (snapshot.get("participantPhones") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val clearedUntil = (snapshot.get("clearedUntil") as? Map<String, Long>)?.toMutableMap() ?: mutableMapOf()

            // Actualizar mi información
            names[myUid] = myName
            if (!myPhoto.isNullOrEmpty()) photos[myUid] = myPhoto
            titles[myUid] = myTitle
            emails[myUid] = prefs.getString("user_email", auth.currentUser?.email) ?: ""
            phones[myUid] = prefs.getString("user_phone", "") ?: ""
            
            // Actualizar información del otro
            if (otherUserName != "Chat") names[otherUserId] = otherUserName
            if (!otherPhoto.isNullOrEmpty()) photos[otherUserId] = otherPhoto
            if (otherTitle.isNotEmpty()) titles[otherUserId] = otherTitle
            
            if (snapshot.exists()) {
                transaction.update(chatRef, 
                    "lastMessage", lastMessage, 
                    "lastMessageTime", FieldValue.serverTimestamp(),
                    "participantNames", names,
                    "participantPhotos", photos,
                    "participantTitles", titles,
                    "participantEmails", emails,
                    "participantPhones", phones,
                    "clearedUntil", clearedUntil
                )
            } else {
                val data = mapOf(
                    "id" to orderId,
                    "participants" to participants,
                    "participantNames" to names,
                    "participantPhotos" to photos,
                    "participantTitles" to titles,
                    "participantEmails" to emails,
                    "participantPhones" to phones,
                    "clearedUntil" to clearedUntil,
                    "lastMessage" to lastMessage,
                    "lastMessageTime" to FieldValue.serverTimestamp()
                )
                transaction.set(chatRef, data)
            }
        }.addOnFailureListener { e ->
            Log.e("ChatActivity", "Error updating metadata: ${e.message}")
        }
    }

    private fun openNavigation(lat: Double, lng: Double, address: String) {
        val gmmIntentUri = if (lat != 0.0) {
            Uri.parse("google.navigation:q=$lat,$lng")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        }
        
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val webUri = if (lat != 0.0) {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
            } else {
                Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(address)}")
            }
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun updateLinkedOrderStatus(orderId: String, status: String) {
        if (orderId.isEmpty()) return
        val loading = AlertDialog.Builder(this).setMessage("Actualizando pedido...").show()
        
        db.collection("orders").document(orderId).update("status", status)
            .addOnSuccessListener {
                loading.dismiss()
                Toast.makeText(this, "Pedido ${if(status == "ACCEPTED") "aceptado" else "rechazado"} ✅", Toast.LENGTH_SHORT).show()
                
                // Opcional: Enviar mensaje automático de confirmación
                val statusMsg = if(status == "ACCEPTED") "¡He aceptado tu pedido! 👨‍🍳 Se está preparando." else "Lo siento, he tenido que cancelar el pedido. 🚫"
                binding.etChatMessage.setText(statusMsg)
                sendMessage()
            }
            .addOnFailureListener {
                loading.dismiss()
                Toast.makeText(this, "Error al actualizar", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .putString("current_chat_id", orderId)
            .apply()
        
        clearNotificationsFromThisUser()
        resetUnreadCount() // Limpiar contador al volver al chat
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .remove("current_chat_id")
            .apply()
        
        resetUnreadCount() // Asegurar que al salir se marquen como leídos
    }

    override fun onDestroy() {
        super.onDestroy()
        chatRegistration?.remove()
        chatMetadataRegistration?.remove()
        otherProfileRegistration?.remove()
        myProfileRegistration?.remove()
    }

    data class DateHeader(val title: String)

    inner class ChatAdapter(private val currentUserId: String) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        private var messages = listOf<ChatMessage>()
        private var displayItems = mutableListOf<Any>() 

        fun submitList(newList: List<ChatMessage>) {
            // Filtrar mensajes de servidor que ya tenemos como locales (por ID)
            val filteredServer = newList.filter { srv -> 
                displayItems.none { it is ChatMessage && it.id == srv.id && it.isUploading }
            }
            
            // Mantener los locales que están subiendo
            val uploadingOnes = displayItems.filter { it is ChatMessage && it.isUploading }
            
            val oldSize = displayItems.size
            messages = filteredServer
            refreshDisplayItems(uploadingOnes)
            
            // OPTIMIZACIÓN DE CARGA: Si solo se añadieron mensajes al final, usar notifyItemRangeInserted
            // para evitar parpadeos y pérdida de scroll
            if (displayItems.size > oldSize && oldSize > 0) {
                notifyItemRangeInserted(oldSize, displayItems.size - oldSize)
            } else {
                notifyDataSetChanged()
            }
        }

        fun addTempMessage(msg: ChatMessage) {
            displayItems.add(msg)
            notifyItemInserted(displayItems.size - 1)
        }

        fun removeTempMessage(id: String) {
            val idx = displayItems.indexOfFirst { it is ChatMessage && it.id == id }
            if (idx != -1) {
                displayItems.removeAt(idx)
                notifyItemRemoved(idx)
            }
        }

        fun markAsUploaded(id: String, url: String) {
            val idx = displayItems.indexOfFirst { it is ChatMessage && it.id == id }
            if (idx != -1) {
                val msg = displayItems[idx] as ChatMessage
                displayItems[idx] = msg.copy(isUploading = false, imageUrl = url, localPath = null)
                notifyItemChanged(idx)
            }
        }

        private fun formatDateHeader(timestamp: Any?): String {
            if (timestamp == null) return ""
            val date: Date = when (timestamp) {
                is Timestamp -> timestamp.toDate()
                is Date -> timestamp
                is Long -> Date(timestamp)
                else -> return ""
            }

            val calMessage = Calendar.getInstance().apply { time = date }
            val calToday = Calendar.getInstance()
            val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

            val isSameYearToday = calMessage.get(Calendar.YEAR) == calToday.get(Calendar.YEAR)
            val isToday = isSameYearToday && calMessage.get(Calendar.DAY_OF_YEAR) == calToday.get(Calendar.DAY_OF_YEAR)

            val isSameYearYesterday = calMessage.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR)
            val isYesterday = isSameYearYesterday && calMessage.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR)

            return when {
                isToday -> "Hoy"
                isYesterday -> "Ayer"
                else -> {
                    val sdf = SimpleDateFormat("d-MM-yyyy", Locale.getDefault())
                    sdf.format(date)
                }
            }
        }

        private fun refreshDisplayItems(uploading: List<Any> = emptyList()) {
            displayItems.clear()
            var lastDateKey = ""
            val totalMessages = messages.size

            if (initialUnreadCount > 0 && totalMessages >= initialUnreadCount) {
                val splitIdx = totalMessages - initialUnreadCount
                for (i in messages.indices) {
                    val msg = messages[i]
                    val dateKey = formatDateHeader(msg.timestamp)
                    if (dateKey.isNotEmpty() && dateKey != lastDateKey) {
                        displayItems.add(DateHeader(dateKey))
                        lastDateKey = dateKey
                    }
                    if (i == splitIdx) {
                        displayItems.add("SEPARATOR")
                    }
                    displayItems.add(msg)
                }
            } else {
                for (msg in messages) {
                    val dateKey = formatDateHeader(msg.timestamp)
                    if (dateKey.isNotEmpty() && dateKey != lastDateKey) {
                        displayItems.add(DateHeader(dateKey))
                        lastDateKey = dateKey
                    }
                    displayItems.add(msg)
                }
            }
            
            for (up in uploading) {
                if (up is ChatMessage) {
                    val dateKey = formatDateHeader(up.timestamp)
                    if (dateKey.isNotEmpty() && dateKey != lastDateKey) {
                        displayItems.add(DateHeader(dateKey))
                        lastDateKey = dateKey
                    }
                    displayItems.add(up)
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            val item = displayItems[position]
            if (item is DateHeader) return 9
            if (item is String) return 2 // Separator
            val msg = item as ChatMessage
            val isMe = msg.senderId == currentUserId
            
            return when (msg.type) {
                "PRODUCT_CARD" -> if (isMe) 3 else 4
                "IMAGE" -> if (isMe) 5 else 6
                "ORDER_ALERT" -> if (isMe) 7 else 8
                else -> if (isMe) 0 else 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> MessageViewHolder(ItemChatMessageMeBinding.inflate(inflater, parent, false))
                1 -> MessageViewHolder(ItemChatMessageOtherBinding.inflate(inflater, parent, false))
                3 -> ProductViewHolder(ItemChatMessageProductMeBinding.inflate(inflater, parent, false))
                4 -> ProductViewHolder(ItemChatMessageProductOtherBinding.inflate(inflater, parent, false))
                5 -> ImageViewHolder(ItemChatMessageImageMeBinding.inflate(inflater, parent, false))
                6 -> ImageViewHolder(ItemChatMessageImageOtherBinding.inflate(inflater, parent, false))
                7 -> OrderAlertViewHolder(ItemChatMessageOrderAlertMeBinding.inflate(inflater, parent, false).root)
                8 -> OrderAlertViewHolder(ItemChatMessageOrderAlertOtherBinding.inflate(inflater, parent, false).root)
                9 -> DateHeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
                else -> SeparatorViewHolder(inflater.inflate(R.layout.item_chat_separator, parent, false))
            }
        }

        private fun formatTime(timestamp: Any?): String {
            if (timestamp == null) return ""
            val date: Date = when (timestamp) {
                is Timestamp -> timestamp.toDate()
                is Date -> timestamp
                is Long -> Date(timestamp)
                else -> return ""
            }
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            return sdf.format(date).lowercase(Locale.getDefault())
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayItems[position]
            if (item is DateHeader) {
                val tv = holder.itemView.findViewById<TextView>(R.id.tvDateHeader)
                tv?.text = item.title
                return
            }
            if (item is ChatMessage) {
                val tvTime = holder.itemView.findViewById<TextView>(R.id.tvMessageTime)
                if (tvTime != null) {
                    tvTime.text = formatTime(item.timestamp)
                }

                when (holder) {
                    is MessageViewHolder -> {
                        val tv = holder.itemView.findViewById<TextView>(R.id.tvMessageText)
                        tv?.text = item.message
                    }
                    is ProductViewHolder -> {
                        bindProductCard(holder, item)
                    }
                    is OrderAlertViewHolder -> {
                        bindOrderAlert(holder, item)
                    }
                    is ImageViewHolder -> {
                        val iv = holder.itemView.findViewById<ImageView>(R.id.ivChatMessageImage)
                        val pb = holder.itemView.findViewById<ProgressBar>(R.id.pbImageUpload)
                        
                        if (iv != null) {
                            // Cargar desde URL o desde path local si está subiendo
                            val source = if (item.isUploading) item.localPath else item.imageUrl
                            
                            Glide.with(holder.itemView.context)
                                .load(source)
                                .apply(GlideHelper.getOptions(holder.itemView.context))
                                .into(iv)
                                
                            // Efecto de transparencia si está subiendo
                            iv.alpha = if (item.isUploading) 0.5f else 1.0f
                            pb?.visibility = if (item.isUploading) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }

        private fun bindProductCard(holder: ProductViewHolder, msg: ChatMessage) {
            val tvName = holder.itemView.findViewById<TextView>(R.id.tvProductCardName)
            val tvPrice = holder.itemView.findViewById<TextView>(R.id.tvProductCardPrice)
            val ivPhoto = holder.itemView.findViewById<ImageView>(R.id.ivProductCardPhoto)
            val btnOrder = holder.itemView.findViewById<MaterialButton>(R.id.btnProductCardOrder)

            tvName?.text = msg.message
            tvPrice?.text = String.format(Locale.US, "S/ %.2f", msg.productPrice)
            
            if (msg.productPhoto.isNotEmpty() && ivPhoto != null) {
                Glide.with(holder.itemView.context)
                    .load(msg.productPhoto)
                    .apply(GlideHelper.getOptions(holder.itemView.context))
                    .into(ivPhoto)
            }

            // El botón de pedir solo debe ser funcional para quien recibe el producto (el comprador)
            val isMe = msg.senderId == currentUserId
            if (isMe) {
                btnOrder?.alpha = 0.5f
                btnOrder?.text = "ENVIADO 📦"
                btnOrder?.setOnClickListener(null)
            } else {
                btnOrder?.alpha = 1.0f
                btnOrder?.text = "PEDIR 🛵"
                btnOrder?.setOnClickListener {
                    startProductOrderFlow(msg)
                }
            }
        }

        private fun bindOrderAlert(holder: OrderAlertViewHolder, msg: ChatMessage) {
            val tvText = holder.itemView.findViewById<TextView>(R.id.tvOrderAlertText)
            val btnAccept = holder.itemView.findViewById<MaterialButton>(R.id.btnOrderAlertAccept)
            val btnReject = holder.itemView.findViewById<MaterialButton>(R.id.btnOrderAlertReject)
            val btnLocation = holder.itemView.findViewById<MaterialButton>(R.id.btnOrderAlertLocation)

            val isMe = msg.senderId == currentUserId
            
            // Personalizar mensaje para que no parezca que el receptor lo escribió
            if (isMe) {
                tvText?.text = msg.message
            } else {
                val name = otherUserName.ifEmpty { "Un cliente" }
                tvText?.text = msg.message.replace("¡He realizado", "¡$name ha realizado")
            }
            
            // Configurar botones según el rol
            if (isMe) {
                btnAccept?.visibility = View.GONE
                btnReject?.visibility = View.GONE
                btnLocation?.text = "Hacer Seguimiento 🛵"
            } else {
                btnAccept?.visibility = View.VISIBLE
                btnReject?.visibility = View.VISIBLE
                btnLocation?.text = "Ver Ubicación 📍"
            }

            btnAccept?.setOnClickListener {
                updateLinkedOrderStatus(msg.linkedOrderId, "ACCEPTED")
            }
            btnReject?.setOnClickListener {
                updateLinkedOrderStatus(msg.linkedOrderId, "CANCELLED")
            }
            btnLocation?.setOnClickListener {
                if (isMe && msg.linkedOrderId.isNotEmpty()) {
                    val intent = Intent(this@ChatActivity, OrderTrackingActivity::class.java).apply {
                        putExtra("ORDER_ID", msg.linkedOrderId)
                    }
                    startActivity(intent)
                } else {
                    openNavigation(msg.deliveryLat, msg.deliveryLng, msg.deliveryAddress)
                }
            }
        }

        override fun getItemCount() = displayItems.size

        inner class MessageViewHolder(b: ViewBinding) : RecyclerView.ViewHolder(b.root)
        
        inner class ProductViewHolder(b: ViewBinding) : RecyclerView.ViewHolder(b.root) {
            val cardBinding: ItemChatProductCardBinding by lazy {
                ItemChatProductCardBinding.bind(itemView.findViewById(R.id.cardProductCard))
            }
        }

        inner class OrderAlertViewHolder(view: View) : RecyclerView.ViewHolder(view)

        inner class ImageViewHolder(b: ViewBinding) : RecyclerView.ViewHolder(b.root)
        
        inner class DateHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v)

        inner class SeparatorViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    private fun startProductOrderFlow(msg: ChatMessage) {
        val options = arrayOf("A domicilio 🛵")
        AlertDialog.Builder(this)
            .setTitle("Realizar pedido de ${msg.message}")
            .setItems(options) { _, _ ->
                showLocationSelection(msg)
            }
            .show()
    }

    private fun showLocationSelection(msg: ChatMessage) {
        val locOptions = arrayOf("Ubicación actual 📍", "Ubicación registrada 🏠", "Nueva ubicación 🗺️")
        AlertDialog.Builder(this)
            .setTitle("¿A dónde enviamos tu pedido?")
            .setItems(locOptions) { _, which ->
                when (which) {
                    0 -> useCurrentLocationForOrder(msg)
                    1 -> showRegisteredLocations(msg)
                    2 -> openMapToSelectLocation(msg)
                }
            }
            .show()
    }

    private fun useCurrentLocationForOrder(msg: ChatMessage) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
            return
        }
        
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                createOrderFromChat(msg, loc.latitude, loc.longitude, "Ubicación actual")
            } else {
                Toast.makeText(this, "No se pudo obtener tu ubicación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRegisteredLocations(msg: ChatMessage) {
        val myUid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val locsSnap = db.collection("users").document(myUid).collection("locations").get().await()
                if (locsSnap.isEmpty) {
                    Toast.makeText(this@ChatActivity, "No tienes ubicaciones registradas", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val names = locsSnap.documents.map { it.getString("name") ?: "Ubicación" }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Tus ubicaciones")
                    .setItems(names) { _, which ->
                        val doc = locsSnap.documents[which]
                        createOrderFromChat(msg, doc.getDouble("lat") ?: 0.0, doc.getDouble("lng") ?: 0.0, names[which])
                    }
                    .show()
            } catch (e: Exception) {}
        }
    }

    private fun openMapToSelectLocation(msg: ChatMessage) {
        // Por ahora, usamos un selector de dirección simple o abrimos la actividad de mapa si existe
        val intent = Intent(this, BusinessMapSelectorActivity::class.java)
        // Necesitaríamos onActivityResult, pero vamos a simplificar por ahora con un Address Input
        val et = EditText(this).apply { hint = "Ingresa la dirección de entrega" }
        AlertDialog.Builder(this)
            .setTitle("Nueva ubicación")
            .setView(et)
            .setPositiveButton("Pedir") { _, _ ->
                val addr = et.text.toString().trim()
                if (addr.isNotEmpty()) createOrderFromChat(msg, 0.0, 0.0, addr)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createOrderFromChat(msg: ChatMessage, lat: Double, lng: Double, address: String) {
        val myUid = auth.currentUser?.uid ?: return
        val storeId = msg.senderId // El que envió el producto es la tienda
        
        lifecycleScope.launch {
            try {
                val myName = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_full_name", "Comprador") ?: "Comprador"
                val orderId = UUID.randomUUID().toString()
                
                val item = CartItem(
                    productId = msg.productId,
                    productName = msg.message,
                    price = msg.productPrice,
                    quantity = 1,
                    category = msg.productCategory
                )

                val order = OrderModel(
                    id = orderId,
                    buyerId = myUid,
                    buyerName = myName,
                    storeId = storeId,
                    items = listOf(item),
                    totalAmount = msg.productPrice,
                    status = "PENDING",
                    timestamp = FieldValue.serverTimestamp(),
                    deliveryAddress = address,
                    deliveryLat = lat,
                    deliveryLng = lng
                )

                db.collection("orders").document(orderId).set(order).await()
                
                // Avisar al vendedor en el chat
                val reply = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = myUid,
                    message = "¡He realizado un pedido de ${msg.message}! 🛵 Destino: $address",
                    timestamp = FieldValue.serverTimestamp(),
                    type = "ORDER_ALERT",
                    linkedOrderId = orderId,
                    deliveryLat = lat,
                    deliveryLng = lng,
                    deliveryAddress = address
                )
                db.collection(if (isDirectChat) "direct_chats" else "orders").document(this@ChatActivity.orderId).collection("messages").document(reply.id).set(reply)

                Toast.makeText(this@ChatActivity, "Pedido realizado con éxito ✅", Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Error al crear pedido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUserProfilePreviewDialog() {
        if (otherUserId.isEmpty()) return

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_user_profile_preview, null)
        dialog.setContentView(view)

        val cardPhoto = view.findViewById<View>(R.id.cardPreviewUserPhoto)
        val ivPhoto = view.findViewById<ImageView>(R.id.ivPreviewUserPhoto)
        val tvName = view.findViewById<TextView>(R.id.tvPreviewUserName)
        val tvRole = view.findViewById<TextView>(R.id.tvPreviewUserRole)
        val tvEmail = view.findViewById<TextView>(R.id.tvPreviewUserEmail)
        val tvPhone = view.findViewById<TextView>(R.id.tvPreviewUserPhone)
        val btnCall = view.findViewById<MaterialButton>(R.id.btnPreviewCallUser)
        val btnClose = view.findViewById<View>(R.id.btnPreviewClose)
        val rvPhotos = view.findViewById<RecyclerView>(R.id.rvSharedPhotos)
        val tvNoPhotos = view.findViewById<TextView>(R.id.tvNoSharedPhotos)
        val cardStorage = view.findViewById<View>(R.id.cardChatStorageInfo)
        val tvStorageUsed = view.findViewById<TextView>(R.id.tvStorageUsed)
        val btnDeleteChat = view.findViewById<MaterialButton>(R.id.btnDeleteChat)
        val btnBlockUser = view.findViewById<MaterialButton>(R.id.btnBlockUser)

        val currentPhoto = intent.getStringExtra("OTHER_USER_PHOTO") ?: ""
        val currentTitle = intent.getStringExtra("OTHER_USER_TITLE") ?: ""

        tvName.text = otherUserName

        val cardBizInfo = view.findViewById<View>(R.id.cardPreviewBusinessInfo)
        val tvBizName = view.findViewById<TextView>(R.id.tvPreviewBusinessName)

        val isStoreOwnerOrCollab = currentTitle.contains("Dueño", ignoreCase = true) ||
                currentTitle.contains("Colaborador", ignoreCase = true) ||
                currentTitle.contains("Gestionando", ignoreCase = true) ||
                currentTitle.contains("Comercial", ignoreCase = true)

        val openStoreClickListener = View.OnClickListener {
            dialog.dismiss()
            fetchAndOpenStoreProfile(otherUserId)
        }

        val cardBizLogo = view.findViewById<View>(R.id.cardPreviewBizLogo)
        val ivBizLogo = view.findViewById<ImageView>(R.id.ivPreviewBizLogo)
        val otherBizLogo = intent.getStringExtra("OTHER_USER_BIZ_LOGO") ?: ""

        if (otherBizLogo.isNotEmpty()) {
            cardBizLogo?.visibility = View.VISIBLE
            Glide.with(this).load(otherBizLogo)
                .apply(GlideHelper.getOptions(this))
                .circleCrop().into(ivBizLogo)

            cardBizLogo?.setOnClickListener(openStoreClickListener)
        } else {
            lifecycleScope.launch {
                try {
                    val bizDoc = db.collection("users").document(otherUserId)
                        .collection("settings").document("business").get().await()

                    val logoFromDb = bizDoc.getString("business_logo_url")
                    if (!logoFromDb.isNullOrEmpty()) {
                        cardBizLogo?.visibility = View.VISIBLE
                        Glide.with(this@ChatActivity).load(logoFromDb)
                            .apply(GlideHelper.getOptions(this@ChatActivity))
                            .circleCrop().into(ivBizLogo)

                        cardBizLogo?.setOnClickListener(openStoreClickListener)
                    } else {
                        cardBizLogo?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    cardBizLogo?.visibility = View.GONE
                }
            }
        }

        if (isStoreOwnerOrCollab) {
            tvRole.text = "$currentTitle 🏪 >"
            tvRole.isClickable = true
            tvRole.isFocusable = true
            
            cardBizInfo?.visibility = View.VISIBLE
            val extractedBizName = when {
                currentTitle.contains(" de ") -> currentTitle.substringAfter(" de ")
                currentTitle.contains(" en ") -> currentTitle.substringAfter(" en ")
                else -> "Ver catálogo de esta tienda 🏪 >"
            }
            tvBizName?.text = extractedBizName

            tvRole.setOnClickListener(openStoreClickListener)
            cardBizInfo?.setOnClickListener(openStoreClickListener)
        } else {
            tvRole.text = if (currentTitle.isNotEmpty()) currentTitle else "🛒 COMPRADOR"
            cardBizInfo?.visibility = View.GONE
        }

        if (currentPhoto.isNotEmpty()) {
            Glide.with(this).load(currentPhoto)
                .apply(GlideHelper.getOptions(this))
                .circleCrop().into(ivPhoto)

            cardPhoto.setOnClickListener {
                showFullScreenImageDialog(currentPhoto)
            }
        } else {
            ivPhoto.setImageResource(R.drawable.gomezmart_icon)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        cardStorage?.visibility = View.VISIBLE
        btnDeleteChat?.visibility = View.VISIBLE
        btnBlockUser?.visibility = View.VISIBLE

        // Calcular almacenamiento local ocupado
        lifecycleScope.launch(Dispatchers.IO) {
            var totalBytes = 0L
            val cacheDir = externalCacheDir ?: cacheDir
            cacheDir.walkTopDown().forEach { if (it.isFile) totalBytes += it.length() }
            val filesDir = getExternalFilesDir(null)
            filesDir?.walkTopDown()?.forEach { if (it.isFile && it.name.startsWith("chat_")) totalBytes += it.length() }

            val mb = totalBytes / (1024.0 * 1024.0)
            val text = if (mb < 0.1) String.format(Locale.US, "%.1f KB", totalBytes / 1024.0) else String.format(Locale.US, "%.1f MB", mb)
            withContext(Dispatchers.Main) {
                tvStorageUsed?.text = "$text ocupados en este dispositivo"
            }
        }

        btnDeleteChat?.setOnClickListener {
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("¿Eliminar todo el chat?")
                .setMessage("Esta acción eliminará de forma permanente los mensajes de este chat en la nube y en tu dispositivo.")
                .setPositiveButton("Eliminar Todo") { _, _ ->
                    dialog.dismiss()
                    executeChatDeletionProcess()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnBlockUser?.setOnClickListener {
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("¿Bloquear a $otherUserName?")
                .setMessage("No podrás recibir ni enviar mensajes a este usuario.")
                .setPositiveButton("Bloquear") { _, _ ->
                    dialog.dismiss()
                    executeBlockUserProcess()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        lifecycleScope.launch {
            try {
                val profileDoc = db.collection("users").document(otherUserId)
                    .collection("profile").document("info").get().await()

                val emailFromProfile = profileDoc.getString("email")
                val phoneFromProfile = profileDoc.getString("phone") ?: profileDoc.getString("phoneNumber")

                val mainDoc = db.collection("users").document(otherUserId).get().await()
                val emailFromMain = mainDoc.getString("email")
                val phoneFromMain = mainDoc.getString("phone") ?: mainDoc.getString("phoneNumber")

                val finalEmail = emailFromProfile ?: emailFromMain ?: "No especificado"
                val userPhone = phoneFromProfile ?: phoneFromMain ?: ""

                tvEmail.text = finalEmail
                tvPhone.text = if (userPhone.isNotEmpty()) userPhone else "No especificado"

                btnCall.isEnabled = userPhone.isNotEmpty()
                btnCall.setOnClickListener {
                    if (userPhone.isNotEmpty()) {
                        val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$userPhone"))
                        startActivity(callIntent)
                    } else {
                        Toast.makeText(this@ChatActivity, "El usuario no tiene teléfono registrado", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                tvEmail.text = "No disponible"
                tvPhone.text = "No disponible"
            }
        }

        val collection = if (isDirectChat) "direct_chats" else "orders"
        db.collection(collection).document(orderId).collection("messages")
            .whereEqualTo("type", "IMAGE")
            .get()
            .addOnSuccessListener { snap ->
                val photoUrls = snap.documents.mapNotNull { it.getString("imageUrl") }.filter { it.isNotEmpty() }
                if (photoUrls.isNotEmpty()) {
                    tvNoPhotos.visibility = View.GONE
                    rvPhotos.visibility = View.VISIBLE
                    rvPhotos.adapter = SharedPhotosAdapter(photoUrls) { photoUrl ->
                        showFullScreenImageDialog(photoUrl)
                    }
                } else {
                    tvNoPhotos.visibility = View.VISIBLE
                    rvPhotos.visibility = View.GONE
                }
            }

        dialog.show()
    }

    private fun fetchAndOpenStoreProfile(targetUserId: String) {
        if (targetUserId.isEmpty()) return
        
        lifecycleScope.launch {
            try {
                val bizDoc = db.collection("users").document(targetUserId)
                    .collection("settings").document("business").get().await()

                val rubroDoc = db.collection("users").document(targetUserId)
                    .collection("settings").document("info").get().await()

                val mainDoc = if (bizDoc.exists()) bizDoc else rubroDoc

                if (mainDoc.exists() && mainDoc.getString("business_name") != null) {
                    val storeName = mainDoc.getString("business_name") ?: "Tienda"
                    val storeType = mainDoc.getString("business_type") ?: "PRODUCTS"
                    
                    val targetClass = if (storeType == "LOANS") LoanStoreProfileActivity::class.java else StoreProfileActivity::class.java
                    val intent = Intent(this@ChatActivity, targetClass).apply {
                        putExtra("STORE_ID", targetUserId)
                        putExtra("STORE_NAME", storeName)
                        putExtra("FORCE_RUBRO", storeType)
                    }
                    startActivityWithFade(intent)
                    return@launch
                }

                val userEmail = intent.getStringExtra("OTHER_USER_EMAIL") ?: ""
                if (userEmail.isNotEmpty() && userEmail.contains("@")) {
                    val keysSnap = db.collection("access_keys")
                        .whereEqualTo("email", userEmail.lowercase())
                        .get().await()

                    if (!keysSnap.isEmpty) {
                        val keyDoc = keysSnap.documents[0]
                        val storeOwnerId = keyDoc.getString("ownerUid") ?: keyDoc.getString("businessId") ?: ""
                        val storeName = keyDoc.getString("businessName") ?: "Tienda"

                        if (storeOwnerId.isNotEmpty()) {
                            val intent = Intent(this@ChatActivity, StoreProfileActivity::class.java).apply {
                                putExtra("STORE_ID", storeOwnerId)
                                putExtra("STORE_NAME", storeName)
                            }
                            startActivityWithFade(intent)
                            return@launch
                        }
                    }
                }

                val intent = Intent(this@ChatActivity, StoreProfileActivity::class.java).apply {
                    putExtra("STORE_ID", targetUserId)
                    putExtra("STORE_NAME", otherUserName)
                }
                startActivityWithFade(intent)

            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "No se pudo cargar la tienda", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeChatDeletionProcess() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Eliminando chat... ⏳")
            .setMessage("Borrando mensajes del dispositivo y de la nube. Por favor espera.")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            try {
                val collection = if (isDirectChat) "direct_chats" else "orders"
                val messagesRef = db.collection(collection).document(orderId).collection("messages")
                
                val snapshot = messagesRef.get().await()
                val batch = db.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()

                val chatDocRef = db.collection(collection).document(orderId)
                db.runTransaction { transaction ->
                    if (isDirectChat) {
                        transaction.update(chatDocRef, "lastMessage", "Chat eliminado", "lastMessageTime", FieldValue.serverTimestamp())
                    } else {
                        transaction.update(chatDocRef, "lastMessage", "Historial eliminado", "lastMessageTime", FieldValue.serverTimestamp())
                    }
                }.await()

                withContext(Dispatchers.IO) {
                    val filesDir = getExternalFilesDir(null)
                    filesDir?.listFiles()?.forEach {
                        if (it.name.startsWith("chat_")) {
                            it.delete()
                        }
                    }
                }

                lastClearedAt = System.currentTimeMillis()
                refreshMessagesWithCurrentFilter()

                progressDialog.dismiss()
                Toast.makeText(this@ChatActivity, "Chat eliminado con éxito 🗑️", Toast.LENGTH_SHORT).show()
                finish()

            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@ChatActivity, "Error al eliminar el chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeBlockUserProcess() {
        val myUid = auth.currentUser?.uid ?: return
        if (otherUserId.isEmpty()) return

        lifecycleScope.launch {
            try {
                db.collection("users").document(myUid)
                    .collection("blocked_users").document(otherUserId)
                    .set(mapOf(
                        "blockedAt" to FieldValue.serverTimestamp(),
                        "userName" to otherUserName
                    )).await()

                Toast.makeText(this@ChatActivity, "Usuario $otherUserName bloqueado 🚫", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Error al bloquear usuario", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFullScreenImageDialog(imageUrl: String) {
        if (imageUrl.isEmpty()) return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.dialog_full_screen_image, null)
        dialog.setContentView(view)

        val ivFull = view.findViewById<ImageView>(R.id.ivFullScreenPhoto)
        val pbFull = view.findViewById<ProgressBar>(R.id.pbFullScreenPhoto)
        val btnClose = view.findViewById<View>(R.id.btnCloseFullScreenPhoto)

        Glide.with(this)
            .load(imageUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    pbFull.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    pbFull.visibility = View.GONE
                    return false
                }
            })
            .into(ivFull)

        btnClose.setOnClickListener { dialog.dismiss() }
        view.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    inner class SharedPhotosAdapter(
        private val urls: List<String>,
        private val onPhotoClick: (String) -> Unit
    ) : RecyclerView.Adapter<SharedPhotosAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iv: ImageView = view.findViewById(R.id.ivChatMessageImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_chat_image, parent, false)
            v.layoutParams = ViewGroup.LayoutParams(
                (100 * resources.displayMetrics.density).toInt(),
                (100 * resources.displayMetrics.density).toInt()
            )
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = urls[position]
            Glide.with(holder.itemView.context)
                .load(url)
                .apply(GlideHelper.getOptions(holder.itemView.context))
                .circleCrop()
                .into(holder.iv)

            holder.itemView.setOnClickListener { onPhotoClick(url) }
        }

        override fun getItemCount() = urls.size
    }
}
