package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.R
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ItemVentaBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaleAdapter(
    private var transactions: List<List<SaleEntity>>,
    private val onShare: (List<SaleEntity>) -> Unit,
    private val onLongClick: (List<SaleEntity>) -> Unit
) : RecyclerView.Adapter<SaleAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemVentaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVentaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val items = transactions[position]
        val firstItem = items[0]
        
        with(holder.binding) {
            // 1. Mostrar Hora
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvVentaHora.text = sdf.format(Date(firstItem.timestamp))

            // 2. Calcular y mostrar Total de la transacciÃ³n
            val totalTransaccion = items.sumOf { it.total }
            tvVentaTotal.text = String.format(Locale.getDefault(), "S/ %.2f", totalTransaccion)

            // 3. AÃ±adir productos dinÃ¡micamente
            layoutVentaProductos.removeAllViews()
            val inflater = LayoutInflater.from(root.context)
            
            for (item in items) {
                val productRow = inflater.inflate(android.R.layout.simple_list_item_2, layoutVentaProductos, false)
                val text1 = productRow.findViewById<TextView>(android.R.id.text1)
                val text2 = productRow.findViewById<TextView>(android.R.id.text2)
                
                text1.text = item.nombreProducto
                text1.textSize = 14f
                
                text2.text = "${item.cantidad} uds x ${String.format(Locale.getDefault(), "S/ %.2f", item.precioVenta)} = ${String.format(Locale.getDefault(), "S/ %.2f", item.total)}"
                text2.textSize = 12f
                
                layoutVentaProductos.addView(productRow)
            }

            btnShareSaleTicket.setOnClickListener {
                onShare(items)
            }

            root.setOnLongClickListener {
                onLongClick(items)
                true
            }
        }
    }

    override fun getItemCount() = transactions.size

    fun updateList(newList: List<List<SaleEntity>>) {
        transactions = newList
        notifyDataSetChanged()
    }
}

