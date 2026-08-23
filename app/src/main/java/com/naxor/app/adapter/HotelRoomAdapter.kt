package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.databinding.ItemFloorHeaderBinding
import com.naxor.app.databinding.ItemHotelRoomBinding
import java.util.Locale

data class RoomListItem(
    val entity: HotelRoomEntity,
    val hasPendingFailure: Boolean = false,
    val hasReservation: Boolean = false,
    val failureDescription: String? = null
)

sealed class RoomListType {
    data class Header(val floor: Int) : RoomListType()
    data class Room(val data: RoomListItem) : RoomListType()
}

class HotelRoomAdapter(
    private val onAction: (HotelRoomEntity) -> Unit,
    private val onSecondAction: ((HotelRoomEntity) -> Unit)? = null,
    private val onThirdAction: ((HotelRoomEntity) -> Unit)? = null,
    private val onCardClick: (HotelRoomEntity, Boolean) -> Unit // Entidad y si tiene falla
) : ListAdapter<RoomListType, RecyclerView.ViewHolder>(DiffCallback) {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROOM = 1
    }

    class RoomViewHolder(val binding: ItemHotelRoomBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderViewHolder(val binding: ItemFloorHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RoomListType.Header -> TYPE_HEADER
            is RoomListType.Room -> TYPE_ROOM
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
        if (holder is HeaderViewHolder && item is RoomListType.Header) {
            holder.binding.tvFloorHeader.text = "PISO ${item.floor}"
        } else if (holder is RoomViewHolder && item is RoomListType.Room) {
            val roomData = item.data
            val room = roomData.entity
            with(holder.binding) {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                val startOfToday = cal.timeInMillis
                val needsCleaning = room.lastCleaned < startOfToday

                // --- Lógica Unificada de Alertas en el Título ---
                val statusText = StringBuilder("Habitación ${room.number}")
                var titleColor = root.context.getColor(R.color.slate_900)

                if (roomData.hasPendingFailure) {
                    // Prioridad Máxima: Falla Técnica (Crítico - Rojo)
                    statusText.append(" 🛠️ (!)")
                    titleColor = root.context.getColor(R.color.red_600)
                } else if (needsCleaning || room.status == "DIRTY" || room.status == "MAINTENANCE") {
                    // Prioridad 2: Limpieza/Aseo Pendiente (Advertencia - Naranja)
                    statusText.append(" 🧹 (!)")
                    titleColor = root.context.getColor(R.color.orange_600)
                } else if (room.status == "OCCUPIED") {
                    // Si está ocupada y limpia: Color Verde sin brillitos
                    titleColor = root.context.getColor(R.color.emerald_600)
                }

                tvRoomNumber.text = statusText.toString()
                tvRoomNumber.setTextColor(titleColor)

                val iconRes = when(room.type.uppercase()) {
                    "SIMPLE" -> android.R.drawable.ic_menu_directions
                    "DOBLE" -> android.R.drawable.ic_menu_sort_by_size
                    "MATRIMONIAL" -> android.R.drawable.ic_menu_gallery
                    "SUITE" -> android.R.drawable.ic_menu_compass
                    else -> android.R.drawable.ic_menu_agenda
                }
                ivRoomIcon.setImageResource(iconRes)

                // Botón Reparado (🛠️)
                if (roomData.hasPendingFailure) {
                    btnThirdAction.text = "Reparado"
                    btnThirdAction.visibility = View.VISIBLE
                    btnThirdAction.setOnClickListener { onThirdAction?.invoke(room) }
                } else {
                    btnThirdAction.visibility = View.GONE
                }

                when (room.status) {
                    "FREE" -> {
                        if (roomData.hasReservation) {
                            chipRoomStatus.text = "RESERVADA"
                            chipRoomStatus.setChipBackgroundColorResource(R.color.sky_600)
                            chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                        } else {
                            chipRoomStatus.text = "LIBRE"
                            chipRoomStatus.setChipBackgroundColorResource(R.color.emerald_50)
                            chipRoomStatus.setTextColor(root.context.getColor(R.color.emerald_600))
                        }
                        btnRoomAction.text = "Check-in"
                        btnRoomAction.setBackgroundColor(root.context.getColor(R.color.emerald_50))
                        btnRoomAction.setTextColor(root.context.getColor(R.color.emerald_600))
                        
                        if (needsCleaning) {
                            btnSecondAction.text = "Asear"
                            btnSecondAction.visibility = View.VISIBLE
                            btnSecondAction.setBackgroundColor(root.context.getColor(R.color.orange_600))
                            btnSecondAction.setTextColor(root.context.getColor(R.color.white))
                            btnSecondAction.setOnClickListener { onSecondAction?.invoke(room) }
                        } else {
                            btnSecondAction.visibility = View.GONE
                        }
                    }
                    "OCCUPIED" -> {
                        chipRoomStatus.text = "OCUPADA"; chipRoomStatus.setChipBackgroundColorResource(R.color.red_600)
                        chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                        
                        // Botón Gestionar en MORADO
                        btnRoomAction.text = "Gestionar"
                        btnRoomAction.setBackgroundColor(root.context.getColor(R.color.vibrant_purple))
                        btnRoomAction.setTextColor(root.context.getColor(R.color.white))
                        
                        if (needsCleaning) {
                            btnSecondAction.text = "Aseado"
                            btnSecondAction.visibility = View.VISIBLE
                            btnSecondAction.setBackgroundColor(root.context.getColor(R.color.orange_600))
                            btnSecondAction.setTextColor(root.context.getColor(R.color.white))
                            btnSecondAction.setOnClickListener { onSecondAction?.invoke(room) }
                        } else {
                            btnSecondAction.visibility = View.GONE
                        }
                    }
                    "DIRTY", "MAINTENANCE" -> {
                        val statusLabel = if(room.status == "DIRTY") "SUCIA" else "POR ASEAR"
                        val statusColor = if(room.status == "DIRTY") R.color.orange_600 else R.color.vibrant_purple
                        
                        chipRoomStatus.text = statusLabel
                        chipRoomStatus.setChipBackgroundColorResource(statusColor)
                        chipRoomStatus.setTextColor(root.context.getColor(R.color.white))
                        
                        // Check-in se mantiene visible
                        btnRoomAction.text = "Check-in"
                        btnRoomAction.setBackgroundColor(root.context.getColor(R.color.emerald_50))
                        btnRoomAction.setTextColor(root.context.getColor(R.color.emerald_600))

                        // Botón Aseado al costado en NARANJA
                        btnSecondAction.text = "Aseado"
                        btnSecondAction.visibility = View.VISIBLE
                        btnSecondAction.setBackgroundColor(root.context.getColor(R.color.orange_600))
                        btnSecondAction.setTextColor(root.context.getColor(R.color.white))
                        btnSecondAction.setOnClickListener { onSecondAction?.invoke(room) }
                    }
                }
                btnRoomAction.setOnClickListener { onAction(room) }
                root.setOnClickListener { onCardClick(room, roomData.hasPendingFailure) }
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RoomListType>() {
        override fun areItemsTheSame(oldItem: RoomListType, newItem: RoomListType): Boolean {
            return if (oldItem is RoomListType.Header && newItem is RoomListType.Header) oldItem.floor == newItem.floor
            else if (oldItem is RoomListType.Room && newItem is RoomListType.Room) oldItem.data.entity.id == newItem.data.entity.id
            else false
        }
        override fun areContentsTheSame(oldItem: RoomListType, newItem: RoomListType) = oldItem == newItem
    }
}
