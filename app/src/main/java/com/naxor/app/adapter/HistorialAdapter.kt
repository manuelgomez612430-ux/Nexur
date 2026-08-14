package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.CalculationEntity
import com.naxor.app.databinding.ItemHistorialBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialAdapter(
    private var items: List<CalculationEntity>,
    private val onShare: (CalculationEntity) -> Unit,
    private val onLongClick: (CalculationEntity) -> Unit
) : RecyclerView.Adapter<HistorialAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemHistorialBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistorialBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvHistorialNombre.text = item.nombre
            tvHistorialCategoria.text = item.categoria
            tvHistorialPrecio.text = String.format(Locale.getDefault(), "S/ %.2f", item.precioSugerido)
            tvHistorialUnidades.text = "${item.unidades} uds"
            
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            tvHistorialFecha.text = sdf.format(Date(item.timestamp))

            btnShareTicket.setOnClickListener {
                onShare(item)
            }

            root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<CalculationEntity>) {
        items = newList
        notifyDataSetChanged()
    }
}

