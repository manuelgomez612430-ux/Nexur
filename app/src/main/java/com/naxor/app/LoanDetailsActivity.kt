package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.naxor.app.data.*
import com.naxor.app.databinding.ActivityLoanDetailsBinding
import com.naxor.app.databinding.ItemLoanInstallmentBinding
import com.naxor.app.util.LoanPdfGenerator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LoanDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoanDetailsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: InstallmentsAdapter
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
        
        binding.btnCallClient.setOnClickListener {
            currentClient?.let {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${it.phone}"))
                startActivity(intent)
            }
        }
        
        binding.btnViewClientLocation.setOnClickListener {
            currentClient?.let {
                if (it.latitude != null && it.longitude != null) {
                    val uri = android.net.Uri.parse("geo:${it.latitude},${it.longitude}?q=${it.latitude},${it.longitude}(${it.name})")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.setPackage("com.google.android.apps.maps")
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "El cliente no tiene ubicación guardada", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setupRecyclerView()
        loadLoanData(clientId)
    }

    private fun showEditClientDialog() {
        val client = currentClient ?: return
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val etName = android.widget.EditText(this).apply { hint = "Nombre"; setText(client.name) }
        val etPhone = android.widget.EditText(this).apply { hint = "Teléfono"; setText(client.phone); inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val etDoc = android.widget.EditText(this).apply { hint = "DNI / RUC"; setText(client.doc) }
        val etAddress = android.widget.EditText(this).apply { hint = "Dirección"; setText(client.address) }

        layout.addView(etName); layout.addView(etPhone); layout.addView(etDoc); layout.addView(etAddress)

        AlertDialog.Builder(this)
            .setTitle("Editar Perfil del Cliente")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
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
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun setupRecyclerView() {
        adapter = InstallmentsAdapter { installment -> showPaymentDialog(installment) }
        binding.rvInstallments.layoutManager = LinearLayoutManager(this)
        binding.rvInstallments.adapter = adapter
    }

    private fun loadLoanData(clientId: String) {
        lifecycleScope.launch {
            currentClient = database.loanDao().getClientById(clientId)
            binding.tvDetailClientName.text = currentClient?.name ?: "Cargando..."
            
            database.loanDao().getLoansByClient(clientId).collectLatest { loans ->
                val loan = loans.firstOrNull { it.status == "ACTIVE" } ?: loans.firstOrNull()
                currentLoan = loan
                loan?.let {
                    binding.tvDetailLoanAmount.text = "Préstamo de S/ ${String.format(Locale.US, "%.2f", it.totalToPay)}"
                    
                    // Mostrar Garantía
                    if (!it.collateralDescription.isNullOrEmpty() || !it.collateralPhotoPath.isNullOrEmpty()) {
                        binding.tvLabelCollateral.visibility = View.VISIBLE
                        binding.cardCollateralDisplay.visibility = View.VISIBLE
                        binding.tvCollateralDescDetail.text = it.collateralDescription ?: "Sin descripción"
                        if (!it.collateralPhotoPath.isNullOrEmpty()) {
                            binding.ivCollateralPreview.visibility = View.VISIBLE
                            Glide.with(this@LoanDetailsActivity).load(File(it.collateralPhotoPath)).into(binding.ivCollateralPreview)
                        } else {
                            binding.ivCollateralPreview.visibility = View.GONE
                        }
                    } else {
                        binding.tvLabelCollateral.visibility = View.GONE
                        binding.cardCollateralDisplay.visibility = View.GONE
                    }

                    database.loanDao().getInstallmentsByLoan(it.id).collectLatest { installments ->
                        currentInstallments = installments
                        adapter.submitList(installments)
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
        
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText((installment.amount - installment.amountPaid).toString())
        }

        val now = System.currentTimeMillis()
        var lateFee = 0.0
        if (now > installment.dueDate && installment.status != "PAID") {
            val diffMs = now - installment.dueDate
            val daysLate = (java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs)).toInt()
            if (daysLate > loan.graceDays) {
                lateFee = (daysLate - loan.graceDays) * loan.lateFeeAmount
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Pago Cuota #${installment.installmentNumber}")
            
        val msg = StringBuilder("Monto cuota: S/ ${String.format(Locale.US, "%.2f", installment.amount)}\n")
        if (lateFee > 0) {
            msg.append("⚠️ Mora acumulada (${loan.lateFeeAmount} x día): S/ ${String.format(Locale.US, "%.2f", lateFee)}\n")
            msg.append("Total sugerido: S/ ${String.format(Locale.US, "%.2f", (installment.amount - installment.amountPaid) + lateFee)}")
        }
        builder.setMessage(msg.toString())
        builder.setView(et)

        builder.setPositiveButton("Pagar") { _, _ ->
            val amount = et.text.toString().toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                processPayment(installment, amount, lateFee)
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
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
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) == 
                        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(item.dueDate)) -> R.color.orange_600
                    else -> R.color.sky_600
                }
                cardInstallmentStatusColor.setCardBackgroundColor(getColor(statusColor))

                if (item.status == "PAID") {
                    btnPayInstallment.visibility = View.GONE
                    ivInstallmentStatus.visibility = View.VISIBLE
                } else {
                    btnPayInstallment.visibility = View.VISIBLE
                    ivInstallmentStatus.visibility = View.GONE
                }
                btnPayInstallment.setOnClickListener { onPay(item) }
            }
        }
        override fun getItemCount() = items.size
    }
}
