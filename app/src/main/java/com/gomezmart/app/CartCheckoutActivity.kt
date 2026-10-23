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
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.CartItem
import com.gomezmart.app.data.CartManager
import com.gomezmart.app.data.ChatMessage
import com.gomezmart.app.data.OrderModel
import com.gomezmart.app.databinding.ActivityCartCheckoutBinding
import com.gomezmart.app.databinding.ItemCartItemBinding
import com.gomezmart.app.util.FloatingCartHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
            
            // Si venimos del flujo de "Realizar Pedido", completar el pedido automáticamente
            if (deliveryLat != 0.0) {
                placeOrder()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCartCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar Insets - Usamos applyWindowInsets (padding) para la barra de abajo
        applyWindowInsets(binding.toolbarCart, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.layoutCartCheckoutBottomButtons, applyTop = false, applyBottom = true)

        // PRIORIDAD: Datos del CartManager (Source of Truth)
        storeId = CartManager.currentStoreId.ifEmpty { intent.getStringExtra("STORE_ID") ?: "" }
        storeName = CartManager.currentStoreName.ifEmpty { intent.getStringExtra("STORE_NAME") ?: "Tienda" }
        val storeLogo = CartManager.currentStoreLogo.ifEmpty { intent.getStringExtra("STORE_LOGO") ?: "" }

        binding.tvCartStoreName.text = storeName
        if (storeLogo.isNotEmpty()) {
            Glide.with(this).load(storeLogo).circleCrop().into(binding.ivCartStoreLogo)
        }

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
            },
            onRemove = { productId ->
                CartManager.removeItem(productId)
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
            if (CartManager.getItems().isEmpty()) {
                Toast.makeText(this, "El carrito está vacío", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showLocationSelection()
        }

        binding.btnSendOrderWhatsApp.setOnClickListener {
            fetchStoreContactAndSend()
        }

        binding.btnEmptyCartGoShop.setOnClickListener {
            finish()
        }
    }

    private fun showLocationSelection() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location_selection, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<View>(R.id.btnLocCurrent).setOnClickListener {
            dialog.dismiss()
            useCurrentLocationForOrder()
        }
        view.findViewById<View>(R.id.btnLocSaved).setOnClickListener {
            dialog.dismiss()
            showRegisteredLocations()
        }
        view.findViewById<View>(R.id.btnLocNew).setOnClickListener {
            dialog.dismiss()
            openMapToSelectLocation()
        }
        view.findViewById<View>(R.id.btnLocPickup).setOnClickListener {
            dialog.dismiss()
            deliveryAddress = "Retiro en Local"
            deliveryLat = 0.0
            deliveryLng = 0.0
            placeOrder()
        }
        view.findViewById<View>(R.id.btnCancelLocation).setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun useCurrentLocationForOrder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
            return
        }

        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                deliveryLat = loc.latitude
                deliveryLng = loc.longitude
                deliveryAddress = "Ubicación actual"
                placeOrder()
            } else {
                Toast.makeText(this, "No se pudo obtener tu ubicación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRegisteredLocations() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val locsSnap = db.collection("users").document(myUid).collection("locations").get().await()
                if (locsSnap.isEmpty) {
                    Toast.makeText(this@CartCheckoutActivity, "No tienes ubicaciones registradas", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val names = locsSnap.documents.map { it.getString("title") ?: it.getString("name") ?: "Ubicación" }.toTypedArray()
                AlertDialog.Builder(this@CartCheckoutActivity)
                    .setTitle("Tus ubicaciones")
                    .setItems(names) { _, which ->
                        val doc = locsSnap.documents[which]
                        deliveryLat = doc.getDouble("lat") ?: doc.getDouble("latitude") ?: 0.0
                        deliveryLng = doc.getDouble("lng") ?: doc.getDouble("longitude") ?: 0.0
                        deliveryAddress = doc.getString("address") ?: names[which]
                        placeOrder()
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@CartCheckoutActivity, "Error al cargar ubicaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMapToSelectLocation() {
        mapSelectorLauncher.launch(Intent(this, BusinessMapSelectorActivity::class.java))
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

        val isDelivery = deliveryAddress.isNotEmpty() && deliveryAddress != "Retiro en Local"
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
            deliveryFee = if (isDelivery) 5.0 else 0.0, 
            status = "PENDING",
            timestamp = FieldValue.serverTimestamp(),
            deliveryAddress = deliveryAddress,
            deliveryReference = "",
            deliveryLat = deliveryLat,
            deliveryLng = deliveryLng
        )

        val loading = AlertDialog.Builder(this).setMessage("Procesando pedido...").setCancelable(false).show()

        FirebaseFirestore.getInstance().collection("orders").document(orderId)
            .set(order)
            .addOnSuccessListener {
                // 1. Notificar en el chat (Tarjeta de Alerta)
                val myUid = user.uid
                val chatId = if (myUid < storeId) "${myUid}_$storeId" else "${storeId}_$myUid"
                val alertMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = myUid,
                    message = "¡He realizado un pedido de ${order.items.size} productos! 🛍️ Destino: ${order.deliveryAddress}",
                    timestamp = FieldValue.serverTimestamp(),
                    type = "ORDER_ALERT",
                    linkedOrderId = orderId,
                    deliveryLat = deliveryLat,
                    deliveryLng = deliveryLng,
                    deliveryAddress = deliveryAddress
                )
                FirebaseFirestore.getInstance().collection("direct_chats").document(chatId)
                    .collection("messages").document(alertMessage.id).set(alertMessage)

                loading.dismiss()
                Toast.makeText(this@CartCheckoutActivity, "¡Pedido realizado con éxito! 🎉", Toast.LENGTH_LONG).show()
                CartManager.clear()
                finish()
            }
            .addOnFailureListener { e ->
                loading.dismiss()
                Toast.makeText(this, "Error al procesar pedido: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateTotal() {
        val items = CartManager.getItems()
        val total = CartManager.getTotalPrice()
        binding.tvCheckoutTotal.text = "S/ ${String.format(Locale.US, "%.2f", total)}"
        
        // Actualizar el contador del botón flotante
        FloatingCartHelper.updateBadge()
        
        if (items.isEmpty()) {
            binding.layoutEmptyCart.visibility = View.VISIBLE
            binding.rvCartItems.visibility = View.GONE
            binding.layoutCartStoreHeader.visibility = View.GONE
            binding.btnPlaceOrderGoMezMart.isEnabled = false
            binding.btnSendOrderWhatsApp.isEnabled = false
        } else {
            binding.layoutEmptyCart.visibility = View.GONE
            binding.rvCartItems.visibility = View.VISIBLE
            binding.layoutCartStoreHeader.visibility = View.VISIBLE
            binding.btnPlaceOrderGoMezMart.isEnabled = true
            binding.btnSendOrderWhatsApp.isEnabled = true
            
            // [ROBUSTEZ] Asegurar que el nombre y logo estén cargados
            if (binding.tvCartStoreName.text.isNullOrEmpty() || binding.tvCartStoreName.text == "Tienda") {
                binding.tvCartStoreName.text = CartManager.currentStoreName.ifEmpty { "Tienda" }
                if (CartManager.currentStoreLogo.isNotEmpty()) {
                    Glide.with(this).load(CartManager.currentStoreLogo).circleCrop().into(binding.ivCartStoreLogo)
                }
            }
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
        private val onQtyChange: (String, Int) -> Unit,
        private val onRemove: (String) -> Unit
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
                tvCartItemTotal.text = "Total: S/ ${String.format(Locale.US, "%.2f", item.total)}"

                if (!item.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivCartProductPhoto.context)
                        .load(item.photoUrl)
                        .centerCrop()
                        .placeholder(R.drawable.gomezmart_icon)
                        .into(ivCartProductPhoto)
                } else {
                    ivCartProductPhoto.setImageResource(R.drawable.gomezmart_icon)
                }

                btnDecreaseQty.setOnClickListener { onQtyChange(item.productId, -1) }
                btnIncreaseQty.setOnClickListener { onQtyChange(item.productId, 1) }
                btnRemoveItem.setOnClickListener { onRemove(item.productId) }
            }
        }

        override fun getItemCount() = items.size
    }
}
