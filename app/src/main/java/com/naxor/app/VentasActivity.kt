package com.naxor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.naxor.app.adapter.CartAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityVentasBinding
import com.naxor.app.databinding.ItemQuickActionBinding
import com.naxor.app.util.ComprobantePdfGenerator
import com.naxor.app.util.NotificationHelper
import com.naxor.app.util.VoiceRecognitionHelper
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class VentasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVentasBinding
    private lateinit var cartAdapter: CartAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var allProducts: List<ProductEntity> = emptyList()
    private val cartItems = mutableListOf<SaleEntity>()
    private val cartProducts = mutableListOf<ProductEntity?>()
    private var currentTransactionId = UUID.randomUUID().toString()
    private val voiceHelper by lazy { VoiceRecognitionHelper(this) }
    private lateinit var favoritesAdapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVentasBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Se requiere permiso de cámara para el escáner", Toast.LENGTH_LONG).show()
            }
        }
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)

        setupFavoritesRecyclerView()
        setupCartRecyclerView()
        setupListeners()
        loadProducts()
        loadFavorites()
        checkEmptyState()
    }

    private fun setupFavoritesRecyclerView() {
        favoritesAdapter = FavoritesAdapter { product -> addToCart(product) }
        binding.rvFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFavorites.adapter = favoritesAdapter
    }

    private fun loadFavorites() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sales = database.saleDao().allSales
            val topIds = sales.groupBy { it.productId }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(8)
                .map { it.first }
            
            val topProducts = database.productDao().allProducts.filter { topIds.contains(it.id) && !it.isDeleted }
            
            withContext(Dispatchers.Main) {
                if (topProducts.isNotEmpty()) {
                    binding.layoutFavorites.visibility = View.VISIBLE
                    favoritesAdapter.submitList(topProducts)
                } else {
                    binding.layoutFavorites.visibility = View.GONE
                }
            }
        }
    }

    private fun setupCartRecyclerView() {
        cartAdapter = CartAdapter(
            items = cartItems,
            products = cartProducts,
            onQtyChanged = { updateTotalPrice() },
            onRemove = { position -> removeCartItem(position) },
            onIncrease = { position -> increaseCartItem(position) },
            onDecrease = { position -> decreaseCartItem(position) }
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
                .setMessage("¿Deseas vender ${item.cantidad + 1} unidades de '${item.nombreProducto}'?")
                .setPositiveButton("Sí") { _, _ -> processIncrease(item, position) }
                .setNegativeButton("No", null).show()
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
        binding.btnOpenMenuVentas.setOnClickListener { binding.drawerLayoutVentas.openDrawer(GravityCompat.START) }
        binding.btnVoiceSearchVentas.setOnClickListener { voiceHelper.startListening { binding.autoVentaBusqueda.setText(it) } }
        binding.btnScanBarcodeVentas.setOnClickListener { startBarcodeScanner() }
        binding.btnQuickAddManual.setOnClickListener { showManualAddDialog() }
        binding.btnPayCash.setOnClickListener { showCashCalculatorDialog() }
        binding.btnPayDigital.setOnClickListener { showComprobanteDialog("DIGITAL") }
        binding.btnPayCard.setOnClickListener { showComprobanteDialog("TARJETA") }

        binding.autoVentaBusqueda.addTextChangedListener { updateSearchIcon(it.isNullOrEmpty()) }
        binding.autoVentaBusqueda.setOnItemClickListener { parent, _, position, _ ->
            val selection = parent.getItemAtPosition(position) as String
            allProducts.find { it.nombre == selection }?.let { addToCart(it) }
            binding.autoVentaBusqueda.setText("")
            hideKeyboard()
        }

        binding.navigationViewVentas.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutVentas.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_sales_history -> { startActivity(Intent(this, SalesHistoryActivity::class.java)); true }
                R.id.menu_clean_cart -> { resetSale(); true }
                else -> false
            }
        }
    }

    private fun showCashCalculatorDialog() {
        val totalVenta = cartItems.sumOf { it.total }
        val currency = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("currency_symbol", "S/")
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val tvTotal = TextView(this).apply {
            text = "Total a cobrar: $currency ${String.format("%.2f", totalVenta)}"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        val etMontoRecibido = EditText(this).apply {
            hint = "Monto recibido"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 24f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val tvVuelto = TextView(this).apply {
            text = "Vuelto: $currency 0.00"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, 30, 0, 0)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            visibility = View.GONE
        }
        etMontoRecibido.addTextChangedListener {
            val recibido = it.toString().toDoubleOrNull() ?: 0.0
            if (recibido >= totalVenta) {
                tvVuelto.text = "Vuelto: $currency ${String.format("%.2f", recibido - totalVenta)}"
                tvVuelto.visibility = View.VISIBLE
            } else tvVuelto.visibility = View.GONE
        }
        layout.addView(tvTotal); layout.addView(etMontoRecibido); layout.addView(tvVuelto)
        AlertDialog.Builder(this).setTitle("Pago en Efectivo").setView(layout)
            .setPositiveButton("Finalizar") { _, _ -> showComprobanteDialog("EFECTIVO") }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun loadProducts() {
        lifecycleScope.launch(Dispatchers.IO) {
            allProducts = database.productDao().allProducts
            val names = allProducts.map { it.nombre }
            withContext(Dispatchers.Main) {
                binding.autoVentaBusqueda.setAdapter(ArrayAdapter(this@VentasActivity, android.R.layout.simple_dropdown_item_1line, names))
            }
        }
    }

    private fun addToCart(product: ProductEntity) {
        val existingIndex = cartItems.indexOfFirst { it.productId == product.id }
        val currentQty = if (existingIndex != -1) cartItems[existingIndex].cantidad else 0
        if (currentQty + 1 > product.stock) {
            AlertDialog.Builder(this).setTitle("Stock Bajo").setMessage("¿Vender sin stock?")
                .setPositiveButton("Sí") { _, _ -> processAddToCart(product, existingIndex) }
                .setNegativeButton("No", null).show()
        } else processAddToCart(product, existingIndex)
    }

    private fun processAddToCart(product: ProductEntity, existingIndex: Int) {
        if (existingIndex != -1) {
            cartItems[existingIndex].cantidad++
            cartItems[existingIndex].total = cartItems[existingIndex].cantidad * cartItems[existingIndex].precioVenta
            cartAdapter.notifyItemChanged(existingIndex)
        } else {
            val costU = if (product.stock > 0) product.precioCosto / product.stock else 0.0
            val newItem = SaleEntity(currentTransactionId, product.id, product.nombre, product.categoria, 1, product.precioVenta, costU, "EFECTIVO")
            cartItems.add(0, newItem); cartProducts.add(0, product)
            cartAdapter.notifyItemInserted(0); binding.rvCartItems.scrollToPosition(0)
        }
        updateTotalPrice(); checkEmptyState(); beep(ToneGenerator.TONE_PROP_BEEP)
    }

    private fun updateTotalPrice() {
        val total = cartItems.sumOf { it.total }
        binding.tvVentaTotalDinamico.text = String.format("S/ %.2f", total)
    }

    private fun checkEmptyState() {
        binding.layoutEmptyCart.visibility = if (cartItems.isEmpty()) View.VISIBLE else View.GONE
        binding.paymentFooter.visibility = if (cartItems.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showComprobanteDialog(method: String) {
        AlertDialog.Builder(this).setTitle("Confirmar Venta").setMessage("¿Registrar venta?")
            .setPositiveButton("Sí") { _, _ -> finalizeSaleStep1(method) }
            .setNegativeButton("No", null).show()
    }

    private fun finalizeSaleStep1(method: String) {
        val loading = AlertDialog.Builder(this).setMessage("Registrando...").setCancelable(false).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val total = cartItems.sumOf { it.total }
                cartItems.forEachIndexed { i, sale ->
                    sale.paymentMethod = method; sale.isSynced = false
                    database.saleDao().insert(sale)
                    cartProducts[i]?.let { it.stock -= sale.cantidad; it.isSynced = false; database.productDao().update(it) }
                }
                val log = MovementLogEntity(type = "SALE", title = "Venta Realizada", description = "${cartItems.size} productos", value = "+ S/ ${String.format("%.2f", total)}", colorHex = "#059669", iconRes = android.R.drawable.ic_menu_add)
                database.movementLogDao().insert(log)
                SyncManager(this@VentasActivity).scheduleOfflineSync()
                withContext(Dispatchers.Main) { 
                    loading.dismiss(); resetSale(); beep(ToneGenerator.TONE_PROP_BEEP2)
                    Toast.makeText(this@VentasActivity, "Venta registrada", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { loading.dismiss(); Toast.makeText(this@VentasActivity, "Error", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun resetSale() {
        cartItems.clear(); cartProducts.clear(); cartAdapter.notifyDataSetChanged()
        currentTransactionId = UUID.randomUUID().toString(); updateTotalPrice(); checkEmptyState(); loadProducts()
    }

    private fun updateSearchIcon(hasFocus: Boolean) {
        binding.layoutVentaBusqueda.setEndIconDrawable(if (hasFocus) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_camera)
    }

    private fun startBarcodeScanner() {
        GmsBarcodeScanning.getClient(this).startScan().addOnSuccessListener { barcode ->
            val code = barcode.rawValue ?: ""
            allProducts.find { it.codigo?.split(",")?.contains(code) == true || it.codigo == code }?.let { addToCart(it) }
        }
    }

    private fun beep(type: Int) {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(type, 200) } catch (e: Exception) {}
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun showManualAddDialog() {
        val etN = EditText(this).apply { hint = "Nombre" }
        val etP = EditText(this).apply { hint = "Precio"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val lay = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(50, 20, 50, 10); addView(etN); addView(etP) }
        AlertDialog.Builder(this).setTitle("Venta Directa").setView(lay).setPositiveButton("Añadir") { _, _ ->
            val n = etN.text.toString(); val p = etP.text.toString().toDoubleOrNull() ?: 0.0
            if (n.isNotEmpty() && p > 0) {
                cartItems.add(0, SaleEntity(currentTransactionId, null, n, "Varios", 1, p, 0.0, "EFECTIVO"))
                cartProducts.add(0, null); cartAdapter.notifyItemInserted(0); updateTotalPrice(); checkEmptyState()
            }
        }.show()
    }

    override fun onDestroy() { super.onDestroy(); voiceHelper.destroy() }

    inner class FavoritesAdapter(private val onAdd: (ProductEntity) -> Unit) : RecyclerView.Adapter<FavoritesAdapter.VH>() {
        private var items = listOf<ProductEntity>()
        fun submitList(l: List<ProductEntity>) { items = l; notifyDataSetChanged() }
        inner class VH(val b: ItemQuickActionBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemQuickActionBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val item = items[p]
            h.b.tvActionName.text = item.nombre
            h.b.tvActionName.setTextColor(Color.parseColor("#4C1D95"))
            h.b.cardQuickAction.setCardBackgroundColor(Color.parseColor("#F5F3FF"))
            h.b.tvActionIcon.text = if (item.nombre.isNotEmpty()) item.nombre[0].uppercase() else "⭐"
            h.b.root.setOnClickListener { onAdd(item) }
        }
        override fun getItemCount() = items.size
    }
}
