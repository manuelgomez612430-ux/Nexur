package com.naxor.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.QuickAction
import com.naxor.app.databinding.ItemQuickActionBinding
import java.util.Collections

class QuickActionsAdapter(
    private var actions: MutableList<QuickAction>,
    private val onOrderChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<QuickActionsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemQuickActionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        
        with(holder.binding) {
            val parts = action.name.split(" ", limit = 2)
            if (parts.size == 2) {
                tvActionIcon.text = parts[0]
                tvActionName.text = parts[1]
            } else {
                tvActionIcon.text = "⚡"
                tvActionName.text = action.name
            }
            
            cardQuickAction.setCardBackgroundColor(Color.parseColor(action.color))
            root.setOnClickListener { action.action() }
        }
    }

    override fun getItemCount(): Int = actions.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(actions, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(actions, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onOrderChanged(actions.map { it.id })
    }

    fun updateData(newActions: List<QuickAction>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = actions.size
            override fun getNewListSize(): Int = newActions.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                actions[oldItemPosition].id == newActions[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                actions[oldItemPosition].name == newActions[newItemPosition].name &&
                        actions[oldItemPosition].color == newActions[newItemPosition].color
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        actions = newActions.toMutableList()
        diffResult.dispatchUpdatesTo(this)
    }
}
