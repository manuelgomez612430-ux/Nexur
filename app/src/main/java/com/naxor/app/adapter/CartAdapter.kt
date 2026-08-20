package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ItemCartProductBinding
import java.util.Locale

class CartAdapter(
    private var items: MutableList<SaleEntity>,
    private var products: MutableList<com.naxor.app.data.ProductEntity?>,
    private val onQtyChanged: () -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onIncrease: (Int) -> Unit,
    private val onDecrease: (Int) -> Unit
) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCartProductBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCartProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val product = products.getOrNull(position)
        
        with(holder.binding) {
            tvCartProdName.text = item.nombreProducto
            tvCartProdPrice.text = String.format(Locale.getDefault(), "S/ %.2f c/u", item.precioVenta)
            tvCartQty.text = item.cantidad.toString()
            tvCartSubtotal.text = String.format(Locale.getDefault(), "S/ %.2f", item.total)

            // Lógica de Advertencia de Stock (Sincronizada con el producto real)
            if (product != null) {
                when {
                    item.cantidad > product.stock -> {
                        tvStockWarning.visibility = android.view.View.VISIBLE
                        tvStockWarning.text = "⚠️ Superaste el stock disponible (${product.stock})"
                        tvStockWarning.setTextColor(android.graphics.Color.RED)
                    }
                    item.cantidad == product.stock -> {
                        tvStockWarning.visibility = android.view.View.VISIBLE
                        tvStockWarning.text = "⚠️ Stock al límite"
                        tvStockWarning.setTextColor(android.graphics.Color.parseColor("#F59E0B")) // Amber
                    }
                    else -> {
                        tvStockWarning.visibility = android.view.View.GONE
                    }
                }
            } else {
                tvStockWarning.visibility = android.view.View.GONE
            }

            btnCartPlus.setOnClickListener {
                onIncrease(holder.adapterPosition)
            }

            btnCartMinus.setOnClickListener {
                onDecrease(holder.adapterPosition)
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<SaleEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = items[oldPos].productId == newList[newPos].productId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newList[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items.clear()
        items.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }
}

