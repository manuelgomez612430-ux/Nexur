package com.naxor.app.fragment

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.naxor.app.*
import com.naxor.app.data.MarketplaceProduct
import com.naxor.app.data.StoreModel
import com.naxor.app.databinding.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class BuyerMarketplaceFragment : Fragment() {

    private var _binding: FragmentBuyerMarketplaceBinding? = null
    private val binding get() = _binding!!
    private val db = FirebaseFirestore.getInstance()
    
    private lateinit var marketplaceAdapter: MarketplaceAdapter
    private lateinit var storesAdapter: StoreAdapter
    private lateinit var recentlyViewedAdapter: RecentlyViewedAdapter
    private lateinit var recentShopsAdapter: RecentShopsAdapter
    
    private var allMarketplaceProducts = mutableListOf<MarketplaceProduct>()
    private var allStores = mutableListOf<StoreModel>()
    private var currentMode = "ALL"
    
    private var lastLoadTime: Long = 0
    private val LOAD_COOLDOWN = 300000 // 5 minutos en milisegundos

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerMarketplaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupCategoryTabs()
        setupListeners()
        
        // Cargar datos solo si es la primera vez
        if (allStores.isEmpty()) {
            loadData()
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecentlyViewed()
        
        // Solo recargar automáticamente si ha pasado mucho tiempo
        if (System.currentTimeMillis() - lastLoadTime > LOAD_COOLDOWN) {
            loadData()
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
        if (currentMode == "ALL") {
            binding.layoutRecentShopsHeader.visibility = View.VISIBLE
            binding.rvRecentShops.visibility = View.VISIBLE
            binding.layoutRecentlyViewedHeader.visibility = if (recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.rvRecentlyViewed.visibility = if (recentlyViewedAdapter.itemCount > 0) View.VISIBLE else View.GONE
            binding.tvMainFeedTitle.visibility = View.VISIBLE
            binding.rvMarketplaceGrid.adapter = marketplaceAdapter
            binding.rvMarketplaceGrid.layoutManager = GridLayoutManager(requireContext(), 2)
            marketplaceAdapter.updateData(allMarketplaceProducts)
        } else {
            binding.layoutRecentShopsHeader.visibility = View.GONE
            binding.rvRecentShops.visibility = View.GONE
            binding.layoutRecentlyViewedHeader.visibility = View.GONE
            binding.rvRecentlyViewed.visibility = View.GONE
            binding.tvMainFeedTitle.visibility = View.GONE
            binding.rvMarketplaceGrid.adapter = storesAdapter
            binding.rvMarketplaceGrid.layoutManager = LinearLayoutManager(requireContext())
            storesAdapter.setStores(allStores.filter { it.businessType == currentMode })
        }
    }

    private fun setupRecyclerViews() {
        marketplaceAdapter = MarketplaceAdapter { openProductDetail(it) }
        storesAdapter = StoreAdapter { 
            val intent = Intent(requireContext(), StoreProfileActivity::class.java).apply {
                putExtra("STORE_ID", it.id)
                putExtra("STORE_NAME", it.name)
                putExtra("FORCE_RUBRO", it.businessType)
            }
            startActivity(intent)
        }
        recentShopsAdapter = RecentShopsAdapter { 
            val intent = Intent(requireContext(), StoreProfileActivity::class.java).apply {
                putExtra("STORE_ID", it.id)
                putExtra("STORE_NAME", it.name)
                putExtra("FORCE_RUBRO", it.businessType)
            }
            startActivity(intent)
        }
        binding.rvRecentShops.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentShops.adapter = recentShopsAdapter

        recentlyViewedAdapter = RecentlyViewedAdapter { openProductDetail(it) }
        binding.rvRecentlyViewed.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentlyViewed.adapter = recentlyViewedAdapter

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
        startActivity(intent)
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
            } else {
                if (currentMode == "ALL") {
                    binding.layoutRecentlyViewedHeader.visibility = View.VISIBLE
                    binding.rvRecentlyViewed.visibility = View.VISIBLE
                }
                recentlyViewedAdapter.updateData(list)
            }
        } catch (e: Exception) {}
    }

    private fun setupListeners() {
        binding.swipeRefreshMarketplace.setOnRefreshListener { loadData() }
        binding.btnOpenMenu.setOnClickListener { (activity as? BuyerHomeActivity)?.openDrawer() }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun loadData() {
        if (!isAdded || _binding == null) return
        
        if (!isNetworkAvailable()) {
            binding.swipeRefreshMarketplace.isRefreshing = false
            Toast.makeText(requireContext(), "Sin conexión a Internet 📶", Toast.LENGTH_SHORT).show()
            return
        }

        binding.swipeRefreshMarketplace.isRefreshing = true
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val storesSnap = db.collectionGroup("settings").limit(100).get().await()
                val newStoresMap = mutableMapOf<String, StoreModel>()
                
                if (_binding == null) return@launch

                for (doc in storesSnap.documents) {
                    if (doc.get("is_online") != true) continue
                    val sName = doc.getString("business_name") ?: continue
                    if (sName.equals("Mi Negocio", ignoreCase = true) || sName.isEmpty()) continue
                    
                    val pathParts = doc.reference.path.split("/")
                    if (pathParts.size < 2) continue
                    val sId = pathParts[1]
                    
                    val sType = doc.getString("business_type") ?: doc.id
                    val store = StoreModel(
                        id = sId, 
                        name = sName, 
                        address = doc.getString("business_address") ?: "Sin dirección", 
                        logoUrl = doc.getString("business_logo_url"), 
                        description = doc.getString("business_description") ?: "", 
                        ownerName = doc.getString("business_owner_name") ?: "", 
                        creationDate = doc.getLong("creation_date") ?: 0L, 
                        businessType = sType
                    )

                    // Evitar duplicados por usuario: 
                    // Priorizamos el documento que NO sea el genérico 'business'
                    if (!newStoresMap.containsKey(sId) || doc.id != "business") {
                        newStoresMap[sId] = store
                    }
                }
                
                allStores.clear()
                allStores.addAll(newStoresMap.values)
                
                if (_binding != null) {
                    recentShopsAdapter.updateData(allStores.sortedByDescending { it.creationDate }.take(5))
                }

                val productResults = coroutineScope {
                    allStores.take(15).map { store ->
                        async(Dispatchers.IO) {
                            try {
                                val coll = if (store.businessType == "HOTEL") "hotel_rooms" else "inventory"
                                val invSnap = db.collection("users").document(store.id).collection(coll).whereEqualTo("isDeleted", false).limit(20).get().await()
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

                if (_binding == null) return@launch
                
                allMarketplaceProducts.clear()
                productResults.forEach { allMarketplaceProducts.addAll(it) }
                
                filterDataByMode()
                lastLoadTime = System.currentTimeMillis()
                binding.swipeRefreshMarketplace.isRefreshing = false
                
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.swipeRefreshMarketplace.isRefreshing = false
                }
            }
        }
    }

    // --- ADAPTERS ---
    inner class MarketplaceAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<MarketplaceAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        fun updateData(list: List<MarketplaceProduct>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemMarketplaceGridProductBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemMarketplaceGridProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvProductName.text = p.name
                tvProductStore.text = p.storeName
                tvProductPrice.text = if (p.storeType == "HOTEL") "S/ ${String.format(Locale.US, "%.2f", p.price)} noche" else "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                if (!p.photoUrl.isNullOrEmpty()) Glide.with(ivProductImage.context).load(p.photoUrl).into(ivProductImage) else ivProductImage.setImageResource(R.drawable.gomezmart_icon)
                if (!p.storeLogoUrl.isNullOrEmpty()) Glide.with(ivProductStoreLogo.context).load(p.storeLogoUrl).into(ivProductStoreLogo) else ivProductStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentlyViewedAdapter(private val onClick: (MarketplaceProduct) -> Unit) : RecyclerView.Adapter<RecentlyViewedAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        fun updateData(list: List<MarketplaceProduct>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemRecentlyViewedBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemRecentlyViewedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvRecentProductName.text = p.name
                tvRecentProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                if (!p.photoUrl.isNullOrEmpty()) Glide.with(ivRecentProductImage.context).load(p.photoUrl).into(ivRecentProductImage) else ivRecentProductImage.setImageResource(R.drawable.gomezmart_icon)
                root.setOnClickListener { onClick(p) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class StoreAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<StoreAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        fun setStores(list: List<StoreModel>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemStoreBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                tvStoreName.text = s.name
                tvStoreCategory.text = if (s.businessType == "HOTEL") "Hospedaje y Turismo" else "Tienda de Productos"
                tvStoreAddress.text = "📍 ${s.address}"
                if (!s.logoUrl.isNullOrEmpty()) Glide.with(ivStoreLogo.context).load(s.logoUrl).into(ivStoreLogo) else ivStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                root.setOnClickListener { onClick(s) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class RecentShopsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<RecentShopsAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        fun updateData(list: List<StoreModel>) { items = list; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemStoreHorizontalBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemStoreHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            with(holder.b) {
                tvStoreNameSmall.text = s.name
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvStoreDateSmall.text = "Agregada: ${if (s.creationDate > 0) sdf.format(Date(s.creationDate)) else "--/--"}"
                if (!s.logoUrl.isNullOrEmpty()) Glide.with(ivStoreLogoSmall.context).load(s.logoUrl).into(ivStoreLogoSmall) else ivStoreLogoSmall.setImageResource(R.drawable.gomezmart_icon)
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
