package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.naxor.app.data.OrderModel
import com.naxor.app.databinding.ActivityDeliveriesBinding
import com.naxor.app.databinding.ItemOrderCardBinding
import java.util.*

class DeliveriesActivity : BaseActivity() {

    private lateinit var binding: ActivityDeliveriesBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var orderRegistration: ListenerRegistration? = null
    
    private lateinit var adapter: OrdersAdapter
    private var allOrders = mutableListOf<OrderModel>()
    private var currentFilter = "PENDING"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeliveriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        startOrdersListener()
    }

    private fun setupRecyclerView() {
        adapter = OrdersAdapter(
            onAction = { order -> handleOrderAction(order) },
            onChat = { order -> openChat(order) }
        )
        binding.rvDeliveries.layoutManager = LinearLayoutManager(this)
        binding.rvDeliveries.adapter = adapter
    }

    private fun setupListeners() {
        binding.toolbarDeliveries.setNavigationOnClickListener { finish() }

        binding.tabLayoutDeliveries.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilter = when (tab?.position) {
                    0 -> "PENDING"
                    1 -> "ACCEPTED" // O EN_ROUTE
                    2 -> "DELIVERED"
                    else -> "PENDING"
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun startOrdersListener() {
        val userId = auth.currentUser?.uid ?: return
        
        orderRegistration = db.collection("orders")
            .whereEqualTo("storeId", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                allOrders = snapshot.toObjects(OrderModel::class.java)
                applyFilter()
            }
    }

    private fun applyFilter() {
        val filtered = if (currentFilter == "ACCEPTED") {
            allOrders.filter { it.status == "ACCEPTED" || it.status == "EN_ROUTE" }
        } else {
            allOrders.filter { it.status == currentFilter }
        }
        
        adapter.submitList(filtered)
        binding.layoutEmptyDeliveries.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleOrderAction(order: OrderModel) {
        val nextStatus = when (order.status) {
            "PENDING" -> "ACCEPTED"
            "ACCEPTED" -> "EN_ROUTE"
            "EN_ROUTE" -> "DELIVERED"
            else -> return
        }

        if (order.status == "ACCEPTED" && order.deliveryLat != 0.0) {
            // Sugerir guiar al usuario
            AlertDialog.Builder(this)
                .setTitle("Iniciar Entrega")
                .setMessage("¿Deseas iniciar la ruta de entrega? Se abrirá Google Maps para guiarte.")
                .setPositiveButton("Sí, Guiar") { _, _ ->
                    updateStatus(order.id, "EN_ROUTE")
                    openNavigation(order.deliveryLat, order.deliveryLng)
                }
                .setNegativeButton("Solo marcar como En Ruta") { _, _ ->
                    updateStatus(order.id, "EN_ROUTE")
                }
                .show()
        } else {
            updateStatus(order.id, nextStatus)
        }
    }

    private fun updateStatus(orderId: String, status: String) {
        db.collection("orders").document(orderId)
            .update("status", status)
            .addOnSuccessListener {
                Toast.makeText(this, "Estado actualizado a $status", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openNavigation(lat: Double, lng: Double) {
        val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lng")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun openChat(order: OrderModel) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", order.id)
            putExtra("OTHER_USER_NAME", order.buyerName)
            putExtra("OTHER_USER_ID", order.buyerId)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        orderRegistration?.remove()
    }

    inner class OrdersAdapter(
        private val onAction: (OrderModel) -> Unit,
        private val onChat: (OrderModel) -> Unit
    ) : RecyclerView.Adapter<OrdersAdapter.ViewHolder>() {
        
        private var items = listOf<OrderModel>()

        fun submitList(newList: List<OrderModel>) {
            items = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemOrderCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemOrderCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val order = items[position]
            with(holder.b) {
                tvOrderBuyerName.text = order.buyerName
                tvOrderAddress.text = if (order.deliveryAddress.isNotEmpty()) "📍 ${order.deliveryAddress}" else "Entrega en tienda / Sin dirección"
                tvOrderItemsSummary.text = "${order.items.size} productos - Total: S/ ${String.format(Locale.US, "%.2f", order.totalAmount)}"
                tvOrderTotal.text = "S/ ${String.format(Locale.US, "%.2f", order.totalAmount + order.deliveryFee)}"
                
                tvOrderStatusBadge.text = order.status
                
                btnOrderAction.text = when (order.status) {
                    "PENDING" -> "Aceptar"
                    "ACCEPTED" -> "Enviar"
                    "EN_ROUTE" -> "Entregado"
                    else -> "Finalizado"
                }
                btnOrderAction.visibility = if (order.status == "DELIVERED" || order.status == "CANCELLED") View.GONE else View.VISIBLE

                btnOrderAction.setOnClickListener { onAction(order) }
                btnOrderChat.setOnClickListener { onChat(order) }
            }
        }

        override fun getItemCount() = items.size
    }
}
