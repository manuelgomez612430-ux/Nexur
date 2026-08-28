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
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.naxor.app.data.*
import com.naxor.app.databinding.ActivityLoanDetailsBinding
import com.naxor.app.databinding.ItemLoanInstallmentBinding
import com.naxor.app.databinding.ItemLoanPaymentBinding
import com.naxor.app.util.LoanPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class LoanDetailsActivity : BaseActivity() {

    private lateinit var binding: ActivityLoanDetailsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: InstallmentsAdapter
    private lateinit var paymentsAdapter: PaymentsAdapter
    private var currentLoan: LoanEntity? = null
    private var currentClient: LoanClientEntity? = null
    private var currentInstallments: List<LoanInstallmentEntity> = emptyList()
    private var loadDataJob: Job? = null
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val clientId = intent.getStringExtra("CLIENT_ID") ?: return finish()
        
        binding.toolbarLoanDetails.setNavigationOnClickListener { finish() }
        binding.btnDeleteClient.setOnClickListener { showDeleteClientConfirmation() }
        binding.btnShareLoanContract.setOnClickListener { showPdfOptionsDialog() }
        binding.btnLiquidateLoan.setOnClickListener { showSettlementDialog() }
        binding.btnRefinanceLoan.setOnClickListener { showRefinanceDialog() }
        binding.btnEditClientInfo.setOnClickListener { showEditClientDialog() }
        binding.btnEditLoanTerms.setOnClickListener { showEditLoanTermsDialog() }
        binding.btnIncreaseDebt.setOnClickListener { showIncreaseDebtDialog() }
        
        binding.btnQuickPaySingle.setOnClickListener { 
            currentInstallments.firstOrNull { it.status != "PAID" }?.let { showPaymentDialog(it) }
        }

        binding.btnCallClient.setOnClickListener {
            currentClient?.let {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${it.phone}"))
                startActivity(intent)
            }
        }
        
        setupSyncIndicator()
        setupRecyclerView()
        loadLoanData(clientId)
    }

    private fun showDeleteClientConfirmation() {
        val client = currentClient ?: return
        AlertDialog.Builder(this)
            .setTitle("🗑️ ¿Eliminar Cliente?")
            .setMessage("Esta acción eliminará a ${client.name} y TODOS sus préstamos, pagos e historial de forma permanente. ¿Estás seguro?")
            .setPositiveButton("Sí, Eliminar Todo") { _, _ ->
                performClientDeletion(client.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performClientDeletion(clientId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Marcar como borrado localmente (Atómico)
                database.loanDao().deleteClientFully(clientId)

                // 2. Notificar a la nube (Eliminación real en Firestore)
                SyncManager(this@LoanDetailsActivity).deleteLoanClientFromCloud(clientId)
                SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoanDetailsActivity, "Cliente eliminado correctamente", Toast.LENGTH_SHORT).show()
                    finish() // Salir del detalle
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoanDetailsActivity, "Error al eliminar cliente", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

    private fun showIncreaseDebtDialog() {
        val options = arrayOf("Aumento Directo (Sin Interés)", "Nueva Deuda Estructurada (Interés/Cuotas)")
        AlertDialog.Builder(this)
            .setTitle("Seleccione tipo de aumento")
            .setItems(options) { _, which ->
                if (which == 0) showAumentoDirectoDialog()
                else showNuevaDeudaEstructuradaDialog()
            }
            .show()
    }

    private fun showAumentoDirectoDialog() {
        val etAmount = EditText(this).apply {
            hint = "Monto a entregar (S/)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            addView(etAmount)
        }

        AlertDialog.Builder(this)
            .setTitle("Aumento Directo")
            .setMessage("Este monto se sumará a la deuda total sin intereses adicionales.")
            .setView(layout)
            .setPositiveButton("Sumar") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                if (amount > 0) performDebtIncrease(amount, 0.0, 1, System.currentTimeMillis() + (24 * 60 * 60 * 1000))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNuevaDeudaEstructuradaDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_loan_structured, null)
        val etAmount = dialogView.findViewById<EditText>(R.id.etStructuredAmount)
        val etInterest = dialogView.findViewById<EditText>(R.id.etStructuredInterest)
        val etInstallments = dialogView.findViewById<EditText>(R.id.etStructuredInstallments)
        val btnDate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStructuredDate)
        
        var selectedDate = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
            android.app.DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                selectedDate = cal.timeInMillis
                btnDate.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        AlertDialog.Builder(this)
            .setTitle("Nueva Deuda Estructurada")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                val interest = etInterest.text.toString().toDoubleOrNull() ?: 20.0
                val installments = etInstallments.text.toString().toIntOrNull() ?: 1
                if (amount > 0) performDebtIncrease(amount, interest, installments, selectedDate)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performDebtIncrease(amount: Double, interest: Double, installments: Int, startDate: Long) {
        val client = currentClient ?: return
        val isSimple = interest == 0.0 && installments == 1
        
        lifecycleScope.launch(Dispatchers.IO) {
            val total = amount + (amount * (interest / 100))
            val loanId = UUID.randomUUID().toString()
            val newLoan = LoanEntity(
                id = loanId,
                clientId = client.id,
                amount = amount,
                interestRate = interest,
                totalToPay = total,
                installmentsCount = installments,
                frequency = if (installments > 1) "DAILY" else "NONE",
                startDate = System.currentTimeMillis(),
                isSynced = false
            )
            
            database.runInTransaction {
                database.loanDao().insertLoanTransaction(newLoan)
                
                val schedule = mutableListOf<LoanInstallmentEntity>()
                val quotaAmount = total / installments
                val cal = Calendar.getInstance().apply { timeInMillis = startDate }
                
                for (i in 1..installments) {
                    schedule.add(LoanInstallmentEntity(
                        loanId = loanId,
                        installmentNumber = i,
                        amount = quotaAmount,
                        dueDate = cal.timeInMillis,
                        isSynced = false
                    ))
                    if (installments > 1) cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                database.loanDao().insertInstallmentsTransaction(schedule)
                
                // REGISTRAR EN EL HISTORIAL COMO AUMENTO (TYPE = 1)
                database.loanDao().insertPaymentTransaction(LoanPaymentEntity(
                    loanId = loanId,
                    installmentId = null,
                    amount = amount, // Usamos el capital original entregado para el historial
                    type = 1, // AUMENTO
                    isSynced = false
                ))
            }
            
            SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) {
                val msg = if (isSimple) "Deuda aumentada OK" else "Nueva deuda estructurada creada correctamente ✅"
                Toast.makeText(this@LoanDetailsActivity, msg, Toast.LENGTH_SHORT).show()
                loadLoanData(client.id)
            }
        }
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

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
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
                SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                Toast.makeText(this@LoanDetailsActivity, "Información actualizada", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditLoanTermsDialog() {
        val loan = currentLoan ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_loan_terms, null)
        val togglePlan = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleEditPlan)
        val btnStartDate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartDate)
        val etLateFeePercent = dialogView.findViewById<EditText>(R.id.etEditLateFeePercent)
        val etLateFeeAmountAuto = dialogView.findViewById<EditText>(R.id.etEditLateFeeAmountAuto)
        val etGraceDays = dialogView.findViewById<EditText>(R.id.etEditGraceDays)
        val btnReschedule = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogRescheduleDates)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogCancelTerms)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogSaveTerms)

        var tempStartDate = loan.startDate
        
        // Base para el cálculo (Monto de la cuota)
        val quotaBase = if (loan.installmentsCount > 0) loan.totalToPay / loan.installmentsCount else loan.totalToPay
        
        // Cargar valores actuales
        val planButtonId = when(loan.frequency) {
            "WEEKLY" -> R.id.btnEditFreqWeekly
            "MONTHLY" -> R.id.btnEditFreqMonthly
            "NONE" -> R.id.btnEditFreqNone
            else -> R.id.btnEditFreqDaily
        }
        togglePlan.check(planButtonId)
        etGraceDays.setText(loan.graceDays.toString())
        btnStartDate.text = "📅 Inicio: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(tempStartDate))

        // Calcular porcentaje actual basado en el monto guardado
        val currentPercent = if (quotaBase > 0) (loan.lateFeeAmount / quotaBase) * 100 else 0.0
        if (currentPercent > 0) {
            etLateFeePercent.setText(String.format(Locale.US, "%.1f", currentPercent))
        } else {
            etLateFeePercent.setText("")
        }
        etLateFeeAmountAuto.setText(String.format(Locale.US, "%.2f", loan.lateFeeAmount))

        // Lógica de cálculo automático en tiempo real
        etLateFeePercent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val percent = s.toString().toDoubleOrNull() ?: 0.0
                val calculatedAmount = quotaBase * (percent / 100)
                etLateFeeAmountAuto.setText(String.format(Locale.US, "%.2f", calculatedAmount))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnStartDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = tempStartDate }
            android.app.DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                tempStartDate = cal.timeInMillis
                btnStartDate.text = "📅 Inicio: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(tempStartDate))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnReschedule.setOnClickListener { dialog.dismiss(); showRescheduleDialog() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        
        btnSave.setOnClickListener {
            val newFreq = when(togglePlan.checkedButtonId) {
                R.id.btnEditFreqWeekly -> "WEEKLY"
                R.id.btnEditFreqMonthly -> "MONTHLY"
                R.id.btnEditFreqNone -> "NONE"
                else -> "DAILY"
            }
            // Guardamos el monto final calculado, no el porcentaje
            val newLateFee = etLateFeeAmountAuto.text.toString().toDoubleOrNull() ?: loan.lateFeeAmount
            val newGraceDays = etGraceDays.text.toString().toIntOrNull() ?: loan.graceDays
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val updatedLoan = loan.copy(
                        frequency = newFreq,
                        startDate = tempStartDate,
                        lateFeeAmount = newLateFee,
                        graceDays = newGraceDays,
                        isSynced = false
                    )
                    
                    database.runInTransaction {
                        database.loanDao().updateLoanTransaction(updatedLoan)
                        
                        // Si cambió la fecha de inicio o la frecuencia, reprogramar cuotas pendientes
                        if (newFreq != loan.frequency || tempStartDate != loan.startDate) {
                            val unpaid = currentInstallments.filter { it.status != "PAID" }.sortedBy { it.installmentNumber }
                            val cal = Calendar.getInstance().apply { timeInMillis = tempStartDate }
                            
                            unpaid.forEachIndexed { index, inst ->
                                if (index > 0 || (index == 0 && newFreq == "NONE")) {
                                    when(newFreq) {
                                        "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                                        "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                                        "MONTHLY" -> cal.add(Calendar.MONTH, 1)
                                    }
                                }
                                
                                database.loanDao().updateInstallmentTransaction(inst.copy(
                                    dueDate = cal.timeInMillis,
                                    isSynced = false
                                ))
                            }
                        }
                    }
                    
                    SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoanDetailsActivity, "Condiciones y calendario actualizados ✅", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadLoanData(loan.clientId)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoanDetailsActivity, "Error al actualizar", Toast.LENGTH_SHORT).show()
                    }
                }
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
                val cal = Calendar.getInstance().apply { timeInMillis = target.dueDate }
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    lifecycleScope.launch {
                        val newDateCal = Calendar.getInstance()
                        newDateCal.set(y, m, d, 23, 59, 59)
                        newDateCal.set(Calendar.MILLISECOND, 999)
                        database.loanDao().updateInstallment(target.copy(dueDate = newDateCal.timeInMillis))
                        SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                        Toast.makeText(this@LoanDetailsActivity, "Fecha actualizada", Toast.LENGTH_SHORT).show()
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
        loadDataJob?.cancel()
        loadDataJob = lifecycleScope.launch {
            // Escuchar el cliente
            launch {
                database.loanDao().getClientByIdFlow(clientId).collectLatest { client ->
                    currentClient = client
                    if (!::binding.isInitialized) return@collectLatest
                    binding.tvDetailClientName.text = client?.name ?: "Cargando..."
                    binding.tvDetailClientAddress.text = "📍 " + (client?.address ?: "")
                    binding.tvDetailClientAddress.visibility = if (client?.address.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            }

            // Escuchar la deuda total
            launch {
                database.loanDao().getTotalDebtByClientFlow(clientId).collectLatest { balance ->
                    if (!::binding.isInitialized) return@collectLatest
                    binding.tvDetailPendingBalance.text = "S/ ${String.format(Locale.US, "%.2f", balance ?: 0.0)}"
                }
            }

            // Escuchar los préstamos y cuotas para el listado
            database.loanDao().getLoansByClient(clientId).collectLatest { loans ->
                withContext(Dispatchers.IO) {
                    val activeLoans = loans.filter { it.status == "ACTIVE" || it.status == "OVERDUE" }
                    val loan = activeLoans.firstOrNull() ?: loans.firstOrNull()
                    currentLoan = loan
                    
                    var totalLoanAmount = 0.0
                    var hasAnyOverdue = false
                    val now = System.currentTimeMillis()
                    
                    val allActiveInstallments = mutableListOf<LoanInstallmentEntity>()
                    
                    loans.forEach { l ->
                        if (l.status == "ACTIVE" || l.status == "OVERDUE") {
                            val installments = database.loanDao().getInstallmentsByLoanSync(l.id)
                            allActiveInstallments.addAll(installments)
                            totalLoanAmount += l.totalToPay
                            if (installments.any { it.status != "PAID" && it.dueDate < now }) {
                                hasAnyOverdue = true
                            }
                        }
                    }
                    
                    val sortedInstallments = allActiveInstallments.sortedBy { it.dueDate }

                    withContext(Dispatchers.Main) {
                        if (!::binding.isInitialized) return@withContext
                        
                        binding.tvDetailLoanAmount.text = "Deuda Total: S/ ${String.format(Locale.US, "%.2f", totalLoanAmount)}"

                        if (hasAnyOverdue) {
                            binding.cardPendingBalanceContainer.setCardBackgroundColor(getColor(R.color.red_600))
                            binding.tvPendingBalanceLabel.setTextColor(getColor(R.color.white))
                            binding.tvDetailPendingBalance.setTextColor(getColor(R.color.white))
                        } else {
                            binding.cardPendingBalanceContainer.setCardBackgroundColor(getColor(R.color.emerald_50))
                            binding.tvPendingBalanceLabel.setTextColor(getColor(R.color.emerald_600))
                            binding.tvDetailPendingBalance.setTextColor(getColor(R.color.emerald_700))
                        }

                        currentInstallments = sortedInstallments
                        loan?.let { l ->
                            // Mostrar u ocultar garantía
                            binding.tvLabelCollateral.visibility = if (l.collateralDescription.isNullOrEmpty() && l.collateralPhotoPath.isNullOrEmpty()) View.GONE else View.VISIBLE
                            binding.cardCollateralDisplay.visibility = if (l.collateralDescription.isNullOrEmpty() && l.collateralPhotoPath.isNullOrEmpty()) View.GONE else View.VISIBLE
                            binding.tvCollateralDescDetail.text = l.collateralDescription ?: ""
                            
                            if (!l.collateralPhotoPath.isNullOrEmpty()) {
                                val file = File(l.collateralPhotoPath)
                                if (file.exists()) {
                                    Glide.with(this@LoanDetailsActivity).load(file).into(binding.ivCollateralPreview)
                                    binding.ivCollateralPreview.visibility = View.VISIBLE
                                } else {
                                    binding.ivCollateralPreview.visibility = View.GONE
                                }
                            } else {
                                binding.ivCollateralPreview.visibility = View.GONE
                            }

                            // ESCUCHAR TODOS LOS PAGOS DEL CLIENTE PARA EL HISTORIAL UNIFICADO
                            lifecycleScope.launch(Dispatchers.IO) {
                                database.loanDao().getAllPaymentsByClientFlow(clientId).collectLatest { payments ->
                                    withContext(Dispatchers.Main) {
                                        if (::binding.isInitialized) {
                                            paymentsAdapter.submitList(payments)
                                        }
                                    }
                                }
                            }

                            val allAreSingle = activeLoans.all { it.frequency == "NONE" || it.installmentsCount == 1 }
                            if (allAreSingle) {
                                binding.tvLabelInstallments.text = "HISTORIAL DE ACTIVIDAD"
                                binding.cardQuickPayContainer.visibility = if (activeLoans.any { it.status != "PAID" }) View.VISIBLE else View.GONE
                                binding.rvInstallments.adapter = paymentsAdapter
                            } else {
                                binding.tvLabelInstallments.text = "ESTADO DE CUOTAS (UNIFICADO)"
                                binding.cardQuickPayContainer.visibility = if (sortedInstallments.any { it.status != "PAID" }) View.VISIBLE else View.GONE
                                binding.rvInstallments.adapter = adapter
                                adapter.submitList(sortedInstallments)
                            }

                            // Lógica de Garantía
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
        }
    }

    private fun showPdfOptionsDialog() {
        val options = arrayOf("📄 Generar Contrato Formal", "🔔 Generar Recordatorio de Pago")
        AlertDialog.Builder(this)
            .setTitle("Seleccione el tipo de documento")
            .setItems(options) { _, which ->
                if (which == 0) shareContract() else shareReminder()
            }.show()
    }

    private fun shareReminder() {
        val client = currentClient ?: return
        val loan = currentLoan ?: return
        val totalPending = currentInstallments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
        LoanPdfGenerator(this).generateDebtReminder(client, loan, totalPending)?.let { file ->
            val intent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra("PDF_PATH", file.absolutePath)
                putExtra("GUEST_PHONE", client.phone)
                putExtra("GUEST_NAME", client.name)
            }
            startActivity(intent)
        }
    }

    private fun shareContract() {
        val client = currentClient
        val loan = currentLoan
        if (client != null && loan != null && currentInstallments.isNotEmpty()) {
            LoanPdfGenerator(this).generateLoanContract(client, loan, currentInstallments)?.let { file ->
                val intent = Intent(this, PdfViewerActivity::class.java).apply {
                    putExtra("PDF_PATH", file.absolutePath)
                    putExtra("GUEST_PHONE", client.phone)
                    putExtra("GUEST_NAME", client.name)
                }
                startActivity(intent)
            }
        }
    }

    private fun showSettlementDialog() {
        val client = currentClient ?: return
        val pendingTotal = currentInstallments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
        
        AlertDialog.Builder(this)
            .setTitle("Liquidar Deuda Total")
            .setMessage("El saldo total acumulado de todas las deudas es de S/ ${String.format(Locale.US, "%.2f", pendingTotal)}.\n\n¿Deseas marcar TODO como pagado ahora?")
            .setPositiveButton("Sí, Liquidar Todo") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val activeLoans = database.loanDao().getLoansByClientSync(client.id).filter { it.status != "PAID" }
                    
                    database.runInTransaction {
                        activeLoans.forEach { loan ->
                            val installments = database.loanDao().getInstallmentsByLoanTransaction(loan.id)
                            installments.filter { it.status != "PAID" }.forEach { inst ->
                                database.loanDao().updateInstallmentTransaction(inst.copy(
                                    amountPaid = inst.amount, 
                                    status = "PAID",
                                    isSynced = false
                                ))
                                database.loanDao().insertPaymentTransaction(LoanPaymentEntity(
                                    loanId = loan.id,
                                    installmentId = inst.id,
                                    amount = inst.amount - inst.amountPaid,
                                    isSynced = false
                                ))
                            }
                            database.loanDao().updateLoanTransaction(loan.copy(
                                status = "PAID",
                                isSynced = false
                            ))
                        }
                    }
                    SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoanDetailsActivity, "¡Toda la deuda ha sido liquidada!", Toast.LENGTH_SHORT).show()
                    }
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
            .setMessage("Se creará un nuevo préstamo por el saldo pendiente de S/ ${String.format(Locale.US, "%.2f", pendingTotal)}.")
            .setPositiveButton("Siguiente") { _, _ ->
                val intent = Intent(this, AddLoanActivity::class.java).apply {
                    putExtra("REFINANCE_CLIENT_ID", loan.clientId)
                    putExtra("REFINANCE_AMOUNT", pendingTotal)
                    putExtra("OLD_LOAN_ID", loan.id)
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showPaymentDialog(installment: LoanInstallmentEntity) {
        val loan = currentLoan ?: return
        val client = currentClient ?: return
        val totalPendingDebt = currentInstallments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
        val now = System.currentTimeMillis()
        var lateFee = 0.0
        if (now > installment.dueDate && installment.status != "PAID") {
            val daysLate = (java.util.concurrent.TimeUnit.MILLISECONDS.toDays(now - installment.dueDate)).toInt()
            if (daysLate > loan.graceDays) lateFee = (daysLate - loan.graceDays) * loan.lateFeeAmount
        }

        val totalToPayThisAmount = totalPendingDebt + lateFee
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_loan_payment, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvClient = dialogView.findViewById<TextView>(R.id.tvDialogClientName)
        val tvInstallment = dialogView.findViewById<TextView>(R.id.tvDialogInstallmentInfo)
        val tvAmountDue = dialogView.findViewById<TextView>(R.id.tvDialogAmountDue)
        val tvLateFee = dialogView.findViewById<TextView>(R.id.tvDialogLateFee)
        val etAmount = dialogView.findViewById<EditText>(R.id.etPaymentAmount)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelPayment)
        val btnConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirmPayment)

        tvTitle.text = "Registrar Cobro"
        tvClient.text = "Cliente: ${client.name}"
        tvInstallment.text = "SALDO RESTANTE ACUMULADO"
        tvAmountDue.text = "Saldo pendiente: S/ ${String.format(Locale.US, "%.2f", totalPendingDebt)}"
        if (lateFee > 0) {
            tvLateFee.visibility = View.VISIBLE
            tvLateFee.text = "⚠️ Mora por atraso: S/ ${String.format(Locale.US, "%.2f", lateFee)}"
        }

        etAmount.setText("0.00")
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
            val amountValue = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (amountValue > 0) {
                if (amountValue > totalToPayThisAmount + 0.01) {
                    Toast.makeText(this, "⚠️ No puede cobrar más de la deuda total (Máx: S/ ${String.format(Locale.US, "%.2f", totalToPayThisAmount)})", Toast.LENGTH_LONG).show()
                } else {
                    processUnifiedPayment(amountValue, lateFee)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun processUnifiedPayment(amount: Double, lateFee: Double) {
        val client = currentClient ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var remainingPayment = amount
            database.runInTransaction {
                val feeToPay = Math.min(lateFee, remainingPayment)
                remainingPayment -= feeToPay
                val pendingInstallments = currentInstallments.filter { it.status != "PAID" }
                for (inst in pendingInstallments) {
                    if (remainingPayment <= 0.0) break
                    val debtInThisInst = inst.amount - inst.amountPaid
                    val paymentForThisInst = Math.min(debtInThisInst, remainingPayment)
                    val newAmountPaid = inst.amountPaid + paymentForThisInst
                    database.loanDao().updateInstallmentTransaction(inst.copy(
                        amountPaid = newAmountPaid,
                        status = if (newAmountPaid >= inst.amount - 0.01) "PAID" else "PARTIAL",
                        isSynced = false
                    ))
                    database.loanDao().insertPaymentTransaction(LoanPaymentEntity(
                        loanId = inst.loanId, 
                        installmentId = inst.id, 
                        amount = paymentForThisInst, 
                        lateFeeAmount = if (inst.id == pendingInstallments.first().id) feeToPay else 0.0,
                        isSynced = false
                    ))
                    remainingPayment -= paymentForThisInst
                    val loanInsts = database.loanDao().getInstallmentsByLoanTransaction(inst.loanId)
                    if (loanInsts.all { it.status == "PAID" }) {
                        val loan = database.loanDao().getLoanByIdTransaction(inst.loanId)
                        loan?.let { 
                            database.loanDao().updateLoanTransaction(it.copy(
                                status = "PAID",
                                isSynced = false
                            )) 
                        }
                    }
                }
            }
            SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) { Toast.makeText(this@LoanDetailsActivity, "Cobro registrado correctamente", Toast.LENGTH_SHORT).show() }
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
            with(holder.b) {
                tvInstallmentNumber.text = item.installmentNumber.toString()
                tvInstallmentAmount.text = "S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                tvInstallmentDueDate.text = "Vence: ${sdf.format(Date(item.dueDate))}"
                val now = System.currentTimeMillis()
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
        
        inner class ViewHolder(val b: ItemLoanPaymentBinding) : RecyclerView.ViewHolder(b.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanPaymentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            
            with(holder.b) {
                if (item.type == 1) {
                    tvPaymentAmount.text = "Aumento: + S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                    tvPaymentAmount.setTextColor(getColor(R.color.indigo_500))
                } else {
                    tvPaymentAmount.text = "Abono de S/ ${String.format(Locale.US, "%.2f", item.amount + item.lateFeeAmount)}"
                    tvPaymentAmount.setTextColor(getColor(R.color.emerald_600))
                }
                
                tvPaymentDate.text = sdf.format(Date(item.timestamp))
                
                if (item.lateFeeAmount > 0) {
                    tvPaymentDetail.visibility = View.VISIBLE
                    tvPaymentDetail.text = "Incluye S/ ${String.format(Locale.US, "%.2f", item.lateFeeAmount)} de mora"
                } else {
                    tvPaymentDetail.visibility = View.GONE
                }
                
                btnDownloadReceipt.setOnClickListener {
                    lifecycleScope.launch {
                        val client = currentClient ?: return@launch
                        val loan = database.loanDao().getLoanById(item.loanId) ?: return@launch
                        
                        // Si tiene installmentId, buscar la cuota, sino crear una "virtual" para el recibo
                        val inst = if (item.installmentId != null) {
                            database.loanDao().getInstallmentById(item.installmentId)
                        } else {
                            LoanInstallmentEntity(
                                loanId = item.loanId,
                                installmentNumber = 0,
                                amount = item.amount,
                                dueDate = item.timestamp,
                                status = "PAID"
                            )
                        } ?: return@launch

                        val receipt = LoanPdfGenerator(this@LoanDetailsActivity).generatePaymentReceipt(client, loan, inst, item.amount + item.lateFeeAmount)
                        receipt?.let { file ->
                            val intent = Intent(this@LoanDetailsActivity, PdfViewerActivity::class.java).apply {
                                putExtra("PDF_PATH", file.absolutePath)
                                putExtra("GUEST_PHONE", client.phone)
                                putExtra("GUEST_NAME", client.name)
                            }
                            startActivity(intent)
                        }
                    }
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
