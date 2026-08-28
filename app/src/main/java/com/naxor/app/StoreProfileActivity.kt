package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.naxor.app.data.StoreReview
import com.naxor.app.databinding.ActivityStoreProfileBinding
import com.naxor.app.databinding.ItemStoreReviewBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    private var storeId: String = ""

    private var currentLat = 0.0
    private var currentLng = 0.0

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
        startStoreListener(storeId, forcedRubro)
        startReviewsListener(storeId)

        storeProfileBinding.btnViewFullCatalog.setOnClickListener {
            val intent = Intent(this, StoreCatalogActivity::class.java).apply {
                putExtra("STORE_ID", storeId)
                putExtra("STORE_NAME", storeName)
                putExtra("FORCE_RUBRO", forcedRubro)
            }
            startActivity(intent)
        }

        storeProfileBinding.btnSubmitReview.setOnClickListener { submitReview() }
        
        storeProfileBinding.tvProfileStoreAddress.setOnClickListener { openGoogleMaps() }
    }

    private fun openGoogleMaps() {
        if (currentLat != 0.0 && currentLng != 0.0) {
            val uri = "google.navigation:q=$currentLat,$currentLng"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.setPackage("com.google.android.apps.maps")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Si no tiene Maps, abrir en navegador
                val webUri = "https://www.google.com/maps/dir/?api=1&destination=$currentLat,$currentLng"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
            }
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
    }

    private fun renderExtraSections(sections: List<Map<String, Any>>) {
        storeProfileBinding.layoutExtraSectionsProfile.removeAllViews()
        sections.forEach { m ->
            val title = m["title"] as? String ?: ""
            val desc = m["description"] as? String ?: ""
            val urls = m["imageUrls"] as? List<String> ?: emptyList()

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
            Glide.with(holder.itemView).load(urls[position]).into(holder.iv)
        }
        override fun getItemCount() = urls.size
    }

    private fun setupReviewsRecyclerView() {
        reviewsAdapter = ReviewsAdapter()
        storeProfileBinding.rvStoreReviews.layoutManager = LinearLayoutManager(this)
        storeProfileBinding.rvStoreReviews.adapter = reviewsAdapter
    }

    private fun startStoreListener(sId: String, forcedRubro: String?) {
        if (sId.isEmpty()) return
        val docId = forcedRubro ?: "business"

        storeRegistration = db.collection("users").document(sId)
            .collection("settings").document(docId)
            .addSnapshotListener { doc, e ->
                if (e != null || doc == null || !doc.exists()) {
                    if (forcedRubro != null && docId != "business") startStoreListener(sId, null)
                    return@addSnapshotListener
                }
                
                val name = doc.getString("business_name") ?: "Tienda GoMezMart"
                val desc = doc.getString("business_description") ?: ""
                val addr = doc.getString("business_address") ?: ""
                val logo = doc.getString("business_logo_url")
                val cover = doc.getString("business_cover_url")
                val owner = doc.getString("business_owner_name") ?: "Propietario GoMezMart"
                val bType = doc.getString("business_type") ?: forcedRubro ?: "PRODUCTS"
                val rubro = doc.getString("business_category") ?: "Tienda"

                storeProfileBinding.tvProfileStoreName.text = name
                storeProfileBinding.tvProfileStoreDesc.text = desc
                storeProfileBinding.tvProfileStoreAddress.text = "📍 $addr"
                storeProfileBinding.tvProfileOwnerName.text = owner
                storeProfileBinding.tvProfileStoreCategory.text = rubro
                
                if (bType == "HOTEL") {
                    storeProfileBinding.cardHotelAvailability.visibility = View.VISIBLE
                    loadHotelAvailability(sId)
                } else {
                    storeProfileBinding.cardHotelAvailability.visibility = View.GONE
                }

                if (!logo.isNullOrEmpty()) Glide.with(this).load(logo).circleCrop().into(storeProfileBinding.ivProfileStoreLogo)
                if (!cover.isNullOrEmpty()) Glide.with(this).load(cover).centerCrop().into(storeProfileBinding.ivStoreCover)

                val lat = doc.getDouble("latitude") ?: 0.0
                val lng = doc.getDouble("longitude") ?: 0.0
                updateMapLocation(lat, lng, name)

                val extras = doc.get("extraSections") as? List<Map<String, Any>>
                if (extras != null) renderExtraSections(extras)
            }
    }

    private fun startReviewsListener(sId: String) {
        reviewsRegistration = db.collection("users").document(sId).collection("reviews")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val reviews = snapshot.documents.mapNotNull { it.toObject(StoreReview::class.java) }.distinctBy { it.userId }
                reviewsAdapter.updateData(reviews)
                storeProfileBinding.tvNoReviews.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun submitReview() { /* Lógica de review... */ }
    private fun startEditReview(review: StoreReview) { /* Lógica de edición... */ }
    private fun loadHotelAvailability(sId: String) { /* Lógica de hotel... */ }

    override fun onDestroy() {
        super.onDestroy()
        storeRegistration?.remove()
        reviewsRegistration?.remove()
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
            }
        }
        override fun getItemCount() = items.size
    }
}
