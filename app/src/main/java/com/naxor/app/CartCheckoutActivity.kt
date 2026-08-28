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
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.CartItem
import com.naxor.app.data.CartManager
import com.naxor.app.databinding.ActivityCartCheckoutBinding
import com.naxor.app.databinding.ItemCartItemBinding
import java.util.*

class CartCheckoutActivity : BaseActivity() {

    private lateinit var binding: ActivityCartCheckoutBinding
    private lateinit var adapter: CartAdapter
    private var storeId: String = ""
    private var storeName: String = ""

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
        binding.btnSendOrderWhatsApp.setOnClickListener {
            fetchStoreContactAndSend()
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
