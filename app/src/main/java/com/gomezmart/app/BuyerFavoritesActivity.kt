package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gomezmart.app.data.MarketplaceProduct
import com.gomezmart.app.databinding.ActivityBuyerFavoritesBinding
import com.gomezmart.app.databinding.ItemFavoriteProductBinding
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.GlideHelper
import java.util.Locale

class BuyerFavoritesActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerFavoritesBinding
    private lateinit var adapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar Insets
        applyWindowInsets(binding.toolbarFavorites, applyTop = true, applyBottom = false)

        setupToolbar()
        setupRecyclerView()
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun setupToolbar() {
        binding.btnBackFavorites.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = FavoritesAdapter(
            onClick = { openProductDetail(it) },
            onRemove = { product ->
                FavoritesManager.toggleFavorite(this, product)
                loadFavorites()
            }
        )
        binding.rvFavorites.layoutManager = GridLayoutManager(this, 2)
        binding.rvFavorites.adapter = adapter
    }

    private fun loadFavorites() {
        val list = FavoritesManager.getFavorites(this)
        if (list.isEmpty()) {
            binding.rvFavorites.visibility = View.GONE
            binding.layoutEmptyFavorites.visibility = View.VISIBLE
        } else {
            binding.rvFavorites.visibility = View.VISIBLE
            binding.layoutEmptyFavorites.visibility = View.GONE
            adapter.submitList(list)
        }
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

    inner class FavoritesAdapter(
        private val onClick: (MarketplaceProduct) -> Unit,
        private val onRemove: (MarketplaceProduct) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {
        private var items = listOf<MarketplaceProduct>()
        
        fun submitList(newList: List<MarketplaceProduct>) {
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

        inner class ViewHolder(val b: ItemFavoriteProductBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemFavoriteProductBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = items[position]
            with(holder.b) {
                tvFavProductName.text = p.name
                tvFavProductPrice.text = "S/ ${String.format(Locale.US, "%.2f", p.price)}"
                
                if (!p.photoUrl.isNullOrEmpty()) {
                    Glide.with(ivFavProductImage.context)
                        .load(p.photoUrl)
                        .apply(GlideHelper.getOptions(ivFavProductImage.context, showPlaceholder = false))
                        .centerCrop()
                        .into(ivFavProductImage)
                } else {
                    ivFavProductImage.setImageResource(R.drawable.gomezmart_icon)
                }

                btnRemoveFavorite.text = "⭐"
                btnRemoveFavorite.setOnClickListener { onRemove(p) }
                btnFavBuy.setOnClickListener { onClick(p) }
                root.setOnClickListener { onClick(p) }
            }
        }

        override fun getItemCount() = items.size
    }
}