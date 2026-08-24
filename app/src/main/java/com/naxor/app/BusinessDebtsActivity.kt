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
import com.naxor.app.databinding.DialogAddBusinessDebtBinding
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
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")
        
        lifecycleScope.launch(Dispatchers.IO) {
            val debts = database.businessDebtDao().getPendingDebts()
            val total = database.businessDebtDao().getTotalOwed() ?: 0.0
            
            withContext(Dispatchers.Main) {
                adapter.submitList(debts)
                binding.tvTotalOwed.text = String.format(Locale.getDefault(), "$currency %.2f", total)
                binding.layoutEmptyDebts.visibility = if (debts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDebtDialog() {
        val db = DialogAddBusinessDebtBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")
        db.layoutDebtMontoTotal.prefixText = currency
        db.layoutDebtMontoCuota.prefixText = currency

        var selectedDate = 0L
        db.btnDebtDatePicker.setOnClickListener {
            val cal = Calendar.getInstance()
            android.app.DatePickerDialog(this, { _, y, m, d ->
                val sel = Calendar.getInstance()
                sel.set(y, m, d, 9, 0, 0)
                selectedDate = sel.timeInMillis
                db.btnDebtDatePicker.text = "📅 Pagar el: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(selectedDate))}"
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        db.toggleDebtRecurrence.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                db.layoutDebtMonthlyDay.visibility = if (checkedId == R.id.btnRecurrenceMonthly) View.VISIBLE else View.GONE
                db.btnDebtDatePicker.visibility = if (checkedId == R.id.btnRecurrenceNone) View.VISIBLE else View.GONE
            }
        }

        db.btnCancelDebt.setOnClickListener { dialog.dismiss() }
        db.btnSaveDebt.setOnClickListener {
            val acreedor = db.etDebtAcreedor.text.toString().trim()
            val concepto = db.etDebtConcepto.text.toString().trim()
            val montoTotal = db.etDebtMontoTotal.text.toString().toDoubleOrNull() ?: 0.0
            val montoCuota = db.etDebtMontoCuota.text.toString().toDoubleOrNull() ?: 0.0
            
            if (acreedor.isNotBlank() && montoTotal > 0) {
                val recurrencia = when(db.toggleDebtRecurrence.checkedButtonId) {
                    R.id.btnRecurrenceDaily -> "DAILY"
                    R.id.btnRecurrenceMonthly -> "MONTHLY"
                    else -> "NONE"
                }
                val diaRecurrencia = db.etDebtMonthlyDay.text.toString().toIntOrNull() ?: 0
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val debt = BusinessDebtEntity(
                        acreedor = acreedor,
                        concepto = concepto,
                        montoTotal = montoTotal,
                        montoCuota = montoCuota,
                        fechaVencimiento = if (recurrencia == "NONE") selectedDate else 0L,
                        recurrencia = recurrencia,
                        diaRecurrencia = diaRecurrencia,
                        proximoPago = calculateNextPayment(recurrencia, diaRecurrencia)
                    )
                    database.businessDebtDao().insert(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    
                    val log = MovementLogEntity(
                        type = "BUSINESS_DEBT_ADDED",
                        title = "Deuda Contraída",
                        description = "Acreedor: $acreedor ($concepto)",
                        value = "S/ ${String.format(Locale.getDefault(), "%.2f", montoTotal)}",
                        colorHex = "#475569",
                        iconRes = android.R.drawable.ic_menu_edit
                    )
                    database.movementLogDao().insert(log)
                    
                    withContext(Dispatchers.Main) { 
                        loadDebts()
                        dialog.dismiss()
                    }
                }
            } else {
                Toast.makeText(this, "Completa Acreedor y Monto Total", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
        dialog.window?.let {
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            it.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun calculateNextPayment(recurrencia: String, dia: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        
        when(recurrencia) {
            "DAILY" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            "MONTHLY" -> {
                if (dia > 0) {
                    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                    if (currentDay >= dia) {
                        cal.add(Calendar.MONTH, 1)
                    }
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, Math.min(dia, maxDay))
                } else {
                    cal.add(Calendar.MONTH, 1)
                }
            }
            else -> return 0L
        }
        return cal.timeInMillis
    }

    private fun showPaymentDialog(debt: BusinessDebtEntity) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pagar a ${debt.acreedor}")
        val remaining = debt.montoTotal - debt.montoPagado
        
        val message = StringBuilder("Saldo pendiente: S/ ${String.format(Locale.getDefault(), "%.2f", remaining)}")
        if (debt.montoCuota > 0) {
            message.append("\nCuota sugerida: S/ ${String.format(Locale.getDefault(), "%.2f", debt.montoCuota)}")
        }
        builder.setMessage(message.toString())

        val etMonto = EditText(this).apply { 
            hint = "Monto a pagar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (debt.montoCuota > 0) setText(debt.montoCuota.toString())
            else if (remaining > 0) setText(remaining.toString())
        }
        builder.setView(etMonto)

        builder.setPositiveButton("Registrar Pago") { _, _ ->
            val pago = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (pago > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    debt.montoPagado += pago
                    if (debt.montoPagado >= debt.montoTotal) {
                        debt.isPaid = true
                        debt.recurrencia = "NONE" // Deja de ser recurrente si ya se pagó el total
                    } else if (debt.recurrencia != "NONE") {
                        // Si es recurrente y se hizo un pago, calculamos la siguiente fecha
                        debt.proximoPago = calculateNextPayment(debt.recurrencia, debt.diaRecurrencia)
                    }
                    
                    database.businessDebtDao().update(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    
                    val log = MovementLogEntity(
                        type = "EXPENSE",
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
                debt.recurrencia = "NONE"
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
            val currency = holder.itemView.context.getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("currency_symbol", "S/")
            
            holder.b.tvDebtCreditor.text = d.acreedor
            holder.b.tvDebtConcept.text = d.concepto
            holder.b.tvDebtAmount.text = String.format("$currency %.2f", d.montoTotal - d.montoPagado)
            
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            
            if (d.recurrencia != "NONE") {
                holder.b.tvDebtRecurrenceBadge.visibility = View.VISIBLE
                val recText = when(d.recurrencia) {
                    "DAILY" -> "🔄 Pago Diario"
                    "MONTHLY" -> "🔄 Mensual (Día ${d.diaRecurrencia})"
                    else -> ""
                }
                holder.b.tvDebtRecurrenceBadge.text = recText
                if (d.proximoPago > 0) {
                    holder.b.tvDebtDueDate.text = "Siguiente pago: ${sdf.format(Date(d.proximoPago))}"
                } else {
                    holder.b.tvDebtDueDate.text = "Pendiente de programar"
                }
            } else {
                holder.b.tvDebtRecurrenceBadge.visibility = View.GONE
                if (d.fechaVencimiento > 0) {
                    holder.b.tvDebtDueDate.text = "Pagar el: ${sdf.format(Date(d.fechaVencimiento))}"
                    holder.b.tvDebtDueDate.visibility = View.VISIBLE
                } else {
                    holder.b.tvDebtDueDate.visibility = View.GONE
                }
            }
            
            holder.b.btnRegisterPayment.setOnClickListener { onPay(d) }
            holder.b.btnDebtPdf.setOnClickListener { onPdf(d) }
        }
        override fun getItemCount() = list.size
    }
}
