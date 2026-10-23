package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.auth.FirebaseAuth
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.Query
import com.gomezmart.app.data.*
import com.gomezmart.app.databinding.ActivityProductDetailBinding
import com.gomezmart.app.databinding.ItemRecentlyViewedBinding
import com.gomezmart.app.databinding.ItemStoreReviewBinding
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.FloatingCartHelper
import com.gomezmart.app.util.GlideHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class ProductDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityProductDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val registrations = mutableListOf<ListenerRegistration>()
    
    private lateinit var similarAdapter: SmallProductAdapter
    private lateinit var recommendedAdapter: SmallProductAdapter
    private lateinit var productReviewsAdapter: ProductReviewsAdapter
    private var myProductReview: StoreReview? = null
    private var productReviewsRegistration: ListenerRegistration? = null

    private var pendingProdId = ""
    private var pendingProdName = ""
    private var pendingPrice = 0.0
    private var pendingCat = ""
    private var pendingStoreId = ""
    private var pendingStoreName = ""
    private var pendingQty = 1

    private val mapSelectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra("LAT", 0.0) ?: 0.0
            val lng = result.data?.getDoubleExtra("LNG", 0.0) ?: 0.0
            val addr = result.data?.getStringExtra("ADDRESS") ?: "Nueva ubicación"
            createOrderFromDetail(pendingProdId, pendingProdName, pendingPrice, pendingCat, pendingStoreId, pendingStoreName, lat, lng, addr, "", pendingQty)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar Insets para evitar que la interfaz sea obstruida
        applyWindowInsets(binding.toolbarProductDetail, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.layoutProductDetailBottomButtons, applyTop = false, applyBottom = true)

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
        setupRecyclerViews(storeId, storeName, storeType)
        setupListeners(id, name, price, cat, storeId, storeName, storeLogo, storeType, photo, desc, storeAddr)
        
        if (id.isNotEmpty()) {
            saveToRecentlyViewed(MarketplaceProduct(
                id = id,
                name = name,
                category = cat,
                price = price,
                description = desc,
                photoUrl = photo,
                storeId = storeId,
                storeName = storeName,
                storeAddress = storeAddr,
                storeLogoUrl = storeLogo,
                storeType = storeType
            ))
        }

        loadProductRealtime(id, storeId, storeType)
        loadStoreRealtime(storeId, storeType)
        loadSimilarAndRecommended(id, storeId, cat, storeType, storeLogo)
    }

    private fun saveToRecentlyViewed(product: MarketplaceProduct) {
        try {
            val prefs = getSharedPreferences("BuyerPrefs", MODE_PRIVATE)
            val json = prefs.getString("recently_viewed", null)
            val type = object : TypeToken<MutableList<MarketplaceProduct>>() {}.type
            val list: MutableList<MarketplaceProduct> = if (json != null) Gson().fromJson(json, type) ?: mutableListOf() else mutableListOf()
            list.removeAll { it.id == product.id }
            list.add(0, product)
            if (list.size > 10) list.removeAt(list.size - 1)
            prefs.edit().putString("recently_viewed", Gson().toJson(list)).apply()
        } catch (e: Exception) {}
    }

    private fun loadStoreRealtime(sId: String, sType: String) {
        val docId = if (sType == "PRODUCTS" || sType == "SERVICES") "business" else sType
        
        val reg = db.collection("users").document(sId).collection("settings").document(docId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) {
                    if (docId != "business") {
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
                            similarAdapter.updateSeeMoreLogo(logoUrl)
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
                        binding.tvDetailName.text = name
                        binding.tvDetailCategory.text = cat
                        binding.tvDetailDescription.text = desc
                        binding.tvDetailPrice.text = if (sType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", price)} la noche" else "S/ ${String.format(Locale.US, "%.2f", price)}"
                        
                        if (!photo.isNullOrEmpty()) {
                            GlideHelper.loadImageWithProgressBar(
                                this@ProductDetailActivity,
                                photo,
                                binding.ivProductDetailImage,
                                binding.pbProductDetailLoading,
                                skipCache = true
                            )
                        } else {
                            binding.pbProductDetailLoading.visibility = View.GONE
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
        binding.btnBackProductDetail.setOnClickListener { finish() }
        binding.toolbarProductDetail.setNavigationOnClickListener { finish() }
    }

    private fun setupUI(name: String, desc: String, price: Double, cat: String, photo: String?, sName: String, sAddr: String, sLogo: String?) {
        val storeType = intent.getStringExtra("STORE_TYPE") ?: "PRODUCTS"
        
        binding.tvDetailName.text = name
        binding.tvDetailCategory.text = cat
        binding.tvDetailDescription.text = desc
        binding.tvDetailPrice.text = if (storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", price)} la noche" else "S/ ${String.format(Locale.US, "%.2f", price)}"
        binding.tvDetailStoreName.text = sName
        binding.tvDetailStoreAddress.text = sAddr

        if (storeType == "HOTEL") {
            binding.btnDetailDirectOrder.text = "Reservar 🛎️"
        } else {
            binding.btnDetailDirectOrder.text = "Hacer Pedido 🛵"
        }

        if (!sLogo.isNullOrEmpty()) {
            binding.tvStoreEmojiPlaceholder.visibility = View.GONE
            binding.ivDetailStoreLogo.visibility = View.VISIBLE
            Glide.with(this).load(sLogo).into(binding.ivDetailStoreLogo)
        } else {
            binding.tvStoreEmojiPlaceholder.visibility = View.VISIBLE
            binding.ivDetailStoreLogo.visibility = View.GONE
        }

        if (!photo.isNullOrEmpty()) {
            binding.ivProductDetailImage.setImageDrawable(null)
            GlideHelper.loadImageWithProgressBar(
                this,
                photo,
                binding.ivProductDetailImage,
                binding.pbProductDetailLoading
            )
        } else {
            binding.pbProductDetailLoading.visibility = View.GONE
            binding.ivProductDetailImage.setImageResource(R.drawable.gomezmart_icon)
        }
    }

    private fun openStoreProfile(sId: String, sName: String, sType: String) {
        if (sId.isEmpty()) return
        val targetClass = if (sType == "LOANS") LoanStoreProfileActivity::class.java else StoreProfileActivity::class.java
        val intent = Intent(this, targetClass).apply {
            putExtra("STORE_ID", sId)
            putExtra("STORE_NAME", sName)
            putExtra("FORCE_RUBRO", sType)
        }
        startActivityWithFade(intent)
    }

    private fun openChatWithStore(sId: String, sName: String, sLogo: String?) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para chatear con la tienda 🔐", Toast.LENGTH_SHORT).show()
            return
        }
        val myUid = user.uid
        if (sId.isEmpty() || sId == myUid) {
            Toast.makeText(this, "Esta es tu propia tienda", Toast.LENGTH_SHORT).show()
            return
        }

        val chatId = if (myUid < sId) "${myUid}_${sId}" else "${sId}_${myUid}"
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId)
            putExtra("OTHER_USER_ID", sId)
            putExtra("OTHER_USER_NAME", sName)
            putExtra("OTHER_USER_PHOTO", sLogo)
            putExtra("OTHER_USER_TITLE", "Dueño de $sName")
            putExtra("OTHER_USER_BIZ_LOGO", sLogo)
            putExtra("IS_DIRECT", true)
        }
        startActivityWithFade(intent)
    }

    private fun setupRecyclerViews(sId: String, sName: String, sType: String) {
        similarAdapter = SmallProductAdapter(
            onProductClick = { product -> navigateToProduct(product) },
            onSeeMoreStoreClick = { openStoreProfile(sId, sName, sType) }
        )
        binding.rvSimilarProducts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSimilarProducts.adapter = similarAdapter

        recommendedAdapter = SmallProductAdapter(
            onProductClick = { product -> navigateToProduct(product) }
        )
        binding.rvRecommendedProducts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecommendedProducts.adapter = recommendedAdapter

        productReviewsAdapter = ProductReviewsAdapter()
        binding.rvProductReviews.layoutManager = LinearLayoutManager(this)
        binding.rvProductReviews.adapter = productReviewsAdapter
    }

    private fun setupListeners(id: String, name: String, price: Double, cat: String, sId: String, sName: String, sLogo: String?, storeType: String, photo: String?, desc: String, storeAddr: String) {
        binding.cardDetailStoreInfo.setOnClickListener {
            openStoreProfile(sId, sName, storeType)
        }
        binding.btnDetailChatStore.setOnClickListener {
            openChatWithStore(sId, sName, sLogo)
        }
        binding.btnSubmitProductReview.setOnClickListener {
            submitProductReview(sId, id, storeType)
        }
        binding.btnCancelProductReview.setOnClickListener {
            resetProductReviewForm()
        }
        startProductReviewsListener(sId, id, storeType)

        updateFavoriteUI(id)
        binding.btnDetailFavorite.setOnClickListener {
            // Animación de escala al presionar
            binding.btnDetailFavorite.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(150)
                .withEndAction {
                    binding.btnDetailFavorite.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start()
                }
                .start()

            val p = MarketplaceProduct(id, name, cat, price, desc, photo, sId, sName, storeAddr, sLogo, storeType)
            FavoritesManager.toggleFavorite(this, p)
            updateFavoriteUI(id)
        }

        if (storeType == "HOTEL") {
            binding.btnDetailAddToCartOnly.visibility = View.GONE
        }
        
        binding.btnDetailAddToCartOnly.setOnClickListener {
            showQuantitySelectorDialog(name, price, "AGREGAR AL CARRITO") { qty ->
                if (CartManager.currentStoreId != sId && CartManager.getItems().isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Cambiar de Tienda")
                        .setMessage("Tu carrito pertenece a otra tienda. ¿Deseas vaciarlo y comprar en $sName?")
                        .setPositiveButton("Sí, Cambiar") { _, _ ->
                            CartManager.clear()
                            repeat(qty) {
                                CartManager.addItemFromManualData(id, name, price, cat, sId, sName, sLogo ?: "", photo)
                            }
                            Toast.makeText(this, "Añadido al nuevo carrito 🛒", Toast.LENGTH_SHORT).show()
                            updateAddToCartUI()
                            FloatingCartHelper.show(this)
                        }
                        .setNegativeButton("No", null)
                        .show()
                } else {
                    repeat(qty) {
                        CartManager.addItemFromManualData(id, name, price, cat, sId, sName, sLogo ?: "", photo)
                    }
                    Toast.makeText(this, "Añadido al carrito 🛒", Toast.LENGTH_SHORT).show()
                    updateAddToCartUI()
                    FloatingCartHelper.show(this)
                }
            }
        }

        if (storeType == "HOTEL") {
            binding.btnDetailDirectOrder.text = "Reservar 🛎️"
        }

        binding.btnDetailDirectOrder.setOnClickListener {
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
            
            showQuantitySelectorDialog(name, price, "REALIZAR PEDIDO") { qty ->
                startProductOrderFlow(id, name, price, cat, sId, sName, qty)
            }
        }

        if (CartManager.getItems().any { it.productId == id }) {
            updateAddToCartUI()
        }
    }

    private fun updateFavoriteUI(productId: String) {
        val isFav = FavoritesManager.isFavorite(this, productId)
        if (isFav) {
            binding.tvDetailFavoriteEmoji.text = "⭐"
            binding.tvDetailFavoriteEmoji.alpha = 1.0f
        } else {
            binding.tvDetailFavoriteEmoji.text = "☆"
            binding.tvDetailFavoriteEmoji.alpha = 0.4f
        }
    }

    private fun showQuantitySelectorDialog(prodName: String, price: Double, actionText: String, onConfirm: (Int) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_select_quantity, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        
        val tvValue = view.findViewById<TextView>(R.id.tvQtyDialogValue)
        val tvProductName = view.findViewById<TextView>(R.id.tvQtyDialogProductName)
        val tvTotal = view.findViewById<TextView>(R.id.tvQtyDialogTotal)
        val btnPlus = view.findViewById<View>(R.id.btnQtyDialogPlus)
        val btnMinus = view.findViewById<View>(R.id.btnQtyDialogMinus)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnQtyDialogConfirm)
        val btnCancel = view.findViewById<View>(R.id.btnQtyDialogCancel)

        var currentQty = 1
        
        fun updateDialogUI() {
            tvValue.text = currentQty.toString()
            val total = price * currentQty
            tvTotal.text = "S/ ${String.format(Locale.US, "%.2f", total)}"
        }
        
        tvProductName.text = prodName
        btnConfirm.text = actionText
        updateDialogUI()
        
        btnPlus.setOnClickListener {
            currentQty++
            updateDialogUI()
        }
        
        btnMinus.setOnClickListener {
            if (currentQty > 1) {
                currentQty--
                updateDialogUI()
            }
        }
        
        btnConfirm.setOnClickListener {
            onConfirm(currentQty)
            dialog.dismiss()
        }
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun updateAddToCartUI() {
        binding.btnDetailAddToCartOnly.apply {
            text = "Añadido 🛒"
            setBackgroundColor(Color.parseColor("#059669")) // emerald_600
            setTextColor(Color.WHITE)
        }
    }

    private fun startProductOrderFlow(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, qty: Int) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para pedir", Toast.LENGTH_SHORT).show()
            return
        }
        if (user.uid == sId) {
            Toast.makeText(this, "No puedes pedir en tu propio negocio", Toast.LENGTH_SHORT).show()
            return
        }

        showLocationSelection(prodId, prodName, price, cat, sId, sName, qty)
    }

    private fun showLocationSelection(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, qty: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location_selection, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<View>(R.id.btnLocCurrent).setOnClickListener {
            dialog.dismiss()
            useCurrentLocationForOrder(prodId, prodName, price, cat, sId, sName, qty)
        }
        view.findViewById<View>(R.id.btnLocSaved).setOnClickListener {
            dialog.dismiss()
            showRegisteredLocations(prodId, prodName, price, cat, sId, sName, qty)
        }
        view.findViewById<View>(R.id.btnLocNew).setOnClickListener {
            dialog.dismiss()
            openMapToSelectLocation(prodId, prodName, price, cat, sId, sName, qty)
        }
        view.findViewById<View>(R.id.btnLocPickup).setOnClickListener {
            dialog.dismiss()
            createOrderFromDetail(prodId, prodName, price, cat, sId, sName, 0.0, 0.0, "Retiro en Local", "", qty)
        }
        view.findViewById<View>(R.id.btnCancelLocation).setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun useCurrentLocationForOrder(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, qty: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
            return
        }
        
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                createOrderFromDetail(prodId, prodName, price, cat, sId, sName, loc.latitude, loc.longitude, "Ubicación actual", "", qty)
            } else {
                Toast.makeText(this, "No se pudo obtener tu ubicación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRegisteredLocations(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, qty: Int) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val locsSnap = db.collection("users").document(myUid).collection("locations").get().await()
                if (locsSnap.isEmpty) {
                    Toast.makeText(this@ProductDetailActivity, "No tienes ubicaciones registradas", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val names = locsSnap.documents.map { it.getString("title") ?: it.getString("name") ?: "Ubicación" }.toTypedArray()
                AlertDialog.Builder(this@ProductDetailActivity)
                    .setTitle("Tus ubicaciones")
                    .setItems(names) { _, which ->
                        val doc = locsSnap.documents[which]
                        val lat = doc.getDouble("lat") ?: doc.getDouble("latitude") ?: 0.0
                        val lng = doc.getDouble("lng") ?: doc.getDouble("longitude") ?: 0.0
                        val addr = doc.getString("address") ?: names[which]
                        val ref = doc.getString("reference") ?: ""
                        
                        createOrderFromDetail(prodId, prodName, price, cat, sId, sName, lat, lng, addr, ref, qty)
                    }
                    .show()
            } catch (e: Exception) {}
        }
    }

    private fun openMapToSelectLocation(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, qty: Int) {
        pendingProdId = prodId
        pendingProdName = prodName
        pendingPrice = price
        pendingCat = cat
        pendingStoreId = sId
        pendingStoreName = sName
        pendingQty = qty
        
        val intent = Intent(this, BusinessMapSelectorActivity::class.java)
        mapSelectorLauncher.launch(intent)
    }

    private fun createOrderFromDetail(prodId: String, prodName: String, price: Double, cat: String, sId: String, sName: String, lat: Double, lng: Double, address: String, reference: String, qty: Int) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        lifecycleScope.launch {
            try {
                val myName = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_full_name", "Comprador") ?: "Comprador"
                val orderId = UUID.randomUUID().toString()
                
                val item = CartItem(
                    productId = prodId,
                    productName = prodName,
                    price = price,
                    quantity = qty,
                    category = cat
                )

                val order = OrderModel(
                    id = orderId,
                    buyerId = myUid,
                    buyerName = myName,
                    storeId = sId,
                    storeName = sName,
                    items = listOf(item),
                    totalAmount = price * qty,
                    status = "PENDING",
                    timestamp = FieldValue.serverTimestamp(),
                    deliveryAddress = address,
                    deliveryReference = reference,
                    deliveryLat = lat,
                    deliveryLng = lng
                )

                db.collection("orders").document(orderId).set(order).await()
                
                val chatId = if (myUid < sId) "${myUid}_$sId" else "${sId}_$myUid"
                
                val reply = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = myUid,
                    message = "¡He realizado un pedido de $prodName! 🛵 Destino: $address",
                    timestamp = FieldValue.serverTimestamp(),
                    type = "ORDER_ALERT",
                    linkedOrderId = orderId,
                    deliveryLat = lat,
                    deliveryLng = lng,
                    deliveryAddress = address
                )
                db.collection("direct_chats").document(chatId).collection("messages").document(reply.id).set(reply)

                Toast.makeText(this@ProductDetailActivity, "Pedido realizado con éxito ✅", Toast.LENGTH_LONG).show()
                finish()
                
            } catch (e: Exception) {
                Toast.makeText(this@ProductDetailActivity, "Error al crear pedido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSimilarAndRecommended(currentProdId: String, sId: String, category: String, sType: String, sLogo: String? = null) {
        val currentName = intent.getStringExtra("PROD_NAME") ?: ""
        val storeName = intent.getStringExtra("STORE_NAME") ?: "Tienda"
        val storeLogo = sLogo ?: intent.getStringExtra("STORE_LOGO")
        
        lifecycleScope.launch {
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

                val seeMoreItem = MarketplaceProduct(
                    id = "SEE_MORE_STORE",
                    name = "Ver catálogo",
                    price = 0.0,
                    storeId = sId,
                    storeName = storeName,
                    storeLogoUrl = storeLogo,
                    storeType = sType
                )

                val displayList = if (sortedSimilar.size >= 4) {
                    sortedSimilar.take(4) + seeMoreItem
                } else if (sortedSimilar.isNotEmpty()) {
                    sortedSimilar + seeMoreItem
                } else {
                    emptyList()
                }

                if (displayList.isNotEmpty()) {
                    binding.layoutSimilarHeader.visibility = View.VISIBLE
                    binding.rvSimilarProducts.visibility = View.VISIBLE
                    similarAdapter.updateData(displayList)
                } else {
                    binding.layoutSimilarHeader.visibility = View.GONE
                    binding.rvSimilarProducts.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("ProductDetail", "Error similares: ${e.message}")
            }

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

                val filteredRecs = recList.filter { it.category == category }.toMutableList()
                if (filteredRecs.size < 5) {
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

    private fun startProductReviewsListener(sId: String, prodId: String, sType: String) {
        if (sId.isEmpty() || prodId.isEmpty()) return
        val collectionName = if (sType == "HOTEL") "hotel_rooms" else "inventory"
        
        productReviewsRegistration?.remove()
        productReviewsRegistration = db.collection("users").document(sId)
            .collection(collectionName).document(prodId).collection("reviews")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val reviews = snapshot.documents.mapNotNull { it.toObject(StoreReview::class.java) }.distinctBy { it.userId }
                productReviewsAdapter.updateData(reviews)

                if (reviews.isNotEmpty()) {
                    val avg = reviews.map { it.rating }.average()
                    binding.tvProductAverageRating.text = "★ ${String.format(Locale.US, "%.1f", avg)} (${reviews.size} opiniones)"
                    binding.tvNoProductReviews.visibility = View.GONE
                    binding.rvProductReviews.visibility = View.VISIBLE
                } else {
                    binding.tvProductAverageRating.text = "Sin calificaciones aún"
                    binding.tvNoProductReviews.visibility = View.VISIBLE
                    binding.rvProductReviews.visibility = View.GONE
                }

                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                myProductReview = reviews.find { it.userId == currentUserId }

                if (myProductReview != null) {
                    binding.btnSubmitProductReview.text = "ACTUALIZAR CALIFICACIÓN"
                } else {
                    binding.btnSubmitProductReview.text = "PUBLICAR CALIFICACIÓN"
                    binding.btnCancelProductReview.visibility = View.GONE
                }
            }
        
        productReviewsRegistration?.let { registrations.add(it) }
    }

    private fun submitProductReview(sId: String, prodId: String, sType: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para calificar este producto 🔐", Toast.LENGTH_SHORT).show()
            return
        }

        val comment = binding.etNewProductReviewComment.text.toString().trim()
        val rating = binding.rbNewProductReview.rating.toInt()

        if (comment.isEmpty()) {
            Toast.makeText(this, "Escribe una opinión sobre el producto", Toast.LENGTH_SHORT).show()
            return
        }

        val collectionName = if (sType == "HOTEL") "hotel_rooms" else "inventory"
        val reviewId = user.uid
        val review = StoreReview(
            id = reviewId,
            userId = user.uid,
            userName = user.displayName ?: "Comprador GoMezMart",
            userPhotoUrl = user.photoUrl?.toString(),
            comment = comment,
            rating = rating,
            timestamp = System.currentTimeMillis()
        )

        db.collection("users").document(sId)
            .collection(collectionName).document(prodId).collection("reviews")
            .document(reviewId)
            .set(review)
            .addOnSuccessListener {
                Toast.makeText(this, "¡Calificación guardada! ✨", Toast.LENGTH_SHORT).show()
                resetProductReviewForm()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al guardar calificación", Toast.LENGTH_SHORT).show()
            }
    }

    private fun resetProductReviewForm() {
        binding.etNewProductReviewComment.setText("")
        binding.rbNewProductReview.rating = 5f
        binding.btnCancelProductReview.visibility = View.GONE
        binding.btnSubmitProductReview.text = if (myProductReview != null) "ACTUALIZAR CALIFICACIÓN" else "PUBLICAR CALIFICACIÓN"
    }

    private fun startEditProductReview(review: StoreReview) {
        binding.etNewProductReviewComment.setText(review.comment)
        binding.rbNewProductReview.rating = review.rating.toFloat()
        binding.btnCancelProductReview.visibility = View.VISIBLE
        binding.btnSubmitProductReview.text = "ACTUALIZAR CALIFICACIÓN"
        
        binding.nsvProductDetail.smoothScrollTo(0, binding.cardAddProductReview.top)
    }

    inner class ProductReviewsAdapter : RecyclerView.Adapter<ProductReviewsAdapter.ViewHolder>() {
        private var items = listOf<StoreReview>()

        fun updateData(newList: List<StoreReview>) {
            if (items == newList) return
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos].id == newList[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newList[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newList
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val b: ItemStoreReviewBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemStoreReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvReviewUserName.text = item.userName
                tvReviewComment.text = item.comment
                rbReviewStars.rating = item.rating.toFloat()

                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                tvReviewDate.text = if (item.timestamp > 0) sdf.format(Date(item.timestamp)) else ""

                if (!item.userPhotoUrl.isNullOrEmpty()) {
                    Glide.with(ivReviewUserPhoto.context)
                        .load(item.userPhotoUrl)
                        .apply(GlideHelper.getOptions(ivReviewUserPhoto.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivReviewUserPhoto)
                } else {
                    ivReviewUserPhoto.setImageResource(R.drawable.gomezmart_icon)
                }

                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                btnEditReview.visibility = if (item.userId == currentUserId) View.VISIBLE else View.GONE
                btnEditReview.setOnClickListener { startEditProductReview(item) }
            }
        }

        override fun getItemCount() = items.size
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
        startActivityWithFade(intent)
        finish()
    }

    inner class SmallProductAdapter(
        private val onProductClick: (MarketplaceProduct) -> Unit,
        private val onSeeMoreStoreClick: (() -> Unit)? = null
    ) : RecyclerView.Adapter<SmallProductAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()

        fun updateSeeMoreLogo(logoUrl: String) {
            val updated = items.map {
                if (it.id == "SEE_MORE_STORE") it.copy(storeLogoUrl = logoUrl) else it
            }
            updateData(updated)
        }

        fun updateData(newList: List<MarketplaceProduct>) {
            if (items == newList) return
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos].id == newList[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newList[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newList
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val b: ItemRecentlyViewedBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemRecentlyViewedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                if (p.id == "SEE_MORE_STORE") {
                    tvRecentProductPrice.text = "➕ Ver más"
                    tvRecentProductPrice.setTextColor(ContextCompat.getColor(root.context, R.color.purple_600))
                    tvRecentProductName.text = "Ver catálogo"
                    
                    val pad = (10 * root.resources.displayMetrics.density).toInt()
                    ivRecentProductImage.setPadding(pad, pad, pad, pad)
                    ivRecentProductImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    ivRecentProductImage.setBackgroundColor(Color.parseColor("#F1F5F9"))

                    if (!p.storeLogoUrl.isNullOrEmpty()) {
                        Glide.with(ivRecentProductImage.context)
                            .load(p.storeLogoUrl)
                            .apply(GlideHelper.getOptions(ivRecentProductImage.context, showPlaceholder = false))
                            .circleCrop()
                            .into(ivRecentProductImage)
                    } else {
                        Glide.with(ivRecentProductImage.context).clear(ivRecentProductImage)
                        ivRecentProductImage.setImageResource(R.drawable.gomezmart_icon)
                    }

                    root.setOnClickListener {
                        onSeeMoreStoreClick?.invoke() ?: onProductClick(p)
                    }
                } else {
                    tvRecentProductName.text = p.name
                    tvRecentProductPrice.text = if (p.storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", p.price)} noche" else "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                    tvRecentProductPrice.setTextColor(ContextCompat.getColor(root.context, R.color.slate_900))
                    
                    ivRecentProductImage.setPadding(0, 0, 0, 0)
                    ivRecentProductImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivRecentProductImage.setBackgroundColor(Color.TRANSPARENT)
                    
                    if (!p.photoUrl.isNullOrEmpty()) {
                        Glide.with(ivRecentProductImage.context)
                            .load(p.photoUrl)
                            .apply(GlideHelper.getOptions(ivRecentProductImage.context, showPlaceholder = false))
                            .into(ivRecentProductImage)
                    } else {
                        ivRecentProductImage.setImageResource(R.drawable.gomezmart_icon)
                    }
                    root.setOnClickListener { onProductClick(p) }
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
