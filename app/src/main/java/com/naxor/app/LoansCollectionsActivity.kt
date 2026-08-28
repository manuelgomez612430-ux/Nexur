package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanClientEntity
import com.naxor.app.data.LoanEntity
import com.naxor.app.data.LoanInstallmentEntity
import com.naxor.app.databinding.ActivityLoansCollectionsBinding
import com.naxor.app.databinding.ItemLoanCollectionBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class LoansCollectionsActivity : BaseActivity() {

    private lateinit var binding: ActivityLoansCollectionsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CollectionsAdapter
    private var allTodayPending: List<LoanInstallmentEntity> = emptyList()
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansCollectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarCollections.inflateMenu(R.menu.menu_loans_collections)
        binding.toolbarCollections.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_export_daily) {
                exportDailyReport()
                true
            } else false
        }

        binding.toolbarCollections.setNavigationOnClickListener { finish() }
        setupSyncIndicator()
        setupRecyclerView()
        setupSearch()
        loadCollections()
    }

    private fun setupSyncIndicator() {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                runOnUiThread { updateSyncIconState() }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback)

        lifecycleScope.launch {
            database.loanDao().getUnsyncedCount().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sincronización de Cartera")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus")
                .setPositiveButton("Sincronizar ahora") { _, _ -> 
                    SyncManager(this).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
        val color: Int
        val iconRes: Int
        
        when {
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión"
                color = android.graphics.Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6")
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun exportDailyReport() {
        if (allTodayPending.isEmpty()) {
            Toast.makeText(this, "No hay cobros hoy para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val pdfFile = com.naxor.app.util.LoanPdfGenerator(this@LoansCollectionsActivity).generateDailyCollectionReport(allTodayPending)
            pdfFile?.let { file ->
                val intent = Intent(this@LoansCollectionsActivity, PdfViewerActivity::class.java).apply {
                    putExtra("PDF_PATH", file.absolutePath)
                    putExtra("GUEST_NAME", "Reporte Diario")
                }
                startActivity(intent)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = CollectionsAdapter(
            onWhatsapp = { installment -> sendWhatsappReminder(installment) },
            onQuickPay = { installment -> showQuickPaymentDialog(installment) }
        )
        binding.rvTodayCollections.layoutManager = LinearLayoutManager(this)
        binding.rvTodayCollections.adapter = adapter
    }

    private fun showQuickPaymentDialog(installment: LoanInstallmentEntity) {
        lifecycleScope.launch {
            val loan = database.loanDao().getLoanById(installment.loanId)
            val client = loan?.let { database.loanDao().getClientById(it.clientId) }
            
            if (loan != null && client != null) {
                val remainingInInstallment = installment.amount - installment.amountPaid
                
                // Calcular mora (si existe)
                val now = System.currentTimeMillis()
                var lateFee = 0.0
                if (now > installment.dueDate) {
                    val diffMs = now - installment.dueDate
                    val daysLate = (java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs)).toInt()
                    if (daysLate > loan.graceDays) {
                        lateFee = (daysLate - loan.graceDays) * loan.lateFeeAmount
                    }
                }
                
                val totalToPayThisQuota = remainingInInstallment + lateFee

                // Inflar el nuevo diseño premium
                val dialogView = LayoutInflater.from(this@LoansCollectionsActivity).inflate(R.layout.dialog_loan_payment, null)
                val tvClient = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogClientName)
                val tvInstallment = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogInstallmentInfo)
                val tvAmountDue = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogAmountDue)
                val tvLateFee = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogLateFee)
                val etAmount = dialogView.findViewById<android.widget.EditText>(R.id.etPaymentAmount)
                val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelPayment)
                val btnConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirmPayment)

                tvClient.text = "Cliente: ${client.name}"
                tvInstallment.text = if (installment.amountPaid > 0) "SALDO RESTANTE" else "CUOTA #${installment.installmentNumber}"
                tvAmountDue.text = "Saldo pendiente: S/ ${String.format(Locale.US, "%.2f", remainingInInstallment)}"
                
                if (lateFee > 0) {
                    tvLateFee.visibility = android.view.View.VISIBLE
                    tvLateFee.text = "⚠️ Mora por atraso: S/ ${String.format(Locale.US, "%.2f", lateFee)}"
                }

                etAmount.setText("0.00")
                etAmount.requestFocus()

                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@LoansCollectionsActivity)
                    .setView(dialogView)
                    .create()
                
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                btnCancel.setOnClickListener { dialog.dismiss() }
                btnConfirm.setOnClickListener {
                    val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                    if (amount > 0) {
                        if (amount > totalToPayThisQuota + 0.01) {
                            Toast.makeText(this@LoansCollectionsActivity, "⚠️ No puede cobrar más del saldo (Máx: S/ ${String.format(Locale.US, "%.2f", totalToPayThisQuota)})", Toast.LENGTH_SHORT).show()
                        } else {
                            processQuickPayment(installment, client, loan, amount)
                            dialog.dismiss()
                        }
                    }
                }
                
                dialog.show()
            }
        }
    }

    private fun processQuickPayment(installment: LoanInstallmentEntity, client: LoanClientEntity, loan: LoanEntity, amount: Double) {
        lifecycleScope.launch {
            val updated = installment.copy(
                amountPaid = installment.amountPaid + amount,
                status = if (installment.amountPaid + amount >= installment.amount - 0.01) "PAID" else "PARTIAL"
            )
            database.loanDao().updateInstallment(updated)
            
            // REGISTRAR EN HISTORIAL
            database.loanDao().insertPayment(com.naxor.app.data.LoanPaymentEntity(
                loanId = loan.id,
                installmentId = installment.id,
                amount = amount,
                lateFeeAmount = 0.0
            ))

            // Lógica de finalización automática (Cobro rápido)
            val allInsts = database.loanDao().getInstallmentsByLoanSync(loan.id)
            val isFullyPaid = allInsts.all { 
                if (it.id == installment.id) updated.status == "PAID" 
                else it.status == "PAID" 
            }
            if (isFullyPaid) {
                database.loanDao().updateLoan(loan.copy(status = "PAID"))
            }

            SyncManager(this@LoansCollectionsActivity).scheduleOfflineSync()

            // Generar Recibo PDF
            val receipt = com.naxor.app.util.LoanPdfGenerator(this@LoansCollectionsActivity).generatePaymentReceipt(client, loan, updated, amount)
            receipt?.let { file ->
                val intent = Intent(this@LoansCollectionsActivity, PdfViewerActivity::class.java).apply {
                    putExtra("PDF_PATH", file.absolutePath)
                    putExtra("GUEST_PHONE", client.phone)
                    putExtra("GUEST_NAME", client.name)
                }
                startActivity(intent)
            }
            Toast.makeText(this@LoansCollectionsActivity, "Cobro registrado correctamente", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearch() {
        binding.etSearchCollections.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCollections(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun loadCollections() {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0)
        val start = now.timeInMillis
        now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59); now.set(Calendar.SECOND, 59)
        val end = now.timeInMillis

        lifecycleScope.launch {
            database.loanDao().getInstallmentsInRange(start, end).collectLatest { installments ->
                allTodayPending = installments.filter { it.status != "PAID" }
                filterCollections(binding.etSearchCollections.text.toString())
                
                val total = allTodayPending.sumOf { it.amount - it.amountPaid }
                binding.tvTotalToCollectToday.text = "S/ ${String.format(Locale.US, "%.2f", total)}"
            }
        }
    }

    private fun filterCollections(query: String) {
        lifecycleScope.launch {
            val filtered = if (query.isEmpty()) {
                allTodayPending
            } else {
                allTodayPending.filter { inst ->
                    val loan = database.loanDao().getLoanById(inst.loanId)
                    val client = loan?.let { database.loanDao().getClientById(it.clientId) }
                    client?.name?.contains(query, ignoreCase = true) == true
                }
            }
            adapter.submitList(filtered)
        }
    }

    private fun sendWhatsappReminder(installment: LoanInstallmentEntity) {
        lifecycleScope.launch {
            val loan = database.loanDao().getLoanById(installment.loanId)
            val client = loan?.let { database.loanDao().getClientById(it.clientId) }
            
            client?.let {
                val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                val businessType = prefs.getString("business_type", "LOANS") ?: "LOANS"
                val systemName = prefs.getString("${businessType}_name", "") ?: ""
                val ownerName = prefs.getString("${businessType}_owner_name", "") ?: ""
                
                val senderLabel = when {
                    systemName.isNotEmpty() -> systemName
                    ownerName.isNotEmpty() -> ownerName
                    else -> "tu gestor"
                }

                val montoStr = String.format(Locale.US, "%.2f", installment.amount)
                val message = "Hola *${it.name}*, te saluda *${senderLabel}*. 👋\n\nTe escribo para recordarte que hoy vence tu cuota por un valor de *S/ $montoStr*. 🗓️\n\nAgradezco mucho tu puntualidad. ¡Que tengas un excelente día! 😊"
                
                val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=${it.phone}&text=${android.net.Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            } ?: Toast.makeText(this@LoansCollectionsActivity, "Error al buscar datos del cliente", Toast.LENGTH_SHORT).show()
        }
    }

    inner class CollectionsAdapter(
        private val onWhatsapp: (LoanInstallmentEntity) -> Unit,
        private val onQuickPay: (LoanInstallmentEntity) -> Unit
    ) : RecyclerView.Adapter<CollectionsAdapter.ViewHolder>() {
        private var items = emptyList<LoanInstallmentEntity>()
        fun submitList(newItems: List<LoanInstallmentEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanCollectionBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            lifecycleScope.launch {
                val loan = database.loanDao().getLoanById(item.loanId)
                val client = loan?.let { database.loanDao().getClientById(it.clientId) }
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    with(holder.b) {
                        tvCollClientName.text = client?.name ?: "Cargando..."
                        tvCollInstallmentInfo.text = "Cuota ${item.installmentNumber} | Vence hoy"
                        tvCollAmount.text = "S/ ${String.format(Locale.US, "%.2f", item.amount - item.amountPaid)}"
                        btnCollWhatsapp.setOnClickListener { onWhatsapp(item) }
                        btnQuickPay.setOnClickListener { onQuickPay(item) }
                    }
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
