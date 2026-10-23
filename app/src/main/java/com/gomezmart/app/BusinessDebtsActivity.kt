package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.BusinessDebtEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.ActivityBusinessDebtsBinding
import com.gomezmart.app.databinding.DialogAddBusinessDebtBinding
import com.gomezmart.app.databinding.ItemBusinessDebtBinding
import com.gomezmart.app.util.BusinessDebtPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class BusinessDebtsActivity : BaseActivity() {

    private lateinit var binding: ActivityBusinessDebtsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: BusinessDebtsAdapter
    private var hotelRegistrations: List<ListenerRegistration> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusinessDebtsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadDebts()
    }

    override fun onStart() {
        super.onStart()
        // Activar sincronización en tiempo real al entrar al app
        if (hotelRegistrations.isEmpty()) {
            hotelRegistrations = SyncManager(this).startRealtimeHotelSync {
                loadDebts()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hotelRegistrations.forEach { it.remove() }
        hotelRegistrations = emptyList()
    }

    private fun setupRecyclerView() {
        adapter = BusinessDebtsAdapter(
            onPay = { debt -> 
                val now = Calendar.getInstance()
                // Normalizar a inicio del día para la comparación
                now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
                
                val dueDate = if (debt.recurrencia != "NONE") debt.proximoPago else debt.fechaVencimiento
                
                if (dueDate > 0 && now.timeInMillis < dueDate) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Pago Anticipado")
                        .setMessage("La fecha programada para este pago es el ${sdf.format(Date(dueDate))}.\n\n¿Deseas registrar el pago de todos modos?")
                        .setPositiveButton("Sí, Pagar ahora") { _, _ -> showPaymentDialog(debt) }
                        .setNegativeButton("No, Volver", null)
                        .show()
                } else {
                    showPaymentDialog(debt)
                }
            },
            onPdf = { debt -> shareDebtPdf(debt) },
            onLongClick = { debt -> showDebtOptions(debt) }
        )
        binding.rvBusinessDebts.layoutManager = LinearLayoutManager(this)
        binding.rvBusinessDebts.adapter = adapter
    }

    private fun showDebtOptions(debt: BusinessDebtEntity) {
        val options = arrayOf("📄 Generar PDF / Compromiso", "🗑️ Eliminar Cuenta por Pagar")
        AlertDialog.Builder(this)
            .setTitle("Opciones: ${debt.acreedor}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareDebtPdf(debt)
                    1 -> confirmDeleteDebt(debt)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeleteDebt(debt: BusinessDebtEntity) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar cuenta?")
            .setMessage("¿Estás seguro de que deseas eliminar esta cuenta por pagar? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    debt.isDeleted = true
                    database.businessDebtDao().update(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    withContext(Dispatchers.Main) {
                        loadDebts()
                        Toast.makeText(this@BusinessDebtsActivity, "Cuenta eliminada correctamente", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
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
            
            // 1. Total Global (Deudas Reales)
            val totalGlobal = debts.filter { it.categoria != "SERVICIOS" }
                .sumOf { it.montoTotal - it.montoPagado }
            
            // 2. Cálculos por períodos (Considerando la cuota o saldo)
            val cal = Calendar.getInstance()
            
            // Inicio de hoy
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val startOfToday = cal.timeInMillis
            
            // Fin de hoy
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            val endOfToday = cal.timeInMillis
            
            // Fin de semana (7 días)
            val endOfWeek = startOfToday + (7L * 24 * 60 * 60 * 1000) - 1
            
            // Fin de mes
            cal.timeInMillis = startOfToday
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            val endOfMonth = cal.timeInMillis

            var totalToday = 0.0
            var totalWeek = 0.0
            var totalMonth = 0.0

            for (d in debts) {
                val dueDate = if (d.recurrencia != "NONE") d.proximoPago else d.fechaVencimiento
                if (dueDate == 0L) continue
                
                val amountToConsider = if (d.categoria == "SERVICIOS") d.montoCuota else (d.montoTotal - d.montoPagado)

                if (dueDate in startOfToday..endOfToday) totalToday += amountToConsider
                if (dueDate in startOfToday..endOfWeek) totalWeek += amountToConsider
                if (dueDate in startOfToday..endOfMonth) totalMonth += amountToConsider
            }
            
            withContext(Dispatchers.Main) {
                adapter.submitList(debts)
                binding.tvTotalOwed.text = String.format(Locale.getDefault(), "$currency %.2f", totalGlobal)
                
                binding.tvTotalToday.text = String.format(Locale.getDefault(), "$currency %.2f", totalToday)
                binding.tvTotalWeek.text = String.format(Locale.getDefault(), "$currency %.2f", totalWeek)
                binding.tvTotalMonth.text = String.format(Locale.getDefault(), "$currency %.2f", totalMonth)
                
                binding.layoutEmptyDebts.visibility = if (debts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDebtDialog() {
        val db = DialogAddBusinessDebtBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog).setView(db.root).create()
        
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")
        db.layoutDebtMontoTotal.prefixText = currency
        db.layoutDebtMontoCuota.prefixText = currency
        
        // Establecer hints iniciales programáticamente
        db.layoutDebtConcepto.hint = "Concepto (Préstamo, Luz, Mercancía)"

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
                db.layoutMonthlyType.visibility = if (checkedId == R.id.btnRecurrenceMonthly) View.VISIBLE else View.GONE
                db.layoutDebtMonthlyDay.visibility = if (checkedId == R.id.btnRecurrenceMonthly) View.VISIBLE else View.GONE
                db.btnDebtDatePicker.visibility = if (checkedId == R.id.btnRecurrenceNone) View.VISIBLE else View.GONE
                
                // Resetear visibilidad por defecto al cambiar recurrencia
                if (checkedId != R.id.btnRecurrenceMonthly) {
                    db.layoutDebtAcreedor.visibility = View.VISIBLE
                    db.layoutDebtMontoTotal.visibility = View.VISIBLE
                    db.layoutDebtMontoCuota.visibility = View.VISIBLE
                    db.layoutDebtConcepto.hint = "Concepto (Préstamo, Luz, Mercancía)"
                } else {
                    // Si es mensual, forzar el disparador de sub-tipo
                    updateMonthlyFormFields(db, db.toggleMonthlySubType.checkedButtonId)
                }
            }
        }

        db.toggleMonthlySubType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && db.toggleDebtRecurrence.checkedButtonId == R.id.btnRecurrenceMonthly) {
                updateMonthlyFormFields(db, checkedId)
            }
        }

        db.btnCancelDebt.setOnClickListener { dialog.dismiss() }
        db.btnSaveDebt.setOnClickListener {
            val isMonthly = db.toggleDebtRecurrence.checkedButtonId == R.id.btnRecurrenceMonthly
            val isService = isMonthly && db.toggleMonthlySubType.checkedButtonId == R.id.btnMonthlyService
            
            val acreedor = if (isService) "Servicio" else db.etDebtAcreedor.text.toString().trim()
            val concepto = db.etDebtConcepto.text.toString().trim()
            val montoTotal = if (isService) 0.0 else db.etDebtMontoTotal.text.toString().toDoubleOrNull() ?: 0.0
            val montoCuota = db.etDebtMontoCuota.text.toString().toDoubleOrNull() ?: 0.0
            
            if ((isService || acreedor.isNotBlank()) && (montoTotal > 0 || isService) && (montoCuota > 0 || !isService)) {
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
                        montoTotal = if (isService) 0.0 else montoTotal, // Los servicios no tienen un "total" a liquidar, son recurrentes
                        montoCuota = montoCuota,
                        fechaVencimiento = if (recurrencia == "NONE") selectedDate else 0L,
                        recurrencia = recurrencia,
                        diaRecurrencia = diaRecurrencia,
                        proximoPago = calculateNextPayment(recurrencia, diaRecurrencia),
                        categoria = if (isService) "SERVICIOS" else "VARIOS"
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

    private fun updateMonthlyFormFields(db: DialogAddBusinessDebtBinding, selectedId: Int) {
        if (selectedId == R.id.btnMonthlyService) {
            db.layoutDebtAcreedor.visibility = View.GONE
            db.layoutDebtMontoTotal.visibility = View.GONE
            db.layoutDebtMontoCuota.hint = "Monto del Servicio"
            db.layoutDebtMontoCuota.helperText = "Monto fijo a pagar cada mes"
            db.layoutDebtConcepto.hint = "Servicio (Internet, Luz, Agua...)"
        } else {
            db.layoutDebtAcreedor.visibility = View.VISIBLE
            db.layoutDebtMontoTotal.visibility = View.VISIBLE
            db.layoutDebtMontoCuota.hint = "Monto Cuota"
            db.layoutDebtMontoCuota.helperText = "Opcional"
            db.layoutDebtConcepto.hint = "Concepto (Préstamo, Mercancía...)"
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
        builder.setTitle("Registrar Pago a ${debt.acreedor}")
        
        val isService = debt.categoria == "SERVICIOS"
        val remaining = if (isService) debt.montoCuota else debt.montoTotal - debt.montoPagado
        val currency = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("currency_symbol", "S/")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        // --- Información Detallada del Plan ---
        val tvInfo = android.widget.TextView(this).apply {
            val sb = StringBuilder()
            if (isService) {
                sb.append("🔄 Servicio Recurrente: ${debt.concepto}\n")
                sb.append("📅 Siguiente pago: $currency ${String.format(Locale.US, "%.2f", debt.montoCuota)}")
            } else {
                sb.append("💰 Saldo Actual: $currency ${String.format(Locale.US, "%.2f", remaining)}\n")
                if (debt.recurrencia == "DAILY") {
                    sb.append("🔄 Plan: PAGO DIARIO ACTIVADO\n")
                    sb.append("📅 Siguiente cuota: $currency ${String.format(Locale.US, "%.2f", debt.montoCuota)}\n")
                    sb.append("🏁 Meta Total: $currency ${String.format(Locale.US, "%.2f", debt.montoTotal)}")
                } else if (debt.montoCuota > 0) {
                    sb.append("💵 Cuota sugerida: $currency ${String.format(Locale.US, "%.2f", debt.montoCuota)}")
                }
            }
            
            text = sb.toString()
            textSize = 15f
            setTextColor(getColor(R.color.slate_700))
            setPadding(0, 0, 0, 30)
        }
        
        val etMonto = EditText(this).apply { 
            hint = "Monto a pagar ahora"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            val suggest = if (debt.montoCuota > 0) debt.montoCuota else if (remaining > 0) remaining else 0.0
            setText(if (suggest > 0) suggest.toString() else "")
        }

        layout.addView(tvInfo)
        layout.addView(etMonto)
        builder.setView(layout)

        builder.setPositiveButton("Registrar Pago") { _, _ ->
            val pago = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            if (pago > 0) {
                if (!isService && pago > remaining + 0.01) {
                    Toast.makeText(this@BusinessDebtsActivity, "⚠️ El monto excede el saldo pendiente (Máx: $currency ${String.format(Locale.US, "%.2f", remaining)})", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                
                lifecycleScope.launch(Dispatchers.IO) {
                    if (isService) {
                        // Para servicios, simplemente registramos el gasto y saltamos al siguiente pago
                        debt.proximoPago = calculateNextPayment(debt.recurrencia, debt.diaRecurrencia)
                        // Para servicios no acumulamos montoPagado contra un total de 0
                    } else {
                        debt.montoPagado += pago
                        if (debt.montoPagado >= debt.montoTotal - 0.01) {
                            debt.isPaid = true
                            debt.recurrencia = "NONE"
                            debt.montoPagado = debt.montoTotal
                        } else if (debt.recurrencia != "NONE") {
                            debt.proximoPago = calculateNextPayment(debt.recurrencia, debt.diaRecurrencia)
                        }
                    }
                    
                    database.businessDebtDao().update(debt)
                    SyncManager(this@BusinessDebtsActivity).syncBusinessDebtToCloud(debt)
                    
                    val log = MovementLogEntity(
                        type = "EXPENSE",
                        title = if (isService) "Pago de Servicio" else "Pago de Deuda",
                        description = "${debt.acreedor}: ${debt.concepto}",
                        value = "- $currency ${String.format(Locale.US, "%.2f", pago)}",
                        colorHex = "#F43F5E",
                        iconRes = if (isService) android.R.drawable.ic_menu_agenda else android.R.drawable.ic_menu_send
                    )
                    database.movementLogDao().insert(log)
                    
                    withContext(Dispatchers.Main) { 
                        loadDebts()
                        Toast.makeText(this@BusinessDebtsActivity, "Pago registrado con éxito ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        if (!isService) {
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
                        value = "- $currency ${String.format(Locale.US, "%.2f", montoLiquidado)}",
                        colorHex = "#F43F5E",
                        iconRes = android.R.drawable.checkbox_on_background
                    )
                    database.movementLogDao().insert(log)
                    withContext(Dispatchers.Main) { loadDebts() }
                }
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
        private val onPdf: (BusinessDebtEntity) -> Unit,
        private val onLongClick: (BusinessDebtEntity) -> Unit
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
            
            if (d.categoria == "SERVICIOS") {
                holder.b.tvDebtAmount.text = String.format("$currency %.2f", d.montoCuota)
                holder.b.tvDebtAmount.setTextColor(holder.itemView.context.getColor(R.color.slate_900))
            } else {
                holder.b.tvDebtAmount.text = String.format("$currency %.2f", d.montoTotal - d.montoPagado)
                holder.b.tvDebtAmount.setTextColor(holder.itemView.context.getColor(R.color.red_600))
            }
            
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
            
            holder.itemView.setOnLongClickListener {
                onLongClick(d)
                true
            }
        }
        override fun getItemCount() = list.size
    }
}
