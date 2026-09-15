package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.data.CartItem
import com.naxor.app.data.CartManager
import com.naxor.app.data.MarketplaceProduct
import com.naxor.app.databinding.ActivityProductDetailBinding
import com.naxor.app.databinding.ItemRecentlyViewedBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

class ProductDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityProductDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val registrations = mutableListOf<ListenerRegistration>()
    
    private lateinit var similarAdapter: SmallProductAdapter
    private lateinit var recommendedAdapter: SmallProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("PROD_ID") ?: ""
        val name = intent.getStringExtra("PROD_NAME") ?: "Producto"
        val desc = intent.getStringExtra("PROD_DESC") ?: "Sin descripción"
        val price = intent.getDoubleExtra("PROD_PRICE", 0.0)
        val cat = intent.getStringExtra("PROD_CAT") ?: "General"
        val photo = intent.getStringExtra("PROD_PHOTO")
        val storeId = intent.getStringExtra("STORE_ID") ?: ""
        val storeName = intent.getStringExtra("STORE_NAME") ?: "Tienda"
        val storeAddr = intent.getStringExtra("STORE_ADDR") ?: "Sin dirección"
        val storeLogo = intent.getStringExtra("STORE_LOGO")
        val storeType = intent.getStringExtra("STORE_TYPE") ?: "PRODUCTS"

        setupToolbar()
        setupUI(name, desc, price, cat, photo, storeName, storeAddr, storeLogo)
        setupRecyclerViews()
        setupListeners(id, name, price, cat, storeId, storeName, storeType)
        
        loadProductRealtime(id, storeId, storeType)
        loadStoreRealtime(storeId, storeType)
        loadSimilarAndRecommended(id, storeId, cat, storeType)
    }

    private fun loadStoreRealtime(sId: String, sType: String) {
        val docId = if (sType == "PRODUCTS" || sType == "SERVICES") "business" else sType
        
        val reg = db.collection("users").document(sId).collection("settings").document(docId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) {
                    if (docId != "business") {
                        // Re-intentar con business si el específico falla
                        loadStoreRealtime(sId, "PRODUCTS")
                    }
                    return@addSnapshotListener
                }
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val logoUrl = snapshot.getString("business_logo_url")
                    withContext(Dispatchers.Main) {
                        if (!logoUrl.isNullOrEmpty()) {
                            binding.tvStoreEmojiPlaceholder.visibility = View.GONE
                            binding.ivDetailStoreLogo.visibility = View.VISIBLE
                            Glide.with(this@ProductDetailActivity)
                                .load(logoUrl)
                                .into(binding.ivDetailStoreLogo)
                        }
                    }
                }
            }
        registrations.add(reg)
    }

    private fun loadProductRealtime(id: String, sId: String, sType: String) {
        val collectionName = if (sType == "HOTEL") "hotel_rooms" else "inventory"
        val reg = db.collection("users").document(sId).collection(collectionName).document(id)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val name = if (sType == "HOTEL") "Habitación ${snapshot.getString("number")}" else snapshot.getString("nombre") ?: ""
                    val desc = snapshot.getString("descripcion") ?: snapshot.getString("specificDescription") ?: snapshot.getString("description") ?: ""
                    val price = if (sType == "HOTEL") snapshot.getDouble("baseRate") ?: 0.0 else snapshot.getDouble("precioVenta") ?: 0.0
                    val cat = snapshot.getString("categoria") ?: snapshot.getString("type") ?: ""
                    val photo = snapshot.getString("photoUrl")
                    
                    withContext(Dispatchers.Main) {
                        // Actualizar UI con datos frescos de la nube
                        binding.tvDetailName.text = name
                        binding.tvDetailCategory.text = cat
                        binding.tvDetailDescription.text = desc
                        binding.tvDetailPrice.text = if (sType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", price)} la noche" else "S/ ${String.format(Locale.US, "%.2f", price)}"
                        
                        if (!photo.isNullOrEmpty()) {
                            Glide.with(this@ProductDetailActivity)
                                .load(photo)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .placeholder(R.drawable.gomezmart_icon)
                                .into(binding.ivProductDetailImage)
                        }
                    }
                }
            }
        registrations.add(reg)
    }

    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    private fun setupToolbar() {
        binding.toolbarProductDetail.setNavigationOnClickListener { finish() }
    }

    private fun setupUI(name: String, desc: String, price: Double, cat: String, photo: String?, sName: String, sAddr: String, sLogo: String?) {
        val storeType = intent.getStringExtra("STORE_TYPE") ?: "PRODUCTS"
        
        // Mostrar datos iniciales de los extras
        binding.tvDetailName.text = name
        binding.tvDetailCategory.text = cat
        binding.tvDetailDescription.text = desc
        binding.tvDetailPrice.text = if (storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", price)} la noche" else "S/ ${String.format(Locale.US, "%.2f", price)}"
        binding.tvDetailStoreName.text = sName
        binding.tvDetailStoreAddress.text = sAddr

        if (storeType == "HOTEL") {
            binding.btnDetailAddToCart.text = "RESERVAR HABITACIÓN 🛎️"
            binding.btnDetailAddToCart.setIconResource(android.R.drawable.ic_menu_agenda)
        } else {
            binding.btnDetailAddToCart.text = "AÑADIR AL CARRITO"
            binding.btnDetailAddToCart.setIconResource(android.R.drawable.ic_input_add)
        }

        // Cargar logo de la tienda si existe
        if (!sLogo.isNullOrEmpty()) {
            binding.tvStoreEmojiPlaceholder.visibility = View.GONE
            binding.ivDetailStoreLogo.visibility = View.VISIBLE
            Glide.with(this).load(sLogo).into(binding.ivDetailStoreLogo)
        } else {
            binding.tvStoreEmojiPlaceholder.visibility = View.VISIBLE
            binding.ivDetailStoreLogo.visibility = View.GONE
        }

        // Limpiar imagen previa para evitar confusión si hay cambio
        binding.ivProductDetailImage.setImageDrawable(null)

        if (!photo.isNullOrEmpty()) {
            Glide.with(this)
                .load(photo)
                .placeholder(R.drawable.gomezmart_icon)
                .into(binding.ivProductDetailImage)
        } else {
            binding.ivProductDetailImage.setImageResource(R.drawable.gomezmart_icon)
        }
    }

    private fun setupRecyclerViews() {
        similarAdapter = SmallProductAdapter { product -> navigateToProduct(product) }
        binding.rvSimilarProducts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSimilarProducts.adapter = similarAdapter

        recommendedAdapter = SmallProductAdapter { product -> navigateToProduct(product) }
        binding.rvRecommendedProducts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecommendedProducts.adapter = recommendedAdapter
    }

    private fun setupListeners(id: String, name: String, price: Double, cat: String, sId: String, sName: String, storeType: String) {
        binding.btnDetailAddToCart.setOnClickListener {
            if (storeType == "HOTEL") {
                val intent = Intent(this, HotelBookingRequestActivity::class.java).apply {
                    putExtra("ROOM_ID", id)
                    putExtra("ROOM_NAME", name)
                    putExtra("ROOM_PRICE", price)
                    putExtra("STORE_ID", sId)
                    putExtra("STORE_NAME", sName)
                }
                startActivity(intent)
                return@setOnClickListener
            }
            
            if (CartManager.currentStoreId != sId && CartManager.getItems().isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Cambiar de Tienda")
                    .setMessage("Tu carrito pertenece a otra tienda. ¿Deseas vaciarlo y comprar en $sName?")
                    .setPositiveButton("Sí, Cambiar") { _, _ ->
                        CartManager.clear()
                        CartManager.currentStoreId = sId
                        CartManager.addItemFromManualData(id, name, price, cat, sId)
                        Toast.makeText(this, "Añadido al nuevo carrito 🛒", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            } else {
                CartManager.currentStoreId = sId
                CartManager.addItemFromManualData(id, name, price, cat, sId)
                Toast.makeText(this, "Añadido al carrito 🛒", Toast.LENGTH_SHORT).show()
            }
        }

        // Navegación al catálogo eliminada
    }

    private fun loadSimilarAndRecommended(currentProdId: String, sId: String, category: String, sType: String) {
        val currentName = intent.getStringExtra("PROD_NAME") ?: ""
        val storeName = intent.getStringExtra("STORE_NAME") ?: "Tienda"
        
        lifecycleScope.launch {
            // 1. CARGAR OTROS PRODUCTOS DE LA MISMA TIENDA
            try {
                val collectionName = if (sType == "HOTEL") "hotel_rooms" else "inventory"
                val storeInvRef = db.collection("users").document(sId).collection(collectionName)
                val similarSnap = storeInvRef.get().await()
                
                val similarList = mutableListOf<MarketplaceProduct>()
                for (doc in similarSnap.documents) {
                    if (doc.id == currentProdId || doc.getBoolean("isDeleted") == true) continue
                    similarList.add(mapToMarketplace(doc, sId, storeName, "", sType)) 
                }
                
                val sortedSimilar = similarList.sortedByDescending { 
                    it.name.lowercase().contains(currentName.lowercase()) || currentName.lowercase().contains(it.name.lowercase()) 
                }

                if (sortedSimilar.isNotEmpty()) {
                    binding.layoutSimilarHeader.visibility = View.VISIBLE
                    binding.rvSimilarProducts.visibility = View.VISIBLE
                    similarAdapter.updateData(sortedSimilar.take(10))
                } else {
                    binding.layoutSimilarHeader.visibility = View.GONE
                    binding.rvSimilarProducts.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("ProductDetail", "Error similares: ${e.message}")
            }

            // 2. CARGAR RECOMENDADOS DE TODA LA RED (Marketplace)
            try {
                val collGroup = if (sType == "HOTEL") "hotel_rooms" else "inventory"
                val allInvSnap = db.collectionGroup(collGroup).limit(50).get().await()
                val recList = mutableListOf<MarketplaceProduct>()
                
                for (doc in allInvSnap.documents) {
                    if (doc.id == currentProdId || doc.getBoolean("isDeleted") == true) continue
                    
                    val pathParts = doc.reference.path.split("/")
                    val foundStoreId = if (pathParts.size >= 2) pathParts[1] else ""
                    
                    if (foundStoreId != sId && foundStoreId.isNotEmpty()) {
                        recList.add(mapToMarketplace(doc, foundStoreId, "Vendedor GoMezMart", "", sType))
                    }
                }

                // Filtrar localmente por categoría para evitar errores
                val filteredRecs = recList.filter { it.category == category }.toMutableList()
                if (filteredRecs.size < 5) {
                    // Si hay pocos de la misma categoría, rellenar con cualquiera de otra tienda
                    filteredRecs.addAll(recList.filter { it.category != category }.take(5 - filteredRecs.size))
                }

                if (filteredRecs.isNotEmpty()) {
                    binding.tvRecommendedTitle.visibility = View.VISIBLE
                    binding.rvRecommendedProducts.visibility = View.VISIBLE
                    recommendedAdapter.updateData(filteredRecs.distinctBy { it.id }.take(10))
                }
            } catch (e: Exception) {
                Log.e("ProductDetail", "Error recomendados: ${e.message}")
            }
        }
    }

    private fun mapToMarketplace(doc: DocumentSnapshot, sId: String, sName: String, sAddr: String, sType: String): MarketplaceProduct {
        val name = if (sType == "HOTEL") "Habitación ${doc.getString("number")}" else doc.getString("nombre") ?: "Producto"
        val desc = doc.getString("descripcion") ?: doc.getString("specificDescription") ?: doc.getString("description") ?: ""
        val price = if (sType == "HOTEL") doc.getDouble("baseRate") ?: 0.0 else doc.getDouble("precioVenta") ?: 0.0
        val cat = doc.getString("categoria") ?: doc.getString("type") ?: "General"

        return MarketplaceProduct(
            id = doc.id,
            name = name,
            category = cat,
            price = price,
            description = desc,
            photoUrl = doc.getString("photoUrl"),
            storeId = sId,
            storeName = sName,
            storeAddress = sAddr,
            storeType = sType
        )
    }

    private fun navigateToProduct(p: MarketplaceProduct) {
        val intent = Intent(this, ProductDetailActivity::class.java).apply {
            putExtra("PROD_ID", p.id)
            putExtra("PROD_NAME", p.name)
            putExtra("PROD_DESC", p.description)
            putExtra("PROD_PRICE", p.price)
            putExtra("PROD_CAT", p.category)
            putExtra("PROD_PHOTO", p.photoUrl)
            putExtra("STORE_ID", p.storeId)
            putExtra("STORE_NAME", p.name)
            putExtra("STORE_ADDR", p.storeAddress)
            putExtra("STORE_LOGO", p.storeLogoUrl)
            putExtra("STORE_TYPE", p.storeType)
        }
        startActivity(intent)
        finish()
    }

    inner class SmallProductAdapter(private val onProductClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<SmallProductAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        fun updateData(list: List<MarketplaceProduct>) { items = list; notifyDataSetChanged() }

        inner class ViewHolder(val b: ItemRecentlyViewedBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemRecentlyViewedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvRecentProductName.text = p.name
                tvRecentProductPrice.text = if (p.storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", p.price)} noche" else "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                if (!p.photoUrl.isNullOrEmpty()) Glide.with(ivRecentProductImage.context).load(p.photoUrl).into(ivRecentProductImage)
                else ivRecentProductImage.setImageResource(R.drawable.gomezmart_icon)
                root.setOnClickListener { onProductClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }
}
