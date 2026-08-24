package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.data.HotelToolEntity
import com.naxor.app.databinding.ItemHotelToolBinding
import java.util.concurrent.TimeUnit

class HotelToolAdapter(
    private val onRetire: (HotelToolEntity) -> Unit,
    private val onRenew: (HotelToolEntity) -> Unit
) : ListAdapter<HotelToolEntity, HotelToolAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemHotelToolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemHotelToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tool = getItem(position)
        with(holder.binding) {
            tvToolName.text = tool.name
            tvToolCode.text = "CÓDIGO: #${tool.code}"

            // Calcular tiempo de uso
            val diffMs = System.currentTimeMillis() - tool.registrationDate
            val daysInUse = TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
            val monthsInUse = (daysInUse / 30).coerceAtLeast(0)
            
            tvUsageTime.text = if (monthsInUse == 0) "$daysInUse Días" else "$monthsInUse Meses"

            // Calcular vida útil restante
            if (tool.maxUsageMonths > 0) {
                val remainingMonths = tool.maxUsageMonths - monthsInUse
                tvRemainingTime.text = if (remainingMonths <= 0) "VENCIDO" else "$remainingMonths Meses"
                tvRemainingTime.setTextColor(
                    if (remainingMonths <= 1) ContextCompat.getColor(root.context, R.color.red_600)
                    else ContextCompat.getColor(root.context, R.color.emerald_600)
                )
            } else {
                tvRemainingTime.text = "Ilimitada"
                tvRemainingTime.setTextColor(ContextCompat.getColor(root.context, R.color.slate_400))
            }

            if (tool.status == "RETIRED") {
                chipStatus.text = "DE BAJA"
                chipStatus.setChipBackgroundColorResource(R.color.slate_200)
                chipStatus.setTextColor(ContextCompat.getColor(root.context, R.color.slate_600))
                btnMarkRetired.visibility = View.GONE
                btnRenew.visibility = View.VISIBLE
                btnRenew.text = "Reactivar"
            } else {
                chipStatus.text = "ACTIVO"
                chipStatus.setChipBackgroundColorResource(R.color.emerald_50)
                chipStatus.setTextColor(ContextCompat.getColor(root.context, R.color.emerald_600))
                btnMarkRetired.visibility = View.VISIBLE
                btnRenew.visibility = View.VISIBLE
                btnRenew.text = "Renovar"
            }

            btnMarkRetired.setOnClickListener { onRetire(tool) }
            btnRenew.setOnClickListener { onRenew(tool) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<HotelToolEntity>() {
        override fun areItemsTheSame(oldItem: HotelToolEntity, newItem: HotelToolEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HotelToolEntity, newItem: HotelToolEntity) = oldItem == newItem
    }
}
