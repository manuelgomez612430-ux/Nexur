package com.naxor.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.naxor.app.data.PaymentMethod
import com.naxor.app.data.StoreModel
import com.naxor.app.data.StoreReview
import com.naxor.app.databinding.ActivityStoreProfileBinding
import com.naxor.app.databinding.ItemStoreHorizontalBinding
import com.naxor.app.databinding.ItemStoreReviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class StoreProfileActivity : BaseActivity() {

    private lateinit var storeProfileBinding: ActivityStoreProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var storeRegistration: ListenerRegistration? = null
    private var reviewsRegistration: ListenerRegistration? = null
    
    private lateinit var reviewsAdapter: ReviewsAdapter
    private lateinit var recommendationsAdapter: RecommendationsAdapter
    private var storeId: String = ""
    private var myReview: StoreReview? = null

    private var currentLat = 0.0
    private var currentLng = 0.0
    private var storePhone = ""
    private var businessType = "PRODUCTS"
    private val paymentMethods = mutableListOf<PaymentMethod>()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            setupMapWithUserLocation()
        } else {
            Toast.makeText(this, "Se requiere permiso de ubicación para mostrar tu posición en el mapa", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        storeProfileBinding = ActivityStoreProfileBinding.inflate(layoutInflater)
        setContentView(storeProfileBinding.root)

        storeId = intent.getStringExtra("STORE_ID") ?: ""
        val storeName = intent.getStringExtra("STORE_NAME") ?: "Tienda"
        val isOwner = intent.getBooleanExtra("IS_OWNER", false)
        val forcedRubro = intent.getStringExtra("FORCE_RUBRO")
        
        storeProfileBinding.toolbarStoreProfile.setNavigationOnClickListener { finish() }
        
        setupMap()
        if (isOwner) {
            storeProfileBinding.btnEditStoreProfile.visibility = View.VISIBLE
            storeProfileBinding.btnEditStoreProfile.setOnClickListener {
                val intent = Intent(this, StoreEditorActivity::class.java).apply {
                    putExtra("STORE_ID", storeId)
                    putExtra("FORCE_RUBRO", forcedRubro)
                }
                startActivity(intent)
            }
        }

        setupReviewsRecyclerView()
        setupRecommendationsRecyclerView()
        startStoreListener(storeId, forcedRubro)
        startReviewsListener(storeId)

        storeProfileBinding.btnSubmitReview.setOnClickListener { submitReview() }
        
        storeProfileBinding.btnCancelReview.setOnClickListener {
            if (myReview != null) {
                storeProfileBinding.cardAddReview.visibility = View.GONE
            }
            storeProfileBinding.etNewReviewComment.setText("")
            storeProfileBinding.rbNewReview.rating = 5f
        }
        
        storeProfileBinding.tvProfileStoreAddress.setOnClickListener { openGoogleMaps() }
        storeProfileBinding.btnHowToGetThere.setOnClickListener { openGoogleMaps() }

        storeProfileBinding.btnContactWhatsApp.setOnClickListener {
            if (storePhone.isNotEmpty()) {
                val url = "https://api.whatsapp.com/send?phone=$storePhone"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }
    }

    private fun setupRecommendationsRecyclerView() {
        recommendationsAdapter = RecommendationsAdapter { recommendedStore ->
            val intent = Intent(this, StoreProfileActivity::class.java).apply {
                putExtra("STORE_ID", recommendedStore.id)
                putExtra("STORE_NAME", recommendedStore.name)
                putExtra("FORCE_RUBRO", recommendedStore.businessType)
            }
            startActivity(intent)
        }
        storeProfileBinding.rvStoreRecommendations.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        storeProfileBinding.rvStoreRecommendations.adapter = recommendationsAdapter
    }

    private var lastRecommendedType = ""
    private fun loadRecommendations(type: String) {
        if (type == lastRecommendedType) return // Evitar recargas innecesarias
        lastRecommendedType = type
        
        lifecycleScope.launch {
            try {
                val storesSnap = db.collectionGroup("settings")
                    .whereEqualTo("business_type", type)
                    .orderBy("creation_date", Query.Direction.DESCENDING) // Indexado
                    .limit(8)
                    .get().await()
                
                val recommendations = storesSnap.documents.mapNotNull { doc ->
                    val pathParts = doc.reference.path.split("/")
                    if (pathParts.size < 2) return@mapNotNull null
                    val sId = pathParts[1]
                    
                    if (sId == storeId) return@mapNotNull null
                    
                    val name = doc.getString("business_name") ?: return@mapNotNull null
                    if (name.isEmpty() || name.equals("Mi Negocio", ignoreCase = true)) return@mapNotNull null

                    StoreModel(
                        id = sId,
                        name = name,
                        businessType = doc.getString("business_type") ?: type,
                        logoUrl = doc.getString("business_logo_url"),
                        address = doc.getString("business_address") ?: "",
                        creationDate = doc.getLong("creation_date") ?: 0L
                    )
                }

                withContext(Dispatchers.Main) {
                    recommendationsAdapter.updateData(recommendations)
                    
                    if (recommendations.isEmpty()) {
                        storeProfileBinding.tvNoRecommendations.visibility = View.VISIBLE
                        storeProfileBinding.tvNoRecommendations.text = if (type == "HOTEL") "Hospedaje no encontrado" else "Negocio no encontrado"
                        storeProfileBinding.rvStoreRecommendations.visibility = View.GONE
                    } else {
                        storeProfileBinding.tvNoRecommendations.visibility = View.GONE
                        storeProfileBinding.rvStoreRecommendations.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    storeProfileBinding.tvNoRecommendations.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showPaymentMethodsDialog() {
        if (paymentMethods.isEmpty()) return

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Métodos de Pago 💳")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        paymentMethods.forEach { method ->
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 20)
            }

            val tvIcon = TextView(this).apply {
                text = when {
                    method.title.lowercase().contains("yape") -> "🟣"
                    method.title.lowercase().contains("plin") -> "🟢"
                    else -> "🏦"
                }
                textSize = 20f
            }

            val tvInfo = TextView(this).apply {
                text = "${method.title}\n${method.accountInfo}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 30
                }
                setTextColor(Color.parseColor("#334155"))
            }

            val btnCopy = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setBackgroundResource(0)
                setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Cuenta", method.accountInfo)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@StoreProfileActivity, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                }
            }

            itemView.addView(tvIcon)
            itemView.addView(tvInfo)
            itemView.addView(btnCopy)
            container.addView(itemView)
        }

        builder.setView(container)
        builder.setPositiveButton("Cerrar", null)
        builder.show()
    }

    private fun renderPublicPaymentMethods() {
        storeProfileBinding.layoutPaymentIconsContainer.removeAllViews()
        if (paymentMethods.isEmpty()) {
            storeProfileBinding.layoutPublicPaymentMethods.visibility = View.GONE
            return
        }

        storeProfileBinding.layoutPublicPaymentMethods.visibility = View.VISIBLE
        paymentMethods.forEach { method ->
            val tv = TextView(this).apply {
                text = method.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#6366F1"))
                setPadding(0, 0, 32, 0)
                setOnClickListener { showPaymentMethodsDialog() }
            }
            storeProfileBinding.layoutPaymentIconsContainer.addView(tv)
        }
    }

    private fun openGoogleMaps() {
        if (currentLat != 0.0 && currentLng != 0.0) {
            // "google.navigation:q=lat,lng" abre directamente la navegación en Google Maps
            val uri = Uri.parse("google.navigation:q=$currentLat,$currentLng")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: Abrir en el navegador si no hay Google Maps instalado
                val webUri = "https://www.google.com/maps/dir/?api=1&destination=$currentLat,$currentLng"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
            }
        } else {
            Toast.makeText(this, "Ubicación del negocio no disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationPermissions() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (fine != PackageManager.PERMISSION_GRANTED || coarse != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            setupMapWithUserLocation()
        }
    }

    private fun setupMapWithUserLocation() {
        // Aquí podríamos activar el overlay de "MyLocation" de osmdroid si fuera necesario
        // Por ahora, solo aseguramos que el mapa esté centrado en el negocio
        if (currentLat != 0.0 && currentLng != 0.0) {
            val point = GeoPoint(currentLat, currentLng)
            storeProfileBinding.mvStoreProfile.controller.setCenter(point)
        }
    }

    private fun setupMap() {
        storeProfileBinding.mvStoreProfile.apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }

    private fun updateMapLocation(lat: Double, lng: Double, businessName: String) {
        currentLat = lat
        currentLng = lng
        if (lat == 0.0 && lng == 0.0) {
            storeProfileBinding.cardStoreMap.visibility = View.GONE
            return
        }

        storeProfileBinding.cardStoreMap.visibility = View.VISIBLE
        val point = GeoPoint(lat, lng)
        storeProfileBinding.mvStoreProfile.controller.setZoom(17.0)
        storeProfileBinding.mvStoreProfile.controller.setCenter(point)

        storeProfileBinding.mvStoreProfile.overlays.clear()
        val marker = Marker(storeProfileBinding.mvStoreProfile)
        marker.position = point
        marker.title = businessName
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        storeProfileBinding.mvStoreProfile.overlays.add(marker)
        
        // Solicitar permisos cuando el mapa se muestra para mejorar la experiencia
        checkLocationPermissions()
    }

    private fun renderExtraSections(sections: List<Map<String, Any>>) {
        storeProfileBinding.layoutExtraSectionsProfile.removeAllViews()
        sections.forEach { m ->
            val title = m["title"] as? String ?: ""
            val desc = m["description"] as? String ?: ""
            val urls = (m["imageUrls"] as? List<*>)?.map { it.toString() } ?: emptyList()

            if (title.isNotEmpty() || desc.isNotEmpty() || urls.isNotEmpty()) {
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_store_extra_section, storeProfileBinding.layoutExtraSectionsProfile, false)
                val tvTitle = itemView.findViewById<TextView>(R.id.tvSectionTitle)
                val tvDesc = itemView.findViewById<TextView>(R.id.tvSectionContent)
                val vpImages = itemView.findViewById<ViewPager2>(R.id.vpSectionImages)
                val tabLayout = itemView.findViewById<TabLayout>(R.id.tlPagerIndicator)
                val cardCarousel = itemView.findViewById<View>(R.id.cardSectionCarousel)

                tvTitle.text = title
                tvDesc.text = desc
                if (title.isEmpty()) tvTitle.visibility = View.GONE
                if (desc.isEmpty()) tvDesc.visibility = View.GONE

                if (urls.isNotEmpty()) {
                    cardCarousel.visibility = View.VISIBLE
                    val imageAdapter = SectionImageAdapter(urls)
                    vpImages.adapter = imageAdapter
                    
                    if (urls.size > 1) {
                        tabLayout.visibility = View.VISIBLE
                        TabLayoutMediator(tabLayout, vpImages) { _, _ -> }.attach()
                        
                        // [NUEVO] AUTO-SCROLL CADA 4 SEGUNDOS
                        val handler = Handler(Looper.getMainLooper())
                        val runnable = object : Runnable {
                            override fun run() {
                                var current = vpImages.currentItem
                                current = (current + 1) % urls.size
                                vpImages.setCurrentItem(current, true)
                                handler.postDelayed(this, 4000)
                            }
                        }
                        handler.postDelayed(runnable, 4000)
                    } else {
                        tabLayout.visibility = View.GONE
                    }
                } else {
                    cardCarousel.visibility = View.GONE
                    tabLayout.visibility = View.GONE
                }

                storeProfileBinding.layoutExtraSectionsProfile.addView(itemView)
            }
        }
    }

    inner class SectionImageAdapter(private val urls: List<String>) : RecyclerView.Adapter<SectionImageAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iv = view as ImageView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            return ViewHolder(iv)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            Glide.with(holder.itemView)
                .load(urls[position])
                .placeholder(R.drawable.gomezmart_full_bg)
                .error(R.drawable.gomezmart_full_bg)
                .thumbnail(0.1f)
                .override(800, 600) // Reducir resolución para carrusel
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.iv)
        }
        override fun getItemCount() = urls.size
    }

    private fun setupReviewsRecyclerView() {
        reviewsAdapter = ReviewsAdapter()
        storeProfileBinding.rvStoreReviews.layoutManager = LinearLayoutManager(this)
        storeProfileBinding.rvStoreReviews.adapter = reviewsAdapter
    }

    private var lastStoreData: String = ""
    private fun startStoreListener(sId: String, forcedRubro: String?) {
        if (sId.isEmpty()) return
        val docId = forcedRubro ?: "business"
        val intentName = intent.getStringExtra("STORE_NAME") ?: "Tienda GoMezMart"

        storeRegistration = db.collection("users").document(sId)
            .collection("settings").document(docId)
            .addSnapshotListener { doc, e ->
                if (e != null || doc == null || !doc.exists()) {
                    // Si el documento específico no existe, probar con el genérico 'business'
                    if (forcedRubro != null && docId != "business") {
                        startStoreListener(sId, null)
                    } else {
                        // Si nada existe, mostrar al menos el nombre que tenemos
                        storeProfileBinding.tvProfileStoreName.text = intentName
                    }
                    return@addSnapshotListener
                }
                
                // Optimización: Solo procesar si el documento cambió realmente
                val currentData = doc.data.toString()
                if (currentData == lastStoreData) return@addSnapshotListener
                lastStoreData = currentData

                val name = doc.getString("business_name") ?: doc.getString("${docId}_name") ?: intentName
                val desc = doc.getString("business_description") ?: ""
                val addr = doc.getString("business_address") ?: ""
                val logo = doc.getString("business_logo_url")
                val cover = doc.getString("business_cover_url")
                val owner = doc.getString("business_owner_name") ?: "Propietario GoMezMart"
                val bType = doc.getString("business_type") ?: forcedRubro ?: "PRODUCTS"
                var rubro = doc.getString("business_category") ?: "Tienda"
                
                if (bType == "PRODUCTS" && (rubro == "Tienda" || rubro.isEmpty())) {
                    rubro = "TIENDA DE PRODUCTOS"
                } else if (bType == "HOTEL" && (rubro == "Tienda" || rubro.isEmpty())) {
                    rubro = "HOSPEDAJE / TURISMO"
                } else if (bType == "LOANS" && (rubro == "Tienda" || rubro.isEmpty())) {
                    rubro = "SERVICIOS FINANCIEROS"
                }
                
                businessType = bType
                storePhone = doc.getString("business_phone") ?: ""

                storeProfileBinding.tvProfileStoreName.text = name
                storeProfileBinding.tvProfileStoreDesc.text = desc
                storeProfileBinding.tvProfileStoreAddress.text = "📍 $addr"
                storeProfileBinding.tvProfileOwnerName.text = owner
                storeProfileBinding.tvProfileStoreCategory.text = rubro
                
                storeProfileBinding.btnContactWhatsApp.visibility = if (storePhone.isNotEmpty()) View.VISIBLE else View.GONE
                
                if (bType == "HOTEL") {
                    storeProfileBinding.cardHotelAvailability.visibility = View.VISIBLE
                    loadHotelAvailability(sId)
                } else {
                    storeProfileBinding.cardHotelAvailability.visibility = View.GONE
                }

                val glide = Glide.with(this@StoreProfileActivity)

                if (!logo.isNullOrEmpty()) {
                    glide.load(logo)
                        .placeholder(R.drawable.gomezmart_icon)
                        .error(R.drawable.gomezmart_icon)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(storeProfileBinding.ivProfileStoreLogo)
                }
                
                if (!cover.isNullOrEmpty()) {
                    glide.load(cover)
                        .placeholder(R.drawable.gomezmart_full_bg)
                        .error(R.drawable.gomezmart_full_bg)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .into(storeProfileBinding.ivStoreCover)
                }

                val lat = doc.getDouble("latitude") ?: 0.0
                val lng = doc.getDouble("longitude") ?: 0.0
                updateMapLocation(lat, lng, name)

                val extras = doc.get("extraSections") as? List<Map<String, Any>>
                if (extras != null) renderExtraSections(extras)

                val payments = doc.get("paymentMethods") as? List<Map<String, Any>>
                if (payments != null) {
                    paymentMethods.clear()
                    payments.forEach { m ->
                        paymentMethods.add(PaymentMethod(
                            type = m["type"] as? String ?: "BANK",
                            title = m["title"] as? String ?: "",
                            accountInfo = m["accountInfo"] as? String ?: ""
                        ))
                    }
                    renderPublicPaymentMethods()
                }

                val themeMap = doc.get("theme") as? Map<String, Any>
                if (themeMap != null) applyDynamicTheme(themeMap)

                // Cargar recomendaciones solo la primera vez o si cambia el rubro
                if (bType != lastRecommendedType) {
                    loadRecommendations(bType)
                }
            }
    }

    private fun applyDynamicTheme(theme: Map<String, Any>) {
        val primary = theme["primaryColor"] as? String ?: "#7C3AED"
        val text = theme["textColor"] as? String ?: "#0F172A"
        val bg = theme["backgroundColor"] as? String ?: "#FFFFFF"
        val font = theme["fontStyle"] as? String ?: "MODERN"
        val decor = theme["decorations"] as? String ?: "CIRCLES"

        try {
            val primaryColor = Color.parseColor(primary)
            val textColor = Color.parseColor(text)
            val bgColor = Color.parseColor(bg)

            storeProfileBinding.nsvStoreProfile.setBackgroundColor(bgColor)
            storeProfileBinding.tvProfileStoreName.setTextColor(textColor)
            storeProfileBinding.tvProfileStoreDesc.setTextColor(textColor)
            
            val typeface = when(font) {
                "CLASSIC" -> Typeface.SERIF
                "BOLD" -> Typeface.DEFAULT_BOLD
                else -> Typeface.SANS_SERIF
            }
            storeProfileBinding.tvProfileStoreName.typeface = typeface

            if (decor == "CIRCLES" || decor == "MIXED") {
                storeProfileBinding.viewDecor1.visibility = View.VISIBLE
                storeProfileBinding.viewDecor2.visibility = View.VISIBLE
                val circleColor = (primaryColor and 0x00FFFFFF) or 0x0D000000 
                storeProfileBinding.viewDecor1.backgroundTintList = ColorStateList.valueOf(circleColor)
                storeProfileBinding.viewDecor2.backgroundTintList = ColorStateList.valueOf(circleColor)
            } else {
                storeProfileBinding.viewDecor1.visibility = View.GONE
                storeProfileBinding.viewDecor2.visibility = View.GONE
            }
        } catch (e: Exception) {}
    }

    private fun startReviewsListener(sId: String) {
        reviewsRegistration = db.collection("users").document(sId).collection("reviews")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val reviews = snapshot.documents.mapNotNull { it.toObject(StoreReview::class.java) }.distinctBy { it.userId }
                reviewsAdapter.updateData(reviews)
                storeProfileBinding.tvNoReviews.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE
                
                // Lógica para ocultar/mostrar el editor de comentarios
                val currentUserId = auth.currentUser?.uid
                myReview = reviews.find { it.userId == currentUserId }
                
                if (myReview != null) {
                    storeProfileBinding.cardAddReview.visibility = View.GONE
                } else {
                    storeProfileBinding.cardAddReview.visibility = View.VISIBLE
                    storeProfileBinding.btnSubmitReview.text = "PUBLICAR COMENTARIO"
                }
            }
    }

    private fun submitReview() {
        val user = auth.currentUser ?: return
        val comment = storeProfileBinding.etNewReviewComment.text.toString().trim()
        val rating = storeProfileBinding.rbNewReview.rating.toInt()

        if (comment.isEmpty()) {
            Toast.makeText(this, "Por favor escribe un comentario", Toast.LENGTH_SHORT).show()
            return
        }

        val reviewId = user.uid // Un comentario por usuario
        val review = StoreReview(
            id = reviewId,
            userId = user.uid,
            userName = user.displayName ?: "Usuario GoMezMart",
            userPhotoUrl = user.photoUrl?.toString(),
            comment = comment,
            rating = rating,
            timestamp = System.currentTimeMillis()
        )

        db.collection("users").document(storeId).collection("reviews").document(reviewId)
            .set(review)
            .addOnSuccessListener {
                Toast.makeText(this, "¡Comentario guardado! ✨", Toast.LENGTH_SHORT).show()
                storeProfileBinding.etNewReviewComment.setText("")
                storeProfileBinding.rbNewReview.rating = 5f
                storeProfileBinding.cardAddReview.visibility = View.GONE
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al guardar comentario", Toast.LENGTH_SHORT).show()
            }
    }

    private fun startEditReview(review: StoreReview) {
        storeProfileBinding.etNewReviewComment.setText(review.comment)
        storeProfileBinding.rbNewReview.rating = review.rating.toFloat()
        storeProfileBinding.cardAddReview.visibility = View.VISIBLE
        storeProfileBinding.btnSubmitReview.text = "ACTUALIZAR COMENTARIO"
        
        // Hacer scroll hasta el editor
        storeProfileBinding.nsvStoreProfile.smoothScrollTo(0, storeProfileBinding.cardAddReview.top)
    }
    private fun loadHotelAvailability(sId: String) { /* ... */ }

    override fun onDestroy() {
        super.onDestroy()
        storeRegistration?.remove()
        reviewsRegistration?.remove()
    }

    inner class RecommendationsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        fun updateData(list: List<StoreModel>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemStoreHorizontalBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemStoreHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                tvStoreNameSmall.text = s.name
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvStoreDateSmall.text = if (s.creationDate > 0) "Desde: ${sdf.format(Date(s.creationDate))}" else ""
                
                Glide.with(ivStoreLogoSmall.context)
                    .load(s.logoUrl)
                    .placeholder(R.drawable.gomezmart_icon)
                    .error(R.drawable.gomezmart_icon)
                    .override(120, 120) // Tamaño pequeño para recomendaciones
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(ivStoreLogoSmall)
                
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class ReviewsAdapter : RecyclerView.Adapter<ReviewsAdapter.ViewHolder>() {
        private var items = listOf<StoreReview>()
        fun updateData(list: List<StoreReview>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemStoreReviewBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvReviewUserName.text = item.userName
                tvReviewComment.text = item.comment
                rbReviewStars.rating = item.rating.toFloat()
                if (!item.userPhotoUrl.isNullOrEmpty()) Glide.with(ivReviewUserPhoto).load(item.userPhotoUrl).circleCrop().into(ivReviewUserPhoto)
                else ivReviewUserPhoto.setImageResource(R.drawable.gomezmart_icon)
                
                // Solo el autor puede editar su comentario
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                btnEditReview.visibility = if (item.userId == currentUserId) View.VISIBLE else View.GONE
                btnEditReview.setOnClickListener { startEditReview(item) }
            }
        }
        override fun getItemCount() = items.size
    }
}
