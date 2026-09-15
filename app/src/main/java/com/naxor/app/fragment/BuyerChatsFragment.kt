package com.naxor.app.fragment

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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.naxor.app.*
import com.naxor.app.data.AppMessage
import com.naxor.app.data.DirectChat
import com.naxor.app.databinding.FragmentBuyerChatsBinding
import com.naxor.app.databinding.ItemMessageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class BuyerChatsFragment : Fragment() {

    private var _binding: FragmentBuyerChatsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var userSearchAdapter: UserSearchAdapter
    private lateinit var activeChatsAdapter: ActiveChatsAdapter
    private val registrations = mutableListOf<ListenerRegistration>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupRecyclerViews()
        setupListeners()
        loadMessages()
        loadActiveChats()
    }

    private fun setupTabs() {
        binding.tabLayoutBuyerChats.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
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

    private fun setupRecyclerViews() {
        messageAdapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = messageAdapter

        userSearchAdapter = UserSearchAdapter { user -> openChatWithUser(user) }
        activeChatsAdapter = ActiveChatsAdapter { chat -> openExistingChat(chat) }

        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChats.adapter = activeChatsAdapter
    }

    private fun setupListeners() {
        binding.btnAddUserChat.setOnClickListener {
            startActivity(Intent(requireContext(), SearchUsersActivity::class.java))
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
            val otherUid = chat.participants.firstOrNull { it != auth.currentUser?.uid } ?: ""
            val name = chat.participantNames[otherUid] ?: ""
            name.contains(query, ignoreCase = true)
        }
        activeChatsAdapter.submitList(filtered)
    }

    private fun loadActiveChats() {
        val myUid = auth.currentUser?.uid ?: return
        val reg = db.collection("direct_chats")
            .whereArrayContains("participants", myUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, _ ->
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

                if (binding.etSearchUserChat.text.toString().isEmpty()) {
                    binding.rvChats.adapter = activeChatsAdapter
                    activeChatsAdapter.submitList(allActiveChats)
                }
            }
        registrations.add(reg)
    }

    private fun loadMessages() {
        val userId = auth.currentUser?.uid ?: return
        val regGlobal = db.collection("app_messages").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ -> processAndSortMessages(snapshot, userId) }
        registrations.add(regGlobal)

        val regPrivate = db.collection("users").document(userId).collection("messages").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { _, _ -> processAndSortMessages(null, userId) }
        registrations.add(regPrivate)
    }

    private fun processAndSortMessages(globalSnapshot: QuerySnapshot?, userId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val globalSnap = globalSnapshot ?: db.collection("app_messages").get().await()
                val globalList = globalSnap.documents.mapNotNull { it.toObject(AppMessage::class.java)?.copy(id = it.id) }
                val privateSnap = db.collection("users").document(userId).collection("messages").get().await()
                val privateList = privateSnap.documents.mapNotNull { 
                    val msg = it.toObject(AppMessage::class.java)
                    if (msg?.type == "CHAT") return@mapNotNull null
                    msg?.copy(id = it.id)
                }
                val allMessages = (globalList + privateList).sortedByDescending { it.getTimestampMillis() }

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        messageAdapter.submitList(allMessages)
                        binding.layoutEmptyMailbox.visibility = if (allMessages.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun openChatWithUser(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        val chatId = if (myUid < user.uid) "${myUid}_${user.uid}" else "${user.uid}_${myUid}"
        startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId); putExtra("OTHER_USER_NAME", user.name)
            putExtra("OTHER_USER_ID", user.uid); putExtra("OTHER_USER_PHOTO", user.photoUrl)
            putExtra("OTHER_USER_TITLE", user.businessTitle)
            putExtra("OTHER_USER_BIZ_LOGO", user.businessLogoUrl)
            putExtra("IS_DIRECT", true)
        })
    }

    private fun openExistingChat(chat: DirectChat) {
        val myUid = auth.currentUser?.uid ?: return
        val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
        val otherName = chat.participantNames[otherUid] ?: "Chat"
        val otherPhoto = chat.participantPhotos[otherUid] ?: ""
        val otherTitle = chat.participantTitles[otherUid] ?: ""
        val otherBizLogo = chat.participantBusinessLogos[otherUid] ?: ""
        startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chat.id); putExtra("OTHER_USER_NAME", otherName)
            putExtra("OTHER_USER_ID", otherUid); putExtra("OTHER_USER_PHOTO", otherPhoto)
            putExtra("OTHER_USER_TITLE", otherTitle); putExtra("OTHER_USER_BIZ_LOGO", otherBizLogo); putExtra("IS_DIRECT", true)
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        registrations.forEach { it.remove() }
        _binding = null
    }

    private fun AppMessage.getTimestampMillis(): Long = when (val t = timestamp) {
        is Timestamp -> t.toDate().time
        is String -> t.toLongOrNull() ?: 0L
        is Long -> t
        else -> 0L
    }

    data class UserSearchResult(val uid: String, val name: String, val photoUrl: String?, val businessTitle: String?, val businessLogoUrl: String?)

    inner class ActiveChatsAdapter(private val onClick: (DirectChat) -> Unit) : RecyclerView.Adapter<ActiveChatsAdapter.ViewHolder>() {
        private var items = listOf<DirectChat>()
        fun submitList(list: List<DirectChat>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvChatUserName)
            val tvLastMsg: TextView = view.findViewById(R.id.tvChatLastMessage)
            val tvTime: TextView = view.findViewById(R.id.tvChatTime)
            val tvInitial: TextView = view.findViewById(R.id.tvChatUserInitial)
            val ivPhoto: ImageView = view.findViewById(R.id.ivChatUserPhoto)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_active_chat, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chat = items[position]
            val otherUid = chat.participants.firstOrNull { it != auth.currentUser?.uid } ?: ""
            val name = chat.participantNames[otherUid] ?: "Usuario"
            val title = chat.participantTitles[otherUid]
            val bizLogo = chat.participantBusinessLogos[otherUid]
            val unread = chat.unreadCount[auth.currentUser?.uid ?: ""] ?: 0
            
            holder.tvName.text = name
            holder.tvLastMsg.text = chat.lastMessage
            
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
            
            holder.tvInitial.text = if (name.isNotEmpty()) name[0].uppercaseChar().toString() else "?"
            
            val photo = chat.participantPhotos[otherUid]
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
            val ts = when (val t = chat.lastMessageTime) {
                is Timestamp -> t.toDate().time
                is Long -> t
                else -> 0L
            }
            if (ts > 0) {
                holder.tvTime.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ts))
            }
            holder.itemView.setOnClickListener { onClick(chat) }
        }
        override fun getItemCount() = items.size
    }

    inner class UserSearchAdapter(private val onClick: (UserSearchResult) -> Unit) : RecyclerView.Adapter<UserSearchAdapter.ViewHolder>() {
        private var items = listOf<UserSearchResult>()
        fun submitList(list: List<UserSearchResult>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSearchUserName)
            val tvInitial: TextView = view.findViewById(R.id.tvSearchUserInitial)
            val ivPhoto: ImageView = view.findViewById(R.id.ivSearchUserPhoto)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_user_search_result, parent, false))
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
            
            holder.tvInitial.text = if (user.name.isNotEmpty()) user.name[0].uppercaseChar().toString() else "?"
            
            if (!user.photoUrl.isNullOrEmpty()) {
                holder.ivPhoto.visibility = View.VISIBLE
                Glide.with(holder.itemView.context).load(user.photoUrl).circleCrop().into(holder.ivPhoto)
            } else {
                holder.ivPhoto.visibility = View.GONE
            }
            
            holder.itemView.setOnClickListener { onClick(user) }
        }
        override fun getItemCount() = items.size
    }

    inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {
        private var items = listOf<AppMessage>()
        fun submitList(list: List<AppMessage>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = items[position]
            holder.b.tvMessageTitle.text = msg.title
            holder.b.tvMessageContent.text = if (msg.content.length > 80) msg.content.take(77) + "..." else msg.content
            holder.b.tvMessageTime.text = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date(msg.getTimestampMillis()))
            holder.itemView.setOnClickListener { 
                startActivity(Intent(requireContext(), MessageDetailActivity::class.java).apply {
                    putExtra("EXTRA_TITLE", msg.title); putExtra("EXTRA_CONTENT", msg.content); putExtra("EXTRA_TIME", msg.getTimestampMillis())
                })
            }
        }
        override fun getItemCount() = items.size
    }
}
