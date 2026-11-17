package com.gomezmart.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.ClipData
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.data.MarketplaceProduct
import com.gomezmart.app.data.PaymentMethod
import com.gomezmart.app.data.SocialRepository
import com.gomezmart.app.data.StoreFollower
import com.gomezmart.app.data.StoreModel
import com.gomezmart.app.data.StoreReview
import com.gomezmart.app.data.StoreSection
import com.gomezmart.app.data.StoreUpdate
import com.gomezmart.app.util.BillingManager
import com.gomezmart.app.databinding.ActivityStoreProfileBinding
import com.gomezmart.app.databinding.ItemMarketplaceGridProductBinding
import com.gomezmart.app.databinding.ItemStoreFollowerBinding
import com.gomezmart.app.databinding.ItemStoreHorizontalBinding
import com.gomezmart.app.databinding.ItemStoreReviewBinding
import com.gomezmart.app.databinding.ItemStoreUpdateBinding
import com.gomezmart.app.network.SupabaseManager
import com.gomezmart.app.util.GlideHelper
import com.gomezmart.app.util.SocialShareHelper
import androidx.core.content.FileProvider
import android.location.Geocoder
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class StoreProfileActivity : BaseActivity() {

    private lateinit var storeProfileBinding: ActivityStoreProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var storeRegistration: ListenerRegistration? = null
    private var reviewsRegistration: ListenerRegistration? = null
    private var productsRegistration: ListenerRegistration? = null
    private var followersRegistration: ListenerRegistration? = null
    private var updatesRegistration: ListenerRegistration? = null
    
    private lateinit var reviewsAdapter: ReviewsAdapter
    private lateinit var recommendationsAdapter: RecommendationsAdapter
    private lateinit var productsAdapter: ProductsAdapter
    private lateinit var storeUpdatesAdapter: StoreUpdatesAdapter
    private var currentStoreUpdates: List<StoreUpdate> = emptyList()
    private var allStoreProducts = mutableListOf<MarketplaceProduct>()
    private var storeId: String = ""
    private var isOwner: Boolean = false
    private var isClientPreviewMode: Boolean = false
    private val isEditingStore: Boolean get() = isOwner && !isClientPreviewMode
    private var activeSettingsDocId: String = "business"
    private var billingManager: BillingManager? = null
    private var isStoreVerified: Boolean = false
    private var cachedAverageRating: Double = 0.0
    private var cachedReviewCount: Int = 0
    private var cachedFollowersCount: Int = 0
    private var currentReviewsList: List<StoreReview> = emptyList()
    private var currentFollowersList: List<StoreFollower> = emptyList()
    private var isFollowingStore: Boolean = false
    private var isFollowActionInFlight: Boolean = false
    private var followersSheetAdapter: FollowersAdapter? = null
    private var followersSheetCountView: TextView? = null
    private var followersSheetEmptyView: TextView? = null
    private var myReview: StoreReview? = null

    private var currentLat = 0.0
    private var currentLng = 0.0
    private var storePhone = ""
    private var storeLogoUrl: String? = null
    private var storeLogoPosition: String = "LEFT" // "LEFT", "CENTER", "RIGHT"
    private var storeCoverUrl: String? = null
    private var storeDisplayName: String = ""
    private var storeDisplayNameStyled: String = ""
    private var storeDescription: String = ""
    private var storeDescriptionStyled: String = ""
    private var storeOwnerName: String = ""
    private var storeOwnerNameStyled: String = ""
    private var storeAddress: String = ""
    private var storeAddressStyled: String = ""
    private var isStoreOnline: Boolean = false
    private var businessType = "PRODUCTS"
    private val paymentMethods = mutableListOf<PaymentMethod>()
    private val extraSections = mutableListOf<StoreSection>()

    private var headerBgType: String = "NONE" // "NONE", "COLOR", "GRADIENT", "IMAGE"
    private var headerBgValue: String = ""
    private var headerBgOverlay: Double = 0.45
    private var headerTextTheme: String = "DARK" // "DARK" or "LIGHT"

    private var currentCropType = "" // "LOGO", "COVER", "SECTION", "HEADER_BG"
    private var currentTargetSectionId: String? = null
    private var cameraImageUri: Uri? = null

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uriContent = result.uriContent ?: return@registerForActivityResult
            when (currentCropType) {
                "LOGO" -> uploadInlineImage(uriContent, "LOGO")
                "COVER" -> uploadInlineImage(uriContent, "COVER")
                "SECTION" -> uploadMultipleSectionImages(listOf(uriContent))
                "SECTION_REPLACE" -> replaceSectionImage(uriContent)
                "HEADER_BG" -> uploadInlineImage(uriContent, "HEADER_BG")
            }
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            startCrop(cameraImageUri!!, currentCropType)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            openCamera(currentCropType)
        } else {
            Toast.makeText(this, "Se requiere permiso de cámara para tomar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "LOGO") }
    }

    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "COVER") }
    }

    private val pickHeaderBgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "HEADER_BG") }
    }

    private val pickSectionImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uploadMultipleSectionImages(uris)
        }
    }

    private val mapSelectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val lat = data?.getDoubleExtra("LAT", 0.0) ?: 0.0
            val lng = data?.getDoubleExtra("LNG", 0.0) ?: 0.0
            val addr = data?.getStringExtra("ADDRESS")?.trim().orEmpty()
            saveInlineLocation(lat, lng, addr)
        }
    }

    private var storeMapView: MapView? = null
    private var mapInitJob: Job? = null
    private val carouselHandlers = mutableListOf<Pair<Handler, Runnable>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storeProfileBinding = ActivityStoreProfileBinding.inflate(layoutInflater)
        setContentView(storeProfileBinding.root)

        showLoadingOverlay()

        // Manejar insets para evitar que la barra superior o el botón flotante choquen con las barras del sistema
        applyWindowInsets(storeProfileBinding.nsvStoreProfile, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(storeProfileBinding.layoutBottomActionBar, applyTop = false, applyBottom = true)
        applyWindowInsetsMargins(storeProfileBinding.btnOwnerVerifiedBadgeStatus, applyTop = false, applyBottom = true)

        val deepLinkData = intent.data
        val deepStoreId = deepLinkData?.getQueryParameter("id")
        val deepStoreType = deepLinkData?.getQueryParameter("type")
        val deepStoreName = deepLinkData?.getQueryParameter("name")

        storeId = intent.getStringExtra("STORE_ID") ?: deepStoreId ?: auth.currentUser?.uid ?: ""
        if (storeId.isEmpty()) {
            Toast.makeText(this, "Tienda no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val storeName = intent.getStringExtra("STORE_NAME") ?: deepStoreName ?: ""
        storeDisplayName = storeName
        isOwner = intent.getBooleanExtra("IS_OWNER", false) || storeId == auth.currentUser?.uid
        val forcedRubro = intent.getStringExtra("FORCE_RUBRO") ?: deepStoreType
        if (!forcedRubro.isNullOrEmpty()) {
            businessType = forcedRubro
            activeSettingsDocId = forcedRubro
        }
        
        storeProfileBinding.btnBackStoreProfile.setOnClickListener { finish() }
        storeProfileBinding.btnShareStoreProfile.setOnClickListener {
            val name = storeDisplayName.ifBlank { storeProfileBinding.tvProfileStoreName.text.toString() }.ifBlank { "Tienda GomezMart" }
            val addr = storeAddress.ifBlank { storeProfileBinding.tvProfileStoreAddress.text.toString() }
            SocialShareHelper.shareStoreExternal(
                activity = this,
                storeId = storeId,
                storeName = name,
                storeLogo = storeLogoUrl,
                storeType = businessType,
                storeAddress = addr
            )
        }
        storeProfileBinding.btnChatStoreOwner.setOnClickListener {
            openChatWithStoreOwner(storeId, storeDisplayName.ifEmpty { "Tienda" })
        }
        storeProfileBinding.layoutStoreRatingSummary.setOnClickListener {
            storeProfileBinding.nsvStoreProfile.smoothScrollTo(0, storeProfileBinding.tvReviewsSectionTitle.top)
        }
        storeProfileBinding.tvStoreFollowersPill.setOnClickListener {
            showFollowersBottomSheetDialog()
        }
        storeProfileBinding.ivVerifiedBadgeStore.setOnClickListener {
            showVerifiedBadgeInfoDialog()
        }
        storeProfileBinding.btnFollowStore.visibility = View.VISIBLE
        storeProfileBinding.btnFollowStore.setOnClickListener {
            if (isOwner) {
                Toast.makeText(this, "Así verán tus clientes el botón para seguir tu negocio", Toast.LENGTH_SHORT).show()
            } else {
                toggleFollowStore()
            }
        }
        if (isOwner) {
            billingManager = BillingManager(this) { verified, _ ->
                isStoreVerified = verified
                updateVerifiedBadgeUI()
            }
            billingManager?.startConnection()
            setupOwnerLiveEditorUI()
        } else {
            storeProfileBinding.btnPublishStoreUpdate.visibility = View.GONE
        }

        setupReviewsRecyclerView()
        setupRecommendationsRecyclerView()
        setupProductsRecyclerView()
        setupStoreUpdatesRecyclerView()
        startStoreListener(storeId, forcedRubro)
        startReviewsListener(storeId)
        startFollowersListener(storeId)
        startUpdatesListener(storeId)

        storeProfileBinding.btnSubmitReview.setOnClickListener { 
            if (requireLoginForGuest("publicar un comentario o reseña")) return@setOnClickListener
            submitReview() 
        }
        
        storeProfileBinding.etNewReviewComment.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isGuestUser()) {
                storeProfileBinding.etNewReviewComment.clearFocus()
                requireLoginForGuest("publicar un comentario o reseña")
            }
        }
        
        storeProfileBinding.btnCancelReview.setOnClickListener {
            if (myReview != null || isGuestUser()) {
                storeProfileBinding.cardAddReview.visibility = View.GONE
            }
            storeProfileBinding.etNewReviewComment.setText("")
            storeProfileBinding.rbNewReview.rating = 5f
        }
        
        storeProfileBinding.tvProfileStoreAddress.setOnClickListener {
            if (isOwner) showInlineAddressOptions() else openGoogleMaps()
        }
        storeProfileBinding.btnHowToGetThere.setOnClickListener { openGoogleMaps() }

        storeProfileBinding.btnContactWhatsApp.setOnClickListener {
            if (storePhone.isNotEmpty()) {
                val url = "https://api.whatsapp.com/send?phone=$storePhone"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }

        if (!isOwner) {
            storeProfileBinding.cardBottomCatalogButton.setOnClickListener {
                showCatalogBottomSheetDialog()
            }
            storeProfileBinding.btnOpenStoreCatalog.setOnClickListener {
                showCatalogBottomSheetDialog()
            }
            setupScrollAnimationForCatalogButton()
        }
    }

    private fun setupProductsRecyclerView() {
        productsAdapter = ProductsAdapter { product ->
            openProductDetail(product)
        }
        storeProfileBinding.rvStoreProductsProfile.layoutManager = GridLayoutManager(this, 2)
        storeProfileBinding.rvStoreProductsProfile.adapter = productsAdapter
    }

    private fun openProductDetail(p: MarketplaceProduct) {
        val intent = Intent(this, ProductDetailActivity::class.java).apply {
            putExtra("PROD_ID", p.id)
            putExtra("PROD_NAME", p.name)
            putExtra("PROD_DESC", p.description)
            putExtra("PROD_PRICE", p.price)
            putExtra("PROD_CAT", p.category)
            putExtra("PROD_PHOTO", p.photoUrl)
            putExtra("STORE_ID", storeId)
            putExtra("STORE_NAME", storeProfileBinding.tvProfileStoreName.text.toString())
            putExtra("STORE_ADDR", storeProfileBinding.tvProfileStoreAddress.text.toString())
            putExtra("STORE_TYPE", businessType)
        }
        startActivityWithFade(intent)
    }

    private var lastStoreProductsId: String? = null
    private var lastStoreProductsType: String? = null
    private fun loadStoreProducts(sId: String, sType: String) {
        if (sId.isEmpty()) return
        if (productsRegistration != null && sId == lastStoreProductsId && sType == lastStoreProductsType) return
        lastStoreProductsId = sId
        lastStoreProductsType = sType
        val coll = if (sType == "HOTEL") "hotel_rooms" else "inventory"
        
        productsRegistration?.remove()
        productsRegistration = db.collection("users").document(sId).collection(coll)
            .limit(100) // Aumentamos el límite para asegurar que se carguen todos
            .addSnapshotListener { snapshot, e ->
                if (snapshot == null) return@addSnapshotListener
                
                var availableRooms = 0
                
                // Filtramos localmente para ser compatibles con cualquier versión de datos
                val productList = snapshot.documents.mapNotNull { doc ->
                    if (doc.getBoolean("isDeleted") == true) return@mapNotNull null
                    
                    val avgRat = doc.getDouble("average_rating") ?: 0.0
                    val revCnt = (doc.getLong("review_count") ?: 0L).toInt()
                    if (sType == "HOTEL") {
                        val number = doc.getString("number") ?: return@mapNotNull null
                        val status = doc.getString("status") ?: "FREE"
                        if (status == "FREE") availableRooms++
                        
                        MarketplaceProduct(
                            doc.id, "Habitación $number", doc.getString("type") ?: "Hospedaje", 
                            doc.getDouble("baseRate") ?: 0.0, doc.getString("description") ?: "", 
                            doc.getString("photoUrl"), sId, "", "", "", "HOTEL",
                            averageRating = avgRat, reviewCount = revCnt
                        )
                    } else {
                        MarketplaceProduct(
                            doc.id, doc.getString("nombre") ?: "Producto", doc.getString("categoria") ?: "General", 
                            doc.getDouble("precioVenta") ?: 0.0, doc.getString("descripcion") ?: "", 
                            doc.getString("photoUrl"), sId, "", "", "", sType,
                            averageRating = avgRat, reviewCount = revCnt
                        )
                    }
                }
                
                if (sType == "HOTEL") {
                    updateHotelAvailabilityStatus(availableRooms > 0)
                }
                
                allStoreProducts.clear()
                allStoreProducts.addAll(productList)
                productsAdapter.updateData(productList)
                
                if (productList.isNotEmpty()) {
                    storeProfileBinding.tvCatalogBadgeCount.visibility = View.VISIBLE
                    storeProfileBinding.tvCatalogBadgeCount.text = productList.size.toString()
                } else {
                    storeProfileBinding.tvCatalogBadgeCount.visibility = View.GONE
                }
                storeProfileBinding.tvNoStoreProducts.visibility = if (productList.isEmpty()) View.VISIBLE else View.GONE
                storeProfileBinding.rvStoreProductsProfile.visibility = if (productList.isEmpty()) View.GONE else View.VISIBLE
            }
    }

    private var hotelStatusPulseAnimator: AnimatorSet? = null

    private fun updateHotelAvailabilityStatus(isAvailable: Boolean) {
        val card = storeProfileBinding.cardHotelAvailability
        val tv = storeProfileBinding.tvHotelAvailability
        val dot = storeProfileBinding.viewHotelStatusDot
        val pulse = storeProfileBinding.viewHotelStatusPulse

        val colorMain = if (isAvailable) Color.parseColor("#16A34A") else Color.parseColor("#DC2626")
        val colorBg = if (isAvailable) Color.parseColor("#F0FDF4") else Color.parseColor("#FEF2F2")
        val colorStroke = if (isAvailable) Color.parseColor("#86EFAC") else Color.parseColor("#FECACA")
        val textStatus = if (isAvailable) "Habitaciones disponibles" else "Lleno"

        card.setCardBackgroundColor(colorBg)
        card.strokeColor = colorStroke
        tv.setTextColor(colorMain)
        tv.text = textStatus

        val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(colorMain)
        }
        val pulseDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(colorMain)
        }

        dot.background = dotDrawable
        pulse.background = pulseDrawable

        hotelStatusPulseAnimator?.cancel()
        pulse.scaleX = 1f
        pulse.scaleY = 1f
        pulse.alpha = 0.35f

        // Animación suave de 2 pulsos sólo al actualizar el estado para evitar sobrecarga del RenderThread / Skia
        val scaleX = ObjectAnimator.ofFloat(pulse, "scaleX", 0.85f, 1.25f).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(pulse, "scaleY", 0.85f, 1.25f).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
        }
        val alpha = ObjectAnimator.ofFloat(pulse, "alpha", 0.45f, 0.15f).apply {
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
        }

        hotelStatusPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 750
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pulse.scaleX = 1f
                    pulse.scaleY = 1f
                    pulse.alpha = 0.35f
                }
            })
            start()
        }
    }

    private fun openChatWithStoreOwner(sId: String, sName: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para chatear con el vendedor 🔐", Toast.LENGTH_SHORT).show()
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
            putExtra("OTHER_USER_TITLE", "Dueño de $sName")
            putExtra("IS_DIRECT", true)
        }
        startActivityWithFade(intent)
    }

    private fun showCatalogBottomSheetDialog() {
        if (isFinishing || isDestroyed) return

        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_store_catalog, null)
        dialog.setContentView(view)

        val tvStoreName = view.findViewById<TextView>(R.id.tvCatalogStoreName)
        val tvProductCount = view.findViewById<TextView>(R.id.tvCatalogProductCount)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseCatalog)
        val etSearch = view.findViewById<EditText>(R.id.etSearchCatalog)
        val rvGrid = view.findViewById<RecyclerView>(R.id.rvCatalogGrid)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyCatalog)

        val isHotel = businessType == "HOTEL"
        val sName = storeProfileBinding.tvProfileStoreName.text.toString().ifEmpty { if (isHotel) "Hospedaje" else "Tienda" }
        tvStoreName.text = if (isHotel) "Habitaciones: $sName" else "Catálogo: $sName"
        tvProductCount.text = if (isHotel) "${allStoreProducts.size} habitaciones disponibles" else "${allStoreProducts.size} productos disponibles"
        if (isHotel) {
            etSearch.hint = "Buscar habitación..."
            tvEmpty.text = "No hay habitaciones disponibles aún 🛎️"
        } else {
            etSearch.hint = "Buscar producto..."
            tvEmpty.text = "No hay productos en este catálogo 🛍️"
        }

        val catalogAdapter = ProductsAdapter { product ->
            dialog.dismiss()
            openProductDetail(product)
        }
        rvGrid.layoutManager = GridLayoutManager(this, 2)
        rvGrid.adapter = catalogAdapter
        catalogAdapter.updateData(allStoreProducts)

        tvEmpty.visibility = if (allStoreProducts.isEmpty()) View.VISIBLE else View.GONE
        rvGrid.visibility = if (allStoreProducts.isEmpty()) View.GONE else View.VISIBLE

        etSearch.addTextChangedListener { text ->
            val query = text.toString().trim().lowercase(Locale.getDefault())
            val filtered = if (query.isEmpty()) {
                allStoreProducts
            } else {
                allStoreProducts.filter {
                    it.name.lowercase(Locale.getDefault()).contains(query) ||
                    it.category.lowercase(Locale.getDefault()).contains(query) ||
                    it.description.lowercase(Locale.getDefault()).contains(query)
                }
            }
            catalogAdapter.updateData(filtered)
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            rvGrid.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun setupRecommendationsRecyclerView() {
        recommendationsAdapter = RecommendationsAdapter { recommendedStore ->
            val intent = Intent(this, StoreProfileActivity::class.java).apply {
                putExtra("STORE_ID", recommendedStore.id)
                putExtra("STORE_NAME", recommendedStore.name)
                putExtra("FORCE_RUBRO", recommendedStore.businessType)
            }
            startActivityWithFade(intent)
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
                    .limit(25)
                    .get().await()
                
                val recommendations = storesSnap.documents.mapNotNull { doc ->
                    val pathParts = doc.reference.path.split("/")
                    if (pathParts.size < 2) return@mapNotNull null
                    val sId = pathParts[1]
                    
                    if (sId == storeId) return@mapNotNull null

                    val isOnlineRaw = doc.get("is_online")
                    val isPublished = when (isOnlineRaw) {
                        is Boolean -> isOnlineRaw
                        is String -> isOnlineRaw.equals("true", ignoreCase = true)
                        else -> false
                    }
                    if (!isPublished) return@mapNotNull null
                    
                    val name = doc.getString("business_name") ?: return@mapNotNull null
                    if (name.isEmpty() || name.equals("Mi Negocio", ignoreCase = true)) return@mapNotNull null

                    StoreModel(
                        id = sId,
                        name = name,
                        businessType = doc.getString("business_type") ?: type,
                        logoUrl = doc.getString("business_logo_url"),
                        address = doc.getString("business_address") ?: "",
                        creationDate = doc.getLong("creation_date") ?: 0L,
                        averageRating = doc.getDouble("average_rating") ?: 0.0,
                        reviewCount = (doc.getLong("review_count") ?: 0L).toInt(),
                        isVerified = doc.getBoolean("is_verified") ?: false
                    )
                }.sortedByDescending { it.creationDate }.take(8)

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

    private val availablePaymentOptions = listOf(
        "Yape",
        "Plin",
        "BCP (Banco de Crédito)",
        "BBVA",
        "Interbank",
        "Banco de la Nación",
        "Efectivo",
        "Tarjeta de Crédito / Débito",
        "Transferencia Bancaria",
        "Pago Contra Entrega"
    )

    private fun showPaymentMethodsDialog() {
        if (paymentMethods.isEmpty()) return
        val methodsListStr = paymentMethods.joinToString("\n• ") { it.title }

        AlertDialog.Builder(this)
            .setTitle("Métodos de Pago Aceptados 💳")
            .setMessage("Este negocio acepta los siguientes medios de pago:\n\n• $methodsListStr\n\nPuedes acordar el pago y solicitar datos o QR directamente con el vendedor por Chat o WhatsApp.")
            .setPositiveButton("💬 Ir al Chat") { _, _ ->
                if (storeId.isNotEmpty()) {
                    openChatWithStoreOwner(storeId, storeDisplayName.ifEmpty { "Vendedor" })
                }
            }
            .setNeutralButton("🟢 WhatsApp") { _, _ ->
                if (storePhone.isNotEmpty()) {
                    try {
                        val url = "https://api.whatsapp.com/send?phone=$storePhone"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "El vendedor no ha registrado WhatsApp", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun renderPublicPaymentMethods() {
        storeProfileBinding.layoutPaymentIconsContainer.removeAllViews()
        if (paymentMethods.isEmpty()) {
            if (isEditingStore) {
                storeProfileBinding.layoutPublicPaymentMethods.visibility = View.VISIBLE
                val tvHint = TextView(this).apply {
                    text = "Sin métodos de pago configurados (toca ✏️ Editar pagos)"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_400))
                    setOnClickListener { showManagePaymentMethodsDialog() }
                }
                storeProfileBinding.layoutPaymentIconsContainer.addView(tvHint)
            } else {
                storeProfileBinding.layoutPublicPaymentMethods.visibility = View.GONE
            }
            return
        }

        storeProfileBinding.layoutPublicPaymentMethods.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        paymentMethods.forEach { method ->
            val titleLower = method.title.lowercase(Locale.ROOT)
            val rawTitle = method.title.replace("🟣 ", "").replace("🟢 ", "").replace("💵 ", "").replace("💳 ", "").replace("🏦 ", "").replace("🤝 ", "")

            val drawableRes = when {
                titleLower.contains("yape") -> R.drawable.logo_yape
                titleLower.contains("bcp") -> R.drawable.logo_bcp
                titleLower.contains("plin") -> R.drawable.logo_plin
                titleLower.contains("interbank") -> R.drawable.logo_interbank
                titleLower.contains("bbva") -> R.drawable.logo_bbva
                titleLower.contains("naci") -> R.drawable.logo_banco_de_la_nacion
                else -> 0
            }

            if (drawableRes != 0) {
                // Solo debe ir el logo: tamaño optimizado (los logos circulares/cuadrados como Yape y Plin se muestran a 50dp para igualar el peso visual de los logos horizontales)
                val isCompactLogo = drawableRes == R.drawable.logo_yape || drawableRes == R.drawable.logo_plin
                val logoHeightPx = if (isCompactLogo) (50 * density).toInt() else (36 * density).toInt()
                val ivLogo = ImageView(this@StoreProfileActivity).apply {
                    adjustViewBounds = true
                    setImageResource(drawableRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = rawTitle
                    setOnClickListener {
                        if (isEditingStore) showManagePaymentMethodsDialog() else showPaymentMethodsDialog()
                    }
                }
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    logoHeightPx
                ).apply {
                    marginEnd = (16 * density).toInt()
                }
                storeProfileBinding.layoutPaymentIconsContainer.addView(ivLogo, params)
            } else {
                // Opciones genéricas sin icono oficial
                val pillView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_pill_purple_light)
                    setPadding(28, 14, 28, 14)
                    setOnClickListener {
                        if (isEditingStore) showManagePaymentMethodsDialog() else showPaymentMethodsDialog()
                    }

                    val tvName = TextView(this@StoreProfileActivity).apply {
                        text = rawTitle
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        isSingleLine = true
                        maxLines = 1
                        setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_700))
                    }
                    addView(tvName)
                }

                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (12 * density).toInt() }
                storeProfileBinding.layoutPaymentIconsContainer.addView(pillView, params)
            }
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

    private fun applyMapLocation(mapView: MapView, lat: Double, lng: Double, businessName: String) {
        val point = GeoPoint(lat, lng)
        mapView.controller.setZoom(17.0)
        mapView.controller.setCenter(point)

        mapView.overlays.clear()
        val marker = Marker(mapView)
        marker.position = point
        marker.title = businessName
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.overlays.add(marker)
    }

    private fun updateMapLocation(lat: Double, lng: Double, businessName: String) {
        currentLat = lat
        currentLng = lng
        if (lat == 0.0 && lng == 0.0) {
            storeProfileBinding.cardStoreMap.visibility = View.GONE
            return
        }

        storeProfileBinding.cardStoreMap.visibility = View.VISIBLE
        val existingMap = storeMapView
        if (existingMap != null) {
            applyMapLocation(existingMap, lat, lng, businessName)
            return
        }

        if (mapInitJob?.isActive == true) return
        mapInitJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                GomezmartApplication.ensureOsmConfigured(applicationContext)
            }
            kotlinx.coroutines.delay(450)
            if (isFinishing || isDestroyed) return@launch
            try {
                val createdMap = MapView(this@StoreProfileActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                }
                storeProfileBinding.mapContainerStoreProfile.removeAllViews()
                storeProfileBinding.mapContainerStoreProfile.addView(createdMap)
                storeMapView = createdMap
                applyMapLocation(createdMap, currentLat, currentLng, storeDisplayName)
                createdMap.onResume()
            } catch (e: Exception) {
                Log.e("StoreProfile", "Error creando MapView diferido: ${e.message}")
            }
        }
    }

    private fun clearCarouselHandlers() {
        try {
            carouselHandlers.forEach { (handler, runnable) -> handler.removeCallbacks(runnable) }
            carouselHandlers.clear()
        } catch (_: Exception) {}
    }

    private var lastRawExtraSections: List<Map<String, Any>> = emptyList()

    private fun setupOwnerLiveEditorUI() {
        storeProfileBinding.btnEditStoreProfile.visibility = View.GONE

        storeProfileBinding.btnOwnerVerifiedBadgeStatus.setOnClickListener {
            if (isStoreVerified) {
                showVerifiedStoreInfoDialog()
            } else {
                showSubscriptionBottomSheet()
            }
        }

        storeProfileBinding.btnPublishStoreUpdate.setOnClickListener {
            if (requireLoginForGuest("publicar novedades para tus seguidores")) return@setOnClickListener
            showPublishStoreUpdateDialog()
        }

        storeProfileBinding.flCoverContainer.setOnClickListener {
            if (isEditingStore) {
                if (storeCoverUrl.isNullOrEmpty()) showImagePickerOptions("COVER")
                else showMainImageOptions("COVER")
            }
        }
        storeProfileBinding.btnUploadCoverInline.setOnClickListener { showImagePickerOptions("COVER") }
        storeProfileBinding.cardProfileLogo.setOnClickListener {
            if (isEditingStore) {
                showMainImageOptions("LOGO")
            }
        }
        storeProfileBinding.btnUploadLogoInline.setOnClickListener {
            if (isEditingStore) {
                showMainImageOptions("LOGO")
            } else {
                showImagePickerOptions("LOGO")
            }
        }

        storeProfileBinding.btnEditNameInline.setOnClickListener {
            showEditTextFieldDialog(
                title = "Nombre del Negocio",
                hint = "Ej. Hotel Las Palmas",
                initialValue = storeDisplayName,
                initialStyledHtml = storeDisplayNameStyled,
                multiline = false,
                enableColorPicker = true
            ) { newValue, styledHtml ->
                if (newValue.isBlank()) {
                    Toast.makeText(this, "El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
                } else {
                    saveStoreFields(
                        mapOf(
                            "business_name" to newValue,
                            "business_name_styled" to styledHtml
                        )
                    )
                }
            }
        }
        storeProfileBinding.tvProfileStoreName.setOnClickListener {
            if (isEditingStore) storeProfileBinding.btnEditNameInline.performClick()
        }

        storeProfileBinding.btnEditHeaderBgInline.setOnClickListener {
            showCustomHeaderBackgroundDialog()
        }

        storeProfileBinding.btnEditDescInline.setOnClickListener {
            showEditTextFieldDialog(
                title = "Descripción del Negocio",
                hint = "Describe tus servicios, atención, horarios o especialidades...",
                initialValue = storeDescription,
                initialStyledHtml = storeDescriptionStyled,
                multiline = true,
                enableColorPicker = true
            ) { newValue, styledHtml ->
                saveStoreFields(
                    mapOf(
                        "business_description" to newValue,
                        "business_description_styled" to styledHtml
                    )
                )
            }
        }
        storeProfileBinding.tvProfileStoreDesc.setOnClickListener {
            if (isEditingStore) storeProfileBinding.btnEditDescInline.performClick()
        }

        storeProfileBinding.btnEditOwnerInline.setOnClickListener {
            showEditTextFieldDialog(
                title = "Fundador / Dueño",
                hint = "Nombre del propietario o encargado",
                initialValue = storeOwnerName,
                initialStyledHtml = storeOwnerNameStyled,
                multiline = false,
                enableColorPicker = true
            ) { newValue, styledHtml ->
                saveStoreFields(
                    mapOf(
                        "business_owner_name" to newValue,
                        "business_owner_name_styled" to styledHtml
                    )
                )
            }
        }

        storeProfileBinding.btnEditAddressInline.setOnClickListener {
            showInlineAddressOptions()
        }

        storeProfileBinding.btnEditPhoneInline.setOnClickListener {
            showEditTextFieldDialog(
                title = "Número de Contacto (WhatsApp)",
                hint = "Ej. 987654321",
                initialValue = storePhone,
                initialStyledHtml = "",
                multiline = false,
                enableColorPicker = false
            ) { newValue, _ ->
                saveStoreFields(mapOf("business_phone" to newValue))
            }
        }

        storeProfileBinding.btnEditPaymentMethodsInline.setOnClickListener {
            showManagePaymentMethodsDialog()
        }

        storeProfileBinding.btnAddExtraSectionBottom.setOnClickListener {
            showAddOrEditSectionDialog(null)
        }

        storeProfileBinding.cardBottomPreviewButton.visibility = View.VISIBLE
        storeProfileBinding.cardBottomPreviewButton.setOnClickListener {
            toggleClientPreviewMode()
        }
        storeProfileBinding.btnTogglePreviewMode.setOnClickListener {
            toggleClientPreviewMode()
        }

        applyEditorOrPreviewVisibility()
        updateOwnerPublishButtonUI()
    }

    private fun toggleClientPreviewMode() {
        isClientPreviewMode = !isClientPreviewMode
        applyEditorOrPreviewVisibility()
        refreshStoreTextAndPlaceholdersUI()
        renderPublicPaymentMethods()
        renderExtraSections(lastRawExtraSections)
        updateOwnerPublishButtonUI()
        val msg = if (isClientPreviewMode) {
            "👁️ Vista previa activada: así ven tu página los clientes"
        } else {
            "✏️ Modo edición activado"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyEditorOrPreviewVisibility() {
        if (!isOwner) return
        val editVis = if (isEditingStore) View.VISIBLE else View.GONE
        val clientVis = if (isClientPreviewMode) View.VISIBLE else View.GONE

        storeProfileBinding.cardLiveBuilderBanner.visibility = editVis
        storeProfileBinding.btnUploadCoverInline.visibility = editVis
        storeProfileBinding.btnUploadLogoInline.visibility = editVis
        storeProfileBinding.btnEditNameInline.visibility = editVis
        storeProfileBinding.btnEditHeaderBgInline.visibility = editVis
        storeProfileBinding.btnEditDescInline.visibility = editVis
        storeProfileBinding.btnEditOwnerInline.visibility = editVis
        storeProfileBinding.btnEditAddressInline.visibility = editVis
        storeProfileBinding.layoutOwnerPhoneRow.visibility = editVis
        storeProfileBinding.btnEditPaymentMethodsInline.visibility = editVis
        storeProfileBinding.layoutExtraSectionsHeaderOwner.visibility = editVis
        storeProfileBinding.btnAddExtraSectionBottom.visibility = editVis
        storeProfileBinding.btnPublishStoreUpdate.visibility = editVis

        storeProfileBinding.btnFollowStore.visibility = View.VISIBLE
        storeProfileBinding.btnChatStoreOwner.visibility = clientVis
        storeProfileBinding.cardAddReview.visibility = clientVis
        storeProfileBinding.btnContactWhatsApp.visibility = if (isClientPreviewMode && storePhone.isNotEmpty()) View.VISIBLE else View.GONE

        if (isClientPreviewMode) {
            storeProfileBinding.layoutStoreUpdatesSection.visibility = if (currentStoreUpdates.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            storeProfileBinding.layoutStoreUpdatesSection.visibility = View.VISIBLE
        }
        updateVerifiedBadgeUI()
    }

    private fun updateVerifiedBadgeUI() {
        storeProfileBinding.ivVerifiedBadgeStore.visibility = if (isStoreVerified) View.VISIBLE else View.GONE
        if (isOwner && isEditingStore && !isStoreVerified) {
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.visibility = View.VISIBLE
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.text = "⭐ Comprar Plan Profesional (Insignia + Nube)"
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.setTextColor(ContextCompat.getColor(this, R.color.sky_700))
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.sky_200))
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        } else {
            storeProfileBinding.btnOwnerVerifiedBadgeStatus.visibility = View.GONE
        }
    }

    private fun showSubscriptionBottomSheet() {
        BillingManager.showSubscriptionBottomSheet(this, billingManager)
    }

    private fun showVerifiedBadgeInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tienda Oficial Verificada")
            .setMessage("Esta tienda cuenta con la insignia oficial de GoMezMart. Su identidad ha sido comprobada y cuenta con suscripción activa.")
            .setIcon(R.drawable.ic_verified_badge)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun showVerifiedStoreInfoDialog() {
        val tier = BillingManager.getActiveTier(this)
        val isVip = tier == BillingManager.TIER_VIP_30
        val builder = AlertDialog.Builder(this)
            .setTitle(if (isVip) "¡Plan Empresarial VIP Activo! 👑" else "¡Tu Tienda es Oficial! 🛡️")
            .setMessage(
                if (isVip) {
                    "Tu negocio cuenta con el Plan Empresarial VIP (1.5 GB en la Nube, Posicionamiento Top en el Mercado e IA ilimitada)."
                } else {
                    "Tu negocio cuenta con el Plan Profesional (250 MB en la Nube e Insignia Oficial activa). Tus clientes compran con total confianza."
                }
            )
            .setIcon(R.drawable.ic_verified_badge)
            .setPositiveButton("Excelente", null)
        if (!isVip) {
            builder.setNeutralButton("Ver Plan VIP (S/ 30)") { _, _ ->
                BillingManager.showSubscriptionBottomSheet(this, billingManager, BillingManager.TIER_VIP_30)
            }
        }
        builder.show()
    }

    private fun refreshStoreTextAndPlaceholdersUI() {
        val cleanName = storeDisplayName
        val desc = storeDescription
        val addr = storeAddress
        val owner = storeOwnerName

        if (headerTextTheme == "LIGHT") {
            storeProfileBinding.tvProfileStoreName.setTextColor(
                if (cleanName.isBlank() && isEditingStore) Color.parseColor("#CBD5E1") else Color.WHITE
            )
            storeProfileBinding.tvProfileStoreName.setShadowLayer(6f, 1f, 2f, Color.parseColor("#80000000"))
        } else {
            storeProfileBinding.tvProfileStoreName.setTextColor(
                ContextCompat.getColor(this, if (cleanName.isBlank() && isEditingStore) R.color.slate_400 else R.color.slate_900)
            )
            storeProfileBinding.tvProfileStoreName.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        storeProfileBinding.tvProfileStoreName.text = if (cleanName.isNotBlank()) {
            com.gomezmart.app.util.RichTextColorHelper.parseStyledText(storeDisplayNameStyled, cleanName)
        } else if (isEditingStore) {
            "Nombre del negocio"
        } else {
            "Tienda GoMezMart"
        }

        storeProfileBinding.tvProfileStoreDesc.setTextColor(
            ContextCompat.getColor(this, if (desc.isBlank() && isEditingStore) R.color.slate_400 else R.color.slate_700)
        )
        storeProfileBinding.tvProfileStoreDesc.text = if (desc.isNotBlank()) {
            com.gomezmart.app.util.RichTextColorHelper.parseStyledText(storeDescriptionStyled, desc)
        } else if (isEditingStore) {
            "Agrega una descripción atractiva de tu negocio..."
        } else {
            "Sin descripción disponible."
        }

        storeProfileBinding.tvProfileStoreAddress.text = if (addr.isNotBlank()) {
            android.text.SpannableStringBuilder("📍 ").append(
                com.gomezmart.app.util.RichTextColorHelper.parseStyledText(storeAddressStyled, addr)
            )
        } else if (isEditingStore) {
            "Dirección de tu negocio (toca ✏️ Ubicación)"
        } else {
            "📍 Ubicación no especificada"
        }

        storeProfileBinding.tvProfileOwnerName.text = if (owner.isNotBlank()) {
            com.gomezmart.app.util.RichTextColorHelper.parseStyledText(storeOwnerNameStyled, owner)
        } else if (isEditingStore) {
            "Nombre del Fundador / Dueño"
        } else {
            "Propietario GoMezMart"
        }

        storeProfileBinding.tvProfileOwnerPhone.text = if (storePhone.isNotBlank()) {
            "WhatsApp: $storePhone"
        } else {
            "Número de WhatsApp (sin configurar)"
        }

        if (storeLogoUrl.isNullOrEmpty()) {
            storeProfileBinding.layoutLogoPlaceholder.visibility = if (isEditingStore) View.VISIBLE else View.GONE
        }
        if (storeCoverUrl.isNullOrEmpty()) {
            storeProfileBinding.layoutCoverPlaceholder.visibility = if (isEditingStore) View.VISIBLE else View.GONE
        }
    }

    private fun updateOwnerPublishButtonUI() {
        if (!isOwner) return
        storeProfileBinding.cardBottomPreviewButton.visibility = View.VISIBLE
        if (isClientPreviewMode) {
            storeProfileBinding.tvPreviewButtonIcon.text = "✏️"
            storeProfileBinding.tvPreviewButtonText.text = "Editar"
            storeProfileBinding.cardBottomPreviewButton.setCardBackgroundColor(ContextCompat.getColor(this, R.color.purple_50))
        } else {
            storeProfileBinding.tvPreviewButtonIcon.text = "👁️"
            storeProfileBinding.tvPreviewButtonText.text = "Vista previa"
            storeProfileBinding.cardBottomPreviewButton.setCardBackgroundColor(ContextCompat.getColor(this, R.color.white))
        }

        if (isStoreOnline) {
            storeProfileBinding.cardBottomCatalogButton.setCardBackgroundColor(ContextCompat.getColor(this, R.color.emerald_600))
            storeProfileBinding.tvCatalogButtonIcon.text = "🌐"
            storeProfileBinding.tvCatalogButtonText.text = "PÁGINA EN LÍNEA"
            storeProfileBinding.tvCatalogBadgeCount.visibility = View.GONE
            storeProfileBinding.tvLiveBuilderBannerTitle.text = "Tu página está activa en el Mercado"
            storeProfileBinding.tvLiveBuilderBannerSub.text = "Cualquier cambio que edites aquí (✏️) se actualiza en tiempo real para tus clientes."
        } else {
            storeProfileBinding.cardBottomCatalogButton.setCardBackgroundColor(ContextCompat.getColor(this, R.color.purple_600))
            storeProfileBinding.tvCatalogButtonIcon.text = "🚀"
            storeProfileBinding.tvCatalogButtonText.text = "PUBLICAR MI PÁGINA"
            storeProfileBinding.tvCatalogBadgeCount.visibility = View.GONE
            storeProfileBinding.tvLiveBuilderBannerTitle.text = "Construye tu página en vivo"
            storeProfileBinding.tvLiveBuilderBannerSub.text = "Completa las secciones en blanco con el botón editar (✏️) y pulsa Publicar abajo cuando estés listo."
        }
        storeProfileBinding.cardBottomCatalogButton.setOnClickListener {
            handleOwnerPublishClick()
        }
        storeProfileBinding.btnOpenStoreCatalog.setOnClickListener {
            handleOwnerPublishClick()
        }
    }

    private fun handleOwnerPublishClick() {
        if (requireLoginForGuest("publicar tu página en el Mercado en Línea")) return
        if (!isStoreOnline) {
            if (storeDisplayName.isBlank() || storeDisplayName.equals("Mi Negocio", ignoreCase = true)) {
                Toast.makeText(this, "⚠️ Primero edita el nombre de tu negocio (✏️)", Toast.LENGTH_LONG).show()
                storeProfileBinding.btnEditNameInline.performClick()
                return
            }
            if (storeDescription.length < 10) {
                Toast.makeText(this, "⚠️ Agrega una breve descripción de tu negocio (✏️)", Toast.LENGTH_LONG).show()
                storeProfileBinding.btnEditDescInline.performClick()
                return
            }
            if (storeAddress.isBlank()) {
                Toast.makeText(this, "⚠️ Indica la dirección o ubicación de tu negocio (✏️)", Toast.LENGTH_LONG).show()
                showInlineAddressOptions()
                return
            }
            saveStoreFields(mapOf("is_online" to true)) {
                isStoreOnline = true
                updateOwnerPublishButtonUI()
                Toast.makeText(this, "🎉 ¡Tu página ya está publicada en el Mercado!", Toast.LENGTH_LONG).show()
            }
        } else {
            val options = arrayOf("🛍️ Ver mi catálogo / habitaciones", "⏸️ Ocultar página del Mercado temporalmente")
            AlertDialog.Builder(this)
                .setTitle("Tu página está en línea 🌐")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        showCatalogBottomSheetDialog()
                    } else {
                        saveStoreFields(mapOf("is_online" to false)) {
                            isStoreOnline = false
                            updateOwnerPublishButtonUI()
                            Toast.makeText(this, "Tu página se ocultó del Mercado", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun showEditTextFieldDialog(
        title: String,
        hint: String,
        initialValue: String,
        initialStyledHtml: String = "",
        multiline: Boolean,
        enableColorPicker: Boolean = true,
        onSave: (plainText: String, styledHtml: String) -> Unit
    ) {
        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 24, 52, 12)
        }
        val et = EditText(this).apply {
            this.hint = hint
            if (multiline) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                maxLines = 1
            }
            setText(initialValue, TextView.BufferType.SPANNABLE)
            setSelection(text.length)
        }
        com.gomezmart.app.util.SpanishInputHelper.configureEditText(et)
        container.addView(et)

        if (enableColorPicker) {
            val colorPanel = com.gomezmart.app.util.RichTextColorHelper.createWordColorEditorPanel(
                context = this,
                editText = et,
                initialStyledHtml = initialStyledHtml
            )
            container.addView(colorPanel)
        }

        scrollView.addView(container)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val editable = et.text
                val plain = com.gomezmart.app.util.RichTextColorHelper.extractPlainText(editable)
                val styled = if (enableColorPicker && editable != null) {
                    com.gomezmart.app.util.RichTextColorHelper.toStyledHtml(editable)
                } else {
                    ""
                }
                onSave(plain, styled)
            }
            .create()
        com.gomezmart.app.util.SpanishInputHelper.attachToDialog(dialog)
        dialog.show()
    }

    private fun showInlineAddressOptions() {
        val options = arrayOf("🗺️ Seleccionar en el Mapa GPS", "✍️ Escribir dirección manualmente")
        AlertDialog.Builder(this)
            .setTitle("Ubicación del Negocio")
            .setItems(options) { _, which ->
                if (which == 0) {
                    mapSelectorLauncher.launch(Intent(this, BusinessMapSelectorActivity::class.java))
                } else {
                    showEditTextFieldDialog(
                        title = "Dirección del Negocio",
                        hint = "Ej. Av. Principal 123, Centro",
                        initialValue = storeAddress,
                        initialStyledHtml = storeAddressStyled,
                        multiline = false,
                        enableColorPicker = true
                    ) { newAddr, styledAddr ->
                        saveStoreFields(
                            mapOf(
                                "business_address" to newAddr,
                                "business_address_styled" to styledAddr
                            )
                        )
                    }
                }
            }
            .show()
    }

    private fun saveInlineLocation(lat: Double, lng: Double, addressFromMap: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var detectedCity = ""
            var detectedPostalCode = ""
            var detectedCountry = ""
            try {
                val geocoder = Geocoder(this@StoreProfileActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addrObj = addresses[0]
                    detectedCity = addrObj.locality ?: addrObj.subAdminArea ?: ""
                    detectedPostalCode = addrObj.postalCode ?: ""
                    detectedCountry = addrObj.countryName ?: ""
                }
            } catch (_: Exception) {}

            val fields = mutableMapOf<String, Any>(
                "latitude" to lat,
                "longitude" to lng,
                "business_city" to detectedCity,
                "business_postal_code" to detectedPostalCode,
                "business_country" to detectedCountry
            )
            if (addressFromMap.isNotEmpty()) {
                fields["business_address"] = addressFromMap
            }
            withContext(Dispatchers.Main) {
                saveStoreFields(fields)
            }
        }
    }

    private fun startCrop(uri: Uri, type: String) {
        currentCropType = type
        val options = CropImageOptions().apply {
            guidelines = CropImageView.Guidelines.ON
            activityTitle = "Ajustar Imagen"
            borderCornerOffset = 20f
            borderCornerThickness = 6f
            borderCornerLength = 20f
            allowRotation = true
            allowFlipping = true
            if (type == "LOGO") {
                cropShape = CropImageView.CropShape.OVAL
                fixAspectRatio = true
                aspectRatioX = 1
                aspectRatioY = 1
            } else if (type == "HEADER_BG") {
                cropShape = CropImageView.CropShape.RECTANGLE
                fixAspectRatio = false
            } else {
                cropShape = CropImageView.CropShape.RECTANGLE
                fixAspectRatio = false
                aspectRatioX = 16
                aspectRatioY = 9
            }
            showProgressBar = true
            activityMenuIconColor = Color.WHITE
            backgroundColor = Color.parseColor("#CC000000")
        }
        cropImage.launch(CropImageContractOptions(uri, options))
    }

    private fun showMainImageOptions(type: String) {
        if (type == "LOGO") {
            val hasLogo = !storeLogoUrl.isNullOrEmpty()
            val options = if (hasLogo) {
                arrayOf("🔄 Cambiar Logo", "✏️ Editar Logo (Recortar/Tamaño)", "📐 Posición del Logo (Izquierda / Centro / Derecha)")
            } else {
                arrayOf("📷 Subir Logo", "📐 Posición del Logo (Izquierda / Centro / Derecha)")
            }
            AlertDialog.Builder(this)
                .setTitle("Gestionar Logo")
                .setItems(options) { _, which ->
                    if (hasLogo) {
                        when (which) {
                            0 -> showImagePickerOptions("LOGO")
                            1 -> editCurrentMainImage("LOGO")
                            2 -> showLogoPositionDialog()
                        }
                    } else {
                        when (which) {
                            0 -> showImagePickerOptions("LOGO")
                            1 -> showLogoPositionDialog()
                        }
                    }
                }.show()
            return
        }

        val options = arrayOf("🔄 Reemplazar Imagen", "✏️ Editar Imagen")
        AlertDialog.Builder(this)
            .setTitle("Gestionar Portada")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showImagePickerOptions(type)
                    1 -> editCurrentMainImage(type)
                }
            }.show()
    }

    private fun editCurrentMainImage(type: String) {
        val currentUrl = if (type == "LOGO") storeLogoUrl else storeCoverUrl
        if (!currentUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Preparando imagen...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val tempFile = File(filesDir, "temp_crop_${type.lowercase()}_${System.currentTimeMillis()}.png")
                    if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                        val downloadedFile = Glide.with(this@StoreProfileActivity)
                            .downloadOnly()
                            .load(currentUrl)
                            .submit()
                            .get()
                        downloadedFile.copyTo(tempFile, overwrite = true)
                    } else {
                        val sourceFile = if (currentUrl.startsWith("file://")) File(Uri.parse(currentUrl).path!!) else File(currentUrl)
                        if (sourceFile.exists()) {
                            sourceFile.copyTo(tempFile, overwrite = true)
                        } else {
                            val inputStream = contentResolver.openInputStream(Uri.parse(currentUrl))
                            val outputStream = FileOutputStream(tempFile)
                            inputStream?.use { input -> outputStream.use { output -> input.copyTo(output) } }
                        }
                    }

                    val localUri = FileProvider.getUriForFile(
                        this@StoreProfileActivity,
                        "$packageName.provider",
                        tempFile
                    )
                    withContext(Dispatchers.Main) {
                        startCrop(localUri, type)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StoreProfileActivity, "Error al cargar la imagen para editar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "No hay imagen para editar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogoPositionDialog() {
        val labels = arrayOf("⬅️ A la izquierda", "⏺️ En el centro", "➡️ A la derecha")
        val values = arrayOf("LEFT", "CENTER", "RIGHT")
        val checkedItem = values.indexOf(storeLogoPosition).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Posición del Logo")
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val selectedPos = values[which]
                storeLogoPosition = selectedPos
                applyLogoPositionUI(selectedPos)
                saveStoreFields(mapOf("business_logo_position" to selectedPos))
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyLogoPositionUI(position: String) {
        val container = storeProfileBinding.flLogoOuterContainer
        val params = container.layoutParams as? RelativeLayout.LayoutParams ?: return
        val margin20 = (20 * resources.displayMetrics.density).toInt()

        params.removeRule(RelativeLayout.ALIGN_PARENT_START)
        params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
        params.removeRule(RelativeLayout.CENTER_HORIZONTAL)
        params.removeRule(RelativeLayout.ALIGN_PARENT_END)
        params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)

        when (position.uppercase(Locale.ROOT)) {
            "CENTER" -> {
                params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                params.marginStart = 0
                params.marginEnd = 0
            }
            "RIGHT" -> {
                params.addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
                params.marginStart = 0
                params.marginEnd = margin20
            }
            else -> {
                params.addRule(RelativeLayout.ALIGN_PARENT_START, RelativeLayout.TRUE)
                params.marginStart = margin20
                params.marginEnd = 0
            }
        }
        container.layoutParams = params
    }

    private fun applyHeaderBackgroundUI(
        bgType: String?,
        bgValue: String?,
        overlayAlpha: Double?,
        textTheme: String?
    ) {
        val type = bgType ?: "NONE"
        val value = bgValue.orEmpty()
        val theme = textTheme ?: if (type == "IMAGE" || type == "GRADIENT") "LIGHT" else "DARK"

        when (type) {
            "COLOR" -> {
                storeProfileBinding.ivHeaderCustomBg.visibility = View.GONE
                storeProfileBinding.ivHeaderCustomBg.setImageDrawable(null)
                storeProfileBinding.viewHeaderBgOverlay.visibility = View.GONE
                try {
                    val color = Color.parseColor(if (value.startsWith("#")) value else "#$value")
                    storeProfileBinding.cardStoreHeaderBox.setCardBackgroundColor(color)
                    storeProfileBinding.cardStoreHeaderBox.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
                } catch (_: Exception) {
                    storeProfileBinding.cardStoreHeaderBox.setCardBackgroundColor(Color.TRANSPARENT)
                }
            }
            "GRADIENT" -> {
                storeProfileBinding.viewHeaderBgOverlay.visibility = View.GONE
                val gradient = createHeaderGradient(value)
                storeProfileBinding.ivHeaderCustomBg.visibility = View.VISIBLE
                storeProfileBinding.ivHeaderCustomBg.setImageDrawable(gradient)
                storeProfileBinding.cardStoreHeaderBox.setCardBackgroundColor(Color.TRANSPARENT)
                storeProfileBinding.cardStoreHeaderBox.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
            }
            "IMAGE" -> {
                storeProfileBinding.ivHeaderCustomBg.visibility = View.VISIBLE
                storeProfileBinding.viewHeaderBgOverlay.visibility = View.VISIBLE
                storeProfileBinding.cardStoreHeaderBox.setCardBackgroundColor(Color.parseColor("#1E293B"))
                storeProfileBinding.cardStoreHeaderBox.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)

                val alpha = (overlayAlpha ?: 0.45).toFloat().coerceIn(0.1f, 0.9f)
                storeProfileBinding.viewHeaderBgOverlay.alpha = alpha
                storeProfileBinding.viewHeaderBgOverlay.setBackgroundColor(Color.BLACK)

                if (value.isNotEmpty()) {
                    Glide.with(this)
                        .load(value)
                        .apply(GlideHelper.getOptions(this))
                        .centerCrop()
                        .into(storeProfileBinding.ivHeaderCustomBg)
                }
            }
            else -> {
                storeProfileBinding.ivHeaderCustomBg.visibility = View.GONE
                storeProfileBinding.ivHeaderCustomBg.setImageDrawable(null)
                storeProfileBinding.viewHeaderBgOverlay.visibility = View.GONE
                storeProfileBinding.cardStoreHeaderBox.setCardBackgroundColor(Color.TRANSPARENT)
                storeProfileBinding.cardStoreHeaderBox.cardElevation = 0f
            }
        }

        if (theme == "LIGHT") {
            storeProfileBinding.tvProfileStoreCategory.setTextColor(Color.parseColor("#F3E8FF"))
            if (storeDisplayName.isNotBlank()) {
                storeProfileBinding.tvProfileStoreName.setTextColor(Color.WHITE)
            } else {
                storeProfileBinding.tvProfileStoreName.setTextColor(Color.parseColor("#CBD5E1"))
            }
            storeProfileBinding.tvProfileStoreName.setShadowLayer(6f, 1f, 2f, Color.parseColor("#80000000"))
            storeProfileBinding.tvStoreFollowersPill.setTextColor(Color.parseColor("#F8FAFC"))
            storeProfileBinding.tvStoreFollowersPill.setShadowLayer(4f, 1f, 1f, Color.parseColor("#80000000"))
            storeProfileBinding.tvStoreAverageRatingPill.setTextColor(Color.parseColor("#FDE68A"))
        } else {
            storeProfileBinding.tvProfileStoreCategory.setTextColor(ContextCompat.getColor(this, R.color.purple_600))
            if (storeDisplayName.isNotBlank()) {
                storeProfileBinding.tvProfileStoreName.setTextColor(ContextCompat.getColor(this, R.color.slate_900))
            } else {
                storeProfileBinding.tvProfileStoreName.setTextColor(ContextCompat.getColor(this, R.color.slate_400))
            }
            storeProfileBinding.tvProfileStoreName.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            storeProfileBinding.tvStoreFollowersPill.setTextColor(ContextCompat.getColor(this, R.color.purple_700))
            storeProfileBinding.tvStoreFollowersPill.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            storeProfileBinding.tvStoreAverageRatingPill.setTextColor(Color.parseColor("#B45309"))
        }
    }

    private fun createHeaderGradient(gradientId: String): Drawable {
        val colors = when (gradientId) {
            "GRADIENT_SUNSET" -> intArrayOf(Color.parseColor("#FF512F"), Color.parseColor("#DD2476"))
            "GRADIENT_OCEAN" -> intArrayOf(Color.parseColor("#0575E6"), Color.parseColor("#021B79"))
            "GRADIENT_EMERALD" -> intArrayOf(Color.parseColor("#11998E"), Color.parseColor("#38EF7D"))
            "GRADIENT_MIDNIGHT" -> intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#0F172A"))
            "GRADIENT_PURPLE" -> intArrayOf(Color.parseColor("#7F00FF"), Color.parseColor("#E100FF"))
            "GRADIENT_ROYAL" -> intArrayOf(Color.parseColor("#4A00E0"), Color.parseColor("#8E2DE2"))
            "GRADIENT_WARM_GOLD" -> intArrayOf(Color.parseColor("#F7971E"), Color.parseColor("#FFD200"))
            else -> intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"))
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
        }
    }

    data class HeaderBgStudioOption(
        val category: String, // "GRADIENT", "COLOR", "PHOTO", "NONE"
        val name: String,
        val value: String,
        val theme: String,
        val previewDrawable: Drawable? = null,
        val previewColor: Int? = null
    )

    private fun showCustomHeaderBackgroundDialog() {
        showLiveHeaderBackgroundStudio("GRADIENTS")
    }

    private fun showLiveHeaderBackgroundStudio(startCategory: String = "GRADIENTS") {
        if (isFinishing || isDestroyed || !isOwner) return

        storeProfileBinding.nsvStoreProfile.smoothScrollTo(0, 0)

        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_header_background, null)
        dialog.setContentView(sheetView)

        dialog.window?.setDimAmount(0.12f)

        val initialType = headerBgType
        val initialValue = headerBgValue
        val initialOverlay = headerBgOverlay
        val initialTheme = headerTextTheme
        var isSaved = false

        var currentSelectedCategory = when {
            startCategory == "COLORS" || (initialType == "COLOR" && startCategory == "AUTO") -> "COLOR"
            startCategory == "PHOTO" || (initialType == "IMAGE" && startCategory == "AUTO") -> "PHOTO"
            startCategory == "NONE" || (initialType == "NONE" && startCategory == "AUTO") -> "NONE"
            else -> "GRADIENT"
        }

        var activeOverlay = headerBgOverlay

        val gradientOptions = listOf(
            HeaderBgStudioOption("GRADIENT", "🌅 Atardecer Cálido (Sunset)", "GRADIENT_SUNSET", "LIGHT", createHeaderGradient("GRADIENT_SUNSET")),
            HeaderBgStudioOption("GRADIENT", "🌊 Océano Profundo (Ocean)", "GRADIENT_OCEAN", "LIGHT", createHeaderGradient("GRADIENT_OCEAN")),
            HeaderBgStudioOption("GRADIENT", "🌲 Esmeralda Fresco (Emerald)", "GRADIENT_EMERALD", "LIGHT", createHeaderGradient("GRADIENT_EMERALD")),
            HeaderBgStudioOption("GRADIENT", "🌌 Carbón Nocturno (Midnight)", "GRADIENT_MIDNIGHT", "LIGHT", createHeaderGradient("GRADIENT_MIDNIGHT")),
            HeaderBgStudioOption("GRADIENT", "💜 Neón Violeta (Purple Glow)", "GRADIENT_PURPLE", "LIGHT", createHeaderGradient("GRADIENT_PURPLE")),
            HeaderBgStudioOption("GRADIENT", "👑 Violeta Real (Royal)", "GRADIENT_ROYAL", "LIGHT", createHeaderGradient("GRADIENT_ROYAL")),
            HeaderBgStudioOption("GRADIENT", "✨ Oro Radiante (Warm Gold)", "GRADIENT_WARM_GOLD", "DARK", createHeaderGradient("GRADIENT_WARM_GOLD"))
        )

        val colorOptions = listOf(
            HeaderBgStudioOption("COLOR", "🤍 Blanco Neutro", "#FFFFFF", "DARK", previewColor = Color.WHITE),
            HeaderBgStudioOption("COLOR", "🖤 Carbón Grafito", "#0F172A", "LIGHT", previewColor = Color.parseColor("#0F172A")),
            HeaderBgStudioOption("COLOR", "💜 Púrpura Nexur", "#5B21B6", "LIGHT", previewColor = Color.parseColor("#5B21B6")),
            HeaderBgStudioOption("COLOR", "💙 Azul Marino", "#1E3A8A", "LIGHT", previewColor = Color.parseColor("#1E3A8A")),
            HeaderBgStudioOption("COLOR", "🌲 Verde Bosque", "#065F46", "LIGHT", previewColor = Color.parseColor("#065F46")),
            HeaderBgStudioOption("COLOR", "🍷 Borgoña / Vino", "#881337", "LIGHT", previewColor = Color.parseColor("#881337")),
            HeaderBgStudioOption("COLOR", "📜 Crema Cálido", "#FEF3C7", "DARK", previewColor = Color.parseColor("#FEF3C7")),
            HeaderBgStudioOption("COLOR", "🌫️ Gris Pizarra", "#334155", "LIGHT", previewColor = Color.parseColor("#334155")),
            HeaderBgStudioOption("COLOR", "🌸 Rosa Pastel", "#FCE7F3", "DARK", previewColor = Color.parseColor("#FCE7F3")),
            HeaderBgStudioOption("COLOR", "🍃 Menta Suave", "#D1FAE5", "DARK", previewColor = Color.parseColor("#D1FAE5")),
            HeaderBgStudioOption("COLOR", "✏️ Hex personalizado...", "HEX_CUSTOM", "AUTO", previewColor = Color.parseColor("#E2E8F0"))
        )

        var activeOptionsList: List<HeaderBgStudioOption> = gradientOptions
        var activeIndex = 0

        if (currentSelectedCategory == "GRADIENT") {
            val found = gradientOptions.indexOfFirst { it.value == initialValue }
            activeIndex = if (found >= 0) found else 0
        } else if (currentSelectedCategory == "COLOR") {
            val found = colorOptions.indexOfFirst { it.value.equals(initialValue, ignoreCase = true) }
            activeIndex = if (found >= 0) found else 0
        }

        val chipGroup = sheetView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupHeaderCategories)
        val chipGradients = sheetView.findViewById<com.google.android.material.chip.Chip>(R.id.chipCategoryGradients)
        val chipColors = sheetView.findViewById<com.google.android.material.chip.Chip>(R.id.chipCategoryColors)
        val chipPhoto = sheetView.findViewById<com.google.android.material.chip.Chip>(R.id.chipCategoryPhoto)
        val chipDefault = sheetView.findViewById<com.google.android.material.chip.Chip>(R.id.chipCategoryDefault)

        val cardStepper = sheetView.findViewById<View>(R.id.cardStepperController)
        val btnPrev = sheetView.findViewById<View>(R.id.btnNavPrevHeaderBg)
        val btnNext = sheetView.findViewById<View>(R.id.btnNavNextHeaderBg)
        val tvName = sheetView.findViewById<TextView>(R.id.tvActiveOptionName)
        val tvCounter = sheetView.findViewById<TextView>(R.id.tvActiveOptionCounter)

        val hsvThumbnails = sheetView.findViewById<View>(R.id.hsvThumbnailsContainer)
        val layoutThumbnails = sheetView.findViewById<LinearLayout>(R.id.layoutThumbnailsRow)

        val layoutPhotoControls = sheetView.findViewById<View>(R.id.layoutPhotoControls)
        val btnUploadNewPhoto = sheetView.findViewById<View>(R.id.btnUploadNewHeaderPhoto)
        val btnOverlay30 = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOverlay30)
        val btnOverlay45 = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOverlay45)
        val btnOverlay60 = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOverlay60)
        val btnOverlay75 = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOverlay75)

        val btnDiscard = sheetView.findViewById<View>(R.id.btnDiscardHeaderBg)
        val btnSave = sheetView.findViewById<View>(R.id.btnSaveHeaderBg)

        fun updateOverlayButtonsUI() {
            val selectedStroke = (2 * resources.displayMetrics.density).toInt()
            val normalStroke = (1 * resources.displayMetrics.density).toInt()
            listOf(
                btnOverlay30 to 0.30,
                btnOverlay45 to 0.45,
                btnOverlay60 to 0.60,
                btnOverlay75 to 0.75
            ).forEach { (btn, value) ->
                val isSelected = Math.abs(activeOverlay - value) < 0.05
                if (isSelected) {
                    btn.strokeWidth = selectedStroke
                    btn.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.purple_600))
                    btn.setBackgroundColor(Color.parseColor("#EDE9FE"))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.purple_700))
                } else {
                    btn.strokeWidth = normalStroke
                    btn.strokeColor = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(ContextCompat.getColor(this, R.color.slate_600))
                }
            }
        }

        fun applyCurrentSelectionLive() {
            if (currentSelectedCategory == "PHOTO") {
                val photoUrl = if (headerBgType == "IMAGE") headerBgValue else initialValue
                applyHeaderBackgroundUI("IMAGE", photoUrl, activeOverlay, "LIGHT")
                tvName.text = "Foto de Fondo Personalizada 📷"
                tvCounter.text = "Oscurecimiento actual: ${(activeOverlay * 100).toInt()}%"
                return
            }

            if (currentSelectedCategory == "NONE") {
                applyHeaderBackgroundUI("NONE", "", 0.45, "DARK")
                tvName.text = "Fondo Transparente / Predeterminado 🔄"
                tvCounter.text = "Vuelve al estilo limpio original de la tienda"
                return
            }

            if (activeOptionsList.isEmpty()) return
            val option = activeOptionsList[activeIndex.coerceIn(0, activeOptionsList.size - 1)]

            if (option.value == "HEX_CUSTOM") {
                tvName.text = "Color Hex Personalizado ✏️"
                tvCounter.text = "Toca para escribir tu código #HEX"
            } else {
                tvName.text = option.name
                tvCounter.text = "Opción ${activeIndex + 1} de ${activeOptionsList.size} • En vivo"
                applyHeaderBackgroundUI(option.category, option.value, activeOverlay, option.theme)
            }

            for (i in 0 until layoutThumbnails.childCount) {
                val card = layoutThumbnails.getChildAt(i) as? com.google.android.material.card.MaterialCardView ?: continue
                if (i == activeIndex) {
                    card.strokeColor = ContextCompat.getColor(this, R.color.purple_600)
                    card.strokeWidth = (2.5f * resources.displayMetrics.density).toInt()
                    card.cardElevation = 4f
                } else {
                    card.strokeColor = Color.parseColor("#E2E8F0")
                    card.strokeWidth = (1f * resources.displayMetrics.density).toInt()
                    card.cardElevation = 0f
                }
            }
        }

        fun populateThumbnails() {
            layoutThumbnails.removeAllViews()
            if (currentSelectedCategory == "PHOTO" || currentSelectedCategory == "NONE") {
                hsvThumbnails.visibility = View.GONE
                return
            }
            hsvThumbnails.visibility = View.VISIBLE

            activeOptionsList.forEachIndexed { index, option ->
                val card = com.google.android.material.card.MaterialCardView(this).apply {
                    val w = (54 * resources.displayMetrics.density).toInt()
                    val h = (44 * resources.displayMetrics.density).toInt()
                    val m = (4 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(w, h).apply {
                        marginStart = m
                        marginEnd = m
                    }
                    radius = (10 * resources.displayMetrics.density)
                    cardElevation = 0f
                    strokeWidth = (1f * resources.displayMetrics.density).toInt()
                    strokeColor = Color.parseColor("#E2E8F0")

                    if (option.previewDrawable != null) {
                        background = option.previewDrawable
                    } else if (option.previewColor != null) {
                        setCardBackgroundColor(option.previewColor)
                    }

                    if (option.value == "HEX_CUSTOM") {
                        val tv = TextView(this@StoreProfileActivity).apply {
                            text = "HEX"
                            textSize = 10.5f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#475569"))
                        }
                        addView(tv)
                    }

                    setOnClickListener {
                        activeIndex = index
                        if (option.value == "HEX_CUSTOM") {
                            showCustomHexColorInputDialog { customHex, customTheme ->
                                applyHeaderBackgroundUI("COLOR", customHex, activeOverlay, customTheme)
                                tvName.text = "Color Hex: $customHex"
                                tvCounter.text = "Personalizado • En vivo"
                            }
                        } else {
                            applyCurrentSelectionLive()
                        }
                    }
                }
                layoutThumbnails.addView(card)
            }
        }

        fun switchCategory(category: String) {
            currentSelectedCategory = category
            when (category) {
                "GRADIENT" -> {
                    activeOptionsList = gradientOptions
                    activeIndex = 0
                    cardStepper.visibility = View.VISIBLE
                    hsvThumbnails.visibility = View.VISIBLE
                    layoutPhotoControls.visibility = View.GONE
                    populateThumbnails()
                    applyCurrentSelectionLive()
                }
                "COLOR" -> {
                    activeOptionsList = colorOptions
                    activeIndex = 0
                    cardStepper.visibility = View.VISIBLE
                    hsvThumbnails.visibility = View.VISIBLE
                    layoutPhotoControls.visibility = View.GONE
                    populateThumbnails()
                    applyCurrentSelectionLive()
                }
                "PHOTO" -> {
                    cardStepper.visibility = View.VISIBLE
                    hsvThumbnails.visibility = View.GONE
                    layoutPhotoControls.visibility = View.VISIBLE
                    updateOverlayButtonsUI()
                    applyCurrentSelectionLive()
                }
                "NONE" -> {
                    cardStepper.visibility = View.VISIBLE
                    hsvThumbnails.visibility = View.GONE
                    layoutPhotoControls.visibility = View.GONE
                    applyCurrentSelectionLive()
                }
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipCategoryGradients) -> switchCategory("GRADIENT")
                checkedIds.contains(R.id.chipCategoryColors) -> switchCategory("COLOR")
                checkedIds.contains(R.id.chipCategoryPhoto) -> switchCategory("PHOTO")
                checkedIds.contains(R.id.chipCategoryDefault) -> switchCategory("NONE")
            }
        }

        btnPrev.setOnClickListener {
            if (activeOptionsList.isNotEmpty()) {
                activeIndex = if (activeIndex - 1 < 0) activeOptionsList.size - 1 else activeIndex - 1
                applyCurrentSelectionLive()
            }
        }

        btnNext.setOnClickListener {
            if (activeOptionsList.isNotEmpty()) {
                activeIndex = (activeIndex + 1) % activeOptionsList.size
                applyCurrentSelectionLive()
            }
        }

        btnOverlay30.setOnClickListener { activeOverlay = 0.30; updateOverlayButtonsUI(); applyCurrentSelectionLive() }
        btnOverlay45.setOnClickListener { activeOverlay = 0.45; updateOverlayButtonsUI(); applyCurrentSelectionLive() }
        btnOverlay60.setOnClickListener { activeOverlay = 0.60; updateOverlayButtonsUI(); applyCurrentSelectionLive() }
        btnOverlay75.setOnClickListener { activeOverlay = 0.75; updateOverlayButtonsUI(); applyCurrentSelectionLive() }

        btnUploadNewPhoto.setOnClickListener {
            dialog.dismiss()
            showImagePickerOptions("HEADER_BG")
        }

        btnDiscard.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            isSaved = true
            when (currentSelectedCategory) {
                "GRADIENT" -> {
                    val opt = gradientOptions[activeIndex.coerceIn(0, gradientOptions.size - 1)]
                    headerBgType = "GRADIENT"
                    headerBgValue = opt.value
                    headerTextTheme = opt.theme
                }
                "COLOR" -> {
                    val opt = colorOptions[activeIndex.coerceIn(0, colorOptions.size - 1)]
                    if (opt.value != "HEX_CUSTOM") {
                        headerBgType = "COLOR"
                        headerBgValue = opt.value
                        headerTextTheme = opt.theme
                    }
                }
                "PHOTO" -> {
                    headerBgType = "IMAGE"
                    headerBgOverlay = activeOverlay
                    headerTextTheme = "LIGHT"
                }
                "NONE" -> {
                    headerBgType = "NONE"
                    headerBgValue = ""
                    headerTextTheme = "DARK"
                }
            }
            saveStoreFields(
                mapOf(
                    "header_bg_type" to headerBgType,
                    "header_bg_value" to headerBgValue,
                    "header_bg_overlay" to headerBgOverlay,
                    "header_text_theme" to headerTextTheme
                )
            )
            Toast.makeText(this, "Fondo guardado con éxito ✨", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!isSaved) {
                headerBgType = initialType
                headerBgValue = initialValue
                headerBgOverlay = initialOverlay
                headerTextTheme = initialTheme
                applyHeaderBackgroundUI(initialType, initialValue, initialOverlay, initialTheme)
            }
        }

        when (currentSelectedCategory) {
            "COLOR" -> chipColors.isChecked = true
            "PHOTO" -> chipPhoto.isChecked = true
            "NONE" -> chipDefault.isChecked = true
            else -> chipGradients.isChecked = true
        }
        switchCategory(currentSelectedCategory)

        dialog.show()
    }

    private fun showCustomHexColorInputDialog(onApplied: ((String, String) -> Unit)? = null) {
        val et = EditText(this).apply {
            hint = "Ejemplo: #3B82F6 o #111827"
            setText(if (headerBgValue.startsWith("#")) headerBgValue else "#")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Color Personalizado (Hex)")
            .setView(et)
            .setPositiveButton("Aplicar") { _, _ ->
                val hex = et.text.toString().trim()
                try {
                    val parsed = Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                    val darkness = 1.0 - (0.299 * Color.red(parsed) + 0.587 * Color.green(parsed) + 0.114 * Color.blue(parsed)) / 255.0
                    val theme = if (darkness > 0.45) "LIGHT" else "DARK"
                    headerBgType = "COLOR"
                    headerBgValue = hex
                    headerTextTheme = theme
                    if (onApplied != null) {
                        onApplied(hex, theme)
                    } else {
                        applyHeaderBackgroundUI("COLOR", hex, headerBgOverlay, theme)
                        saveStoreFields(
                            mapOf(
                                "header_bg_type" to "COLOR",
                                "header_bg_value" to hex,
                                "header_text_theme" to theme
                            )
                        )
                        Toast.makeText(this, "Fondo aplicado ✨", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Código de color no válido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showImagePickerOptions(type: String) {
        val galleryOption = if (type == "SECTION") "🖼️ Elegir de Galería (Selección Múltiple)" else "🖼️ Elegir de Galería"
        val options = arrayOf("📸 Tomar Fotografía", galleryOption)
        val title = when (type) {
            "LOGO" -> "Subir Logo del Negocio"
            "COVER" -> "Subir Imagen de Portada"
            "HEADER_BG" -> "Fondo para Cabecera del Negocio"
            else -> "Agregar Fotos a la Sección"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera(type)
                    1 -> openGallery(type)
                }
            }.show()
    }

    private fun openGallery(type: String) {
        when (type) {
            "LOGO" -> pickLogoLauncher.launch("image/*")
            "COVER" -> pickCoverLauncher.launch("image/*")
            "SECTION" -> pickSectionImagesLauncher.launch("image/*")
            "HEADER_BG" -> pickHeaderBgLauncher.launch("image/*")
        }
    }

    private fun openCamera(type: String) {
        currentCropType = type
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        try {
            val photoFile = File(filesDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            if (photoFile.parentFile?.exists() == false) photoFile.parentFile?.mkdirs()
            cameraImageUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
            takePhotoLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir cámara: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageLocally(uri: Uri, fileName: String): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) { null }
    }

    private fun uploadInlineImage(uri: Uri, type: String) {
        val docId = activeSettingsDocId.takeIf { it != "business" } ?: businessType
        if (type == "LOGO") {
            storeProfileBinding.pbLogoLoading.visibility = View.VISIBLE
            storeProfileBinding.layoutLogoPlaceholder.visibility = View.GONE
            Glide.with(this).load(uri).circleCrop().into(storeProfileBinding.ivProfileStoreLogo)
        } else if (type == "COVER") {
            storeProfileBinding.pbCoverLoading.visibility = View.VISIBLE
            storeProfileBinding.layoutCoverPlaceholder.visibility = View.GONE
            Glide.with(this).load(uri).centerCrop().into(storeProfileBinding.ivStoreCover)
        } else if (type == "HEADER_BG") {
            Toast.makeText(this, "Subiendo foto de fondo... ⏳", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val bucket = "naxor-imagenes-usuarios"
            val remotePath = when (type) {
                "LOGO" -> "logos/$storeId/$docId.png"
                "COVER" -> "covers/$storeId/$docId.png"
                "HEADER_BG" -> "header_bg/$storeId/${docId}_${System.currentTimeMillis()}.jpg"
                else -> "misc/$storeId/${System.currentTimeMillis()}.png"
            }
            val localFileName = when (type) {
                "LOGO" -> "logo_live.png"
                "COVER" -> "cover_live.png"
                "HEADER_BG" -> "header_bg_live.jpg"
                else -> "misc_live.png"
            }
            val localPath = saveImageLocally(uri, localFileName)
            val uploadedUrl = localPath?.let { SupabaseManager.uploadImage(bucket, remotePath, File(it)) }
            withContext(Dispatchers.Main) {
                storeProfileBinding.pbLogoLoading.visibility = View.GONE
                storeProfileBinding.pbCoverLoading.visibility = View.GONE
                if (!uploadedUrl.isNullOrEmpty()) {
                    if (type == "HEADER_BG") {
                        headerBgType = "IMAGE"
                        headerBgValue = uploadedUrl
                        headerBgOverlay = 0.45
                        headerTextTheme = "LIGHT"
                        applyHeaderBackgroundUI("IMAGE", uploadedUrl, 0.45, "LIGHT")
                        saveStoreFields(
                            mapOf(
                                "header_bg_type" to "IMAGE",
                                "header_bg_value" to uploadedUrl,
                                "header_bg_overlay" to 0.45,
                                "header_text_theme" to "LIGHT"
                            )
                        )
                        Toast.makeText(this@StoreProfileActivity, "Fondo de cabecera actualizado ✨", Toast.LENGTH_SHORT).show()
                    } else {
                        val field = if (type == "LOGO") "business_logo_url" else "business_cover_url"
                        saveStoreFields(mapOf(field to uploadedUrl))
                    }
                } else {
                    Toast.makeText(this@StoreProfileActivity, "No se pudo subir la imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var currentReplacingImageIndex: Int = -1

    private fun editSectionImage(url: String, sectionId: String, imageIndex: Int) {
        if (url.isBlank()) return
        currentTargetSectionId = sectionId
        currentReplacingImageIndex = imageIndex
        currentCropType = "SECTION_REPLACE"

        Toast.makeText(this, "Preparando foto para recortar... ⏳", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(filesDir, "temp_crop_sec_${System.currentTimeMillis()}.png")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val downloadedFile = Glide.with(this@StoreProfileActivity)
                        .downloadOnly()
                        .load(url)
                        .submit()
                        .get()
                    downloadedFile.copyTo(tempFile, overwrite = true)
                } else {
                    val sourceFile = if (url.startsWith("file://")) File(Uri.parse(url).path!!) else File(url)
                    if (sourceFile.exists()) {
                        sourceFile.copyTo(tempFile, overwrite = true)
                    } else {
                        val inputStream = contentResolver.openInputStream(Uri.parse(url))
                        val outputStream = FileOutputStream(tempFile)
                        inputStream?.use { input -> outputStream.use { output -> input.copyTo(output) } }
                    }
                }

                val localUri = FileProvider.getUriForFile(
                    this@StoreProfileActivity,
                    "$packageName.provider",
                    tempFile
                )
                withContext(Dispatchers.Main) {
                    startCrop(localUri, "SECTION_REPLACE")
                }
            } catch (e: Exception) {
                Log.e("StoreProfileActivity", "Error al preparar imagen para recortar: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StoreProfileActivity, "Error al abrir la foto para recortar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun replaceSectionImage(uri: Uri) {
        val secId = currentTargetSectionId ?: return
        val imgIdx = currentReplacingImageIndex
        val idx = extraSections.indexOfFirst { it.id == secId }
        if (idx == -1 || imgIdx < 0) return

        Toast.makeText(this, "Guardando imagen recortada... ⏳", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val bucket = "naxor-imagenes-usuarios"
            val localPath = saveImageLocally(uri, "sec_${UUID.randomUUID()}.png")
            val uploadedUrl = localPath?.let {
                SupabaseManager.uploadImage(bucket, "sections/$storeId/${UUID.randomUUID()}.png", File(it))
            }
            withContext(Dispatchers.Main) {
                if (!uploadedUrl.isNullOrEmpty()) {
                    val sec = extraSections[idx]
                    val urls = sec.imageUrls.toMutableList()
                    if (imgIdx < urls.size) {
                        urls[imgIdx] = uploadedUrl
                        extraSections[idx] = sec.copy(imageUrls = urls)
                        val updatedRaw = extraSections.map { s ->
                            mapOf(
                                "id" to s.id,
                                "title" to s.title,
                                "description" to s.description,
                                "titleStyled" to s.titleStyled,
                                "descriptionStyled" to s.descriptionStyled,
                                "imageUrls" to s.imageUrls,
                                "aspectRatio" to s.aspectRatio
                            )
                        }
                        lastRawExtraSections = updatedRaw
                        persistExtraSections()
                        renderExtraSections(lastRawExtraSections)
                        Toast.makeText(this@StoreProfileActivity, "🎉 Foto actualizada", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@StoreProfileActivity, "No se pudo guardar la foto recortada", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun uploadMultipleSectionImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!BillingManager.hasCloudSpaceAvailable(this)) {
            AlertDialog.Builder(this)
                .setTitle("☁️ Espacio en la Nube Lleno (15 MB)")
                .setMessage("Has alcanzado el límite de 15 MB de tu nube gratuita para fotos de tienda virtual.\n\nAmplía a 250 MB + Insignia Oficial con el Plan Profesional (S/ 15/mes) o a 1.5 GB con el Plan Empresarial (S/ 30/mes).")
                .setPositiveButton("Ver Planes") { _, _ ->
                    showSubscriptionBottomSheet()
                }
                .setNegativeButton("Ahora no", null)
                .show()
            return
        }
        val secId = currentTargetSectionId ?: return
        val index = extraSections.indexOfFirst { it.id == secId }
        if (index == -1) return
        val total = uris.size

        val density = resources.displayMetrics.density
        val dialogCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = (24 * density)
            cardElevation = (8 * density)
            setCardBackgroundColor(Color.WHITE)
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tvStatusTitle = TextView(this).apply {
            text = "Subiendo fotografías 📸"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_900))
            gravity = Gravity.CENTER
        }

        val tvCounter = TextView(this).apply {
            text = "Agregando foto (1 de $total)..."
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_700))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        }

        val progressBar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isIndeterminate = false
            max = 100
            progress = 0
            trackCornerRadius = (8 * density).toInt()
            trackThickness = (10 * density).toInt()
            setIndicatorColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_600))
            trackColor = ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_50)
        }

        val tvPercent = TextView(this).apply {
            text = "0%"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_600))
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        }

        dialogView.addView(tvStatusTitle)
        dialogView.addView(tvCounter)
        dialogView.addView(progressBar)
        dialogView.addView(tvPercent)
        dialogCard.addView(dialogView)

        val progressDialog = AlertDialog.Builder(this)
            .setView(dialogCard)
            .setCancelable(false)
            .create()

        progressDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val uploadedUrls = mutableListOf<String>()
            val bucket = "naxor-imagenes-usuarios"

            uris.forEachIndexed { i, uri ->
                val currentNum = i + 1
                val startPercent = ((i * 100) / total).coerceIn(0, 100)

                withContext(Dispatchers.Main) {
                    tvCounter.text = "Agregando foto ($currentNum de $total)..."
                    progressBar.setProgress(startPercent, true)
                    tvPercent.text = "$startPercent%"
                }

                val localPath = saveImageLocally(uri, "sec_${UUID.randomUUID()}.png")
                val uploadedUrl = localPath?.let {
                    SupabaseManager.uploadImage(bucket, "sections/$storeId/${UUID.randomUUID()}.png", File(it))
                }
                if (!uploadedUrl.isNullOrEmpty()) {
                    uploadedUrls.add(uploadedUrl)
                }

                val endPercent = ((currentNum * 100) / total).coerceIn(0, 100)
                withContext(Dispatchers.Main) {
                    progressBar.setProgress(endPercent, true)
                    tvPercent.text = "$endPercent%"
                }
            }

            withContext(Dispatchers.Main) {
                kotlinx.coroutines.delay(300)
                try { progressDialog.dismiss() } catch (_: Exception) {}

                if (uploadedUrls.isNotEmpty()) {
                    val idx = extraSections.indexOfFirst { it.id == secId }
                    if (idx != -1) {
                        val sec = extraSections[idx]
                        extraSections[idx] = sec.copy(imageUrls = sec.imageUrls + uploadedUrls)
                        val updatedRaw = extraSections.map { s ->
                            mapOf(
                                "id" to s.id,
                                "title" to s.title,
                                "description" to s.description,
                                "titleStyled" to s.titleStyled,
                                "descriptionStyled" to s.descriptionStyled,
                                "imageUrls" to s.imageUrls,
                                "aspectRatio" to s.aspectRatio
                            )
                        }
                        lastRawExtraSections = updatedRaw
                        persistExtraSections()
                        renderExtraSections(lastRawExtraSections)
                    }
                    Toast.makeText(this@StoreProfileActivity, "🎉 ¡${uploadedUrls.size} foto(s) agregadas a la sección!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@StoreProfileActivity, "No se pudieron subir las fotos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun persistExtraSections() {
        val rawList = extraSections.map { s ->
            mapOf(
                "id" to s.id,
                "title" to s.title,
                "description" to s.description,
                "titleStyled" to s.titleStyled,
                "descriptionStyled" to s.descriptionStyled,
                "imageUrls" to s.imageUrls,
                "aspectRatio" to s.aspectRatio
            )
        }
        saveStoreFields(mapOf("extraSections" to rawList))
    }

    private fun showAddOrEditSectionDialog(existing: StoreSection?) {
        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 24, 52, 12)
        }
        val etTitle = EditText(this).apply {
            hint = "Título de la sección (ej. Nuestras Instalaciones)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            maxLines = 1
            setText(existing?.title.orEmpty(), TextView.BufferType.SPANNABLE)
        }
        val etDesc = EditText(this).apply {
            hint = "Descripción o detalles de esta sección..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setText(existing?.description.orEmpty(), TextView.BufferType.SPANNABLE)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        com.gomezmart.app.util.SpanishInputHelper.configureEditText(etTitle)
        com.gomezmart.app.util.SpanishInputHelper.configureEditText(etDesc)
        container.addView(etTitle)
        container.addView(etDesc)

        val titleColorPanel = com.gomezmart.app.util.RichTextColorHelper.createWordColorEditorPanel(
            context = this,
            editText = etTitle,
            initialStyledHtml = existing?.titleStyled
        )
        val descColorPanel = com.gomezmart.app.util.RichTextColorHelper.createWordColorEditorPanel(
            context = this,
            editText = etDesc,
            initialStyledHtml = existing?.descriptionStyled
        ).apply { visibility = View.GONE }

        etTitle.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                titleColorPanel.visibility = View.VISIBLE
                descColorPanel.visibility = View.GONE
            }
        }
        etDesc.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                titleColorPanel.visibility = View.GONE
                descColorPanel.visibility = View.VISIBLE
            }
        }

        container.addView(titleColorPanel)
        container.addView(descColorPanel)
        scrollView.addView(container)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva Sección" else "Editar Sección")
            .setView(scrollView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val title = com.gomezmart.app.util.RichTextColorHelper.extractPlainText(etTitle.text)
                val desc = com.gomezmart.app.util.RichTextColorHelper.extractPlainText(etDesc.text)
                val titleStyled = etTitle.text?.let { com.gomezmart.app.util.RichTextColorHelper.toStyledHtml(it) }.orEmpty()
                val descStyled = etDesc.text?.let { com.gomezmart.app.util.RichTextColorHelper.toStyledHtml(it) }.orEmpty()
                if (title.isEmpty() && desc.isEmpty()) return@setPositiveButton
                if (existing == null) {
                    extraSections.add(
                        StoreSection(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            description = desc,
                            titleStyled = titleStyled,
                            descriptionStyled = descStyled
                        )
                    )
                } else {
                    val idx = extraSections.indexOfFirst { it.id == existing.id }
                    if (idx != -1) {
                        extraSections[idx] = extraSections[idx].copy(
                            title = title,
                            description = desc,
                            titleStyled = titleStyled,
                            descriptionStyled = descStyled
                        )
                    }
                }
                persistExtraSections()
            }

        if (existing != null) {
            builder.setNeutralButton("🗑️ Eliminar") { _, _ ->
                extraSections.removeAll { it.id == existing.id }
                persistExtraSections()
            }
        }
        val dialog = builder.create()
        com.gomezmart.app.util.SpanishInputHelper.attachToDialog(dialog)
        dialog.show()
    }

    private fun showManagePaymentMethodsDialog() {
        val currentTitles = paymentMethods.map { it.title }
        val checkedItems = BooleanArray(availablePaymentOptions.size) { i ->
            currentTitles.contains(availablePaymentOptions[i])
        }

        AlertDialog.Builder(this)
            .setTitle("Métodos de Pago que Aceptas 💳")
            .setMultiChoiceItems(
                availablePaymentOptions.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Guardar") { _, _ ->
                paymentMethods.clear()
                availablePaymentOptions.forEachIndexed { index, optionTitle ->
                    if (checkedItems[index]) {
                        paymentMethods.add(PaymentMethod(type = "METHOD", title = optionTitle, accountInfo = ""))
                    }
                }
                saveStoreFields(mapOf("paymentMethods" to paymentMethods))
                renderPublicPaymentMethods()
                Toast.makeText(this, "Métodos de pago actualizados ✨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveStoreFields(fields: Map<String, Any>, onComplete: (() -> Unit)? = null) {
        if (storeId.isEmpty()) return
        val docId = (activeSettingsDocId.takeIf { it != "business" } ?: businessType).ifEmpty { "PRODUCTS" }
        val payload = fields.toMutableMap()
        payload["business_type"] = docId

        db.collection("users").document(storeId)
            .collection("settings").document(docId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                prefs.putBoolean("${docId}_hide_create_page_intro", true)
                (fields["business_name"] as? String)?.let { prefs.putString("${docId}_name", it) }
                (fields["business_owner_name"] as? String)?.let { prefs.putString("${docId}_owner_name", it) }
                (fields["business_phone"] as? String)?.let { prefs.putString("${docId}_phone", it) }
                (fields["business_address"] as? String)?.let { prefs.putString("${docId}_address", it) }
                (fields["business_logo_url"] as? String)?.let { prefs.putString("${docId}_logo_url", it) }
                (fields["is_online"] as? Boolean)?.let {
                    prefs.putBoolean("is_online_store", it)
                    prefs.putBoolean("${docId}_is_online", it)
                    com.gomezmart.app.fragment.BuyerMarketplaceFragment.needsMarketplaceRefresh = true
                }
                prefs.apply()
                onComplete?.invoke()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error al guardar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun renderExtraSections(sections: List<Map<String, Any>>) {
        lastRawExtraSections = sections
        clearCarouselHandlers()
        storeProfileBinding.layoutExtraSectionsProfile.removeAllViews()
        extraSections.clear()
        sections.forEachIndexed { secIndex, m ->
            val id = m["id"] as? String ?: UUID.randomUUID().toString()
            val title = m["title"] as? String ?: ""
            val desc = m["description"] as? String ?: ""
            val titleStyled = m["titleStyled"] as? String ?: ""
            val descStyled = m["descriptionStyled"] as? String ?: ""
            val urls = (m["imageUrls"] as? List<*>)?.map { it.toString() } ?: emptyList()
            val secObj = StoreSection(
                id = id,
                title = title,
                description = desc,
                titleStyled = titleStyled,
                descriptionStyled = descStyled,
                imageUrls = urls
            )
            extraSections.add(secObj)

            if (title.isNotEmpty() || desc.isNotEmpty() || urls.isNotEmpty() || isEditingStore) {
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_store_extra_section, storeProfileBinding.layoutExtraSectionsProfile, false)
                val tvTitle = itemView.findViewById<TextView>(R.id.tvSectionTitle)
                val tvDesc = itemView.findViewById<TextView>(R.id.tvSectionContent)
                val vpImages = itemView.findViewById<ViewPager2>(R.id.vpSectionImages)
                val cardCarousel = itemView.findViewById<View>(R.id.cardSectionCarousel)

                tvTitle.text = if (title.isNotEmpty()) {
                    com.gomezmart.app.util.RichTextColorHelper.parseStyledText(titleStyled, title)
                } else if (isEditingStore) {
                    "Título de la sección"
                } else {
                    ""
                }
                tvDesc.text = if (desc.isNotEmpty()) {
                    com.gomezmart.app.util.RichTextColorHelper.parseStyledText(descStyled, desc)
                } else if (isEditingStore) {
                    "Toca para editar el contenido o agregar fotos..."
                } else {
                    ""
                }
                if (tvTitle.text.isEmpty()) tvTitle.visibility = View.GONE
                if (tvDesc.text.isEmpty()) tvDesc.visibility = View.GONE

                val layoutActions = itemView.findViewById<View>(R.id.layoutSectionActions)
                val btnManageImages = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnManageSectionImages)
                val btnEditSec = itemView.findViewById<View>(R.id.btnEditSection)
                val btnMoveUp = itemView.findViewById<View>(R.id.btnMoveSectionUp)
                val btnMoveDown = itemView.findViewById<View>(R.id.btnMoveSectionDown)

                if (isEditingStore) {
                    layoutActions?.visibility = View.VISIBLE
                    btnEditSec?.setOnClickListener {
                        showAddOrEditSectionDialog(secObj)
                    }

                    btnManageImages?.visibility = View.VISIBLE
                    if (urls.isNotEmpty()) {
                        btnManageImages?.text = "🖼️ Fotos (${urls.size})"
                        btnManageImages?.setOnClickListener {
                            showManageSectionImagesDialog(secObj)
                        }
                    } else {
                        btnManageImages?.text = "🖼️ + Fotos"
                        btnManageImages?.setOnClickListener {
                            currentTargetSectionId = id
                            showImagePickerOptions("SECTION")
                        }
                    }

                    if (sections.size > 1) {
                        btnMoveUp?.visibility = if (secIndex > 0) View.VISIBLE else View.GONE
                        btnMoveDown?.visibility = if (secIndex < sections.size - 1) View.VISIBLE else View.GONE

                        btnMoveUp?.setOnClickListener {
                            if (secIndex > 0) {
                                java.util.Collections.swap(extraSections, secIndex, secIndex - 1)
                                persistExtraSections()
                                renderExtraSections(lastRawExtraSections)
                            }
                        }
                        btnMoveDown?.setOnClickListener {
                            if (secIndex < sections.size - 1) {
                                java.util.Collections.swap(extraSections, secIndex, secIndex + 1)
                                persistExtraSections()
                                renderExtraSections(lastRawExtraSections)
                            }
                        }
                    } else {
                        btnMoveUp?.visibility = View.GONE
                        btnMoveDown?.visibility = View.GONE
                    }
                } else {
                    layoutActions?.visibility = View.GONE
                }

                if (urls.isNotEmpty()) {
                    cardCarousel.visibility = View.VISIBLE
                    val imageAdapter = SectionImageAdapter(urls) { clickedPos ->
                        if (isEditingStore) {
                            showManageSectionImagesDialog(secObj)
                        } else {
                            val pos = clickedPos.coerceIn(0, urls.size - 1)
                            showFullScreenGalleryDialog(urls, pos)
                        }
                    }
                    vpImages.adapter = imageAdapter

                    val dotsContainer = itemView.findViewById<LinearLayout>(R.id.layoutDotsIndicator)
                    setupAnimatedDotsIndicator(dotsContainer, vpImages, urls.size)

                    if (urls.size > 1) {
                        val handler = Handler(Looper.getMainLooper())
                        val runnable = object : Runnable {
                            override fun run() {
                                if (isFinishing || isDestroyed || !vpImages.isAttachedToWindow) return
                                // Solo cambiar si el usuario no está tocando/arrastrando el ViewPager en ese instante
                                if (vpImages.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                                    val current = vpImages.currentItem
                                    val next = (current + 1) % urls.size
                                    vpImages.setCurrentItem(next, true)
                                }
                                handler.postDelayed(this, 5000)
                            }
                        }
                        carouselHandlers.add(handler to runnable)
                        handler.postDelayed(runnable, 5000)
                    }
                } else {
                    cardCarousel.visibility = View.GONE
                }

                storeProfileBinding.layoutExtraSectionsProfile.addView(itemView)
            }
        }
    }

    private fun setupAnimatedDotsIndicator(container: LinearLayout?, viewPager: ViewPager2, count: Int) {
        if (container == null) return
        container.removeAllViews()
        if (count <= 1) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val dotInactiveWidth = (11 * density).toInt()
        val dotActiveWidth = (26 * density).toInt()
        val dotHeight = (11 * density).toInt()
        val dotMargin = (5 * density).toInt()

        val dotViews = mutableListOf<View>()

        for (i in 0 until count) {
            val dot = View(this).apply {
                val w = if (i == viewPager.currentItem) dotActiveWidth else dotInactiveWidth
                layoutParams = LinearLayout.LayoutParams(w, dotHeight).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                background = ContextCompat.getDrawable(
                    this@StoreProfileActivity,
                    if (i == viewPager.currentItem) R.drawable.bg_dot_active_shape else R.drawable.bg_dot_inactive_shape
                )
            }
            dotViews.add(dot)
            container.addView(dot)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var lastSelectedPosition = viewPager.currentItem

            override fun onPageSelected(position: Int) {
                if (position == lastSelectedPosition || position !in dotViews.indices) return

                val oldDot = dotViews[lastSelectedPosition]
                val newDot = dotViews[position]
                lastSelectedPosition = position

                val animOld = ValueAnimator.ofInt(oldDot.width, dotInactiveWidth).apply {
                    duration = 250
                    addUpdateListener { anim ->
                        val p = oldDot.layoutParams
                        p.width = anim.animatedValue as Int
                        oldDot.layoutParams = p
                    }
                }
                oldDot.background = ContextCompat.getDrawable(this@StoreProfileActivity, R.drawable.bg_dot_inactive_shape)

                val animNew = ValueAnimator.ofInt(newDot.width, dotActiveWidth).apply {
                    duration = 250
                    addUpdateListener { anim ->
                        val p = newDot.layoutParams
                        p.width = anim.animatedValue as Int
                        newDot.layoutParams = p
                    }
                }
                newDot.background = ContextCompat.getDrawable(this@StoreProfileActivity, R.color.purple_600.let { R.drawable.bg_dot_active_shape })

                animOld.start()
                animNew.start()
            }
        })
    }

    private fun showManageSectionImagesDialog(section: StoreSection) {
        val secIdx = extraSections.indexOfFirst { it.id == section.id }
        if (secIdx == -1) return

        val currentSec = extraSections[secIdx]
        val urls = currentSec.imageUrls.toMutableList()

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        val tvInfo = TextView(this).apply {
            text = "Reordena las fotos con las flechas o elimina las que desees. La primera foto (Foto 1) será la portada principal de la sección."
            setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_600))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(tvInfo)

        val photosContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(photosContainer)

        fun updateSectionState(newUrls: List<String>) {
            extraSections[secIdx] = extraSections[secIdx].copy(imageUrls = newUrls)
            val updatedRaw = extraSections.map { s ->
                mapOf(
                    "id" to s.id,
                    "title" to s.title,
                    "description" to s.description,
                    "titleStyled" to s.titleStyled,
                    "descriptionStyled" to s.descriptionStyled,
                    "imageUrls" to s.imageUrls,
                    "aspectRatio" to s.aspectRatio
                )
            }
            lastRawExtraSections = updatedRaw
            persistExtraSections()
            renderExtraSections(lastRawExtraSections)
        }

        var dialogRef: AlertDialog? = null

        fun renderPhotosInDialog() {
            photosContainer.removeAllViews()
            if (urls.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = "No hay fotos en esta sección."
                    setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_500))
                    textSize = 14f
                    setPadding(0, 16, 0, 16)
                }
                photosContainer.addView(tvEmpty)
            } else {
                urls.forEachIndexed { i, url ->
                    val card = com.google.android.material.card.MaterialCardView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 16) }
                        radius = (16 * resources.displayMetrics.density)
                        cardElevation = (2 * resources.displayMetrics.density)
                        setCardBackgroundColor(Color.WHITE)
                        strokeWidth = (1 * resources.displayMetrics.density).toInt()
                        strokeColor = ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_200)
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(16, 16, 16, 16)
                    }

                    val iv = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (80 * resources.displayMetrics.density).toInt(),
                            (80 * resources.displayMetrics.density).toInt()
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    Glide.with(this)
                        .load(url)
                        .override(200, 200)
                        .into(iv)

                    val infoCol = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply { marginStart = 14 }
                    }

                    val tvBadge = TextView(this).apply {
                        text = if (i == 0) "⭐ Foto 1 (Portada)" else "Foto ${i + 1}"
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(
                            if (i == 0) ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_700)
                            else ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_800)
                        )
                    }
                    infoCol.addView(tvBadge)

                    // FILA 1 DE BOTONES: REORDENAR (Subir / Bajar)
                    val btnRow1 = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 6, 0, 0)
                    }

                    val btnUp = com.google.android.material.button.MaterialButton(this).apply {
                        text = "⬆️ Subir"
                        textSize = 10.5f
                        isEnabled = i > 0
                        setPadding(12, 0, 12, 0)
                        setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_800))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_100))
                        setOnClickListener {
                            if (i > 0) {
                                java.util.Collections.swap(urls, i, i - 1)
                                updateSectionState(urls)
                                renderPhotosInDialog()
                            }
                        }
                    }

                    val btnDown = com.google.android.material.button.MaterialButton(this).apply {
                        text = "⬇️ Bajar"
                        textSize = 10.5f
                        isEnabled = i < urls.size - 1
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = 6 }
                        setPadding(12, 0, 12, 0)
                        setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_800))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@StoreProfileActivity, R.color.slate_100))
                        setOnClickListener {
                            if (i < urls.size - 1) {
                                java.util.Collections.swap(urls, i, i + 1)
                                updateSectionState(urls)
                                renderPhotosInDialog()
                            }
                        }
                    }

                    btnRow1.addView(btnUp)
                    btnRow1.addView(btnDown)
                    infoCol.addView(btnRow1)

                    // FILA 2 DE BOTONES: EDICIÓN (Recortar / Eliminar)
                    val btnRow2 = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 4, 0, 0)
                    }

                    val btnCrop = com.google.android.material.button.MaterialButton(this).apply {
                        text = "✂️ Recortar"
                        textSize = 10.5f
                        setPadding(12, 0, 12, 0)
                        setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_700))
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_50))
                        setOnClickListener {
                            dialogRef?.dismiss()
                            editSectionImage(url, section.id, i)
                        }
                    }

                    val btnDelete = com.google.android.material.button.MaterialButton(this).apply {
                        text = "🗑️ Eliminar"
                        textSize = 10.5f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = 6 }
                        setPadding(12, 0, 12, 0)
                        setTextColor(Color.RED)
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@StoreProfileActivity, R.color.red_50))
                        setOnClickListener {
                            AlertDialog.Builder(this@StoreProfileActivity)
                                .setTitle("¿Eliminar foto?")
                                .setMessage("Se quitará esta imagen de la sección.")
                                .setPositiveButton("Eliminar") { _, _ ->
                                    urls.removeAt(i)
                                    updateSectionState(urls)
                                    renderPhotosInDialog()
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }

                    btnRow2.addView(btnCrop)
                    btnRow2.addView(btnDelete)
                    infoCol.addView(btnRow2)

                    row.addView(iv)
                    row.addView(infoCol)
                    card.addView(row)
                    photosContainer.addView(card)
                }
            }
        }

        renderPhotosInDialog()

        val btnAdd = com.google.android.material.button.MaterialButton(this).apply {
            text = "📷 Agregar otra foto"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_700))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@StoreProfileActivity, R.color.purple_50))
            setOnClickListener {
                dialogRef?.dismiss()
                currentTargetSectionId = section.id
                showImagePickerOptions("SECTION")
            }
        }
        mainLayout.addView(btnAdd)
        scrollView.addView(mainLayout)

        dialogRef = AlertDialog.Builder(this)
            .setTitle("Gestionar Fotos de la Sección 🖼️")
            .setView(scrollView)
            .setPositiveButton("Listo", null)
            .show()
    }

    private fun showFullScreenGalleryDialog(urls: List<String>, initialPosition: Int = 0) {
        if (urls.isEmpty()) return
        val total = urls.size
        val startPos = initialPosition.coerceIn(0, total - 1)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.dialog_full_screen_image, null)
        dialog.setContentView(view)

        val vpGallery = view.findViewById<ViewPager2>(R.id.vpFullScreenGallery)
        val ivFull = view.findViewById<ImageView>(R.id.ivFullScreenPhoto)
        val pbFull = view.findViewById<ProgressBar>(R.id.pbFullScreenPhoto)
        val tvCounter = view.findViewById<TextView>(R.id.tvFullScreenPhotoCounter)
        val btnClose = view.findViewById<View>(R.id.btnCloseFullScreenPhoto)

        btnClose.setOnClickListener { dialog.dismiss() }

        if (total == 1) {
            vpGallery.visibility = View.GONE
            ivFull.visibility = View.VISIBLE
            tvCounter.visibility = View.GONE
            pbFull.visibility = View.VISIBLE
            Glide.with(this)
                .load(urls[0])
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        pbFull.visibility = View.GONE
                        Toast.makeText(this@StoreProfileActivity, "No se pudo cargar la foto", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        pbFull.visibility = View.GONE
                        return false
                    }
                })
                .into(ivFull)
        } else {
            vpGallery.visibility = View.VISIBLE
            ivFull.visibility = View.GONE
            pbFull.visibility = View.GONE
            tvCounter.visibility = View.VISIBLE
            tvCounter.text = "${startPos + 1} / $total"

            val galleryAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                inner class Holder(v: View) : RecyclerView.ViewHolder(v)

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val iv = ImageView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    return Holder(iv)
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val iv = holder.itemView as ImageView
                    Glide.with(holder.itemView)
                        .load(urls[position])
                        .into(iv)
                }

                override fun getItemCount() = urls.size
            }

            vpGallery.adapter = galleryAdapter
            vpGallery.setCurrentItem(startPos, false)

            vpGallery.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    tvCounter.text = "${position + 1} / $total"
                }
            })
        }

        dialog.show()
    }

    inner class SectionImageAdapter(
        private val urls: List<String>,
        private val onImageClick: ((Int) -> Unit)? = null
    ) : RecyclerView.Adapter<SectionImageAdapter.ViewHolder>() {
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
                .apply(GlideHelper.getOptions(holder.itemView.context))
                .override(800, 600)
                .into(holder.iv)

            holder.iv.setOnClickListener {
                onImageClick?.invoke(position)
            }
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
        val intentName = intent.getStringExtra("STORE_NAME") ?: ""

        storeRegistration = db.collection("users").document(sId)
            .collection("settings").document(docId)
            .addSnapshotListener { doc, e ->
                if (e != null) {
                    showLoadingError("No se pudo conectar con el servidor. Revisa tu conexión a internet.")
                    return@addSnapshotListener
                }
                if (doc == null || !doc.exists()) {
                    if (forcedRubro != null && docId != "business" && !isOwner) {
                        startStoreListener(sId, null)
                    } else {
                        activeSettingsDocId = forcedRubro ?: "PRODUCTS"
                        applyBlankOrDefaultOwnerState()
                        dismissLoadingOverlay()
                    }
                    return@addSnapshotListener
                }
                
                val currentData = doc.data.toString()
                if (currentData == lastStoreData) return@addSnapshotListener
                lastStoreData = currentData
                activeSettingsDocId = docId

                cachedAverageRating = doc.getDouble("average_rating") ?: 0.0
                cachedReviewCount = (doc.getLong("review_count") ?: 0L).toInt()
                cachedFollowersCount = (doc.getLong("followers_count") ?: 0L).toInt()
                if (currentReviewsList.isEmpty() && cachedReviewCount > 0) {
                    updateRatingSummaryPill(cachedAverageRating, cachedReviewCount)
                }
                if (currentFollowersList.isEmpty() && cachedFollowersCount > 0) {
                    updateFollowersSummaryPill(cachedFollowersCount)
                }

                val rawName = doc.getString("business_name") ?: doc.getString("${docId}_name") ?: intentName
                val cleanName = if (rawName.equals("Mi Negocio", ignoreCase = true)) "" else rawName
                val desc = doc.getString("business_description") ?: ""
                val addr = doc.getString("business_address") ?: ""
                val logo = doc.getString("business_logo_url")
                val cover = doc.getString("business_cover_url")
                val owner = doc.getString("business_owner_name") ?: ""
                val bType = doc.getString("business_type") ?: forcedRubro ?: "PRODUCTS"
                isStoreOnline = doc.getBoolean("is_online") ?: false
                isStoreVerified = doc.getBoolean("is_verified") ?: false
                updateVerifiedBadgeUI()
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
                storeLogoUrl = logo
                storeLogoPosition = doc.getString("business_logo_position") ?: "LEFT"
                applyLogoPositionUI(storeLogoPosition)
                storeCoverUrl = cover
                storeDisplayName = cleanName
                storeDisplayNameStyled = doc.getString("business_name_styled") ?: ""
                storeDescription = desc
                storeDescriptionStyled = doc.getString("business_description_styled") ?: ""
                storeOwnerName = owner
                storeOwnerNameStyled = doc.getString("business_owner_name_styled") ?: ""
                storeAddress = addr
                storeAddressStyled = doc.getString("business_address_styled") ?: ""

                headerBgType = doc.getString("header_bg_type") ?: "NONE"
                headerBgValue = doc.getString("header_bg_value") ?: ""
                headerBgOverlay = doc.getDouble("header_bg_overlay") ?: 0.45
                headerTextTheme = doc.getString("header_text_theme") ?: if (headerBgType == "IMAGE" || headerBgType == "GRADIENT") "LIGHT" else "DARK"
                applyHeaderBackgroundUI(headerBgType, headerBgValue, headerBgOverlay, headerTextTheme)

                if (isOwner && (cleanName.isNotBlank() || desc.isNotBlank() || !logo.isNullOrEmpty() || !cover.isNullOrEmpty())) {
                    getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                        .putBoolean("${bType}_hide_create_page_intro", true)
                        .apply()
                }

                storeProfileBinding.tvProfileStoreCategory.text = rubro
                refreshStoreTextAndPlaceholdersUI()

                storeProfileBinding.btnContactWhatsApp.visibility = if (storePhone.isNotEmpty() && !isEditingStore) View.VISIBLE else View.GONE
                
                if (bType == "HOTEL") {
                    storeProfileBinding.cardHotelAvailability.visibility = View.VISIBLE
                    if (!isOwner) {
                        storeProfileBinding.tvCatalogButtonIcon.text = "🛎️"
                        storeProfileBinding.tvCatalogButtonText.text = "VER HABITACIONES"
                    }
                } else {
                    storeProfileBinding.cardHotelAvailability.visibility = View.GONE
                    if (!isOwner) {
                        storeProfileBinding.tvCatalogButtonIcon.text = "🛍️"
                        storeProfileBinding.tvCatalogButtonText.text = "VER CATÁLOGO VIRTUAL"
                    }
                }
                updateOwnerPublishButtonUI()

                val glide = Glide.with(this@StoreProfileActivity)

                if (!logo.isNullOrEmpty()) {
                    storeProfileBinding.layoutLogoPlaceholder.visibility = View.GONE
                    storeProfileBinding.pbLogoLoading.visibility = View.VISIBLE
                    storeProfileBinding.ivProfileStoreLogo.alpha = 0f
                    glide.load(logo)
                        .apply(GlideHelper.getOptions(this@StoreProfileActivity))
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                storeProfileBinding.pbLogoLoading.visibility = View.GONE
                                storeProfileBinding.ivProfileStoreLogo.alpha = 1f
                                return false
                            }
                            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                storeProfileBinding.pbLogoLoading.visibility = View.GONE
                                storeProfileBinding.ivProfileStoreLogo.animate().alpha(1f).setDuration(400).start()
                                return false
                            }
                        })
                        .circleCrop()
                        .into(storeProfileBinding.ivProfileStoreLogo)
                } else {
                    storeProfileBinding.ivProfileStoreLogo.setImageDrawable(null)
                    storeProfileBinding.layoutLogoPlaceholder.visibility = if (isEditingStore) View.VISIBLE else View.GONE
                }
                
                if (!cover.isNullOrEmpty()) {
                    storeProfileBinding.layoutCoverPlaceholder.visibility = View.GONE
                    storeProfileBinding.pbCoverLoading.visibility = View.VISIBLE
                    storeProfileBinding.ivStoreCover.alpha = 0f
                    glide.load(cover)
                        .apply(GlideHelper.getOptions(this@StoreProfileActivity))
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                storeProfileBinding.pbCoverLoading.visibility = View.GONE
                                storeProfileBinding.ivStoreCover.alpha = 1f
                                return false
                            }
                            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                storeProfileBinding.pbCoverLoading.visibility = View.GONE
                                storeProfileBinding.ivStoreCover.animate().alpha(1f).setDuration(400).start()
                                return false
                            }
                        })
                        .centerCrop()
                        .into(storeProfileBinding.ivStoreCover)
                } else {
                    storeProfileBinding.ivStoreCover.setImageDrawable(null)
                    storeProfileBinding.layoutCoverPlaceholder.visibility = if (isEditingStore) View.VISIBLE else View.GONE
                }

                val lat = doc.getDouble("latitude") ?: 0.0
                val lng = doc.getDouble("longitude") ?: 0.0
                updateMapLocation(lat, lng, cleanName.ifEmpty { "Mi Negocio" })

                val extras = doc.get("extraSections") as? List<Map<String, Any>> ?: emptyList()
                renderExtraSections(extras)

                val payments = doc.get("paymentMethods") as? List<Map<String, Any>>
                paymentMethods.clear()
                payments?.forEach { m ->
                    paymentMethods.add(PaymentMethod(
                        type = m["type"] as? String ?: "BANK",
                        title = m["title"] as? String ?: "",
                        accountInfo = m["accountInfo"] as? String ?: ""
                    ))
                }
                renderPublicPaymentMethods()

                val themeMap = doc.get("theme") as? Map<String, Any>
                if (themeMap != null) applyDynamicTheme(themeMap)

                loadStoreProducts(sId, bType)

                if (bType != lastRecommendedType) {
                    loadRecommendations(bType)
                }

                dismissLoadingOverlay()
            }
    }

    private fun applyBlankOrDefaultOwnerState() {
        val bType = intent.getStringExtra("FORCE_RUBRO") ?: businessType
        businessType = bType
        storeProfileBinding.tvProfileStoreCategory.text = when (bType) {
            "HOTEL" -> "HOSPEDAJE / TURISMO"
            "LOANS" -> "SERVICIOS FINANCIEROS"
            else -> "TIENDA DE PRODUCTOS"
        }
        refreshStoreTextAndPlaceholdersUI()
        applyHeaderBackgroundUI("NONE", "", 0.45, "DARK")
        renderPublicPaymentMethods()
        updateOwnerPublishButtonUI()
        dismissLoadingOverlay()
    }

    private var loadingLogoAnimator: ObjectAnimator? = null
    private var isFirstLoadDismissed = false

    private fun showLoadingOverlay() {
        isFirstLoadDismissed = false
        storeProfileBinding.layoutFullPageLoadingOverlay.visibility = View.VISIBLE
        storeProfileBinding.layoutFullPageLoadingOverlay.alpha = 1f
        storeProfileBinding.pbFullPageLoading.visibility = View.VISIBLE
        storeProfileBinding.btnRetryLoadingEditor.visibility = View.GONE
        storeProfileBinding.tvLoadingTitle.text = "Cargando Editor de Negocio Virtual"
        storeProfileBinding.tvLoadingSubtitle.text = "Sincronizando secciones y datos con la nube ☁️"

        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.08f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.08f, 1.0f)
        loadingLogoAnimator?.cancel()
        loadingLogoAnimator = ObjectAnimator.ofPropertyValuesHolder(storeProfileBinding.cardLoadingLogo, scaleX, scaleY).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }

        storeProfileBinding.btnRetryLoadingEditor.setOnClickListener {
            showLoadingOverlay()
            startStoreListener(storeId, businessType)
        }
    }

    private fun dismissLoadingOverlay() {
        if (isFirstLoadDismissed) return
        isFirstLoadDismissed = true
        loadingLogoAnimator?.cancel()

        storeProfileBinding.layoutFullPageLoadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                if (!isDestroyed && !isFinishing) {
                    storeProfileBinding.layoutFullPageLoadingOverlay.visibility = View.GONE
                }
            }
            .start()
    }

    private fun showLoadingError(message: String) {
        isFirstLoadDismissed = false
        storeProfileBinding.layoutFullPageLoadingOverlay.visibility = View.VISIBLE
        storeProfileBinding.layoutFullPageLoadingOverlay.alpha = 1f
        storeProfileBinding.pbFullPageLoading.visibility = View.GONE
        storeProfileBinding.btnRetryLoadingEditor.visibility = View.VISIBLE
        storeProfileBinding.tvLoadingTitle.text = "Sin conexión a internet 📶"
        storeProfileBinding.tvLoadingSubtitle.text = message
        loadingLogoAnimator?.cancel()
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
        if (sId.isEmpty()) return
        reviewsRegistration?.remove()
        reviewsRegistration = db.collection("users").document(sId).collection("reviews")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val reviews = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(StoreReview::class.java)?.let { r ->
                        if (r.id.isBlank()) r.copy(id = doc.id) else r
                    }
                }
                    .sortedByDescending { it.timestamp }
                    .distinctBy { it.userId.ifBlank { it.id } }
                currentReviewsList = reviews
                reviewsAdapter.updateData(reviews)
                storeProfileBinding.tvNoReviews.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE

                val reviewCount = reviews.size
                val avgRating = if (reviews.isNotEmpty()) reviews.map { it.rating }.average() else 0.0
                updateRatingSummaryPill(avgRating, reviewCount)

                // Si el dueño abre su perfil y los contadores cacheados quedaron desactualizados, sincronizarlos
                if (isOwner && (reviewCount != cachedReviewCount || kotlin.math.abs(avgRating - cachedAverageRating) > 0.05)) {
                    syncStoreRatingToSettings(avgRating, reviewCount)
                }

                // Lógica para ocultar/mostrar el editor de comentarios
                val currentUserId = auth.currentUser?.uid
                myReview = reviews.find { it.userId == currentUserId }

                if (isGuestUser() || isOwner) {
                    storeProfileBinding.cardAddReview.visibility = View.GONE
                } else if (myReview != null) {
                    storeProfileBinding.cardAddReview.visibility = View.GONE
                } else {
                    storeProfileBinding.cardAddReview.visibility = View.VISIBLE
                    storeProfileBinding.btnSubmitReview.text = "PUBLICAR CALIFICACIÓN"
                }
            }
    }

    private fun updateRatingSummaryPill(avgRating: Double, reviewCount: Int) {
        if (reviewCount > 0) {
            val formattedAvg = String.format(Locale.US, "%.1f", avgRating)
            storeProfileBinding.tvStoreAverageRatingPill.text = "★ $formattedAvg ($reviewCount)"
            storeProfileBinding.tvReviewsSectionTitle.text = "RESEÑAS Y COMENTARIOS ($reviewCount)"
        } else {
            storeProfileBinding.tvStoreAverageRatingPill.text = "☆ Sé el primero en calificar"
            storeProfileBinding.tvReviewsSectionTitle.text = "RESEÑAS Y COMENTARIOS"
        }
    }

    private fun syncStoreRatingToSettings(avgRating: Double, reviewCount: Int) {
        if (storeId.isEmpty() || activeSettingsDocId.isEmpty()) return
        val roundedAvg = kotlin.math.round(avgRating * 10.0) / 10.0
        cachedAverageRating = roundedAvg
        cachedReviewCount = reviewCount
        db.collection("users").document(storeId)
            .collection("settings").document(activeSettingsDocId)
            .update(
                mapOf(
                    "average_rating" to roundedAvg,
                    "review_count" to reviewCount
                )
            )
            .addOnFailureListener { e ->
                Log.d("StoreProfile", "No se pudo sincronizar promedio en settings: ${e.message}")
            }
    }

    private var isCatalogButtonHidden = false

    private fun setupScrollAnimationForCatalogButton() {
        storeProfileBinding.nsvStoreProfile.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val reviewsTop = storeProfileBinding.tvReviewsSectionTitle.top
            val nsvHeight = storeProfileBinding.nsvStoreProfile.height
            val scrollThreshold = reviewsTop - (nsvHeight * 0.7f)

            if (scrollY >= scrollThreshold && reviewsTop > 0) {
                if (!isCatalogButtonHidden) {
                    isCatalogButtonHidden = true
                    val slideDistance = (storeProfileBinding.layoutBottomActionBar.width.coerceAtLeast(300) + 150).toFloat()
                    storeProfileBinding.layoutBottomActionBar.animate()
                        .translationX(slideDistance)
                        .alpha(0f)
                        .setDuration(300)
                        .start()
                }
            } else {
                if (isCatalogButtonHidden) {
                    isCatalogButtonHidden = false
                    storeProfileBinding.layoutBottomActionBar.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }
            }
        }
    }

    private fun submitReview() {
        if (requireLoginForGuest("calificar esta tienda")) return
        if (isOwner) {
            Toast.makeText(this, "No puedes calificar tu propia tienda", Toast.LENGTH_SHORT).show()
            return
        }
        val user = auth.currentUser ?: return
        val comment = storeProfileBinding.etNewReviewComment.text.toString().trim()
        val rating = storeProfileBinding.rbNewReview.rating.toInt().coerceIn(1, 5)

        val reviewId = user.uid // Una calificación por usuario
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
                Toast.makeText(this, "¡Calificación guardada! ⭐", Toast.LENGTH_SHORT).show()
                storeProfileBinding.etNewReviewComment.setText("")
                storeProfileBinding.rbNewReview.rating = 5f
                storeProfileBinding.cardAddReview.visibility = View.GONE

                val updatedList = currentReviewsList.filterNot { it.userId == user.uid } + review
                val newAvg = updatedList.map { it.rating }.average()
                syncStoreRatingToSettings(newAvg, updatedList.size)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al guardar calificación", Toast.LENGTH_SHORT).show()
            }
    }

    private fun startEditReview(review: StoreReview) {
        if (requireLoginForGuest("editar tu calificación")) return
        storeProfileBinding.etNewReviewComment.setText(review.comment)
        storeProfileBinding.rbNewReview.rating = review.rating.toFloat()
        storeProfileBinding.cardAddReview.visibility = View.VISIBLE
        storeProfileBinding.btnSubmitReview.text = "ACTUALIZAR CALIFICACIÓN"
        
        // Hacer scroll hasta el editor
        storeProfileBinding.nsvStoreProfile.smoothScrollTo(0, storeProfileBinding.cardAddReview.top)
    }
    private fun startFollowersListener(sId: String) {
        if (sId.isEmpty()) return
        followersRegistration?.remove()
        followersRegistration = db.collection("users").document(sId).collection("followers")
            .orderBy("followedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val followers = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(StoreFollower::class.java)?.let { f ->
                        if (f.uid.isBlank()) f.copy(uid = doc.id) else f
                    }
                }.distinctBy { it.uid }

                currentFollowersList = followers
                val count = followers.size
                updateFollowersSummaryPill(count)

                val myUid = auth.currentUser?.uid
                isFollowingStore = myUid != null && !isGuestUser() && followers.any { it.uid == myUid }
                updateFollowButtonState()

                followersSheetAdapter?.updateData(followers)
                followersSheetCountView?.text = if (count == 1) "1 seguidor" else "$count seguidores"
                followersSheetEmptyView?.visibility = if (followers.isEmpty()) View.VISIBLE else View.GONE

                if (isOwner && count != cachedFollowersCount) {
                    syncStoreFollowersToSettings(count)
                }
            }
    }

    private fun updateFollowersSummaryPill(count: Int) {
        val label = if (count == 1) "seguidor" else "seguidores"
        storeProfileBinding.tvStoreFollowersPill.text = "👥 $count $label"
    }

    private fun updateFollowButtonState() {
        val btn = storeProfileBinding.btnFollowStore
        if (isFollowingStore) {
            btn.text = "✓ Siguiendo"
            btn.setTextColor(ContextCompat.getColor(this, R.color.emerald_700))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.emerald_50))
            btn.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            btn.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.emerald_600))
        } else {
            btn.text = "+ Seguir"
            btn.setTextColor(ContextCompat.getColor(this, R.color.white))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.purple_600))
            btn.strokeWidth = 0
        }
    }

    private fun toggleFollowStore() {
        if (requireLoginForGuest("seguir esta tienda y recibir sus novedades")) return
        if (isOwner || storeId.isBlank() || isFollowActionInFlight) return
        val user = auth.currentUser ?: return

        isFollowActionInFlight = true
        storeProfileBinding.btnFollowStore.isEnabled = false
        val willFollow = !isFollowingStore

        lifecycleScope.launch {
            if (willFollow) {
                var myName = user.displayName ?: ""
                var myPhoto = user.photoUrl?.toString()
                if (myName.isBlank()) {
                    val infoDoc = runCatching {
                        db.collection("users").document(user.uid)
                            .collection("profile").document("info").get().await()
                    }.getOrNull()
                    myName = infoDoc?.getString("name") ?: "Usuario GoMezMart"
                    if (myPhoto.isNullOrBlank()) {
                        myPhoto = infoDoc?.getString("photoUrl")
                    }
                }
                val result = SocialRepository.followStore(
                    db = db,
                    buyerUid = user.uid,
                    buyerName = myName.ifBlank { "Usuario GoMezMart" },
                    buyerPhoto = myPhoto,
                    storeId = storeId,
                    storeName = storeDisplayName,
                    storeLogoUrl = storeLogoUrl,
                    businessType = businessType
                )
                result.onSuccess {
                    isFollowingStore = true
                    updateFollowButtonState()
                    val newCount = (currentFollowersList.count { it.uid != user.uid } + 1)
                    syncStoreFollowersToSettings(newCount)
                    Toast.makeText(this@StoreProfileActivity, "Ahora sigues a $storeDisplayName 🔔", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@StoreProfileActivity, "No se pudo seguir la tienda", Toast.LENGTH_SHORT).show()
                }
            } else {
                val result = SocialRepository.unfollowStore(
                    db = db,
                    buyerUid = user.uid,
                    storeId = storeId
                )
                result.onSuccess {
                    isFollowingStore = false
                    updateFollowButtonState()
                    val newCount = currentFollowersList.count { it.uid != user.uid }
                    syncStoreFollowersToSettings(newCount)
                    Toast.makeText(this@StoreProfileActivity, "Dejaste de seguir a $storeDisplayName", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@StoreProfileActivity, "No se pudo actualizar", Toast.LENGTH_SHORT).show()
                }
            }
            isFollowActionInFlight = false
            storeProfileBinding.btnFollowStore.isEnabled = true
        }
    }

    private fun syncStoreFollowersToSettings(followersCount: Int) {
        if (storeId.isEmpty() || activeSettingsDocId.isEmpty()) return
        cachedFollowersCount = followersCount
        db.collection("users").document(storeId)
            .collection("settings").document(activeSettingsDocId)
            .update("followers_count", followersCount)
            .addOnFailureListener { e ->
                Log.d("StoreProfile", "No se pudo sincronizar seguidores en settings: ${e.message}")
            }
    }

    private fun showFollowersBottomSheetDialog() {
        if (isFinishing || isDestroyed) return
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_store_followers, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvFollowersDialogTitle)
        val tvCount = view.findViewById<TextView>(R.id.tvFollowersDialogCount)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseFollowers)
        val etSearch = view.findViewById<EditText>(R.id.etSearchFollowers)
        val rvFollowers = view.findViewById<RecyclerView>(R.id.rvStoreFollowers)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyFollowers)

        tvTitle.text = "Seguidores de $storeDisplayName 👥"
        val total = currentFollowersList.size
        tvCount.text = if (total == 1) "1 seguidor" else "$total seguidores"

        val adapter = FollowersAdapter()
        rvFollowers.layoutManager = LinearLayoutManager(this)
        rvFollowers.adapter = adapter
        adapter.updateData(currentFollowersList)

        followersSheetAdapter = adapter
        followersSheetCountView = tvCount
        followersSheetEmptyView = tvEmpty

        tvEmpty.visibility = if (currentFollowersList.isEmpty()) View.VISIBLE else View.GONE
        rvFollowers.visibility = if (currentFollowersList.isEmpty()) View.GONE else View.VISIBLE

        etSearch.addTextChangedListener { editable ->
            val q = editable.toString().trim().lowercase(Locale.getDefault())
            val filtered = if (q.isEmpty()) {
                currentFollowersList
            } else {
                currentFollowersList.filter { it.name.lowercase(Locale.getDefault()).contains(q) }
            }
            adapter.updateData(filtered)
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            rvFollowers.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            followersSheetAdapter = null
            followersSheetCountView = null
            followersSheetEmptyView = null
        }
        dialog.show()
    }

    private fun setupStoreUpdatesRecyclerView() {
        storeUpdatesAdapter = StoreUpdatesAdapter { update ->
            if (!update.productId.isNullOrBlank()) {
                val matched = allStoreProducts.firstOrNull { it.id == update.productId }
                if (matched != null) {
                    openProductDetail(matched)
                    return@StoreUpdatesAdapter
                }
            }
            if (update.message.isNotBlank()) {
                AlertDialog.Builder(this)
                    .setTitle(update.title.ifBlank { storeDisplayName })
                    .setMessage(update.message)
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }
        storeProfileBinding.rvStoreUpdates.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        storeProfileBinding.rvStoreUpdates.adapter = storeUpdatesAdapter
    }

    private fun startUpdatesListener(sId: String) {
        if (sId.isEmpty()) return
        updatesRegistration?.remove()
        updatesRegistration = db.collection("users").document(sId).collection("updates")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(10)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val list = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(StoreUpdate::class.java)?.let { u ->
                        if (u.id.isBlank()) u.copy(id = doc.id) else u
                    }
                }
                currentStoreUpdates = list
                storeUpdatesAdapter.updateData(list)

                val showSection = isOwner || list.isNotEmpty()
                storeProfileBinding.layoutStoreUpdatesSection.visibility = if (showSection) View.VISIBLE else View.GONE
                storeProfileBinding.rvStoreUpdates.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                storeProfileBinding.tvNoStoreUpdates.visibility = if (isOwner && list.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun showPublishStoreUpdateDialog() {
        if (isFinishing || isDestroyed || !isOwner) return
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_publish_store_update, null)
        dialog.setContentView(view)

        val cgType = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgUpdateType)
        val etTitle = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUpdateTitle)
        val etMessage = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUpdateMessage)
        val btnConfirm = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPublishStoreUpdateConfirm)

        btnConfirm.setOnClickListener {
            val title = etTitle.text?.toString()?.trim().orEmpty()
            val message = etMessage.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) {
                etTitle.error = "Ingresa un título para la novedad"
                return@setOnClickListener
            }
            val type = if (cgType.checkedChipId == R.id.chipTypePromo) "PROMO" else "ANNOUNCEMENT"
            btnConfirm.isEnabled = false
            btnConfirm.text = "Publicando..."

            lifecycleScope.launch {
                val res = SocialRepository.publishStoreUpdate(
                    db = db,
                    storeId = storeId,
                    storeName = storeDisplayName,
                    storeLogoUrl = storeLogoUrl,
                    storeType = businessType,
                    type = type,
                    title = title,
                    message = message
                )
                res.onSuccess {
                    Toast.makeText(this@StoreProfileActivity, "¡Novedad publicada para tus seguidores! 📢", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }.onFailure {
                    btnConfirm.isEnabled = true
                    btnConfirm.text = "PUBLICAR NOVEDAD"
                    Toast.makeText(this@StoreProfileActivity, "Error al publicar novedad", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        storeMapView?.onResume()
    }

    override fun onPause() {
        hotelStatusPulseAnimator?.cancel()
        loadingLogoAnimator?.cancel()
        clearCarouselHandlers()
        storeMapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hotelStatusPulseAnimator?.cancel()
        clearCarouselHandlers()
        mapInitJob?.cancel()
        storeMapView?.onDetach()
        storeMapView = null
        billingManager?.destroy()
        billingManager = null
        storeRegistration?.remove()
        reviewsRegistration?.remove()
        productsRegistration?.remove()
        followersRegistration?.remove()
        updatesRegistration?.remove()
    }

    inner class RecommendationsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        fun updateData(newList: List<StoreModel>) {
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
        inner class ViewHolder(val b: ItemStoreHorizontalBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemStoreHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                tvStoreNameSmall.text = s.name
                ivStoreVerifiedSmall.visibility = if (s.isVerified) View.VISIBLE else View.GONE
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvStoreDateSmall.text = if (s.creationDate > 0) "Desde: ${sdf.format(Date(s.creationDate))}" else ""
                tvStoreRatingSmall.visibility = View.VISIBLE
                if (s.reviewCount > 0) {
                    tvStoreRatingSmall.text = "★ ${String.format(Locale.US, "%.1f", s.averageRating)} (${s.reviewCount})"
                    tvStoreRatingSmall.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvStoreRatingSmall.setTextColor(Color.parseColor("#B45309"))
                } else {
                    tvStoreRatingSmall.text = "☆ 0.0 (0)"
                    tvStoreRatingSmall.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvStoreRatingSmall.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                
                if (!s.logoUrl.isNullOrEmpty()) {
                    pbStoreLogoSmallLoading.visibility = View.VISIBLE
                    Glide.with(ivStoreLogoSmall.context)
                        .load(s.logoUrl)
                        .apply(GlideHelper.getOptions(ivStoreLogoSmall.context, showPlaceholder = false))
                        .override(120, 120) // Tamaño pequeño para recomendaciones
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                pbStoreLogoSmallLoading.visibility = View.GONE
                                return false
                            }
                            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                pbStoreLogoSmallLoading.visibility = View.GONE
                                return false
                            }
                        })
                        .into(ivStoreLogoSmall)
                } else {
                    pbStoreLogoSmallLoading.visibility = View.GONE
                }
                
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class ReviewsAdapter : RecyclerView.Adapter<ReviewsAdapter.ViewHolder>() {
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvReviewUserName.text = item.userName
                if (item.comment.isBlank()) {
                    tvReviewComment.visibility = View.GONE
                } else {
                    tvReviewComment.visibility = View.VISIBLE
                    tvReviewComment.text = item.comment
                }
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("es-PE"))
                tvReviewDate.text = if (item.timestamp > 0) sdf.format(Date(item.timestamp)) else ""
                rbReviewStars.rating = item.rating.toFloat()
                if (!item.userPhotoUrl.isNullOrEmpty()) {
                    Glide.with(ivReviewUserPhoto)
                        .load(item.userPhotoUrl)
                        .apply(GlideHelper.getOptions(ivReviewUserPhoto.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivReviewUserPhoto)
                } else {
                    ivReviewUserPhoto.setImageResource(R.drawable.gomezmart_icon)
                }
                
                // Solo el autor puede editar su comentario, previniendo crashes en modo invitado
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                if (currentUserId != null && item.userId == currentUserId) {
                    btnEditReview.visibility = View.VISIBLE
                    btnEditReview.setOnClickListener { startEditReview(item) }
                } else {
                    btnEditReview.visibility = View.GONE
                }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class ProductsAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<ProductsAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
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
        inner class ViewHolder(val b: ItemMarketplaceGridProductBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemMarketplaceGridProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvProductName.text = p.name
                tvProductStore.visibility = View.GONE
                ivProductStoreLogo.visibility = View.GONE
                tvProductPrice.text = if (p.storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", p.price)} la noche" else "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                if (p.reviewCount > 0) {
                    tvProductRating.text = "★ ${String.format(Locale.US, "%.1f", p.averageRating)} (${p.reviewCount})"
                    tvProductRating.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvProductRating.setTextColor(Color.parseColor("#B45309"))
                } else {
                    tvProductRating.text = "☆ 0.0 (0)"
                    tvProductRating.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvProductRating.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                
                if (!p.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivProductImage.context)
                        .load(p.photoUrl)
                        .apply(GlideHelper.getOptions(ivProductImage.context, showPlaceholder = false))
                        .thumbnail(0.1f)
                        .into(ivProductImage)
                } else {
                    ivProductImage.setImageResource(R.drawable.gomezmart_icon)
                }
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class FollowersAdapter : RecyclerView.Adapter<FollowersAdapter.ViewHolder>() {
        private var items = listOf<StoreFollower>()
        fun updateData(newList: List<StoreFollower>) {
            if (items == newList) return
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos].uid == newList[newPos].uid
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newList[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newList
            diffResult.dispatchUpdatesTo(this)
        }
        inner class ViewHolder(val b: ItemStoreFollowerBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemStoreFollowerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val myUid = auth.currentUser?.uid
            with(holder.b) {
                tvFollowerName.text = item.name.ifBlank { "Usuario GoMezMart" }
                tvFollowerSelfBadge.visibility = if (myUid != null && item.uid == myUid) View.VISIBLE else View.GONE
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("es-PE"))
                tvFollowerSince.text = if (item.followedAt > 0) "Sigue desde ${sdf.format(Date(item.followedAt))}" else "Sigue esta tienda"
                if (!item.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivFollowerPhoto)
                        .load(item.photoUrl)
                        .apply(GlideHelper.getOptions(ivFollowerPhoto.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivFollowerPhoto)
                } else {
                    ivFollowerPhoto.setImageResource(R.drawable.gomezmart_icon)
                }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class StoreUpdatesAdapter(private val onClick: (StoreUpdate) -> Unit) : RecyclerView.Adapter<StoreUpdatesAdapter.ViewHolder>() {
        private var items = listOf<StoreUpdate>()
        fun updateData(newList: List<StoreUpdate>) {
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
        inner class ViewHolder(val b: ItemStoreUpdateBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemStoreUpdateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvUpdateStoreName.text = item.storeName.ifBlank { storeDisplayName }
                val sdf = SimpleDateFormat("dd MMM • HH:mm", Locale.forLanguageTag("es-PE"))
                tvUpdateTime.text = if (item.createdAt > 0) sdf.format(Date(item.createdAt)) else "Reciente"
                tvUpdateTitle.text = item.title
                tvUpdateMessage.text = item.message
                tvUpdateMessage.visibility = if (item.message.isBlank()) View.GONE else View.VISIBLE

                when (item.type) {
                    "NEW_PRODUCT" -> {
                        tvUpdateTypeBadge.text = "🆕 Producto"
                        tvUpdateTypeBadge.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                        tvUpdateTypeBadge.setTextColor(ContextCompat.getColor(root.context, R.color.emerald_700))
                    }
                    "PROMO" -> {
                        tvUpdateTypeBadge.text = "🏷️ Oferta"
                        tvUpdateTypeBadge.setBackgroundResource(R.drawable.bg_pill_amber_light)
                        tvUpdateTypeBadge.setTextColor(Color.parseColor("#B45309"))
                    }
                    else -> {
                        tvUpdateTypeBadge.text = "📢 Anuncio"
                        tvUpdateTypeBadge.setBackgroundResource(R.drawable.bg_pill_purple_light)
                        tvUpdateTypeBadge.setTextColor(ContextCompat.getColor(root.context, R.color.purple_700))
                    }
                }

                val logo = item.storeLogoUrl ?: storeLogoUrl
                if (!logo.isNullOrBlank()) {
                    Glide.with(ivUpdateStoreLogo)
                        .load(logo)
                        .apply(GlideHelper.getOptions(ivUpdateStoreLogo.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivUpdateStoreLogo)
                } else {
                    ivUpdateStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                }

                if (!item.imageUrl.isNullOrBlank()) {
                    cardUpdateThumb.visibility = View.VISIBLE
                    Glide.with(ivUpdateThumb)
                        .load(item.imageUrl)
                        .apply(GlideHelper.getOptions(ivUpdateThumb.context, showPlaceholder = false))
                        .centerCrop()
                        .into(ivUpdateThumb)
                } else {
                    cardUpdateThumb.visibility = View.GONE
                }

                root.setOnClickListener { onClick(item) }
            }
        }
        override fun getItemCount() = items.size
    }
}
