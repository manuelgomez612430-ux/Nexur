package com.naxor.app.adapter

import android.view.LayoutInflater
import android.view.View
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
    private var items: List<SaleListItem>,
    private val onShare: (List<SaleEntity>) -> Unit,
    private val onEmit: (List<SaleEntity>) -> Unit,
    private val onLongClick: (List<SaleEntity>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TRANSACTION = 1
    }

    sealed class SaleListItem {
        data class Header(val date: String) : SaleListItem()
        data class Transaction(val sales: List<SaleEntity>) : SaleListItem()
    }

    class TransactionViewHolder(val binding: ItemVentaBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDateHeader)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SaleListItem.Header -> TYPE_HEADER
            is SaleListItem.Transaction -> TYPE_TRANSACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_date_header, parent, false)
            HeaderViewHolder(view)
        } else {
            TransactionViewHolder(ItemVentaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is SaleListItem.Header) {
            holder.tvDate.text = item.date
        } else if (holder is TransactionViewHolder && item is SaleListItem.Transaction) {
            bindTransaction(holder, item.sales)
        }
    }

    private fun bindTransaction(holder: TransactionViewHolder, sales: List<SaleEntity>) {
        val firstItem = sales[0]
        with(holder.binding) {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvVentaHora.text = sdf.format(Date(firstItem.timestamp))

            val totalTransaccion = sales.sumOf { it.total }
            tvVentaTotal.text = String.format(Locale.getDefault(), "S/ %.2f", totalTransaccion)

            layoutVentaProductos.removeAllViews()
            val inflater = LayoutInflater.from(root.context)
            
            for (sale in sales) {
                val productRow = inflater.inflate(android.R.layout.simple_list_item_2, layoutVentaProductos, false)
                val text1 = productRow.findViewById<TextView>(android.R.id.text1)
                val text2 = productRow.findViewById<TextView>(android.R.id.text2)
                
                text1.text = sale.nombreProducto
                text1.textSize = 14f
                
                text2.text = "${sale.cantidad} uds x ${String.format(Locale.getDefault(), "S/ %.2f", sale.precioVenta)} = ${String.format(Locale.getDefault(), "S/ %.2f", sale.total)}"
                text2.textSize = 12f
                
                layoutVentaProductos.addView(productRow)
            }

            btnShareSaleTicket.setOnClickListener { onShare(sales) }
            
            btnEmitLegalDoc.setOnClickListener { onEmit(sales) }

            root.setOnLongClickListener {
                onLongClick(sales)
                true
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<SaleListItem>) {
        items = newList
        notifyDataSetChanged()
    }
}
