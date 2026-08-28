package com.naxor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.*
import com.naxor.app.databinding.ActivityLoansAddBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AddLoanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoansAddBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var clientPhotoPath: String? = null
    private var docPhotoPath: String? = null
    private var collateralPhotoPath: String? = null
    private var activeCaptureType: String? = null
    
    private var selectedLoanDate = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
    private var selectedClientId: String? = null
    private var allClients: List<LoanClientEntity> = emptyList()

    private val pickContactLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
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

    private val takePhotoLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            when(activeCaptureType) {
                "CLIENT" -> Toast.makeText(this, "Foto del cliente capturada", Toast.LENGTH_SHORT).show()
                "DOC" -> Toast.makeText(this, "Foto del documento capturada", Toast.LENGTH_SHORT).show()
                "COLLATERAL" -> Toast.makeText(this, "Foto de la garantía capturada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarAddLoan.setNavigationOnClickListener { finish() }
        setupCalculator()
        setupAdvancedToggle()
        setupClientAutocomplete()
        
        // Configurar selector de contactos
        val phoneInputLayout = binding.etLoanClientPhone.parent.parent as? com.google.android.material.textfield.TextInputLayout
        phoneInputLayout?.setEndIconOnClickListener {
            checkContactsPermissionAndPick()
        }

        // Cargar cliente seleccionado si viene de la lista de selección
        val preselectedId = intent.getStringExtra("SELECTED_CLIENT_ID")
        if (preselectedId != null) {
            lifecycleScope.launch {
                val client = database.loanDao().getClientById(preselectedId)
                client?.let {
                    selectedClientId = it.id
                    binding.etLoanClientName.setText(it.name)
                    binding.etLoanClientPhone.setText(it.phone)
                    binding.etLoanClientDoc.setText(it.doc)
                    binding.etLoanClientAddress.setText(it.address)
                }
            }
        }

        binding.btnCaptureClientPhoto.setOnClickListener { launchCamera("CLIENT") }
        binding.btnCaptureDocPhoto.setOnClickListener { launchCamera("DOC") }
        binding.btnCaptureCollateralPhoto.setOnClickListener { launchCamera("COLLATERAL") }
        binding.btnSaveLoan.setOnClickListener { saveLoan() }
        binding.btnLoanDatePicker.setOnClickListener { showDatePicker() }
        setupKeyboardHelpers()

        binding.toggleLoanFrequency.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                hideKeyboard()
                if (checkedId == R.id.btnFreqNone) {
                    binding.btnLoanDatePicker.visibility = View.VISIBLE
                    binding.layoutInstallmentConfig.visibility = View.GONE
                    binding.etLoanInstallments.setText("1")
                } else {
                    binding.btnLoanDatePicker.visibility = View.GONE
                    binding.layoutInstallmentConfig.visibility = View.VISIBLE
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
                lifecycleScope.launch {
                    val client = database.loanDao().getClientById(refinanceClientId)
                    client?.let { 
                        binding.etLoanClientName.setText(it.name) 
                        selectedClientId = it.id
                    }
                }
            }
        }
    }

    private fun setupKeyboardHelpers() {
        // Al presionar Enter en campos finales, cerrar teclado
        binding.etLoanClientPhone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(); true
            } else false
        }
        binding.etLoanInstallments.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
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
        lifecycleScope.launch {
            database.loanDao().getAllClients().collectLatest { clients ->
                allClients = clients
                val names = clients.map { it.name }
                val adapter = ArrayAdapter(this@AddLoanActivity, android.R.layout.simple_dropdown_item_1line, names)
                val autoView = binding.etLoanClientName as? com.google.android.material.textfield.MaterialAutoCompleteTextView
                autoView?.setAdapter(adapter)
                autoView?.setOnItemClickListener { _, _, position, _ ->
                    val selectedName = adapter.getItem(position)
                    val client = allClients.find { it.name == selectedName }
                    client?.let {
                        selectedClientId = it.id
                        binding.etLoanClientPhone.setText(it.phone)
                        binding.etLoanClientDoc.setText(it.doc)
                        binding.etLoanClientAddress.setText(it.address)
                        Toast.makeText(this@AddLoanActivity, "Cliente seleccionado: ${it.name}", Toast.LENGTH_SHORT).show()
                        hideKeyboard()
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
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

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = selectedLoanDate
        val picker = android.app.DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            selectedLoanDate = cal.timeInMillis
            binding.btnLoanDatePicker.text = "📅 Cobrar el: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(cal.time)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        picker.show()
    }

    private fun setupAdvancedToggle() {
        binding.btnToggleAdvancedOptions.setOnClickListener {
            hideKeyboard()
            val isVisible = binding.layoutAdvancedOptions.visibility == View.VISIBLE
            binding.layoutAdvancedOptions.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleAdvancedOptions.text = if (isVisible) "➕ Añadir más detalles (Fotos, DNI, Dirección...)" else "➖ Ocultar detalles adicionales"
        }
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
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = File(getExternalFilesDir(null), "loan_${type}_${System.currentTimeMillis()}.jpg")
        when(type) {
            "CLIENT" -> clientPhotoPath = photoFile.absolutePath
            "DOC" -> docPhotoPath = photoFile.absolutePath
            "COLLATERAL" -> collateralPhotoPath = photoFile.absolutePath
        }
        
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
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
    }

    private fun calculate() {
        val amount = binding.etLoanAmount.text.toString().toDoubleOrNull() ?: 0.0
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 20.0
        val installments = binding.etLoanInstallments.text.toString().toIntOrNull() ?: 1

        if (amount > 0) {
            val total = amount + (amount * (interest / 100))
            val quota = total / installments
            
            binding.tvLoanCalculatedQuota.text = "Cuota: S/ ${String.format(Locale.US, "%.2f", quota)}"
            binding.tvLoanCalculatedTotal.text = "Total a devolver: S/ ${String.format(Locale.US, "%.2f", total)}"
        }
    }

    private fun saveLoan() {
        val clientName = binding.etLoanClientName.text.toString().trim()
        val amount = binding.etLoanAmount.text.toString().toDoubleOrNull() ?: 0.0
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 20.0
        val installments = binding.etLoanInstallments.text.toString().toIntOrNull() ?: 24
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

        lifecycleScope.launch(Dispatchers.IO) {
            val oldLoanId = intent.getStringExtra("OLD_LOAN_ID")
            
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
            } else {
                val existing = database.loanDao().getClientById(clientId!!)
                existing?.let {
                    database.loanDao().updateClient(it.copy(
                        phone = if (clientPhone.isNotEmpty()) clientPhone else it.phone,
                        doc = if (clientDoc.isNotEmpty()) clientDoc else it.doc,
                        address = if (clientAddress.isNotEmpty()) clientAddress else it.address
                    ))
                }
            }

            val total = amount + (amount * (interest / 100))
            val freq = when(binding.toggleLoanFrequency.checkedButtonId) {
                R.id.btnFreqWeekly -> "WEEKLY"
                R.id.btnFreqMonthly -> "MONTHLY"
                R.id.btnFreqNone -> "NONE"
                else -> "DAILY"
            }
            
            val loanId = UUID.randomUUID().toString()
            val loan = LoanEntity(
                id = loanId,
                clientId = clientId!!,
                amount = amount,
                interestRate = interest,
                totalToPay = total,
                installmentsCount = installments,
                frequency = freq,
                lateFeeAmount = lateFee,
                graceDays = graceDays,
                collateralDescription = collateralDesc,
                collateralPhotoPath = collateralPhotoPath
            )
            database.loanDao().insertLoan(loan)

            val schedule = mutableListOf<LoanInstallmentEntity>()
            val quotaAmount = total / installments
            val cal = Calendar.getInstance()
            // Normalizar el calendario de inicio al final del día de hoy
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)

            val skipSundays = binding.cbSkipSundays.isChecked
            
            if (freq == "NONE") {
                schedule.add(LoanInstallmentEntity(
                    loanId = loanId,
                    installmentNumber = 1,
                    amount = total,
                    dueDate = selectedLoanDate
                ))
            } else {
                for (i in 1..installments) {
                    do {
                        when(freq) {
                            "DAILY" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                            "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
                        }
                    } while (skipSundays && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                    
                    schedule.add(LoanInstallmentEntity(
                        loanId = loanId,
                        installmentNumber = i,
                        amount = quotaAmount,
                        dueDate = cal.timeInMillis
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

            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddLoanActivity, if (oldLoanId != null) "Préstamo refinanciado con éxito" else "Préstamo registrado con éxito", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
