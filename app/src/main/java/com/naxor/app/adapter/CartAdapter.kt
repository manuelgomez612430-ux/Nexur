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
    private val onQtyChanged: () -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCartProductBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCartProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvCartProdName.text = item.nombreProducto
            tvCartProdPrice.text = String.format(Locale.getDefault(), "S/ %.2f c/u", item.precioVenta)
            tvCartQty.text = item.cantidad.toString()
            tvCartSubtotal.text = String.format(Locale.getDefault(), "S/ %.2f", item.total)

            btnCartPlus.setOnClickListener {
                item.cantidad++
                item.total = item.cantidad * item.precioVenta
                notifyItemChanged(holder.adapterPosition)
                onQtyChanged()
            }

            btnCartMinus.setOnClickListener {
                if (item.cantidad > 1) {
                    item.cantidad--
                    item.total = item.cantidad * item.precioVenta
                    notifyItemChanged(holder.adapterPosition)
                    onQtyChanged()
                } else {
                    onRemove(holder.adapterPosition)
                }
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

