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
    private val onRenew: (HotelToolEntity) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<HotelToolEntity, HotelToolAdapter.ViewHolder>(DiffCallback) {

    private var isSelectionMode = false
    private val selectedIds = mutableSetOf<String>()

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedTools(): List<HotelToolEntity> {
        return currentList.filter { selectedIds.contains(it.id) }
    }

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
            val now = System.currentTimeMillis()
            val diffMs = now - tool.registrationDate
            val daysInUse = TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
            tvUsageTime.text = formatDetailedTime(daysInUse)

            // Calcular vida útil restante
            if (tool.expiryDate > 0L) {
                val remainingMs = tool.expiryDate - now
                val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMs).toInt()
                
                if (remainingDays <= 0) {
                    tvRemainingTime.text = "VENCIDO"
                    tvRemainingTime.setTextColor(ContextCompat.getColor(root.context, R.color.red_600))
                } else {
                    tvRemainingTime.text = formatDetailedTime(remainingDays)
                    tvRemainingTime.setTextColor(
                        if (remainingDays <= 7) ContextCompat.getColor(root.context, R.color.red_600)
                        else if (remainingDays <= 30) ContextCompat.getColor(root.context, R.color.orange_600)
                        else ContextCompat.getColor(root.context, R.color.emerald_600)
                    )
                }
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

            // --- SELECCIÓN DINÁMICA ---
            checkTool.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkTool.isChecked = selectedIds.contains(tool.id)
            
            root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedIds.add(tool.id)
                    notifyDataSetChanged()
                    onSelectionChanged(selectedIds.size)
                }
                true
            }

            root.setOnClickListener {
                if (isSelectionMode) {
                    if (selectedIds.contains(tool.id)) selectedIds.remove(tool.id)
                    else selectedIds.add(tool.id)
                    checkTool.isChecked = selectedIds.contains(tool.id)
                    onSelectionChanged(selectedIds.size)
                    if (selectedIds.isEmpty()) {
                        isSelectionMode = false
                        notifyDataSetChanged()
                    }
                }
            }

            checkTool.setOnClickListener {
                if (selectedIds.contains(tool.id)) selectedIds.remove(tool.id)
                else selectedIds.add(tool.id)
                onSelectionChanged(selectedIds.size)
                if (selectedIds.isEmpty()) {
                    isSelectionMode = false
                    notifyDataSetChanged()
                }
            }

            btnMarkRetired.setOnClickListener { if (!isSelectionMode) onRetire(tool) }
            btnRenew.setOnClickListener { if (!isSelectionMode) onRenew(tool) }
        }
    }

    private fun formatDetailedTime(totalDays: Int): String {
        if (totalDays <= 0) return "0 Días"
        val months = totalDays / 30
        val days = totalDays % 30
        
        return when {
            months > 0 && days > 0 -> "$months Meses, $days Días"
            months > 0 -> "$months Meses"
            else -> "$days Días"
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<HotelToolEntity>() {
        override fun areItemsTheSame(oldItem: HotelToolEntity, newItem: HotelToolEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HotelToolEntity, newItem: HotelToolEntity) = oldItem == newItem
    }
}
