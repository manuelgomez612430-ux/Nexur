package com.naxor.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.CartAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityVentasBinding
import com.naxor.app.network.RetrofitClient
import com.naxor.app.util.ComprobantePdfGenerator
import com.naxor.app.util.NotificationHelper
import com.naxor.app.util.VoiceRecognitionHelper
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class VentasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVentasBinding
    private lateinit var cartAdapter: CartAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var allProducts: List<ProductEntity> = emptyList()
    private val cartItems = mutableListOf<SaleEntity>()
    private val cartProducts = mutableListOf<ProductEntity?>()
    private var currentTransactionId = UUID.randomUUID().toString()
    private val voiceHelper by lazy { VoiceRecognitionHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVentasBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Solicitar permisos de cámara al inicio
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Se requiere permiso de cámara para el escáner", Toast.LENGTH_LONG).show()
            }
        }
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)

        setupCartRecyclerView()
        setupListeners()
        loadProducts()
    }

    private fun setupCartRecyclerView() {
        cartAdapter = CartAdapter(
            items = cartItems,
            onQtyChanged = { updateTotalPrice() },
            onRemove = { position ->
                cartItems.removeAt(position)
                cartProducts.removeAt(position)
                cartAdapter.notifyItemRemoved(position)
                updateTotalPrice()
                checkEmptyState()
            }
        )
        binding.rvCartItems.layoutManager = LinearLayoutManager(this)
        binding.rvCartItems.adapter = cartAdapter
    }

    private fun setupListeners() {
        binding.btnBackVentas.setOnClickListener { finish() }
        
        binding.btnOpenMenuVentas.setOnClickListener {
            binding.drawerLayoutVentas.openDrawer(GravityCompat.START)
        }

        binding.navigationViewVentas.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutVentas.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_sales_history -> {
                    startActivity(Intent(this, SalesHistoryActivity::class.java))
                    true
                }
                R.id.menu_reset_day -> {
                    // Lógica para reiniciar día si es necesario
                    Toast.makeText(this, "Función Próximamente", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_export_sales -> {
                    Toast.makeText(this, "Exportando...", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_undo_info -> {
                    AlertDialog.Builder(this)
                        .setTitle("¿Cómo anular una venta?")
                        .setMessage("Para anular una venta, ve al 'Historial de Ventas' desde este menú, busca la transacción y mantén presionado el item para ver la opción de eliminar.")
                        .setPositiveButton("Entendido", null).show()
                    true
                }
                R.id.menu_settings_venta -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // Búsqueda de Productos
        binding.autoVentaBusqueda.setOnFocusChangeListener { _, hasFocus ->
            updateSearchIcon(hasFocus)
        }

        binding.autoVentaBusqueda.addTextChangedListener { text ->
            val query = text.toString().trim()
            updateSearchIcon(binding.autoVentaBusqueda.hasFocus())

            if (query.length >= 1) {
                val matched = allProducts.find { p ->
                    p.codigo == query || p.nombre.equals(query, ignoreCase = true)
                }
                matched?.let { addToCart(it); binding.autoVentaBusqueda.setText("", false) }
            }
        }

        binding.autoVentaBusqueda.setOnItemClickListener { parent, _, position, _ ->
            val selection = parent.getItemAtPosition(position) as String
            val matched = allProducts.find { "${it.codigo} - ${it.nombre}" == selection || it.nombre == selection }
            matched?.let { addToCart(it) }
            binding.autoVentaBusqueda.setText("", false)
        }

        // Icono final (Cámara o Limpiar)
        binding.btnVoiceSearchVentas.setOnClickListener {
            voiceHelper.startListening { text ->
                binding.autoVentaBusqueda.setText(text)
                // Opcional: disparar bÃºsqueda inmediatamente
            }
        }

        binding.btnScanBarcodeVentas.setOnClickListener {
            if (binding.autoVentaBusqueda.isPopupShowing) binding.autoVentaBusqueda.dismissDropDown()
            startBarcodeScanner()
        }

        // Botones de Pago
        binding.btnPayCash.setOnClickListener { showComprobanteDialog("EFECTIVO") }
        binding.btnPayDigital.setOnClickListener { showComprobanteDialog("DIGITAL") }
        binding.btnPayCard.setOnClickListener { showComprobanteDialog("TARJETA") }

        binding.btnQuickAddManual.setOnClickListener {
            showManualAddDialog()
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch(Dispatchers.IO) {
            allProducts = database.productDao().allProducts
            val productNames = allProducts.map { "${it.codigo} - ${it.nombre}" }
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@VentasActivity, android.R.layout.simple_dropdown_item_1line, productNames)
                binding.autoVentaBusqueda.setAdapter(adapter)

                val scannedCode = intent.getStringExtra("EXTRA_SCANNED_CODE")
                if (scannedCode != null) {
                    intent.removeExtra("EXTRA_SCANNED_CODE")
                    val matched = allProducts.find { it.codigo?.split(",")?.contains(scannedCode) == true || it.codigo == scannedCode }
                    if (matched != null) {
                        addToCart(matched)
                    } else {
                        Toast.makeText(this@VentasActivity, "Producto no encontrado: $scannedCode", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun addToCart(product: ProductEntity) {
        if (product.stock <= 0) {
            Toast.makeText(this, "¡Producto agotado!", Toast.LENGTH_SHORT).show()
            return
        }

        // Buscar si ya está en el carrito
        val existingIndex = cartItems.indexOfFirst { it.productId == product.id }
        if (existingIndex != -1) {
            val item = cartItems[existingIndex]
            if (item.cantidad < product.stock) {
                item.cantidad++
                item.total = item.cantidad * item.precioVenta
                cartAdapter.notifyItemChanged(existingIndex)
            } else {
                Toast.makeText(this, "No hay más stock disponible", Toast.LENGTH_SHORT).show()
            }
        } else {
            val costU = if(product.stock > 0) product.precioCosto / product.stock else 0.0
            val newItem = SaleEntity(currentTransactionId, product.id, product.nombre, product.categoria, 1, product.precioVenta, costU, "EFECTIVO")
            cartItems.add(0, newItem)
            cartProducts.add(0, product)
            cartAdapter.notifyItemInserted(0)
            binding.rvCartItems.scrollToPosition(0)
        }
        
        updateTotalPrice()
        checkEmptyState()
    }

    private fun updateTotalPrice() {
        val total = cartItems.sumOf { it.total }
        binding.tvVentaTotalDinamico.text = String.format(Locale.getDefault(), "S/ %.2f", total)
    }

    private fun checkEmptyState() {
        binding.layoutEmptyCart.visibility = if (cartItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showComprobanteDialog(paymentMethod: String) {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "El carrito está vacío", Toast.LENGTH_SHORT).show()
            return
        }

        val total = cartItems.sumOf { it.total }
        val options = arrayOf("Nota de Venta (Interno)", "Boleta Electrónica", "Factura Electrónica")
        var selectedType = 0

        AlertDialog.Builder(this)
            .setTitle("Tipo de Comprobante")
            .setSingleChoiceItems(options, 0) { _, which -> selectedType = which }
            .setPositiveButton("Continuar") { _, _ ->
                when (selectedType) {
                    0 -> finalizeSaleWithDetails(paymentMethod, "NOTA_VENTA", "", "", "")
                    1 -> {
                        if (total > 700) showCustomerDataDialog(paymentMethod, "BOLETA", true)
                        else showCustomerDataDialog(paymentMethod, "BOLETA", false)
                    }
                    2 -> showCustomerDataDialog(paymentMethod, "FACTURA", true)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCustomerDataDialog(paymentMethod: String, docType: String, isMandatory: Boolean) {
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
            .setPositiveButton("Finalizar Venta") { _, _ ->
                val doc = etDoc.text.toString().trim()
                val name = etName.text.toString().trim()
                val address = etAddress.text.toString().trim()

                if (isMandatory && doc.isEmpty()) {
                    Toast.makeText(this, "El documento es obligatorio para este comprobante", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                finalizeSaleWithDetails(paymentMethod, docType, doc, name, address)
            }
            .setNegativeButton("Atrás", null)
            .show()
    }

    private fun finalizeSaleWithDetails(method: String, docType: String, cDoc: String, cName: String, cAddress: String) {
        val docPrefs = getSharedPreferences("DocumentPrefs", MODE_PRIVATE)
        val series = when(docType) {
            "BOLETA" -> "B001"
            "FACTURA" -> "F001"
            else -> "NV01"
        }
        val nextCorrelative = docPrefs.getInt("last_${docType.lowercase()}", 0) + 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalItems = cartItems.map { it }
                for (i in cartItems.indices) {
                    val sale = cartItems[i]
                    val product = cartProducts[i]
                    
                    sale.paymentMethod = method
                    sale.documentType = docType
                    sale.series = series
                    sale.correlative = nextCorrelative
                    sale.customerDoc = cDoc
                    sale.customerName = cName
                    sale.customerAddress = cAddress
                    sale.isSynced = false
                    
                    database.saleDao().insert(sale)
                    // ... (resto de logica de stock igual)

                    // REGISTRAR EN HISTORIAL
                    val log = MovementLogEntity(
                        type = "SALE",
                        title = "Venta Realizada",
                        description = "${sale.cantidad} x ${sale.nombreProducto}",
                        value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", sale.total)}",
                        colorHex = "#059669",
                        iconRes = android.R.drawable.ic_menu_add
                    )
                    database.movementLogDao().insert(log)
                    SyncManager(this@VentasActivity).syncLogToCloud(log)
                    
                    if (product != null) {
                        // Lógica de Inversión Dinámica:
                        // Calculamos el costo unitario del producto antes de descontar stock
                        val unitCost = if (product.stock > 0) product.precioCosto / product.stock else 0.0
                        
                        // Actualizamos el stock
                        product.stock = (product.stock - sale.cantidad).coerceAtLeast(0)
                        
                        // Actualizamos la inversión remanente basándonos en el nuevo stock
                        product.precioCosto = product.stock * unitCost
                        product.isSynced = false
                        
                        database.productDao().update(product)
                    }
                }
                // Incrementar correlativo
                docPrefs.edit().putInt("last_${docType.lowercase()}", nextCorrelative).apply()

                SyncManager(this@VentasActivity).scheduleOfflineSync()
                withContext(Dispatchers.Main) {
                    beep(ToneGenerator.TONE_PROP_BEEP2)
                    
                    // Enviar Notificación
                    val totalStr = String.format(Locale.getDefault(), "S/ %.2f", finalItems.sumOf { it.total })
                    val bizName = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_name", "Mi Negocio") ?: "Mi Negocio"
                    NotificationHelper.showSaleNotification(this@VentasActivity, totalStr, bizName)

                    showPrintDialog(finalItems)
                    resetSale()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showPrintDialog(items: List<SaleEntity>) {
        val options = arrayOf("Compartir Comprobante PDF (Legal)", "Imprimir Ticket Bluetooth (Rápido)", "Solo Cerrar")
        
        AlertDialog.Builder(this)
            .setTitle("Venta Exitosa")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sharePdfComprobante(items)
                    1 -> printBluetoothTicket(items)
                }
            }
            .show()
    }

    private fun sharePdfComprobante(items: List<SaleEntity>) {
        val pdfFile = ComprobantePdfGenerator(this).generateComprobantePdf(items)
        if (pdfFile != null && pdfFile.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar Comprobante"))
        } else {
            Toast.makeText(this, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun printBluetoothTicket(items: List<SaleEntity>) {
        val printerHelper = BluetoothPrinterHelper(this)
        val devices = printerHelper.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No hay impresoras vinculadas", Toast.LENGTH_SHORT).show()
        } else {
            val deviceNames = devices.map { it.name ?: "Desconocido" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Seleccionar Impresora")
                .setItems(deviceNames) { _, which ->
                    val selectedDevice = devices[which]
                    val receiptContent = generateReceiptText(items)
                    printerHelper.connectAndPrint(selectedDevice, receiptContent)
                }
                .show()
        }
    }

    private fun generateReceiptText(items: List<SaleEntity>): String {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val name = prefs.getString("business_name", "Mi Negocio")
        val phone = prefs.getString("business_phone", "")
        val currency = prefs.getString("currency_symbol", "S/")
        
        val sb = StringBuilder()
        sb.append("$name\n")
        if (phone?.isNotEmpty() == true) sb.append("Tel: $phone\n")
        sb.append("--------------------------------\n")
        items.forEach { 
            sb.append("${it.nombreProducto}\n")
            sb.append("${it.cantidad} x $currency${String.format("%.2f", it.precioVenta)} = $currency${String.format("%.2f", it.total)}\n")
        }
        sb.append("--------------------------------\n")
        val total = items.sumOf { it.total }
        sb.append("TOTAL: $currency${String.format("%.2f", total)}\n\n")
        sb.append("¡Gracias por su compra!\n\n\n")
        return sb.toString()
    }

    private fun resetSale() {
        cartItems.clear()
        cartProducts.clear()
        cartAdapter.notifyDataSetChanged()
        currentTransactionId = UUID.randomUUID().toString()
        updateTotalPrice()
        checkEmptyState()
        loadProducts() // Recargar para actualizar stock en memoria
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
                        Toast.makeText(this@VentasActivity, "DNI no encontrado", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val res = withContext(Dispatchers.IO) { com.naxor.app.network.RetrofitClient.api.buscarRuc(code, token) }
                    if (res.success && res.data != null) {
                        val bizName = res.data.razonSocial ?: res.data.razon_social ?: res.data.nombre_o_razon_social
                        val bizAddr = res.data.direccion ?: res.data.direccion_completa
                        etName.setText(bizName?.uppercase())
                        etAddress.setText(bizAddr?.uppercase())
                    } else {
                        Toast.makeText(this@VentasActivity, "RUC no encontrado o inválido", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Ventas", "Error en consulta: ${e.message}")
                Toast.makeText(this@VentasActivity, "Error de conexión con SUNAT/RENIEC", Toast.LENGTH_SHORT).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun updateSearchIcon(hasFocus: Boolean) {
        val hasText = binding.autoVentaBusqueda.text?.isNotEmpty() == true
        if (hasFocus || hasText) {
            binding.layoutVentaBusqueda.setEndIconDrawable(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            binding.layoutVentaBusqueda.setEndIconDrawable(android.R.drawable.ic_menu_camera)
        }
    }

    private fun startBarcodeScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                val matched = allProducts.find { it.codigo?.split(",")?.contains(code) == true || it.codigo == code }
                if (matched != null) {
                    addToCart(matched)
                    beep(ToneGenerator.TONE_PROP_BEEP)
                } else {
                    Toast.makeText(this, "Producto no encontrado: $code", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error del escáner: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
    }

    private fun beep(type: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(type, 200)
        } catch (e: Exception) {}
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.autoVentaBusqueda.clearFocus()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showManualAddDialog() {
        val etName = android.widget.EditText(this).apply { hint = "Nombre del producto" }
        val etPrice = android.widget.EditText(this).apply { hint = "Precio"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(etName)
            addView(etPrice)
        }

        AlertDialog.Builder(this)
            .setTitle("Venta Directa (Sin Inventario)")
            .setView(layout)
            .setPositiveButton("Añadir") { _, _ ->
                val name = etName.text.toString()
                val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
                if (name.isNotEmpty() && price > 0) {
                    val newItem = SaleEntity(currentTransactionId, null, name, "Varios", 1, price, 0.0, "EFECTIVO").apply { isSynced = false }
                    cartItems.add(0, newItem)
                    cartProducts.add(0, null)
                    cartAdapter.notifyItemInserted(0)
                    updateTotalPrice()
                    checkEmptyState()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.destroy()
    }
}
