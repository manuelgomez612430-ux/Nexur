package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.HotelBookingEntity
import com.naxor.app.databinding.ItemHotelBookingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HotelBookingAdapter : ListAdapter<HotelBookingEntity, HotelBookingAdapter.ViewHolder>(DiffCallback) {

    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    class ViewHolder(val binding: ItemHotelBookingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemHotelBookingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val booking = getItem(position)
        with(holder.binding) {
            tvGuestName.text = booking.guestName
            tvBookingDates.text = "${sdf.format(Date(booking.checkInDate))} - ${sdf.format(Date(booking.checkOutDate))}"
            tvBookingStatus.text = booking.status
            tvBookingTotal.text = "S/ ${String.format(Locale.US, "%.2f", booking.totalAmount)}"
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<HotelBookingEntity>() {
        override fun areItemsTheSame(oldItem: HotelBookingEntity, newItem: HotelBookingEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HotelBookingEntity, newItem: HotelBookingEntity) = oldItem == newItem
    }
}
