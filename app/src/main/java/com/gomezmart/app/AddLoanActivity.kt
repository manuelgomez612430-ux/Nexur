package com.gomezmart.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.data.*
import com.gomezmart.app.databinding.ActivityLoansAddBinding
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AddLoanActivity : BaseActivity() {

    private lateinit var binding: ActivityLoansAddBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var clientPhotoPath: String? = null
    private var docPhotoPath: String? = null
    private var collateralPhotoPath: String? = null
    private var activeCaptureType: String? = null
    
    private var selectedStartDate = System.currentTimeMillis()
    private var selectedDueDate = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
    private var selectedClientId: String? = null
    private var allClients: List<LoanClientEntity> = emptyList()
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    private var allOriginAccounts: List<AccountEntity> = emptyList()
    private val selectedAccountIds = mutableSetOf<String>()

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val number = cursor.getString(1)
                    binding.etLoanClientName.setText(name)
                    binding.etLoanClientPhone.setText(number)
                    selectedClientId = null
                }
            }
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            when(activeCaptureType) {
                "CLIENT" -> {
                    Toast.makeText(this, "Foto del cliente capturada 📸", Toast.LENGTH_SHORT).show()
                    if (!clientPhotoPath.isNullOrEmpty()) {
                        val file = File(clientPhotoPath!!)
                        if (file.exists()) {
                            com.bumptech.glide.Glide.with(this)
                                .load(file)
                                .circleCrop()
                                .into(binding.ivClientPhotoPreview)
                        }
                    }
                }
                "DOC" -> Toast.makeText(this, "Foto del documento capturada", Toast.LENGTH_SHORT).show()
                "COLLATERAL" -> Toast.makeText(this, "Foto de la garantía capturada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityLoansAddBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Estabilización Edge-to-Edge para formulario y footer
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            
            val footer = binding.btnSaveLoan.parent as View
            footer.setPadding(
                footer.paddingLeft,
                footer.paddingTop,
                footer.paddingRight,
                systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            )
            insets
        }

        binding.toolbarAddLoan.setNavigationOnClickListener { finish() }
        setupSyncIndicator()
        setupCalculator()
        setupClientAutocomplete()
        setupFundingAccounts()
        
        // Configurar selector de Fecha del Préstamo (siempre visible, hoy por defecto)
        val sdfLoanDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        binding.btnLoanStartDatePicker.text = "📅 Fecha del Préstamo: " + sdfLoanDate.format(Date(selectedStartDate))
        binding.btnLoanStartDatePicker.setOnClickListener { showStartDatePicker() }

        // Configurar switch de Fecha de Cobro Opcional
        binding.switchEnableDueDate.setOnCheckedChangeListener { _, isChecked ->
            binding.btnLoanDatePicker.visibility = if (isChecked) View.VISIBLE else View.GONE
            calculate()
        }

        binding.btnLoanDatePicker.setOnClickListener { showDueDatePicker() }

        // Configurar switch de Plan de Pago / Cuotas Opcional
        binding.switchEnablePaymentPlan.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutPaymentPlanOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            calculate()
        }

        // Configurar switch de Detalles Adicionales Opcional
        binding.switchEnableAdvancedOptions.setOnCheckedChangeListener { _, isChecked ->
            hideKeyboard()
            binding.layoutAdvancedOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Configurar selector de contactos
        binding.btnPickContact.setOnClickListener {
            checkContactsPermissionAndPick()
        }

        val preselectedId = intent.getStringExtra("SELECTED_CLIENT_ID")
        if (preselectedId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = database.loanDao().getClientById(preselectedId)
                    withContext(Dispatchers.Main) {
                        client?.let {
                            selectedClientId = it.id
                            binding.etLoanClientName.setText(it.name)
                            binding.etLoanClientPhone.setText(it.phone)
                            binding.etLoanClientDoc.setText(it.doc)
                            binding.etLoanClientAddress.setText(it.address)
                            if (!it.photoPath.isNullOrEmpty()) {
                                clientPhotoPath = it.photoPath
                                val file = File(it.photoPath)
                                if (file.exists()) {
                                    com.bumptech.glide.Glide.with(this@AddLoanActivity)
                                        .load(file)
                                        .circleCrop()
                                        .into(binding.ivClientPhotoPreview)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AddLoanActivity", "Error loading preselected client: ${e.message}")
                }
            }
        }

        binding.btnCaptureClientPhoto.setOnClickListener { launchCamera("CLIENT") }
        binding.btnCaptureDocPhoto.setOnClickListener { launchCamera("DOC") }
        binding.btnCaptureCollateralPhoto.setOnClickListener { launchCamera("COLLATERAL") }
        binding.btnSaveLoan.setOnClickListener { saveLoan() }
        binding.btnLoanDatePicker.setOnClickListener { showDueDatePicker() }
        setupKeyboardHelpers()

        binding.toggleLoanFrequency.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                hideKeyboard()
                if (checkedId == R.id.btnFreqNone) {
                    binding.etLoanInstallments.setText("1")
                } else {
                    if (binding.etLoanInstallments.text.toString() == "1") {
                        binding.etLoanInstallments.setText("24")
                    }
                }
                calculate()
            }
        }

        val refinanceAmount = intent.getDoubleExtra("REFINANCE_AMOUNT", 0.0)
        if (refinanceAmount > 0) {
            binding.etLoanAmount.setText(refinanceAmount.toString())
            val refinanceClientId = intent.getStringExtra("REFINANCE_CLIENT_ID")
            if (refinanceClientId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val client = database.loanDao().getClientById(refinanceClientId)
                        withContext(Dispatchers.Main) {
                            client?.let { 
                                binding.etLoanClientName.setText(it.name) 
                                selectedClientId = it.id
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AddLoanActivity", "Error loading refinance client: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setupSyncIndicator() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkAvailable = true
                runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: Network) {
                isNetworkAvailable = false
                runOnUiThread { updateSyncIconState() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) { }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.loanDao().getUnsyncedCount().collectLatest { count ->
                    withContext(Dispatchers.Main) {
                        lastUnsyncedCount = count
                        updateSyncIconState()
                    }
                }
            } catch (e: Exception) {
                Log.e("AddLoanActivity", "Error loading unsynced count: ${e.message}")
            }
        }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            AlertDialog.Builder(this)
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
                color = Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = Color.parseColor("#3B82F6")
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun setupKeyboardHelpers() {
        binding.etLoanClientPhone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(); true
            } else false
        }
        binding.etLoanInstallments.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(); true
            } else false
        }
    }

    private fun checkContactsPermissionAndPick() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 1002)
        } else {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickContactLauncher.launch(intent)
        }
    }

    private fun setupClientAutocomplete() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.loanDao().getAllClients().collectLatest { clients ->
                    withContext(Dispatchers.Main) {
                        allClients = clients
                        val names = clients.map { it.name }
                        val adapter = ArrayAdapter(this@AddLoanActivity, android.R.layout.simple_dropdown_item_1line, names)
                        val autoView = binding.etLoanClientName as? MaterialAutoCompleteTextView
                        autoView?.setAdapter(adapter)
                        autoView?.setOnItemClickListener { _, _, position, _ ->
                            val selectedName = adapter.getItem(position)
                            val client = allClients.find { it.name == selectedName }
                            client?.let {
                                selectedClientId = it.id
                                binding.etLoanClientPhone.setText(it.phone)
                                binding.etLoanClientDoc.setText(it.doc)
                                binding.etLoanClientAddress.setText(it.address)
                                if (!it.photoPath.isNullOrEmpty()) {
                                    clientPhotoPath = it.photoPath
                                    val file = File(it.photoPath)
                                    if (file.exists()) {
                                        com.bumptech.glide.Glide.with(this@AddLoanActivity)
                                            .load(file)
                                            .circleCrop()
                                            .into(binding.ivClientPhotoPreview)
                                    }
                                }
                                Toast.makeText(this@AddLoanActivity, "Cliente seleccionado: ${it.name}", Toast.LENGTH_SHORT).show()
                                hideKeyboard()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AddLoanActivity", "Error loading clients: ${e.message}")
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showStartDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedStartDate }
        val picker = DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            selectedStartDate = cal.timeInMillis
            binding.btnLoanStartDatePicker.text = "📅 Inició el: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
            calculate()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        picker.show()
    }

    private fun showDueDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDueDate }
        val picker = DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            selectedDueDate = cal.timeInMillis
            binding.btnLoanDatePicker.text = "📅 Cobrar el: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
            calculate()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        picker.show()
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun launchCamera(type: String) {
        activeCaptureType = type
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = File(getExternalFilesDir(null), "loan_${type}_${System.currentTimeMillis()}.jpg")
        when(type) {
            "CLIENT" -> clientPhotoPath = photoFile.absolutePath
            "DOC" -> docPhotoPath = photoFile.absolutePath
            "COLLATERAL" -> collateralPhotoPath = photoFile.absolutePath
        }
        
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
    }

    private fun setupFundingAccounts() {
        binding.toggleFundingSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val origin = if (checkedId == R.id.btnFundingPersonal) "PERSONAL" else "BUSINESS"
                loadLinkedAccountsForOrigin(origin)
            }
        }
        // Cargar por defecto las cuentas del Presupuesto (Negocio)
        loadLinkedAccountsForOrigin("BUSINESS")
    }

    private fun loadLinkedAccountsForOrigin(origin: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val accounts = database.accountDao().getAllAccountsList()
                val filtered = accounts.filter { !it.isDeleted && it.origin == origin }
                val displayAccounts = if (filtered.isNotEmpty()) filtered else accounts.filter { !it.isDeleted }

                withContext(Dispatchers.Main) {
                    allOriginAccounts = displayAccounts
                    binding.layoutAccountChipsContainer.removeAllViews()
                    selectedAccountIds.clear()

                    if (displayAccounts.isEmpty()) {
                        binding.tvLinkedAccountsLabel.text = if (origin == "PERSONAL") "No hay cuentas del Bolsillo Personal" else "No hay cuentas del Presupuesto del Negocio"
                        binding.tvAvailableFundsSummary.text = "Saldo disponible: S/ 0.00 (Registra cuentas en Ajustes > Cuentas)"
                    } else {
                        binding.tvLinkedAccountsLabel.text = if (origin == "PERSONAL") "Cuentas del Bolsillo Personal (selecciona para debitar):" else "Cuentas del Presupuesto del Negocio (selecciona para debitar):"

                        displayAccounts.forEach { acc ->
                            val checkBox = com.google.android.material.checkbox.MaterialCheckBox(this@AddLoanActivity).apply {
                                text = "${acc.name} (${acc.bankName}) — S/ ${String.format(Locale.US, "%.2f", acc.balance)}"
                                textSize = 13f
                                setTextColor(androidx.core.content.ContextCompat.getColor(this@AddLoanActivity, R.color.slate_900))
                                isChecked = acc.balance > 0
                                setOnCheckedChangeListener { _, isChecked ->
                                    if (isChecked) {
                                        selectedAccountIds.add(acc.id)
                                    } else {
                                        selectedAccountIds.remove(acc.id)
                                    }
                                    updateFundsCheck()
                                }
                            }
                            if (checkBox.isChecked) {
                                selectedAccountIds.add(acc.id)
                            }
                            binding.layoutAccountChipsContainer.addView(checkBox)
                        }
                    }
                    updateFundsCheck()
                }
            } catch (e: Exception) {
                Log.e("AddLoanActivity", "Error loading origin accounts: ${e.message}")
            }
        }
    }

    private fun updateFundsCheck() {
        val loanAmount = binding.etLoanAmount.text.toString().toDoubleOrNull() ?: 0.0
        val selectedFunds = allOriginAccounts.filter { it.id in selectedAccountIds }.sumOf { it.balance }

        binding.tvAvailableFundsSummary.text = "Saldo disponible en cuenta(s) seleccionada(s): S/ ${String.format(Locale.US, "%.2f", selectedFunds)}"

        if (loanAmount > 0 && selectedFunds < loanAmount) {
            binding.tvFundsWarning.visibility = View.VISIBLE
            binding.tvFundsWarning.text = "⚠️ Fondos insuficientes en la(s) cuenta(s) seleccionada(s). Requerido: S/ ${String.format(Locale.US, "%.2f", loanAmount)}, Disponible: S/ ${String.format(Locale.US, "%.2f", selectedFunds)}. Selecciona más cuentas o ajusta el saldo."
        } else {
            binding.tvFundsWarning.visibility = View.GONE
        }
    }

    private fun setupCalculator() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { calculate() }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etLoanAmount.addTextChangedListener(watcher)
        binding.etLoanInterest.addTextChangedListener(watcher)
        binding.etLoanInstallments.addTextChangedListener(watcher)

        binding.etLoanInterest.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (binding.etLoanInterest.text.toString().trim().isEmpty()) {
                    binding.etLoanInterest.setText("0")
                }
            }
        }
    }

    private fun calculate() {
        val amount = binding.etLoanAmount.text.toString().toDoubleOrNull() ?: 0.0
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 0.0
        val hasDueDate = binding.switchEnableDueDate.isChecked
        val hasPlan = binding.switchEnablePaymentPlan.isChecked
        val installments = if (hasPlan) (binding.etLoanInstallments.text.toString().toIntOrNull() ?: 1) else 1

        if (amount > 0) {
            binding.cardLoanCalculationSummary.visibility = View.VISIBLE

            val total = amount + (amount * (interest / 100))
            val quota = if (hasPlan && installments > 0) total / installments else total

            when {
                hasPlan && installments > 1 -> {
                    // Caso con cuotas múltiples
                    binding.tvLoanCalculatedQuota.text = "Cuota (${installments}x): S/ ${String.format(Locale.US, "%.2f", quota)}"
                    binding.tvLoanCalculatedTotal.text = "Total a devolver: S/ ${String.format(Locale.US, "%.2f", total)} (Capital: S/ ${String.format(Locale.US, "%.2f", amount)} + ${interest}%)"
                }
                interest > 0 -> {
                    // Caso sin cuotas (préstamo único), pero con interés
                    binding.tvLoanCalculatedQuota.text = "Total a devolver: S/ ${String.format(Locale.US, "%.2f", total)}"
                    binding.tvLoanCalculatedTotal.text = "Monto prestado: S/ ${String.format(Locale.US, "%.2f", amount)} con ${interest}% de interés"
                }
                else -> {
                    // Caso sin cuotas y sin interés (solo monto prestado)
                    binding.tvLoanCalculatedQuota.text = "Monto prestado: S/ ${String.format(Locale.US, "%.2f", amount)}"
                    binding.tvLoanCalculatedTotal.text = "Sin interés registrado"
                }
            }

            val freqText = when(binding.toggleLoanFrequency.checkedButtonId) {
                R.id.btnFreqDaily -> "Diario"
                R.id.btnFreqWeekly -> "Semanal"
                R.id.btnFreqMonthly -> "Mensual"
                else -> "Única"
            }
            
            val detailsBuilder = StringBuilder()
            if (hasPlan) {
                detailsBuilder.append("Plan: $freqText")
            } else {
                detailsBuilder.append("Pago único")
            }
            
            if (hasDueDate) {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                detailsBuilder.append(" • Vence: ${sdf.format(Date(selectedDueDate))}")
            } else {
                detailsBuilder.append(" • Sin fecha fija")
            }
            
            binding.tvLoanCalculatedDetails.text = detailsBuilder.toString()
        } else {
            binding.cardLoanCalculationSummary.visibility = View.GONE
        }
        updateFundsCheck()
    }

    private fun saveLoan() {
        val clientName = binding.etLoanClientName.text.toString().trim()
        val amount = binding.etLoanAmount.text.toString().toDoubleOrNull() ?: 0.0
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 0.0
        
        val hasDueDate = binding.switchEnableDueDate.isChecked
        val hasPaymentPlan = binding.switchEnablePaymentPlan.isChecked
        
        val installments = if (!hasPaymentPlan) 1 else (binding.etLoanInstallments.text.toString().toIntOrNull() ?: 24)
        val lateFee = binding.etLoanLateFee.text.toString().toDoubleOrNull() ?: 0.0
        val graceDays = binding.etLoanGraceDays.text.toString().toIntOrNull() ?: 0
        val collateralDesc = binding.etLoanCollateralDesc.text.toString().trim()
        
        val clientPhone = binding.etLoanClientPhone.text.toString().trim()
        val clientDoc = binding.etLoanClientDoc.text.toString().trim()
        val clientAddress = binding.etLoanClientAddress.text.toString().trim()

        if (clientName.isEmpty() || amount <= 0) {
            Toast.makeText(this, "⚠️ Por favor, ingresa el nombre y el monto", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedFunds = allOriginAccounts.filter { it.id in selectedAccountIds }.sumOf { it.balance }
        if (amount > selectedFunds) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Fondos Insuficientes")
                .setMessage("No se puede efectuar el préstamo de S/ ${String.format(Locale.US, "%.2f", amount)} porque el saldo total disponible en la(s) cuenta(s) seleccionada(s) es de solo S/ ${String.format(Locale.US, "%.2f", selectedFunds)}.\n\nPor favor, selecciona más cuentas vinculadas o ajusta tus saldos en Ajustes > Cuentas.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val oldLoanId = intent.getStringExtra("OLD_LOAN_ID")

            // Descontar fondos de las cuentas seleccionadas
            var remainingToDeduct = amount
            for (accId in selectedAccountIds) {
                if (remainingToDeduct <= 0) break
                val acc = allOriginAccounts.find { it.id == accId } ?: continue
                if (acc.balance <= 0) continue

                val deduct = Math.min(acc.balance, remainingToDeduct)
                acc.balance -= deduct
                remainingToDeduct -= deduct
                acc.isSynced = false
                database.accountDao().update(acc)
            }
            
            var clientId = selectedClientId
            val existingByExactName = allClients.find { it.name.equals(clientName, ignoreCase = true) }
            if (clientId == null && existingByExactName != null) {
                clientId = existingByExactName.id
            }

            if (clientId == null) {
                clientId = UUID.randomUUID().toString()
                val client = LoanClientEntity(
                    id = clientId!!, 
                    name = clientName, 
                    doc = clientDoc, 
                    phone = clientPhone,
                    address = clientAddress,
                    photoPath = clientPhotoPath,
                    docPhotoPath = docPhotoPath
                )
                database.loanDao().insertClient(client)

                // REGISTRAR EN HISTORIAL (Nuevo Cliente)
                val clientLog = MovementLogEntity(
                    type = "CLIENT_CREATED",
                    title = "Nuevo Cliente",
                    description = "Cliente registrado: $clientName",
                    value = "",
                    colorHex = "#3B82F6",
                    iconRes = android.R.drawable.ic_input_add
                )
                database.movementLogDao().insert(clientLog)
                SyncManager(this@AddLoanActivity).syncLogToCloud(clientLog)
            } else {
                val existing = database.loanDao().getClientById(clientId!!)
                existing?.let {
                    database.loanDao().updateClient(it.copy(
                        phone = if (clientPhone.isNotEmpty()) clientPhone else it.phone,
                        doc = if (clientDoc.isNotEmpty()) clientDoc else it.doc,
                        address = if (clientAddress.isNotEmpty()) clientAddress else it.address,
                        photoPath = clientPhotoPath ?: it.photoPath
                    ))
                }
            }

            val total = amount + (amount * (interest / 100))
            val freq = if (!hasPaymentPlan) "NONE" else when(binding.toggleLoanFrequency.checkedButtonId) {
                R.id.btnFreqWeekly -> "WEEKLY"
                R.id.btnFreqMonthly -> "MONTHLY"
                R.id.btnFreqNone -> "NONE"
                else -> "DAILY"
            }
            
            val isPersonalFunding = binding.toggleFundingSource.checkedButtonId == R.id.btnFundingPersonal

            // Evitar duplicidad de préstamos activos para el mismo cliente
            val existingActiveLoans = database.loanDao().getLoansByClientSync(clientId!!)
            existingActiveLoans.filter { it.status == "ACTIVE" || it.status == "OVERDUE" }.forEach { existing ->
                if (existing.id != oldLoanId) {
                    database.loanDao().updateLoan(existing.copy(status = "REFINANCED", isSynced = false))
                }
            }

            val finalCreatedAt = System.currentTimeMillis()

            val loanId = UUID.randomUUID().toString()
            val loan = LoanEntity(
                id = loanId,
                clientId = clientId!!,
                amount = amount,
                interestRate = interest,
                totalToPay = total,
                installmentsCount = installments,
                frequency = freq,
                startDate = selectedStartDate,
                createdAt = finalCreatedAt,
                lateFeeAmount = lateFee,
                graceDays = graceDays,
                collateralDescription = collateralDesc,
                collateralPhotoPath = collateralPhotoPath,
                isPersonal = isPersonalFunding
            )
            database.loanDao().insertLoan(loan)

            // Guardar el origen de fondos en la tabla PROTEGIDA (inmune a la nube)
            database.loanDao().insertFundingSource(
                LoanFundingSourceEntity(
                    loanId = loan.id,
                    isPersonal = isPersonalFunding,
                    updatedAt = System.currentTimeMillis()
                )
            )

            // REGISTRAR EN HISTORIAL (Nuevo Préstamo)
            val loanLog = MovementLogEntity(
                type = "LOAN_CREATED",
                title = "Nuevo Préstamo",
                description = "Préstamo a $clientName • Capital: S/ ${String.format(Locale.US, "%.2f", amount)} • Total: S/ ${String.format(Locale.US, "%.2f", total)}",
                value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                colorHex = "#2563EB",
                iconRes = android.R.drawable.ic_menu_send
            )
            database.movementLogDao().insert(loanLog)
            SyncManager(this@AddLoanActivity).syncLogToCloud(loanLog)

            val schedule = mutableListOf<LoanInstallmentEntity>()
            val quotaAmount = if (installments > 0) total / installments else total
            val cal = Calendar.getInstance().apply { 
                timeInMillis = if (hasDueDate) selectedDueDate else selectedStartDate 
            }
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)

            val skipSundays = binding.cbSkipSundays.isChecked
            
            if (!hasPaymentPlan || freq == "NONE") {
                schedule.add(LoanInstallmentEntity(
                    loanId = loanId,
                    installmentNumber = 1,
                    amount = total,
                    dueDate = if (hasDueDate) selectedDueDate else 0L
                ))
            } else {
                for (i in 1..installments) {
                    if (i > 1) {
                        do {
                            when(freq) {
                                "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                                "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                                "MONTHLY" -> cal.add(Calendar.MONTH, 1)
                            }
                        } while (skipSundays && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                    } else {
                        while (skipSundays && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                            cal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    
                    schedule.add(LoanInstallmentEntity(
                        loanId = loanId,
                        installmentNumber = i,
                        amount = quotaAmount,
                        dueDate = if (hasDueDate) cal.timeInMillis else 0L
                    ))
                }
            }
            database.loanDao().insertInstallments(schedule)

            oldLoanId?.let { id ->
                val oldLoan = database.loanDao().getLoanById(id)
                oldLoan?.let { 
                    database.loanDao().updateLoan(it.copy(status = "REFINANCED")) 
                }
            }

            SyncManager(this@AddLoanActivity).scheduleOfflineSync()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddLoanActivity, if (oldLoanId != null) "Préstamo refinanciado en GoMezMart" else "Préstamo registrado en GoMezMart ✅", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
