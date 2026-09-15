package com.naxor.app

import android.content.Intent
import android.graphics.Color
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
import com.naxor.app.data.OrderModel
import com.naxor.app.databinding.ActivityBuyerOrdersBinding
import com.naxor.app.databinding.ItemOrderCardBinding
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
            onChat = { order -> openChat(order) }
        )
        binding.rvBuyerOrders.layoutManager = LinearLayoutManager(this)
        binding.rvBuyerOrders.adapter = adapter
    }

    private fun setupListeners() {
        binding.toolbarBuyerOrders.setNavigationOnClickListener { finish() }
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
                        val orders = snapshot.toObjects(OrderModel::class.java)
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
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", order.id)
            putExtra("OTHER_USER_NAME", order.storeName)
            putExtra("OTHER_USER_ID", order.storeId)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ordersRegistration?.remove()
    }

    inner class OrdersAdapter(
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
                tvOrderBuyerName.text = "Tienda: ${order.storeName}"
                tvOrderAddress.text = "📍 ${order.deliveryAddress}"
                tvOrderItemsSummary.text = "${order.items.size} productos - Total: S/ ${String.format(Locale.US, "%.2f", order.totalAmount)}"
                tvOrderTotal.text = "S/ ${String.format(Locale.US, "%.2f", order.totalAmount + order.deliveryFee)}"
                
                tvOrderStatusBadge.text = order.status
                tvOrderStatusBadge.setBackgroundColor(when(order.status) {
                    "PENDING" -> Color.parseColor("#F59E0B")
                    "ACCEPTED" -> Color.parseColor("#3B82F6")
                    "EN_ROUTE" -> Color.parseColor("#10B981")
                    "DELIVERED" -> Color.parseColor("#059669")
                    else -> Color.LTGRAY
                })

                btnOrderAction.visibility = View.GONE // Los compradores no accionan el pedido aquí
                btnOrderChat.setOnClickListener { onChat(order) }
            }
        }

        override fun getItemCount() = items.size
    }
}
