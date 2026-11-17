package com.gomezmart.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.databinding.ActivitySearchUsersBinding
import com.gomezmart.app.databinding.ItemUserSearchResultBinding
import com.gomezmart.app.databinding.DialogUserProfilePreviewBinding
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.gomezmart.app.data.FriendRelationStatus
import com.gomezmart.app.data.FriendRequest
import com.gomezmart.app.data.MessagePrivacy
import com.gomezmart.app.data.SocialRepository
import com.gomezmart.app.util.GlideHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SearchUsersActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchUsersBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: UserSearchAdapter

    // Almacenamiento unificado de usuarios (UID -> Datos)
    private val allUsersMap = mutableMapOf<String, UserSearchResult>()
    private val blockedUids = mutableSetOf<String>()
    private val friendUids = mutableSetOf<String>()
    private val activeChatUids = mutableSetOf<String>()
    private val incomingRequestsMap = mutableMapOf<String, FriendRequest>()
    private val outgoingRequestsMap = mutableMapOf<String, FriendRequest>()
    private var currentFilter = UserFilter.ALL

    private var profileRegistration: ListenerRegistration? = null
    private var chatRegistration: ListenerRegistration? = null
    private var usersRegistration: ListenerRegistration? = null
    private var blockedRegistration: ListenerRegistration? = null
    private var friendsRegistration: ListenerRegistration? = null
    private var incomingReqRegistration: ListenerRegistration? = null
    private var outgoingReqRegistration: ListenerRegistration? = null

    private enum class UserFilter { ALL, FRIENDS, REQUESTS, BUSINESSES, BUYERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para la cabecera y la lista inferior
        applyWindowInsets(binding.headerSearchUsers, applyTop = true, applyBottom = false)
        applyWindowInsetsHeight(binding.topHeaderBackgroundSearch, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.rvUserSearchResults, applyTop = false, applyBottom = true)

        setupRecyclerView()
        setupListeners()

        when (intent.getStringExtra("INITIAL_FILTER")) {
            "FRIENDS" -> binding.chipFilterFriends.isChecked = true
            "REQUESTS" -> binding.chipFilterRequests.isChecked = true
        }
    }

    private fun setupRecyclerView() {
        adapter = UserSearchAdapter(
            onClick = { user -> showUserProfilePreview(user) },
            onQuickAction = { user -> handleQuickRowAction(user) },
            onAcceptRequest = { user -> acceptFriendRequest(user) },
            onRejectRequest = { user -> rejectFriendRequest(user) }
        )
        binding.rvUserSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvUserSearchResults.adapter = adapter
    }

    private fun showUserProfilePreview(user: UserSearchResult) {
        if (requireLoginForGuest("ver los datos de los usuarios")) return
        val dialogView = DialogUserProfilePreviewBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog)
            .setView(dialogView.root as View)
            .create()
        
        dialogView.tvPreviewUserName.text = user.name
        
        val title = user.businessTitle ?: ""
        val isOwnerOrCollab = title.contains("Dueño", ignoreCase = true) || title.contains("Colaborador", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true)
        
        if (isOwnerOrCollab) {
            dialogView.tvPreviewUserRole.text = "$title 🏪 >"
            dialogView.tvPreviewUserRole.isClickable = true
            dialogView.tvPreviewUserRole.isFocusable = true
        } else {
            dialogView.tvPreviewUserRole.text = if (title.isNotEmpty()) title else "🛒 COMPRADOR"
        }

        val color = when {
            title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> Color.parseColor("#B45309")
            title.contains("Colaborador", ignoreCase = true) -> Color.parseColor("#6D28D9")
            title.contains("Comprador", ignoreCase = true) -> Color.parseColor("#047857")
            else -> Color.parseColor("#475569")
        }
        dialogView.tvPreviewUserRole.setTextColor(color)
        dialogView.tvPreviewUserRole.setBackgroundResource(
            when {
                title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> R.drawable.bg_pill_amber_light
                title.contains("Colaborador", ignoreCase = true) -> R.drawable.bg_pill_purple_light
                else -> R.drawable.bg_pill_emerald_light
            }
        )

        // Foto de perfil
        if (!user.photoUrl.isNullOrEmpty()) {
            dialogView.pbPreviewUserPhotoLoading.visibility = View.VISIBLE
            Glide.with(this).load(user.photoUrl)
                .apply(GlideHelper.getOptions(this))
                .circleCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        dialogView.pbPreviewUserPhotoLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        dialogView.pbPreviewUserPhotoLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(dialogView.ivPreviewUserPhoto)

            dialogView.cardPreviewUserPhoto.setOnClickListener {
                showFullScreenImageDialog(user.photoUrl)
            }
        } else {
            dialogView.pbPreviewUserPhotoLoading.visibility = View.GONE
            dialogView.ivPreviewUserPhoto.setImageResource(R.drawable.gomezmart_icon)
        }

        // Lógica de Negocio si existe
        val openStoreListener = View.OnClickListener {
            dialog.dismiss()
            val intent = Intent(this, StoreProfileActivity::class.java).apply {
                putExtra("STORE_ID", user.uid)
                putExtra("STORE_NAME", user.name)
            }
            startActivityWithFade(intent)
        }

        if (isOwnerOrCollab) {
            dialogView.tvPreviewUserRole.setOnClickListener(openStoreListener)
            
            if (!user.businessLogoUrl.isNullOrEmpty()) {
                dialogView.cardPreviewBizLogo.visibility = View.VISIBLE
                Glide.with(this).load(user.businessLogoUrl)
                    .apply(GlideHelper.getOptions(this))
                    .circleCrop()
                    .into(dialogView.ivPreviewBizLogo)
                dialogView.cardPreviewBizLogo.setOnClickListener(openStoreListener)
            } else {
                dialogView.cardPreviewBizLogo.visibility = View.GONE
            }

            dialogView.cardPreviewBusinessInfo.visibility = View.VISIBLE
            val bizName = when {
                title.contains(" de ") -> title.substringAfter(" de ")
                title.contains(" en ") -> title.substringAfter(" en ")
                else -> "Ver tienda de este usuario 🏪 >"
            }
            dialogView.tvPreviewBusinessName.text = bizName
            dialogView.cardPreviewBusinessInfo.setOnClickListener(openStoreListener)
        } else {
            dialogView.cardPreviewBizLogo.visibility = View.GONE
            dialogView.cardPreviewBusinessInfo.visibility = View.GONE
        }

        // Privacidad por capas: solo negocios o amigos con permiso muestran email/teléfono
        val isFriend = friendUids.contains(user.uid)
        val canViewContact = isOwnerOrCollab || (isFriend && user.shareContactWithFriends)

        if (canViewContact) {
            var userPhone = user.phone ?: ""
            val userEmail = user.email ?: ""

            if (userPhone.isNotEmpty()) {
                dialogView.tvPreviewUserPhone.text = userPhone
                dialogView.btnPreviewCallUser.visibility = View.VISIBLE
                dialogView.btnPreviewCallUser.isEnabled = true
            } else {
                dialogView.tvPreviewUserPhone.text = "Cargando..."
                dialogView.btnPreviewCallUser.visibility = View.VISIBLE
                dialogView.btnPreviewCallUser.isEnabled = false
            }

            dialogView.tvPreviewUserEmail.text = if (userEmail.isNotEmpty()) userEmail else "Cargando..."

            dialogView.btnPreviewCallUser.setOnClickListener {
                if (userPhone.isNotEmpty()) {
                    val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$userPhone"))
                    startActivity(callIntent)
                } else {
                    Toast.makeText(this@SearchUsersActivity, "No tiene teléfono registrado", Toast.LENGTH_SHORT).show()
                }
            }

            // Carga asíncrona desde Firestore si faltaban datos de contacto comercial/amigo
            lifecycleScope.launch {
                try {
                    val profileDoc = db.collection("users").document(user.uid)
                        .collection("profile").document("info").get().await()

                    val emailFromProfile = profileDoc.getString("email")
                    val phoneFromProfile = profileDoc.getString("phone") ?: profileDoc.getString("phoneNumber") ?: profileDoc.getString("user_phone")

                    val mainDoc = db.collection("users").document(user.uid).get().await()
                    val emailFromMain = mainDoc.getString("email")
                    val phoneFromMain = mainDoc.getString("phone") ?: mainDoc.getString("phoneNumber") ?: mainDoc.getString("user_phone")

                    val finalEmail = emailFromProfile ?: emailFromMain ?: userEmail.ifEmpty { "No especificado" }
                    userPhone = phoneFromProfile ?: phoneFromMain ?: userPhone

                    dialogView.tvPreviewUserEmail.text = if (finalEmail.isNotEmpty()) finalEmail else "No especificado"
                    dialogView.tvPreviewUserPhone.text = if (userPhone.isNotEmpty()) userPhone else "No especificado"

                    dialogView.btnPreviewCallUser.isEnabled = userPhone.isNotEmpty()
                } catch (e: Exception) {
                    if (userEmail.isEmpty()) dialogView.tvPreviewUserEmail.text = "No disponible"
                    if (userPhone.isEmpty()) dialogView.tvPreviewUserPhone.text = "No disponible"
                }
            }
        } else {
            dialogView.tvPreviewUserEmail.text = "🔒 Privado (solo amigos)"
            dialogView.tvPreviewUserPhone.text = "🔒 Privado (solo amigos)"
            dialogView.btnPreviewCallUser.visibility = View.GONE
        }

        // Ocultar elementos de chat en la búsqueda de usuarios
        dialogView.cardChatStorageInfo.visibility = View.GONE
        dialogView.layoutSharedPhotosSection.visibility = View.GONE
        dialogView.btnDeleteChat.visibility = View.GONE
        dialogView.btnBlockUser.visibility = View.GONE

        // Estado de amistad y acciones sociales
        val relation = getRelationStatus(user.uid)
        val incomingReq = incomingRequestsMap[user.uid]

        if (relation == FriendRelationStatus.REQUEST_RECEIVED && incomingReq != null && incomingReq.note.isNotBlank()) {
            dialogView.tvPreviewFriendNote.visibility = View.VISIBLE
            dialogView.tvPreviewFriendNote.text = "💬 \"${incomingReq.note}\""
        } else {
            dialogView.tvPreviewFriendNote.visibility = View.GONE
        }

        dialogView.btnPreviewFriendAction.visibility = View.VISIBLE
        when (relation) {
            FriendRelationStatus.NONE -> {
                dialogView.btnPreviewFriendAction.text = "🤝 Enviar Solicitud de Amistad"
                dialogView.btnPreviewFriendSecondary.visibility = View.GONE
                dialogView.btnPreviewFriendAction.setOnClickListener {
                    dialog.dismiss()
                    promptSendFriendRequest(user)
                }
            }
            FriendRelationStatus.REQUEST_SENT -> {
                dialogView.btnPreviewFriendAction.text = "⏳ Solicitud Enviada (Cancelar)"
                dialogView.btnPreviewFriendSecondary.visibility = View.GONE
                dialogView.btnPreviewFriendAction.setOnClickListener {
                    dialog.dismiss()
                    cancelFriendRequest(user)
                }
            }
            FriendRelationStatus.REQUEST_RECEIVED -> {
                dialogView.btnPreviewFriendAction.text = "✅ Aceptar Solicitud de Amistad"
                dialogView.btnPreviewFriendSecondary.visibility = View.VISIBLE
                dialogView.btnPreviewFriendSecondary.text = "Rechazar solicitud"
                dialogView.btnPreviewFriendAction.setOnClickListener {
                    dialog.dismiss()
                    acceptFriendRequest(user)
                }
                dialogView.btnPreviewFriendSecondary.setOnClickListener {
                    dialog.dismiss()
                    rejectFriendRequest(user)
                }
            }
            FriendRelationStatus.FRIENDS -> {
                dialogView.btnPreviewFriendAction.text = "🤝 Son Amigos (Eliminar amistad)"
                dialogView.btnPreviewFriendSecondary.visibility = View.GONE
                dialogView.btnPreviewFriendAction.setOnClickListener {
                    dialog.dismiss()
                    confirmRemoveFriend(user)
                }
            }
        }

        // Respetar preferencia de quién puede enviar mensajes
        val canMessageDirectly = isFriend || isOwnerOrCollab
        when {
            canMessageDirectly -> {
                dialogView.tvPreviewMessagePrivacyNotice.visibility = View.GONE
                dialogView.btnPreviewSendMessage.visibility = View.VISIBLE
                dialogView.btnPreviewSendMessage.text = "Enviar Mensaje 💬"
                dialogView.btnPreviewSendMessage.setOnClickListener {
                    dialog.dismiss()
                    openChatWithUser(user, isMessageRequest = false)
                }
            }
            user.messagePrivacy == MessagePrivacy.NOBODY -> {
                dialogView.btnPreviewSendMessage.visibility = View.GONE
                dialogView.tvPreviewMessagePrivacyNotice.visibility = View.VISIBLE
                dialogView.tvPreviewMessagePrivacyNotice.text = "🔒 Este usuario no acepta mensajes nuevos"
            }
            user.messagePrivacy == MessagePrivacy.FRIENDS_ONLY -> {
                dialogView.btnPreviewSendMessage.visibility = View.GONE
                dialogView.tvPreviewMessagePrivacyNotice.visibility = View.VISIBLE
                dialogView.tvPreviewMessagePrivacyNotice.text = "🔒 Solo acepta mensajes de amigos"
            }
            else -> {
                dialogView.tvPreviewMessagePrivacyNotice.visibility = View.VISIBLE
                dialogView.tvPreviewMessagePrivacyNotice.text = "Se enviará como solicitud de mensaje"
                dialogView.btnPreviewSendMessage.visibility = View.VISIBLE
                dialogView.btnPreviewSendMessage.text = "Enviar Solicitud de Mensaje 💬"
                dialogView.btnPreviewSendMessage.setOnClickListener {
                    dialog.dismiss()
                    openChatWithUser(user, isMessageRequest = true)
                }
            }
        }

        dialogView.btnPreviewClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.let { window ->
            window.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun showFullScreenImageDialog(imageUrl: String?) {
        if (imageUrl.isNullOrEmpty()) return
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

    private fun setupListeners() {
        binding.btnBackSearchUsers.setOnClickListener { finish() }
        binding.btnFriendQrCode.setOnClickListener { showFriendQrOptionsDialog() }

        binding.swipeRefreshUsers.setOnRefreshListener {
            startRealtimeListeners()
        }

        binding.btnClearSearchUser.setOnClickListener {
            binding.etSearchUserAdd.setText("")
        }

        binding.etSearchUserAdd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearSearchUser.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refreshListView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupUserFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterFriends -> UserFilter.FRIENDS
                R.id.chipFilterRequests -> UserFilter.REQUESTS
                R.id.chipFilterBusinesses -> UserFilter.BUSINESSES
                R.id.chipFilterBuyers -> UserFilter.BUYERS
                else -> UserFilter.ALL
            }
            refreshListView()
        }
    }

    override fun onResume() {
        super.onResume()
        startRealtimeListeners()
    }

    private fun startRealtimeListeners() {
        binding.pbLoadingSearch.visibility = View.VISIBLE

        profileRegistration?.remove()
        chatRegistration?.remove()
        usersRegistration?.remove()
        blockedRegistration?.remove()
        friendsRegistration?.remove()
        incomingReqRegistration?.remove()
        outgoingReqRegistration?.remove()
        allUsersMap.clear()
        blockedUids.clear()
        friendUids.clear()
        activeChatUids.clear()
        incomingRequestsMap.clear()
        outgoingRequestsMap.clear()

        val myUid = auth.currentUser?.uid ?: ""

        // Timeout de seguridad para ocultar el cargador en 2.5 segundos como máximo
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2500)
            binding.pbLoadingSearch.visibility = View.GONE
            binding.swipeRefreshUsers.isRefreshing = false
        }

        // 0. Escuchar usuarios bloqueados, amigos y solicitudes de amistad
        if (myUid.isNotEmpty()) {
            blockedRegistration = db.collection("users").document(myUid)
                .collection("blocked_users")
                .addSnapshotListener { snapshot, _ ->
                    blockedUids.clear()
                    snapshot?.documents?.forEach { blockedUids.add(it.id) }
                    refreshListView()
                }

            friendsRegistration = db.collection("users").document(myUid)
                .collection("friends")
                .addSnapshotListener { snapshot, _ ->
                    friendUids.clear()
                    snapshot?.documents?.forEach { doc ->
                        val fUid = doc.id
                        friendUids.add(fUid)
                        if (!allUsersMap.containsKey(fUid)) {
                            val name = doc.getString("name") ?: "Amigo"
                            deduplicateAndAdd(
                                UserSearchResult(
                                    uid = fUid,
                                    name = name,
                                    photoUrl = doc.getString("photoUrl"),
                                    businessTitle = doc.getString("businessTitle") ?: "Comprador",
                                    businessLogoUrl = null
                                )
                            )
                        }
                    }
                    refreshListView()
                }

            incomingReqRegistration = db.collection("friend_requests")
                .whereEqualTo("toUid", myUid)
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snapshot, _ ->
                    incomingRequestsMap.clear()
                    snapshot?.documents?.forEach { doc ->
                        val req = doc.toObject(FriendRequest::class.java)?.copy(id = doc.id) ?: return@forEach
                        if (req.fromUid.isNotBlank()) {
                            incomingRequestsMap[req.fromUid] = req
                            if (!allUsersMap.containsKey(req.fromUid) && req.fromName.isNotBlank()) {
                                deduplicateAndAdd(
                                    UserSearchResult(
                                        uid = req.fromUid,
                                        name = req.fromName,
                                        photoUrl = req.fromPhoto,
                                        businessTitle = req.fromTitle ?: "Comprador",
                                        businessLogoUrl = null
                                    )
                                )
                            }
                        }
                    }
                    refreshListView()
                }

            outgoingReqRegistration = db.collection("friend_requests")
                .whereEqualTo("fromUid", myUid)
                .addSnapshotListener { snapshot, _ ->
                    outgoingRequestsMap.clear()
                    snapshot?.documents?.forEach { doc ->
                        val req = doc.toObject(FriendRequest::class.java)?.copy(id = doc.id) ?: return@forEach
                        if (req.toUid.isBlank()) return@forEach
                        if (req.status == "PENDING") {
                            outgoingRequestsMap[req.toUid] = req
                        } else if (req.status == "ACCEPTED" && !friendUids.contains(req.toUid)) {
                            // Sincronizar en mi lista de amigos si el otro usuario aceptó
                            val myFriendRef = db.collection("users").document(myUid)
                                .collection("friends").document(req.toUid)
                            myFriendRef.set(
                                mapOf(
                                    "uid" to req.toUid,
                                    "name" to req.toName,
                                    "photoUrl" to (req.toPhoto ?: ""),
                                    "businessTitle" to (req.toTitle ?: "Comprador"),
                                    "since" to System.currentTimeMillis()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                        }
                    }
                    refreshListView()
                }
        }

        // 1. Escuchar TODOS los perfiles registrados (Buscador Global)
        profileRegistration = db.collectionGroup("profile").addSnapshotListener { snapshot, e ->
            binding.pbLoadingSearch.visibility = View.GONE
            binding.swipeRefreshUsers.isRefreshing = false

            if (e != null) {
                Log.e("SearchUsers", "Error en perfiles: ${e.message}")
                return@addSnapshotListener
            }

            snapshot?.documents?.forEach { doc ->
                val rawName = doc.getString("name") ?: doc.getString("fullName") ?: ""
                val photo = doc.getString("photoUrl") ?: doc.getString("profilePhoto") ?: doc.getString("user_photo_url") ?: ""
                val email = doc.getString("email") ?: doc.getString("user_email")
                val phone = doc.getString("phone") ?: doc.getString("user_phone")
                val allowDirectorySearch = doc.getBoolean("allowDirectorySearch") ?: true
                val shareContactWithFriends = doc.getBoolean("shareContactWithFriends") ?: false
                val msgPrivacy = MessagePrivacy.fromValue(doc.getString("messagePrivacy"))

                val pathParts = doc.reference.path.split("/")
                if (pathParts.size >= 2) {
                    val uid = pathParts[1]
                    if (myUid.isNotEmpty() && uid == myUid) return@forEach

                    val name = if (rawName.isNotBlank()) rawName else (email?.substringBefore("@") ?: "")
                    if (name.isBlank()) return@forEach

                    val user = UserSearchResult(
                        uid = uid,
                        name = name,
                        photoUrl = photo,
                        businessTitle = doc.getString("businessTitle") ?: "Comprador",
                        businessLogoUrl = doc.getString("businessLogoUrl") ?: doc.getString("logoUrl"),
                        email = email,
                        phone = phone,
                        allowDirectorySearch = allowDirectorySearch,
                        shareContactWithFriends = shareContactWithFriends,
                        messagePrivacy = msgPrivacy
                    )

                    deduplicateAndAdd(user)
                }
            }
            refreshListView()
        }

        // 2. Escuchar MIS CHATS (Fallback para asegurar que salgan contactos con chat activo)
        if (myUid.isNotEmpty()) {
            chatRegistration = db.collection("direct_chats")
                .whereArrayContains("participants", myUid)
                .addSnapshotListener { snapshot, e ->
                    binding.pbLoadingSearch.visibility = View.GONE
                    binding.swipeRefreshUsers.isRefreshing = false
                    if (e != null) return@addSnapshotListener

                    snapshot?.documents?.forEach { doc ->
                        val participants = doc.get("participants") as? List<String> ?: return@forEach
                        val otherUid = participants.find { it != myUid } ?: return@forEach
                        activeChatUids.add(otherUid)

                        val names = doc.get("participantNames") as? Map<String, String>
                        val photos = doc.get("participantPhotos") as? Map<String, String>
                        val titles = doc.get("participantTitles") as? Map<String, String>
                        val emails = doc.get("participantEmails") as? Map<String, String>
                        val phones = doc.get("participantPhones") as? Map<String, String>

                        val rawName = names?.get(otherUid) ?: ""
                        val phone = phones?.get(otherUid) ?: ""
                        val email = emails?.get(otherUid)
                        val name = if (rawName.isNotBlank()) rawName else (email?.substringBefore("@") ?: "")
                        if (name.isBlank()) return@forEach

                        val user = UserSearchResult(
                            uid = otherUid,
                            name = name,
                            photoUrl = photos?.get(otherUid),
                            businessTitle = titles?.get(otherUid) ?: "Comprador",
                            businessLogoUrl = null,
                            email = email,
                            phone = phone
                        )

                        deduplicateAndAdd(user)
                    }
                    refreshListView()
                }
        }

        // 3. Escuchar la colección raíz de USUARIOS (Asegura cobertura total)
        usersRegistration = db.collection("users").addSnapshotListener { snapshot, e ->
            binding.pbLoadingSearch.visibility = View.GONE
            binding.swipeRefreshUsers.isRefreshing = false
            if (e != null || snapshot == null) return@addSnapshotListener
            snapshot.documents.forEach { doc ->
                val uid = doc.id
                if (myUid.isNotEmpty() && uid == myUid) return@forEach

                val rawName = doc.getString("name") ?: doc.getString("fullName") ?: doc.getString("displayName") ?: doc.getString("user_full_name") ?: ""
                val photo = doc.getString("photoUrl") ?: doc.getString("profilePhoto") ?: doc.getString("user_photo_url") ?: doc.getString("photo")
                val email = doc.getString("email") ?: doc.getString("user_email")
                val phone = doc.getString("phone") ?: doc.getString("user_phone")
                val allowDirectorySearch = doc.getBoolean("allowDirectorySearch") ?: true
                val shareContactWithFriends = doc.getBoolean("shareContactWithFriends") ?: false
                val msgPrivacy = MessagePrivacy.fromValue(doc.getString("messagePrivacy"))

                val name = if (rawName.isNotBlank()) rawName else (email?.substringBefore("@") ?: "")
                if (name.isBlank()) return@forEach

                val user = UserSearchResult(
                    uid = uid,
                    name = name,
                    photoUrl = photo,
                    businessTitle = "Comprador",
                    businessLogoUrl = null,
                    email = email,
                    phone = phone,
                    allowDirectorySearch = allowDirectorySearch,
                    shareContactWithFriends = shareContactWithFriends,
                    messagePrivacy = msgPrivacy
                )
                deduplicateAndAdd(user)
            }
            refreshListView()
        }
    }

    /**
     * Lógica centralizada para evitar duplicados por UID, Email o Teléfono, conservando los datos más completos.
     */
    private fun deduplicateAndAdd(user: UserSearchResult) {
        if (user.uid.isEmpty()) return

        val existingByUid = allUsersMap[user.uid]
        val existingByEmail = if (!user.email.isNullOrBlank()) {
            allUsersMap.values.find { !it.email.isNullOrBlank() && it.email.equals(user.email, ignoreCase = true) }
        } else null
        val existingByPhone = if (!user.phone.isNullOrBlank()) {
            allUsersMap.values.find { !it.phone.isNullOrBlank() && it.phone == user.phone }
        } else null

        val finalExisting = existingByUid ?: existingByEmail ?: existingByPhone

        if (finalExisting == null) {
            allUsersMap[user.uid] = user
        } else {
            val mergedName = when {
                user.name.contains(" ") && !finalExisting.name.contains(" ") -> user.name
                finalExisting.name.contains("@") && !user.name.contains("@") -> user.name
                user.name.length > finalExisting.name.length -> user.name
                else -> finalExisting.name
            }
            val mergedPrivacy = if (user.messagePrivacy != MessagePrivacy.EVERYONE_WITH_REQUEST) {
                user.messagePrivacy
            } else {
                finalExisting.messagePrivacy
            }
            val updated = finalExisting.copy(
                name = mergedName,
                photoUrl = if (!user.photoUrl.isNullOrEmpty()) user.photoUrl else finalExisting.photoUrl,
                email = if (!user.email.isNullOrEmpty()) user.email else finalExisting.email,
                phone = if (!user.phone.isNullOrEmpty()) user.phone else finalExisting.phone,
                businessTitle = if (!user.businessTitle.isNullOrEmpty() && user.businessTitle != "Comprador") user.businessTitle else finalExisting.businessTitle,
                businessLogoUrl = if (!user.businessLogoUrl.isNullOrEmpty()) user.businessLogoUrl else finalExisting.businessLogoUrl,
                allowDirectorySearch = finalExisting.allowDirectorySearch && user.allowDirectorySearch,
                shareContactWithFriends = finalExisting.shareContactWithFriends || user.shareContactWithFriends,
                messagePrivacy = mergedPrivacy
            )
            allUsersMap[finalExisting.uid] = updated
        }
    }

    private fun getRelationStatus(uid: String): FriendRelationStatus {
        return when {
            friendUids.contains(uid) -> FriendRelationStatus.FRIENDS
            incomingRequestsMap.containsKey(uid) -> FriendRelationStatus.REQUEST_RECEIVED
            outgoingRequestsMap.containsKey(uid) -> FriendRelationStatus.REQUEST_SENT
            else -> FriendRelationStatus.NONE
        }
    }

    private fun handleQuickRowAction(user: UserSearchResult) {
        if (requireLoginForGuest("conectar con usuarios")) return
        when (getRelationStatus(user.uid)) {
            FriendRelationStatus.FRIENDS -> showUserProfilePreview(user)
            FriendRelationStatus.REQUEST_SENT -> cancelFriendRequest(user)
            FriendRelationStatus.REQUEST_RECEIVED -> acceptFriendRequest(user)
            FriendRelationStatus.NONE -> {
                if (isBusinessUser(user)) {
                    showUserProfilePreview(user)
                } else {
                    promptSendFriendRequest(user)
                }
            }
        }
    }

    private fun getMySenderProfile(): Triple<String, String?, String?> {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val myUid = auth.currentUser?.uid ?: ""
        val rawName = appPrefs.getString("user_full_name", auth.currentUser?.displayName)
        val myName = com.gomezmart.app.util.UserNameGenerator.getOrGenerateName(this, rawName, myUid)
        val myPhoto = appPrefs.getString("user_photo_url", auth.currentUser?.photoUrl?.toString())
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bizName = bizPrefs.getString("business_name", "")
        val myTitle = if (!bizName.isNullOrBlank()) "Dueño de $bizName" else "Comprador"
        return Triple(myName, myPhoto, myTitle)
    }

    private fun promptSendFriendRequest(user: UserSearchResult) {
        if (requireLoginForGuest("enviar solicitudes de amistad")) return
        val input = android.widget.EditText(this).apply {
            hint = "Mensaje opcional (ej. ¡Hola, conectemos!)"
            maxLines = 2
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Conectar con ${user.name} 🤝")
            .setMessage("Al ser amigos podrán enviarse mensajes directos y compartir recomendaciones en GoMezMart.")
            .setView(container)
            .setPositiveButton("Enviar Solicitud") { _, _ ->
                val myUid = auth.currentUser?.uid ?: return@setPositiveButton
                val (myName, myPhoto, myTitle) = getMySenderProfile()
                val note = input.text.toString().trim()
                lifecycleScope.launch {
                    val result = SocialRepository.sendFriendRequest(
                        db = db,
                        myUid = myUid,
                        myName = myName,
                        myPhoto = myPhoto,
                        myTitle = myTitle,
                        targetUid = user.uid,
                        targetName = user.name,
                        targetPhoto = user.photoUrl,
                        targetTitle = user.businessTitle,
                        note = note
                    )
                    result.onSuccess { status ->
                        val msg = if (status == FriendRelationStatus.FRIENDS) {
                            "¡Ahora tú y ${user.name} son amigos! 🤝✨"
                        } else {
                            "Solicitud de amistad enviada a ${user.name} 📩"
                        }
                        Toast.makeText(this@SearchUsersActivity, msg, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this@SearchUsersActivity, "Error al enviar solicitud: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cancelFriendRequest(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        AlertDialog.Builder(this)
            .setTitle("Cancelar solicitud")
            .setMessage("¿Deseas cancelar la solicitud enviada a ${user.name}?")
            .setPositiveButton("Sí, cancelar") { _, _ ->
                lifecycleScope.launch {
                    SocialRepository.cancelFriendRequest(db, myUid, user.uid)
                        .onSuccess {
                            Toast.makeText(this@SearchUsersActivity, "Solicitud cancelada", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(this@SearchUsersActivity, "No se pudo cancelar", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun acceptFriendRequest(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        val (myName, myPhoto, myTitle) = getMySenderProfile()
        lifecycleScope.launch {
            SocialRepository.acceptFriendRequest(
                db = db,
                myUid = myUid,
                myName = myName,
                myPhoto = myPhoto,
                myTitle = myTitle,
                otherUid = user.uid,
                otherName = user.name,
                otherPhoto = user.photoUrl,
                otherTitle = user.businessTitle
            ).onSuccess {
                Toast.makeText(this@SearchUsersActivity, "¡Ahora son amigos con ${user.name}! 🤝", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SearchUsersActivity, "Error al aceptar solicitud", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rejectFriendRequest(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            SocialRepository.rejectFriendRequest(db, myUid, user.uid)
                .onSuccess {
                    Toast.makeText(this@SearchUsersActivity, "Solicitud rechazada", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@SearchUsersActivity, "Error al rechazar solicitud", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmRemoveFriend(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        AlertDialog.Builder(this)
            .setTitle("Eliminar de amigos")
            .setMessage("¿Deseas quitar a ${user.name} de tu lista de amigos?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    SocialRepository.removeFriend(db, myUid, user.uid)
                        .onSuccess {
                            Toast.makeText(this@SearchUsersActivity, "${user.name} eliminado de tus amigos", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(this@SearchUsersActivity, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun isBusinessUser(user: UserSearchResult): Boolean {
        val title = user.businessTitle ?: ""
        return title.contains("Dueño", ignoreCase = true) ||
            title.contains("Colaborador", ignoreCase = true) ||
            title.contains("Gestionando", ignoreCase = true) ||
            title.contains("Tienda", ignoreCase = true) ||
            title.contains("Negocio", ignoreCase = true)
    }

    private fun refreshListView() {
        binding.pbLoadingSearch.visibility = View.GONE
        binding.swipeRefreshUsers.isRefreshing = false

        val query = binding.etSearchUserAdd.text.toString().trim()

        // Respetar bloqueos y privacidad de visibilidad en el directorio
        val visibleUsers = allUsersMap.values
            .filter { user ->
                !blockedUids.contains(user.uid) &&
                    (user.allowDirectorySearch ||
                        friendUids.contains(user.uid) ||
                        incomingRequestsMap.containsKey(user.uid) ||
                        activeChatUids.contains(user.uid))
            }

        val friendsCount = visibleUsers.count { friendUids.contains(it.uid) }
        val requestsCount = visibleUsers.count { incomingRequestsMap.containsKey(it.uid) }
        binding.chipFilterFriends.text = "🤝 Amigos ($friendsCount)"
        binding.chipFilterRequests.text = "📩 Solicitudes ($requestsCount)"

        val byCategory = when (currentFilter) {
            UserFilter.ALL -> visibleUsers
            UserFilter.FRIENDS -> visibleUsers.filter { friendUids.contains(it.uid) }
            UserFilter.REQUESTS -> visibleUsers.filter { incomingRequestsMap.containsKey(it.uid) }
            UserFilter.BUSINESSES -> visibleUsers.filter { isBusinessUser(it) }
            UserFilter.BUYERS -> visibleUsers.filter { !isBusinessUser(it) }
        }

        // Búsqueda respetando privacidad: solo busca por nombre público y rol/negocio
        val filtered = if (query.isEmpty()) {
            byCategory
        } else {
            byCategory.filter {
                it.name.contains(query, ignoreCase = true) ||
                    (it.businessTitle ?: "").contains(query, ignoreCase = true)
            }
        }.sortedWith(
            compareByDescending<UserSearchResult> { incomingRequestsMap.containsKey(it.uid) }
                .thenByDescending { friendUids.contains(it.uid) }
                .thenByDescending { isBusinessUser(it) }
                .thenBy { it.name.lowercase() }
        )

        val label = when (currentFilter) {
            UserFilter.ALL -> "Lista de usuarios"
            UserFilter.FRIENDS -> "Mis amigos"
            UserFilter.REQUESTS -> "Solicitudes recibidas"
            UserFilter.BUSINESSES -> "Negocios y tiendas"
            UserFilter.BUYERS -> "Compradores"
        }
        binding.tvTitleUserList.text = "$label (${filtered.size})"

        adapter.submitList(filtered)

        val isEmpty = filtered.isEmpty()
        binding.layoutEmptyUsers.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.swipeRefreshUsers.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (isEmpty) {
            binding.tvEmptyUsersTitle.text = when {
                query.isNotEmpty() -> "Sin resultados para \"$query\""
                currentFilter == UserFilter.FRIENDS -> "Aún no tienes amigos agregados"
                currentFilter == UserFilter.REQUESTS -> "No tienes solicitudes pendientes"
                else -> "No hay usuarios en esta categoría"
            }
            binding.tvEmptyUsersSubtitle.text = when {
                query.isNotEmpty() -> "Intenta con otro nombre o selecciona el filtro Todos."
                currentFilter == UserFilter.FRIENDS -> "Explora el directorio en 'Todos' y toca '+ Conectar' para agregar amigos."
                currentFilter == UserFilter.REQUESTS -> "Cuando alguien quiera conectar contigo aparecerá aquí."
                else -> "Desliza hacia abajo para actualizar la lista."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        profileRegistration?.remove()
        chatRegistration?.remove()
        usersRegistration?.remove()
        blockedRegistration?.remove()
        friendsRegistration?.remove()
        incomingReqRegistration?.remove()
        outgoingReqRegistration?.remove()
    }

    private fun showFriendQrOptionsDialog() {
        if (requireLoginForGuest("usar tu Código QR de amistad")) return
        val options = arrayOf(
            "🪪 Mostrar mi Código QR de Amistad",
            "📷 Escanear QR de un Amigo"
        )
        AlertDialog.Builder(this)
            .setTitle("Conectar por Código QR 🤝")
            .setItems(options) { _, which ->
                if (which == 0) showMyFriendQrDialog() else startFriendQrScanner()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showMyFriendQrDialog() {
        val myUid = auth.currentUser?.uid ?: return
        val (myName, _, _) = getMySenderProfile()
        val qrContent = "gomezmart://friend?uid=$myUid"

        try {
            val size = 560
            val bitMatrix = com.google.zxing.MultiFormatWriter().encode(
                qrContent,
                com.google.zxing.BarcodeFormat.QR_CODE,
                size,
                size
            )
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }

            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad / 2)
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bitmap)
                val imgSize = (220 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(imgSize, imgSize)
            }
            val tvName = TextView(this).apply {
                text = myName
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(0, 16, 0, 4)
            }
            val tvHint = TextView(this).apply {
                text = "Pide a tu amigo que escanee este QR desde Comunidad y Usuarios. Tu teléfono y correo siguen protegidos 🔒."
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#64748B"))
            }
            container.addView(iv)
            container.addView(tvName)
            container.addView(tvHint)

            AlertDialog.Builder(this)
                .setTitle("Mi Código QR GoMezMart")
                .setView(container)
                .setPositiveButton("Listo", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo generar el QR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFriendQrScanner() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue ?: ""
                val targetUid = when {
                    raw.startsWith("gomezmart://friend?uid=") -> raw.substringAfter("gomezmart://friend?uid=").substringBefore("&")
                    else -> ""
                }
                if (targetUid.isBlank()) {
                    Toast.makeText(this, "Código QR no válido para GoMezMart", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                if (targetUid == auth.currentUser?.uid) {
                    Toast.makeText(this, "Ese es tu propio código QR 😄", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                loadAndPreviewUserByUid(targetUid)
            }
            .addOnFailureListener {
                Toast.makeText(this, "No se pudo abrir el escáner QR", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadAndPreviewUserByUid(targetUid: String) {
        val existing = allUsersMap[targetUid]
        if (existing != null) {
            showUserProfilePreview(existing)
            return
        }

        val loading = AlertDialog.Builder(this)
            .setMessage("Buscando perfil del usuario...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val profileDoc = db.collection("users").document(targetUid)
                    .collection("profile").document("info").get().await()
                val mainDoc = db.collection("users").document(targetUid).get().await()
                loading.dismiss()

                val name = profileDoc.getString("name")
                    ?: profileDoc.getString("fullName")
                    ?: mainDoc.getString("name")
                    ?: mainDoc.getString("fullName")
                    ?: "Usuario GoMezMart"
                val photo = profileDoc.getString("photoUrl")
                    ?: mainDoc.getString("photoUrl")
                val title = profileDoc.getString("businessTitle") ?: "Comprador"
                val logo = profileDoc.getString("businessLogoUrl")
                val shareContact = profileDoc.getBoolean("shareContactWithFriends") ?: mainDoc.getBoolean("shareContactWithFriends") ?: false
                val msgPrivacy = MessagePrivacy.fromValue(profileDoc.getString("messagePrivacy") ?: mainDoc.getString("messagePrivacy"))

                val user = UserSearchResult(
                    uid = targetUid,
                    name = name,
                    photoUrl = photo,
                    businessTitle = title,
                    businessLogoUrl = logo,
                    shareContactWithFriends = shareContact,
                    messagePrivacy = msgPrivacy
                )
                allUsersMap[targetUid] = user
                showUserProfilePreview(user)
            } catch (e: Exception) {
                loading.dismiss()
                Toast.makeText(this@SearchUsersActivity, "No se encontró el usuario del QR", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChatWithUser(user: UserSearchResult, isMessageRequest: Boolean = false) {
        val myUid = auth.currentUser?.uid ?: return
        val chatId = if (myUid < user.uid) "${myUid}_${user.uid}" else "${user.uid}_${myUid}"

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId)
            putExtra("OTHER_USER_NAME", user.name)
            putExtra("OTHER_USER_ID", user.uid)
            putExtra("OTHER_USER_PHOTO", user.photoUrl)
            putExtra("OTHER_USER_TITLE", user.businessTitle)
            putExtra("OTHER_USER_BIZ_LOGO", user.businessLogoUrl)
            putExtra("IS_DIRECT", true)
            putExtra("IS_MESSAGE_REQUEST", isMessageRequest)
        }
        startActivity(intent)
        finish()
    }

    data class UserSearchResult(
        val uid: String,
        val name: String,
        val photoUrl: String?,
        val businessTitle: String?,
        val businessLogoUrl: String?,
        val email: String? = null,
        val phone: String? = null,
        val allowDirectorySearch: Boolean = true,
        val shareContactWithFriends: Boolean = false,
        val messagePrivacy: MessagePrivacy = MessagePrivacy.EVERYONE_WITH_REQUEST
    )

    inner class UserSearchAdapter(
        private val onClick: (UserSearchResult) -> Unit,
        private val onQuickAction: (UserSearchResult) -> Unit,
        private val onAcceptRequest: (UserSearchResult) -> Unit,
        private val onRejectRequest: (UserSearchResult) -> Unit
    ) : RecyclerView.Adapter<UserSearchAdapter.ViewHolder>() {
        private var items = listOf<UserSearchResult>()
        fun submitList(list: List<UserSearchResult>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemUserSearchResultBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemUserSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = items[position]
            holder.b.tvSearchUserName.text = user.name
            holder.b.tvSearchUserRole.text = user.businessTitle ?: "Comprador"

            val title = user.businessTitle ?: ""
            val isOwner = title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true)
            val isCollab = title.contains("Colaborador", ignoreCase = true)

            when {
                isOwner -> {
                    holder.b.tvSearchUserRole.setTextColor(Color.parseColor("#B45309"))
                    holder.b.tvSearchUserRole.setBackgroundResource(R.drawable.bg_pill_amber_light)
                }
                isCollab -> {
                    holder.b.tvSearchUserRole.setTextColor(Color.parseColor("#6D28D9"))
                    holder.b.tvSearchUserRole.setBackgroundResource(R.drawable.bg_pill_purple_light)
                }
                else -> {
                    holder.b.tvSearchUserRole.setTextColor(Color.parseColor("#047857"))
                    holder.b.tvSearchUserRole.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                }
            }

            holder.b.ivSearchUserArrow.visibility = View.GONE
            val relation = getRelationStatus(user.uid)
            val incomingReq = incomingRequestsMap[user.uid]

            if (relation == FriendRelationStatus.REQUEST_RECEIVED) {
                holder.b.layoutFriendRequestRowActions.visibility = View.VISIBLE
                holder.b.tvSearchUserActionBadge.visibility = View.GONE
                val note = incomingReq?.note ?: ""
                if (note.isNotBlank()) {
                    holder.b.tvSearchUserRequestNote.visibility = View.VISIBLE
                    holder.b.tvSearchUserRequestNote.text = "💬 \"$note\""
                } else {
                    holder.b.tvSearchUserRequestNote.visibility = View.GONE
                }
                holder.b.btnAcceptFriendRow.setOnClickListener { onAcceptRequest(user) }
                holder.b.btnRejectFriendRow.setOnClickListener { onRejectRequest(user) }
            } else {
                holder.b.layoutFriendRequestRowActions.visibility = View.GONE
                holder.b.tvSearchUserRequestNote.visibility = View.GONE
                holder.b.tvSearchUserActionBadge.visibility = View.VISIBLE

                when (relation) {
                    FriendRelationStatus.FRIENDS -> {
                        holder.b.tvSearchUserActionBadge.text = "🤝 Amigo ›"
                        holder.b.tvSearchUserActionBadge.setTextColor(Color.parseColor("#047857"))
                        holder.b.tvSearchUserActionBadge.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                    }
                    FriendRelationStatus.REQUEST_SENT -> {
                        holder.b.tvSearchUserActionBadge.text = "⏳ Pendiente"
                        holder.b.tvSearchUserActionBadge.setTextColor(Color.parseColor("#B45309"))
                        holder.b.tvSearchUserActionBadge.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    }
                    else -> {
                        if (isBusinessUser(user)) {
                            holder.b.tvSearchUserActionBadge.text = "🏪 Tienda ›"
                            holder.b.tvSearchUserActionBadge.setTextColor(Color.parseColor("#6D28D9"))
                            holder.b.tvSearchUserActionBadge.setBackgroundResource(R.drawable.bg_pill_purple_light)
                        } else {
                            holder.b.tvSearchUserActionBadge.text = "+ Conectar"
                            holder.b.tvSearchUserActionBadge.setTextColor(Color.parseColor("#6D28D9"))
                            holder.b.tvSearchUserActionBadge.setBackgroundResource(R.drawable.bg_pill_purple_light)
                        }
                    }
                }
                holder.b.tvSearchUserActionBadge.setOnClickListener { onQuickAction(user) }
            }

            holder.b.tvSearchUserInitial.text = if (user.name.isNotEmpty()) user.name[0].uppercaseChar().toString() else "?"

            if (!user.photoUrl.isNullOrEmpty()) {
                holder.b.ivSearchUserPhoto.visibility = View.VISIBLE
                Glide.with(holder.itemView.context)
                    .load(user.photoUrl)
                    .apply(GlideHelper.getOptions(holder.itemView.context))
                    .circleCrop()
                    .into(holder.b.ivSearchUserPhoto)
            } else {
                holder.b.ivSearchUserPhoto.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(user) }
        }
        override fun getItemCount() = items.size
    }
}
