package com.naxor.app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ConfigActionsAdapter(
    private val context: Context,
    private val actionNames: Array<String>,
    private val actionIds: Array<String>,
    private val tempSelection: MutableList<String>
) : RecyclerView.Adapter<ConfigActionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvActionName)
        val tvStatus: TextView = view.findViewById(R.id.tvActionStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_config_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val id = actionIds[position]
        val name = actionNames[position]
        val pos = tempSelection.indexOf(id)

        holder.tvName.text = name
        
        if (pos != -1) {
            holder.tvStatus.text = "[${pos + 1}] ✅"
            holder.tvStatus.visibility = View.VISIBLE
            holder.itemView.setBackgroundColor(Color.parseColor("#1510B981"))
        } else {
            holder.tvStatus.text = ""
            holder.tvStatus.visibility = View.GONE
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
            
            val currentId = actionIds[currentPos]
            if (tempSelection.contains(currentId)) {
                tempSelection.remove(currentId)
                notifyDataSetChanged()
            } else {
                if (tempSelection.size < 5) {
                    tempSelection.add(currentId)
                    notifyDataSetChanged()
                } else {
                    // Animación de error (temblor)
                    val shake = AnimationUtils.loadAnimation(context, R.anim.shake)
                    holder.itemView.startAnimation(shake)
                    
                    // Feedback visual en rojo
                    val originalColor = if (tempSelection.contains(currentId)) Color.parseColor("#1510B981") else Color.TRANSPARENT
                    holder.itemView.setBackgroundColor(Color.parseColor("#25DC2626"))
                    holder.itemView.postDelayed({
                        holder.itemView.setBackgroundColor(originalColor)
                    }, 500)

                    Toast.makeText(context, "Límite alcanzado: Máximo 5 herramientas", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun getItemCount(): Int = actionNames.size
}
