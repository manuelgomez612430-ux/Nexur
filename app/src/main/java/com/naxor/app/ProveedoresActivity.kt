package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProviderEntity
import com.naxor.app.databinding.ActivityProveedoresBinding
import com.naxor.app.databinding.ItemProveedorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class ProveedoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProveedoresBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ProveedoresAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProveedoresBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadProviders()
    }

    private fun setupRecyclerView() {
        adapter = ProveedoresAdapter(
            onDelete = { provider -> showDeleteConfirmation(provider) },
            onWhatsApp = { provider -> contactProvider(provider) }
        )
        binding.rvProveedores.layoutManager = LinearLayoutManager(this)
        binding.rvProveedores.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackProveedores.setOnClickListener { finish() }
        binding.btnHelpProveedores.setOnClickListener { showHelpDialog() }
        binding.fabAddProveedor.setOnClickListener { showAddProviderDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Proveedores")
            .setMessage("• REGISTRO: Guarda aquí los contactos de quienes te venden mercancía.\n" +
                    "• PEDIDOS: Toca el icono de WhatsApp para enviarles un mensaje rápido de consulta.\n" +
                    "• ORGANIZACIÓN: Clasifícalos por categoría para encontrarlos más rápido.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadProviders() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.providerDao().getAllProviders()
            }
            adapter.submitList(list)
            binding.layoutEmptyProveedores.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showAddProviderDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nuevo Proveedor")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        
        val etNombre = EditText(this).apply { hint = "Empresa / Nombre" }
        val etContacto = EditText(this).apply { hint = "Persona de contacto" }
        val etTelefono = EditText(this).apply { hint = "Teléfono"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        
        val spinner = Spinner(this)
        val categories = resources.getStringArray(R.array.categorias_array)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        
        layout.addView(etNombre)
        layout.addView(etContacto)
        layout.addView(etTelefono)
        layout.addView(spinner)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val contacto = etContacto.text.toString()
            val telf = etTelefono.text.toString()
            val cat = spinner.selectedItem.toString()
            
            if (nombre.isNotBlank() && telf.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.providerDao().insert(ProviderEntity(nombre = nombre, contacto = contacto, telefono = telf, categoria = cat, isSynced = false))
                    SyncManager(this@ProveedoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadProviders() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun contactProvider(provider: ProviderEntity) {
        val mensaje = "Hola ${provider.contacto}, te saludo de *Naxor*. Quisiera hacer una consulta sobre sus productos. 📦"
        try {
            val url = "https://api.whatsapp.com/send?phone=51${provider.telefono}&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(provider: ProviderEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Proveedor")
            .setMessage("¿Deseas eliminar a ${provider.nombre}?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    provider.isDeleted = true
                    provider.isSynced = false
                    database.providerDao().update(provider)
                    SyncManager(this@ProveedoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadProviders() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class ProveedoresAdapter(
        private val onDelete: (ProviderEntity) -> Unit,
        private val onWhatsApp: (ProviderEntity) -> Unit
    ) : RecyclerView.Adapter<ProveedoresAdapter.ViewHolder>() {
        
        private var list: List<ProviderEntity> = emptyList()
        
        fun submitList(newList: List<ProviderEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemProveedorBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemProveedorBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = list[position]
            holder.b.tvProvNombre.text = p.nombre
            holder.b.tvProvCategoria.text = "Categoría: ${p.categoria}"
            holder.b.tvProvContacto.text = "Contacto: ${p.contacto}"
            
            holder.b.btnProvWhatsApp.setOnClickListener { onWhatsApp(p) }
            holder.b.btnProvDelete.setOnClickListener { onDelete(p) }
        }

        override fun getItemCount() = list.size
    }
}
