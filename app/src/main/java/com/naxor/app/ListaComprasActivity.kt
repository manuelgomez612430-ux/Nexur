package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityListaComprasBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class ListaComprasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListaComprasBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: SimpleProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListaComprasBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadLowStockProducts()
    }

    private fun setupRecyclerView() {
        adapter = SimpleProductAdapter()
        binding.rvListaCompras.layoutManager = LinearLayoutManager(this)
        binding.rvListaCompras.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackLista.setOnClickListener { finish() }
        binding.btnHelpLista.setOnClickListener { showHelpDialog() }
        binding.btnEnviarPedido.setOnClickListener { sharePurchaseList() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Lista de Compras")
            .setMessage("• AUTOMATIZACIÓN: Esta lista muestra solo productos con stock de 5 unidades o menos.\n" +
                    "• PEDIDO: Toca el botón azul para enviar toda la lista de faltantes a un proveedor por WhatsApp.\n" +
                    "• ACTUALIZACIÓN: En cuanto registres nuevas entradas en Almacén, el producto desaparecerá de esta lista.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadLowStockProducts() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.productDao().allProducts.filter { it.stock <= 5 }
            }
            adapter.submitList(list)
            binding.layoutEmptyLista.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.tvCountFaltantes.text = "Tienes ${list.size} productos por agotarse"
            binding.btnEnviarPedido.isEnabled = list.isNotEmpty()
        }
    }

    private fun sharePurchaseList() {
        val products = adapter.getList()
        if (products.isEmpty()) return

        val body = StringBuilder()
        body.append("📝 *LISTA DE COMPRAS / PEDIDO* 📝\n")
        body.append("🏪 *Naxor*\n")
        body.append("--------------------------------\n\n")
        
        for (p in products) {
            body.append("• *${p.nombre}* (Stock: ${p.stock})\n")
        }
        
        body.append("\n--------------------------------\n")
        body.append("Por favor, enviar cotización. 📦")

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, body.toString())
        startActivity(Intent.createChooser(intent, "Enviar pedido por:"))
    }

    inner class SimpleProductAdapter : RecyclerView.Adapter<SimpleProductAdapter.ViewHolder>() {
        private var list: List<ProductEntity> = emptyList()
        fun submitList(newList: List<ProductEntity>) {
            list = newList
            notifyDataSetChanged()
        }
        fun getList() = list
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val t1: TextView = v.findViewById(android.R.id.text1)
            val t2: TextView = v.findViewById(android.R.id.text2)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = list[position]
            holder.t1.text = p.nombre
            holder.t1.setTextColor(resources.getColor(R.color.slate_900, theme))
            holder.t2.text = "Stock actual: ${p.stock} unidades | Categoría: ${p.categoria}"
            holder.t2.setTextColor(resources.getColor(R.color.red_600, theme))
        }
        override fun getItemCount() = list.size
    }
}
