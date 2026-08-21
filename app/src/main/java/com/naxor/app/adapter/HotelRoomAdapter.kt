package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.databinding.ItemHotelRoomBinding
import java.util.Locale

class HotelRoomAdapter(
    private val onAction: (HotelRoomEntity) -> Unit
) : ListAdapter<HotelRoomEntity, HotelRoomAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemHotelRoomBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemHotelRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val room = getItem(position)
        with(holder.binding) {
            tvRoomNumber.text = "Habitación ${room.number}"
            tvRoomType.text = room.type
            tvRoomPrice.text = "S/ ${String.format(Locale.US, "%.2f", room.baseRate)}"

            // Icon by Type
            val iconRes = when(room.type.uppercase()) {
                "SIMPLE" -> android.R.drawable.ic_menu_directions
                "DOBLE" -> android.R.drawable.ic_menu_sort_by_size
                "MATRIMONIAL" -> android.R.drawable.ic_menu_gallery
                "SUITE" -> android.R.drawable.ic_menu_compass
                else -> android.R.drawable.ic_menu_agenda
            }
            ivRoomIcon.setImageResource(iconRes)

            // Status UI
            when (room.status) {
                "FREE" -> {
                    chipRoomStatus.text = "LIBRE"
                    chipRoomStatus.setChipBackgroundColorResource(R.color.emerald_50)
                    chipRoomStatus.setTextColor(root.context.getColor(R.color.emerald_600))
                    btnRoomAction.text = "Check-in"
                }
                "OCCUPIED" -> {
                    chipRoomStatus.text = "OCUPADA"
                    chipRoomStatus.setChipBackgroundColorResource(R.color.red_600)
                    chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                    btnRoomAction.text = "Gestionar"
                }
                "DIRTY" -> {
                    chipRoomStatus.text = "SUCIA"
                    chipRoomStatus.setChipBackgroundColorResource(R.color.orange_600)
                    chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                    btnRoomAction.text = "Limpiar"
                }
                "MAINTENANCE" -> {
                    chipRoomStatus.text = "MANTENIM."
                    chipRoomStatus.setChipBackgroundColorResource(R.color.slate_400)
                    chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                    btnRoomAction.text = "Habilitar"
                }
            }

            btnRoomAction.setOnClickListener { onAction(room) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<HotelRoomEntity>() {
        override fun areItemsTheSame(oldItem: HotelRoomEntity, newItem: HotelRoomEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HotelRoomEntity, newItem: HotelRoomEntity) = oldItem == newItem
    }
}
