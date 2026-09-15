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
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.databinding.ActivitySearchUsersBinding
import com.naxor.app.databinding.ItemUserSearchResultBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SearchUsersActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchUsersBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: UserSearchAdapter
    private var allUsers = mutableListOf<UserSearchResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadAllUsers()
    }

    private fun setupRecyclerView() {
        adapter = UserSearchAdapter { user -> openChatWithUser(user) }
        binding.rvUserSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvUserSearchResults.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackSearchUsers.setOnClickListener { finish() }
        binding.etSearchUserAdd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterUsers(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadAllUsers() {
        binding.pbLoadingSearch.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val profilesSnap = db.collectionGroup("profile").get().await()
                val results = profilesSnap.documents.mapNotNull { doc ->
                    if (doc.id != "info") return@mapNotNull null
                    val name = doc.getString("name") ?: ""
                    val pathParts = doc.reference.path.split("/")
                    if (pathParts.size >= 2) {
                        val uid = pathParts[1]
                        if (uid == auth.currentUser?.uid) return@mapNotNull null
                        UserSearchResult(
                            uid, 
                            name, 
                            doc.getString("photoUrl"), 
                            doc.getString("businessTitle"),
                            doc.getString("businessLogoUrl")
                        )
                    } else null
                }
                allUsers.clear()
                allUsers.addAll(results)
                adapter.submitList(allUsers)
                binding.pbLoadingSearch.visibility = View.GONE
            } catch (e: Exception) {
                binding.pbLoadingSearch.visibility = View.GONE
                Toast.makeText(this@SearchUsersActivity, "Error al cargar usuarios", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterUsers(query: String) {
        val filtered = allUsers.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submitList(filtered)
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
        finish() // Cerrar búsqueda al abrir chat
    }

    data class UserSearchResult(val uid: String, val name: String, val photoUrl: String?, val businessTitle: String?, val businessLogoUrl: String?)

    inner class UserSearchAdapter(private val onClick: (UserSearchResult) -> Unit) : RecyclerView.Adapter<UserSearchAdapter.ViewHolder>() {
        private var items = listOf<UserSearchResult>()
        fun submitList(list: List<UserSearchResult>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemUserSearchResultBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemUserSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = items[position]
            val displayName = user.name
            holder.b.tvSearchUserName.text = displayName
            holder.b.tvSearchUserRole.text = user.businessTitle ?: "Comprador"
            
            // Color por rol
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
