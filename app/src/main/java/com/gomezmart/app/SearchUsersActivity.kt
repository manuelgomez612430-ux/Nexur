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
    
    private var profileRegistration: ListenerRegistration? = null
    private var chatRegistration: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para la cabecera
        applyWindowInsets(binding.headerSearchUsers, applyTop = true, applyBottom = false)
        applyWindowInsetsHeight(binding.topHeaderBackgroundSearch, applyTop = true, applyBottom = false)

        setupRecyclerView()
        setupListeners()
    }

    private fun setupRecyclerView() {
        adapter = UserSearchAdapter { user -> showUserProfilePreview(user) }
        binding.rvUserSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvUserSearchResults.adapter = adapter
    }

    private fun showUserProfilePreview(user: UserSearchResult) {
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
            title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> Color.parseColor("#FFD700")
            title.contains("Colaborador", ignoreCase = true) -> Color.parseColor("#3B82F6")
            title.contains("Comprador", ignoreCase = true) -> Color.parseColor("#10B981")
            else -> Color.parseColor("#64748B")
        }
        dialogView.tvPreviewUserRole.setTextColor(color)

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

        // Cargar email y teléfono reales
        var userPhone = user.phone ?: ""
        var userEmail = user.email ?: ""

        if (userPhone.isNotEmpty()) {
            dialogView.tvPreviewUserPhone.text = userPhone
            dialogView.btnPreviewCallUser.isEnabled = true
        } else {
            dialogView.tvPreviewUserPhone.text = "Cargando..."
            dialogView.btnPreviewCallUser.isEnabled = false
        }

        if (userEmail.isNotEmpty()) {
            dialogView.tvPreviewUserEmail.text = userEmail
        } else {
            dialogView.tvPreviewUserEmail.text = "Cargando..."
        }

        dialogView.btnPreviewCallUser.setOnClickListener {
            if (userPhone.isNotEmpty()) {
                val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$userPhone"))
                startActivity(callIntent)
            } else {
                Toast.makeText(this@SearchUsersActivity, "No tiene teléfono registrado", Toast.LENGTH_SHORT).show()
            }
        }

        // Carga asíncrona desde Firestore si faltaban datos
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

        // Ocultar elementos de chat en la búsqueda de usuarios
        dialogView.cardChatStorageInfo.visibility = View.GONE
        dialogView.layoutSharedPhotosSection.visibility = View.GONE
        dialogView.btnDeleteChat.visibility = View.GONE
        dialogView.btnBlockUser.visibility = View.GONE

        // Mostrar botón de Enviar Mensaje
        dialogView.btnPreviewSendMessage.visibility = View.VISIBLE
        dialogView.btnPreviewSendMessage.setOnClickListener {
            dialog.dismiss()
            openChatWithUser(user)
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
        
        binding.swipeRefreshUsers.setOnRefreshListener {
            startRealtimeListeners()
        }

        binding.etSearchUserAdd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshListView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        startRealtimeListeners()
    }

    private fun startRealtimeListeners() {
        binding.pbLoadingSearch.visibility = View.VISIBLE
        
        profileRegistration?.remove()
        chatRegistration?.remove()
        allUsersMap.clear()

        val myUid = auth.currentUser?.uid ?: return

        // 1. Escuchar TODOS los perfiles registrados (Buscador Global)
        profileRegistration = db.collectionGroup("profile").addSnapshotListener { snapshot, e ->
            binding.pbLoadingSearch.visibility = View.GONE
            binding.swipeRefreshUsers.isRefreshing = false
            
            if (e != null) {
                Log.e("SearchUsers", "Error en perfiles: ${e.message}")
                return@addSnapshotListener
            }
            
            snapshot?.documents?.forEach { doc ->
                val name = doc.getString("name") ?: doc.getString("fullName") ?: ""
                val photo = doc.getString("photoUrl") ?: doc.getString("profilePhoto") ?: doc.getString("user_photo_url") ?: ""
                val email = doc.getString("email") ?: doc.getString("user_email")
                val phone = doc.getString("phone") ?: doc.getString("user_phone")
                
                val pathParts = doc.reference.path.split("/")
                if (pathParts.size >= 2) {
                    val uid = pathParts[1]
                    if (uid == myUid) return@forEach
                    
                    // REGLA: Solo añadir si tiene Nombre y Teléfono (Información requerida)
                    if (name.isBlank() || name == "Usuario" || phone.isNullOrBlank()) return@forEach
                    
                    val user = UserSearchResult(
                        uid, 
                        name, 
                        photo, 
                        doc.getString("businessTitle") ?: "Comprador",
                        doc.getString("businessLogoUrl") ?: doc.getString("logoUrl"),
                        email,
                        phone
                    )
                    
                    deduplicateAndAdd(user)
                }
            }
            refreshListView()
        }

        // 2. Escuchar MIS CHATS (Fallback para asegurar que salgan todos)
        chatRegistration = db.collection("direct_chats")
            .whereArrayContains("participants", myUid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                
                snapshot?.documents?.forEach { doc ->
                    val participants = doc.get("participants") as? List<String> ?: return@forEach
                    val otherUid = participants.find { it != myUid } ?: return@forEach
                    
                    val names = doc.get("participantNames") as? Map<String, String>
                    val photos = doc.get("participantPhotos") as? Map<String, String>
                    val titles = doc.get("participantTitles") as? Map<String, String>
                    val emails = doc.get("participantEmails") as? Map<String, String>
                    val phones = doc.get("participantPhones") as? Map<String, String>
                    
                    val name = names?.get(otherUid) ?: ""
                    val phone = phones?.get(otherUid) ?: ""
                    val email = emails?.get(otherUid)
                    
                    // REGLA: Solo añadir si tiene Nombre y Teléfono
                    if (name.isBlank() || name.startsWith("Usuario (") || phone.isBlank()) return@forEach
                    
                    val user = UserSearchResult(
                        otherUid,
                        name,
                        photos?.get(otherUid),
                        titles?.get(otherUid) ?: "Comprador",
                        null,
                        email,
                        phone
                    )

                    deduplicateAndAdd(user)
                }
                refreshListView()
            }
        
        // 3. Escuchar la colección raíz de USUARIOS (Asegura cobertura total)
        db.collection("users").addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null) return@addSnapshotListener
            snapshot.documents.forEach { doc ->
                val uid = doc.id
                if (uid == myUid) return@forEach
                
                val name = doc.getString("name") ?: doc.getString("fullName") ?: doc.getString("displayName") ?: doc.getString("user_full_name") ?: ""
                val photo = doc.getString("photoUrl") ?: doc.getString("profilePhoto") ?: doc.getString("user_photo_url") ?: doc.getString("photo")
                val email = doc.getString("email") ?: doc.getString("user_email")
                val phone = doc.getString("phone") ?: doc.getString("user_phone")

                // REGLA: Solo añadir si tiene Nombre y Teléfono
                if (name.isBlank() || name.startsWith("Usuario (") || phone.isNullOrBlank()) return@forEach

                val user = UserSearchResult(
                    uid, name, photo, "Comprador", null, email, phone
                )
                deduplicateAndAdd(user)
            }
            refreshListView()
        }
    }

    /**
     * Lógica centralizada para evitar duplicados por Email o Teléfono.
     */
    private fun deduplicateAndAdd(user: UserSearchResult) {
        // 1. Buscar si ya existe por el mismo UID
        val existingByUid = allUsersMap[user.uid]
        
        // 2. Buscar si existe otra cuenta con el mismo EMAIL
        val existingByEmail = if (!user.email.isNullOrBlank()) {
            allUsersMap.values.find { it.email?.lowercase() == user.email.lowercase() }
        } else null
        
        // 3. Buscar si existe otra cuenta con el mismo TELÉFONO
        val existingByPhone = if (!user.phone.isNullOrBlank()) {
            allUsersMap.values.find { it.phone == user.phone }
        } else null

        val finalExisting = existingByUid ?: existingByEmail ?: existingByPhone

        if (finalExisting == null) {
            allUsersMap[user.uid] = user
        } else {
            // FUSIONAR: Mantener el UID original pero actualizar datos si el nuevo tiene más info
            if (finalExisting.photoUrl.isNullOrEmpty() && !user.photoUrl.isNullOrEmpty()) {
                allUsersMap[finalExisting.uid] = finalExisting.copy(photoUrl = user.photoUrl)
            }
            // Asegurar que guardamos email/phone si no los teníamos
            if (finalExisting.email.isNullOrEmpty() && !user.email.isNullOrEmpty()) {
                allUsersMap[finalExisting.uid] = allUsersMap[finalExisting.uid]?.copy(email = user.email) ?: user
            }
            if (finalExisting.phone.isNullOrEmpty() && !user.phone.isNullOrEmpty()) {
                allUsersMap[finalExisting.uid] = allUsersMap[finalExisting.uid]?.copy(phone = user.phone) ?: user
            }
        }
    }

    private fun refreshListView() {
        val query = binding.etSearchUserAdd.text.toString().trim()
        val list = allUsersMap.values.toList().sortedBy { it.name }
        
        val filtered = if (query.isEmpty()) {
            list
        } else {
            // [NUEVO] BUSCAR POR NOMBRE O POR NÚMERO DE CELULAR
            list.filter { 
                it.name.contains(query, ignoreCase = true) || 
                (it.phone ?: "").contains(query)
            }
        }
        
        adapter.submitList(filtered)
    }

    override fun onDestroy() {
        super.onDestroy()
        profileRegistration?.remove()
        chatRegistration?.remove()
    }

    private fun openChatWithUser(user: UserSearchResult) {
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
        val phone: String? = null
    )

    inner class UserSearchAdapter(private val onClick: (UserSearchResult) -> Unit) : RecyclerView.Adapter<UserSearchAdapter.ViewHolder>() {
        private var items = listOf<UserSearchResult>()
        fun submitList(list: List<UserSearchResult>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemUserSearchResultBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemUserSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = items[position]
            holder.b.tvSearchUserName.text = user.name
            holder.b.tvSearchUserRole.text = user.businessTitle ?: "Comprador"
            
            val title = user.businessTitle ?: ""
            val color = when {
                title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> Color.parseColor("#FFD700")
                title.contains("Colaborador", ignoreCase = true) -> Color.parseColor("#3B82F6")
                title.contains("Comprador", ignoreCase = true) -> Color.parseColor("#10B981")
                else -> Color.parseColor("#64748B")
            }
            holder.b.tvSearchUserRole.setTextColor(color)

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
