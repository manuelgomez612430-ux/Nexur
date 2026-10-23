package com.gomezmart.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.gomezmart.app.data.OrderModel
import com.gomezmart.app.databinding.ActivityDeliveriesBinding
import com.gomezmart.app.databinding.ItemOrderCardBinding
import com.gomezmart.app.databinding.ItemOrderProductRowBinding
import kotlinx.coroutines.*
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

        // Manejar insets para evitar que la cabecera sea tapada por la barra de estado
        applyWindowInsets(binding.appBarDeliveries, applyTop = true, applyBottom = false)

        setupRecyclerView()
        setupListeners()
        startOrdersListener()
        
        // Pedir permisos de ubicación si no los tiene, ya que los necesitará el Service
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        }
    }

    private fun setupRecyclerView() {
        adapter = OrdersAdapter(
            onAction = { order -> handleOrderAction(order) },
            onChat = { order -> openChat(order) },
            onNavigate = { order -> openNavigation(order.deliveryLat, order.deliveryLng, order.deliveryAddress) },
            onCancel = { order -> showCancelConfirmation(order) }
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
                
                allOrders = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(OrderModel::class.java)?.copy(id = doc.id)
                }.toMutableList()
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
        val myUid = auth.currentUser?.uid ?: return
        
        when (order.status) {
            "PENDING" -> {
                updateStatus(order.id, "ACCEPTED") {
                    notifyBuyerOrderAccepted(order)
                }
            }
            "ACCEPTED" -> {
                val updates = mapOf(
                    "status" to "EN_ROUTE",
                    "deliveryPersonId" to myUid
                )
                updateStatusWithFields(order.id, updates) {
                    notifyBuyerOrderEnRoute(order)
                    // El Service detectará el cambio en Firestore e iniciará el rastreo automáticamente
                    if (order.deliveryLat != 0.0 || order.deliveryAddress.isNotEmpty()) {
                        openNavigation(order.deliveryLat, order.deliveryLng, order.deliveryAddress)
                    }
                }
            }
            "EN_ROUTE" -> {
                updateStatus(order.id, "DELIVERED")
            }
        }
    }

    private fun updateStatusWithFields(orderId: String, updates: Map<String, Any>, onSuccess: (() -> Unit)? = null) {
        if (orderId.isEmpty()) return
        val loading = AlertDialog.Builder(this).setMessage("Actualizando pedido...").setCancelable(false).show()
        
        db.collection("orders").document(orderId)
            .update(updates)
            .addOnSuccessListener {
                loading.dismiss()
                val status = updates["status"] as String
                val index = allOrders.indexOfFirst { it.id == orderId }
                if (index != -1) {
                    allOrders[index] = allOrders[index].copy(status = status)
                    applyFilter()
                }
                Toast.makeText(this, "Pedido actualizado con éxito ✅", Toast.LENGTH_SHORT).show()
                onSuccess?.invoke()
            }
            .addOnFailureListener { e ->
                loading.dismiss()
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun notifyBuyerOrderAccepted(order: OrderModel) {
        val buyerId = order.buyerId
        if (buyerId.isEmpty()) return

        val notification = mapOf(
            "title" to "¡Pedido Aceptado! ✅",
            "content" to "Tu pedido en ${order.storeName} ha sido aceptado y se está preparando.",
            "timestamp" to FieldValue.serverTimestamp(),
            "type" to "SYSTEM",
            "senderId" to (auth.currentUser?.uid ?: "system")
        )

        db.collection("users").document(buyerId).collection("messages").add(notification)
    }

    private fun notifyBuyerOrderEnRoute(order: OrderModel) {
        val buyerId = order.buyerId
        if (buyerId.isEmpty()) return

        val notification = mapOf(
            "title" to "¡Pedido en Camino! 🛵",
            "content" to "Tu pedido de ${order.storeName} ya salió y está en camino a tu ubicación.",
            "timestamp" to FieldValue.serverTimestamp(),
            "type" to "SYSTEM",
            "senderId" to (auth.currentUser?.uid ?: "system")
        )

        db.collection("users").document(buyerId).collection("messages").add(notification)
    }

    private fun notifyBuyerOrderCancelled(order: OrderModel) {
        val buyerId = order.buyerId
        if (buyerId.isEmpty()) return

        val notification = mapOf(
            "title" to "Pedido Cancelado 🚫",
            "content" to "Lo sentimos, tu pedido en ${order.storeName} ha sido cancelado.",
            "timestamp" to FieldValue.serverTimestamp(),
            "type" to "SYSTEM",
            "senderId" to (auth.currentUser?.uid ?: "system")
        )

        db.collection("users").document(buyerId).collection("messages").add(notification)
    }

    private fun updateStatus(orderId: String, status: String, onSuccess: (() -> Unit)? = null) {
        updateStatusWithFields(orderId, mapOf("status" to status), onSuccess)
    }

    private fun openNavigation(lat: Double, lng: Double, address: String) {
        val gmmIntentUri = if (lat != 0.0) {
            Uri.parse("google.navigation:q=$lat,$lng")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        }
        
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val webUri = if (lat != 0.0) {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
            } else {
                Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(address)}")
            }
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun openChat(order: OrderModel) {
        val myUid = auth.currentUser?.uid ?: return
        val buyerId = order.buyerId
        if (buyerId.isEmpty()) return
        
        val directChatId = if (myUid < buyerId) "${myUid}_$buyerId" else "${buyerId}_$myUid"
        
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", directChatId)
            putExtra("OTHER_USER_NAME", order.buyerName)
            putExtra("OTHER_USER_ID", buyerId)
            putExtra("IS_DIRECT", true)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        orderRegistration?.remove()
    }

    inner class OrdersAdapter(
        private val onAction: (OrderModel) -> Unit,
        private val onChat: (OrderModel) -> Unit,
        private val onNavigate: (OrderModel) -> Unit,
        private val onCancel: (OrderModel) -> Unit
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
                
                val rawAddress = order.deliveryAddress
                tvOrderAddress.text = when {
                    rawAddress.isEmpty() -> "📍 Entrega en tienda / Sin dirección"
                    rawAddress.contains("Ubicación actual") && order.deliveryLat != 0.0 -> "📍 Ubicación fijada por GPS"
                    else -> "📍 $rawAddress"
                }
                
                if (order.deliveryReference.isNotEmpty()) {
                    tvOrderReference.visibility = View.VISIBLE
                    tvOrderReference.text = "Ref: ${order.deliveryReference}"
                } else {
                    tvOrderReference.visibility = View.GONE
                }

                layoutOrderItemsList.removeAllViews()
                order.items.forEach { item ->
                    val itemBinding = ItemOrderProductRowBinding.inflate(LayoutInflater.from(root.context), layoutOrderItemsList, false)
                    itemBinding.tvProductName.text = "${item.productName} (x${item.quantity})"
                    itemBinding.tvProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", item.price)}"
                    layoutOrderItemsList.addView(itemBinding.root)
                }

                tvOrderTotal.text = "S/ ${String.format(Locale.US, "%.2f", order.totalAmount + order.deliveryFee)}"
                tvOrderStatusBadge.text = order.status
                
                btnOrderAction.text = when (order.status) {
                    "PENDING" -> "Aceptar Pedido"
                    "ACCEPTED" -> "En Camino 🛵"
                    "EN_ROUTE" -> "Marcar Entregado"
                    else -> "Finalizado"
                }
                btnOrderAction.visibility = if (order.status == "DELIVERED" || order.status == "CANCELLED") View.GONE else View.VISIBLE

                btnOrderNavigate.text = "Ver Ubicación"
                btnOrderNavigate.visibility = if ((order.status == "PENDING" || order.status == "ACCEPTED" || order.status == "EN_ROUTE") && 
                    (order.deliveryLat != 0.0 || order.deliveryAddress.isNotEmpty())) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                btnOrderCancel.text = if (order.status == "PENDING") "Rechazar" else "Anular"
                btnOrderCancel.visibility = if (order.status == "PENDING" || order.status == "ACCEPTED" || order.status == "EN_ROUTE") {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                btnOrderAction.setOnClickListener { onAction(order) }
                btnOrderChat.setOnClickListener { onChat(order) }
                btnOrderNavigate.setOnClickListener { onNavigate(order) }
                btnOrderCancel.setOnClickListener { onCancel(order) }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun showCancelConfirmation(order: OrderModel) {
        val title = if (order.status == "PENDING") "Rechazar Pedido" else "Cancelar Pedido"
        val message = if (order.status == "PENDING") "¿Deseas rechazar esta solicitud de pedido?" else "¿Deseas cancelar este pedido en curso?"
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Sí, Anular") { _, _ ->
                updateStatus(order.id, "CANCELLED") {
                    notifyBuyerOrderCancelled(order)
                }
            }
            .setNegativeButton("No", null)
            .show()
    }
}
