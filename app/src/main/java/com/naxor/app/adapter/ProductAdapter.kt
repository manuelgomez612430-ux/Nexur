package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.naxor.app.R
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ItemProductoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProductAdapter(
    private var isEditorMode: Boolean = false,
    private val onEdit: (ProductEntity) -> Unit,
    private val onViewLabel: (ProductEntity) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val selectedIds = mutableSetOf<String>()
    private var isMultiSelectMode = false

    private val differCallback = object : DiffUtil.ItemCallback<ProductEntity>() {
        override fun areItemsTheSame(oldItem: ProductEntity, newItem: ProductEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ProductEntity, newItem: ProductEntity): Boolean {
            return oldItem.id == newItem.id && 
                   (oldItem.nombre ?: "") == (newItem.nombre ?: "") &&
                   oldItem.stock == newItem.stock &&
                   oldItem.precioVenta == newItem.precioVenta &&
                   (oldItem.categoria ?: "") == (newItem.categoria ?: "") &&
                   (oldItem.photoPath ?: "") == (newItem.photoPath ?: "")
        }
        override fun getChangePayload(oldItem: ProductEntity, newItem: ProductEntity): Any? {
            return if (oldItem.stock != newItem.stock) "STOCK" else null
        }
    }
    
    private val differ = androidx.recyclerview.widget.AsyncListDiffer(this, differCallback)

    class ViewHolder(val binding: ItemProductoBinding) : RecyclerView.ViewHolder(binding.root)

    fun setEditorMode(enabled: Boolean) {
        if (this.isEditorMode == enabled) return
        this.isEditorMode = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    fun setMultiSelectMode(enabled: Boolean) {
        if (this.isMultiSelectMode == enabled) return
        this.isMultiSelectMode = enabled
        if (!enabled) selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun getSelectedItems(): List<ProductEntity> {
        return differ.currentList.filter { selectedIds.contains(it.id) }
    }

    fun toggleSelection(productId: String, position: Int) {
        if (selectedIds.contains(productId)) {
            selectedIds.remove(productId)
        } else {
            selectedIds.add(productId)
        }
        notifyItemChanged(position, "SELECTION")
        onSelectionChanged?.invoke(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProductoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val currentItems = differ.currentList
        if (position < 0 || position >= currentItems.size) return
        val item = currentItems[position]
        
        if (payloads.isNotEmpty()) {
            for (payload in payloads) {
                if (payload == "STOCK") {
                    holder.binding.tvProdItemStock.text = if (item.stock <= 0) "X" else item.stock.toString()
                    updateStockColor(holder.binding.tvProdItemStock, item.stock)
                }
                if (payload == "SELECTION") {
                    holder.binding.cbProdSelected.isChecked = selectedIds.contains(item.id)
                }
            }
            return
        }

        with(holder.binding) {
            val context = root.context
            
            // 1. Mostrar Foto (Optimizado para scroll masivo)
            if (!item.photoPath.isNullOrEmpty()) {
                Glide.with(context)
                    .load(item.photoPath)
                    .override(150, 150) // Miniaturas más pequeñas para más FPS
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .transform(CenterCrop(), RoundedCorners(12))
                    .into(ivProdItemPhoto)
                ivProdItemPhoto.alpha = 1.0f
            } else {
                ivProdItemPhoto.setImageResource(android.R.drawable.ic_menu_camera)
                ivProdItemPhoto.alpha = 0.2f
            }

            // 2. Información Central
            tvProdItemNombre.text = item.nombre
            tvProdItemCategoria.text = item.categoria
            
            val codigoReal = item.codigo ?: ""
            tvProdItemCodigoSub.text = if (codigoReal.length > 6) codigoReal.take(6) + ".." else if (codigoReal.isEmpty()) "---" else codigoReal

            // 3. Fecha (Formato simple)
            tvProdItemIntegration.text = sdf.format(Date(item.timestamp))

            // 4. Stock y Precios
            tvProdItemStock.text = if (item.stock <= 0) "X" else item.stock.toString()
            updateStockColor(tvProdItemStock, item.stock)

            tvProdItemPrecioVenta.text = "S/ ${String.format(Locale.US, "%.2f", item.precioVenta)}"
            val costU = if (item.stock > 0) item.precioCosto / item.stock else 0.0
            tvProdItemPrecioCosto.text = "C: S/ ${String.format(Locale.US, "%.2f", costU)}"

            // --- INTERACCIONES ---
            cbProdSelected.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            cbProdSelected.isChecked = selectedIds.contains(item.id)

            root.setOnClickListener {
                when {
                    isMultiSelectMode -> toggleSelection(item.id, holder.adapterPosition)
                    isEditorMode -> onEdit(item)
                    else -> onViewLabel(item)
                }
            }

            root.setOnLongClickListener {
                if (!isMultiSelectMode && !isEditorMode) {
                    onSelectionChanged?.invoke(-1) 
                    toggleSelection(item.id, holder.adapterPosition)
                } else {
                    onEdit(item)
                }
                true
            }
        }
    }

    private fun updateStockColor(textView: android.widget.TextView, stock: Int) {
        val color = if (stock <= 0) R.color.red_600 else if (stock <= 5) R.color.red_600 else R.color.sky_600
        textView.setTextColor(textView.context.getColor(color))
    }

    override fun getItemCount() = differ.currentList.size

    fun updateList(newList: List<ProductEntity>) {
        differ.submitList(newList)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.binding.ivProdItemPhoto.context).clear(holder.binding.ivProdItemPhoto)
    }

    private fun getDaysDifference(timestamp: Long): Long {
        val calHoy = java.util.Calendar.getInstance()
        calHoy.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calHoy.set(java.util.Calendar.MINUTE, 0)
        calHoy.set(java.util.Calendar.SECOND, 0)
        calHoy.set(java.util.Calendar.MILLISECOND, 0)

        val calItem = java.util.Calendar.getInstance()
        calItem.timeInMillis = timestamp
        calItem.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calItem.set(java.util.Calendar.MINUTE, 0)
        calItem.set(java.util.Calendar.SECOND, 0)
        calItem.set(java.util.Calendar.MILLISECOND, 0)

        val diff = calHoy.timeInMillis - calItem.timeInMillis
        return diff / (1000 * 60 * 60 * 24)
    }
}

