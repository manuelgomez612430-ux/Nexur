package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.naxor.app.data.MarketplaceProduct
import com.naxor.app.databinding.ActivityViewedHistoryBinding
import com.naxor.app.databinding.ItemViewedHistoryListBinding
import java.util.*

class ViewedHistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityViewedHistoryBinding
    private lateinit var adapter: ViewedHistoryAdapter
    private var historyList = mutableListOf<MarketplaceProduct>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewedHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadHistory()
    }

    private fun setupToolbar() {
        binding.toolbarHistory.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ViewedHistoryAdapter(
            onItemClick = { openProductDetail(it) },
            onDeleteClick = { deleteFromHistory(it) }
        )
        binding.rvViewedHistory.layoutManager = LinearLayoutManager(this)
        binding.rvViewedHistory.adapter = adapter
    }

    private fun loadHistory() {
        try {
            val prefs = getSharedPreferences("BuyerPrefs", MODE_PRIVATE)
            val json = prefs.getString("recently_viewed", null)
            val type = object : TypeToken<MutableList<MarketplaceProduct>>() {}.type
            historyList = if (json != null) Gson().fromJson(json, type) ?: mutableListOf() else mutableListOf()
            
            updateUI()
        } catch (e: Exception) {
            historyList = mutableListOf()
            updateUI()
        }
    }

    private fun updateUI() {
        if (historyList.isEmpty()) {
            binding.rvViewedHistory.visibility = View.GONE
            binding.layoutEmptyHistory.visibility = View.VISIBLE
        } else {
            binding.rvViewedHistory.visibility = View.VISIBLE
            binding.layoutEmptyHistory.visibility = View.GONE
            adapter.updateData(historyList)
        }
    }

    private fun deleteFromHistory(product: MarketplaceProduct) {
        historyList.removeAll { it.id == product.id }
        saveHistory()
        updateUI()
        Toast.makeText(this, "Eliminado del historial", Toast.LENGTH_SHORT).show()
    }

    private fun saveHistory() {
        val prefs = getSharedPreferences("BuyerPrefs", MODE_PRIVATE)
        prefs.edit().putString("recently_viewed", Gson().toJson(historyList)).apply()
    }

    private fun openProductDetail(p: MarketplaceProduct) {
        val intent = Intent(this, ProductDetailActivity::class.java).apply {
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

    inner class ViewedHistoryAdapter(
        private val onItemClick: (MarketplaceProduct) -> Unit,
        private val onDeleteClick: (MarketplaceProduct) -> Unit
    ) : RecyclerView.Adapter<ViewedHistoryAdapter.ViewHolder>() {
        
        private var items = listOf<MarketplaceProduct>()

        fun updateData(list: List<MarketplaceProduct>) {
            items = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemViewedHistoryListBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            ViewHolder(ItemViewedHistoryListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvHistoryProductName.text = p.name
                tvHistoryProductStore.text = p.storeName
                tvHistoryProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                
                Glide.with(ivHistoryProductImage.context)
                    .load(p.photoUrl)
                    .placeholder(R.drawable.gomezmart_icon)
                    .error(R.drawable.gomezmart_icon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(ivHistoryProductImage)

                root.setOnClickListener { onItemClick(p) }
                btnDeleteFromHistory.setOnClickListener { onDeleteClick(p) }
            }
        }

        override fun getItemCount() = items.size
    }
}