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
        checkEmptyState()
    }

    private fun setupCartRecyclerView() {
        cartAdapter = CartAdapter(
            items = cartItems,
            products = cartProducts,
            onQtyChanged = { updateTotalPrice() },
            onRemove = { position ->
                removeCartItem(position)
            },
            onIncrease = { position ->
                increaseCartItem(position)
            },
            onDecrease = { position ->
                decreaseCartItem(position)
            }
        )
        binding.rvCartItems.layoutManager = LinearLayoutManager(this)
        binding.rvCartItems.adapter = cartAdapter
    }

    private fun removeCartItem(position: Int) {
        if (position >= 0 && position < cartItems.size) {
            cartItems.removeAt(position)
            cartProducts.removeAt(position)
            cartAdapter.notifyItemRemoved(position)
            updateTotalPrice()
            checkEmptyState()
        }
    }

    private fun increaseCartItem(position: Int) {
        if (position < 0 || position >= cartItems.size) return
        
        val item = cartItems[position]
        val product = cartProducts[position]
        
        if (product != null && item.cantidad + 1 > product.stock) {
            AlertDialog.Builder(this)
                .setTitle("Stock Insuficiente")
                .setMessage("¿Deseas vender ${item.cantidad + 1} unidades de '${item.nombreProducto}' aunque solo queden ${product.stock} en stock?")
                .setPositiveButton("Sí, Continuar") { _, _ ->
                    processIncrease(item, position)
                }
                .setNegativeButton("No, Cancelar", null)
                .show()
        } else {
            processIncrease(item, position)
        }
    }

    private fun processIncrease(item: SaleEntity, position: Int) {
        item.cantidad++
        item.total = item.cantidad * item.precioVenta
        cartAdapter.notifyItemChanged(position)
        updateTotalPrice()
    }

    private fun decreaseCartItem(position: Int) {
        if (position < 0 || position >= cartItems.size) return
        val item = cartItems[position]
        if (item.cantidad > 1) {
            item.cantidad--
            item.total = item.cantidad * item.precioVenta
            cartAdapter.notifyItemChanged(position)
            updateTotalPrice()
        } else {
            removeCartItem(position)
        }
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
            // Ocultar el footer de pago al buscar para dar más espacio
            if (hasFocus) {
                binding.paymentFooter.visibility = View.GONE
            } else {
                if (cartItems.isNotEmpty()) {
                    binding.paymentFooter.visibility = View.VISIBLE
                }
            }
        }

        binding.autoVentaBusqueda.addTextChangedListener { text ->
            val query = text.toString().trim()
            updateSearchIcon(binding.autoVentaBusqueda.hasFocus())

            if (query.isNotEmpty()) {
                binding.paymentFooter.visibility = View.GONE
            }
        }

        binding.autoVentaBusqueda.setOnItemClickListener { parent, _, position, _ ->
            val selection = parent.getItemAtPosition(position) as String
            val matched = allProducts.find { it.nombre == selection }
            matched?.let { addToCart(it) }
            binding.autoVentaBusqueda.setText("", false)
            binding.autoVentaBusqueda.clearFocus()
            hideKeyboard()
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
            val productNames = allProducts.map { it.nombre }
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
        // Buscar si ya está en el carrito para calcular la cantidad total solicitada
        val existingIndex = cartItems.indexOfFirst { it.productId == product.id }
        val currentQtyInCart = if (existingIndex != -1) cartItems[existingIndex].cantidad else 0
        val requestedQty = currentQtyInCart + 1

        if (requestedQty > product.stock) {
            // Advertencia de falta de stock
            AlertDialog.Builder(this)
                .setTitle("Stock Insuficiente")
                .setMessage("Estás intentando vender ${requestedQty} unidades de '${product.nombre}', pero solo quedan ${product.stock} en stock. ¿Deseas registrar la venta de todas formas?")
                .setPositiveButton("Sí, Continuar") { _, _ ->
                    processAddToCart(product, existingIndex)
                }
                .setNegativeButton("No, Cancelar", null)
                .show()
        } else {
            processAddToCart(product, existingIndex)
        }
    }

    private fun processAddToCart(product: ProductEntity, existingIndex: Int) {
        if (existingIndex != -1) {
            val item = cartItems[existingIndex]
            item.cantidad++
            item.total = item.cantidad * item.precioVenta
            cartAdapter.notifyItemChanged(existingIndex)
        } else {
            val costU = if (product.stock > 0) product.precioCosto / product.stock else 0.0
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
        val isEmpty = cartItems.isEmpty()
        binding.layoutEmptyCart.visibility = if (isEmpty) View.VISIBLE else View.GONE
        
        // El footer solo aparece si hay items Y NO se está buscando
        if (!isEmpty && !binding.autoVentaBusqueda.hasFocus()) {
            binding.paymentFooter.visibility = View.VISIBLE
        } else {
            binding.paymentFooter.visibility = View.GONE
        }
    }

    private fun showComprobanteDialog(paymentMethod: String) {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "El carrito está vacío", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Confirmar registro de venta inmediatamente
        AlertDialog.Builder(this)
            .setTitle("Confirmar Venta")
            .setMessage("¿Deseas registrar esta venta de ${binding.tvVentaTotalDinamico.text}?")
            .setPositiveButton("Registrar") { _, _ ->
                finalizeSaleStep1(paymentMethod)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun finalizeSaleStep1(paymentMethod: String) {
        // Registrar la venta en la base de datos (por ahora con tipo NOTA_VENTA)
        // Luego preguntaremos por Boleta/Factura
        finalizeSaleWithDetails(paymentMethod, "NOTA_VENTA", "", "", "", "")
    }

    private fun finalizeSaleWithDetails(method: String, docType: String, cDoc: String, cName: String, cAddress: String, cPhone: String) {
        if (cartItems.isEmpty()) return

        val loadingDialog = AlertDialog.Builder(this)
            .setMessage("Registrando venta...")
            .setCancelable(false)
            .show()

        val docPrefs = getSharedPreferences("DocumentPrefs", MODE_PRIVATE)
        val series = when(docType) {
            "BOLETA" -> "B001"
            "FACTURA" -> "F001"
            else -> "NV01"
        }
        val nextCorrelative = docPrefs.getInt("last_${docType.lowercase()}", 0) + 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalItems = ArrayList(cartItems)
                val totalVenta = finalItems.sumOf { it.total }
                
                for (i in finalItems.indices) {
                    val sale = finalItems[i]
                    val product = cartProducts.getOrNull(i)
                    
                    sale.paymentMethod = method
                    sale.documentType = docType
                    sale.series = series
                    sale.correlative = nextCorrelative
                    sale.customerDoc = cDoc
                    sale.customerName = cName
                    sale.customerAddress = cAddress
                    sale.isSynced = false
                    
                    database.saleDao().insert(sale)
                    
                    if (product != null) {
                        product.stock = (product.stock - sale.cantidad).coerceAtLeast(0)
                        product.isSynced = false
                        database.productDao().update(product)
                    }
                }

                val log = MovementLogEntity(
                    type = "SALE",
                    title = "Venta Realizada",
                    description = if(finalItems.size == 1) finalItems[0].nombreProducto else "${finalItems.size} productos",
                    value = "+ S/ ${String.format(Locale.getDefault(), "%.2f", totalVenta)}",
                    colorHex = "#059669",
                    iconRes = android.R.drawable.ic_menu_add
                )
                database.movementLogDao().insert(log)
                SyncManager(this@VentasActivity).syncLogToCloud(log)

                docPrefs.edit().putInt("last_${docType.lowercase()}", nextCorrelative).apply()
                SyncManager(this@VentasActivity).scheduleOfflineSync()

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    beep(ToneGenerator.TONE_PROP_BEEP2)
                    
                    val totalStr = String.format(Locale.getDefault(), "S/ %.2f", totalVenta)
                    val bizName = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_name", "Mi Negocio") ?: "Mi Negocio"
                    NotificationHelper.showSaleNotification(this@VentasActivity, totalStr, bizName)

                    // 2. UNA VEZ REGISTRADO, PREGUNTAR POR COMPROBANTE PARA ENVIAR
                    showPostSaleReceiptDialog(finalItems)
                    resetSale()
                }
            } catch (e: Exception) {
                Log.e("Ventas", "Error al finalizar venta: ${e.message}")
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@VentasActivity, "Error al procesar venta: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPostSaleReceiptDialog(items: List<SaleEntity>) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Venta Exitosa ✅")
            .setMessage("¿Deseas emitir un comprobante para el cliente?")
            .setPositiveButton("Boleta / Factura") { _, _ ->
                showReceiptTypeDialog(items)
            }
            .setNegativeButton("Solo Cerrar") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()

        dialog.show()
    }

    private fun showReceiptTypeDialog(items: List<SaleEntity>) {
        val options = arrayOf("📄 Boleta de Venta", "🏢 Factura Electrónica")
        AlertDialog.Builder(this)
            .setTitle("Tipo de Comprobante")
            .setItems(options) { _, which ->
                val type = if (which == 0) "BOLETA" else "FACTURA"
                showCustomerDataDialog(items, type, which == 1)
            }
            .setNegativeButton("Atrás", null)
            .show()
    }

    private fun showCustomerDataDialog(items: List<SaleEntity>, docType: String, isMandatory: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_customer_data, null)
        val etDoc = view.findViewById<android.widget.EditText>(R.id.etCustomerDoc)
        val etName = view.findViewById<android.widget.EditText>(R.id.etCustomerName)
        val etPhone = view.findViewById<android.widget.EditText>(R.id.etCustomerPhone)
        val etAddress = view.findViewById<android.widget.EditText>(R.id.etCustomerAddress)
        val progress = view.findViewById<android.view.View>(R.id.progressDocSearch)
        
        val layoutDoc = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomerDoc)
        val layoutName = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomerName)
        val layoutAddress = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutCustomerAddress)

        layoutDoc.hint = if (docType == "FACTURA") "RUC del Cliente" else "DNI (Opcional)"
        layoutName.hint = if (docType == "FACTURA") "Razón Social" else "Nombre (Opcional)"
        layoutAddress.visibility = if (docType == "FACTURA") View.VISIBLE else View.GONE

        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val apiToken = prefs.getString("api_token", "") ?: ""

        val consultaAction = {
            val code = etDoc.text.toString().trim()
            if (code.length == 8 || code.length == 11) {
                if (apiToken.isNotEmpty()) ejecutarConsultaDocumento(code, apiToken, etName, etAddress, progress)
            }
        }
        etDoc.addTextChangedListener { text -> if (text?.length == 8 || text?.length == 11) consultaAction() }
        layoutDoc.setEndIconOnClickListener { consultaAction() }

        AlertDialog.Builder(this)
            .setTitle("Datos del Cliente")
            .setView(view)
            .setPositiveButton("Generar") { _, _ ->
                val phone = etPhone.text.toString().trim()
                items.forEach { 
                    it.customerDoc = etDoc.text.toString()
                    it.customerName = etName.text.toString()
                    it.customerAddress = etAddress.text.toString()
                    it.documentType = docType
                }
                showPrintDialog(items, phone)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPrintDialog(items: List<SaleEntity>, customerPhone: String) {
        val options = arrayOf("Compartir Comprobante PDF (WhatsApp)", "Imprimir Ticket Bluetooth", "Solo Cerrar")
        
        AlertDialog.Builder(this)
            .setTitle("Venta Exitosa")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sharePdfComprobante(items, customerPhone)
                    1 -> printBluetoothTicket(items)
                }
            }
            .show()
    }

    private fun sharePdfComprobante(items: List<SaleEntity>, customerPhone: String) {
        val pdfFile = ComprobantePdfGenerator(this).generateComprobantePdf(items)
        if (pdfFile != null && pdfFile.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (customerPhone.isNotEmpty()) {
                // Intentar abrir WhatsApp directamente al número del cliente
                val phoneClean = customerPhone.replace("[^0-9]".toRegex(), "")
                val whatsappNum = if (phoneClean.length == 9) "51$phoneClean" else phoneClean
                
                try {
                    // Para enviar a un número específico de WhatsApp con archivo adjunto
                    shareIntent.`package` = "com.whatsapp"
                    shareIntent.putExtra("jid", "$whatsappNum@s.whatsapp.net")
                    startActivity(shareIntent)
                } catch (e: Exception) {
                    startActivity(Intent.createChooser(shareIntent, "Enviar Comprobante"))
                }
            } else {
                startActivity(Intent.createChooser(shareIntent, "Enviar Comprobante"))
            }
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
