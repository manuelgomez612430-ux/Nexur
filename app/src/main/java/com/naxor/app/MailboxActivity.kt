package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.naxor.app.data.AppMessage
import com.naxor.app.databinding.ActivityMailboxBinding
import com.naxor.app.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.*

class MailboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMailboxBinding
    private lateinit var adapter: MessageAdapter

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
        
        // Escuchamos mensajes globales
        FirebaseFirestore.getInstance().collection("app_messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                val globalMessages = snapshot?.toObjects(AppMessage::class.java) ?: emptyList()
                
                // Escuchamos mensajes privados del usuario
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("messages")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .addSnapshotListener { snapshotPriv, ePriv ->
                        if (ePriv != null) {
                            adapter.submitList(globalMessages)
                            return@addSnapshotListener
                        }
                        val privateMessages = snapshotPriv?.toObjects(AppMessage::class.java) ?: emptyList()
                        
                        // Combinamos y ordenamos por fecha
                        val allMessages = (globalMessages + privateMessages).sortedByDescending { it.timestamp?.toDate()?.time ?: 0L }
                        
                        adapter.submitList(allMessages)
                        binding.layoutEmptyMailbox.visibility = if (allMessages.isEmpty()) View.VISIBLE else View.GONE
                    }
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
            
            // Mostrar solo una vista previa si es muy largo
            val previewText = if (msg.content.length > 80) msg.content.take(77) + "..." else msg.content
            holder.b.tvMessageContent.text = previewText
            
            val date = msg.timestamp?.toDate() ?: Date()
            val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
            holder.b.tvMessageTime.text = sdf.format(date)

            // Acción para abrir el mensaje completo
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
            putExtra("EXTRA_TIME", msg.timestamp?.toDate()?.time ?: 0L)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }
}
