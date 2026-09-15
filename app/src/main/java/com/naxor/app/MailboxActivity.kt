package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.naxor.app.data.AppMessage
import com.naxor.app.data.DirectChat
import com.naxor.app.databinding.ActivityMailboxBinding
import com.naxor.app.databinding.ItemMessageBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class MailboxActivity : BaseActivity() {

    private lateinit var binding: ActivityMailboxBinding
    private lateinit var adapter: MessageAdapter
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMailboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupRecyclerView()
        setupListeners()
        loadMessages()
        loadActiveChats()
    }

    private fun setupTabs() {
        binding.tabLayoutMailbox.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutChatsSection.visibility = View.VISIBLE
                        binding.rvMessages.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutChatsSection.visibility = View.GONE
                        binding.rvMessages.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupListeners() {
        binding.btnBackMailbox.setOnClickListener { finish() }
        
        binding.btnAddUserChat.setOnClickListener {
            startActivity(Intent(this, SearchUsersActivity::class.java))
        }

        binding.etSearchUserChat.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterExistingChats(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private var allActiveChats = listOf<DirectChat>()

    private fun filterExistingChats(query: String) {
        val filtered = allActiveChats.filter { chat ->
            val otherUid = chat.participants.firstOrNull { it != FirebaseAuth.getInstance().currentUser?.uid } ?: ""
            val name = chat.participantNames[otherUid] ?: ""
            name.contains(query, ignoreCase = true)
        }
        activeChatsAdapter.submitList(filtered)
    }

    data class UserSearchResult(val uid: String, val name: String, val photoUrl: String?, val businessTitle: String?, val businessLogoUrl: String?)

    private lateinit var activeChatsAdapter: ActiveChatsAdapter

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter
        
        activeChatsAdapter = ActiveChatsAdapter { chat ->
            openExistingChat(chat)
        }
        
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = activeChatsAdapter
    }

    private fun openExistingChat(chat: DirectChat) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
        val otherName = chat.participantNames[otherUid] ?: "Chat"
        val otherPhoto = chat.participantPhotos[otherUid] ?: ""
        val otherTitle = chat.participantTitles[otherUid] ?: ""
        val otherBizLogo = chat.participantBusinessLogos[otherUid] ?: ""
        
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chat.id)
            putExtra("OTHER_USER_NAME", otherName)
            putExtra("OTHER_USER_ID", otherUid)
            putExtra("OTHER_USER_PHOTO", otherPhoto)
            putExtra("OTHER_USER_TITLE", otherTitle)
            putExtra("OTHER_USER_BIZ_LOGO", otherBizLogo)
            putExtra("IS_DIRECT", true)
        }
        startActivity(intent)
    }

    private fun loadActiveChats() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        val reg = FirebaseFirestore.getInstance().collection("direct_chats")
            .whereArrayContains("participants", myUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, e ->
                if (e != null) return@addSnapshotListener
                
                val chats = snapshot?.documents?.mapNotNull { doc ->
                    val chat = doc.toObject(DirectChat::class.java)
                    chat?.copy(id = doc.id)
                } ?: emptyList()
                
                allActiveChats = chats.sortedByDescending { chat ->
                    when (val t = chat.lastMessageTime) {
                        is Timestamp -> t.toDate().time
                        is Long -> t
                        else -> 0L
                    }
                }
                
                // Si el campo de búsqueda está vacío, mostrar todos
                if (binding.etSearchUserChat.text.toString().isEmpty()) {
                    activeChatsAdapter.submitList(allActiveChats)
                }
            }
        registrations.add(reg)
    }

    inner class ActiveChatsAdapter(private val onClick: (DirectChat) -> Unit) : RecyclerView.Adapter<ActiveChatsAdapter.ViewHolder>() {
        private var items = listOf<DirectChat>()
        
        fun submitList(list: List<DirectChat>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvChatUserName)
            val tvLastMessage: TextView = view.findViewById(R.id.tvChatLastMessage)
            val tvTime: TextView = view.findViewById(R.id.tvChatTime)
            val tvInitial: TextView = view.findViewById(R.id.tvChatUserInitial)
            val ivPhoto: ImageView = view.findViewById(R.id.ivChatUserPhoto)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_active_chat, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chat = items[position]
            val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
            val name = chat.participantNames[otherUid] ?: "Usuario"
            val photo = chat.participantPhotos[otherUid]
            val title = chat.participantTitles[otherUid]
            val bizLogo = chat.participantBusinessLogos[otherUid]
            val unread = chat.unreadCount[myUid] ?: 0
            
            holder.tvName.text = name
            
            val roleTv: TextView? = holder.itemView.findViewById(R.id.tvChatUserRole)
            if (roleTv != null) {
                roleTv.text = title ?: "Usuario"
                val color = when {
                    (title ?: "").contains("Dueño", ignoreCase = true) || (title ?: "").contains("Gestionando", ignoreCase = true) -> Color.parseColor("#FFD700")
                    (title ?: "").contains("Colaborador", ignoreCase = true) -> Color.parseColor("#3B82F6")
                    (title ?: "").contains("Comprador", ignoreCase = true) -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#64748B")
                }
                roleTv.setTextColor(color)
            }
            
            holder.tvLastMessage.text = chat.lastMessage

            // Bind unread badge
            val unreadBadge: TextView? = holder.itemView.findViewById(R.id.tvUnreadBadgeItem)
            if (unreadBadge != null) {
                if (unread > 0) {
                    unreadBadge.visibility = View.VISIBLE
                    unreadBadge.text = if (unread > 99) "99+" else unread.toString()
                } else {
                    unreadBadge.visibility = View.GONE
                }
            }
            
            val initial = if (name.isNotEmpty()) name[0].uppercaseChar().toString() else "?"
            holder.tvInitial.text = initial

            if (!photo.isNullOrEmpty()) {
                holder.ivPhoto.visibility = View.VISIBLE
                Glide.with(holder.itemView.context).load(photo).circleCrop().into(holder.ivPhoto)
            } else {
                holder.ivPhoto.visibility = View.GONE
            }

            // Bind Biz Logo in list
            val bizLogoCard: View? = holder.itemView.findViewById(R.id.cardChatListBizLogo)
            val bizLogoIv: ImageView? = holder.itemView.findViewById(R.id.ivChatListBizLogo)
            if (bizLogoCard != null && bizLogoIv != null) {
                if (!bizLogo.isNullOrEmpty()) {
                    bizLogoCard.visibility = View.VISIBLE
                    Glide.with(holder.itemView.context).load(bizLogo).circleCrop().into(bizLogoIv)
                } else {
                    bizLogoCard.visibility = View.GONE
                }
            }

            // Formatear tiempo
            val time = chat.lastMessageTime
            if (time != null) {
                val ts = when (time) {
                    is Timestamp -> time.toDate().time
                    is Long -> time
                    else -> 0L
                }
                if (ts > 0) {
                    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    holder.tvTime.text = sdf.format(Date(ts))
                }
            }

            holder.itemView.setOnClickListener { onClick(chat) }
        }

        override fun getItemCount() = items.size
    }

    inner class UserSearchAdapter(private val onClick: (UserSearchResult) -> Unit) : RecyclerView.Adapter<UserSearchAdapter.ViewHolder>() {
        private var items = listOf<UserSearchResult>()
        
        fun submitList(list: List<UserSearchResult>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSearchUserName)
            val tvInitial: TextView = view.findViewById(R.id.tvSearchUserInitial)
            val ivPhoto: ImageView = view.findViewById(R.id.ivSearchUserPhoto)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_search_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = items[position]
            holder.tvName.text = user.name
            
            val roleTv: TextView? = holder.itemView.findViewById(R.id.tvSearchUserRole)
            if (roleTv != null) {
                roleTv.text = user.businessTitle ?: "Comprador"
                val color = when {
                    (user.businessTitle ?: "").contains("Dueño", ignoreCase = true) || (user.businessTitle ?: "").contains("Gestionando", ignoreCase = true) -> Color.parseColor("#FFD700")
                    (user.businessTitle ?: "").contains("Colaborador", ignoreCase = true) -> Color.parseColor("#3B82F6")
                    (user.businessTitle ?: "").contains("Comprador", ignoreCase = true) -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#64748B")
                }
                roleTv.setTextColor(color)
            }
            
            val initial = if (user.name.isNotEmpty()) user.name[0].uppercaseChar().toString() else "?"
            holder.tvInitial.text = initial

            if (!user.photoUrl.isNullOrEmpty()) {
                holder.ivPhoto.visibility = View.VISIBLE
                Glide.with(holder.itemView.context)
                    .load(user.photoUrl)
                    .circleCrop()
                    .into(holder.ivPhoto)
            } else {
                holder.ivPhoto.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(user) }
        }

        override fun getItemCount() = items.size
    }

    private fun loadMessages() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        // 1. CARGAR MENSAJES GLOBALES
        val regGlobal = FirebaseFirestore.getInstance().collection("app_messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                processAndSortMessages(snapshot, userId)
            }
        registrations.add(regGlobal)

        // 2. CARGAR MENSAJES PRIVADOS
        val regPrivate = FirebaseFirestore.getInstance().collection("users").document(userId).collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                processAndSortMessages(null, userId) // Activamos el refresco total
            }
        registrations.add(regPrivate)
    }

    private fun processAndSortMessages(globalSnapshot: QuerySnapshot?, userId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Obtener Globales
                val globalSnap = globalSnapshot ?: FirebaseFirestore.getInstance().collection("app_messages").get().await()
                val globalList = globalSnap.documents.mapNotNull { doc ->
                    val msg = doc.toObject(AppMessage::class.java)
                    msg?.copy(id = doc.id)
                }

                // Obtener Privados (Filtrar los de tipo CHAT que son solo para notificaciones)
                val privateSnap = FirebaseFirestore.getInstance().collection("users").document(userId).collection("messages").get().await()
                val privateList = privateSnap.documents.mapNotNull { doc ->
                    val msg = doc.toObject(AppMessage::class.java)
                    if (msg?.type == "CHAT") return@mapNotNull null
                    msg?.copy(id = doc.id)
                }

                // Unificar y ordenar por el más RECIENTE primero
                val allMessages = (globalList + privateList).sortedByDescending { it.getTimestampMillis() }

                withContext(Dispatchers.Main) {
                    adapter.submitList(allMessages)
                    binding.layoutEmptyMailbox.visibility = if (allMessages.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    private fun AppMessage.getTimestampMillis(): Long {
        return when (val t = timestamp) {
            is Timestamp -> t.toDate().time
            is String -> try { t.toLong() } catch (e: Exception) { 0L }
            is Long -> t
            else -> 0L
        }
    }

    inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {
        private var items = listOf<AppMessage>()

        fun submitList(list: List<AppMessage>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            holder.b.tvMessageTitle.text = msg.title
            
            val previewText = if (msg.content.length > 80) msg.content.take(77) + "..." else msg.content
            holder.b.tvMessageContent.text = previewText
            
            val date = Date(msg.getTimestampMillis())
            val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
            holder.b.tvMessageTime.text = sdf.format(date)

            holder.itemView.setOnClickListener {
                showMessageDetail(msg)
            }
        }

        override fun getItemCount() = items.size
    }

    private fun showMessageDetail(msg: AppMessage) {
        val intent = Intent(this, MessageDetailActivity::class.java).apply {
            putExtra("EXTRA_TITLE", msg.title)
            putExtra("EXTRA_CONTENT", msg.content)
            putExtra("EXTRA_TIME", msg.getTimestampMillis())
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }
}
