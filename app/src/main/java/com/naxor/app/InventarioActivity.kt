package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.naxor.app.adapter.ProductAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityInventarioBinding
import com.naxor.app.databinding.DialogViewLabelBinding
import com.naxor.app.util.VoiceRecognitionHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.widget.addTextChangedListener

class InventarioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventarioBinding
    private val adapter by lazy { 
        ProductAdapter(
            onEdit = { product -> 
                val intent = Intent(this, AddProductActivity::class.java)
                intent.putExtra("PRODUCT_ID", product.id)
                startActivity(intent)
            },
            onViewLabel = { product -> showProductLabel(product) },
            onStockQuickChange = { product, delta -> 
                handleStockQuickChange(product, delta)
            },
            onSelectionChanged = { count -> updateMultiSelectUI(count) }
        )
    }
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentSortAttribute: String = "NOMBRE"
    private var isAscending: Boolean = true
    private var currentCategory: String = "Todos"
    private var currentSearchQuery: String = ""
    private var filterLowStock: Boolean = false
    private var businessCurrency: String = "S/"
    private var isEditorMode: Boolean = false
    private var isMultiSelectMode: Boolean = false
    private val voiceHelper by lazy { VoiceRecognitionHelper(this) }
    private var syncStatusText: String = "Sincronizado"
    private var lastUnsyncedCount: Int = 0
    private var isNetworkAvailable: Boolean = true
    private var currentIsSyncing: Boolean = false
    private var loadJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventarioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSyncIndicator()
        setupRecyclerView()
        setupListeners()

        SyncManager(this).startRealtimeInventorySync { 
            loadProducts()
        }
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    private fun setupRecyclerView() {
        binding.rvInventario.layoutManager = LinearLayoutManager(this)
        binding.rvInventario.adapter = adapter
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
        updateSyncIconState()
    }

    private fun updateSyncIconState(showDialog: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val unsynced = database.productDao().allProducts.count { !it.isSynced && !it.isDeleted }
            lastUnsyncedCount = unsynced
            
            withContext(Dispatchers.Main) {
                val networkStatus = if (isNetworkAvailable) "Conectado" else "Sin internet"
                syncStatusText = if (unsynced > 0) "Sincronizando ($unsynced pendientes)" else "Todo al día"
                
                if (unsynced > 0) {
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.stat_notify_sync)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                } else {
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.stat_sys_download_done)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }

                if (showDialog) {
                    AlertDialog.Builder(this@InventarioActivity)
                        .setTitle("Estado de Sincronización")
                        .setMessage("Internet: $networkStatus\nEstado: $syncStatusText")
                        .setPositiveButton("Sincronizar ahora") { _, _ -> forceFullSync() }
                        .setNeutralButton("Probar conexión") { _, _ -> testFirebaseWrite() }
                        .setNegativeButton("Cerrar", null).show()
                }
            }
        }
    }

    private fun testFirebaseWrite() {
        Toast.makeText(this, "Probando conexión...", Toast.LENGTH_SHORT).show()
        SyncManager(this).syncBusinessSettingsToCloud()
    }

    private fun forceFullSync() {
        Toast.makeText(this, "Iniciando sincronización total...", Toast.LENGTH_SHORT).show()
        SyncManager(this).scheduleOfflineSync()
        loadProducts()
    }

    private fun setupListeners() {
        binding.btnBackInventario.setOnClickListener { finish() }
        binding.btnHelpInventario.setOnClickListener { showHelpDialog() }
        binding.btnSyncIndicator.setOnClickListener { updateSyncIconState(true) }
        
        binding.btnVoiceSearch.setOnClickListener {
            voiceHelper.startListening { text ->
                binding.etSearchInventario.setText(text)
                currentSearchQuery = text
                loadProducts()
            }
        }

        binding.fabAddProducto.setOnClickListener { 
            startActivity(Intent(this, AddProductActivity::class.java))
        }
        binding.fabToggleEditor.setOnClickListener { toggleEditorMode(!isEditorMode) }
        binding.btnExitEditorMode.setOnClickListener { 
            if (isMultiSelectMode) toggleMultiSelectMode(false)
            else toggleEditorMode(false) 
        }

        binding.etSearchInventario.addTextChangedListener { 
            currentSearchQuery = it.toString()
            loadProducts() 
            val isExpanded = currentSearchQuery.isNotEmpty() || binding.etSearchInventario.hasFocus()
            binding.btnSearchClear.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearchInventario.setText("")
            binding.etSearchInventario.clearFocus()
            hideKeyboard()
        }

        binding.chipFilterAll.setOnClickListener {
            filterLowStock = false
            loadProducts()
        }

        binding.chipFilterLowStock.setOnClickListener {
            filterLowStock = true
            loadProducts()
        }

        binding.btnFilterCategory.setOnClickListener { showCategorySelector() }

        binding.navigationViewInventario.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_export_excel -> { Toast.makeText(this, "Exportando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_import_excel -> { Toast.makeText(this, "Importando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_multi_delete -> { toggleMultiSelectMode(true); true }
                R.id.menu_sort_options -> { showSortAttributeSelector(); true }
                R.id.menu_settings_inventario -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }.also { binding.drawerLayoutInventario.closeDrawer(GravityCompat.START) }
        }
    }

    private fun toggleEditorMode(enabled: Boolean) {
        isEditorMode = enabled
        adapter.setEditorMode(enabled)
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.fabToggleEditor.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun toggleMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        adapter.setMultiSelectMode(enabled)
        
        if (enabled) {
            binding.cardEditorBanner.visibility = View.VISIBLE
            binding.btnExitEditorMode.text = "CANCELAR"
            binding.cardEditorBanner.setCardBackgroundColor(getColor(R.color.red_700))
            val tv = binding.cardEditorBanner.findViewById<TextView>(android.R.id.text1) ?: 
                     binding.cardEditorBanner.getChildAt(0).let { if(it is android.view.ViewGroup) it.getChildAt(1) as? TextView else null }
            tv?.text = "0 productos seleccionados"
            tv?.setTextColor(Color.WHITE)
            
            // Reemplazar botón de ayuda por borrar si está en modo multiselección
            binding.btnHelpInventario.setImageResource(android.R.drawable.ic_menu_delete)
            binding.btnHelpInventario.setOnClickListener { showMultiDeleteConfirmation() }
        } else {
            toggleEditorMode(false)
            binding.btnExitEditorMode.text = "SALIR"
            binding.cardEditorBanner.setCardBackgroundColor(getColor(R.color.vibrant_purple_light))
            binding.btnHelpInventario.setImageResource(android.R.drawable.ic_menu_help)
            binding.btnHelpInventario.setOnClickListener { showHelpDialog() }
        }
    }

    private fun updateMultiSelectUI(count: Int) {
        if (count == -1) { 
            toggleMultiSelectMode(true)
            return
        }
        if (isMultiSelectMode && count == 0) {
            toggleMultiSelectMode(false)
            return
        }
        val tv = binding.cardEditorBanner.findViewById<TextView>(android.R.id.text1) ?: 
                 binding.cardEditorBanner.getChildAt(0).let { if(it is android.view.ViewGroup) it.getChildAt(1) as? TextView else null }
        tv?.text = "$count productos seleccionados"
    }

    private fun loadProducts() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        businessCurrency = prefs.getString("currency_symbol", "S/") ?: "S/"
        
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val search = if (currentSearchQuery.isBlank()) "%%" else "%$currentSearchQuery%"
                    var dbList = database.productDao().getFilteredAndSorted(search, currentCategory)
                    
                    val totalInvValue = dbList.sumOf { it.stock * it.precioCosto }
                    val lowStockItems = dbList.count { it.stock <= 5 && !it.isDeleted }
                    val totalProducts = dbList.count { !it.isDeleted }

                    if (filterLowStock) {
                        dbList = dbList.filter { it.stock <= 5 && !it.isDeleted }
                    }

                    val sortedList = when (currentSortAttribute) {
                        "STOCK" -> if (isAscending) dbList.sortedBy { it.stock } else dbList.sortedByDescending { it.stock }
                        "PRECIO" -> if (isAscending) dbList.sortedBy { it.precioVenta } else dbList.sortedByDescending { it.precioVenta }
                        else -> if (isAscending) {
                            dbList.sortedBy { it.nombre?.lowercase()?.trim() ?: "" }
                        } else {
                            dbList.sortedByDescending { it.nombre?.lowercase()?.trim() ?: "" }
                        }
                    }
                    
                    Triple(sortedList, totalInvValue, Pair(lowStockItems, totalProducts))
                }
                
                if (!isActive) return@launch

                adapter.updateList(result.first)
                updateStatsUI(result.second, result.third.first, result.third.second)
                updateSyncIconState()
            } catch (e: Exception) {
                android.util.Log.e("INVENTARIO", "Error cargando productos", e)
            }
        }
    }

    private fun updateStatsUI(totalValue: Double, lowStock: Int, totalProducts: Int) {
        binding.tvTotalInventoryValue.text = String.format(Locale.getDefault(), "$businessCurrency %.2f", totalValue)
        binding.tvLowStockCount.text = "$lowStock Stock Bajo"
        binding.tvTotalProductsCount.text = "$totalProducts productos en catálogo"
        binding.cardLowStockAlert.setCardBackgroundColor(
            if (lowStock > 0) getColor(R.color.red_700) else getColor(R.color.emerald_600)
        )
    }

    private fun handleStockQuickChange(product: ProductEntity, delta: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            product.stock += delta
            if (product.stock < 0) product.stock = 0
            product.isSynced = false
            database.productDao().update(product)
            SyncManager(this@InventarioActivity).syncProductToCloud(product)
            
            withContext(Dispatchers.Main) {
                loadProducts()
                Toast.makeText(this@InventarioActivity, "${product.nombre}: ${product.stock}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMultiDeleteConfirmation() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        
        AlertDialog.Builder(this)
            .setTitle("Eliminar Varios")
            .setMessage("¿Borrar ${selected.size} productos seleccionados?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val sm = SyncManager(this@InventarioActivity)
                    val count = selected.size
                    val names = selected.take(3).joinToString { it.nombre } + (if (count > 3) "..." else "")
                    
                    selected.forEach { p ->
                        p.isDeleted = true
                        p.isSynced = false
                        database.productDao().update(p)
                        sm.deleteProductFromCloud(p.id)
                    }

                    val log = MovementLogEntity(
                        type = "PRODUCT_DELETED",
                        title = "Eliminación Múltiple",
                        description = "Se eliminaron $count productos: $names",
                        value = "BORRADO",
                        colorHex = "#E11D48",
                        iconRes = android.R.drawable.ic_menu_delete
                    )
                    database.movementLogDao().insert(log)
                    
                    withContext(Dispatchers.Main) {
                        toggleMultiSelectMode(false)
                        loadProducts()
                    }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showCategorySelector() {
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) {
                val list = database.productDao().uniqueCategories.toMutableList()
                if (!list.contains("Todos")) list.add(0, "Todos")
                list.toTypedArray()
            }
            AlertDialog.Builder(this@InventarioActivity)
                .setTitle("Seleccionar Categoría")
                .setItems(categories) { _, which ->
                    currentCategory = categories[which]
                    binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory
                    loadProducts()
                }.show()
        }
    }

    private fun showSortAttributeSelector() {
        val options = arrayOf("Nombre", "Stock", "Precio")
        val values = arrayOf("NOMBRE", "STOCK", "PRECIO")
        AlertDialog.Builder(this)
            .setTitle("Ordenar por")
            .setItems(options) { _, which ->
                currentSortAttribute = values[which]
                loadProducts()
            }.show()
    }

    private fun showHelpDialog() {
        val helpMessage = """
            📦 INVENTARIO INTELIGENTE
            • Valor Total: Es el dinero que tienes en mercancía (Costo x Stock).
            • Stock Bajo: Productos con menos de 5 unidades.
            • Edición Rápida: Usa los botones +/- para cambiar el stock sin entrar a editar.
            • Utilidad: El margen de ganancia calculado para cada producto.
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("Guía de Inventario").setMessage(helpMessage).setPositiveButton("Entendido", null).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchInventario.windowToken, 0)
    }

    private fun showProductLabel(p: ProductEntity) {
        val db = DialogViewLabelBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        db.tvLabelProdName.text = p.nombre
        db.tvLabelProdDesc.text = p.descripcion ?: "Sin descripción."

        if (!p.photoPath.isNullOrEmpty()) {
            try {
                val uri = if (p.photoPath!!.startsWith("/")) Uri.fromFile(File(p.photoPath!!)) else Uri.parse(p.photoPath!!)
                db.ivLabelProdPhoto.setImageURI(uri)
                db.ivLabelProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                db.ivLabelProdPhoto.alpha = 1.0f
            } catch (e: Exception) {}
        }

        val content = if (!p.codigo.isNullOrBlank()) p.codigo!!.split(",")[0] else p.nombre
        var currentBitmap: Bitmap? = null

        fun generateCode(format: BarcodeFormat, width: Int, height: Int): Bitmap? {
            return try {
                val bitMatrix = MultiFormatWriter().encode(content, format, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                for (x in 0 until width) for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
                bitmap
            } catch (e: Exception) { null }
        }

        val qrBitmap = generateCode(BarcodeFormat.QR_CODE, 500, 500)
        val barBitmap = generateCode(BarcodeFormat.CODE_128, 800, 300)

        db.ivProductCode.setImageBitmap(qrBitmap)
        db.ivProductBarcode.setImageBitmap(barBitmap)
        currentBitmap = qrBitmap

        db.chipGroupCodeType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipShowQR)) {
                db.ivProductCode.visibility = View.VISIBLE
                db.ivProductBarcode.visibility = View.GONE
                currentBitmap = qrBitmap
            } else {
                db.ivProductCode.visibility = View.GONE
                db.ivProductBarcode.visibility = View.VISIBLE
                currentBitmap = barBitmap
            }
        }

        db.btnShareLabel.setOnClickListener { currentBitmap?.let { shareLabelBitmap(it, p.nombre) } }
        db.tvLabelProdCode.text = if (!p.codigo.isNullOrBlank()) p.codigo!!.replace(",", "\n• ") else "---"
        db.btnCloseLabel.setOnClickListener { d.dismiss() }
        d.show()
        d.window?.let {
            it.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun shareLabelBitmap(bitmap: Bitmap, name: String) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Ficha_$name.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir Ficha"))
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.destroy()
    }
}
