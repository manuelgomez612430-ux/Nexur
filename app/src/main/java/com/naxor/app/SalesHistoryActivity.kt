package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Pair
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.naxor.app.adapter.SaleAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivitySalesHistoryBinding
import com.naxor.app.network.RetrofitClient
import com.naxor.app.util.ComprobantePdfGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class SalesHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySalesHistoryBinding
    private lateinit var adapter: SaleAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    private var startDate: Long? = null
    private var endDate: Long? = null
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySalesHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = SaleAdapter(
            items = emptyList(),
            onShare = { items -> shareTicket(items) },
            onEmit = { items -> showComprobanteDialog(items) },
            onLongClick = { items -> showDeleteConfirmation(items) }
        )
        binding.rvSalesHistory.layoutManager = LinearLayoutManager(this)
        binding.rvSalesHistory.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackSalesHistory.setOnClickListener { finish() }
        binding.btnOpenMenuSalesHistory.setOnClickListener {
            binding.drawerLayoutSalesHistory.openDrawer(androidx.core.view.GravityCompat.START)
        }

        binding.navigationViewSalesHistory.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutSalesHistory.closeDrawer(androidx.core.view.GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_clear_history -> {
                    showDeleteAllConfirmation()
                    true
                }
                else -> false
            }
        }

        binding.etSearchSalesHistory.addTextChangedListener { text ->
            currentQuery = text.toString()
            loadData()
        }

        binding.btnFilterDateRange.setOnClickListener {
            showDateRangePicker()
        }
    }

    private fun showDateRangePicker() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val startPicker = android.app.DatePickerDialog(this, { _, y1, m1, d1 ->
            val startCal = Calendar.getInstance()
            startCal.set(y1, m1, d1, 0, 0, 0)
            val tempStart = startCal.timeInMillis

            val endPicker = android.app.DatePickerDialog(this, { _, y2, m2, d2 ->
                val endCal = Calendar.getInstance()
                endCal.set(y2, m2, d2, 23, 59, 59)
                
                startDate = tempStart
                endDate = endCal.timeInMillis
                updateFilterLabel()
                loadData()
            }, y1, m1, d1)
            endPicker.setTitle("Seleccionar fecha final")
            endPicker.show()
        }, year, month, day)
        startPicker.setTitle("Seleccionar fecha inicial")
        startPicker.show()
    }

    private fun updateFilterLabel() {
        if (startDate != null && endDate != null) {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val startStr = sdf.format(Date(startDate!!))
            val endStr = sdf.format(Date(endDate!!))
            binding.tvCurrentDateRange.text = "Mostrando: del $startStr al $endStr"
            binding.tvCurrentDateRange.setTextColor(Color.parseColor("#7C3AED"))
            binding.tvCurrentDateRange.visibility = View.VISIBLE
        } else {
            binding.tvCurrentDateRange.text = "Mostrando: Historial completo"
            binding.tvCurrentDateRange.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allSales = database.saleDao().allSales
            
            val filteredByQuery = if (currentQuery.isEmpty()) {
                allSales
            } else {
                allSales.filter { 
                    it.nombreProducto.contains(currentQuery, ignoreCase = true) || 
                    it.transactionId.contains(currentQuery, ignoreCase = true)
                }
            }

            val filteredByDate = if (startDate != null && endDate != null) {
                filteredByQuery.filter { sale ->
                    val saleDate = sale.timestamp
                    saleDate >= startDate!! && saleDate <= (endDate!! + 86399999)
                }
            } else {
                filteredByQuery
            }

            val groupedByDay = filteredByDate.groupBy { 
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
            }

            val finalItems = mutableListOf<SaleAdapter.SaleListItem>()
            val daySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val displayDateSdf = SimpleDateFormat("d 'de' MMMM", Locale.getDefault())
            val todayStr = daySdf.format(Date())
            
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayStr = daySdf.format(calendar.time)

            groupedByDay.keys.sortedDescending().forEach { dayKey ->
                val headerLabel = when (dayKey) {
                    todayStr -> "Hoy"
                    yesterdayStr -> "Ayer"
                    else -> {
                        val date = daySdf.parse(dayKey)
                        if (date != null) displayDateSdf.format(date) else dayKey
                    }
                }
                
                finalItems.add(SaleAdapter.SaleListItem.Header(headerLabel))
                
                val transactionsInDay = groupedByDay[dayKey]!!.groupBy { it.transactionId }
                    .values.toList()
                    .sortedByDescending { it[0].timestamp }
                
                transactionsInDay.forEach { trans ->
                    finalItems.add(SaleAdapter.SaleListItem.Transaction(trans))
                }
            }

            withContext(Dispatchers.Main) {
                adapter.updateList(finalItems)
                binding.layoutEmptySalesHistory.visibility = if (finalItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showComprobanteDialog(items: List<SaleEntity>) {
        val total = items.sumOf { it.total }
        val options = arrayOf("Nota de Venta (Interno)", "Boleta Electrónica", "Factura Electrónica")
        var selectedType = 0

        AlertDialog.Builder(this)
            .setTitle("Emitir Comprobante Legal")
            .setSingleChoiceItems(options, 0) { _, which -> selectedType = which }
            .setPositiveButton("Continuar") { _, _ ->
                when (selectedType) {
                    0 -> finalizeEmissionWithDetails(items, "NOTA_VENTA", "", "", "")
                    1 -> {
                        if (total > 700) showCustomerDataDialog(items, "BOLETA", true)
                        else showCustomerDataDialog(items, "BOLETA", false)
                    }
                    2 -> showCustomerDataDialog(items, "FACTURA", true)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCustomerDataDialog(items: List<SaleEntity>, docType: String, isMandatory: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_customer_data, null)
        val etDoc = view.findViewById<android.widget.EditText>(R.id.etCustomerDoc)
        val etName = view.findViewById<android.widget.EditText>(R.id.etCustomerName)
        val etAddress = view.findViewById<android.widget.EditText>(R.id.etCustomerAddress)
        val progress = view.findViewById<android.view.View>(R.id.progressDocSearch)
        
        etDoc.hint = if (docType == "FACTURA") "RUC del Cliente" else "DNI del Cliente (Opcional)"
        etName.hint = if (docType == "FACTURA") "Razón Social" else "Nombre del Cliente (Opcional)"

        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val apiToken = prefs.getString("api_token", "") ?: ""

        val inputLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomerDoc)

        val consultaAction = {
            val code = etDoc.text.toString().trim()
            if (code.length == 8 || code.length == 11) {
                if (apiToken.isEmpty()) {
                    Toast.makeText(this, "Configura el Token de API en Ajustes", Toast.LENGTH_SHORT).show()
                } else {
                    ejecutarConsultaDocumento(code, apiToken, etName, etAddress, progress)
                }
            }
        }

        etDoc.addTextChangedListener { text ->
            if (text?.length == 8 || text?.length == 11) consultaAction()
        }

        inputLayout.setEndIconOnClickListener { consultaAction() }

        AlertDialog.Builder(this)
            .setTitle("Datos del Cliente")
            .setView(view)
            .setPositiveButton("Emitir") { _, _ ->
                val doc = etDoc.text.toString().trim()
                val name = etName.text.toString().trim()
                val address = etAddress.text.toString().trim()

                if (isMandatory && doc.isEmpty()) {
                    Toast.makeText(this, "El documento es obligatorio", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                finalizeEmissionWithDetails(items, docType, doc, name, address)
            }
            .setNegativeButton("Atrás", null)
            .show()
    }

    private fun ejecutarConsultaDocumento(code: String, token: String, etName: android.widget.EditText, etAddress: android.widget.EditText, progress: View) {
        lifecycleScope.launch {
            try {
                progress.visibility = View.VISIBLE
                if (code.length == 8) {
                    val res = withContext(Dispatchers.IO) { com.naxor.app.network.RetrofitClient.api.buscarDni(code, token) }
                    if (res.success && res.data != null) {
                        val fullName = res.data.nombreCompleto ?: res.data.nombre_completo ?: 
                                     "${res.data.nombres ?: res.data.nombre ?: ""} ${res.data.apellidoPaterno ?: ""} ${res.data.apellidoMaterno ?: ""}"
                        etName.setText(fullName.trim().uppercase())
                    } else {
                        Toast.makeText(this@SalesHistoryActivity, "DNI no encontrado", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val res = withContext(Dispatchers.IO) { com.naxor.app.network.RetrofitClient.api.buscarRuc(code, token) }
                    if (res.success && res.data != null) {
                        val bizName = res.data.razonSocial ?: res.data.razon_social ?: res.data.nombre_o_razon_social
                        val bizAddr = res.data.direccion ?: res.data.direccion_completa
                        etName.setText(bizName?.uppercase())
                        etAddress.setText(bizAddr?.uppercase())
                    } else {
                        Toast.makeText(this@SalesHistoryActivity, "RUC no encontrado o inválido", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("SalesHistory", "Error en consulta: ${e.message}")
                Toast.makeText(this@SalesHistoryActivity, "Error de conexión con SUNAT/RENIEC", Toast.LENGTH_SHORT).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun finalizeEmissionWithDetails(items: List<SaleEntity>, docType: String, cDoc: String, cName: String, cAddress: String) {
        val docPrefs = getSharedPreferences("DocumentPrefs", MODE_PRIVATE)
        val series = when(docType) {
            "BOLETA" -> "B001"
            "FACTURA" -> "F001"
            else -> "NV01"
        }
        val nextCorrelative = docPrefs.getInt("last_${docType.lowercase()}", 0) + 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                items.forEach { sale ->
                    sale.documentType = docType
                    sale.series = series
                    sale.correlative = nextCorrelative
                    sale.customerDoc = cDoc
                    sale.customerName = cName
                    sale.customerAddress = cAddress
                    sale.isSynced = false
                    database.saleDao().update(sale)
                }
                
                docPrefs.edit().putInt("last_${docType.lowercase()}", nextCorrelative).apply()
                SyncManager(this@SalesHistoryActivity).scheduleOfflineSync()

                withContext(Dispatchers.Main) {
                    shareTicket(items) // Reutiliza la función que genera el PDF
                    loadData()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun shareTicket(items: List<SaleEntity>) {
        val pdfFile = ComprobantePdfGenerator(this).generateComprobantePdf(items)
        if (pdfFile != null && pdfFile.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar Comprobante PDF"))
        } else {
            Toast.makeText(this, "Error al generar comprobante", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(items: List<SaleEntity>) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Venta")
            .setMessage("¿Deseas anular esta venta? El stock de los productos se devolverá automáticamente al inventario.")
            .setPositiveButton("Eliminar") { _, _ ->
                deleteTransaction(items)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteTransaction(items: List<SaleEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            items.forEach { sale ->
                sale.isDeleted = true
                sale.isSynced = false
                database.saleDao().update(sale)

                val product = database.productDao().getProductById(sale.productId)
                if (product != null) {
                    product.stock += sale.cantidad
                    product.precioCosto += (sale.costoUnitario * sale.cantidad)
                    product.isSynced = false
                    database.productDao().update(product)
                }

                val log = com.naxor.app.data.MovementLogEntity(
                    type = "SALE_DELETED",
                    title = "Venta Eliminada",
                    description = "${sale.cantidad} x ${sale.nombreProducto}",
                    value = "ANULADO",
                    colorHex = "#95A5A6",
                    iconRes = android.R.drawable.ic_menu_close_clear_cancel
                )
                database.movementLogDao().insert(log)
                SyncManager(this@SalesHistoryActivity).syncLogToCloud(log)
            }
            SyncManager(this@SalesHistoryActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) { loadData() }
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Vaciar Historial")
            .setMessage("¿Estás seguro de que deseas borrar TODAS las ventas? Esta acción no se puede deshacer.")
            .setPositiveButton("Vaciar Todo") { _, _ ->
                deleteAllSales()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteAllSales() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.saleDao().deleteAllSales()
            SyncManager(this@SalesHistoryActivity).scheduleOfflineSync()
            loadData()
        }
    }
}
