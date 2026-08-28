package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.CartManager
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityStoreCatalogBinding
import com.naxor.app.databinding.ItemCatalogProductBinding
import java.util.*

class StoreCatalogActivity : BaseActivity() {

    private lateinit var binding: ActivityStoreCatalogBinding
    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: CatalogAdapter
    private var storeId: String = ""
    private var storeName: String = ""
    private var forcedRubro: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoreCatalogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storeId = intent.getStringExtra("STORE_ID") ?: ""
        storeName = intent.getStringExtra("STORE_NAME") ?: "Catálogo"
        forcedRubro = intent.getStringExtra("FORCE_RUBRO")
        
        setupToolbar()
        setupRecyclerView()
        setupListeners()
        loadStoreInfo()
        loadProducts()
        updateCartSummary()
    }

    private fun loadStoreInfo() {
        if (storeId.isEmpty()) return
        
        val docId = forcedRubro ?: "business"

        db.collection("users").document(storeId)
            .collection("settings").document(docId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists() && docId != "business") {
                    forcedRubro = null
                    loadStoreInfo()
                    return@addOnSuccessListener
                }

                val logoUrl = doc.getString("business_logo_url")
                if (!logoUrl.isNullOrEmpty()) {
                    binding.cardToolbarStoreLogo.visibility = View.VISIBLE
                    Glide.with(this).load(logoUrl).into(binding.ivToolbarStoreLogo)
                }
            }
    }

    private fun setupToolbar() {
        binding.toolbarCatalog.title = storeName
        binding.toolbarCatalog.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = CatalogAdapter { product ->
            CartManager.addItem(product, storeId)
            updateCartSummary()
            Toast.makeText(this, "Añadido al carrito 🛒", Toast.LENGTH_SHORT).show()
        }
        // Cuadrícula de 2 columnas para visualización rápida
        binding.rvCatalog.layoutManager = GridLayoutManager(this, 2)
        binding.rvCatalog.adapter = adapter
    }

    private fun setupListeners() {
        binding.etSearchProduct.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.cardCartSummary.setOnClickListener {
            startActivity(Intent(this, CartCheckoutActivity::class.java).apply {
                putExtra("STORE_ID", storeId)
                putExtra("STORE_NAME", storeName)
            })
        }
    }

    private fun updateCartSummary() {
        val count = CartManager.getTotalCount()
        if (count > 0) {
            binding.cardCartSummary.visibility = View.VISIBLE
            binding.tvCartCount.text = count.toString()
            binding.tvCartTotal.text = "S/ ${String.format(Locale.US, "%.2f", CartManager.getTotalPrice())}"
        } else {
            binding.cardCartSummary.visibility = View.GONE
        }
    }

    private fun loadProducts() {
        if (storeId.isEmpty()) return
        binding.layoutLoadingCatalog.visibility = View.VISIBLE
        
        val docId = forcedRubro ?: "business"

        db.collection("users").document(storeId)
            .collection("settings").document(docId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists() && docId != "business") {
                    forcedRubro = null
                    loadProducts()
                    return@addOnSuccessListener
                }

                val bType = doc.getString("business_type") ?: doc.id
                if (bType == "HOTEL") loadHotelRooms()
                else if (bType == "LOANS") loadLoanOffers()
                else loadInventoryProducts(bType)
            }
            .addOnFailureListener { handleLoadError(it) }
    }

    private fun loadHotelRooms() {
        db.collection("users").document(storeId)
            .collection("hotel_rooms")
            .get()
            .addOnSuccessListener { querySnapshot ->
                val roomList = querySnapshot.documents.mapNotNull { doc ->
                    if (doc.getBoolean("isDeleted") == true) return@mapNotNull null
                    
                    try {
                        val roomNum = doc.getString("number") ?: ""
                        val roomType = doc.getString("type") ?: "Simple"
                        val price = doc.getDouble("baseRate") ?: 0.0
                        val desc = doc.getString("specificDescription") ?: doc.getString("description") ?: ""
                        val photo = doc.getString("photoUrl")
                        val status = doc.getString("status") ?: "FREE"

                        val product = ProductEntity()
                        product.id = doc.id
                        product.nombre = "Habitación $roomNum"
                        product.categoria = "Piso ${doc.getLong("floor") ?: 1} - $roomType"
                        product.descripcion = desc
                        product.precioVenta = price
                        product.photoUrl = photo
                        product.location = status 
                        product.codigo = roomNum // Para ordenar
                        product
                    } catch (e: Exception) { null }
                }

                // ORDENAR NUMÉRICAMENTE
                val sortedProducts = roomList.sortedBy { 
                    it.codigo?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 999 
                }

                updateAdapter(sortedProducts, "HOTEL")
            }
            .addOnFailureListener { handleLoadError(it) }
    }

    private fun loadLoanOffers() { loadInventoryProducts("LOANS") }

    private fun loadInventoryProducts(sType: String) {
        db.collection("users").document(storeId)
            .collection("inventory")
            .get()
            .addOnSuccessListener { querySnapshot ->
                val products = querySnapshot.documents.mapNotNull { doc ->
                    if (doc.getBoolean("isDeleted") == true) return@mapNotNull null
                    try {
                        val product = ProductEntity()
                        product.id = doc.id
                        product.nombre = doc.getString("nombre") ?: "Producto"
                        product.categoria = doc.getString("categoria") ?: "General"
                        product.descripcion = doc.getString("descripcion") ?: ""
                        product.precioVenta = doc.getDouble("precioVenta") ?: 0.0
                        product.photoUrl = doc.getString("photoUrl")
                        product
                    } catch (e: Exception) { null }
                }
                updateAdapter(products, sType)
            }
            .addOnFailureListener { handleLoadError(it) }
    }

    private fun updateAdapter(products: List<ProductEntity>, sType: String) {
        adapter.setProducts(products, sType)
        binding.layoutLoadingCatalog.visibility = View.GONE
    }

    private fun handleLoadError(e: Exception) {
        binding.layoutLoadingCatalog.visibility = View.GONE
        Toast.makeText(this, "Error al cargar catálogo", Toast.LENGTH_SHORT).show()
    }

    inner class CatalogAdapter(private val onAddClick: (ProductEntity) -> Unit) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {
        private var allProducts = listOf<ProductEntity>()
        private var filteredProducts = listOf<ProductEntity>()
        private var storeType: String = "PRODUCTS"

        fun setProducts(list: List<ProductEntity>, type: String = "PRODUCTS") {
            allProducts = list
            filteredProducts = list
            storeType = type
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filteredProducts = if (query.isEmpty()) allProducts
            else allProducts.filter { it.nombre.lowercase().contains(query.lowercase()) }
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemCatalogProductBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemCatalogProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = filteredProducts[position]
            with(holder.b) {
                tvProductName.text = product.nombre
                tvProductCategory.text = product.categoria
                
                if (storeType == "HOTEL") {
                    tvProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", product.precioVenta)}"
                    val status = product.location ?: "FREE"
                    when (status) {
                        "OCCUPIED" -> {
                            tvProductDescription.text = "🚫 OCUPADA"
                            tvProductDescription.setTextColor(root.context.getColor(R.color.red_600))
                            btnAddToCart.visibility = View.GONE
                        }
                        "RESERVED" -> {
                            tvProductDescription.text = "⏳ RESERVADA"
                            tvProductDescription.setTextColor(root.context.getColor(R.color.sky_600))
                            btnAddToCart.visibility = View.GONE
                        }
                        else -> {
                            tvProductDescription.text = "✅ DISPONIBLE"
                            tvProductDescription.setTextColor(root.context.getColor(R.color.emerald_600))
                            btnAddToCart.visibility = View.VISIBLE
                        }
                    }
                    btnAddToCart.text = "RESERVAR"
                } else {
                    tvProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", product.precioVenta)}"
                    tvProductDescription.text = product.descripcion
                    tvProductDescription.setTextColor(root.context.getColor(R.color.slate_500))
                    btnAddToCart.visibility = View.VISIBLE
                    btnAddToCart.text = "AGREGAR"
                }
                
                if (!product.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivProductImage.context).load(product.photoUrl).into(ivProductImage)
                } else {
                    ivProductImage.setImageResource(R.drawable.gomezmart_icon)
                }
                
                btnAddToCart.setOnClickListener { 
                    if (storeType == "HOTEL") navigateToDetail(product, holder.itemView.context) else onAddClick(product)
                }
                root.setOnClickListener { navigateToDetail(product, holder.itemView.context) }
            }
        }

        private fun navigateToDetail(product: ProductEntity, context: Context) {
            val intent = Intent(context, ProductDetailActivity::class.java).apply {
                putExtra("PROD_ID", product.id)
                putExtra("PROD_NAME", product.nombre)
                putExtra("PROD_DESC", product.descripcion)
                putExtra("PROD_PRICE", product.precioVenta)
                putExtra("PROD_CAT", product.categoria)
                putExtra("PROD_PHOTO", product.photoUrl)
                putExtra("STORE_ID", storeId)
                putExtra("STORE_NAME", storeName)
                putExtra("STORE_TYPE", storeType)
            }
            context.startActivity(intent)
        }

        override fun getItemCount() = filteredProducts.size
    }
}
