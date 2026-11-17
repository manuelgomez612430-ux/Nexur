package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gomezmart.app.data.MarketplaceProduct
import com.gomezmart.app.databinding.ActivityViewedHistoryBinding
import com.gomezmart.app.databinding.ItemViewedHistoryListBinding
import com.gomezmart.app.util.GlideHelper
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

    override fun onResume() {
        super.onResume()
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
            adapter.updateData(ArrayList(historyList))
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
        startActivityWithFade(intent)
    }

    inner class ViewedHistoryAdapter(
        private val onItemClick: (MarketplaceProduct) -> Unit,
        private val onDeleteClick: (MarketplaceProduct) -> Unit
    ) : RecyclerView.Adapter<ViewedHistoryAdapter.ViewHolder>() {
        
        private var items = listOf<MarketplaceProduct>()

        fun updateData(newList: List<MarketplaceProduct>) {
            val oldItems = items
            val newItems = ArrayList(newList)

            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = oldItems[oldPos].id == newItems[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = oldItems[oldPos] == newItems[newPos]
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val b: ItemViewedHistoryListBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            ViewHolder(ItemViewedHistoryListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvHistoryProductName.text = p.name
                tvHistoryProductStore.text = p.storeName.ifEmpty { "Tienda GomezMart" }
                
                if (p.storeType == "HOTEL") {
                    tvHistoryProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)} la noche"
                } else {
                    tvHistoryProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                }
                
                if (!p.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivHistoryProductImage.context)
                        .load(p.photoUrl)
                        .apply(GlideHelper.getOptions(ivHistoryProductImage.context, showPlaceholder = false))
                        .centerCrop()
                        .into(ivHistoryProductImage)
                } else {
                    ivHistoryProductImage.setImageResource(R.drawable.gomezmart_icon)
                }

                root.setOnClickListener { onItemClick(p) }
                btnDeleteFromHistory.setOnClickListener { onDeleteClick(p) }
            }
        }

        override fun getItemCount() = items.size
    }
}
