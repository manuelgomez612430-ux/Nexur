package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.naxor.app.data.AppMessage
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

        setupRecyclerView()
        setupListeners()
        loadMessages()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackMailbox.setOnClickListener { finish() }
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

                // Obtener Privados
                val privateSnap = FirebaseFirestore.getInstance().collection("users").document(userId).collection("messages").get().await()
                val privateList = privateSnap.documents.mapNotNull { doc ->
                    val msg = doc.toObject(AppMessage::class.java)
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
