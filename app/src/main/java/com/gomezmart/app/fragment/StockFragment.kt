package com.gomezmart.app.fragment

import android.app.AlertDialog
import android.content.Context
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
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.ListenerRegistration
import com.bumptech.glide.Glide
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.gomezmart.app.*
import com.gomezmart.app.adapter.ProductAdapter
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.data.ProductEntity
import com.gomezmart.app.databinding.ActivityInventarioBinding
import com.gomezmart.app.databinding.DialogViewLabelBinding
import com.gomezmart.app.util.VoiceRecognitionHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class StockFragment : Fragment() {

    private var _binding: ActivityInventarioBinding? = null
    private val binding get() = _binding!!
    
    private val adapter by lazy { 
        ProductAdapter(
            onEdit = { product -> 
                val intent = Intent(requireContext(), AddProductActivity::class.java)
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
    
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private var loadJob: Job? = null
    private var lastLoadedList: List<ProductEntity> = emptyList()
    private var currentSortAttribute: String = "NOMBRE"
    private var isAscending: Boolean = true
    private var currentCategory: String = "Todos"
    private var currentSearchQuery: String = ""
    private var filterLowStock: Boolean = false
    private var isMultiSelectMode: Boolean = false
    private val voiceHelper by lazy { context?.let { VoiceRecognitionHelper(it) } }
    private var syncStatusText: String = "Sincronizado"
    private var lastUnsyncedCount: Int = 0
    private var isNetworkAvailable: Boolean = true
    private var inventoryRegistration: ListenerRegistration? = null

    fun openDrawer() {
        if (_binding != null) {
            binding.drawerLayoutInventario.openDrawer(GravityCompat.START)
        }
    }

    override fun onStart() {
        super.onStart()
        loadProducts()
        if (inventoryRegistration == null) {
            inventoryRegistration = SyncManager(requireContext()).startRealtimeInventorySync { 
                loadProducts()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        inventoryRegistration?.remove()
        inventoryRegistration = null
        loadJob?.cancel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityInventarioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Manejar insets para que la cabecera no sea tapada por la barra de estado y el FAB
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.layoutStockHeader, applyTop = true, applyBottom = false)
        base?.applyWindowInsetsMargins(binding.fabAddProducto, applyTop = false, applyBottom = true)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerScrollViewInventario) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, statusBars.top, 0, navBars.bottom)
            insets
        }

        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        adapter.setBusinessType(businessType)

        setupSyncIndicator()
        setupRecyclerView()
        setupListeners()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadProducts()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            loadProducts()
        }
    }

    private fun setupRecyclerView() {
        binding.rvInventario.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StockFragment.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }
    }

    private fun setupSyncIndicator() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                activity?.runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                activity?.runOnUiThread { updateSyncIconState() }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback)
        updateSyncIconState()
    }

    private fun updateSyncIconState(showDialog: Boolean = false) {
        val dbInstance = database ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val unsynced = dbInstance.productDao().allProducts.count { !it.isSynced && !it.isDeleted }
            lastUnsyncedCount = unsynced
            
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
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
                    AlertDialog.Builder(requireContext())
                        .setTitle("Estado de Sincronización")
                        .setMessage("Internet: $networkStatus\nEstado: $syncStatusText")
                        .setPositiveButton("Sincronizar ahora") { _, _ -> forceFullSync() }
                        .setNegativeButton("Cerrar", null).show()
                }
            }
        }
    }

    private fun forceFullSync() {
        Toast.makeText(requireContext(), "Iniciando sincronización total...", Toast.LENGTH_SHORT).show()
        SyncManager(requireContext()).scheduleOfflineSync()
        loadProducts()
    }

    private fun setupListeners() {
        binding.btnBackInventario.visibility = View.GONE
        binding.btnHelpInventario.setOnClickListener { showHelpDialog() }
        binding.btnSyncIndicator.setOnClickListener { updateSyncIconState(true) }
        
        binding.btnOpenFilters.setOnClickListener { 
            binding.drawerLayoutInventario.openDrawer(GravityCompat.START)
        }

        binding.btnVoiceSearch.setOnClickListener {
            voiceHelper?.startListening { text ->
                binding.etSearchInventario.setText(text)
                currentSearchQuery = text
                loadProducts()
            }
        }

        binding.fabAddProducto.setOnClickListener { 
            startActivity(Intent(requireContext(), AddProductActivity::class.java))
        }
        
        binding.btnExitEditorMode.setOnClickListener { 
            if (isMultiSelectMode) toggleMultiSelectMode(false)
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

        // Filtros en el Drawer
        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            filterLowStock = checkedIds.contains(R.id.chipFilterLowStock)
            loadProducts()
        }

        binding.btnFilterCategory.setOnClickListener { showCategorySelector() }
        binding.btnSortAttribute.setOnClickListener { showSortAttributeSelector() }
        binding.btnSortOrder.setOnClickListener { 
            isAscending = !isAscending
            binding.btnSortOrder.text = if (isAscending) "Orden: Ascendente" else "Orden: Descendente"
            binding.btnSortOrder.setIconResource(if (isAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            loadProducts()
        }

        binding.btnMultiDelete.setOnClickListener { 
            binding.drawerLayoutInventario.closeDrawer(GravityCompat.START)
            toggleMultiSelectMode(true) 
        }
        
        binding.btnExportExcel.setOnClickListener {
            binding.drawerLayoutInventario.closeDrawer(GravityCompat.START)
            Toast.makeText(requireContext(), "Exportando...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        adapter.setMultiSelectMode(enabled)
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        
        if (enabled) {
            val tv = binding.cardEditorBanner.findViewById<TextView>(android.R.id.text1) ?: 
                     binding.cardEditorBanner.getChildAt(0).let { if(it is android.view.ViewGroup) it.getChildAt(1) as? TextView else null }
            tv?.text = "Seleccione los productos para eliminar"
            binding.btnExitEditorMode.text = "CANCELAR"
            binding.fabAddProducto.setImageResource(android.R.drawable.ic_menu_delete)
            binding.fabAddProducto.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            binding.fabAddProducto.setOnClickListener { showMultiDeleteConfirmation() }
        } else {
            binding.btnExitEditorMode.text = "SALIR"
            binding.fabAddProducto.setImageResource(android.R.drawable.ic_input_add)
            binding.fabAddProducto.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9333EA"))
            binding.fabAddProducto.setOnClickListener { startActivity(Intent(requireContext(), AddProductActivity::class.java)) }
            loadProducts()
        }
    }

    private fun updateMultiSelectUI(count: Int) {
        if (count == -1) { toggleMultiSelectMode(true); return }
        if (isMultiSelectMode && count == 0) { toggleMultiSelectMode(false); return }
        val tv = binding.cardEditorBanner.findViewById<TextView>(android.R.id.text1) ?: 
                 binding.cardEditorBanner.getChildAt(0).let { if(it is android.view.ViewGroup) it.getChildAt(1) as? TextView else null }
        tv?.text = "$count seleccionados"
    }

    private fun handleStockQuickChange(product: ProductEntity, delta: Int) {
        val dbInstance = database ?: return
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            product.stock += delta
            if (product.stock < 0) product.stock = 0
            product.isSynced = false
            dbInstance.productDao().update(product)
            SyncManager(ctx).syncProductToCloud(product)
            
            withContext(Dispatchers.Main) {
                loadProducts()
                Toast.makeText(ctx, "${product.nombre}: ${product.stock}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadProducts() {
        val ctx = context ?: return
        val dbInstance = database ?: return
        val prefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessCurrency = prefs.getString("currency_symbol", "S/") ?: "S/"

        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val searchPattern = if (currentSearchQuery.isBlank()) "%%" else "%$currentSearchQuery%"
                var dbList = dbInstance.productDao().getFilteredAndSorted(searchPattern, currentCategory)
                
                val totalInvestment = dbList.sumOf { it.precioCosto * it.stock }
                val totalSaleValue = dbList.sumOf { it.precioVenta * it.stock }

                if (filterLowStock) {
                    dbList = dbList.filter { it.stock <= 5 && !it.isDeleted }
                }

                val fullList = when (currentSortAttribute) {
                    "STOCK" -> if (isAscending) dbList.sortedBy { it.stock } else dbList.sortedByDescending { it.stock }
                    "PRECIO" -> if (isAscending) dbList.sortedBy { it.precioVenta } else dbList.sortedByDescending { it.precioVenta }
                    else -> if (isAscending) {
                        dbList.sortedBy { it.nombre?.lowercase() ?: "" }
                    } else {
                        dbList.sortedByDescending { it.nombre?.lowercase() ?: "" }
                    }
                }

                if (!isActive) return@launch

                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        adapter.updateList(fullList)
                        lastLoadedList = fullList
                        
                        updateDrawerHeader(totalSaleValue, totalInvestment)
                        updateSyncIconState()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) e.printStackTrace()
            }
        }
    }

    private fun updateDrawerHeader(totalSale: Double, investment: Double) {
        try {
            if (_binding != null) {
                binding.includedDrawerHeader.tvDrawerValorTotal.text = String.format(Locale.getDefault(), "S/ %.2f", totalSale)
                binding.includedDrawerHeader.tvDrawerInversionTotal.text = String.format(Locale.getDefault(), "S/ %.2f", investment)
            }
        } catch (e: Exception) {}
    }

    private fun checkProductByCode() {
        val ctx = context ?: return
        val dbInstance = database ?: return
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(ctx, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                if (code.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val product = dbInstance.productDao().allProducts.find { 
                            it.codigo?.split(",")?.contains(code) == true || it.codigo == code 
                        }
                        withContext(Dispatchers.Main) {
                            val alertCtx = context ?: return@withContext
                            if (product != null) {
                                AlertDialog.Builder(alertCtx)
                                    .setTitle("Producto Encontrado ✅")
                                    .setMessage("Nombre: ${product.nombre}\nStock: ${product.stock}\nPrecio: S/ ${product.precioVenta}")
                                    .setPositiveButton("Ver Ficha") { _, _ -> showProductLabel(product) }
                                    .setNegativeButton("Cerrar", null)
                                    .show()
                            } else {
                                AlertDialog.Builder(alertCtx)
                                    .setTitle("No registrado ❌")
                                    .setMessage("El código $code no existe.")
                                    .setPositiveButton("Añadir") { _, _ ->
                                        val intent = Intent(alertCtx, AddProductActivity::class.java)
                                        intent.putExtra("EXTRA_SCANNED_CODE", code)
                                        startActivity(intent)
                                    }
                                    .setNegativeButton("Cerrar", null)
                                    .show()
                            }
                        }
                    }
                }
            }
    }

    private fun showMultiDeleteConfirmation() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        val ctx = context ?: return
        val dbInstance = database ?: return
        
        AlertDialog.Builder(ctx)
            .setTitle("Eliminar")
            .setMessage("¿Borrar ${selected.size} productos?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val sm = SyncManager(ctx)
                    selected.forEach { p ->
                        p.isDeleted = true
                        p.isSynced = false
                        dbInstance.productDao().update(p)
                        sm.deleteProductFromCloud(p.id)
                    }
                    sm.scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        toggleMultiSelectMode(false)
                        loadProducts()
                    }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showCategorySelector() {
        val dbInstance = database ?: return
        val ctx = context ?: return
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) { 
                val list = dbInstance.productDao().uniqueCategories.toMutableList()
                if (!list.contains("Todos")) list.add(0, "Todos")
                list.toTypedArray()
            }
            AlertDialog.Builder(ctx)
                .setTitle("Categoría")
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
        AlertDialog.Builder(requireContext())
            .setTitle("Ordenar por")
            .setItems(options) { _, which ->
                currentSortAttribute = values[which]
                binding.btnSortAttribute.text = "Ordenar por: ${options[which]}"
                loadProducts()
            }.show()
    }

    private fun toggleSortOrder() {
        isAscending = !isAscending
        loadProducts()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Inventario")
            .setMessage("Mantén tus existencias actualizadas.")
            .setPositiveButton("Entendido", null).show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchInventario.windowToken, 0)
    }

    private fun showProductLabel(p: ProductEntity) {
        val db = DialogViewLabelBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext(), R.style.Theme_Gomezmart_Dialog).setView(db.root).create()
        db.tvLabelProdName.text = p.nombre
        db.tvLabelProdDesc.text = p.descripcion ?: "Sin descripción."

        if (!p.photoPath.isNullOrEmpty()) {
            try {
                Glide.with(this).load(p.photoPath).into(db.ivLabelProdPhoto)
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

        db.btnEditProductFromLabel.setOnClickListener {
            d.dismiss()
            val intent = Intent(requireContext(), AddProductActivity::class.java)
            intent.putExtra("PRODUCT_ID", p.id)
            startActivity(intent)
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
        val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Ficha_$name.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { 
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) 
            }, "Compartir"))
        } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
