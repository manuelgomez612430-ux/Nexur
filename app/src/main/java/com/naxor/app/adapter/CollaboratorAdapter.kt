package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.CollaboratorEntity
import com.naxor.app.databinding.ItemCollaboratorBinding

class CollaboratorAdapter(
    private val onDelete: (CollaboratorEntity) -> Unit,
    private val onEdit: (CollaboratorEntity) -> Unit
) : ListAdapter<CollaboratorEntity, CollaboratorAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemCollaboratorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemCollaboratorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvCollabName.text = item.name
            tvCollabEmail.text = item.email
            tvCollabInitial.text = if (item.name.isNotEmpty()) item.name[0].uppercaseChar().toString() else "?"
            
            val rolesText = if (item.roles.isNotEmpty()) item.roles.joinToString(", ") else item.role
            chipRole.text = rolesText
            
            root.setOnClickListener {
                onEdit(item)
            }

            root.setOnLongClickListener {
                onDelete(item)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<CollaboratorEntity>() {
        override fun areItemsTheSame(oldItem: CollaboratorEntity, newItem: CollaboratorEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CollaboratorEntity, newItem: CollaboratorEntity) = oldItem == newItem
    }
}
