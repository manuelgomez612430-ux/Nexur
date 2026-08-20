package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.BusinessDebtEntity
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.databinding.ActivityBusinessDebtsBinding
import com.naxor.app.databinding.ItemBusinessDebtBinding
import com.naxor.app.util.BusinessDebtPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class BusinessDebtsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBusinessDebtsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: BusinessDebtsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusinessDebtsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadDebts()
    }

    private fun setupRecyclerView() {
        adapter = BusinessDebtsAdapter(
            onPay = { debt -> showPaymentDialog(debt) },
            onPdf = { debt -> shareDebtPdf(debt) }
        )
        binding.rvBusinessDebts.layoutManager = LinearLayoutManager(this)
        binding.rvBusinessDebts.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackDebts.setOnClickListener { finish() }
        binding.fabAddBusinessDebt.setOnClickListener { showAddDebtDialog() }
        binding.btnHelpDebts.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Gestión de Deudas Propias")
                .setMessage("Aquí puedes registrar lo que tu negocio debe a proveedores, bancos o préstamos personales.\n\n• REGISTRO: Usa el '+' para anotar una nueva deuda.\n• PAGO: Presiona 'PAGAR' cuando realices un abono o liquides la deuda.")
                .setPositiveButton("Entendido", null).show()
        }
    }

    private fun loadDebts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val debts = database.businessDebtDao().getPendingDebts()
            val total = database.businessDebtDao().getTotalOwed() ?: 0.0
            
            withContext(Dispatchers.Main) {
                adapter.submitList(debts)
                binding.tvTotalOwed.text = String.format(Locale.getDefault(), "S/ %.2f", total)
                binding.layoutEmptyDebts.visibility = if (debts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDebtDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nueva Deuda del Negocio")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        val etAcreedor = EditText(this).apply { hint = "Acreedor (Banco/Proveedor/Persona)" }
        val etConcepto = EditText(this).apply { hint = "Concepto (Mercadería, Préstamo, etc.)" }
        val etMonto = EditText(this).apply { hint = "Monto de la deuda"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        
        var selectedDate = 0L
        val btnDate = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📅 Programar Fecha de Pago"
            setOnClickListener {
                val cal = Calendar.getInstance()
                android.app.DatePickerDialog(this@BusinessDebtsActivity, { _, y, m, d ->
                    val sel = Calendar.getInstance()
                    sel.set(y, m, d)
                    selectedDate = sel.timeInMillis
                    text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(selectedDate))
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }

        layout.addView(etAcreedor)
        layout.addView(etConcepto)
        layout.addView(etMonto)
        layout.addView(btnDate)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val acreedor = etAcreedor.text.toString()
            val concepto = etConcepto.text.toString()
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            
            if (acreedor.isNotBlank() && monto > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val debt = BusinessDebtEntity(acreedor = acreedor, concepto = concepto, montoTotal = monto, fechaVencimiento = selectedDate)
                    database.businessDebtDao().insert(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    
                    val log = MovementLogEntity(
                        type = "BUSINESS_DEBT_ADDED",
                        title = "Deuda Contraída",
                        description = "Acreedor: $acreedor ($concepto)",
                        value = "S/ ${String.format(Locale.getDefault(), "%.2f", monto)}",
                        colorHex = "#475569",
                        iconRes = android.R.drawable.ic_menu_edit
                    )
                    database.movementLogDao().insert(log)
                    
                    withContext(Dispatchers.Main) { loadDebts() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null).show()
    }

    private fun showPaymentDialog(debt: BusinessDebtEntity) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pagar a ${debt.acreedor}")
        val remaining = debt.montoTotal - debt.montoPagado
        builder.setMessage("Saldo pendiente: S/ ${String.format(Locale.getDefault(), "%.2f", remaining)}")

        val etMonto = EditText(this).apply { hint = "Monto a pagar"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        builder.setView(etMonto)

        builder.setPositiveButton("Registrar Pago") { _, _ ->
            val pago = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (pago > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    debt.montoPagado += pago
                    if (debt.montoPagado >= debt.montoTotal) {
                        debt.isPaid = true
                    }
                    database.businessDebtDao().update(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    
                    val log = MovementLogEntity(
                        type = "EXPENSE", // Los pagos de deudas cuentan como gastos
                        title = "Pago Realizado",
                        description = "Acreedor: ${debt.acreedor}",
                        value = "- S/ ${String.format(Locale.getDefault(), "%.2f", pago)}",
                        colorHex = "#F43F5E",
                        iconRes = android.R.drawable.ic_menu_send
                    )
                    database.movementLogDao().insert(log)
                    
                    withContext(Dispatchers.Main) { loadDebts() }
                }
            }
        }
        builder.setNeutralButton("Liquidar Total") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                val montoLiquidado = debt.montoTotal - debt.montoPagado
                debt.montoPagado = debt.montoTotal
                debt.isPaid = true
                database.businessDebtDao().update(debt)
                SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                
                val log = MovementLogEntity(
                    type = "EXPENSE",
                    title = "Deuda Liquidada",
                    description = "Acreedor: ${debt.acreedor}",
                    value = "- S/ ${String.format(Locale.getDefault(), "%.2f", montoLiquidado)}",
                    colorHex = "#F43F5E",
                    iconRes = android.R.drawable.ic_menu_send
                )
                database.movementLogDao().insert(log)
                withContext(Dispatchers.Main) { loadDebts() }
            }
        }
        builder.setNegativeButton("Cancelar", null).show()
    }

    private fun shareDebtPdf(debt: BusinessDebtEntity) {
        val pdfFile = BusinessDebtPdfGenerator(this).generatePaymentCommitment(debt)
        if (pdfFile != null && pdfFile.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir Compromiso de Pago"))
        } else {
            Toast.makeText(this, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    }

    inner class BusinessDebtsAdapter(
        private val onPay: (BusinessDebtEntity) -> Unit,
        private val onPdf: (BusinessDebtEntity) -> Unit
    ) : RecyclerView.Adapter<BusinessDebtsAdapter.ViewHolder>() {
        private var list = listOf<BusinessDebtEntity>()
        fun submitList(newList: List<BusinessDebtEntity>) { list = newList; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemBusinessDebtBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemBusinessDebtBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val d = list[position]
            holder.b.tvDebtCreditor.text = d.acreedor
            holder.b.tvDebtConcept.text = d.concepto
            holder.b.tvDebtAmount.text = String.format("S/ %.2f", d.montoTotal - d.montoPagado)
            
            if (d.fechaVencimiento > 0) {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                holder.b.tvDebtDueDate.text = "Pagar el: ${sdf.format(Date(d.fechaVencimiento))}"
                holder.b.tvDebtDueDate.visibility = View.VISIBLE
            } else {
                holder.b.tvDebtDueDate.visibility = View.GONE
            }
            holder.b.btnRegisterPayment.setOnClickListener { onPay(d) }
            holder.b.btnDebtPdf.setOnClickListener { onPdf(d) }
        }
        override fun getItemCount() = list.size
    }
}
