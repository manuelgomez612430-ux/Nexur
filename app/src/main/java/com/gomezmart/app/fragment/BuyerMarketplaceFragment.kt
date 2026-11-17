package com.gomezmart.app.fragment

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Geocoder
import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.firebase.auth.FirebaseAuth
import com.gomezmart.app.*
import com.gomezmart.app.data.MarketplaceProduct
import com.gomezmart.app.data.SocialRepository
import com.gomezmart.app.data.StoreModel
import com.gomezmart.app.data.StoreUpdate
import com.gomezmart.app.databinding.*
import com.gomezmart.app.util.GlideHelper
import com.google.android.gms.location.CurrentLocationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class BuyerMarketplaceFragment : Fragment() {

    private var _binding: FragmentBuyerMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val db = FirebaseFirestore.getInstance()
    
    private lateinit var marketplaceAdapter: MarketplaceAdapter
    private lateinit var storesAdapter: StoreAdapter
    private lateinit var recentlyViewedAdapter: RecentlyViewedAdapter
    private lateinit var recentShopsAdapter: RecentShopsAdapter
    private lateinit var followedUpdatesAdapter: FollowedUpdatesAdapter
    private lateinit var searchSuggestionsAdapter: SearchSuggestionsAdapter
    
    companion object {
        @Volatile
        var needsMarketplaceRefresh: Boolean = false
    }

    private var allMarketplaceProducts = mutableListOf<MarketplaceProduct>()
    private var allStores = mutableListOf<StoreModel>()
    private var publishedStoreKeys = setOf<Pair<String, String>>()
    private var unpublishedStoreKeys = setOf<Pair<String, String>>()
    private var hasLoadedStoresFromServer = false
    private var storesSnapWasTruncated = false
    private var followedStoreIds = emptySet<String>()
    private var followedStoreUpdates = listOf<StoreUpdate>()
    private var currentMode = "ALL"
    
    private var buyerCity = ""
    private var buyerPostalCode = ""
    private var buyerCountry = ""
    
    private val locationPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            detectUserLocation()
        } else {
            buyerCountry = "Perú"
            buyerCity = ""
            buyerPostalCode = ""
            val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("buyer_country", buyerCountry)
                .putString("buyer_city", "")
                .putString("buyer_postal_code", "")
                .apply()
            updateLocationUI()
            if (allStores.isEmpty()) {
                loadData(isPullToRefresh = false)
            }
        }
    }
    
    private var isLoadingData = false
    private var lastLoadTime: Long = 0

    // Paginación y búsqueda
    private val PAGE_SIZE = 15
    private var visibleProductLimit = 15
    private var visibleStoreLimit = 15
    private var searchQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerMarketplaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Manejar insets para la cabecera (Edge-to-Edge)
        (activity as? BaseActivity)?.applyWindowInsets(binding.appBarMarketplace, applyTop = true, applyBottom = false)

        loadSavedLocation()
        setupRecyclerViews()
        setupCategoryTabs()
        setupListeners()
        
        // Solicitar permiso de ubicación explícito al entrar al Mercado
        checkAndPromptLocationPermission()

        // Cargar datos solo si es la primera vez
        if (allStores.isEmpty()) {
            loadData()
        } else {
            loadFollowedStoresUpdates()
        }
    }

    private fun checkAndPromptLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            detectUserLocation()
            return
        }

        val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
        val hasAskedPrompt = prefs.getBoolean("has_asked_location_prompt", false)

        if (!hasAskedPrompt) {
            prefs.edit().putBoolean("has_asked_location_prompt", true).apply()
            showLocationExplanatoryDialog()
        }
    }

    private fun showLocationExplanatoryDialog() {
        if (!isAdded || activity == null) return

        AlertDialog.Builder(requireContext())
            .setTitle("Ubicación para Mercado Local 📍")
            .setMessage("Usamos tu ubicación en este momento para mostrarte las tiendas, productos y alojamientos cercanos a tu zona.\n\n¿Deseas permitir el acceso a tu ubicación?")
            .setPositiveButton("CONCEDER PERMISO") { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("CERRAR") { _, _ ->
                buyerCountry = "Perú"
                buyerCity = ""
                buyerPostalCode = ""
                val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("buyer_country", buyerCountry)
                    .putString("buyer_city", "")
                    .putString("buyer_postal_code", "")
                    .apply()
                updateLocationUI()
                if (allStores.isEmpty()) {
                    loadData(isPullToRefresh = false)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun isEmulatorLocation(city: String, country: String): Boolean {
        val c = country.lowercase(Locale.ROOT)
        val ct = city.lowercase(Locale.ROOT)
        return c.contains("united states") || c.contains("ee. uu") || c == "us" || ct.contains("mountain view")
    }

    private fun loadSavedLocation() {
        val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
        buyerCity = prefs.getString("buyer_city", "") ?: ""
        buyerPostalCode = prefs.getString("buyer_postal_code", "") ?: ""
        buyerCountry = prefs.getString("buyer_country", "") ?: ""

        if (buyerCountry.isEmpty() || isEmulatorLocation(buyerCity, buyerCountry)) {
            buyerCountry = "Perú"
            buyerCity = ""
            buyerPostalCode = ""
        }
        updateLocationUI()
    }

    private fun updateLocationUI() {
        if (_binding == null) return
        val locationText = when {
            buyerCity.isNotEmpty() && buyerPostalCode.isNotEmpty() && buyerCountry.isNotEmpty() -> "$buyerCity, $buyerPostalCode, $buyerCountry"
            buyerCity.isNotEmpty() && buyerCountry.isNotEmpty() -> "$buyerCity, $buyerCountry"
            buyerCountry.isNotEmpty() -> buyerCountry
            buyerCity.isNotEmpty() -> buyerCity
            else -> "Perú"
        }
        binding.tvCurrentLocation.text = locationText
    }

    private fun detectUserLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedClient.getCurrentLocation(request, null).addOnSuccessListener { loc ->
            if (loc != null) {
                processNewLocation(loc.latitude, loc.longitude)
            } else {
                fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) processNewLocation(lastLoc.latitude, lastLoc.longitude)
                    else Toast.makeText(requireContext(), "No se pudo obtener ubicación GPS 📍", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun processNewLocation(lat: Double, lng: Double) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                var city = ""
                var zip = ""
                var country = Locale.getDefault().displayCountry ?: "Perú"

                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    city = addr.locality ?: addr.subAdminArea ?: ""
                    zip = addr.postalCode ?: ""
                    if (!addr.countryName.isNullOrEmpty()) {
                        country = addr.countryName
                    }
                }

                // Si es la ubicación predeterminada del emulador (Mountain View, EE. UU.), establecer Perú por defecto
                if (isEmulatorLocation(city, country)) {
                    country = "Perú"
                    city = ""
                    zip = ""
                } else if (country.isEmpty()) {
                    country = "Perú"
                }
                
                withContext(Dispatchers.Main) {
                    val locationChanged = city != buyerCity || zip != buyerPostalCode || country != buyerCountry
                    buyerCity = city
                    buyerPostalCode = zip
                    buyerCountry = country
                    
                    val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("buyer_city", buyerCity)
                        .putString("buyer_postal_code", buyerPostalCode)
                        .putString("buyer_country", buyerCountry)
                        .apply()
                    
                    updateLocationUI()
                    if (locationChanged || allStores.isEmpty()) {
                        loadData(isPullToRefresh = false)
                    }
                    val displayText = if (buyerCity.isNotEmpty()) "$buyerCity, $buyerCountry" else buyerCountry
                    Toast.makeText(requireContext(), "Ubicación: $displayText 📍", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (buyerCountry.isEmpty() || isEmulatorLocation(buyerCity, buyerCountry)) {
                        buyerCountry = "Perú"
                        buyerCity = ""
                        val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("buyer_country", buyerCountry).apply()
                        updateLocationUI()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            detectUserLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        
        val oldCity = buyerCity
        val oldZip = buyerPostalCode
        val oldCountry = buyerCountry
        loadSavedLocation()
        
        if (needsMarketplaceRefresh || buyerCity != oldCity || buyerPostalCode != oldZip || buyerCountry != oldCountry || allStores.isEmpty()) {
            val isRefreshOnly = needsMarketplaceRefresh && allStores.isNotEmpty()
            needsMarketplaceRefresh = false
            loadData(isPullToRefresh = isRefreshOnly)
        } else {
            loadRecentlyViewed()
            loadFollowedStoresUpdates()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupCategoryTabs() {
        binding.hsvCategoryTabs.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        val tabs = listOf(
            binding.tabAll to "ALL",
            binding.tabFollowing to "FOLLOWING",
            binding.tabHotel to "HOTEL",
            binding.tabProducts to "PRODUCTS",
            binding.tabLoans to "LOANS"
        )
        tabs.forEach { (view, mode) ->
            view.setOnClickListener {
                if (currentMode == mode) return@setOnClickListener
                currentMode = mode
                updateTabStyles()
                filterDataByMode()
            }
        }
    }

    private fun updateTabStyles() {
        val tabs = listOf(
            binding.tabAll to "ALL",
            binding.tabFollowing to "FOLLOWING",
            binding.tabHotel to "HOTEL",
            binding.tabProducts to "PRODUCTS",
            binding.tabLoans to "LOANS"
        )
        tabs.forEach { (view, mode) ->
            if (mode == currentMode) {
                view.setTypeface(null, Typeface.BOLD)
                view.alpha = 1.0f
            } else {
                view.setTypeface(null, Typeface.NORMAL)
                view.alpha = 0.7f
            }
        }
    }

    private fun filterDataByMode() {
        lifecycleScope.launch {
            filterDataByModeAsync()
        }
    }

    private suspend fun filterDataByModeAsync() {
        if (_binding == null) return
        
        val q = searchQuery.lowercase(Locale.getDefault()).trim()
        val currentProductsCopy = ArrayList(allMarketplaceProducts)
        val currentStoresCopy = ArrayList(allStores)
        val followedIdsCopy = followedStoreIds
        val mode = currentMode

        val (useProductGrid, filteredProducts, filteredStores) = withContext(Dispatchers.Default) {
            if (mode == "FOLLOWING") {
                val followedProducts = currentProductsCopy.filter { it.storeId in followedIdsCopy }
                val followedStores = currentStoresCopy.filter { it.id in followedIdsCopy }
                if (followedProducts.isNotEmpty()) {
                    val filtered = if (q.isEmpty()) followedProducts else followedProducts.filter {
                        it.name.lowercase(Locale.getDefault()).contains(q) ||
                        it.storeName.lowercase(Locale.getDefault()).contains(q) ||
                        it.category.lowercase(Locale.getDefault()).contains(q) ||
                        it.description.lowercase(Locale.getDefault()).contains(q)
                    }
                    Triple(true, filtered, emptyList<StoreModel>())
                } else {
                    val filtered = if (q.isEmpty()) followedStores else followedStores.filter {
                        it.name.lowercase(Locale.getDefault()).contains(q) ||
                        it.address.lowercase(Locale.getDefault()).contains(q) ||
                        it.description.lowercase(Locale.getDefault()).contains(q)
                    }
                    Triple(false, emptyList<MarketplaceProduct>(), filtered)
                }
            } else {
                val modeProducts = if (mode == "ALL") {
                    currentProductsCopy
                } else {
                    currentProductsCopy.filter { it.storeType == mode }
                }

                val useGrid = mode == "ALL" || mode == "HOTEL" || mode == "PRODUCTS" || modeProducts.isNotEmpty()

                if (useGrid) {
                    val filtered = if (q.isEmpty()) {
                        modeProducts
                    } else {
                        modeProducts.filter {
                            it.name.lowercase(Locale.getDefault()).contains(q) ||
                            it.storeName.lowercase(Locale.getDefault()).contains(q) ||
                            it.category.lowercase(Locale.getDefault()).contains(q) ||
                            it.description.lowercase(Locale.getDefault()).contains(q)
                        }
                    }
                    Triple(true, filtered, emptyList<StoreModel>())
                } else {
                    val modeStores = currentStoresCopy.filter { it.businessType == mode }
                    val filtered = if (q.isEmpty()) {
                        modeStores
                    } else {
                        modeStores.filter {
                            it.name.lowercase(Locale.getDefault()).contains(q) ||
                            it.address.lowercase(Locale.getDefault()).contains(q) ||
                            it.description.lowercase(Locale.getDefault()).contains(q)
                        }
                    }
                    Triple(false, emptyList<MarketplaceProduct>(), filtered)
                }
            }
        }

        if (_binding == null) return

        val showFollowedUpdates = q.isEmpty() && (currentMode == "ALL" || currentMode == "FOLLOWING") && followedStoreUpdates.isNotEmpty()
        binding.layoutFollowedUpdatesHeader.visibility = if (showFollowedUpdates) View.VISIBLE else View.GONE
        binding.rvFollowedUpdates.visibility = if (showFollowedUpdates) View.VISIBLE else View.GONE

        if (useProductGrid) {
            binding.layoutRecentShopsHeader.visibility = if (q.isEmpty() && currentMode == "ALL") View.VISIBLE else View.GONE
            binding.rvRecentShops.visibility = if (q.isEmpty() && currentMode == "ALL") View.VISIBLE else View.GONE
            binding.layoutRecentlyViewedHeader.visibility = if (q.isEmpty() && currentMode == "ALL" && recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.rvRecentlyViewed.visibility = if (q.isEmpty() && currentMode == "ALL" && recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.tvMainFeedTitle.visibility = View.GONE
            
            if (binding.rvMarketplaceGrid.adapter != marketplaceAdapter) {
                binding.rvMarketplaceGrid.adapter = marketplaceAdapter
                binding.rvMarketplaceGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            }
            
            if (filteredProducts.isEmpty()) {
                binding.layoutEmptyMarketplace.visibility = View.VISIBLE
                val emptyTitle = when (currentMode) {
                    "FOLLOWING" -> "Aún no sigues ninguna tienda ⭐"
                    "HOTEL" -> "No hay habitaciones disponibles aún"
                    "PRODUCTS" -> "No hay productos disponibles aún"
                    "LOANS" -> "No hay servicios disponibles aún"
                    else -> "No hay productos o negocios disponibles aún"
                }
                val emptySub = when (currentMode) {
                    "FOLLOWING" -> "Entra al perfil de una tienda y toca '+ Seguir tienda' para ver sus novedades y productos aquí."
                    "HOTEL" -> "Pronto se agregarán habitaciones en esta zona."
                    "PRODUCTS" -> "Pronto se agregarán productos en esta zona."
                    "LOANS" -> "Pronto se agregarán servicios de préstamos en esta zona."
                    else -> "Pronto se agregarán productos en tu zona."
                }
                binding.tvEmptyMarketplaceTitle.text = emptyTitle
                binding.tvEmptyMarketplaceSub.text = emptySub
                binding.rvMarketplaceGrid.visibility = View.GONE
            } else {
                binding.layoutEmptyMarketplace.visibility = View.GONE
                binding.rvMarketplaceGrid.visibility = View.VISIBLE
            }
            
            val displayedProducts = filteredProducts.take(visibleProductLimit)
            marketplaceAdapter.updateData(displayedProducts)
            
            updatePaginationUI(filteredProducts.size, visibleProductLimit)
        } else {
            binding.layoutRecentShopsHeader.visibility = View.GONE
            binding.rvRecentShops.visibility = View.GONE
            binding.layoutRecentlyViewedHeader.visibility = View.GONE
            binding.rvRecentlyViewed.visibility = View.GONE
            binding.tvMainFeedTitle.visibility = View.GONE
            
            if (binding.rvMarketplaceGrid.adapter != storesAdapter) {
                binding.rvMarketplaceGrid.adapter = storesAdapter
                binding.rvMarketplaceGrid.layoutManager = LinearLayoutManager(requireContext())
            }
            
            if (filteredStores.isEmpty()) {
                binding.layoutEmptyMarketplace.visibility = View.VISIBLE
                if (currentMode == "FOLLOWING") {
                    binding.tvEmptyMarketplaceTitle.text = "Aún no sigues ninguna tienda ⭐"
                    binding.tvEmptyMarketplaceSub.text = "Entra al perfil de una tienda y toca '+ Seguir tienda' para ver sus novedades y productos aquí."
                } else {
                    binding.tvEmptyMarketplaceTitle.text = "No hay negocios disponibles aún"
                    binding.tvEmptyMarketplaceSub.text = "Pronto se agregarán negocios de esta categoría en tu zona."
                }
                binding.rvMarketplaceGrid.visibility = View.GONE
            } else {
                binding.layoutEmptyMarketplace.visibility = View.GONE
                binding.rvMarketplaceGrid.visibility = View.VISIBLE
            }
            
            val displayedStores = filteredStores.take(visibleStoreLimit)
            storesAdapter.setStores(displayedStores)
            
            updatePaginationUI(filteredStores.size, visibleStoreLimit)
        }
    }

    private fun updatePaginationUI(totalItems: Int, currentLimit: Int) {
        if (_binding == null) return
        if (totalItems > currentLimit) {
            binding.btnLoadMoreMarketplace.visibility = View.VISIBLE
            binding.tvNoMoreProductsMarketplace.visibility = View.GONE
        } else if (totalItems > 0) {
            binding.btnLoadMoreMarketplace.visibility = View.GONE
            binding.tvNoMoreProductsMarketplace.visibility = View.VISIBLE
        } else {
            binding.btnLoadMoreMarketplace.visibility = View.GONE
            binding.tvNoMoreProductsMarketplace.visibility = View.GONE
        }
    }

    private fun setupRecyclerViews() {
        searchSuggestionsAdapter = SearchSuggestionsAdapter { product ->
            openProductDetail(product)
            clearSearchAndHideKeyboard()
        }
        binding.rvSearchResultsList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResultsList.adapter = searchSuggestionsAdapter

        marketplaceAdapter = MarketplaceAdapter { openProductDetail(it) }
        storesAdapter = StoreAdapter { 
            val targetClass = if (it.businessType == "LOANS") LoanStoreProfileActivity::class.java else StoreProfileActivity::class.java
            val intent = Intent(requireContext(), targetClass).apply {
                putExtra("STORE_ID", it.id)
                putExtra("STORE_NAME", it.name)
                putExtra("FORCE_RUBRO", it.businessType)
            }
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }
        recentShopsAdapter = RecentShopsAdapter { 
            val targetClass = if (it.businessType == "LOANS") LoanStoreProfileActivity::class.java else StoreProfileActivity::class.java
            val intent = Intent(requireContext(), targetClass).apply {
                putExtra("STORE_ID", it.id)
                putExtra("STORE_NAME", it.name)
                putExtra("FORCE_RUBRO", it.businessType)
            }
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }
        binding.rvRecentShops.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentShops.adapter = recentShopsAdapter

        followedUpdatesAdapter = FollowedUpdatesAdapter { update ->
            if (!update.productId.isNullOrBlank()) {
                val matched = allMarketplaceProducts.firstOrNull { it.id == update.productId && it.storeId == update.storeId }
                if (matched != null) {
                    openProductDetail(matched)
                    return@FollowedUpdatesAdapter
                }
            }
            val targetClass = if (update.storeType == "LOANS") LoanStoreProfileActivity::class.java else StoreProfileActivity::class.java
            val intent = Intent(requireContext(), targetClass).apply {
                putExtra("STORE_ID", update.storeId)
                putExtra("STORE_NAME", update.storeName)
                putExtra("FORCE_RUBRO", update.storeType)
            }
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }
        binding.rvFollowedUpdates.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvFollowedUpdates.adapter = followedUpdatesAdapter
        
        val itemTouchInterceptor = object : RecyclerView.OnItemTouchListener {
            private var startX = 0f
            private var startY = 0f
            private val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
            private var parentViewPager: ViewPager2? = null

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (parentViewPager == null) {
                    parentViewPager = activity?.findViewById(R.id.buyerViewPager)
                }

                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        // Bloquear temporalmente la navegación por pestañas de ViewPager2 para evitar cambiar al Chat accidentalmente
                        parentViewPager?.isUserInputEnabled = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(e.x - startX)
                        val dy = abs(e.y - startY)
                        if (dx > touchSlop || dy > touchSlop) {
                            if (dy > dx) {
                                // Desplazamiento predominantemente vertical -> permitir a NestedScrollView desplazarse
                                rv.parent.requestDisallowInterceptTouchEvent(false)
                            } else {
                                // Desplazamiento predominantemente horizontal -> mantener evento para la lista horizontal
                                rv.parent.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Restaurar la navegación del ViewPager2
                        parentViewPager?.isUserInputEnabled = true
                        rv.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        }
        
        binding.rvRecentShops.addOnItemTouchListener(itemTouchInterceptor)
        binding.rvFollowedUpdates.addOnItemTouchListener(itemTouchInterceptor)

        recentlyViewedAdapter = RecentlyViewedAdapter { openProductDetail(it) }
        binding.rvRecentlyViewed.apply {
            setHasFixedSize(false)
            setItemViewCacheSize(10)
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyViewedAdapter
            addOnItemTouchListener(itemTouchInterceptor)
        }

        binding.rvRecentShops.apply {
            setHasFixedSize(false)
            setItemViewCacheSize(10)
        }

        binding.rvMarketplaceGrid.apply {
            setHasFixedSize(false)
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(0, 30)
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = marketplaceAdapter
        }
    }

    private fun openProductDetail(p: MarketplaceProduct) {
        saveToRecentlyViewed(p)
        val intent = Intent(requireContext(), ProductDetailActivity::class.java).apply {
            putExtra("PROD_ID", p.id)
            putExtra("PROD_NAME", p.name)
            putExtra("PROD_DESC", p.description)
            putExtra("PROD_PRICE", p.price)
            putExtra("PROD_CAT", p.category)
            putExtra("PROD_PHOTO", p.photoUrl)
            putExtra("STORE_ID", p.storeId)
            putExtra("STORE_NAME", p.storeName)
            putExtra("STORE_ADDR", p.storeAddress)
            putExtra("STORE_LOGO", p.storeLogoUrl)
            putExtra("STORE_TYPE", p.storeType)
        }
        (activity as? BaseActivity)?.startActivityWithFade(intent)
    }

    private fun saveToRecentlyViewed(product: MarketplaceProduct) {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = appContext.getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                val json = prefs.getString("recently_viewed", null)
                val type = object : TypeToken<MutableList<MarketplaceProduct>>() {}.type
                val gson = Gson()
                val list: MutableList<MarketplaceProduct> = if (json != null) gson.fromJson(json, type) ?: mutableListOf() else mutableListOf()
                list.removeAll { it.id == product.id }
                list.add(0, product)
                if (list.size > 10) list.removeAt(list.size - 1)
                prefs.edit().putString("recently_viewed", gson.toJson(list)).apply()
            } catch (e: Exception) {}
        }
    }

    private fun loadRecentlyViewed() {
        val appContext = context?.applicationContext ?: return
        val productsSnapshot = ArrayList(allMarketplaceProducts)
        val publishedKeysSnapshot = publishedStoreKeys
        val unpublishedKeysSnapshot = unpublishedStoreKeys
        val serverLoaded = hasLoadedStoresFromServer
        val truncated = storesSnapWasTruncated
        viewLifecycleOwner.lifecycleScope.launch {
            val enrichedList = withContext(Dispatchers.IO) {
                try {
                    val prefs = appContext.getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                    val json = prefs.getString("recently_viewed", null)
                    val type = object : TypeToken<List<MarketplaceProduct>>() {}.type
                    val list: List<MarketplaceProduct> = if (json != null) Gson().fromJson(json, type) ?: emptyList() else emptyList()
                    list.filter { recent ->
                        if (recent.storeId.isBlank() || recent.storeId == "local") return@filter false
                        val key = recent.storeId to recent.storeType.uppercase(Locale.ROOT)
                        if (key in unpublishedKeysSnapshot) return@filter false
                        if (serverLoaded && !truncated && key !in publishedKeysSnapshot) return@filter false
                        true
                    }.map { recent ->
                        val live = productsSnapshot.firstOrNull { it.id == recent.id && (recent.storeId.isBlank() || it.storeId == recent.storeId) }
                        if (live != null) {
                            recent.copy(averageRating = live.averageRating, reviewCount = live.reviewCount)
                        } else {
                            recent
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (!isAdded || _binding == null) return@launch

            if (enrichedList.isEmpty()) {
                binding.layoutRecentlyViewedHeader.visibility = View.GONE
                binding.rvRecentlyViewed.visibility = View.GONE
                recentlyViewedAdapter.updateData(emptyList())
            } else {
                if (currentMode == "ALL" && searchQuery.isEmpty()) {
                    binding.layoutRecentlyViewedHeader.visibility = View.VISIBLE
                    binding.rvRecentlyViewed.visibility = View.VISIBLE
                }
                recentlyViewedAdapter.updateData(enrichedList.take(4))
            }
        }
    }

    private var lastTopSwipeTime = 0L

    private fun setupListeners() {
        // Mecánica de doble desliz consecutivo en el tope para evitar refrescos accidentales
        binding.swipeRefreshMarketplace.setDistanceToTriggerSync(350)
        binding.swipeRefreshMarketplace.isEnabled = false

        binding.nsvMarketplaceMain.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 0) {
                binding.swipeRefreshMarketplace.isEnabled = false
            }
        }

        binding.nsvMarketplaceMain.setOnTouchListener { _, event ->
            if (binding.nsvMarketplaceMain.scrollY == 0) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = System.currentTimeMillis()
                    if (now - lastTopSwipeTime < 2500) {
                        binding.swipeRefreshMarketplace.isEnabled = true
                    } else {
                        lastTopSwipeTime = now
                        binding.swipeRefreshMarketplace.isEnabled = false
                    }
                }
            } else {
                binding.swipeRefreshMarketplace.isEnabled = false
            }
            false
        }

        binding.swipeRefreshMarketplace.setOnRefreshListener {
            visibleProductLimit = PAGE_SIZE
            visibleStoreLimit = PAGE_SIZE
            loadData(isPullToRefresh = true)
        }
        binding.btnOpenMenu.setOnClickListener { (activity as? BuyerHomeActivity)?.openDrawer() }
        binding.tvViewHistory.setOnClickListener {
            startActivity(Intent(requireContext(), ViewedHistoryActivity::class.java))
        }
        binding.btnLocationSelector.setOnClickListener {
            startActivity(Intent(requireContext(), ManageLocationsActivity::class.java))
        }
        binding.btnFavorites.setOnClickListener {
            startActivity(Intent(requireContext(), BuyerFavoritesActivity::class.java))
        }
        binding.etSearchMarketplace.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expandSearchBar(true)
                binding.btnSearchClearMarketplace.visibility = View.VISIBLE
                if (searchQuery.isNotEmpty()) {
                    binding.layoutSearchSuggestions.visibility = View.VISIBLE
                    hideMainFeedViews()
                }
            } else {
                if (searchQuery.isEmpty()) {
                    expandSearchBar(false)
                }
                binding.layoutSearchSuggestions.visibility = View.GONE
                restoreMainFeedViews()
            }
        }

        binding.etSearchMarketplace.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                performSearchSubmit()
                true
            } else false
        }

        binding.btnSearchClearMarketplace.setOnClickListener {
            clearSearchAndHideKeyboard()
        }

        binding.etSearchMarketplace.addTextChangedListener { text ->
            onSearchInputChanged(text.toString())
        }

        binding.btnLoadMoreMarketplace.setOnClickListener {
            if (currentMode == "ALL") {
                visibleProductLimit += PAGE_SIZE
            } else {
                visibleStoreLimit += PAGE_SIZE
            }
            filterDataByMode()
        }
    }

    private var searchJob: Job? = null

    private fun onSearchInputChanged(text: String) {
        searchQuery = text.trim()
        
        // La "X" permanece visible mientras la barra esté expandida en modo búsqueda
        if (isSearchBarExpanded) {
            binding.btnSearchClearMarketplace.visibility = View.VISIBLE
        }

        if (searchQuery.isNotEmpty()) {
            expandSearchBar(true)
            binding.btnSearchClearMarketplace.visibility = View.VISIBLE
            binding.layoutSearchSuggestions.visibility = View.VISIBLE
            hideMainFeedViews()
            
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(100)
                val q = searchQuery.lowercase(Locale.getDefault())
                val productsCopy = ArrayList(allMarketplaceProducts)
                val filtered = withContext(Dispatchers.Default) {
                    productsCopy.filter { p ->
                        p.name.lowercase(Locale.getDefault()).contains(q) ||
                        p.storeName.lowercase(Locale.getDefault()).contains(q) ||
                        p.category.lowercase(Locale.getDefault()).contains(q)
                    }
                }
                if (!isActive || _binding == null) return@launch
                searchSuggestionsAdapter.updateData(filtered)
                binding.tvNoSearchResults.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                binding.rvSearchResultsList.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
            }
        } else {
            searchJob?.cancel()
            binding.layoutSearchSuggestions.visibility = View.GONE
            restoreMainFeedViews()
            
            // Filtrado asincrónico fluido al borrar la última letra
            searchJob = lifecycleScope.launch {
                filterDataByModeAsync()
            }
        }
    }

    private var isSearchBarExpanded = false

    private fun expandSearchBar(expand: Boolean) {
        if (_binding == null || isSearchBarExpanded == expand) return
        isSearchBarExpanded = expand

        val parentView = binding.layoutHeaderBar
        val transition = androidx.transition.AutoTransition().apply {
            duration = 220
        }
        androidx.transition.TransitionManager.beginDelayedTransition(parentView, transition)

        if (expand) {
            binding.btnOpenMenu.visibility = View.GONE
            binding.btnFavorites.visibility = View.GONE
            binding.btnSearchClearMarketplace.visibility = View.VISIBLE
            val params = binding.cardSearchMarketplace.layoutParams as? ViewGroup.MarginLayoutParams
            params?.setMargins(0, 0, 0, 0)
            binding.cardSearchMarketplace.layoutParams = params
        } else {
            binding.btnOpenMenu.visibility = View.VISIBLE
            binding.btnFavorites.visibility = View.VISIBLE
            binding.btnSearchClearMarketplace.visibility = View.GONE
            val margin = (12 * resources.displayMetrics.density).toInt()
            val params = binding.cardSearchMarketplace.layoutParams as? ViewGroup.MarginLayoutParams
            params?.setMargins(margin, 0, margin, 0)
            binding.cardSearchMarketplace.layoutParams = params
        }
    }

    private fun performSearchSubmit() {
        searchJob?.cancel()
        hideKeyboard()
        binding.etSearchMarketplace.clearFocus()
        binding.layoutSearchSuggestions.visibility = View.GONE
        restoreMainFeedViews()
        filterDataByMode()
        if (searchQuery.isEmpty()) {
            expandSearchBar(false)
        }
    }

    private fun clearSearchAndHideKeyboard() {
        searchJob?.cancel()
        searchQuery = ""
        binding.etSearchMarketplace.setText("")
        binding.etSearchMarketplace.clearFocus()
        binding.btnSearchClearMarketplace.visibility = View.GONE
        binding.layoutSearchSuggestions.visibility = View.GONE
        expandSearchBar(false)
        hideKeyboard()
        restoreMainFeedViews()
        filterDataByMode()
    }

    private fun hideMainFeedViews() {
        binding.layoutRecentShopsHeader.visibility = View.GONE
        binding.rvRecentShops.visibility = View.GONE
        binding.layoutFollowedUpdatesHeader.visibility = View.GONE
        binding.rvFollowedUpdates.visibility = View.GONE
        binding.layoutRecentlyViewedHeader.visibility = View.GONE
        binding.rvRecentlyViewed.visibility = View.GONE
        binding.rvMarketplaceGrid.visibility = View.GONE
        binding.btnLoadMoreMarketplace.visibility = View.GONE
        binding.tvNoMoreProductsMarketplace.visibility = View.GONE
    }

    private fun restoreMainFeedViews() {
        binding.rvMarketplaceGrid.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchMarketplace.windowToken, 0)
    }

    inner class SearchSuggestionsAdapter(
        private val onClick: (MarketplaceProduct) -> Unit
    ) : RecyclerView.Adapter<SearchSuggestionsAdapter.ViewHolder>() {

        private var items = listOf<MarketplaceProduct>()

        fun updateData(newList: List<MarketplaceProduct>) {
            if (items == newList) return
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos].id == newList[newPos].id && items[oldPos].storeId == newList[newPos].storeId
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newList[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newList.toList()
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvSuggestionName)
            val tvDetails: TextView = view.findViewById(R.id.tvSuggestionStoreAndPrice)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_search_suggestion, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            val ratingText = if (item.reviewCount > 0) " • ★ ${String.format(Locale.US, "%.1f", item.averageRating)} (${item.reviewCount})" else ""
            holder.tvDetails.text = "${item.storeName} • S/ ${String.format(Locale.US, "%.2f", item.price)}$ratingText"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadData(isPullToRefresh: Boolean = false) {
        if (!isAdded || _binding == null || isLoadingData) return
        
        isLoadingData = true
        lastLoadTime = System.currentTimeMillis()
        visibleProductLimit = PAGE_SIZE
        visibleStoreLimit = PAGE_SIZE
        loadFollowedStoresUpdates()
        
        if (isPullToRefresh) {
            binding.swipeRefreshMarketplace.isRefreshing = true
        } else {
            // Ocultar el contenido principal durante la carga para evitar colisión visual
            binding.nsvMarketplaceMain.visibility = View.INVISIBLE
            binding.nsvMarketplaceMain.alpha = 0f
            binding.layoutLoadingStores.visibility = View.VISIBLE
            binding.layoutLoadingStores.alpha = 1f
            marketplaceAdapter.resetAnimation()
            storesAdapter.resetAnimation()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            var renderedAny = false
            try {
                // 1. CARGA ULTRA RÁPIDA DESDE EL CACHÉ LOCAL (0ms de espera al entrar)
                if (!isPullToRefresh) {
                    try {
                        val cacheSnap = db.collectionGroup("settings").limit(200).get(Source.CACHE).await()
                        if (_binding != null && cacheSnap != null && !cacheSnap.isEmpty) {
                            processAndRenderStoresAndProducts(cacheSnap, isPullToRefresh = false, enrichRatings = false)
                            renderedAny = true
                        }
                    } catch (e: Exception) {
                        // Si no hay caché en la primera instalación, continuará normalmente
                    }
                }

                if (!isNetworkAvailable()) {
                    binding.swipeRefreshMarketplace.isRefreshing = false
                    isLoadingData = false
                    showErrorMessage("Sin conexión a internet. Comprueba tu conexión e intenta nuevamente.")
                    return@launch
                }

                // 2. SINCRONIZACIÓN EN SEGUNDO PLANO CON EL SERVIDOR
                var serverSnap: QuerySnapshot? = null
                try {
                    serverSnap = db.collectionGroup("settings").limit(200).get(Source.DEFAULT).await()
                } catch (e: Exception) {
                    android.util.Log.e("BuyerMarketplaceFragment", "Error querying collectionGroup settings: ${e.message}")
                }

                if (_binding != null && serverSnap != null) {
                    hasLoadedStoresFromServer = true
                    storesSnapWasTruncated = serverSnap.size() >= 200
                    processAndRenderStoresAndProducts(serverSnap, isPullToRefresh = isPullToRefresh, enrichRatings = true)
                    renderedAny = true
                }

                // Si no se obtuvieron productos (por reglas de permisos de Firestore o filtrado), ejecutar respaldo SOLO sobre tiendas publicadas
                if (_binding != null && allMarketplaceProducts.isEmpty() && allStores.isNotEmpty()) {
                    loadDirectInventoryFallback()
                    renderedAny = true
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingData = false
                if (_binding != null) {
                    binding.swipeRefreshMarketplace.isRefreshing = false
                    binding.swipeRefreshMarketplace.isEnabled = false

                    if (!isPullToRefresh) {
                        if (!renderedAny || allMarketplaceProducts.isEmpty()) {
                            filterDataByMode()
                        }

                        // Ocultar pantalla de carga con desvanecimiento suave y revelar productos
                        binding.layoutLoadingStores.animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction {
                                if (_binding != null) binding.layoutLoadingStores.visibility = View.GONE
                            }
                            .start()

                        binding.nsvMarketplaceMain.visibility = View.VISIBLE
                        binding.nsvMarketplaceMain.animate()
                            .alpha(1f)
                            .setDuration(350)
                            .start()
                    }
                }
            }
        }
    }

    private fun isStorePublished(doc: DocumentSnapshot): Boolean {
        if (doc.id == "business_info") return false
        return when (val raw = doc.get("is_online")) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun isMatchingStoreForCollection(store: StoreModel, collName: String): Boolean {
        val sType = store.businessType.uppercase(Locale.ROOT)
        return when (collName) {
            "hotel_rooms" -> sType == "HOTEL"
            "inventory", "products" -> sType != "HOTEL" && sType != "LOANS"
            else -> true
        }
    }

    private suspend fun fetchAllProductsParallel(publishedStores: List<StoreModel>): List<MarketplaceProduct> = coroutineScope {
        if (publishedStores.isEmpty()) return@coroutineScope emptyList()

        val collections = listOf("inventory", "hotel_rooms", "products")
        val jobs = collections.map { coll ->
            async(Dispatchers.IO) {
                try {
                    val snap = db.collectionGroup(coll).limit(150).get().await()
                    snap.documents.mapNotNull { pDoc ->
                        if (pDoc.getBoolean("isDeleted") == true) return@mapNotNull null
                        val pathParts = pDoc.reference.path.split("/")
                        val sId = pDoc.getString("storeId")
                            ?: pDoc.getString("userId")
                            ?: pDoc.getString("ownerId")
                            ?: if (pathParts.size >= 2) pathParts[1] else ""

                        if (sId.isBlank()) return@mapNotNull null
                        val store = publishedStores.firstOrNull { it.id == sId && isMatchingStoreForCollection(it, coll) }
                            ?: return@mapNotNull null

                        if (store.country.isNotEmpty() && buyerCountry.isNotEmpty() && !isSameCountry(store.country, buyerCountry)) return@mapNotNull null

                        val isHotel = coll == "hotel_rooms" || store.businessType.equals("HOTEL", ignoreCase = true)
                        val price = pDoc.getDouble("precioVenta")
                            ?: pDoc.getDouble("precio")
                            ?: pDoc.getDouble("baseRate")
                            ?: 0.0
                        val name = if (isHotel) {
                            "Habitación ${pDoc.getString("number") ?: pDoc.id}"
                        } else {
                            pDoc.getString("nombre") ?: pDoc.getString("name") ?: pDoc.getString("title") ?: "Producto"
                        }
                        val sLogo = pDoc.getString("storeLogo")
                            ?: pDoc.getString("storeLogoUrl")
                            ?: pDoc.getString("logoUrl")
                            ?: store.logoUrl

                        MarketplaceProduct(
                            id = pDoc.id,
                            name = name,
                            category = pDoc.getString("categoria") ?: pDoc.getString("type") ?: "General",
                            price = price,
                            description = pDoc.getString("descripcion") ?: pDoc.getString("description") ?: "",
                            photoUrl = pDoc.getString("photoUrl"),
                            storeId = sId,
                            storeName = store.name.ifBlank { pDoc.getString("storeName") ?: "Tienda GoMezMart" },
                            storeAddress = store.address.ifBlank { "Sin dirección" },
                            storeLogoUrl = sLogo,
                            storeType = if (isHotel) "HOTEL" else store.businessType,
                            averageRating = pDoc.getDouble("average_rating") ?: 0.0,
                            reviewCount = (pDoc.getLong("review_count") ?: 0L).toInt()
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BuyerMarketplace", "Error fetching collectionGroup $coll: ${e.message}")
                    emptyList()
                }
            }
        }
        val results = jobs.awaitAll().flatten()
        if (results.isNotEmpty()) {
            results
        } else {
            fetchFallbackProducts(publishedStores)
        }
    }

    private suspend fun fetchFallbackProducts(publishedStores: List<StoreModel>): List<MarketplaceProduct> = withContext(Dispatchers.IO) {
        if (publishedStores.isEmpty()) return@withContext emptyList()
        val fallback = mutableListOf<MarketplaceProduct>()

        for (store in publishedStores.take(40)) {
            val colls = when (store.businessType.uppercase(Locale.ROOT)) {
                "HOTEL" -> listOf("hotel_rooms")
                "LOANS" -> emptyList()
                else -> listOf("inventory", "products")
            }
            for (collName in colls) {
                try {
                    val subSnap = db.collection("users").document(store.id).collection(collName).limit(50).get().await()
                    subSnap.documents.forEach { pDoc ->
                        if (pDoc.getBoolean("isDeleted") == true) return@forEach
                        val isHotel = collName == "hotel_rooms" || store.businessType.equals("HOTEL", ignoreCase = true)
                        val price = pDoc.getDouble("precioVenta") ?: pDoc.getDouble("precio") ?: pDoc.getDouble("baseRate") ?: 0.0
                        val name = if (isHotel) "Habitación ${pDoc.getString("number") ?: pDoc.id}" else pDoc.getString("nombre") ?: pDoc.getString("name") ?: "Producto"
                        val sLogo = pDoc.getString("storeLogo") ?: pDoc.getString("storeLogoUrl") ?: pDoc.getString("logoUrl") ?: store.logoUrl

                        fallback.add(MarketplaceProduct(
                            id = pDoc.id,
                            name = name,
                            category = pDoc.getString("categoria") ?: pDoc.getString("type") ?: "General",
                            price = price,
                            description = pDoc.getString("descripcion") ?: pDoc.getString("description") ?: "",
                            photoUrl = pDoc.getString("photoUrl"),
                            storeId = store.id,
                            storeName = store.name.ifBlank { pDoc.getString("storeName") ?: "Tienda GoMezMart" },
                            storeAddress = store.address.ifBlank { "Sin dirección" },
                            storeLogoUrl = sLogo,
                            storeType = if (isHotel) "HOTEL" else store.businessType,
                            averageRating = pDoc.getDouble("average_rating") ?: 0.0,
                            reviewCount = (pDoc.getLong("review_count") ?: 0L).toInt()
                        ))
                    }
                } catch (e: Exception) {}
            }
        }
        fallback
    }

    private var enrichRatingsJob: Job? = null

    private suspend fun processAndRenderStoresAndProducts(
        storesSnap: QuerySnapshot,
        isPullToRefresh: Boolean = false,
        enrichRatings: Boolean = true
    ) {
        val (newStoresList, seenPublishedKeys, seenUnpublishedKeys) = withContext(Dispatchers.Default) {
            val userDocsMap = mutableMapOf<String, MutableList<DocumentSnapshot>>()
            for (doc in storesSnap.documents) {
                val pathParts = doc.reference.path.split("/")
                if (pathParts.size < 2) continue
                val sId = pathParts[1]
                userDocsMap.getOrPut(sId) { mutableListOf() }.add(doc)
            }

            val filteredStores = mutableListOf<StoreModel>()
            val pubKeys = mutableSetOf<Pair<String, String>>()
            val unpubKeys = mutableSetOf<Pair<String, String>>()

            for ((sId, docs) in userDocsMap) {
                for (mainDoc in docs) {
                    val sType = mainDoc.getString("business_type") ?: mainDoc.id
                    val key = sId to sType.uppercase(Locale.ROOT)

                    if (!isStorePublished(mainDoc)) {
                        unpubKeys.add(key)
                        continue
                    }

                    val sName = mainDoc.getString("business_name")
                        ?: mainDoc.getString("name")
                        ?: mainDoc.getString("title")
                        ?: continue

                    if (sName.isBlank() || sName.equals("Mi Negocio", ignoreCase = true)) {
                        unpubKeys.add(key)
                        continue
                    }

                    val sCity = mainDoc.getString("business_city") ?: ""
                    val sZip = mainDoc.getString("business_postal_code") ?: ""
                    val sCountry = mainDoc.getString("business_country") ?: ""

                    if (buyerCountry.isNotEmpty() && sCountry.isNotEmpty() && !isSameCountry(sCountry, buyerCountry)) continue

                    val sLogo = extractStoreLogoUrl(docs, sType)
                    pubKeys.add(key)

                    filteredStores.add(StoreModel(
                        id = sId,
                        name = sName,
                        address = mainDoc.getString("business_address") ?: "Sin dirección",
                        logoUrl = sLogo,
                        description = mainDoc.getString("business_description") ?: "",
                        ownerName = mainDoc.getString("business_owner_name") ?: "",
                        creationDate = mainDoc.getLong("creation_date") ?: 0L,
                        businessType = sType,
                        city = sCity,
                        postalCode = sZip,
                        country = sCountry,
                        averageRating = mainDoc.getDouble("average_rating") ?: 0.0,
                        reviewCount = (mainDoc.getLong("review_count") ?: 0L).toInt(),
                        followersCount = (mainDoc.getLong("followers_count") ?: 0L).toInt(),
                        isVerified = mainDoc.getBoolean("is_verified") ?: false
                    ))
                }
            }
            // Eliminar tiendas duplicadas por ID, nombre o dirección similar
            val deduped = deduplicateStores(filteredStores)
            deduped.forEach { pubKeys.add(it.id to it.businessType.uppercase(Locale.ROOT)) }
            unpubKeys.removeAll(pubKeys)
            Triple(deduped, pubKeys.toSet(), unpubKeys.toSet())
        }

        publishedStoreKeys = seenPublishedKeys
        unpublishedStoreKeys = seenUnpublishedKeys

        val storesChanged = newStoresList != allStores
        if (storesChanged) {
            allStores.clear()
            allStores.addAll(newStoresList)
        }

        if (_binding != null && storesChanged) {
            recentShopsAdapter.updateData(allStores.sortedByDescending { it.creationDate }.take(5))
            loadRecentlyViewed()
        }

        val rawProducts = fetchAllProductsParallel(allStores)

        if (_binding == null) return
        
        val existingOrderMap = if (!isPullToRefresh && allMarketplaceProducts.isNotEmpty()) {
            allMarketplaceProducts.withIndex().associate { (index, item) ->
                val key = if (item.id.isNotBlank() && item.id != "local") item.id else "${item.storeId}_${item.name.trim().lowercase(Locale.ROOT)}"
                key to index
            }
        } else {
            emptyMap()
        }

        // Eliminar productos duplicados de forma estricta por ID, priorizando tiendas verificadas/VIP arriba
        val verifiedStoreIds = allStores.filter { it.isVerified }.map { it.id }.toSet()
        val uniqueProducts = withContext(Dispatchers.Default) {
            val deduped = rawProducts.distinctBy { p ->
                if (p.id.isNotBlank() && p.id != "local") p.id else "${p.storeId}_${p.name.trim().lowercase(Locale.ROOT)}"
            }
            if (existingOrderMap.isEmpty()) {
                deduped.shuffled().sortedByDescending { p ->
                    p.storeId in verifiedStoreIds
                }
            } else {
                deduped.sortedBy { p ->
                    val key = if (p.id.isNotBlank() && p.id != "local") p.id else "${p.storeId}_${p.name.trim().lowercase(Locale.ROOT)}"
                    existingOrderMap[key] ?: Int.MAX_VALUE
                }
            }
        }

        val productsChanged = uniqueProducts != allMarketplaceProducts
        if (productsChanged) {
            allMarketplaceProducts.clear()
            allMarketplaceProducts.addAll(uniqueProducts)
        }
        
        if (isPullToRefresh) {
            marketplaceAdapter.resetAnimation()
            storesAdapter.resetAnimation()
        }
        
        if (storesChanged || productsChanged) {
            filterDataByMode()
        }
        if (enrichRatings) {
            enrichRatingsFromReviews()
        }
    }

    private fun enrichRatingsFromReviews() {
        enrichRatingsJob?.cancel()
        enrichRatingsJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Deduplicar por userId (manteniendo la reseña más reciente) para coincidir con StoreProfileActivity y ProductDetailActivity
                val storeRatings = mutableMapOf<String, MutableMap<String, Pair<Long, Int>>>()
                val productRatings = mutableMapOf<String, MutableMap<String, Pair<Long, Int>>>()
                var fetchedFullGroup = false

                fun recordRating(
                    map: MutableMap<String, MutableMap<String, Pair<Long, Int>>>,
                    targetKey: String,
                    doc: DocumentSnapshot
                ) {
                    val rawRating = (doc.get("rating") as? Number)?.toInt() ?: return
                    val rating = rawRating.coerceIn(1, 5)
                    val userKey = doc.getString("userId")?.takeIf { it.isNotBlank() } ?: doc.id
                    val ts = (doc.get("timestamp") as? Number)?.toLong() ?: 0L
                    val userMap = map.getOrPut(targetKey) { mutableMapOf() }
                    val prev = userMap[userKey]
                    if (prev == null || ts >= prev.first) {
                        userMap[userKey] = ts to rating
                    }
                }

                val reviewsSnap = runCatching {
                    db.collectionGroup("reviews").limit(500).get().await()
                }.getOrNull()

                if (reviewsSnap != null && !reviewsSnap.isEmpty) {
                    fetchedFullGroup = reviewsSnap.size() < 500
                    for (doc in reviewsSnap.documents) {
                        val parts = doc.reference.path.split("/")
                        if (parts.size == 4 && parts[0] == "users" && parts[2] == "reviews") {
                            recordRating(storeRatings, parts[1], doc)
                        } else if (parts.size == 6 && parts[0] == "users" && parts[4] == "reviews") {
                            val key = "${parts[1]}_${parts[3]}"
                            recordRating(productRatings, key, doc)
                        }
                    }
                } else {
                    val storesCopy = withContext(Dispatchers.Main) { ArrayList(allStores).take(25) }
                    if (storesCopy.isNotEmpty()) {
                        coroutineScope {
                            storesCopy.map { store ->
                                async {
                                    runCatching {
                                        val snap = db.collection("users").document(store.id)
                                            .collection("reviews").get().await()
                                        if (!snap.isEmpty) {
                                            synchronized(storeRatings) {
                                                snap.documents.forEach { doc ->
                                                    recordRating(storeRatings, store.id, doc)
                                                }
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }

                if (storeRatings.isEmpty() && productRatings.isEmpty() && !fetchedFullGroup) return@launch

                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext

                    var storesUpdated = false
                    for (i in allStores.indices) {
                        val s = allStores[i]
                        val ratingsMap = storeRatings[s.id]
                        if (!ratingsMap.isNullOrEmpty()) {
                            val ratings = ratingsMap.values.map { it.second }
                            val avg = kotlin.math.round(ratings.average() * 10.0) / 10.0
                            if (s.averageRating != avg || s.reviewCount != ratings.size) {
                                allStores[i] = s.copy(averageRating = avg, reviewCount = ratings.size)
                                storesUpdated = true
                            }
                        } else if (fetchedFullGroup && (s.reviewCount != 0 || s.averageRating != 0.0)) {
                            allStores[i] = s.copy(averageRating = 0.0, reviewCount = 0)
                            storesUpdated = true
                        }
                    }
                    if (storesUpdated) {
                        recentShopsAdapter.updateData(allStores.sortedByDescending { it.creationDate }.take(5))
                    }

                    var productsUpdated = false
                    for (i in allMarketplaceProducts.indices) {
                        val p = allMarketplaceProducts[i]
                        val ratingsMap = productRatings["${p.storeId}_${p.id}"]
                        if (!ratingsMap.isNullOrEmpty()) {
                            val ratings = ratingsMap.values.map { it.second }
                            val avg = kotlin.math.round(ratings.average() * 10.0) / 10.0
                            if (p.averageRating != avg || p.reviewCount != ratings.size) {
                                allMarketplaceProducts[i] = p.copy(averageRating = avg, reviewCount = ratings.size)
                                productsUpdated = true
                            }
                        } else if (fetchedFullGroup && (p.reviewCount != 0 || p.averageRating != 0.0)) {
                            allMarketplaceProducts[i] = p.copy(averageRating = 0.0, reviewCount = 0)
                            productsUpdated = true
                        }
                    }
                    if (productsUpdated) {
                        loadRecentlyViewed()
                    }

                    if (storesUpdated || productsUpdated) {
                        filterDataByMode()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("BuyerMarketplace", "No se pudieron enriquecer reseñas: ${e.message}")
            }
        }
    }

    private fun toStandardCountryName(c: String): String {
        val trimmed = c.trim()
        if (trimmed.length == 2) {
            try {
                val nameEs = Locale("", trimmed.uppercase(Locale.ROOT)).getDisplayCountry(Locale("es"))
                if (nameEs.isNotBlank()) return nameEs
            } catch (e: Exception) {}
        }
        return trimmed
    }

    private fun extractStoreLogoUrl(docs: List<DocumentSnapshot>, sType: String): String? {
        val logoFields = listOf(
            "business_logo_url",
            "${sType}_logo_url",
            "HOTEL_logo_url",
            "PRODUCTS_logo_url",
            "LOANS_logo_url",
            "logoUrl",
            "businessLogoUrl",
            "collab_business_logo",
            "photoUrl"
        )

        for (doc in docs) {
            for (field in logoFields) {
                val value = doc.getString(field)
                if (!value.isNullOrBlank()) {
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        return value
                    }
                }
            }
        }
        
        for (doc in docs) {
            for (field in logoFields) {
                val value = doc.getString(field)
                if (!value.isNullOrBlank()) return value
            }
        }

        return null
    }

    private val storeLogoCache = mutableMapOf<String, String>()

    private fun loadStoreLogoAsync(storeId: String, onLoaded: (String) -> Unit) {
        if (storeId.isBlank() || storeId == "local") return
        val cached = storeLogoCache[storeId]
        if (cached != null) {
            if (cached.isNotEmpty()) onLoaded(cached)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                var foundLogo: String? = null
                
                try {
                    val profileDoc = db.collection("users").document(storeId).collection("profile").document("info").get().await()
                    if (profileDoc.exists()) {
                        foundLogo = profileDoc.getString("photoUrl") 
                            ?: profileDoc.getString("businessLogoUrl") 
                            ?: profileDoc.getString("business_logo_url")
                            ?: profileDoc.getString("HOTEL_logo_url")
                    }
                } catch (e: Exception) {}

                if (foundLogo.isNullOrEmpty()) {
                    try {
                        val rootDoc = db.collection("users").document(storeId).get().await()
                        if (rootDoc.exists()) {
                            foundLogo = rootDoc.getString("businessLogoUrl") 
                                ?: rootDoc.getString("business_logo_url") 
                                ?: rootDoc.getString("photoUrl")
                                ?: rootDoc.getString("HOTEL_logo_url")
                        }
                    } catch (e: Exception) {}
                }

                if (foundLogo.isNullOrEmpty()) {
                    try {
                        val settingsSnap = db.collection("users").document(storeId).collection("settings").get().await()
                        if (!settingsSnap.isEmpty) {
                            foundLogo = extractStoreLogoUrl(settingsSnap.documents, "HOTEL")
                        }
                    } catch (e: Exception) {}
                }

                val finalLogo = foundLogo ?: ""
                storeLogoCache[storeId] = finalLogo

                if (finalLogo.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onLoaded(finalLogo)
                    }
                }
            } catch (e: Exception) {
                storeLogoCache[storeId] = ""
            }
        }
    }

    private fun isSameCountry(c1: String, c2: String): Boolean {
        if (c1.isBlank() || c2.isBlank()) return true
        if (isEmulatorLocation("", c1) || isEmulatorLocation("", c2)) return true
        val name1 = toStandardCountryName(c1)
        val name2 = toStandardCountryName(c2)
        val norm1 = java.text.Normalizer.normalize(name1.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").trim()
        val norm2 = java.text.Normalizer.normalize(name2.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").trim()
        return norm1 == norm2 || norm1.contains(norm2) || norm2.contains(norm1)
    }

    private fun deduplicateStores(rawList: List<StoreModel>): List<StoreModel> {
        val result = mutableListOf<StoreModel>()
        for (store in rawList) {
            val existingIdx = result.indexOfFirst { existing -> isSimilarStore(existing, store) }
            if (existingIdx != -1) {
                val existing = result[existingIdx]
                val preferred = when {
                    !store.logoUrl.isNullOrEmpty() && store.logoUrl.startsWith("http") && (existing.logoUrl.isNullOrEmpty() || !existing.logoUrl.startsWith("http")) -> store
                    !store.logoUrl.isNullOrEmpty() && existing.logoUrl.isNullOrEmpty() -> store
                    store.creationDate > existing.creationDate -> store
                    store.name.length > existing.name.length -> store
                    else -> existing
                }
                result[existingIdx] = preferred
            } else {
                result.add(store)
            }
        }
        return result
    }

    private fun isSimilarStore(s1: StoreModel, s2: StoreModel): Boolean {
        if (s1.id.isNotBlank() && s1.id == s2.id && s1.businessType == s2.businessType) return true
        
        val n1 = normalizeTextForComparison(s1.name)
        val n2 = normalizeTextForComparison(s2.name)
        
        val a1 = normalizeTextForComparison(s1.address)
        val a2 = normalizeTextForComparison(s2.address)

        if (a1.isBlank() || a2.isBlank() || a1 == "sin direccion" || a2 == "sin direccion") {
            // Si no hay dirección suficiente, comparar nombres muy parecidos del mismo tipo
            if (s1.businessType == s2.businessType && (n1 == n2 || n1.contains(n2) || n2.contains(n1))) return true
            return false
        }

        val nums1 = Regex("\\b\\d+\\b").findAll(a1).map { it.value }.toSet()
        val nums2 = Regex("\\b\\d+\\b").findAll(a2).map { it.value }.toSet()
        val shareNumber = nums1.isNotEmpty() && nums2.isNotEmpty() && nums1.intersect(nums2).isNotEmpty()

        val stopWords = setOf("hospedaje", "tienda", "hotel", "boutique", "hostal", "resort", "prestamos", "servicios")
        val words1 = n1.split("\\s+".toRegex()).filter { it.length > 3 && it !in stopWords }.toSet()
        val words2 = n2.split("\\s+".toRegex()).filter { it.length > 3 && it !in stopWords }.toSet()
        val shareNameKeyword = words1.intersect(words2).isNotEmpty()

        if (shareNumber && shareNameKeyword && s1.businessType == s2.businessType) return true

        val cleanAddr1 = a1.replace(Regex("\\b(calle|av|avenida|jr|jiron|pasaje|psj|n°|no|de|el|la|peru|loreto|requena)\\b"), "").replace("\\s+".toRegex(), "")
        val cleanAddr2 = a2.replace(Regex("\\b(calle|av|avenida|jr|jiron|pasaje|psj|n°|no|de|el|la|peru|loreto|requena)\\b"), "").replace("\\s+".toRegex(), "")

        if (cleanAddr1.length > 4 && cleanAddr2.length > 4 && s1.businessType == s2.businessType) {
            if (cleanAddr1 == cleanAddr2 || cleanAddr1.contains(cleanAddr2) || cleanAddr2.contains(cleanAddr1)) {
                return true
            }
        }

        return false
    }

    private fun normalizeTextForComparison(text: String): String {
        return java.text.Normalizer.normalize(text.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .trim()
    }

    private suspend fun loadDirectInventoryFallback() {
        val publishedStoresSnapshot = withContext(Dispatchers.Main) { ArrayList(allStores) }
        if (publishedStoresSnapshot.isEmpty()) return

        val fallbackProducts = fetchFallbackProducts(publishedStoresSnapshot)
        if (fallbackProducts.isNotEmpty()) {
            val uniqueFallback = fallbackProducts.distinctBy { p ->
                if (p.id.isNotBlank() && p.id != "local") p.id else "${p.storeId}_${p.name.trim().lowercase(Locale.ROOT)}"
            }
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    allMarketplaceProducts.clear()
                    allMarketplaceProducts.addAll(uniqueFallback.shuffled())
                    filterDataByMode()
                }
            }
        }
    }

    private fun showErrorMessage(message: String) {
        if (_binding == null) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    // --- ADAPTERS ---
    inner class MarketplaceAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<MarketplaceAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()

        fun resetAnimation() {}

        fun updateData(newList: List<MarketplaceProduct>) {
            val next = newList.toList()
            if (items == next) return
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    items[oldPos].id == next[newPos].id && items[oldPos].storeId == next[newPos].storeId
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == next[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = next
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val b: ItemMarketplaceGridProductBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemMarketplaceGridProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                if (p.storeType == "HOTEL") {
                    tvProductName.visibility = View.GONE
                    tvProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)} la noche"
                } else {
                    tvProductName.visibility = View.VISIBLE
                    tvProductName.text = p.name
                    tvProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                }
                if (p.reviewCount > 0) {
                    tvProductRating.text = "★ ${String.format(Locale.US, "%.1f", p.averageRating)} (${p.reviewCount})"
                    tvProductRating.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvProductRating.setTextColor(android.graphics.Color.parseColor("#B45309"))
                } else {
                    tvProductRating.text = "☆ 0.0 (0)"
                    tvProductRating.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvProductRating.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                tvProductStore.text = p.storeName

                GlideHelper.loadImageWithProgressBar(
                    context = ivProductImage.context,
                    url = p.photoUrl,
                    imageView = ivProductImage,
                    progressBar = pbProductLoadingGrid,
                    errorPlaceholder = R.drawable.gomezmart_icon
                )
                
                val logoToLoad = p.storeLogoUrl?.takeIf { it.startsWith("http") }
                GlideHelper.loadImageWithProgressBar(
                    context = ivProductStoreLogo.context,
                    url = logoToLoad,
                    imageView = ivProductStoreLogo,
                    progressBar = pbProductStoreLogoLoading,
                    errorPlaceholder = R.drawable.gomezmart_icon
                )
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentlyViewedAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<RecentlyViewedAdapter.ViewHolder>() {
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

        inner class ViewHolder(val b: ItemRecentlyViewedBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemRecentlyViewedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                root.alpha = 1f
                root.translationX = 0f

                if (p.storeType == "HOTEL") {
                    tvRecentProductName.visibility = View.VISIBLE
                    tvRecentProductName.text = p.storeName.ifEmpty { "Hospedaje" }
                    tvRecentProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)} la noche"
                } else {
                    tvRecentProductName.visibility = View.VISIBLE
                    tvRecentProductName.text = p.name
                    tvRecentProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                }
                if (p.reviewCount > 0) {
                    tvRecentProductRating.text = "★ ${String.format(Locale.US, "%.1f", p.averageRating)} (${p.reviewCount})"
                    tvRecentProductRating.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvRecentProductRating.setTextColor(android.graphics.Color.parseColor("#B45309"))
                } else {
                    tvRecentProductRating.text = "☆ 0.0 (0)"
                    tvRecentProductRating.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvRecentProductRating.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                
                GlideHelper.loadImageWithProgressBar(
                    context = ivRecentProductImage.context,
                    url = p.photoUrl,
                    imageView = ivRecentProductImage,
                    progressBar = null,
                    errorPlaceholder = R.drawable.gomezmart_icon
                )
                    
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class StoreAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<StoreAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()

        fun resetAnimation() {}

        fun setStores(newList: List<StoreModel>) {
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

        inner class ViewHolder(val b: ItemStoreBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                root.alpha = 1f
                root.translationY = 0f

                tvStoreName.text = s.name
                ivStoreVerified.visibility = if (s.isVerified) View.VISIBLE else View.GONE
                tvStoreCategory.text = if (s.businessType == "HOTEL") "Hospedaje y Turismo" else "Tienda de Productos"
                tvStoreAddress.text = "📍 ${s.address}"
                tvStoreRating.visibility = View.VISIBLE
                if (s.reviewCount > 0) {
                    tvStoreRating.text = "★ ${String.format(Locale.US, "%.1f", s.averageRating)} (${s.reviewCount})"
                    tvStoreRating.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvStoreRating.setTextColor(android.graphics.Color.parseColor("#B45309"))
                } else {
                    tvStoreRating.text = "☆ 0.0 (0)"
                    tvStoreRating.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvStoreRating.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                if (s.followersCount > 0) {
                    tvStoreFollowers.visibility = View.VISIBLE
                    tvStoreFollowers.text = "👥 ${s.followersCount}"
                } else {
                    tvStoreFollowers.visibility = View.GONE
                }
                GlideHelper.loadImageWithProgressBar(
                    context = ivStoreLogo.context,
                    url = s.logoUrl,
                    imageView = ivStoreLogo,
                    progressBar = null,
                    errorPlaceholder = R.drawable.gomezmart_icon
                )
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentShopsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<RecentShopsAdapter.ViewHolder>() {
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                root.alpha = 1f
                root.translationY = 0f

                tvStoreNameSmall.text = s.name
                ivStoreVerifiedSmall.visibility = if (s.isVerified) View.VISIBLE else View.GONE
                tvStoreRatingSmall.visibility = View.VISIBLE
                if (s.reviewCount > 0) {
                    tvStoreRatingSmall.text = "★ ${String.format(Locale.US, "%.1f", s.averageRating)} (${s.reviewCount})"
                    tvStoreRatingSmall.setBackgroundResource(R.drawable.bg_pill_amber_light)
                    tvStoreRatingSmall.setTextColor(android.graphics.Color.parseColor("#B45309"))
                } else {
                    tvStoreRatingSmall.text = "☆ 0.0 (0)"
                    tvStoreRatingSmall.setBackgroundResource(R.drawable.bg_pill_slate_light)
                    tvStoreRatingSmall.setTextColor(ContextCompat.getColor(root.context, R.color.slate_500))
                }
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvStoreDateSmall.text = "Agregada: ${if (s.creationDate > 0) sdf.format(Date(s.creationDate)) else "--/--"}"
                GlideHelper.loadImageWithProgressBar(
                    context = ivStoreLogoSmall.context,
                    url = s.logoUrl,
                    imageView = ivStoreLogoSmall,
                    progressBar = pbStoreLogoSmallLoading,
                    errorPlaceholder = R.drawable.gomezmart_icon
                )
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    private fun loadFollowedStoresUpdates() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous) {
            followedStoreIds = emptySet()
            followedStoreUpdates = emptyList()
            if (_binding != null) {
                followedUpdatesAdapter.updateData(emptyList())
                if (currentMode == "FOLLOWING") filterDataByMode()
                else {
                    binding.layoutFollowedUpdatesHeader.visibility = View.GONE
                    binding.rvFollowedUpdates.visibility = View.GONE
                }
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (ids, updates) = SocialRepository.fetchFollowedStoresData(db, currentUser.uid)
            if (!isAdded || _binding == null) return@launch
            followedStoreIds = ids
            followedStoreUpdates = updates
            followedUpdatesAdapter.updateData(updates)
            val showBanner = searchQuery.isEmpty() && (currentMode == "ALL" || currentMode == "FOLLOWING") && updates.isNotEmpty()
            binding.layoutFollowedUpdatesHeader.visibility = if (showBanner) View.VISIBLE else View.GONE
            binding.rvFollowedUpdates.visibility = if (showBanner) View.VISIBLE else View.GONE
            if (currentMode == "FOLLOWING") {
                filterDataByMode()
            }
        }
    }

    inner class FollowedUpdatesAdapter(private val onClick: (StoreUpdate) -> Unit) : RecyclerView.Adapter<FollowedUpdatesAdapter.ViewHolder>() {
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
                tvUpdateStoreName.text = item.storeName.ifBlank { "Tienda GoMezMart" }
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
                        tvUpdateTypeBadge.setTextColor(android.graphics.Color.parseColor("#B45309"))
                    }
                    else -> {
                        tvUpdateTypeBadge.text = "📢 Anuncio"
                        tvUpdateTypeBadge.setBackgroundResource(R.drawable.bg_pill_purple_light)
                        tvUpdateTypeBadge.setTextColor(ContextCompat.getColor(root.context, R.color.purple_700))
                    }
                }

                if (!item.storeLogoUrl.isNullOrBlank()) {
                    Glide.with(ivUpdateStoreLogo)
                        .load(item.storeLogoUrl)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}