package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.naxor.app.R
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ItemProductoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProductAdapter(
    private var items: List<ProductEntity>,
    private var isEditorMode: Boolean = false,
    private val onEdit: (ProductEntity) -> Unit,
    private val onViewLabel: (ProductEntity) -> Unit
) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    class ViewHolder(val binding: ItemProductoBinding) : RecyclerView.ViewHolder(binding.root)

    fun setEditorMode(enabled: Boolean) {
        this.isEditorMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProductoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            val context = root.context
            
            // 1. Mostrar Foto (Optimizado con Glide)
            if (!item.photoPath.isNullOrEmpty()) {
                Glide.with(context)
                    .load(item.photoPath)
                    .transform(CenterCrop(), RoundedCorners(12))
                    .placeholder(android.R.drawable.ic_menu_camera)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(ivProdItemPhoto)
                ivProdItemPhoto.alpha = 1.0f
            } else {
                ivProdItemPhoto.setImageResource(android.R.drawable.ic_menu_camera)
                ivProdItemPhoto.alpha = 0.3f
            }

            // 2. InformaciÃ³n Central
            tvProdItemNombre.text = item.nombre
            tvProdItemCategoria.text = item.categoria
            
            val codigoReal = item.codigo ?: ""
            tvProdItemCodigoSub.text = when {
                codigoReal.isBlank() -> "---"
                codigoReal.contains(",") -> codigoReal.split(",")[0].take(4) + "..."
                codigoReal.length > 8 -> codigoReal.take(4) + "..."
                else -> codigoReal
            }

            // 3. Fecha e IntegraciÃ³n (CÃ¡lculo preciso de calendario)
            val dias = getDaysDifference(item.timestamp)
            val textoDias = when {
                dias == 0L -> "Hoy"
                dias == 1L -> "Ayer"
                else -> "Hace $dias d"
            }
            tvProdItemIntegration.text = "Ingreso: ${sdf.format(Date(item.timestamp))} ($textoDias)"

            // 4. Stock
            tvProdItemStock.text = item.stock.toString()

            // 5. Precios
            tvProdItemPrecioVenta.text = String.format(Locale.getDefault(), "S/ %.2f", item.precioVenta)
            val costoUnitario = if (item.stock > 0) item.precioCosto / item.stock else 0.0
            tvProdItemPrecioCosto.text = String.format(Locale.getDefault(), "C: S/ %.2f", costoUnitario)

            // Alertas visuales de stock
            if (item.stock <= 0) {
                tvProdItemStock.setTextColor(context.getColor(R.color.red_600))
                tvProdItemStock.text = "X"
            } else if (item.stock <= 5) {
                tvProdItemStock.setTextColor(context.getColor(R.color.red_600))
            } else {
                tvProdItemStock.setTextColor(context.getColor(R.color.sky_600))
            }

            // --- INTERACCIONES ---
            root.setOnClickListener { onViewLabel(item) }
            root.setOnLongClickListener { onEdit(item); true }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<ProductEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = items[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newList[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList
        diffResult.dispatchUpdatesTo(this)
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

