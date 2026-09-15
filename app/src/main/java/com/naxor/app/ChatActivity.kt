package com.naxor.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.naxor.app.data.ChatMessage
import com.naxor.app.databinding.ActivityChatBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var orderId: String = ""
    private var chatRegistration: ListenerRegistration? = null
    
    private lateinit var adapter: ChatAdapter

    private var isDirectChat: Boolean = false
    private var otherUserId: String = ""
    private var otherUserName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            Glide.with(this).load(otherPhoto).circleCrop().into(binding.ivChatHeaderPhoto)
        } else {
            binding.ivChatHeaderPhoto.visibility = View.GONE
        }
        
        if (otherBizLogo.isNotEmpty()) {
            binding.cardChatHeaderBizLogo.visibility = View.VISIBLE
            Glide.with(this).load(otherBizLogo).circleCrop().into(binding.ivChatHeaderBizLogo)
        } else {
            binding.cardChatHeaderBizLogo.visibility = View.GONE
        }

        binding.toolbarChat.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupListeners()
        
        if (isDirectChat) {
            loadInitialUnreadCountAndStartListener()
            fetchParticipantProfiles()
        } else {
            startChatListener()
        }
    }

    private fun loadInitialUnreadCountAndStartListener() {
        val myUid = auth.currentUser?.uid ?: return
        
        db.collection("direct_chats").document(orderId).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val unreadMap = doc.get("unreadCount") as? Map<String, Long>
                initialUnreadCount = unreadMap?.get(myUid)?.toInt() ?: 0
                Log.d("ChatActivity", "Initial unread count: $initialUnreadCount")
            }
            // Ahora que tenemos el conteo inicial, arrancamos el listener y reseteamos en la nube
            startChatListener()
            resetUnreadCount()
        }.addOnFailureListener {
            startChatListener()
        }
    }

    private fun resetUnreadCount() {
        val myUid = auth.currentUser?.uid ?: return
        if (isDirectChat) {
            db.collection("direct_chats").document(orderId)
                .update("unreadCount.$myUid", 0)
        }
    }

    private fun fetchParticipantProfiles() {
        val myUid = auth.currentUser?.uid ?: return
        
        lifecycleScope.launch {
            try {
                // 1. Obtener mi propio perfil actualizado
                val myProfile = db.collection("users").document(myUid).collection("profile").document("info").get().await()
                val myName = myProfile.getString("name") ?: auth.currentUser?.displayName ?: "Usuario"
                val myPhoto = myProfile.getString("photoUrl") ?: ""
                val myTitle = myProfile.getString("businessTitle") ?: "Comprador"

                // 2. Obtener el perfil del otro usuario
                val otherProfile = db.collection("users").document(otherUserId).collection("profile").document("info").get().await()
                if (otherProfile.exists()) {
                    val oName = otherProfile.getString("name") ?: "Usuario"
                    val oPhoto = otherProfile.getString("photoUrl") ?: ""
                    val oTitle = otherProfile.getString("businessTitle") ?: "Comprador"
                    val oBizLogo = otherProfile.getString("businessLogoUrl") ?: ""
                    
                    otherUserName = oName
                    binding.tvChatHeaderName.text = otherUserName
                    binding.tvChatHeaderRole.text = oTitle
                    updateRoleColor(oTitle)
                    
                    if (oPhoto.isNotEmpty()) {
                        binding.ivChatHeaderPhoto.visibility = View.VISIBLE
                        Glide.with(this@ChatActivity).load(oPhoto).circleCrop().into(binding.ivChatHeaderPhoto)
                    } else {
                        binding.ivChatHeaderPhoto.visibility = View.GONE
                    }
                    
                    if (oBizLogo.isNotEmpty()) {
                        binding.cardChatHeaderBizLogo.visibility = View.VISIBLE
                        Glide.with(this@ChatActivity).load(oBizLogo).circleCrop().into(binding.ivChatHeaderBizLogo)
                    } else {
                        binding.cardChatHeaderBizLogo.visibility = View.GONE
                    }
                    
                    // Actualizar metadatos del chat con la información fresca de ambos
                    syncChatMetadata(myName, myPhoto, myTitle, oName, oPhoto, oTitle, oBizLogo)
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error fetching profiles: ${e.message}")
            }
        }
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

    private fun syncChatMetadata(myName: String, myPhoto: String, myTitle: String, oName: String, oPhoto: String, oTitle: String, oBizLogo: String = "") {
        val myUid = auth.currentUser?.uid ?: return
        val chatRef = db.collection("direct_chats").document(orderId)
        
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = bizPrefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val myBizLogo = bizPrefs.getString("${businessType}_logo_url", null) ?: bizPrefs.getString("business_logo_url", "") ?: ""

        val participants = listOf(myUid, otherUserId).sorted()
        val names = mapOf(myUid to myName, otherUserId to oName)
        val photos = mapOf(myUid to myPhoto, otherUserId to oPhoto)
        val titles = mapOf(myUid to myTitle, otherUserId to oTitle)
        
        val bizLogos = mutableMapOf<String, String>()
        if (myBizLogo.isNotEmpty()) bizLogos[myUid] = myBizLogo
        if (oBizLogo.isNotEmpty()) bizLogos[otherUserId] = oBizLogo

        val data = mapOf(
            "participantNames" to names,
            "participantPhotos" to photos,
            "participantBusinessLogos" to bizLogos,
            "participantTitles" to titles,
            "participants" to participants
        )
        
        chatRef.set(data, SetOptions.merge())
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(auth.currentUser?.uid ?: "")
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvChatMessages.layoutManager = layoutManager
        binding.rvChatMessages.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }
    }

    private fun startChatListener() {
        if (orderId.isEmpty()) return
        
        val collection = if (isDirectChat) "direct_chats" else "orders"

        chatRegistration = db.collection(collection).document(orderId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                val messages = snapshot.toObjects(ChatMessage::class.java)
                
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isDirectChat) {
                    resetUnreadCount()
                }

                adapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    if (!snapshot.metadata.hasPendingWrites()) {
                        binding.rvChatMessages.smoothScrollToPosition(messages.size - 1)
                    } else {
                        binding.rvChatMessages.scrollToPosition(messages.size - 1)
                    }
                }
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

        val collection = if (isDirectChat) "direct_chats" else "orders"

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

            // Actualizar mi información
            names[myUid] = myName
            if (!myPhoto.isNullOrEmpty()) photos[myUid] = myPhoto
            titles[myUid] = myTitle
            
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
                    "participantTitles", titles
                )
            } else {
                val data = mapOf(
                    "id" to orderId,
                    "participants" to participants,
                    "participantNames" to names,
                    "participantPhotos" to photos,
                    "participantTitles" to titles,
                    "lastMessage" to lastMessage,
                    "lastMessageTime" to FieldValue.serverTimestamp()
                )
                transaction.set(chatRef, data)
            }
        }.addOnFailureListener { e ->
            Log.e("ChatActivity", "Error updating metadata: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .putString("current_chat_id", orderId)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .remove("current_chat_id")
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        chatRegistration?.remove()
    }

    inner class ChatAdapter(private val currentUserId: String) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        private var messages = listOf<ChatMessage>()
        private var displayItems = mutableListOf<Any>() // Puede ser ChatMessage o String (divisor)

        fun submitList(newList: List<ChatMessage>) {
            messages = newList
            refreshDisplayItems()
            notifyDataSetChanged()
        }

        private fun refreshDisplayItems() {
            displayItems.clear()
            if (initialUnreadCount > 0 && messages.size >= initialUnreadCount) {
                val splitIdx = messages.size - initialUnreadCount
                for (i in messages.indices) {
                    if (i == splitIdx) displayItems.add("SEPARATOR")
                    displayItems.add(messages[i])
                }
            } else {
                displayItems.addAll(messages)
            }
        }

        override fun getItemViewType(position: Int): Int {
            val item = displayItems[position]
            if (item is String) return 2 // Separator
            val msg = item as ChatMessage
            return if (msg.senderId == currentUserId) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> MessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message_me, parent, false))
                1 -> MessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message_other, parent, false))
                else -> SeparatorViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_separator, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayItems[position]
            if (holder is MessageViewHolder && item is ChatMessage) {
                holder.text.text = item.message
            }
        }

        override fun getItemCount() = displayItems.size

        inner class MessageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.tvMessageText)
        }
        
        inner class SeparatorViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
}
