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
import com.gomezmart.app.*
import com.gomezmart.app.data.MarketplaceProduct
import com.gomezmart.app.data.StoreModel
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
    private lateinit var searchSuggestionsAdapter: SearchSuggestionsAdapter
    
    private var allMarketplaceProducts = mutableListOf<MarketplaceProduct>()
    private var allStores = mutableListOf<StoreModel>()
    private var currentMode = "ALL"
    
    private var buyerCity = ""
    private var buyerPostalCode = ""
    
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
        
        // Cargar datos solo si es la primera vez
        if (allStores.isEmpty()) {
            loadData()
        }

        // Si no hay ciudad detectada, intentar detectar ahora
        if (buyerCity.isEmpty()) {
            detectUserLocation()
        }
    }

    private fun loadSavedLocation() {
        val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
        buyerCity = prefs.getString("buyer_city", "") ?: ""
        buyerPostalCode = prefs.getString("buyer_postal_code", "") ?: ""
        updateLocationUI()
    }

    private fun updateLocationUI() {
        if (_binding == null) return
        if (buyerCity.isNotEmpty()) {
            binding.tvCurrentLocation.text = if (buyerPostalCode.isNotEmpty()) {
                "$buyerCity, $buyerPostalCode"
            } else {
                buyerCity
            }
        } else {
            binding.tvCurrentLocation.text = "Seleccionar ubicación"
        }
    }

    private fun detectUserLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
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
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val city = addr.locality ?: addr.subAdminArea ?: ""
                    val zip = addr.postalCode ?: ""
                    
                    withContext(Dispatchers.Main) {
                        val cityChanged = city != buyerCity || zip != buyerPostalCode
                        buyerCity = city
                        buyerPostalCode = zip
                        
                        val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("buyer_city", buyerCity)
                            .putString("buyer_postal_code", buyerPostalCode)
                            .apply()
                        
                        updateLocationUI()
                        if (cityChanged || allStores.isEmpty()) {
                            loadData(isPullToRefresh = false)
                        }
                        Toast.makeText(requireContext(), "Cerca de: $buyerCity 📍", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {}
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
        loadSavedLocation()
        
        if (buyerCity != oldCity || buyerPostalCode != oldZip || allStores.isEmpty()) {
            loadData(isPullToRefresh = false)
        } else {
            loadRecentlyViewed()
        }
    }

    private fun setupCategoryTabs() {
        val tabs = listOf(
            binding.tabAll to "ALL",
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
        if (_binding == null) return
        
        val q = searchQuery.lowercase(Locale.getDefault()).trim()
        
        if (currentMode == "ALL") {
            binding.layoutRecentShopsHeader.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            binding.rvRecentShops.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            binding.layoutRecentlyViewedHeader.visibility = if (q.isEmpty() && recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.rvRecentlyViewed.visibility = if (q.isEmpty() && recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.tvMainFeedTitle.visibility = View.GONE
            
            binding.rvMarketplaceGrid.adapter = marketplaceAdapter
            binding.rvMarketplaceGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            
            val filteredProducts = if (q.isEmpty()) {
                allMarketplaceProducts
            } else {
                allMarketplaceProducts.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.storeName.lowercase(Locale.getDefault()).contains(q) ||
                    it.category.lowercase(Locale.getDefault()).contains(q)
                }
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
            
            binding.rvMarketplaceGrid.adapter = storesAdapter
            binding.rvMarketplaceGrid.layoutManager = LinearLayoutManager(requireContext())
            
            val modeStores = allStores.filter { it.businessType == currentMode }
            val filteredStores = if (q.isEmpty()) {
                modeStores
            } else {
                modeStores.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.address.lowercase(Locale.getDefault()).contains(q) ||
                    it.description.lowercase(Locale.getDefault()).contains(q)
                }
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

        recentlyViewedAdapter = RecentlyViewedAdapter { openProductDetail(it) }
        binding.rvRecentlyViewed.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentlyViewed.adapter = recentlyViewedAdapter
        binding.rvRecentlyViewed.addOnItemTouchListener(itemTouchInterceptor)

        binding.rvMarketplaceGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvMarketplaceGrid.adapter = marketplaceAdapter
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
        try {
            val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
            val json = prefs.getString("recently_viewed", null)
            val type = object : TypeToken<MutableList<MarketplaceProduct>>() {}.type
            val list: MutableList<MarketplaceProduct> = if (json != null) Gson().fromJson(json, type) ?: mutableListOf() else mutableListOf()
            list.removeAll { it.id == product.id }
            list.add(0, product)
            if (list.size > 10) list.removeAt(list.size - 1)
            prefs.edit().putString("recently_viewed", Gson().toJson(list)).apply()
        } catch (e: Exception) {}
    }

    private fun loadRecentlyViewed() {
        try {
            val prefs = requireContext().getSharedPreferences("BuyerPrefs", Context.MODE_PRIVATE)
            val json = prefs.getString("recently_viewed", null)
            val type = object : TypeToken<List<MarketplaceProduct>>() {}.type
            val list: List<MarketplaceProduct> = if (json != null) Gson().fromJson(json, type) ?: emptyList() else emptyList()
            
            if (_binding == null) return
            
            if (list.isEmpty()) {
                binding.layoutRecentlyViewedHeader.visibility = View.GONE
                binding.rvRecentlyViewed.visibility = View.GONE
                recentlyViewedAdapter.updateData(emptyList())
            } else {
                if (currentMode == "ALL" && searchQuery.isEmpty()) {
                    binding.layoutRecentlyViewedHeader.visibility = View.VISIBLE
                    binding.rvRecentlyViewed.visibility = View.VISIBLE
                }
                recentlyViewedAdapter.updateData(list.take(4))
            }
        } catch (e: Exception) {}
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
            if (hasFocus && searchQuery.isNotEmpty()) {
                binding.btnSearchClearMarketplace.visibility = View.VISIBLE
                binding.layoutSearchSuggestions.visibility = View.VISIBLE
                hideMainFeedViews()
            } else if (!hasFocus && searchQuery.isEmpty()) {
                binding.btnSearchClearMarketplace.visibility = View.GONE
                binding.layoutSearchSuggestions.visibility = View.GONE
                restoreMainFeedViews()
            }
        }

        binding.etSearchMarketplace.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                binding.etSearchMarketplace.clearFocus()
                true
            } else false
        }

        binding.btnSearchClearMarketplace.setOnClickListener {
            clearSearchAndHideKeyboard()
        }

        binding.etSearchMarketplace.addTextChangedListener { text ->
            searchQuery = text.toString().trim()
            if (searchQuery.isNotEmpty()) {
                binding.btnSearchClearMarketplace.visibility = View.VISIBLE
                binding.layoutSearchSuggestions.visibility = View.VISIBLE
                hideMainFeedViews()
                updateSearchSuggestions(searchQuery)
            } else {
                binding.btnSearchClearMarketplace.visibility = View.GONE
                binding.layoutSearchSuggestions.visibility = View.GONE
                restoreMainFeedViews()
                filterDataByMode()
            }
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

    private fun updateSearchSuggestions(query: String) {
        val q = query.lowercase(Locale.getDefault())
        val filtered = allMarketplaceProducts.filter {
            it.name.lowercase(Locale.getDefault()).contains(q) ||
            it.storeName.lowercase(Locale.getDefault()).contains(q) ||
            it.category.lowercase(Locale.getDefault()).contains(q)
        }
        searchSuggestionsAdapter.updateData(filtered)
        binding.tvNoSearchResults.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSearchResultsList.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun clearSearchAndHideKeyboard() {
        searchQuery = ""
        binding.etSearchMarketplace.setText("")
        binding.etSearchMarketplace.clearFocus()
        binding.btnSearchClearMarketplace.visibility = View.GONE
        binding.layoutSearchSuggestions.visibility = View.GONE
        hideKeyboard()
        restoreMainFeedViews()
        filterDataByMode()
    }

    private fun hideMainFeedViews() {
        binding.layoutRecentShopsHeader.visibility = View.GONE
        binding.rvRecentShops.visibility = View.GONE
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
            items = newList
            notifyDataSetChanged()
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
            holder.tvDetails.text = "${item.storeName} • S/ ${String.format(Locale.US, "%.2f", item.price)}"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun loadData(isPullToRefresh: Boolean = false) {
        if (!isAdded || _binding == null || isLoadingData) return
        
        isLoadingData = true
        lastLoadTime = System.currentTimeMillis()
        visibleProductLimit = PAGE_SIZE
        visibleStoreLimit = PAGE_SIZE
        
        if (isPullToRefresh) {
            binding.swipeRefreshMarketplace.isRefreshing = true
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. CARGA ULTRA RÁPIDA DESDE EL CACHÉ LOCAL (0ms de espera al entrar)
                if (!isPullToRefresh) {
                    try {
                        val cacheSnap = db.collectionGroup("settings").limit(200).get(Source.CACHE).await()
                        if (_binding != null && cacheSnap != null && !cacheSnap.isEmpty) {
                            processAndRenderStoresAndProducts(cacheSnap, isPullToRefresh = false)
                        }
                    } catch (e: Exception) {
                        // Si no hay caché en la primera instalación, continuará normalmente
                    }
                }

                if (!isNetworkAvailable()) {
                    binding.swipeRefreshMarketplace.isRefreshing = false
                    isLoadingData = false
                    return@launch
                }

                // 2. SINCRONIZACIÓN EN SEGUNDO PLANO CON EL SERVIDOR
                val serverSnap = db.collectionGroup("settings").limit(200).get(Source.DEFAULT).await()
                if (_binding != null && serverSnap != null) {
                    processAndRenderStoresAndProducts(serverSnap, isPullToRefresh = isPullToRefresh)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingData = false
                if (_binding != null) {
                    binding.swipeRefreshMarketplace.isRefreshing = false
                    binding.swipeRefreshMarketplace.isEnabled = false
                }
            }
        }
    }

    private suspend fun processAndRenderStoresAndProducts(storesSnap: QuerySnapshot, isPullToRefresh: Boolean = false) {
        val newStoresList = withContext(Dispatchers.Default) {
            val userDocsMap = mutableMapOf<String, MutableList<DocumentSnapshot>>()
            for (doc in storesSnap.documents) {
                val pathParts = doc.reference.path.split("/")
                if (pathParts.size < 2) continue
                val sId = pathParts[1]
                userDocsMap.getOrPut(sId) { mutableListOf() }.add(doc)
            }

            val filteredStores = mutableListOf<StoreModel>()
            for ((sId, docs) in userDocsMap) {
                val rubroDoc = docs.find { it.id != "business" }
                val businessDoc = docs.find { it.id == "business" }
                val mainDoc = rubroDoc ?: businessDoc ?: continue
                
                if (mainDoc.get("is_online") != true) continue
                
                val sName = mainDoc.getString("business_name") ?: continue
                if (sName.equals("Mi Negocio", ignoreCase = true) || sName.isEmpty()) continue
                
                val sType = mainDoc.getString("business_type") ?: mainDoc.id
                val sCity = mainDoc.getString("business_city") ?: ""
                val sZip = mainDoc.getString("business_postal_code") ?: ""

                if (buyerCity.isNotEmpty() && sCity.isNotEmpty() && !sCity.equals(buyerCity, ignoreCase = true)) continue
                if (buyerPostalCode.isNotEmpty() && sZip.isNotEmpty() && sZip != buyerPostalCode) continue

                filteredStores.add(StoreModel(
                    id = sId, 
                    name = sName, 
                    address = mainDoc.getString("business_address") ?: "Sin dirección", 
                    logoUrl = mainDoc.getString("business_logo_url"), 
                    description = mainDoc.getString("business_description") ?: "", 
                    ownerName = mainDoc.getString("business_owner_name") ?: "", 
                    creationDate = mainDoc.getLong("creation_date") ?: 0L, 
                    businessType = sType,
                    city = sCity,
                    postalCode = sZip
                ))
            }
            filteredStores
        }
        
        allStores.clear()
        allStores.addAll(newStoresList)
        
        if (_binding != null) {
            recentShopsAdapter.updateData(allStores.sortedByDescending { it.creationDate }.take(5))
            loadRecentlyViewed()
        }

        val productResults = coroutineScope {
            allStores.map { store ->
                async(Dispatchers.IO) {
                    try {
                        if (store.id.isBlank()) return@async emptyList<MarketplaceProduct>()
                        val coll = if (store.businessType == "HOTEL") "hotel_rooms" else "inventory"
                        val invSnap = db.collection("users").document(store.id).collection(coll).whereEqualTo("isDeleted", false).limit(100).get().await()
                        invSnap.documents.mapNotNull { pDoc ->
                            if (store.businessType == "HOTEL") {
                                MarketplaceProduct(pDoc.id, "Habitación ${pDoc.getString("number")}", pDoc.getString("type") ?: "Hospedaje", pDoc.getDouble("baseRate") ?: 0.0, pDoc.getString("description") ?: "", pDoc.getString("photoUrl"), store.id, store.name, store.address, store.logoUrl, "HOTEL")
                            } else {
                                MarketplaceProduct(pDoc.id, pDoc.getString("nombre") ?: "Producto", pDoc.getString("categoria") ?: "General", pDoc.getDouble("precioVenta") ?: 0.0, pDoc.getString("descripcion") ?: "", pDoc.getString("photoUrl"), store.id, store.name, store.address, store.logoUrl, store.businessType)
                            }
                        }
                    } catch (e: Exception) { emptyList<MarketplaceProduct>() }
                }
            }.awaitAll()
        }

        if (_binding == null) return
        
        val newProducts = productResults.flatten()
        if (allMarketplaceProducts.isEmpty() || isPullToRefresh) {
            allMarketplaceProducts.clear()
            allMarketplaceProducts.addAll(newProducts.shuffled())
        } else {
            val currentMap = allMarketplaceProducts.associateBy { it.id }.toMutableMap()
            val newOrderedList = mutableListOf<MarketplaceProduct>()
            
            for (oldItem in allMarketplaceProducts) {
                val updated = newProducts.find { it.id == oldItem.id }
                if (updated != null) {
                    newOrderedList.add(updated)
                    currentMap.remove(oldItem.id)
                }
            }
            val remainingNew = newProducts.filter { !allMarketplaceProducts.any { old -> old.id == it.id } }
            newOrderedList.addAll(remainingNew)

            allMarketplaceProducts.clear()
            allMarketplaceProducts.addAll(newOrderedList)
        }
        
        if (isPullToRefresh) {
            marketplaceAdapter.resetAnimation()
            storesAdapter.resetAnimation()
        }
        
        filterDataByMode()
    }

    // --- ADAPTERS ---
    inner class MarketplaceAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<MarketplaceAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        private var lastAnimatedPosition = -1

        fun resetAnimation() {
            lastAnimatedPosition = -1
        }

        fun updateData(newList: List<MarketplaceProduct>) {
            if (items == newList) return
            if (newList.isEmpty()) lastAnimatedPosition = -1
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemMarketplaceGridProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        @Suppress("RecyclerView")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                if (position > lastAnimatedPosition) {
                    lastAnimatedPosition = position
                    root.alpha = 0f
                    root.translationY = 40f
                    root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setStartDelay((position % 10) * 35L)
                        .start()
                } else {
                    root.animate().cancel()
                    root.alpha = 1f
                    root.translationY = 0f
                }

                tvProductName.text = p.name
                tvProductStore.text = p.storeName
                tvProductPrice.text = if (p.storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", p.price)} noche" else "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                if (!p.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivProductImage.context)
                        .load(p.photoUrl)
                        .apply(GlideHelper.getOptions(ivProductImage.context, showPlaceholder = false))
                        .thumbnail(0.1f)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                pbProductLoadingGrid.visibility = View.GONE
                                return false
                            }
                            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                pbProductLoadingGrid.visibility = View.GONE
                                return false
                            }
                        })
                        .into(ivProductImage)
                } else {
                    pbProductLoadingGrid.visibility = View.GONE
                    ivProductImage.setImageResource(R.drawable.gomezmart_icon)
                }
                
                if (!p.storeLogoUrl.isNullOrEmpty()) {
                    Glide.with(ivProductStoreLogo.context)
                        .load(p.storeLogoUrl)
                        .apply(GlideHelper.getOptions(ivProductStoreLogo.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivProductStoreLogo)
                } else {
                    ivProductStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                }
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentlyViewedAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<RecentlyViewedAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        private var lastAnimatedPosition = -1

        fun updateData(newList: List<MarketplaceProduct>) {
            if (items == newList) return
            if (newList.isEmpty()) lastAnimatedPosition = -1
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
        
        @Suppress("RecyclerView")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                if (position > lastAnimatedPosition) {
                    lastAnimatedPosition = position
                    root.alpha = 0f
                    root.translationX = 40f
                    root.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(350)
                        .setStartDelay(position * 40L)
                        .start()
                } else {
                    root.animate().cancel()
                    root.alpha = 1f
                    root.translationX = 0f
                }

                tvRecentProductName.text = p.name
                tvRecentProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                
                if (!p.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivRecentProductImage.context)
                        .load(p.photoUrl)
                        .apply(GlideHelper.getOptions(ivRecentProductImage.context, showPlaceholder = false))
                        .centerCrop()
                        .into(ivRecentProductImage)
                } else {
                    ivRecentProductImage.setImageResource(R.drawable.gomezmart_icon)
                }
                    
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class StoreAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<StoreAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        private var lastAnimatedPosition = -1

        fun resetAnimation() {
            lastAnimatedPosition = -1
        }

        fun setStores(newList: List<StoreModel>) {
            if (items == newList) return
            if (newList.isEmpty()) lastAnimatedPosition = -1
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
        
        @Suppress("RecyclerView")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                if (position > lastAnimatedPosition) {
                    lastAnimatedPosition = position
                    root.alpha = 0f
                    root.translationY = 40f
                    root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setStartDelay((position % 10) * 35L)
                        .start()
                } else {
                    root.animate().cancel()
                    root.alpha = 1f
                    root.translationY = 0f
                }

                tvStoreName.text = s.name
                tvStoreCategory.text = if (s.businessType == "HOTEL") "Hospedaje y Turismo" else "Tienda de Productos"
                tvStoreAddress.text = "📍 ${s.address}"
                if (!s.logoUrl.isNullOrEmpty()) {
                    Glide.with(ivStoreLogo.context)
                        .load(s.logoUrl)
                        .apply(GlideHelper.getOptions(ivStoreLogo.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivStoreLogo)
                } else {
                    ivStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                }
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentShopsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<RecentShopsAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        private var lastAnimatedPosition = -1

        fun updateData(newList: List<StoreModel>) {
            if (items == newList) return
            if (newList.isEmpty()) lastAnimatedPosition = -1
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
        
        @Suppress("RecyclerView")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                if (position > lastAnimatedPosition) {
                    lastAnimatedPosition = position
                    root.alpha = 0f
                    root.translationY = 30f
                    root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setStartDelay(position * 40L)
                        .start()
                } else {
                    root.animate().cancel()
                    root.alpha = 1f
                    root.translationY = 0f
                }

                tvStoreNameSmall.text = s.name
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvStoreDateSmall.text = "Agregada: ${if (s.creationDate > 0) sdf.format(Date(s.creationDate)) else "--/--"}"
                if (!s.logoUrl.isNullOrEmpty()) {
                    pbStoreLogoSmallLoading.visibility = View.GONE
                    Glide.with(ivStoreLogoSmall.context)
                        .load(s.logoUrl)
                        .apply(GlideHelper.getOptions(ivStoreLogoSmall.context, showPlaceholder = false))
                        .circleCrop()
                        .into(ivStoreLogoSmall)
                } else {
                    pbStoreLogoSmallLoading.visibility = View.GONE
                    ivStoreLogoSmall.setImageResource(R.drawable.gomezmart_icon)
                }
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}