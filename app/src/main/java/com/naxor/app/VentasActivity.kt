package com.naxor.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
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
import com.naxor.app.data.ProductEntity
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityVentasBinding
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
            binding.drawerLayoutVentas.openDrawer(GravityCompat.END)
        }

        binding.btnViewHistory.setOnClickListener {
            startActivity(Intent(this, SalesHistoryActivity::class.java))
        }

        // Búsqueda de Productos
        binding.autoVentaBusqueda.addTextChangedListener { text ->
            val query = text.toString().trim()
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

        // Escáner
        binding.layoutVentaBusqueda.setEndIconOnClickListener {
            val scanner = GmsBarcodeScanning.getClient(this)
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

        // Botones de Pago
        binding.btnPayCash.setOnClickListener { confirmSale("EFECTIVO") }
        binding.btnPayDigital.setOnClickListener { confirmSale("DIGITAL") }
        binding.btnPayCard.setOnClickListener { confirmSale("TARJETA") }

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

    private fun confirmSale(method: String) {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "El carrito está vacío", Toast.LENGTH_SHORT).show()
            return
        }

        val total = cartItems.sumOf { it.total }
        AlertDialog.Builder(this)
            .setTitle("Confirmar Venta")
            .setMessage("Total: S/ ${String.format("%.2f", total)}\nMétodo: $method\n\n¿Deseas finalizar la venta?")
            .setPositiveButton("Sí, Vender") { _, _ ->
                finalizeSale(method)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun finalizeSale(method: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (i in cartItems.indices) {
                    val sale = cartItems[i]
                    val product = cartProducts[i]
                    sale.paymentMethod = method
                    sale.isSynced = false
                    database.saleDao().insert(sale)
                    
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
                SyncManager(this@VentasActivity).scheduleOfflineSync()
                withContext(Dispatchers.Main) {
                    beep(ToneGenerator.TONE_PROP_BEEP2)
                    showPrintDialog(cartItems.toList())
                    resetSale()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showPrintDialog(items: List<SaleEntity>) {
        AlertDialog.Builder(this)
            .setTitle("Venta Exitosa")
            .setMessage("¿Deseas imprimir el ticket de venta?")
            .setPositiveButton("Imprimir") { _, _ ->
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
            .setNegativeButton("Cerrar", null)
            .show()
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

    private fun beep(type: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(type, 200)
        } catch (e: Exception) {}
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
}
