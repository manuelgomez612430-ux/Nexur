package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.databinding.ItemHotelGuestHistoryBinding
import java.util.Locale

data class GuestHistoryItem(
    val bookingId: String,
    val name: String,
    val doc: String,
    val origin: String,
    val nationality: String,
    val roomNumber: String,
    val floor: Int,
    val stayNights: Int,
    val extraCharges: Double,
    val totalAmount: Double
)

class HotelGuestHistoryAdapter : ListAdapter<GuestHistoryItem, HotelGuestHistoryAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemHotelGuestHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemHotelGuestHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvGuestName.text = item.name
            tvGuestDoc.text = "Doc: ${item.doc}"
            tvRoomNumber.text = "Piso ${item.floor} - Hab ${item.roomNumber}"
            tvOrigin.text = if (item.origin.isNotEmpty()) item.origin else "-"
            tvStayDuration.text = "${item.stayNights} Noches"
            tvExtraCharges.text = "S/ ${String.format(Locale.US, "%.2f", item.extraCharges)}"
            tvTotalAmount.text = "S/ ${String.format(Locale.US, "%.2f", item.totalAmount)}"

            chipNationality.text = if (item.nationality == "FOREIGNER") "Extranjero" else "Nacional"
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<GuestHistoryItem>() {
        override fun areItemsTheSame(oldItem: GuestHistoryItem, newItem: GuestHistoryItem) = oldItem.bookingId == newItem.bookingId
        override fun areContentsTheSame(oldItem: GuestHistoryItem, newItem: GuestHistoryItem) = oldItem == newItem
    }
}
