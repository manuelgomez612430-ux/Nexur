package com.naxor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.naxor.app.data.*
import com.naxor.app.databinding.ActivityLoanDetailsBinding
import com.naxor.app.databinding.ItemLoanInstallmentBinding
import com.naxor.app.util.LoanPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LoanDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoanDetailsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: InstallmentsAdapter
    private lateinit var paymentsAdapter: PaymentsAdapter
    private var currentLoan: LoanEntity? = null
    private var currentClient: LoanClientEntity? = null
    private var currentInstallments: List<LoanInstallmentEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val clientId = intent.getStringExtra("CLIENT_ID") ?: return finish()
        
        binding.toolbarLoanDetails.setNavigationOnClickListener { finish() }
        binding.btnShareLoanContract.setOnClickListener { shareContract() }
        binding.btnLiquidateLoan.setOnClickListener { showSettlementDialog() }
        binding.btnRefinanceLoan.setOnClickListener { showRefinanceDialog() }
        binding.btnEditClientInfo.setOnClickListener { showEditClientDialog() }
        binding.btnEditLoanTerms.setOnClickListener { showEditLoanTermsDialog() }
        
        binding.btnQuickPaySingle.setOnClickListener { 
            currentInstallments.firstOrNull()?.let { showPaymentDialog(it) }
        }

        binding.btnCallClient.setOnClickListener {
            currentClient?.let {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${it.phone}"))
                startActivity(intent)
            }
        }
        
        setupRecyclerView()
        loadLoanData(clientId)
    }

    private fun showEditClientDialog() {
        val client = currentClient ?: return
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_client, null)
        val etName = dialogView.findViewById<EditText>(R.id.etEditClientName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etEditClientPhone)
        val etDoc = dialogView.findViewById<EditText>(R.id.etEditClientDoc)
        val etAddress = dialogView.findViewById<EditText>(R.id.etEditClientAddress)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelEditClient)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveEditClient)

        etName.setText(client.name)
        etPhone.setText(client.phone)
        etDoc.setText(client.doc)
        etAddress.setText(client.address)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            lifecycleScope.launch {
                val updated = client.copy(
                    name = etName.text.toString().trim(),
                    phone = etPhone.text.toString().trim(),
                    doc = etDoc.text.toString().trim(),
                    address = etAddress.text.toString().trim()
                )
                database.loanDao().updateClient(updated)
                currentClient = updated
                binding.tvDetailClientName.text = updated.name
                Toast.makeText(this@LoanDetailsActivity, "Información actualizada", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        dialog.show()
    }

    private fun showEditLoanTermsDialog() {
        val loan = currentLoan ?: return
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_loan_terms, null)
        val etLateFee = dialogView.findViewById<EditText>(R.id.etEditLateFee)
        val etGraceDays = dialogView.findViewById<EditText>(R.id.etEditGraceDays)
        val btnReschedule = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogRescheduleDates)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogCancelTerms)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogSaveTerms)

        etLateFee.setText(String.format(Locale.US, "%.2f", loan.lateFeeAmount))
        etGraceDays.setText(loan.graceDays.toString())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnReschedule.setOnClickListener {
            dialog.dismiss()
            showRescheduleDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newLateFee = etLateFee.text.toString().toDoubleOrNull() ?: loan.lateFeeAmount
            val newGraceDays = etGraceDays.text.toString().toIntOrNull() ?: loan.graceDays
            
            lifecycleScope.launch {
                val updated = loan.copy(
                    lateFeeAmount = newLateFee,
                    graceDays = newGraceDays
                )
                database.loanDao().updateLoan(updated)
                currentLoan = updated
                Toast.makeText(this@LoanDetailsActivity, "Condiciones actualizadas", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadLoanData(loan.clientId)
            }
        }

        dialog.show()
    }

    private fun showRescheduleDialog() {
        val pendingInstallments = currentInstallments.filter { it.status != "PAID" }
        if (pendingInstallments.isEmpty()) {
            Toast.makeText(this, "No hay cuotas pendientes para reprogramar", Toast.LENGTH_SHORT).show()
            return
        }

        val installmentNumbers = pendingInstallments.map { "Cuota #${it.installmentNumber} - Actual: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it.dueDate))}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Seleccione Cuota a Reprogramar")
            .setItems(installmentNumbers) { _, which ->
                val target = pendingInstallments[which]
                val cal = Calendar.getInstance()
                cal.timeInMillis = target.dueDate
                
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    lifecycleScope.launch {
                        val newDateCal = Calendar.getInstance()
                        newDateCal.set(y, m, d, 23, 59, 59)
                        newDateCal.set(Calendar.MILLISECOND, 999)
                        
                        database.loanDao().updateInstallment(target.copy(dueDate = newDateCal.timeInMillis))
                        Toast.makeText(this@LoanDetailsActivity, "Fecha actualizada", Toast.LENGTH_SHORT).show()
                        loadLoanData(target.loanId)
                    }
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            .show()
    }

    private fun setupRecyclerView() {
        adapter = InstallmentsAdapter { installment -> showPaymentDialog(installment) }
        paymentsAdapter = PaymentsAdapter()
        binding.rvInstallments.layoutManager = LinearLayoutManager(this)
        binding.rvInstallments.adapter = adapter
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun loadLoanData(clientId: String) {
        lifecycleScope.launch {
            currentClient = database.loanDao().getClientById(clientId)
            binding.tvDetailClientName.text = currentClient?.name ?: "Cargando..."
            
            database.loanDao().getLoansByClient(clientId).collectLatest { loans ->
                val loan = loans.firstOrNull { it.status == "ACTIVE" || it.status == "OVERDUE" } ?: loans.firstOrNull()
                currentLoan = loan
                loan?.let { l ->
                    binding.tvDetailLoanAmount.text = "Monto Total: S/ ${String.format(Locale.US, "%.2f", l.totalToPay)}"
                    
                    database.loanDao().getInstallmentsByLoan(l.id).collectLatest { installments ->
                        currentInstallments = installments
                        
                        val pending = installments.sumOf { inst -> inst.amount - inst.amountPaid }
                        binding.tvDetailPendingBalance.text = "S/ ${String.format(Locale.US, "%.2f", pending)}"

                        val isSinglePayment = l.frequency == "NONE" || l.installmentsCount == 1
                        
                        if (isSinglePayment) {
                            binding.tvLabelInstallments.text = "HISTORIAL DE PAGOS"
                            binding.btnQuickPaySingle.visibility = if (l.status != "PAID") View.VISIBLE else View.GONE
                            binding.rvInstallments.adapter = paymentsAdapter
                            
                            database.loanDao().getPaymentsByLoan(l.id).collectLatest { payments ->
                                paymentsAdapter.submitList(payments)
                            }
                        } else {
                            binding.tvLabelInstallments.text = "CRONOGRAMA DE PAGOS"
                            binding.btnQuickPaySingle.visibility = View.GONE
                            binding.rvInstallments.adapter = adapter
                            adapter.submitList(installments)
                        }
                    }

                    if (!l.collateralDescription.isNullOrEmpty() || !l.collateralPhotoPath.isNullOrEmpty()) {
                        binding.tvLabelCollateral.visibility = View.VISIBLE
                        binding.cardCollateralDisplay.visibility = View.VISIBLE
                        binding.tvCollateralDescDetail.text = l.collateralDescription ?: "Sin descripción"
                        if (!l.collateralPhotoPath.isNullOrEmpty()) {
                            binding.ivCollateralPreview.visibility = View.VISIBLE
                            Glide.with(this@LoanDetailsActivity).load(File(l.collateralPhotoPath)).into(binding.ivCollateralPreview)
                        } else {
                            binding.ivCollateralPreview.visibility = View.GONE
                        }
                    } else {
                        binding.tvLabelCollateral.visibility = View.GONE
                        binding.cardCollateralDisplay.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun shareContract() {
        val client = currentClient
        val loan = currentLoan
        if (client != null && loan != null && currentInstallments.isNotEmpty()) {
            val pdfFile = LoanPdfGenerator(this).generateLoanContract(client, loan, currentInstallments)
            if (pdfFile != null && pdfFile.exists()) {
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Compartir Contrato"))
            }
        }
    }

    private fun showSettlementDialog() {
        val loan = currentLoan ?: return
        val pendingTotal = currentInstallments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
        
        AlertDialog.Builder(this)
            .setTitle("Liquidar Préstamo")
            .setMessage("El saldo total pendiente es de S/ ${String.format(Locale.US, "%.2f", pendingTotal)}.\n\n¿Deseas marcar todo como pagado ahora?")
            .setPositiveButton("Sí, Liquidar") { _, _ ->
                lifecycleScope.launch {
                    val installments = currentInstallments.filter { it.status != "PAID" }
                    installments.forEach { inst ->
                        database.loanDao().updateInstallment(inst.copy(amountPaid = inst.amount, status = "PAID"))
                    }
                    database.loanDao().updateLoan(loan.copy(status = "PAID"))
                    Toast.makeText(this@LoanDetailsActivity, "Préstamo liquidado por completo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRefinanceDialog() {
        val loan = currentLoan ?: return
        val pendingTotal = currentInstallments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
        
        AlertDialog.Builder(this)
            .setTitle("Refinanciar Préstamo")
            .setMessage("Se creará un nuevo préstamo por el saldo pendiente de S/ ${String.format(Locale.US, "%.2f", pendingTotal)}.\n\nEl préstamo actual se marcará como liquidado por refinanciación.")
            .setPositiveButton("Siguiente") { _, _ ->
                val intent = Intent(this, AddLoanActivity::class.java).apply {
                    putExtra("REFINANCE_CLIENT_ID", loan.clientId)
                    putExtra("REFINANCE_AMOUNT", pendingTotal)
                    putExtra("OLD_LOAN_ID", loan.id)
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPaymentDialog(installment: LoanInstallmentEntity) {
        val loan = currentLoan ?: return
        val client = currentClient ?: return
        
        val remainingInInstallment = installment.amount - installment.amountPaid
        val now = System.currentTimeMillis()
        var lateFee = 0.0
        if (now > installment.dueDate && installment.status != "PAID") {
            val diffMs = now - installment.dueDate
            val daysLate = (java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs)).toInt()
            if (daysLate > loan.graceDays) {
                lateFee = (daysLate - loan.graceDays) * loan.lateFeeAmount
            }
        }

        val totalToPayThisQuota = remainingInInstallment + lateFee
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_loan_payment, null)
        val tvClient = dialogView.findViewById<TextView>(R.id.tvDialogClientName)
        val tvInstallment = dialogView.findViewById<TextView>(R.id.tvDialogInstallmentInfo)
        val tvAmountDue = dialogView.findViewById<TextView>(R.id.tvDialogAmountDue)
        val tvLateFee = dialogView.findViewById<TextView>(R.id.tvDialogLateFee)
        val etAmount = dialogView.findViewById<EditText>(R.id.etPaymentAmount)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelPayment)
        val btnConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirmPayment)

        tvClient.text = "Cliente: ${client.name}"
        tvInstallment.text = if (installment.amountPaid > 0) "SALDO RESTANTE" else "CUOTA #${installment.installmentNumber}"
        tvAmountDue.text = "Saldo pendiente: S/ ${String.format(Locale.US, "%.2f", remainingInInstallment)}"
        
        if (lateFee > 0) {
            tvLateFee.visibility = View.VISIBLE
            tvLateFee.text = "⚠️ Mora por atraso: S/ ${String.format(Locale.US, "%.2f", lateFee)}"
        }

        etAmount.setText(String.format(Locale.US, "%.2f", totalToPayThisQuota))
        etAmount.requestFocus()
        etAmount.selectAll()
        
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etAmount, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                if (amount > totalToPayThisQuota + 0.01) {
                    Toast.makeText(this, "⚠️ No puede cobrar más del saldo (Máx: S/ ${String.format(Locale.US, "%.2f", totalToPayThisQuota)})", Toast.LENGTH_LONG).show()
                } else {
                    processPayment(installment, amount, lateFee)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun processPayment(installment: LoanInstallmentEntity, amount: Double, lateFee: Double) {
        val loan = currentLoan ?: return
        val client = currentClient ?: return
        
        lifecycleScope.launch {
            val feeToPay = Math.min(lateFee, amount)
            val capitalToPay = amount - feeToPay
            
            val updatedInstallment = installment.copy(
                amountPaid = installment.amountPaid + capitalToPay,
                lateFeePaid = installment.lateFeePaid + feeToPay,
                status = if (installment.amountPaid + capitalToPay >= installment.amount - 0.01) "PAID" else "PARTIAL"
            )
            database.loanDao().updateInstallment(updatedInstallment)
            database.loanDao().insertPayment(LoanPaymentEntity(loanId = loan.id, installmentId = installment.id, amount = capitalToPay, lateFeeAmount = feeToPay))

            val allInstallments = database.loanDao().getInstallmentsByLoanSync(loan.id)
            val allPaid = allInstallments.all { if (it.id == installment.id) updatedInstallment.status == "PAID" else it.status == "PAID" }
            if (allPaid) {
                database.loanDao().updateLoan(loan.copy(status = "PAID"))
                withContext(Dispatchers.Main) { Toast.makeText(this@LoanDetailsActivity, "¡Felicidades! Préstamo pagado por completo", Toast.LENGTH_LONG).show() }
            }

            val receipt = LoanPdfGenerator(this@LoanDetailsActivity).generatePaymentReceipt(client, loan, updatedInstallment, amount)
            receipt?.let { file ->
                val uri = FileProvider.getUriForFile(this@LoanDetailsActivity, "$packageName.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Enviar Recibo"))
            }
            Toast.makeText(this@LoanDetailsActivity, "Pago registrado", Toast.LENGTH_SHORT).show()
        }
    }

    inner class InstallmentsAdapter(private val onPay: (LoanInstallmentEntity) -> Unit) : RecyclerView.Adapter<InstallmentsAdapter.ViewHolder>() {
        private var items = emptyList<LoanInstallmentEntity>()
        fun submitList(newItems: List<LoanInstallmentEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanInstallmentBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanInstallmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val now = System.currentTimeMillis()
            with(holder.b) {
                tvInstallmentNumber.text = item.installmentNumber.toString()
                tvInstallmentAmount.text = "S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                tvInstallmentDueDate.text = "Vence: ${sdf.format(Date(item.dueDate))}"
                val statusColor = when {
                    item.status == "PAID" -> R.color.emerald_600
                    now > item.dueDate -> R.color.red_600
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) == SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(item.dueDate)) -> R.color.orange_600
                    else -> R.color.sky_600
                }
                cardInstallmentStatusColor.setCardBackgroundColor(getColor(statusColor))
                if (item.status == "PAID") { btnPayInstallment.visibility = View.GONE; ivInstallmentStatus.visibility = View.VISIBLE }
                else { btnPayInstallment.visibility = View.VISIBLE; ivInstallmentStatus.visibility = View.GONE }
                btnPayInstallment.setOnClickListener { onPay(item) }
            }
        }
        override fun getItemCount() = items.size
    }

    inner class PaymentsAdapter : RecyclerView.Adapter<PaymentsAdapter.ViewHolder>() {
        private var items = emptyList<LoanPaymentEntity>()
        fun submitList(newItems: List<LoanPaymentEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(v)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val tv1 = holder.view.findViewById<TextView>(android.R.id.text1)
            val tv2 = holder.view.findViewById<TextView>(android.R.id.text2)
            tv1.text = "Abono de S/ ${String.format(Locale.US, "%.2f", item.amount + item.lateFeeAmount)}"
            tv1.setTextColor(getColor(R.color.emerald_600))
            tv1.setTypeface(null, android.graphics.Typeface.BOLD)
            tv2.text = "Fecha: ${sdf.format(Date(item.timestamp))}"
            if (item.lateFeeAmount > 0) { tv2.text = tv2.text.toString() + " (Incluye S/ ${String.format(Locale.US, "%.2f", item.lateFeeAmount)} de mora)" }
            tv2.setTextColor(getColor(R.color.slate_500))
        }
        override fun getItemCount() = items.size
    }
}
