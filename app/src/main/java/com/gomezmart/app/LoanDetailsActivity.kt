package com.gomezmart.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gomezmart.app.data.*
import com.gomezmart.app.databinding.ActivityLoanDetailsBinding
import com.gomezmart.app.databinding.ItemLoanInstallmentBinding
import com.gomezmart.app.databinding.ItemLoanPaymentBinding
import com.gomezmart.app.util.LoanPdfGenerator
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
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
    // Origen de fondos leído de la tabla protegida (inmune a la sincronización)
    private var protectedFundingSource: Boolean? = null
    private var loadDataJob: Job? = null
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    private var selectedPaymentReceiptUri: Uri? = null
    private var paymentReceiptFile: File? = null
    private var currentPaymentReceiptCard: View? = null
    private var currentPaymentReceiptImage: ImageView? = null
    private var pendingPhoneEditText: EditText? = null

    private val pickPhoneContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)?.replace(" ", "")?.replace("-", "")?.trim() ?: ""
                    pendingPhoneEditText?.setText(number)
                    pendingPhoneEditText?.setSelection(number.length)
                }
            }
        }
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchPhoneContactPicker()
        } else {
            Toast.makeText(this, "Permiso denegado para acceder a contactos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPhoneContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        pickPhoneContactLauncher.launch(intent)
    }

    private val pickPaymentReceiptLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPaymentReceiptUri = uri
            currentPaymentReceiptCard?.visibility = View.VISIBLE
            currentPaymentReceiptImage?.let {
                Glide.with(this).load(uri).centerCrop().into(it)
            }
        }
    }

    private val takePaymentReceiptLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            paymentReceiptFile?.let { file ->
                selectedPaymentReceiptUri = Uri.fromFile(file)
                currentPaymentReceiptCard?.visibility = View.VISIBLE
                currentPaymentReceiptImage?.let {
                    Glide.with(this).load(file).centerCrop().into(it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets de pantalla (Top para la barra de estado/cámara y Bottom para gestos/navegación)
        applyWindowInsets(binding.appBarLoanDetails, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.cardBottomMoneyActionsContainer, applyTop = false, applyBottom = true)

        val clientId = intent.getStringExtra("CLIENT_ID") ?: return finish()
        
        binding.toolbarLoanDetails.setNavigationOnClickListener { finish() }
        binding.btnDeleteClient.setOnClickListener { showDeleteClientConfirmation() }
        binding.btnShareLoanContract.setOnClickListener { showPdfOptionsDialog() }
        binding.btnLiquidateLoan.setOnClickListener { showSettlementDialog() }
        binding.btnEditClientInfo.setOnClickListener { showEditClientDialog() }
        binding.btnEditLoanTerms.setOnClickListener { showEditLoanTermsDialog() }
        binding.btnIncreaseDebt.setOnClickListener { showIncreaseDebtDialog() }
        binding.cardFundingSource.setOnClickListener { showFundingSourceDialog() }
        
        binding.btnQuickPaySingle.setOnClickListener { 
            currentInstallments.firstOrNull { it.status != "PAID" }?.let { showPaymentDialog(it) }
        }

        binding.btnCallClient.setOnClickListener {
            val client = currentClient ?: return@setOnClickListener
            if (client.phone.isBlank()) {
                showNoPhoneRegisteredDialog()
            } else {
                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${client.phone}"))
                startActivity(intent)
            }
        }

        binding.btnSendWhatsAppReminder.setOnClickListener {
            sendWhatsAppReminderFromDetails()
        }
        
        setupSyncIndicator()
        setupRecyclerView()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.loanDao().sanitizeAllLoans()
            } catch (e: Exception) {}
        }
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
        if (isGuestUser()) {
            binding.btnSyncIndicator.visibility = View.GONE
            return
        }
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
        if (isGuestUser()) {
            binding.btnSyncIndicator.visibility = View.GONE
            return
        }
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
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_increase_type, null)
        val cardDirect = dialogView.findViewById<MaterialCardView>(R.id.cardOptionDirect)
        val cardStructured = dialogView.findViewById<MaterialCardView>(R.id.cardOptionStructured)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelIncreaseType)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        cardDirect.setOnClickListener {
            dialog.dismiss()
            showAumentoDirectoDialog()
        }
        cardStructured.setOnClickListener {
            dialog.dismiss()
            showNuevaDeudaEstructuradaDialog()
        }
        dialog.show()
    }

    private fun showAumentoDirectoDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_increase_debt, null)
        val etAmount = dialogView.findViewById<EditText>(R.id.etIncreaseAmount)
        val cardBusiness = dialogView.findViewById<MaterialCardView>(R.id.cardOptionBusinessInc)
        val rbBusiness = dialogView.findViewById<RadioButton>(R.id.rbOptionBusinessInc)
        val cardPersonal = dialogView.findViewById<MaterialCardView>(R.id.cardOptionPersonalInc)
        val rbPersonal = dialogView.findViewById<RadioButton>(R.id.rbOptionPersonalInc)

        var isPersonalSelected = protectedFundingSource ?: currentLoan?.isPersonal ?: false

        fun updateCardStyles() {
            if (isPersonalSelected) {
                cardPersonal.strokeWidth = 3
                cardPersonal.strokeColor = getColor(R.color.purple_600)
                cardPersonal.setCardBackgroundColor(getColor(R.color.purple_50))
                rbPersonal.isChecked = true

                cardBusiness.strokeWidth = 1
                cardBusiness.strokeColor = getColor(R.color.slate_200)
                cardBusiness.setCardBackgroundColor(getColor(R.color.white))
                rbBusiness.isChecked = false
            } else {
                cardBusiness.strokeWidth = 3
                cardBusiness.strokeColor = getColor(R.color.purple_600)
                cardBusiness.setCardBackgroundColor(getColor(R.color.purple_50))
                rbBusiness.isChecked = true

                cardPersonal.strokeWidth = 1
                cardPersonal.strokeColor = getColor(R.color.slate_200)
                cardPersonal.setCardBackgroundColor(getColor(R.color.white))
                rbPersonal.isChecked = false
            }
        }

        updateCardStyles()

        cardBusiness.setOnClickListener {
            isPersonalSelected = false
            updateCardStyles()
        }
        cardPersonal.setOnClickListener {
            isPersonalSelected = true
            updateCardStyles()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Sumar") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                if (amount > 0) performDebtIncrease(amount, 0.0, 1, System.currentTimeMillis() + (24 * 60 * 60 * 1000), isPersonalSelected)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNuevaDeudaEstructuradaDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_loan_structured, null)
        val etAmount = dialogView.findViewById<EditText>(R.id.etStructuredAmount)
        val etInterest = dialogView.findViewById<EditText>(R.id.etStructuredInterest)
        val swInstallments = dialogView.findViewById<MaterialSwitch>(R.id.swEnableInstallments)
        val tilInstallments = dialogView.findViewById<TextInputLayout>(R.id.tilStructuredInstallments)
        val etInstallments = dialogView.findViewById<EditText>(R.id.etStructuredInstallments)
        val btnDate = dialogView.findViewById<MaterialButton>(R.id.btnStructuredDate)

        val cardBusiness = dialogView.findViewById<MaterialCardView>(R.id.cardBusinessStruct)
        val rbBusiness = dialogView.findViewById<RadioButton>(R.id.rbBusinessStruct)
        val cardPersonal = dialogView.findViewById<MaterialCardView>(R.id.cardPersonalStruct)
        val rbPersonal = dialogView.findViewById<RadioButton>(R.id.rbPersonalStruct)

        var isPersonalSelected = protectedFundingSource ?: currentLoan?.isPersonal ?: false

        fun updateCardStyles() {
            if (isPersonalSelected) {
                cardPersonal.strokeWidth = 3
                cardPersonal.strokeColor = getColor(R.color.purple_600)
                cardPersonal.setCardBackgroundColor(getColor(R.color.purple_50))
                rbPersonal.isChecked = true

                cardBusiness.strokeWidth = 1
                cardBusiness.strokeColor = getColor(R.color.slate_200)
                cardBusiness.setCardBackgroundColor(getColor(R.color.white))
                rbBusiness.isChecked = false
            } else {
                cardBusiness.strokeWidth = 3
                cardBusiness.strokeColor = getColor(R.color.purple_600)
                cardBusiness.setCardBackgroundColor(getColor(R.color.purple_50))
                rbBusiness.isChecked = true

                cardPersonal.strokeWidth = 1
                cardPersonal.strokeColor = getColor(R.color.slate_200)
                cardPersonal.setCardBackgroundColor(getColor(R.color.white))
                rbBusiness.isChecked = false
            }
        }

        updateCardStyles()

        cardBusiness.setOnClickListener {
            isPersonalSelected = false
            updateCardStyles()
        }
        cardPersonal.setOnClickListener {
            isPersonalSelected = true
            updateCardStyles()
        }

        swInstallments.setOnCheckedChangeListener { _, isChecked ->
            tilInstallments.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        var selectedDate = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        btnDate.text = "📅 " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(selectedDate))
        
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
            android.app.DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                selectedDate = cal.timeInMillis
                btnDate.text = "📅 " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                val interest = etInterest.text.toString().toDoubleOrNull() ?: 0.0
                val installments = if (swInstallments.isChecked) {
                    etInstallments.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                } else {
                    1
                }
                if (amount > 0) lifecycleScope.launch { performDebtIncrease(amount, interest, installments, selectedDate, isPersonalSelected) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performDebtIncrease(amount: Double, interest: Double, installments: Int, startDate: Long, isPersonal: Boolean) {
        val client = currentClient ?: return
        val isSimple = interest == 0.0 && installments == 1
        
        lifecycleScope.launch(Dispatchers.IO) {
            val total = amount + (amount * (interest / 100))
            val activeLoans = database.loanDao().getLoansByClientSyncNoSuspend(client.id).filter { it.status == "ACTIVE" || it.status == "OVERDUE" }
            val existingLoan = activeLoans.maxByOrNull { it.createdAt }
            var targetLoanId = ""

            val allAccounts = database.accountDao().getAllAccountsList().filter { !it.isDeleted }
            val targetOrigin = if (isPersonal) "PERSONAL" else "BUSINESS"
            val sourceAccount = allAccounts.firstOrNull { it.origin == targetOrigin && it.balance >= amount }
                ?: allAccounts.firstOrNull { it.origin == targetOrigin }
                ?: allAccounts.firstOrNull()
            val usedAccountId = sourceAccount?.id ?: ""
            val usedAccountName = sourceAccount?.name ?: ""

            database.runInTransaction {
                if (existingLoan != null) {
                    targetLoanId = existingLoan.id
                    val updatedLoan = existingLoan.copy(
                        amount = existingLoan.amount + amount,
                        totalToPay = existingLoan.totalToPay + total,
                        isPersonal = isPersonal,
                        isSynced = false
                    )
                    database.loanDao().updateLoanTransaction(updatedLoan)

                    activeLoans.filter { it.id != existingLoan.id }.forEach { old ->
                        database.loanDao().updateLoanTransaction(old.copy(status = "REFINANCED", isSynced = false))
                    }

                    val schedule = mutableListOf<LoanInstallmentEntity>()
                    val quotaAmount = total / installments
                    val cal = Calendar.getInstance().apply { timeInMillis = startDate }
                    
                    val existingInsts = database.loanDao().getInstallmentsByLoanSyncNoSuspend(existingLoan.id)
                    val nextNum = (existingInsts.maxOfOrNull { it.installmentNumber } ?: 0) + 1

                    for (i in 0 until installments) {
                        schedule.add(LoanInstallmentEntity(
                            loanId = existingLoan.id,
                            installmentNumber = nextNum + i,
                            amount = quotaAmount,
                            dueDate = cal.timeInMillis,
                            isSynced = false
                        ))
                        if (installments > 1) cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    database.loanDao().insertInstallmentsTransaction(schedule)

                    database.loanDao().insertPaymentTransaction(LoanPaymentEntity(
                        loanId = existingLoan.id,
                        installmentId = null,
                        amount = amount,
                        type = 1, // AUMENTO
                        accountId = usedAccountId,
                        accountName = usedAccountName,
                        isSynced = false
                    ))
                } else {
                    targetLoanId = UUID.randomUUID().toString()
                    val newLoan = LoanEntity(
                        id = targetLoanId,
                        clientId = client.id,
                        amount = amount,
                        interestRate = interest,
                        totalToPay = total,
                        installmentsCount = installments,
                        frequency = if (installments > 1) "DAILY" else "NONE",
                        startDate = System.currentTimeMillis(),
                        isPersonal = isPersonal,
                        isSynced = false
                    )
                    database.loanDao().insertLoanTransaction(newLoan)
                    
                    val schedule = mutableListOf<LoanInstallmentEntity>()
                    val quotaAmount = total / installments
                    val cal = Calendar.getInstance().apply { timeInMillis = startDate }
                    
                    for (i in 1..installments) {
                        schedule.add(LoanInstallmentEntity(
                            loanId = targetLoanId,
                            installmentNumber = i,
                            amount = quotaAmount,
                            dueDate = cal.timeInMillis,
                            isSynced = false
                        ))
                        if (installments > 1) cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    database.loanDao().insertInstallmentsTransaction(schedule)
                    
                    database.loanDao().insertPaymentTransaction(LoanPaymentEntity(
                        loanId = targetLoanId,
                        installmentId = null,
                        amount = amount,
                        type = 1, // AUMENTO
                        accountId = usedAccountId,
                        accountName = usedAccountName,
                        isSynced = false
                    ))
                }
            }

            // Descontar el aumento de la cuenta de origen si existe
            if (sourceAccount != null) {
                try {
                    val updatedAcc = sourceAccount.copy(
                        balance = (sourceAccount.balance - amount).coerceAtLeast(0.0),
                        updatedAt = System.currentTimeMillis(),
                        isSynced = false
                    )
                    database.accountDao().update(updatedAcc)
                } catch (e: Exception) { }
            }

            if (targetLoanId.isNotEmpty()) {
                database.loanDao().insertFundingSource(
                    LoanFundingSourceEntity(
                        loanId = targetLoanId,
                        isPersonal = isPersonal,
                        updatedAt = System.currentTimeMillis()
                    )
                )
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

                // REGISTRAR EN HISTORIAL DE MOVIMIENTOS
                val clientLog = MovementLogEntity(
                    type = "CLIENT_UPDATED",
                    title = "Cliente Actualizado",
                    description = "Datos actualizados de ${updated.name}",
                    value = "",
                    colorHex = "#3B82F6",
                    iconRes = android.R.drawable.ic_menu_edit
                )
                database.movementLogDao().insert(clientLog)
                SyncManager(this@LoanDetailsActivity).syncLogToCloud(clientLog)

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
        
        val etAmount = dialogView.findViewById<EditText>(R.id.etEditLoanAmount)
        val etInterest = dialogView.findViewById<EditText>(R.id.etEditInterestRate)
        val etInstallments = dialogView.findViewById<EditText>(R.id.etEditInstallmentsCount)
        val tvInstallmentsExplanation = dialogView.findViewById<TextView>(R.id.tvInstallmentsExplanation)
        val tvTotalSummary = dialogView.findViewById<TextView>(R.id.tvEditTotalToPaySummary)
        val tvQuotaSummary = dialogView.findViewById<TextView>(R.id.tvEditQuotaAmountSummary)

        val togglePlan = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleEditPlan)
        val btnStartDate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartDate)
        val btnDueDate = dialogView.findViewById<MaterialButton>(R.id.btnEditDueDate)
        val btnRemoveDueDate = dialogView.findViewById<MaterialButton>(R.id.btnRemoveDueDate)
        val etLateFeePercent = dialogView.findViewById<EditText>(R.id.etEditLateFeePercent)
        val etLateFeeAmountAuto = dialogView.findViewById<EditText>(R.id.etEditLateFeeAmountAuto)
        val etGraceDays = dialogView.findViewById<EditText>(R.id.etEditGraceDays)
        val btnReschedule = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogRescheduleDates)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogCancelTerms)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogSaveTerms)

        etAmount.setText(String.format(Locale.US, "%.2f", loan.amount))
        etInterest.setText(String.format(Locale.US, "%.1f", loan.interestRate))

        if (loan.installmentsCount <= 1 || loan.frequency == "NONE") {
            etInstallments.setText("0")
            togglePlan.check(R.id.btnEditFreqNone)
        } else {
            etInstallments.setText(loan.installmentsCount.toString())
            val planButtonId = when(loan.frequency) {
                "WEEKLY" -> R.id.btnEditFreqWeekly
                "MONTHLY" -> R.id.btnEditFreqMonthly
                else -> R.id.btnEditFreqDaily
            }
            togglePlan.check(planButtonId)
        }

        var tempStartDate = if (loan.startDate > 0L) loan.startDate else System.currentTimeMillis()
        var hasStartDateSet = loan.startDate > 0L

        val firstUnpaidInst = currentInstallments.firstOrNull { it.status != "PAID" }
        var tempDueDate = firstUnpaidInst?.dueDate ?: 0L
        var hasDueDateSet = tempDueDate > 0L

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        fun updateDateButtonsText(isSinglePayment: Boolean) {
            btnStartDate.text = if (hasStartDateSet) {
                "📅 Cambiar Fecha de Inicio: ${sdf.format(Date(tempStartDate))}"
            } else {
                "📅 Agregar Fecha de Inicio"
            }

            val labelPrefix = if (isSinglePayment) "🗓️ Fecha de Cobro (Pago Único)" else "🗓️ Fecha de Cobro 1ª Cuota"

            if (hasDueDateSet && tempDueDate > 0L) {
                btnDueDate.text = "$labelPrefix: ${sdf.format(Date(tempDueDate))}"
                btnRemoveDueDate.visibility = View.VISIBLE
            } else {
                btnDueDate.text = labelPrefix
                btnRemoveDueDate.visibility = View.GONE
            }
        }

        fun recalculateSummary() {
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            val interest = etInterest.text.toString().toDoubleOrNull() ?: 0.0
            val rawCount = etInstallments.text.toString().toIntOrNull() ?: 0
            val selectedFreqId = togglePlan.checkedButtonId

            val isSinglePayment = (selectedFreqId == R.id.btnEditFreqNone) || rawCount <= 1
            val count = if (isSinglePayment) 1 else rawCount

            val totalToPay = amount + (amount * (interest / 100.0))
            val quotaAmount = if (count > 0) totalToPay / count else totalToPay

            tvTotalSummary.text = "Total a pagar: S/ ${String.format(Locale.US, "%.2f", totalToPay)}"
            
            if (isSinglePayment) {
                tvQuotaSummary.text = "Pago Único: S/ ${String.format(Locale.US, "%.2f", totalToPay)}"
                tvInstallmentsExplanation.text = "Pago Único: Se cobrará la totalidad en un solo pago."
                btnReschedule.visibility = View.GONE
            } else {
                tvQuotaSummary.text = "Monto por cuota (${count}x): S/ ${String.format(Locale.US, "%.2f", quotaAmount)}"
                tvInstallmentsExplanation.text = "Estructurado en $count cuotas de S/ ${String.format(Locale.US, "%.2f", quotaAmount)}."
                btnReschedule.visibility = View.VISIBLE
            }

            updateDateButtonsText(isSinglePayment)
        }

        togglePlan.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnEditFreqNone) {
                    if (etInstallments.text.toString() != "0") {
                        etInstallments.setText("0")
                    }
                } else {
                    val currentVal = etInstallments.text.toString().toIntOrNull() ?: 0
                    if (currentVal <= 1) {
                        etInstallments.setText("24")
                    }
                }
                recalculateSummary()
            }
        }

        val financialWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val inputCount = s.toString().toIntOrNull() ?: 0
                if (inputCount > 1 && togglePlan.checkedButtonId == R.id.btnEditFreqNone) {
                    togglePlan.check(R.id.btnEditFreqDaily)
                } else if (inputCount <= 1 && togglePlan.checkedButtonId != R.id.btnEditFreqNone) {
                    togglePlan.check(R.id.btnEditFreqNone)
                }
                recalculateSummary()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        etAmount.addTextChangedListener(financialWatcher)
        etInterest.addTextChangedListener(financialWatcher)
        etInstallments.addTextChangedListener(financialWatcher)
        recalculateSummary()

        btnRemoveDueDate.setOnClickListener {
            tempDueDate = 0L
            hasDueDateSet = false
            val rawCount = etInstallments.text.toString().toIntOrNull() ?: 0
            val isSingle = (togglePlan.checkedButtonId == R.id.btnEditFreqNone) || rawCount <= 1
            updateDateButtonsText(isSingle)
        }

        val quotaBase = if (loan.installmentsCount > 0) loan.totalToPay / loan.installmentsCount else loan.totalToPay
        etGraceDays.setText(loan.graceDays.toString())

        val currentPercent = if (quotaBase > 0) (loan.lateFeeAmount / quotaBase) * 100 else 0.0
        if (currentPercent > 0) {
            etLateFeePercent.setText(String.format(Locale.US, "%.1f", currentPercent))
        } else {
            etLateFeePercent.setText("")
        }
        etLateFeeAmountAuto.setText(String.format(Locale.US, "%.2f", loan.lateFeeAmount))

        etLateFeePercent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val percent = s.toString().toDoubleOrNull() ?: 0.0
                val calculatedAmount = quotaBase * (percent / 100)
                etLateFeeAmountAuto.setText(String.format(Locale.US, "%.2f", calculatedAmount))
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnStartDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = tempStartDate }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                tempStartDate = cal.timeInMillis
                hasStartDateSet = true
                val rawCount = etInstallments.text.toString().toIntOrNull() ?: 0
                val isSingle = (togglePlan.checkedButtonId == R.id.btnEditFreqNone) || rawCount <= 1
                updateDateButtonsText(isSingle)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnDueDate.setOnClickListener {
            val baseTime = if (hasDueDateSet) tempDueDate else (tempStartDate + 24 * 60 * 60 * 1000)
            val cal = Calendar.getInstance().apply { timeInMillis = baseTime }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                tempDueDate = cal.timeInMillis
                hasDueDateSet = true
                val rawCount = etInstallments.text.toString().toIntOrNull() ?: 0
                val isSingle = (togglePlan.checkedButtonId == R.id.btnEditFreqNone) || rawCount <= 1
                updateDateButtonsText(isSingle)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnReschedule.setOnClickListener { dialog.dismiss(); showRescheduleDialog() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        
        btnSave.setOnClickListener {
            val rawCount = etInstallments.text.toString().toIntOrNull() ?: 0
            val selectedFreqId = togglePlan.checkedButtonId
            val isSinglePayment = (selectedFreqId == R.id.btnEditFreqNone) || rawCount <= 1

            val newFreq = if (isSinglePayment) "NONE" else when(selectedFreqId) {
                R.id.btnEditFreqWeekly -> "WEEKLY"
                R.id.btnEditFreqMonthly -> "MONTHLY"
                else -> "DAILY"
            }
            val newInstallmentsCount = if (isSinglePayment) 0 else rawCount.coerceAtLeast(1)

            val newAmount = etAmount.text.toString().toDoubleOrNull() ?: loan.amount
            val newInterestRate = etInterest.text.toString().toDoubleOrNull() ?: loan.interestRate
            val newTotalToPay = newAmount + (newAmount * (newInterestRate / 100.0))
            val newLateFee = etLateFeeAmountAuto.text.toString().toDoubleOrNull() ?: loan.lateFeeAmount
            val newGraceDays = etGraceDays.text.toString().toIntOrNull() ?: loan.graceDays

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val updatedLoan = loan.copy(
                        amount = newAmount,
                        interestRate = newInterestRate,
                        totalToPay = newTotalToPay,
                        installmentsCount = newInstallmentsCount,
                        frequency = newFreq,
                        startDate = if (hasStartDateSet) tempStartDate else loan.startDate,
                        lateFeeAmount = newLateFee,
                        graceDays = newGraceDays,
                        isSynced = false
                    )

                    database.runInTransaction {
                        database.loanDao().updateLoanTransaction(updatedLoan)

                        val allInsts = database.loanDao().getInstallmentsByLoanTransaction(loan.id)
                        val paidInsts = allInsts.filter { it.status == "PAID" && !it.isDeleted }
                        val paidAmountTotal = paidInsts.sumOf { it.amountPaid }

                        database.loanDao().deleteUnpaidInstallmentsTransaction(loan.id)

                        val remainingTotal = (newTotalToPay - paidAmountTotal).coerceAtLeast(0.0)

                        if (isSinglePayment) {
                            val singleInst = LoanInstallmentEntity(
                                loanId = loan.id,
                                installmentNumber = paidInsts.size + 1,
                                amount = remainingTotal,
                                dueDate = if (hasDueDateSet && tempDueDate > 0L) tempDueDate else 0L
                            )
                            database.loanDao().insertInstallmentsTransaction(listOf(singleInst))
                        } else {
                            val remainingCount = (newInstallmentsCount - paidInsts.size).coerceAtLeast(1)
                            val quotaAmount = remainingTotal / remainingCount

                            val cal = Calendar.getInstance().apply {
                                timeInMillis = if (hasDueDateSet && tempDueDate > 0L) tempDueDate else (if (hasStartDateSet) tempStartDate else System.currentTimeMillis())
                            }
                            cal.set(Calendar.HOUR_OF_DAY, 23)
                            cal.set(Calendar.MINUTE, 59)
                            cal.set(Calendar.SECOND, 59)
                            cal.set(Calendar.MILLISECOND, 999)

                            val newSchedule = mutableListOf<LoanInstallmentEntity>()
                            for (i in 1..remainingCount) {
                                if (i > 1) {
                                    when(newFreq) {
                                        "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                                        "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                                        "MONTHLY" -> cal.add(Calendar.MONTH, 1)
                                    }
                                }
                                newSchedule.add(
                                    LoanInstallmentEntity(
                                        loanId = loan.id,
                                        installmentNumber = paidInsts.size + i,
                                        amount = quotaAmount,
                                        dueDate = if (hasDueDateSet && tempDueDate > 0L) cal.timeInMillis else 0L
                                    )
                                )
                            }
                            if (newSchedule.isNotEmpty()) {
                                database.loanDao().insertInstallmentsTransaction(newSchedule)
                            }
                        }
                    }

                    SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoanDetailsActivity, "Préstamo y cuotas actualizados ✅", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadLoanData(loan.clientId)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoanDetailsActivity, "Error al actualizar el préstamo", Toast.LENGTH_SHORT).show()
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
                    val loan = activeLoans.maxByOrNull { it.createdAt } ?: loans.firstOrNull()
                    currentLoan = loan

                    // El origen de fondos se lee de la tabla PROTEGIDA (no la nube)
                    protectedFundingSource = loan?.let {
                        database.loanDao().getFundingSource(it.id)?.isPersonal ?: it.isPersonal
                    }

                    // Consolidar préstamos activos duplicados por error
                    if (activeLoans.size > 1 && loan != null) {
                        activeLoans.filter { it.id != loan.id }.forEach { oldLoan ->
                            database.loanDao().updateLoanSync(oldLoan.copy(status = "REFINANCED", isSynced = false))
                        }
                    }

                    var hasAnyOverdue = false
                    val now = System.currentTimeMillis()
                    val installments = if (loan != null) database.loanDao().getInstallmentsByLoanSync(loan.id) else emptyList()
                    
                    if (installments.any { it.status != "PAID" && it.dueDate < now }) {
                        hasAnyOverdue = true
                    }
                    
                    val sortedInstallments = installments.sortedBy { it.dueDate }

                    withContext(Dispatchers.Main) {
                        if (!::binding.isInitialized) return@withContext
                        
                        val initialCapital = loan?.amount ?: 0.0
                        val totalToPay = loan?.totalToPay ?: 0.0
                        val rate = loan?.interestRate ?: 0.0

                        if (rate > 0.0 && totalToPay > initialCapital) {
                            binding.tvDetailCapitalLent.text = "💰 Capital Prestado: S/ ${String.format(Locale.US, "%.2f", initialCapital)}"
                            binding.tvDetailTotalToPay.visibility = View.VISIBLE
                            binding.tvDetailTotalToPay.text = "📄 Deuda Total (con interés): S/ ${String.format(Locale.US, "%.2f", totalToPay)}"
                            binding.tvDetailInterestInfo.text = "Se aplicó el ${String.format(Locale.US, "%.1f", rate)}% de interés para este cliente"
                        } else {
                            binding.tvDetailCapitalLent.text = "💰 Dinero Prestado: S/ ${String.format(Locale.US, "%.2f", initialCapital)}"
                            binding.tvDetailTotalToPay.visibility = View.GONE
                            binding.tvDetailInterestInfo.text = "No se aplicó interés para este cliente"
                        }

                        // --- FECHAS SEPARADAS Y ESTADO DE MORA (2 COLUMNAS REDISEÑADAS) ---
                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        
                        // 1. Fecha de Registro en App
                        val regDate = if ((loan?.createdAt ?: 0L) > 0L) loan?.createdAt ?: 0L else (loan?.startDate ?: 0L)
                        val regDateStr = if (regDate > 0L) sdf.format(Date(regDate)) else "Reciente"
                        binding.tvDetailRegisterDate.text = regDateStr

                        // 2. Fecha de Inicio del Préstamo
                        val startDate = loan?.startDate ?: 0L
                        val startDateStr = if (startDate > 0L) sdf.format(Date(startDate)) else "Reciente"
                        binding.tvDetailStartDate.text = startDateStr

                        // Si registro en app coincide con inicio de crédito, mostrar únicamente registro en app centrado
                        val sameDates = regDateStr == startDateStr
                        binding.layoutDetailStartDate.visibility = if (sameDates) View.GONE else View.VISIBLE
                        binding.layoutDetailRegisterDate.gravity = if (sameDates) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START

                        // 3. Fecha de Cobro
                        val firstUnpaid = sortedInstallments.firstOrNull { it.status != "PAID" }
                        val dueDate = firstUnpaid?.dueDate ?: 0L
                        binding.tvDetailDueDate.text = if (dueDate > 0L) sdf.format(Date(dueDate)) else "Sin fecha límite"

                        // 4. Estado de Mora en la pastilla/pill
                        val daysOld = if (startDate > 0L) ((now - startDate) / (1000 * 60 * 60 * 24)).coerceAtLeast(0L) else 0L

                        if (dueDate > 0L && now > dueDate && firstUnpaid != null) {
                            val daysLate = ((now - dueDate) / (1000 * 60 * 60 * 24)).coerceAtLeast(1L)
                            binding.tvDetailMoraStatus.text = "Con Mora ($daysLate d)"
                            binding.tvDetailMoraStatus.setTextColor(getColor(R.color.red_600))
                            binding.tvDetailMoraStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.red_50))
                        } else if (hasAnyOverdue || loan?.status == "OVERDUE") {
                            val ageText = if (daysOld > 0L) "hace $daysOld d" else "hoy"
                            binding.tvDetailMoraStatus.text = "Con Mora ($ageText)"
                            binding.tvDetailMoraStatus.setTextColor(getColor(R.color.red_600))
                            binding.tvDetailMoraStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.red_50))
                        } else {
                            binding.tvDetailMoraStatus.text = "Al día"
                            binding.tvDetailMoraStatus.setTextColor(getColor(R.color.emerald_600))
                            binding.tvDetailMoraStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.purple_50))
                        }

                        // --- ORIGEN DE FONDOS ---
                        val isPersonal = protectedFundingSource ?: (loan?.isPersonal == true)
                        if (isPersonal) {
                            binding.tvFundingSourceIcon.text = "👤"
                            binding.tvFundingSourceTitle.text = "Origen: Capital Personal"
                            binding.tvFundingSourceDesc.text = "Financiado con dinero propio (fuera del presupuesto del negocio)"
                        } else {
                            binding.tvFundingSourceIcon.text = "💼"
                            binding.tvFundingSourceTitle.text = "Origen: Presupuesto del Negocio"
                            binding.tvFundingSourceDesc.text = "Financiado con capital oficial del negocio"
                        }

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

                            // ESCUCHAR TODOS LOS PAGOS Y PRÉSTAMOS DEL CLIENTE PARA EL HISTORIAL UNIFICADO
                            lifecycleScope.launch(Dispatchers.IO) {
                                kotlinx.coroutines.flow.combine(
                                    database.loanDao().getAllPaymentsByClientFlow(clientId),
                                    database.loanDao().getLoansByClient(clientId)
                                ) { payments, loans ->
                                    groupPayments(payments, loans)
                                }.collectLatest { historyRows ->
                                    withContext(Dispatchers.Main) {
                                        if (::binding.isInitialized) {
                                            paymentsAdapter.submitList(historyRows)
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

    private fun showNoPhoneRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle("📵 Sin número registrado")
            .setMessage("No hay un número de teléfono registrado para este cliente.")
            .setNegativeButton("Cerrar", null)
            .setPositiveButton("Agregar número") { _, _ ->
                showAddPhoneDialog()
            }
            .show()
    }

    private fun showAddPhoneDialog() {
        val client = currentClient ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_client_phone, null)
        val etPhone = dialogView.findViewById<EditText>(R.id.etAddClientPhone)
        val btnPickContacts = dialogView.findViewById<MaterialButton>(R.id.btnPickPhoneFromContacts)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAddPhone)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAddPhone)

        pendingPhoneEditText = etPhone

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            if (pendingPhoneEditText === etPhone) {
                pendingPhoneEditText = null
            }
        }

        btnPickContacts.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                launchPhoneContactPicker()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val newPhone = etPhone.text.toString().trim()
            if (newPhone.isEmpty()) {
                Toast.makeText(this, "Por favor escribe o selecciona un número", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val updated = client.copy(phone = newPhone, isSynced = false)
                database.loanDao().updateClient(updated)
                currentClient = updated

                val clientLog = MovementLogEntity(
                    type = "CLIENT_UPDATED",
                    title = "Teléfono Registrado",
                    description = "Se registró el número de ${updated.name}",
                    value = newPhone,
                    colorHex = "#3B82F6",
                    iconRes = android.R.drawable.ic_menu_call
                )
                database.movementLogDao().insert(clientLog)
                SyncManager(this@LoanDetailsActivity).syncLogToCloud(clientLog)
                SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()

                Toast.makeText(this@LoanDetailsActivity, "Número guardado correctamente ✅", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun sendWhatsAppReminderFromDetails() {
        val client = currentClient ?: return
        if (client.phone.isBlank()) {
            showNoPhoneRegisteredDialog()
            return
        }

        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "LOANS") ?: "LOANS"
        
        val ownerName = prefs.getString("${businessType}_owner_name", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("business_owner_name", "")?.takeIf { it.isNotBlank() }
            ?: FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: ""

        val systemName = prefs.getString("${businessType}_name", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("business_name", "")?.takeIf { it.isNotBlank() }
            ?: ""
        
        val senderLabel = when {
            ownerName.isNotEmpty() -> ownerName
            systemName.isNotEmpty() -> systemName
            else -> "tu gestor"
        }

        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val writeVerb = if (isCollabMode) "Te escribimos" else "Te escribo"
        val thanksVerb = if (isCollabMode) "Agradecemos" else "Agradezco"
        val requestVerb = if (isCollabMode) "Te pedimos" else "Te pido"

        val loan = currentLoan
        val pendingInstallments = currentInstallments.filter { it.status != "PAID" }
        val totalPendingDebt = pendingInstallments.sumOf { it.amount - it.amountPaid }

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val now = System.currentTimeMillis()

        val startDate = loan?.startDate ?: 0L
        val daysOld = if (startDate > 0L) ((now - startDate) / (1000 * 60 * 60 * 24)).coerceAtLeast(0L) else 0L
        val ageText = when {
            daysOld == 0L -> "registrado hoy"
            daysOld == 1L -> "hace 1 día"
            daysOld < 30L -> "hace $daysOld días"
            daysOld < 365L -> "hace ${daysOld / 30} mes(es)"
            else -> "hace ${daysOld / 365} año(s)"
        }
        val startDateStr = if (startDate > 0L) sdf.format(Date(startDate)) else "Reciente"
        val montoStr = String.format(Locale.US, "%.2f", totalPendingDebt)

        val firstUnpaid = pendingInstallments.minByOrNull { it.dueDate }
        val dueDate = firstUnpaid?.dueDate ?: 0L

        val statusSection = if (dueDate > 0L) {
            val dueDateStr = sdf.format(Date(dueDate))
            val diffDaysDue = ((dueDate - now) / (1000 * 60 * 60 * 24))
            when {
                diffDaysDue < -1 -> "⚠️ *Aviso de Vencimiento:* Tu saldo tenía como fecha límite el *$dueDateStr* (vencido hace ${-diffDaysDue} días). $requestVerb regularizar tu abono a la brevedad."
                diffDaysDue in -1..0 -> "⏰ *Recordatorio de Hoy:* Hoy *$dueDateStr* vence tu fecha de pago. Por favor no olvides realizar tu abono."
                else -> "🗓️ *Próximo Vencimiento:* Tu fecha de pago programada es el *$dueDateStr*."
            }
        } else {
            "🗓️ Por favor contáctame para coordinar la fecha de tu pago."
        }

        val message = "Hola *${client.name}*, te saluda *${senderLabel}*. 👋\n\n" +
               "$writeVerb para recordarte tu préstamo activo por un saldo pendiente de *S/ $montoStr*.\n\n" +
               "📌 *Detalles del Préstamo:*\n" +
               "• Fecha de Préstamo: *$startDateStr* ($ageText)\n" +
               "• Saldo Pendiente: *S/ $montoStr*\n\n" +
               "$statusSection\n\n" +
               "$thanksVerb mucho tu puntualidad y preferencia. ¡Que tengas un excelente día! 😊"

        try {
            val cleanNumber = client.phone.replace(" ", "").replace("-", "").replace("+", "")
            val finalNumber = if (cleanNumber.startsWith("51")) cleanNumber else "51$cleanNumber"
            val url = "https://api.whatsapp.com/send?phone=$finalNumber&text=" + URLEncoder.encode(message, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFundingSourceDialog() {
        val loan = currentLoan ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_funding_source, null)
        
        val cardBusiness = dialogView.findViewById<MaterialCardView>(R.id.cardOptionBusiness)
        val rbBusiness = dialogView.findViewById<RadioButton>(R.id.rbOptionBusiness)
        
        val cardPersonal = dialogView.findViewById<MaterialCardView>(R.id.cardOptionPersonal)
        val rbPersonal = dialogView.findViewById<RadioButton>(R.id.rbOptionPersonal)
        
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelFundingSource)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveFundingSource)

        var isPersonalSelected = protectedFundingSource ?: loan.isPersonal

        fun updateCardStyles() {
            if (isPersonalSelected) {
                cardPersonal.strokeWidth = 4
                cardPersonal.strokeColor = getColor(R.color.purple_600)
                cardPersonal.setCardBackgroundColor(getColor(R.color.purple_50))
                rbPersonal.isChecked = true

                cardBusiness.strokeWidth = 2
                cardBusiness.strokeColor = getColor(R.color.slate_200)
                cardBusiness.setCardBackgroundColor(getColor(R.color.white))
                rbBusiness.isChecked = false
            } else {
                cardBusiness.strokeWidth = 4
                cardBusiness.strokeColor = getColor(R.color.purple_600)
                cardBusiness.setCardBackgroundColor(getColor(R.color.purple_50))
                rbBusiness.isChecked = true

                cardPersonal.strokeWidth = 2
                cardPersonal.strokeColor = getColor(R.color.slate_200)
                cardPersonal.setCardBackgroundColor(getColor(R.color.white))
                rbPersonal.isChecked = false
            }
        }

        updateCardStyles()

        cardBusiness.setOnClickListener {
            isPersonalSelected = false
            updateCardStyles()
        }

        cardPersonal.setOnClickListener {
            isPersonalSelected = true
            updateCardStyles()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val updatedLoan = loan.copy(isPersonal = isPersonalSelected, isSynced = false)
                database.loanDao().updateLoanTransaction(updatedLoan)
                // Guardar además en la tabla PROTEGIDA de origen de fondos, que la
                // sincronización con la nube nunca sobrescribe. Así el origen elegido
                // por el usuario se mantiene de forma permanente.
                database.loanDao().insertFundingSource(
                    LoanFundingSourceEntity(
                        loanId = updatedLoan.id,
                        isPersonal = isPersonalSelected,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                // Subir a la nube de inmediato para que ninguna sincronización
                // posterior revierta el origen de fondos recién configurado.
                SyncManager(this@LoanDetailsActivity).syncLoanToCloud(updatedLoan)
                SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@LoanDetailsActivity, "Origen de fondos actualizado correctamente 💼", Toast.LENGTH_SHORT).show()
                    loadLoanData(loan.clientId)
                }
            }
        }

        dialog.show()
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

        lifecycleScope.launch(Dispatchers.IO) {
            val accounts = database.accountDao().getAllAccountsList().filter { !it.isDeleted }
            val isPersonalLoan = protectedFundingSource ?: (currentLoan?.isPersonal == true)
            val preferredOrigin = if (isPersonalLoan) "PERSONAL" else "BUSINESS"
            val sortedAccounts = accounts.sortedByDescending { it.origin == preferredOrigin }

            withContext(Dispatchers.Main) {
                var selectedAcc: AccountEntity? = sortedAccounts.firstOrNull()
                val builder = AlertDialog.Builder(this@LoanDetailsActivity)
                    .setTitle("Liquidar Deuda Total (S/ ${String.format(Locale.US, "%.2f", pendingTotal)})")

                if (sortedAccounts.isNotEmpty()) {
                    val labels = sortedAccounts.map { "${it.name} (${it.bankName}) - S/ ${String.format(Locale.US, "%.2f", it.balance)}" }.toTypedArray()
                    builder.setSingleChoiceItems(labels, 0) { _, which ->
                        selectedAcc = sortedAccounts.getOrNull(which)
                    }
                } else {
                    builder.setMessage("El saldo total acumulado de todas las deudas es de S/ ${String.format(Locale.US, "%.2f", pendingTotal)}.\n\n¿Deseas marcar TODO como pagado ahora?")
                }

                builder.setPositiveButton("Sí, Liquidar Todo") { _, _ ->
                    val chosenAcc = selectedAcc
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
                                        accountId = chosenAcc?.id ?: "",
                                        accountName = chosenAcc?.name ?: "",
                                        isSynced = false
                                    ))
                                }
                                database.loanDao().updateLoanTransaction(loan.copy(
                                    status = "PAID",
                                    isSynced = false
                                ))
                            }
                        }

                        if (chosenAcc != null && pendingTotal > 0.0) {
                            try {
                                val freshAcc = database.accountDao().getAllAccountsList().find { it.id == chosenAcc.id } ?: chosenAcc
                                database.accountDao().update(freshAcc.copy(
                                    balance = freshAcc.balance + pendingTotal,
                                    updatedAt = System.currentTimeMillis(),
                                    isSynced = false
                                ))
                            } catch (e: Exception) { }
                        }

                        SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
                        withContext(Dispatchers.Main) {
                            val accMsg = if (chosenAcc != null) " en ${chosenAcc.name}" else ""
                            Toast.makeText(this@LoanDetailsActivity, "¡Deuda liquidada$accMsg!", Toast.LENGTH_SHORT).show()
                            showCelebrationAnimation()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            }
        }
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
        val autoAccount = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.autoCompletePaymentAccount)
        val btnAddReceipt = dialogView.findViewById<MaterialButton>(R.id.btnAddPaymentReceipt)
        val cardReceiptContainer = dialogView.findViewById<View>(R.id.cardPaymentReceiptContainer)
        val ivReceiptThumbnail = dialogView.findViewById<ImageView>(R.id.ivPaymentReceiptThumbnail)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelPayment)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmPayment)

        var accountList = emptyList<AccountEntity>()
        var selectedAccountId = ""
        var selectedAccountName = ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allAccs = database.accountDao().getAllAccountsList().filter { !it.isDeleted }
                val isPersonalLoan = protectedFundingSource ?: loan.isPersonal
                val preferredOrigin = if (isPersonalLoan) "PERSONAL" else "BUSINESS"
                accountList = allAccs.sortedByDescending { it.origin == preferredOrigin }

                withContext(Dispatchers.Main) {
                    if (accountList.isNotEmpty()) {
                        val names = accountList.map { "${it.name} (${it.bankName}) - S/ ${String.format(Locale.US, "%.2f", it.balance)}" }
                        autoAccount?.setAdapter(ArrayAdapter(this@LoanDetailsActivity, android.R.layout.simple_dropdown_item_1line, names))
                        autoAccount?.setText(names[0], false)
                        selectedAccountId = accountList[0].id
                        selectedAccountName = accountList[0].name
                    } else {
                        autoAccount?.setText("Sin cuenta registrada", false)
                    }
                }
            } catch (e: Exception) { }
        }

        autoAccount?.setOnItemClickListener { _, _, position, _ ->
            if (position in accountList.indices) {
                selectedAccountId = accountList[position].id
                selectedAccountName = accountList[position].name
            }
        }

        currentPaymentReceiptCard = cardReceiptContainer
        currentPaymentReceiptImage = ivReceiptThumbnail
        selectedPaymentReceiptUri = null
        cardReceiptContainer?.visibility = View.GONE

        btnAddReceipt?.setOnClickListener {
            val options = arrayOf("📸 Tomar Foto con Cámara", "🖼️ Elegir de Galería")
            AlertDialog.Builder(this)
                .setTitle("Adjuntar Comprobante de Pago")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 108)
                        } else {
                            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            paymentReceiptFile = File(getExternalFilesDir(null), "receipt_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(this, "$packageName.provider", paymentReceiptFile!!)
                            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            takePaymentReceiptLauncher.launch(intent)
                        }
                    } else {
                        pickPaymentReceiptLauncher.launch("image/*")
                    }
                }
                .show()
        }

        tvTitle.text = "Registrar Cobro"
        tvClient.text = "Cliente: ${client.name}"
        tvInstallment.text = "SALDO RESTANTE ACUMULADO"
        tvAmountDue.text = "Saldo pendiente: S/ ${String.format(Locale.US, "%.2f", totalPendingDebt)}"
        if (lateFee > 0) {
            tvLateFee.visibility = View.VISIBLE
            tvLateFee.text = "⚠️ Mora por atraso: S/ ${String.format(Locale.US, "%.2f", lateFee)}"
        }

        etAmount.setText("")
        etAmount.hint = "0.00"
        etAmount.requestFocus()
        
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            etAmount.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etAmount, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val amountValue = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (amountValue > 0) {
                if (amountValue > totalToPayThisAmount + 0.01) {
                    Toast.makeText(this, "⚠️ No puede cobrar más de la deuda total (Máx: S/ ${String.format(Locale.US, "%.2f", totalToPayThisAmount)})", Toast.LENGTH_LONG).show()
                } else {
                    val receiptPath = selectedPaymentReceiptUri?.toString()
                    processUnifiedPayment(amountValue, lateFee, receiptPath, selectedAccountId, selectedAccountName)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun processUnifiedPayment(
        amount: Double,
        lateFee: Double,
        receiptPhotoPath: String? = null,
        accountId: String = "",
        accountName: String = ""
    ) {
        val client = currentClient ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var remainingPayment = amount
            var allPaid = false
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
                        accountId = accountId,
                        accountName = accountName,
                        receiptPhotoPath = receiptPhotoPath,
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

                val remainingTotalAfter = database.loanDao().getLoansByClientSyncNoSuspend(client.id)
                    .filter { it.status != "PAID" }
                    .sumOf { loan ->
                        database.loanDao().getInstallmentsByLoanSyncNoSuspend(loan.id)
                            .filter { it.status != "PAID" }
                            .sumOf { it.amount - it.amountPaid }
                    }
                allPaid = remainingTotalAfter <= 0.01
            }

            // Sumar el cobro recibido al saldo de la cuenta destino seleccionada
            if (accountId.isNotEmpty()) {
                try {
                    val acc = database.accountDao().getAllAccountsList().find { it.id == accountId }
                    acc?.let {
                        database.accountDao().update(it.copy(
                            balance = it.balance + amount,
                            updatedAt = System.currentTimeMillis(),
                            isSynced = false
                        ))
                    }
                } catch (e: Exception) { }
            }

            SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()

            // Registrar en el Historial de Movimientos
            try {
                val accSuffix = if (accountName.isNotEmpty()) " ➔ $accountName" else ""
                val log = MovementLogEntity(
                    type = "PAYMENT_REGISTERED",
                    title = "Abono Registrado",
                    description = "Abono de ${client.name}$accSuffix • ${String.format(Locale.US, "%.2f", amount)}",
                    value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                    colorHex = "#059669",
                    iconRes = android.R.drawable.ic_menu_agenda
                )
                database.movementLogDao().insert(log)
                SyncManager(this@LoanDetailsActivity).syncLogToCloud(log)
            } catch (e: Exception) { }

            withContext(Dispatchers.Main) { 
                val accToast = if (accountName.isNotEmpty()) " en $accountName" else ""
                Toast.makeText(this@LoanDetailsActivity, "Cobro registrado$accToast ✅", Toast.LENGTH_SHORT).show() 
                if (allPaid) {
                    showCelebrationAnimation()
                }
            }
        }
    }

    private fun showCelebrationAnimation() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_debt_celebration, null)
        val tvEmoji = dialogView.findViewById<TextView>(R.id.tvCelebrationEmoji)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnCelebrationClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        tvEmoji.scaleX = 0f
        tvEmoji.scaleY = 0f
        tvEmoji.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .rotation(360f)
            .setDuration(600)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .withEndAction {
                tvEmoji.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            .start()

        btnClose.setOnClickListener {
            dialog.dismiss()
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
                tvInstallmentDueDate.text = if (item.dueDate > 0L) "Vence: ${sdf.format(Date(item.dueDate))}" else "Sin fecha límite"
                val now = System.currentTimeMillis()
                val statusColor = when {
                    item.status == "PAID" -> R.color.emerald_600
                    item.dueDate <= 0L -> R.color.sky_600
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

    /**
     * Agrupa los pagos y préstamos iniciales pertenecientes al cliente
     * para mostrarlos de forma unificada en el historial de actividad.
     */
    data class PaymentRow(
        val primary: LoanPaymentEntity,
        val totalAmount: Double,
        val paymentIds: List<String>,
        val isInitialLoan: Boolean = false,
        val initialAmount: Double = 0.0,
        val interestRate: Double = 0.0,
        val totalToPay: Double = 0.0
    )

    private fun groupPayments(payments: List<LoanPaymentEntity>, loans: List<LoanEntity>): List<PaymentRow> {
        val paymentRows = payments
            .filter { !it.isDeleted }
            .groupBy { it.loanId to (it.timestamp / 1000L) }
            .map { (_, group) ->
                PaymentRow(
                    primary = group.first(),
                    totalAmount = group.sumOf { it.amount + it.lateFeeAmount },
                    paymentIds = group.map { it.id }
                )
            }.toMutableList()

        loans.filter { !it.isDeleted }.forEach { loan ->
            val timestamp = if (loan.startDate > 0L) loan.startDate else loan.createdAt
            val dummyPayment = LoanPaymentEntity(
                id = "loan_${loan.id}",
                loanId = loan.id,
                amount = loan.amount,
                type = 2, // 2 = PRÉSTAMO OTORGADO
                timestamp = timestamp
            )
            paymentRows.add(
                PaymentRow(
                    primary = dummyPayment,
                    totalAmount = loan.amount,
                    paymentIds = emptyList(),
                    isInitialLoan = true,
                    initialAmount = loan.amount,
                    interestRate = loan.interestRate,
                    totalToPay = loan.totalToPay
                )
            )
        }

        return paymentRows.sortedByDescending { it.primary.timestamp }
    }

    inner class PaymentsAdapter : RecyclerView.Adapter<PaymentsAdapter.ViewHolder>() {
        private var items = emptyList<PaymentRow>()
        fun submitList(newItems: List<PaymentRow>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanPaymentBinding) : RecyclerView.ViewHolder(b.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanPaymentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = items[position]
            val item = row.primary
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            
            with(holder.b) {
                if (row.isInitialLoan) {
                    val initialStr = String.format(Locale.US, "%.2f", row.initialAmount)
                    tvPaymentAmount.text = "Préstamo otorgado: S/ $initialStr"
                    tvPaymentAmount.setTextColor(getColor(R.color.purple_600))
                    tvPaymentDate.text = sdf.format(Date(item.timestamp))

                    val hasInterest = row.interestRate > 0.01 || (row.totalToPay > row.initialAmount + 0.01)
                    if (hasInterest) {
                        val rate = row.interestRate
                        val rateStr = if (rate % 1.0 == 0.0) "${rate.toInt()}%" else String.format(Locale.US, "%.1f%%", rate)
                        val totalStr = String.format(Locale.US, "%.2f", row.totalToPay)
                        tvPaymentDetail.visibility = View.VISIBLE
                        tvPaymentDetail.text = "Más interés del $rateStr: S/ $totalStr"
                    } else {
                        tvPaymentDetail.visibility = View.VISIBLE
                        tvPaymentDetail.text = "Sin interés"
                    }

                    btnReversePayment.visibility = View.GONE
                    cardPaymentVoucher.visibility = View.GONE
                    btnDownloadReceipt.visibility = View.GONE
                } else if (item.type == 1) {
                    tvPaymentAmount.text = "Aumento: + S/ ${String.format(Locale.US, "%.2f", row.totalAmount)}"
                    tvPaymentAmount.setTextColor(getColor(R.color.indigo_500))
                    tvPaymentDate.text = sdf.format(Date(item.timestamp))

                    val details = mutableListOf<String>()
                    if (item.accountName.isNotEmpty()) details.add("Desde: ${item.accountName}")
                    if (item.lateFeeAmount > 0) details.add("Incluye S/ ${String.format(Locale.US, "%.2f", item.lateFeeAmount)} de mora")

                    if (details.isNotEmpty()) {
                        tvPaymentDetail.visibility = View.VISIBLE
                        tvPaymentDetail.text = details.joinToString(" • ")
                    } else {
                        tvPaymentDetail.visibility = View.GONE
                    }

                    btnReversePayment.visibility = View.GONE
                    cardPaymentVoucher.visibility = View.GONE
                    btnDownloadReceipt.visibility = View.GONE
                } else {
                    tvPaymentAmount.text = "Abono de S/ ${String.format(Locale.US, "%.2f", row.totalAmount)}"
                    tvPaymentAmount.setTextColor(getColor(R.color.emerald_600))
                    tvPaymentDate.text = sdf.format(Date(item.timestamp))

                    val details = mutableListOf<String>()
                    if (item.accountName.isNotEmpty()) details.add("Cuenta: ${item.accountName}")
                    if (item.lateFeeAmount > 0) details.add("Incluye S/ ${String.format(Locale.US, "%.2f", item.lateFeeAmount)} de mora")

                    if (details.isNotEmpty()) {
                        tvPaymentDetail.visibility = View.VISIBLE
                        tvPaymentDetail.text = details.joinToString(" • ")
                    } else {
                        tvPaymentDetail.visibility = View.GONE
                    }

                    if (!item.receiptPhotoPath.isNullOrEmpty()) {
                        cardPaymentVoucher.visibility = View.VISIBLE
                        Glide.with(ivPaymentReceiptPreview.context)
                            .load(item.receiptPhotoPath)
                            .centerCrop()
                            .into(ivPaymentReceiptPreview)

                        cardPaymentVoucher.setOnClickListener {
                            showFullImageDialog(item.receiptPhotoPath)
                        }
                    } else {
                        cardPaymentVoucher.visibility = View.GONE
                    }

                    btnReversePayment.visibility = View.VISIBLE
                    btnReversePayment.setOnClickListener {
                        showReversePaymentDialog(row)
                    }

                    btnDownloadReceipt.visibility = View.VISIBLE
                    btnDownloadReceipt.setOnClickListener {
                        // Recibo PDF
                        lifecycleScope.launch {
                            val client = currentClient ?: return@launch
                            val loan = database.loanDao().getLoanById(item.loanId) ?: return@launch
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
        }
        override fun getItemCount() = items.size
    }

    /**
     * Muestra la confirmación para DESHACER un cobro registrado por error.
     * Anula el pago, recalcula la cuota afectada y deja el préstamo activo si estaba cancelado.
     */
    private fun showReversePaymentDialog(row: PaymentRow) {
        val total = row.totalAmount
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        AlertDialog.Builder(this)
            .setTitle("↩️ ¿Deshacer este cobro?")
            .setMessage(
                "Se anulará el cobro de S/ ${String.format(Locale.US, "%.2f", total)} " +
                "registrado el ${sdf.format(Date(row.primary.timestamp))}.\n\n" +
                "• El pago se eliminará del historial y se descontará de la cuenta receptor.\n" +
                "• La cuota volverá a estar pendiente o parcial.\n" +
                "• Si el préstamo estaba cancelado, se reabrirá."
            )
            .setPositiveButton("Sí, deshacer") { _, _ ->
                performReversePayment(row)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performReversePayment(row: PaymentRow) {
        lifecycleScope.launch(Dispatchers.IO) {
            val revertedAmount = try {
                database.loanDao().reversePayments(row.paymentIds)
            } catch (e: Exception) {
                0.0
            }
            val ok = revertedAmount > 0.0

            if (ok) {
                // Si el cobro había ingresado a una cuenta, revertir el saldo de esa cuenta
                val targetAccountId = row.primary.accountId
                if (targetAccountId.isNotEmpty()) {
                    try {
                        val acc = database.accountDao().getAllAccountsList().find { it.id == targetAccountId }
                        acc?.let {
                            database.accountDao().update(it.copy(
                                balance = (it.balance - revertedAmount).coerceAtLeast(0.0),
                                updatedAt = System.currentTimeMillis(),
                                isSynced = false
                            ))
                        }
                    } catch (e: Exception) { }
                }

                val total = row.totalAmount
                val client = currentClient
                val log = MovementLogEntity(
                    type = "PAYMENT_REVERSED",
                    title = "Cobro Deshecho",
                    description = "Se anuló el cobro de ${client?.name ?: "S/ ${String.format(Locale.US, "%.2f", total)}"}" +
                            " • ${String.format(Locale.US, "%.2f", total)}",
                    value = "S/ ${String.format(Locale.US, "%.2f", total)}",
                    colorHex = "#DC2626",
                    iconRes = android.R.drawable.ic_menu_revert
                )
                database.movementLogDao().insert(log)
                SyncManager(this@LoanDetailsActivity).syncLogToCloud(log)
                SyncManager(this@LoanDetailsActivity).scheduleOfflineSync()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@LoanDetailsActivity,
                    if (ok) "Cobro deshecho ✅" else "No se pudo deshacer el cobro",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showFullImageDialog(photoPath: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_image_preview, null)
        val ivFull = dialogView.findViewById<ImageView>(R.id.ivFullPreview)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseFullPreview)

        Glide.with(this).load(photoPath).into(ivFull)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView)
            .create()

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
