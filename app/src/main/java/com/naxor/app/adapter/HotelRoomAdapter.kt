package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.databinding.ItemFloorHeaderBinding
import com.naxor.app.databinding.ItemHotelRoomBinding
import java.util.Locale

sealed class RoomListItem {
    data class Header(val floor: Int) : RoomListItem()
    data class Room(val entity: HotelRoomEntity) : RoomListItem()
}

class HotelRoomAdapter(
    private val onAction: (HotelRoomEntity) -> Unit
) : ListAdapter<RoomListItem, RecyclerView.ViewHolder>(DiffCallback) {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROOM = 1
    }

    class RoomViewHolder(val binding: ItemHotelRoomBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderViewHolder(val binding: ItemFloorHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RoomListItem.Header -> TYPE_HEADER
            is RoomListItem.Room -> TYPE_ROOM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemFloorHeaderBinding.inflate(inflater, parent, false))
            else -> RoomViewHolder(ItemHotelRoomBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is HeaderViewHolder && item is RoomListItem.Header) {
            holder.binding.tvFloorHeader.text = "PISO ${item.floor}"
        } else if (holder is RoomViewHolder && item is RoomListItem.Room) {
            val room = item.entity
            with(holder.binding) {
                tvRoomNumber.text = "Habitación ${room.number}"
                tvRoomType.text = room.type
                tvRoomPrice.text = "S/ ${String.format(Locale.US, "%.2f", room.baseRate)}"

                val iconRes = when(room.type.uppercase()) {
                    "SIMPLE" -> android.R.drawable.ic_menu_directions
                    "DOBLE" -> android.R.drawable.ic_menu_sort_by_size
                    "MATRIMONIAL" -> android.R.drawable.ic_menu_gallery
                    "SUITE" -> android.R.drawable.ic_menu_compass
                    else -> android.R.drawable.ic_menu_agenda
                }
                ivRoomIcon.setImageResource(iconRes)

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
    }

    object DiffCallback : DiffUtil.ItemCallback<RoomListItem>() {
        override fun areItemsTheSame(oldItem: RoomListItem, newItem: RoomListItem): Boolean {
            return if (oldItem is RoomListItem.Header && newItem is RoomListItem.Header) {
                oldItem.floor == newItem.floor
            } else if (oldItem is RoomListItem.Room && newItem is RoomListItem.Room) {
                oldItem.entity.id == newItem.entity.id
            } else false
        }
        override fun areContentsTheSame(oldItem: RoomListItem, newItem: RoomListItem) = oldItem == newItem
    }
}
