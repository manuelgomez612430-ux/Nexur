package com.gomezmart.app.fragment

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
import androidx.appcompat.app.AlertDialog
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
import com.gomezmart.app.*
import com.gomezmart.app.data.AppMessage
import com.gomezmart.app.data.DirectChat
import com.gomezmart.app.databinding.FragmentBuyerChatsBinding
import com.gomezmart.app.databinding.ItemMessageBinding
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
    private lateinit var friendsListAdapter: FriendsListAdapter
    private val registrations = mutableListOf<ListenerRegistration>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Manejar insets para que la cabecera no sea tapada por la barra de estado
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.headerBuyerChats, applyTop = true, applyBottom = false)
        base?.applyWindowInsetsHeight(binding.topHeaderBackgroundBuyerChats, applyTop = true, applyBottom = false)

        if (activity is MailboxActivity || arguments?.getBoolean("SHOW_BACK_BUTTON") == true) {
            binding.btnBackBuyerChats.visibility = View.VISIBLE
            binding.btnBackBuyerChats.setOnClickListener { activity?.finish() }
        } else {
            binding.btnBackBuyerChats.visibility = View.GONE
        }

        val isGuest = base?.isGuestUser() == true
        if (isGuest) {
            binding.cardGuestInfoBanner.visibility = View.VISIBLE
            binding.btnGuestLoginBanner.setOnClickListener {
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                activity?.finish()
            }
        } else {
            binding.cardGuestInfoBanner.visibility = View.GONE
        }

        setupRecyclerViews()
        setupListeners()
        loadActiveChats()
    }

    private fun setupRecyclerViews() {
        userSearchAdapter = UserSearchAdapter { user -> openChatWithUser(user) }
        activeChatsAdapter = ActiveChatsAdapter(
            onClick = { chat -> openExistingChat(chat) },
            onLongClick = { chat -> showDeleteChatDialog(chat) }
        )
        friendsListAdapter = FriendsListAdapter(
            onFriendClick = { item ->
                if (!item.isPendingRequest) {
                    openChatWithUser(
                        UserSearchResult(
                            uid = item.uid,
                            name = item.name,
                            photoUrl = item.photoUrl,
                            businessTitle = item.businessTitle,
                            businessLogoUrl = null
                        )
                    )
                }
            },
            onFriendLongClick = { item ->
                if (!item.isPendingRequest) showFriendOptionsDialog(item)
            },
            onAcceptRequest = { item -> acceptFriendRequestFromChats(item) },
            onRejectRequest = { item -> rejectFriendRequestFromChats(item) }
        )

        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChats.adapter = activeChatsAdapter
    }

    private enum class ChatsTab { MAIN, FRIENDS, MESSAGE_REQUESTS }
    private var currentChatsTab = ChatsTab.MAIN
    private var allActiveChats = listOf<DirectChat>()
    private var allMessageRequests = listOf<DirectChat>()
    private var allFriends = listOf<com.gomezmart.app.data.FriendProfile>()
    private var incomingFriendRequests = listOf<com.gomezmart.app.data.FriendRequest>()
    private val blockedUids = mutableSetOf<String>()

    private fun setupListeners() {
        binding.btnAddUserChat.setOnClickListener {
            startActivity(Intent(requireContext(), SearchUsersActivity::class.java))
        }

        binding.btnEmptyOpenCommunity.setOnClickListener {
            startActivity(Intent(requireContext(), SearchUsersActivity::class.java))
        }

        binding.chipGroupChatsTabs.setOnCheckedStateChangeListener { _, checkedIds ->
            currentChatsTab = when (checkedIds.firstOrNull()) {
                R.id.chipChatsFriends -> ChatsTab.FRIENDS
                R.id.chipChatsMessageRequests -> ChatsTab.MESSAGE_REQUESTS
                else -> ChatsTab.MAIN
            }
            binding.etSearchUserChat.hint = when (currentChatsTab) {
                ChatsTab.FRIENDS -> "Buscar en tus amigos..."
                ChatsTab.MESSAGE_REQUESTS -> "Buscar en solicitudes..."
                ChatsTab.MAIN -> "Buscar en tus chats..."
            }
            applyCurrentChatsFilter()
        }

        binding.etSearchUserChat.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyCurrentChatsFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyCurrentChatsFilter() {
        if (_binding == null) return
        val myUid = auth.currentUser?.uid ?: "GUEST"
        val query = binding.etSearchUserChat.text.toString().trim()

        if (currentChatsTab == ChatsTab.FRIENDS) {
            val reqItems = incomingFriendRequests
                .filter { !blockedUids.contains(it.fromUid) }
                .map { req ->
                    FriendListItem(
                        uid = req.fromUid,
                        name = req.fromName.ifBlank { "Usuario" },
                        photoUrl = req.fromPhoto,
                        businessTitle = req.fromTitle ?: "Comprador",
                        note = req.note,
                        isPendingRequest = true
                    )
                }
            val friendItems = allFriends
                .filter { !blockedUids.contains(it.uid) }
                .sortedBy { it.name.lowercase() }
                .map { f ->
                    FriendListItem(
                        uid = f.uid,
                        name = f.name,
                        photoUrl = f.photoUrl,
                        businessTitle = f.businessTitle ?: "Comprador",
                        note = "",
                        isPendingRequest = false
                    )
                }

            val combined = (reqItems + friendItems).filter {
                query.isEmpty() || it.name.contains(query, ignoreCase = true)
            }

            if (binding.rvChats.adapter !== friendsListAdapter) {
                binding.rvChats.adapter = friendsListAdapter
            }
            friendsListAdapter.submitList(combined)

            val isEmpty = combined.isEmpty()
            binding.layoutEmptyChats.visibility = if (isEmpty) View.VISIBLE else View.GONE
            if (isEmpty) {
                binding.tvEmptyChatsEmoji.text = "🤝"
                binding.tvEmptyChatsTitle.text = if (query.isNotEmpty()) "No encontramos ese amigo" else "Aún no tienes amigos en tu lista"
                binding.tvEmptyChatsSubtitle.text = if (query.isNotEmpty()) {
                    "Prueba buscando otro nombre."
                } else {
                    "Conecta con personas en Comunidad (+) o escaneando su Código QR para verlas aquí."
                }
                binding.btnEmptyOpenCommunity.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
            } else {
                binding.btnEmptyOpenCommunity.visibility = View.GONE
            }
            return
        }

        binding.btnEmptyOpenCommunity.visibility = View.GONE
        val sourceList = (if (currentChatsTab == ChatsTab.MAIN) allActiveChats else allMessageRequests)
            .filter { chat ->
                val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
                !blockedUids.contains(otherUid)
            }
        val filtered = if (query.isEmpty()) {
            sourceList
        } else {
            sourceList.filter { chat ->
                val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
                val name = chat.participantNames[otherUid] ?: ""
                name.contains(query, ignoreCase = true)
            }
        }
        if (binding.rvChats.adapter !== activeChatsAdapter) {
            binding.rvChats.adapter = activeChatsAdapter
        }
        activeChatsAdapter.submitList(filtered)

        val isEmpty = filtered.isEmpty()
        binding.layoutEmptyChats.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            if (currentChatsTab == ChatsTab.MESSAGE_REQUESTS) {
                binding.tvEmptyChatsEmoji.text = "📩"
                binding.tvEmptyChatsTitle.text = "Sin solicitudes de mensaje"
                binding.tvEmptyChatsSubtitle.text = "Cuando alguien que no es tu amigo te envíe un mensaje, aparecerá aquí para que aceptes o rechaces."
            } else {
                binding.tvEmptyChatsEmoji.text = "💬"
                binding.tvEmptyChatsTitle.text = if (query.isNotEmpty()) "Sin resultados" else "Aún no tienes chats"
                binding.tvEmptyChatsSubtitle.text = if (query.isNotEmpty()) "Prueba buscando otro nombre." else "Presiona el botón '+' para buscar otros usuarios en línea y empezar a chatear."
            }
        }
    }

    private fun loadActiveChats() {
        val myUid = auth.currentUser?.uid ?: "GUEST"

        // 1. Asesor IA
        val aiChat = DirectChat(
            id = "AI_ASSISTANT_CHAT",
            participants = listOf(myUid, "AI_ASSISTANT"),
            participantNames = mapOf("AI_ASSISTANT" to "Asesor IA GoMezMart", myUid to (auth.currentUser?.displayName ?: "Usuario")),
            participantPhotos = mapOf("AI_ASSISTANT" to ""),
            participantTitles = mapOf("AI_ASSISTANT" to "🤖 Asistente Financiero"),
            participantBusinessLogos = mapOf("AI_ASSISTANT" to ""),
            lastMessage = "Toca aquí para conversar con tu Asesor IA sobre el negocio...",
            lastMessageTime = System.currentTimeMillis() + 999999999L
        )

        // 2. Sistema / Desarrollador (Canal informativo de anuncios)
        val systemChat = DirectChat(
            id = "SYSTEM_ANNOUNCEMENTS_CHAT",
            participants = listOf(myUid, "SYSTEM_NOTIFICATIONS"),
            participantNames = mapOf("SYSTEM_NOTIFICATIONS" to "Sistema GoMezMart 📢", myUid to (auth.currentUser?.displayName ?: "Usuario")),
            participantPhotos = mapOf("SYSTEM_NOTIFICATIONS" to ""),
            participantTitles = mapOf("SYSTEM_NOTIFICATIONS" to "📢 Comunicados del Sistema"),
            participantBusinessLogos = mapOf("SYSTEM_NOTIFICATIONS" to ""),
            lastMessage = "Avisos, comunicados oficiales e información del sistema.",
            lastMessageTime = System.currentTimeMillis() + 999999998L
        )

        val initialList = listOf(aiChat, systemChat)
        allActiveChats = initialList
        activeChatsAdapter.submitList(initialList)
        binding.rvChats.adapter = activeChatsAdapter
        binding.layoutEmptyChats.visibility = View.GONE

        if (myUid.isNotEmpty() && myUid != "GUEST") {
            val blockedReg = db.collection("users").document(myUid).collection("blocked_users")
                .addSnapshotListener { snap, _ ->
                    if (_binding == null) return@addSnapshotListener
                    blockedUids.clear()
                    snap?.documents?.forEach { blockedUids.add(it.id) }
                    applyCurrentChatsFilter()
                }
            registrations.add(blockedReg)

            val friendsReg = db.collection("users").document(myUid).collection("friends")
                .addSnapshotListener { snap, _ ->
                    if (_binding == null) return@addSnapshotListener
                    allFriends = snap?.documents?.mapNotNull { doc ->
                        val name = doc.getString("name") ?: return@mapNotNull null
                        com.gomezmart.app.data.FriendProfile(
                            uid = doc.id,
                            name = name,
                            photoUrl = doc.getString("photoUrl"),
                            businessTitle = doc.getString("businessTitle") ?: "Comprador",
                            since = doc.getLong("since") ?: 0L
                        )
                    } ?: emptyList()
                    updateFriendsChipText()
                    if (currentChatsTab == ChatsTab.FRIENDS) applyCurrentChatsFilter()
                }
            registrations.add(friendsReg)

            val friendReqReg = db.collection("friend_requests")
                .whereEqualTo("toUid", myUid)
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snap, _ ->
                    if (_binding == null) return@addSnapshotListener
                    incomingFriendRequests = snap?.documents?.mapNotNull { doc ->
                        doc.toObject(com.gomezmart.app.data.FriendRequest::class.java)?.copy(id = doc.id)
                    } ?: emptyList()
                    updateFriendsChipText()
                    if (currentChatsTab == ChatsTab.FRIENDS) applyCurrentChatsFilter()
                }
            registrations.add(friendReqReg)

            // Sincronizar automáticamente si una solicitud que envié ya fue aceptada por el otro usuario
            val outgoingAcceptedReg = db.collection("friend_requests")
                .whereEqualTo("fromUid", myUid)
                .whereEqualTo("status", "ACCEPTED")
                .addSnapshotListener { snap, _ ->
                    if (_binding == null) return@addSnapshotListener
                    snap?.documents?.forEach { doc ->
                        val toUid = doc.getString("toUid") ?: return@forEach
                        if (allFriends.none { it.uid == toUid }) {
                            db.collection("users").document(myUid).collection("friends").document(toUid)
                                .set(
                                    mapOf(
                                        "uid" to toUid,
                                        "name" to (doc.getString("toName") ?: "Amigo"),
                                        "photoUrl" to (doc.getString("toPhoto") ?: ""),
                                        "businessTitle" to (doc.getString("toTitle") ?: "Comprador"),
                                        "since" to System.currentTimeMillis()
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                        }
                    }
                }
            registrations.add(outgoingAcceptedReg)

            val reg = db.collection("direct_chats")
                .whereArrayContains("participants", myUid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, _ ->
                    if (_binding == null) return@addSnapshotListener
                    val chats = snapshot?.documents?.mapNotNull { doc ->
                        val chat = doc.toObject(DirectChat::class.java)
                        chat?.copy(id = doc.id)
                    } ?: emptyList()

                    val visibleChats = chats.filter { chat ->
                        val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
                        if (blockedUids.contains(otherUid)) return@filter false
                        if (chat.requestStatus == "REJECTED") return@filter false
                        val clearedAt = chat.clearedUntil[myUid] ?: 0L
                        val lastTs = when (val t = chat.lastMessageTime) {
                            is Timestamp -> t.toDate().time
                            is Long -> t
                            else -> 0L
                        }
                        lastTs > clearedAt
                    }

                    val incomingRequests = visibleChats.filter {
                        it.requestStatus == "PENDING" && it.requestedBy.isNotEmpty() && it.requestedBy != myUid
                    }.sortedByDescending { chat ->
                        when (val t = chat.lastMessageTime) {
                            is Timestamp -> t.toDate().time
                            is Long -> t
                            else -> 0L
                        }
                    }

                    val mainVisibleChats = visibleChats.filterNot {
                        it.requestStatus == "PENDING" && it.requestedBy.isNotEmpty() && it.requestedBy != myUid
                    }

                    val fullList = mutableListOf<DirectChat>()
                    fullList.add(aiChat)
                    fullList.add(systemChat)
                    fullList.addAll(mainVisibleChats)

                    allActiveChats = fullList.sortedByDescending { chat ->
                        when (val t = chat.lastMessageTime) {
                            is Timestamp -> t.toDate().time
                            is Long -> t
                            else -> 0L
                        }
                    }
                    allMessageRequests = incomingRequests
                    binding.chipChatsMessageRequests.text = "📩 Solicitudes (${incomingRequests.size})"

                    applyCurrentChatsFilter()
                }
            registrations.add(reg)
        }
    }

    private fun openChatWithUser(user: UserSearchResult) {
        val myUid = auth.currentUser?.uid ?: return
        val chatId = if (myUid < user.uid) "${myUid}_${user.uid}" else "${user.uid}_${myUid}"
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId); putExtra("OTHER_USER_NAME", user.name)
            putExtra("OTHER_USER_ID", user.uid); putExtra("OTHER_USER_PHOTO", user.photoUrl)
            putExtra("OTHER_USER_TITLE", user.businessTitle)
            putExtra("OTHER_USER_BIZ_LOGO", user.businessLogoUrl)
            putExtra("IS_DIRECT", true)
        }
        (activity as? BaseActivity)?.startActivityWithFade(intent)
    }

    private fun openExistingChat(chat: DirectChat) {
        val base = activity as? BaseActivity
        if (chat.id == "AI_ASSISTANT_CHAT") {
            if (base?.requireLoginForGuest("usar el Asesor IA") == true) return
            val intent = Intent(requireContext(), AiChatActivity::class.java)
            base?.startActivityWithFade(intent)
            return
        }

        val myUid = auth.currentUser?.uid ?: "GUEST"
        val otherUid = chat.participants.firstOrNull { it != myUid } ?: chat.participants.lastOrNull() ?: ""
        val otherName = chat.participantNames[otherUid] ?: if (otherUid == "SYSTEM_NOTIFICATIONS") "Sistema GoMezMart 📢" else "Chat"
        val otherPhoto = chat.participantPhotos[otherUid] ?: ""
        val otherTitle = chat.participantTitles[otherUid] ?: if (otherUid == "SYSTEM_NOTIFICATIONS") "📢 Comunicados del Sistema" else ""
        val otherBizLogo = chat.participantBusinessLogos[otherUid] ?: ""

        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chat.id)
            putExtra("OTHER_USER_NAME", otherName)
            putExtra("OTHER_USER_ID", otherUid)
            putExtra("OTHER_USER_PHOTO", otherPhoto)
            putExtra("OTHER_USER_TITLE", otherTitle)
            putExtra("OTHER_USER_BIZ_LOGO", otherBizLogo)
            putExtra("IS_DIRECT", true)
            if (chat.id == "SYSTEM_ANNOUNCEMENTS_CHAT" || otherUid == "SYSTEM_NOTIFICATIONS") {
                putExtra("IS_READ_ONLY_SYSTEM", true)
            }
        }
        (activity as? BaseActivity)?.startActivityWithFade(intent)
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

    private fun updateFriendsChipText() {
        if (_binding == null) return
        val friendsCount = allFriends.count { !blockedUids.contains(it.uid) }
        val reqCount = incomingFriendRequests.count { !blockedUids.contains(it.fromUid) }
        binding.chipChatsFriends.text = if (reqCount > 0) {
            "🤝 Amigos ($friendsCount) · $reqCount nueva"
        } else {
            "🤝 Amigos ($friendsCount)"
        }
    }

    private fun getMyBasicProfile(): Triple<String, String?, String?> {
        val prefs = requireContext().getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
        val myUid = auth.currentUser?.uid ?: ""
        val rawName = prefs.getString("user_full_name", auth.currentUser?.displayName)
        val myName = com.gomezmart.app.util.UserNameGenerator.getOrGenerateName(requireContext(), rawName, myUid)
        val myPhoto = prefs.getString("user_photo_url", auth.currentUser?.photoUrl?.toString())
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
        val bizName = bizPrefs.getString("business_name", "")
        val myTitle = if (!bizName.isNullOrBlank()) "Dueño de $bizName" else "Comprador"
        return Triple(myName, myPhoto, myTitle)
    }

    private fun acceptFriendRequestFromChats(item: FriendListItem) {
        val myUid = auth.currentUser?.uid ?: return
        val (myName, myPhoto, myTitle) = getMyBasicProfile()
        lifecycleScope.launch {
            com.gomezmart.app.data.SocialRepository.acceptFriendRequest(
                db = db,
                myUid = myUid,
                myName = myName,
                myPhoto = myPhoto,
                myTitle = myTitle,
                otherUid = item.uid,
                otherName = item.name,
                otherPhoto = item.photoUrl,
                otherTitle = item.businessTitle
            ).onSuccess {
                Toast.makeText(requireContext(), "¡Ahora son amigos con ${item.name}! 🤝", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(requireContext(), "Error al aceptar solicitud", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rejectFriendRequestFromChats(item: FriendListItem) {
        val myUid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            com.gomezmart.app.data.SocialRepository.rejectFriendRequest(db, myUid, item.uid)
                .onSuccess {
                    Toast.makeText(requireContext(), "Solicitud rechazada", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(requireContext(), "Error al rechazar solicitud", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showFriendOptionsDialog(item: FriendListItem) {
        val options = arrayOf("Enviar mensaje 💬", "Eliminar de amigos")
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which ->
                if (which == 0) {
                    openChatWithUser(
                        UserSearchResult(item.uid, item.name, item.photoUrl, item.businessTitle, null)
                    )
                } else {
                    val myUid = auth.currentUser?.uid ?: return@setItems
                    lifecycleScope.launch {
                        com.gomezmart.app.data.SocialRepository.removeFriend(db, myUid, item.uid)
                            .onSuccess {
                                Toast.makeText(requireContext(), "${item.name} eliminado de tus amigos", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    data class FriendListItem(
        val uid: String,
        val name: String,
        val photoUrl: String?,
        val businessTitle: String?,
        val note: String = "",
        val isPendingRequest: Boolean = false
    )

    inner class FriendsListAdapter(
        private val onFriendClick: (FriendListItem) -> Unit,
        private val onFriendLongClick: (FriendListItem) -> Unit,
        private val onAcceptRequest: (FriendListItem) -> Unit,
        private val onRejectRequest: (FriendListItem) -> Unit
    ) : RecyclerView.Adapter<FriendsListAdapter.ViewHolder>() {
        private var items = listOf<FriendListItem>()
        fun submitList(list: List<FriendListItem>) { items = list; notifyDataSetChanged() }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSearchUserName)
            val tvRole: TextView = view.findViewById(R.id.tvSearchUserRole)
            val tvNote: TextView = view.findViewById(R.id.tvSearchUserRequestNote)
            val tvInitial: TextView = view.findViewById(R.id.tvSearchUserInitial)
            val ivPhoto: ImageView = view.findViewById(R.id.ivSearchUserPhoto)
            val ivArrow: ImageView = view.findViewById(R.id.ivSearchUserArrow)
            val tvBadge: TextView = view.findViewById(R.id.tvSearchUserActionBadge)
            val layoutReqActions: View = view.findViewById(R.id.layoutFriendRequestRowActions)
            val btnAccept: View = view.findViewById(R.id.btnAcceptFriendRow)
            val btnReject: View = view.findViewById(R.id.btnRejectFriendRow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_user_search_result, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            val title = item.businessTitle ?: "Comprador"
            holder.tvRole.text = if (item.isPendingRequest) "📩 Solicitud de amistad" else title
            when {
                item.isPendingRequest -> {
                    holder.tvRole.setTextColor(Color.parseColor("#B45309"))
                    holder.tvRole.setBackgroundResource(R.drawable.bg_pill_amber_light)
                }
                title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> {
                    holder.tvRole.setTextColor(Color.parseColor("#B45309"))
                    holder.tvRole.setBackgroundResource(R.drawable.bg_pill_amber_light)
                }
                title.contains("Colaborador", ignoreCase = true) -> {
                    holder.tvRole.setTextColor(Color.parseColor("#6D28D9"))
                    holder.tvRole.setBackgroundResource(R.drawable.bg_pill_purple_light)
                }
                else -> {
                    holder.tvRole.setTextColor(Color.parseColor("#047857"))
                    holder.tvRole.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                }
            }

            holder.tvInitial.text = if (item.name.isNotEmpty()) item.name[0].uppercaseChar().toString() else "?"
            if (!item.photoUrl.isNullOrEmpty()) {
                holder.ivPhoto.visibility = View.VISIBLE
                Glide.with(holder.itemView.context).load(item.photoUrl).circleCrop().into(holder.ivPhoto)
            } else {
                holder.ivPhoto.visibility = View.GONE
            }

            holder.ivArrow.visibility = View.GONE
            if (item.isPendingRequest) {
                holder.layoutReqActions.visibility = View.VISIBLE
                holder.tvBadge.visibility = View.GONE
                if (item.note.isNotBlank()) {
                    holder.tvNote.visibility = View.VISIBLE
                    holder.tvNote.text = "💬 \"${item.note}\""
                } else {
                    holder.tvNote.visibility = View.GONE
                }
                holder.btnAccept.setOnClickListener { onAcceptRequest(item) }
                holder.btnReject.setOnClickListener { onRejectRequest(item) }
            } else {
                holder.layoutReqActions.visibility = View.GONE
                holder.tvNote.visibility = View.GONE
                holder.tvBadge.visibility = View.VISIBLE
                holder.tvBadge.text = "Mensaje 💬"
                holder.tvBadge.setTextColor(Color.parseColor("#6D28D9"))
                holder.tvBadge.setBackgroundResource(R.drawable.bg_pill_purple_light)
                holder.tvBadge.setOnClickListener { onFriendClick(item) }
            }

            holder.itemView.setOnClickListener { onFriendClick(item) }
            holder.itemView.setOnLongClickListener {
                onFriendLongClick(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    data class UserSearchResult(val uid: String, val name: String, val photoUrl: String?, val businessTitle: String?, val businessLogoUrl: String?)

    inner class ActiveChatsAdapter(
        private val onClick: (DirectChat) -> Unit,
        private val onLongClick: (DirectChat) -> Unit
    ) : RecyclerView.Adapter<ActiveChatsAdapter.ViewHolder>() {
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
            val myUid = auth.currentUser?.uid ?: "GUEST"
            val otherUid = chat.participants.firstOrNull { it != myUid } ?: chat.participants.lastOrNull() ?: ""
            val name = chat.participantNames[otherUid] ?: when (otherUid) {
                "AI_ASSISTANT" -> "Asesor IA GoMezMart"
                "SYSTEM_NOTIFICATIONS" -> "Sistema GoMezMart 📢"
                else -> "Usuario"
            }
            val title = chat.participantTitles[otherUid] ?: when (otherUid) {
                "AI_ASSISTANT" -> "🤖 Asistente Financiero"
                "SYSTEM_NOTIFICATIONS" -> "📢 Comunicados del Sistema"
                else -> "Usuario"
            }
            val bizLogo = chat.participantBusinessLogos[otherUid]
            val unread = chat.unreadCount[myUid] ?: 0
            
            holder.tvName.text = name
            holder.tvLastMsg.text = if (chat.requestStatus == "PENDING") {
                if (chat.requestedBy == myUid) "⏳ Solicitud enviada: ${chat.lastMessage}"
                else "📩 Solicitud: ${chat.lastMessage}"
            } else {
                chat.lastMessage
            }

            val roleTv: TextView? = holder.itemView.findViewById(R.id.tvChatUserRole)
            if (roleTv != null) {
                roleTv.text = title
                val color = when {
                    title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> Color.parseColor("#B45309")
                    title.contains("Colaborador", ignoreCase = true) -> Color.parseColor("#6D28D9")
                    title.contains("Comprador", ignoreCase = true) -> Color.parseColor("#047857")
                    title.contains("Sistema", ignoreCase = true) || title.contains("Comunicados", ignoreCase = true) -> Color.parseColor("#7C3AED")
                    title.contains("Asistente", ignoreCase = true) -> Color.parseColor("#2563EB")
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
            
            val avatarContainer: View? = holder.itemView.findViewById(R.id.layoutChatListAvatarContainer)
            if (otherUid == "AI_ASSISTANT" || otherUid == "SYSTEM_NOTIFICATIONS") {
                avatarContainer?.visibility = View.GONE
            } else {
                avatarContainer?.visibility = View.VISIBLE
                
                val initial = if (name.isNotEmpty()) name[0].uppercaseChar().toString() else "?"
                holder.tvInitial.text = initial
                
                val photo = chat.participantPhotos[otherUid]
                if (!photo.isNullOrEmpty()) {
                    holder.ivPhoto.visibility = View.VISIBLE
                    Glide.with(holder.itemView.context).load(photo).circleCrop().into(holder.ivPhoto)
                } else {
                    holder.ivPhoto.visibility = View.GONE
                }
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
            holder.itemView.setOnLongClickListener {
                onLongClick(chat)
                true
            }
        }
        override fun getItemCount() = items.size
    }

    private fun showDeleteChatDialog(chat: DirectChat) {
        val myUid = auth.currentUser?.uid ?: return
        val otherUid = chat.participants.firstOrNull { it != myUid } ?: ""
        val otherName = chat.participantNames[otherUid] ?: "este usuario"

        val options = arrayOf("Eliminar chat y usuario de mi lista")
        AlertDialog.Builder(requireContext())
            .setTitle("Opciones de Chat")
            .setItems(options) { _, which ->
                if (which == 0) {
                    deleteChat(chat.id)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteChat(chatId: String) {
        val myUid = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Marcar como borrado local para este usuario
                val chatRef = db.collection("direct_chats").document(chatId)
                chatRef.update("clearedUntil.$myUid", now).await()
                
                // 2. Limpieza física de mensajes que AMBOS participantes hayan borrado
                cleanupPhysicalMessages(chatId, myUid, now)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Chat eliminado de tu lista", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al ocultar chat", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun cleanupPhysicalMessages(chatId: String, myUid: String, myClearedAt: Long) {
        val chatRef = db.collection("direct_chats").document(chatId)
        val chatDoc = chatRef.get().await()
        
        if (chatDoc.exists()) {
            val clearedMap = chatDoc.get("clearedUntil") as? Map<String, Long> ?: emptyMap()
            val participants = chatDoc.get("participants") as? List<String> ?: emptyList()
            val otherUid = participants.find { it != myUid } ?: ""
            val otherClearedAt = clearedMap[otherUid] ?: 0L
            
            val threshold = if (otherClearedAt > 0) minOf(myClearedAt, otherClearedAt) else 0L
            
            if (threshold > 0) {
                val messagesSnap = chatRef.collection("messages").get().await()
                messagesSnap.documents.forEach { doc ->
                    val tsObj = doc.get("timestamp")
                    val ts = when (tsObj) {
                        is Timestamp -> tsObj.toDate().time
                        is Long -> tsObj
                        else -> 0L
                    }
                    if (ts <= threshold && ts > 0) {
                        doc.reference.delete()
                    }
                }
            }
        }
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
                val title = user.businessTitle ?: "Comprador"
                roleTv.text = title
                when {
                    title.contains("Dueño", ignoreCase = true) || title.contains("Gestionando", ignoreCase = true) -> {
                        roleTv.setTextColor(Color.parseColor("#B45309"))
                        roleTv.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    }
                    title.contains("Colaborador", ignoreCase = true) -> {
                        roleTv.setTextColor(Color.parseColor("#6D28D9"))
                        roleTv.setBackgroundResource(R.drawable.bg_pill_purple_light)
                    }
                    else -> {
                        roleTv.setTextColor(Color.parseColor("#047857"))
                        roleTv.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                    }
                }
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
