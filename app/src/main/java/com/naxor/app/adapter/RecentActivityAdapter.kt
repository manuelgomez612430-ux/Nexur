package com.naxor.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.databinding.ItemMovementMiniBinding
import java.text.SimpleDateFormat
import java.util.*

class RecentActivityAdapter(
    private var logs: List<MovementLogEntity>
) : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

    private val timeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

    class ViewHolder(val binding: ItemMovementMiniBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMovementMiniBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        with(holder.binding) {
            tvMiniTitle.text = log.title
            tvMiniTime.text = timeSdf.format(Date(log.timestamp))
            tvMiniValue.text = log.value
            ivMiniIcon.setImageResource(log.iconRes)
            try {
                cardMiniIcon.setCardBackgroundColor(Color.parseColor(log.colorHex))
            } catch (e: Exception) {
                cardMiniIcon.setCardBackgroundColor(Color.LTGRAY)
            }
        }
    }

    override fun getItemCount(): Int = logs.size

    fun updateData(newLogs: List<MovementLogEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = logs.size
            override fun getNewListSize(): Int = newLogs.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                logs[oldItemPosition].id == newLogs[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                logs[oldItemPosition] == newLogs[newItemPosition]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        logs = newLogs
        diffResult.dispatchUpdatesTo(this)
    }
}
