package com.naxor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.naxor.app.data.*
import com.naxor.app.databinding.ActivityLoansAddBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class AddLoanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoansAddBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var clientPhotoPath: String? = null
    private var docPhotoPath: String? = null
    private var collateralPhotoPath: String? = null
    private var activeCaptureType: String? = null
    
    private var lastLat: Double? = null
    private var lastLon: Double? = null

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
        captureLocation()
        
        binding.btnCaptureClientPhoto.setOnClickListener { launchCamera("CLIENT") }
        binding.btnCaptureDocPhoto.setOnClickListener { launchCamera("DOC") }
        binding.btnCaptureCollateralPhoto.setOnClickListener { launchCamera("COLLATERAL") }
        binding.btnSaveLoan.setOnClickListener { saveLoan() }

        // Si es refinanciación, pre-llenar monto
        val refinanceAmount = intent.getDoubleExtra("REFINANCE_AMOUNT", 0.0)
        if (refinanceAmount > 0) {
            binding.etLoanAmount.setText(refinanceAmount.toString())
            val refinanceClientId = intent.getStringExtra("REFINANCE_CLIENT_ID")
            if (refinanceClientId != null) {
                lifecycleScope.launch {
                    val client = database.loanDao().getClientById(refinanceClientId)
                    client?.let { binding.etLoanClientName.setText(it.name) }
                }
            }
        }
    }

    private fun captureLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                lastLat = it.latitude
                lastLon = it.longitude
                Log.d("LOAN", "Ubicación capturada: $lastLat, $lastLon")
            }
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
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 0.0
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
        val interest = binding.etLoanInterest.text.toString().toDoubleOrNull() ?: 0.0
        val installments = binding.etLoanInstallments.text.toString().toIntOrNull() ?: 1
        val lateFee = binding.etLoanLateFee.text.toString().toDoubleOrNull() ?: 0.0
        val graceDays = binding.etLoanGraceDays.text.toString().toIntOrNull() ?: 0
        val collateralDesc = binding.etLoanCollateralDesc.text.toString().trim()

        if (clientName.isEmpty() || amount <= 0) {
            Toast.makeText(this, "Completa nombre y monto", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val oldLoanId = intent.getStringExtra("OLD_LOAN_ID")
            
            // 1. Crear o buscar cliente
            var clientId = intent.getStringExtra("REFINANCE_CLIENT_ID")
            if (clientId == null) {
                clientId = UUID.randomUUID().toString()
                val client = LoanClientEntity(
                    id = clientId, 
                    name = clientName, 
                    doc = "", 
                    phone = "",
                    photoPath = clientPhotoPath,
                    docPhotoPath = docPhotoPath,
                    latitude = lastLat,
                    longitude = lastLon
                )
                database.loanDao().insertClient(client)
            }

            // 2. Crear préstamo
            val total = amount + (amount * (interest / 100))
            val freq = when(binding.toggleLoanFrequency.checkedButtonId) {
                R.id.btnFreqWeekly -> "WEEKLY"
                R.id.btnFreqMonthly -> "MONTHLY"
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

            // 3. Generar cronograma
            val schedule = mutableListOf<LoanInstallmentEntity>()
            val quotaAmount = total / installments
            val cal = Calendar.getInstance()
            val skipSundays = binding.cbSkipSundays.isChecked
            
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
            database.loanDao().insertInstallments(schedule)

            // 4. Si es refinanciación, liquidar el anterior
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
