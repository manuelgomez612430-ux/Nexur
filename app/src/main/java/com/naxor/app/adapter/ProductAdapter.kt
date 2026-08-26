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
    private val onStockQuickChange: (ProductEntity, Int) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val selectedIds = mutableSetOf<String>()
    private var isMultiSelectMode = false
    private var businessType: String = "PRODUCTS"

    fun setBusinessType(type: String) {
        this.businessType = type
        notifyItemRangeChanged(0, itemCount)
    }

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
                    holder.binding.tvProdItemStock.text = item.stock.toString()
                    updateStockVisuals(holder.binding, item.stock)
                    updateUtility(holder.binding, item)
                }
                if (payload == "SELECTION") {
                    holder.binding.cbProdSelected.isChecked = selectedIds.contains(item.id)
                }
            }
            return
        }

        with(holder.binding) {
            val context = root.context
            
            // 1. Mostrar Foto (Optimizado)
            if (!item.photoPath.isNullOrEmpty()) {
                Glide.with(context)
                    .load(item.photoPath)
                    .override(200, 200)
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
            
            updateUtility(this, item)

            // 3. Stock y Precios
            if (businessType == "SERVICES") {
                layoutStockControls.visibility = View.GONE
                tvProdItemPrecioCosto.visibility = View.GONE
            } else {
                layoutStockControls.visibility = View.VISIBLE
                tvProdItemPrecioCosto.visibility = View.VISIBLE
                tvProdItemStock.text = item.stock.toString()
                updateStockVisuals(this, item.stock)
                
                // Asumiendo que precioCosto es el costo unitario según ProductEntity.java constructor
                tvProdItemPrecioCosto.text = "Costo: S/ ${String.format(Locale.US, "%.2f", item.precioCosto)}"
            }

            tvProdItemPrecioVenta.text = "S/ ${String.format(Locale.US, "%.2f", item.precioVenta)}"

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

            btnProdItemPlus.setOnClickListener { onStockQuickChange(item, 1) }
            btnProdItemMinus.setOnClickListener { if (item.stock > 0) onStockQuickChange(item, -1) }

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

    private fun updateUtility(binding: ItemProductoBinding, item: ProductEntity) {
        if (item.precioVenta > 0) {
            val ganancia = item.precioVenta - item.precioCosto
            val porcentaje = (ganancia / item.precioVenta) * 100
            binding.tvProdItemUtility.text = String.format(Locale.US, "+%.0f%% utilidad (S/ %.2f)", porcentaje, ganancia)
            binding.tvProdItemUtility.visibility = View.VISIBLE
        } else {
            binding.tvProdItemUtility.visibility = View.GONE
        }
    }

    private fun updateStockVisuals(binding: ItemProductoBinding, stock: Int) {
        val context = binding.root.context
        val (bgColor, textColor) = when {
            stock <= 0 -> Pair(R.color.red_700, R.color.white)
            stock <= 5 -> Pair(R.color.orange_50, R.color.orange_600)
            else -> Pair(R.color.emerald_50, R.color.emerald_600)
        }
        binding.cardStockStatus.setCardBackgroundColor(context.getColor(bgColor))
        binding.tvProdItemStock.setTextColor(context.getColor(textColor))
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

