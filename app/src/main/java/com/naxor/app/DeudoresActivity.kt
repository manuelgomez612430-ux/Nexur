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
import com.naxor.app.data.DebtorEntity
import com.naxor.app.databinding.ActivityDeudoresBinding
import com.naxor.app.databinding.ItemDeudorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.*

class DeudoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeudoresBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: DeudoresAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeudoresBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadDebtors()
    }

    private fun setupRecyclerView() {
        adapter = DeudoresAdapter(
            onPagar = { deudor -> showPaymentDialog(deudor) },
            onWhatsApp = { deudor -> sendWhatsAppReminder(deudor) }
        )
        binding.rvDeudores.layoutManager = LinearLayoutManager(this)
        binding.rvDeudores.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackDeudores.setOnClickListener { finish() }
        binding.btnHelpDeudores.setOnClickListener { showHelpDialog() }
        binding.fabAddDeudor.setOnClickListener { showAddDebtorDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Fiados")
            .setMessage("• REGISTRO: Usa '+' para añadir clientes que te deben.\n" +
                    "• COBRAR: Toca el icono de WhatsApp para enviar un recordatorio automático con el monto de la deuda.\n" +
                    "• PAGAR: Toca el icono de check para registrar un abono o liquidar la deuda total.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadDebtors() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.debtorDao().getAllDebtors()
            }
            adapter.submitList(list)
            binding.layoutEmptyDeudores.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            
            val total = list.sumOf { it.deudaTotal }
            binding.tvTotalDeudaGlobal.text = String.format(Locale.getDefault(), "Total por cobrar: S/ %.2f", total)
        }
    }

    private fun showAddDebtorDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_product, null) // Reusaremos el estilo de tus otros diálogos
        // Para este MVP usaremos un builder simple
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Agregar Deudor")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val etNombre = EditText(this).apply { hint = "Nombre del cliente" }
        val etTelefono = EditText(this).apply { hint = "Teléfono (Opcional)"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val etMonto = EditText(this).apply { hint = "Monto inicial fiado"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        
        layout.addView(etNombre)
        layout.addView(etTelefono)
        layout.addView(etMonto)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val telf = etTelefono.text.toString()
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            
            if (nombre.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.debtorDao().insertDebtor(DebtorEntity(nombre = nombre, telefono = telf, deudaTotal = monto, isSynced = false))
                    SyncManager(this@DeudoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadDebtors() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun showPaymentDialog(deudor: DebtorEntity) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Registrar Pago de ${deudor.nombre}")
        builder.setMessage("¿Cuánto va a pagar el cliente?\nSaldo actual: S/ ${deudor.deudaTotal}")
        
        val etMonto = EditText(this).apply { 
            hint = "Monto a pagar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        builder.setView(etMonto)

        builder.setPositiveButton("Pagar") { _, _ ->
            val pago = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (pago > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val nuevoTotal = deudor.deudaTotal - pago
                    if (nuevoTotal <= 0) {
                        database.debtorDao().deleteDebtor(deudor)
                    } else {
                        val updated = deudor.copy(deudaTotal = nuevoTotal, isSynced = false)
                        database.debtorDao().updateDebtor(updated)
                    }
                    SyncManager(this@DeudoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadDebtors() }
                }
            }
        }
        builder.setNeutralButton("Liquidar Total") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                database.debtorDao().deleteDebtor(deudor)
                withContext(Dispatchers.Main) { loadDebtors() }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun sendWhatsAppReminder(deudor: DebtorEntity) {
        if (deudor.telefono.isBlank()) {
            Toast.makeText(this, "Este deudor no tiene teléfono registrado", Toast.LENGTH_SHORT).show()
            return
        }

        val mensaje = "Hola ${deudor.nombre}, te saludo de *Naxor*. 👋 Te escribo para recordarte que tienes un saldo pendiente de *S/ ${String.format("%.2f", deudor.deudaTotal)}*. ¡Muchas gracias! 😊"
        
        try {
            val url = "https://api.whatsapp.com/send?phone=51${deudor.telefono}&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    inner class DeudoresAdapter(
        private val onPagar: (DebtorEntity) -> Unit,
        private val onWhatsApp: (DebtorEntity) -> Unit
    ) : RecyclerView.Adapter<DeudoresAdapter.ViewHolder>() {
        
        private var list: List<DebtorEntity> = emptyList()
        
        fun submitList(newList: List<DebtorEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemDeudorBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemDeudorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val d = list[position]
            holder.binding.tvDeudorNombre.text = d.nombre
            holder.binding.tvDeudorTelefono.text = if(d.telefono.isBlank()) "Sin número" else d.telefono
            holder.binding.tvDeudorMonto.text = String.format("S/ %.2f", d.deudaTotal)
            
            holder.binding.btnPagarDeuda.setOnClickListener { onPagar(d) }
            holder.binding.btnCobrarWhatsApp.setOnClickListener { onWhatsApp(d) }
        }

        override fun getItemCount() = list.size
    }
}
