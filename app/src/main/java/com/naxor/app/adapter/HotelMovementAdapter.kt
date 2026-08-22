package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.databinding.ItemMovementMiniBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HotelMovementItem(
    val id: String,
    val type: String, // CHARGE, PAYMENT
    val concept: String,
    val amount: Double,
    val timestamp: Long
)

class HotelMovementAdapter : ListAdapter<HotelMovementItem, HotelMovementAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemMovementMiniBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemMovementMiniBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvMiniTitle.text = item.concept
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            tvMiniTime.text = sdf.format(Date(item.timestamp))

            if (item.type == "CHARGE") {
                tvMiniValue.text = "- S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                tvMiniValue.setTextColor(root.context.getColor(R.color.red_600))
                ivMiniIcon.setImageResource(android.R.drawable.ic_input_add)
                cardMiniIcon.setCardBackgroundColor(root.context.getColor(R.color.slate_200))
            } else {
                tvMiniValue.text = "+ S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                tvMiniValue.setTextColor(root.context.getColor(R.color.emerald_600))
                ivMiniIcon.setImageResource(android.R.drawable.ic_menu_send)
                cardMiniIcon.setCardBackgroundColor(root.context.getColor(R.color.emerald_600))
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<HotelMovementItem>() {
        override fun areItemsTheSame(oldItem: HotelMovementItem, newItem: HotelMovementItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HotelMovementItem, newItem: HotelMovementItem) = oldItem == newItem
    }
}
