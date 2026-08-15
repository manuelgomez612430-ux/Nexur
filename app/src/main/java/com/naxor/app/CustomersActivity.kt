package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.CustomerEntity
import com.naxor.app.databinding.ActivityCustomersBinding
import com.naxor.app.databinding.ItemCustomerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class CustomersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomersBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CustomersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadCustomers()
    }

    private fun setupRecyclerView() {
        adapter = CustomersAdapter(
            onDelete = { customer -> showDeleteConfirmation(customer) },
            onWhatsApp = { customer -> contactCustomer(customer) }
        )
        binding.rvCustomers.layoutManager = LinearLayoutManager(this)
        binding.rvCustomers.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackCustomers.setOnClickListener { finish() }
        binding.btnHelpCustomers.setOnClickListener { showHelpDialog() }
        binding.fabAddCustomer.setOnClickListener { showAddCustomerDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Clientes VIP")
            .setMessage("• REGISTRO: Guarda a tus clientes frecuentes con su teléfono y dirección.\n" +
                    "• CONTACTO: Usa el botón de WhatsApp para saludarlos o enviarles promociones.\n" +
                    "• ELIMINAR: Mantén presionado el nombre del cliente para borrarlo de la lista.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.customerDao().getAllCustomers()
            }
            adapter.submitList(list)
        }
    }

    private fun showAddCustomerDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nuevo Cliente")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        
        val etNombre = EditText(this).apply { hint = "Nombre Completo" }
        val etTelefono = EditText(this).apply { hint = "Teléfono"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val etDireccion = EditText(this).apply { hint = "Dirección (Opcional)" }
        
        layout.addView(etNombre)
        layout.addView(etTelefono)
        layout.addView(etDireccion)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val telf = etTelefono.text.toString()
            val dir = etDireccion.text.toString()
            
            if (nombre.isNotBlank() && telf.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.customerDao().insert(CustomerEntity(nombre = nombre, telefono = telf, direccion = dir, isSynced = false))
                    SyncManager(this@CustomersActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadCustomers() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun contactCustomer(customer: CustomerEntity) {
        val mensaje = "Hola ${customer.nombre}, un gusto saludarte. 👋"
        try {
            val url = "https://api.whatsapp.com/send?phone=51${customer.telefono}&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(customer: CustomerEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Cliente")
            .setMessage("¿Deseas eliminar a ${customer.nombre}?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.customerDao().delete(customer)
                    withContext(Dispatchers.Main) { loadCustomers() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class CustomersAdapter(
        private val onDelete: (CustomerEntity) -> Unit,
        private val onWhatsApp: (CustomerEntity) -> Unit
    ) : RecyclerView.Adapter<CustomersAdapter.ViewHolder>() {
        
        private var list: List<CustomerEntity> = emptyList()
        
        fun submitList(newList: List<CustomerEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemCustomerBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemCustomerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val c = list[position]
            holder.b.tvCustomerName.text = c.nombre
            holder.b.tvCustomerPhone.text = c.telefono
            
            holder.b.btnCustomerWhatsApp.setOnClickListener { onWhatsApp(c) }
            holder.b.root.setOnLongClickListener {
                onDelete(c)
                true
            }
        }

        override fun getItemCount() = list.size
    }
}
