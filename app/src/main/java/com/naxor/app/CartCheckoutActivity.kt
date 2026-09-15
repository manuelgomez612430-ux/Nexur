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
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.CartItem
import com.naxor.app.data.CartManager
import com.naxor.app.data.OrderModel
import com.naxor.app.databinding.ActivityCartCheckoutBinding
import com.naxor.app.databinding.ItemCartItemBinding
import java.util.*

class CartCheckoutActivity : BaseActivity() {

    private lateinit var binding: ActivityCartCheckoutBinding
    private lateinit var adapter: CartAdapter
    private var storeId: String = ""
    private var storeName: String = ""
    
    private var deliveryLat = 0.0
    private var deliveryLng = 0.0
    private var deliveryAddress = ""

    private val mapSelectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            deliveryLat = data?.getDoubleExtra("LAT", 0.0) ?: 0.0
            deliveryLng = data?.getDoubleExtra("LNG", 0.0) ?: 0.0
            deliveryAddress = data?.getStringExtra("ADDRESS") ?: ""
            binding.tvDeliveryAddress.text = if (deliveryAddress.isNotEmpty()) deliveryAddress else "Ubicación fijada por GPS"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCartCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storeId = intent.getStringExtra("STORE_ID") ?: ""
        storeName = intent.getStringExtra("STORE_NAME") ?: "Tienda"

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        updateTotal()
    }

    private fun setupToolbar() {
        binding.toolbarCart.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = CartAdapter(
            onQtyChange = { productId, delta ->
                CartManager.updateQuantity(productId, delta)
                adapter.setItems(CartManager.getItems())
                updateTotal()
            }
        )
        binding.rvCartItems.layoutManager = LinearLayoutManager(this)
        binding.rvCartItems.adapter = adapter
        adapter.setItems(CartManager.getItems())
    }

    private fun setupListeners() {
        binding.switchDelivery.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDeliveryInfo.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnSetDeliveryLocation.setOnClickListener {
            mapSelectorLauncher.launch(Intent(this, BusinessMapSelectorActivity::class.java))
        }

        binding.btnPlaceOrderGoMezMart.setOnClickListener {
            if (binding.switchDelivery.isChecked && deliveryLat == 0.0) {
                Toast.makeText(this, "Por favor selecciona tu ubicación para el delivery", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            placeOrder()
        }

        binding.btnSendOrderWhatsApp.setOnClickListener {
            fetchStoreContactAndSend()
        }
    }

    private fun placeOrder() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Debes iniciar sesión para realizar un pedido", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val buyerName = prefs.getString("user_full_name", user.displayName ?: "Comprador") ?: "Comprador"
        val buyerPhone = prefs.getString("user_phone", "") ?: ""

        val orderId = UUID.randomUUID().toString()
        val order = OrderModel(
            id = orderId,
            buyerId = user.uid,
            buyerName = buyerName,
            buyerPhone = buyerPhone,
            storeId = storeId,
            storeName = storeName,
            items = CartManager.getItems(),
            totalAmount = CartManager.getTotalPrice(),
            deliveryFee = if (binding.switchDelivery.isChecked) 5.0 else 0.0, // Costo base de delivery
            status = "PENDING",
            timestamp = FieldValue.serverTimestamp(),
            deliveryAddress = deliveryAddress,
            deliveryLat = deliveryLat,
            deliveryLng = deliveryLng
        )

        val loading = AlertDialog.Builder(this).setMessage("Procesando pedido...").setCancelable(false).show()

        FirebaseFirestore.getInstance().collection("orders").document(orderId)
            .set(order)
            .addOnSuccessListener {
                loading.dismiss()
                Toast.makeText(this, "¡Pedido realizado con éxito! 🎉", Toast.LENGTH_LONG).show()
                CartManager.clear()
                // Aquí podrías ir a una pantalla de "Mis Pedidos"
                finish()
            }
            .addOnFailureListener { e ->
                loading.dismiss()
                Toast.makeText(this, "Error al procesar pedido: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateTotal() {
        val total = CartManager.getTotalPrice()
        binding.tvCheckoutTotal.text = "S/ ${String.format(Locale.US, "%.2f", total)}"
        
        if (CartManager.getItems().isEmpty()) {
            Toast.makeText(this, "El carrito está vacío", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun fetchStoreContactAndSend() {
        if (storeId.isEmpty()) return

        FirebaseFirestore.getInstance().collection("users").document(storeId)
            .collection("settings").document("business")
            .get()
            .addOnSuccessListener { doc ->
                val phone = doc.getString("business_phone") ?: ""
                sendWhatsAppOrder(phone)
            }
            .addOnFailureListener {
                sendWhatsAppOrder("") // Intentar sin número específico
            }
    }

    private fun sendWhatsAppOrder(phone: String) {
        val items = CartManager.getItems()
        if (items.isEmpty()) return

        val sb = StringBuilder()
        sb.append("🛍️ *NUEVO PEDIDO - GOMEZMART* 🛍️\n")
        sb.append("🏪 *Tienda:* $storeName\n")
        sb.append("--------------------------------\n")
        
        items.forEach { item ->
            sb.append("• ${item.quantity}x *${item.productName}* - S/ ${String.format(Locale.US, "%.2f", item.total)}\n")
        }
        
        sb.append("--------------------------------\n")
        sb.append("*TOTAL A PAGAR: S/ ${String.format(Locale.US, "%.2f", CartManager.getTotalPrice())}*\n\n")
        sb.append("Por favor, confírmeme la recepción del pedido. ✅")

        val message = sb.toString()
        val url = "https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}"
        
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
            
            // Opcional: Limpiar carrito tras enviar
            // CartManager.clear()
            // finish()
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }
    }

    inner class CartAdapter(
        private val onQtyChange: (String, Int) -> Unit
    ) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {
        
        private var items = listOf<CartItem>()

        fun setItems(newList: List<CartItem>) {
            items = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemCartItemBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemCartItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvCartProductName.text = item.productName
                tvCartProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", item.price)} c/u"
                tvCartQty.text = item.quantity.toString()

                btnDecreaseQty.setOnClickListener { onQtyChange(item.productId, -1) }
                btnIncreaseQty.setOnClickListener { onQtyChange(item.productId, 1) }
            }
        }

        override fun getItemCount() = items.size
    }
}
