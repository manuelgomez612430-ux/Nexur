package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.gomezmart.app.data.OrderModel
import com.gomezmart.app.databinding.ActivityBuyerOrdersBinding
import com.gomezmart.app.databinding.ItemOrderCardBinding
import java.util.*

class BuyerOrdersActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerOrdersBinding
    
    private val db: FirebaseFirestore? by lazy {
        try { FirebaseFirestore.getInstance() } catch (e: Exception) { 
            Log.e("BuyerOrders", "Firestore init fail: ${e.message}")
            null 
        }
    }
    
    private val auth: FirebaseAuth? by lazy {
        try { FirebaseAuth.getInstance() } catch (e: Exception) {
            Log.e("BuyerOrders", "Auth init fail: ${e.message}")
            null
        }
    }

    private var ordersRegistration: ListenerRegistration? = null
    
    private lateinit var adapter: OrdersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets
        applyWindowInsets(binding.appBarBuyerOrders, applyTop = true, applyBottom = false)

        setupRecyclerView()
        setupListeners()
        
        if (db == null || auth == null) {
            Toast.makeText(this, "Servicios de nube no disponibles ☁️", Toast.LENGTH_LONG).show()
        } else {
            startOrdersListener()
        }
    }

    private fun setupRecyclerView() {
        adapter = OrdersAdapter(
            onChat = { order -> openChat(order) },
            onTrack = { order -> openTracking(order) }
        )
        binding.rvBuyerOrders.layoutManager = LinearLayoutManager(this)
        binding.rvBuyerOrders.adapter = adapter
    }

    private fun openTracking(order: OrderModel) {
        val intent = Intent(this, OrderTrackingActivity::class.java).apply {
            putExtra("ORDER_ID", order.id)
        }
        startActivityWithFade(intent)
    }

    private fun setupListeners() {
        binding.btnBackBuyerOrders.setOnClickListener { finish() }
    }

    private fun startOrdersListener() {
        val firestore = db ?: return
        val userId = auth?.currentUser?.uid ?: return
        
        try {
            ordersRegistration = firestore.collection("orders")
                .whereEqualTo("buyerId", userId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.e("BuyerOrders", "Listen failed: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    
                    try {
                        val orders = snapshot.documents.mapNotNull { doc ->
                            doc.toObject(OrderModel::class.java)?.copy(id = doc.id)
                        }
                        adapter.submitList(orders)
                        binding.layoutEmptyBuyerOrders.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
                    } catch (err: Exception) {
                        Log.e("BuyerOrders", "Deserialization error: ${err.message}")
                        Toast.makeText(this, "Error al cargar algunos pedidos", Toast.LENGTH_SHORT).show()
                    }
                }
        } catch (e: Exception) {
            Log.e("BuyerOrders", "Error setting up listener: ${e.message}")
        }
    }

    private fun openChat(order: OrderModel) {
        val myUid = auth?.currentUser?.uid ?: return
        val storeId = order.storeId
        if (storeId.isEmpty()) {
            Toast.makeText(this, "ID de tienda no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        // Generar ID de chat directo determinista para conectar con el chat existente
        val directChatId = if (myUid < storeId) "${myUid}_$storeId" else "${storeId}_$myUid"

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", directChatId)
            putExtra("OTHER_USER_NAME", order.storeName)
            putExtra("OTHER_USER_ID", storeId)
            putExtra("IS_DIRECT", true)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ordersRegistration?.remove()
    }

    inner class OrdersAdapter(
        private val onChat: (OrderModel) -> Unit,
        private val onTrack: (OrderModel) -> Unit
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
                tvOrderBuyerName.text = "Tienda: ${order.storeName}"
                tvOrderAddress.text = "📍 ${order.deliveryAddress}"
                tvOrderItemsSummary.text = "${order.items.size} productos - Total: S/ ${String.format(Locale.US, "%.2f", order.totalAmount)}"
                tvOrderTotal.text = "S/ ${String.format(Locale.US, "%.2f", order.totalAmount + order.deliveryFee)}"
                
                tvOrderStatusBadge.text = when(order.status) {
                    "PENDING" -> "PENDIENTE"
                    "ACCEPTED" -> "ACEPTADO"
                    "EN_ROUTE" -> "EN CAMINO"
                    "DELIVERED" -> "ENTREGADO"
                    "CANCELLED" -> "CANCELADO"
                    else -> order.status
                }
                
                tvOrderStatusBadge.setBackgroundResource(when(order.status) {
                    "PENDING" -> R.drawable.bg_pill_purple_light
                    "ACCEPTED" -> R.drawable.bg_pill_purple_light
                    "EN_ROUTE" -> R.drawable.bg_pill_purple_light
                    "DELIVERED" -> R.drawable.bg_pill_purple_light
                    "CANCELLED" -> R.drawable.bg_pill_red_light
                    else -> R.drawable.bg_pill_purple_light
                })
                
                // Color de texto para el badge
                tvOrderStatusBadge.setTextColor(if (order.status == "CANCELLED") getColor(R.color.red_600) else getColor(R.color.purple_600))

                btnOrderAction.visibility = View.GONE
                btnOrderCancel.visibility = View.GONE
                
                // Mostrar botón Seguimiento si el pedido no está cancelado ni entregado
                btnOrderNavigate.visibility = if (order.status != "DELIVERED" && order.status != "CANCELLED") View.VISIBLE else View.GONE
                btnOrderNavigate.text = "Seguimiento"
                btnOrderNavigate.setOnClickListener { onTrack(order) }

                btnOrderChat.setOnClickListener { onChat(order) }
            }
        }

        override fun getItemCount() = items.size
    }
}
