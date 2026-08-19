package com.naxor.app.fragment

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.naxor.app.*
import com.naxor.app.adapter.ProductAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityInventarioBinding
import com.naxor.app.databinding.DialogViewLabelBinding
import com.naxor.app.util.VoiceRecognitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

class StockFragment : Fragment() {

    private var _binding: ActivityInventarioBinding? = null
    private val binding get() = _binding!!
    
    private val adapter by lazy { 
        ProductAdapter(
            items = emptyList(),
            onEdit = { product -> 
                val intent = Intent(requireContext(), AddProductActivity::class.java)
                intent.putExtra("PRODUCT_ID", product.id)
                startActivity(intent)
            },
            onViewLabel = { product -> showProductLabel(product) },
            onSelectionChanged = { count -> updateMultiSelectUI(count) }
        )
    }
    
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private var currentSortAttribute: String = "NOMBRE"
    private var isAscending: Boolean = true
    private var currentCategory: String = "Todos"
    private var currentSearchQuery: String = ""
    private var isEditorMode: Boolean = false
    private var isMultiSelectMode: Boolean = false
    private val voiceHelper by lazy { VoiceRecognitionHelper(requireContext()) }
    private var syncStatusText: String = "Sincronizado"
    private var lastUnsyncedCount: Int = 0
    private var isNetworkAvailable: Boolean = true

    fun openDrawer() {
        if (_binding != null) {
            binding.drawerLayoutInventario.openDrawer(GravityCompat.START)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityInventarioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupSyncIndicator()
        setupRecyclerView()
        setupListeners()

        SyncManager(requireContext()).startRealtimeInventorySync { 
            loadProducts()
        }
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    private fun setupRecyclerView() {
        binding.rvInventario.layoutManager = LinearLayoutManager(requireContext())
        binding.rvInventario.adapter = adapter
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
        lifecycleScope.launch(Dispatchers.IO) {
            val unsynced = database.productDao().allProducts.count { !it.isSynced && !it.isDeleted }
            lastUnsyncedCount = unsynced
            
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                val networkStatus = if (isNetworkAvailable) "Conectado" else "Sin internet"
                syncStatusText = if (unsynced > 0) "Sincronizando ($unsynced pendientes)" else "Todo al día"
                
                if (unsynced > 0) {
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.ic_menu_upload)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                } else {
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.ic_menu_upload)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
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
        binding.btnBackInventario.visibility = View.GONE // Ya tenemos navegación inferior
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
            startActivity(Intent(requireContext(), AddProductActivity::class.java))
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
            updateControlsLayout(isExpanded)
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearchInventario.setText("")
            binding.etSearchInventario.clearFocus()
            hideKeyboard()
            updateControlsLayout(false)
        }

        binding.btnFilterCategory.setOnClickListener { showCategorySelector() }
        binding.btnSortAttribute.setOnClickListener { showSortAttributeSelector() }
        binding.btnSortOrder.setOnClickListener { toggleSortOrder() }

        binding.navigationViewInventario.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_check_code -> { checkProductByCode(); true }
                R.id.menu_export_excel -> { Toast.makeText(requireContext(), "Exportando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_import_excel -> { Toast.makeText(requireContext(), "Importando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_multi_delete -> { toggleMultiSelectMode(true); true }
                R.id.menu_settings_inventario -> { startActivity(Intent(requireContext(), SettingsActivity::class.java)); true }
                else -> false
            }.also { binding.drawerLayoutInventario.closeDrawer(GravityCompat.START) }
        }
    }

    private fun toggleEditorMode(enabled: Boolean) {
        if (isMultiSelectMode) toggleMultiSelectMode(false)
        isEditorMode = enabled
        adapter.setEditorMode(enabled)
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        loadProducts()
    }

    private fun toggleMultiSelectMode(enabled: Boolean) {
        if (isEditorMode) toggleEditorMode(false)
        isMultiSelectMode = enabled
        adapter.setMultiSelectMode(enabled)
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        
        if (enabled) {
            val tv = binding.cardEditorBanner.findViewById<TextView>(android.R.id.text1) ?: 
                     binding.cardEditorBanner.getChildAt(0).let { if(it is android.view.ViewGroup) it.getChildAt(1) as? TextView else null }
            tv?.text = "Seleccione los productos que desea eliminar."
            binding.btnExitEditorMode.text = "CANCELAR"
            binding.fabToggleEditor.visibility = View.GONE
            binding.fabAddProducto.setImageResource(android.R.drawable.ic_menu_delete)
            binding.fabAddProducto.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            binding.fabAddProducto.setOnClickListener { showMultiDeleteConfirmation() }
        } else {
            binding.btnExitEditorMode.text = "SALIR"
            binding.fabToggleEditor.visibility = View.VISIBLE
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
        tv?.text = "$count productos seleccionados"
    }

    private fun updateControlsLayout(isExpanded: Boolean) {
        // Lógica de UI para controles... simplificada para fragmento
        binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val dbList = if (currentSearchQuery.isNotBlank()) database.productDao().searchProducts("%$currentSearchQuery%")
                else if (currentCategory == "Todos") database.productDao().allProducts
                else database.productDao().getProductsByCategory(currentCategory)
                
                when (currentSortAttribute) {
                    "STOCK" -> if (isAscending) dbList.sortedBy { it.stock } else dbList.sortedByDescending { it.stock }
                    "PRECIO" -> if (isAscending) dbList.sortedBy { it.precioVenta } else dbList.sortedByDescending { it.precioVenta }
                    else -> if (isAscending) dbList.sortedBy { it.nombre.lowercase() } else dbList.sortedByDescending { it.nombre.lowercase() }
                }
            }
            if (_binding != null) {
                adapter.updateList(list)
                binding.rvInventario.scrollToPosition(0)
                
                // Cálculos Correctos para el menú lateral (Inversión real y Valor venta)
                val totalInversion = list.sumOf { it.precioCosto * it.stock } 
                val totalValorVenta = list.sumOf { it.stock * it.precioVenta }
                updateDrawerHeader(totalValorVenta, totalInversion)
                
                updateSyncIconState()
            }
        }
    }

    private fun updateDrawerHeader(v: Double, i: Double) {
        try {
            val h = binding.navigationViewInventario.getHeaderView(0)
            h.findViewById<TextView>(R.id.tvDrawerValorTotal)?.text = String.format(Locale.getDefault(), "S/ %.2f", v)
            h.findViewById<TextView>(R.id.tvDrawerInversionTotal)?.text = String.format(Locale.getDefault(), "S/ %.2f", i)
        } catch (e: Exception) {}
    }

    private fun checkProductByCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(requireContext(), options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                if (code.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val product = database.productDao().allProducts.find { 
                            it.codigo?.split(",")?.contains(code) == true || it.codigo == code 
                        }
                        withContext(Dispatchers.Main) {
                            if (product != null) {
                                AlertDialog.Builder(requireContext())
                                    .setTitle("Producto Encontrado ✅")
                                    .setMessage("Nombre: ${product.nombre}\nStock: ${product.stock}\nPrecio: S/ ${product.precioVenta}")
                                    .setPositiveButton("Ver Ficha") { _, _ -> showProductLabel(product) }
                                    .setNegativeButton("Cerrar", null)
                                    .show()
                            } else {
                                AlertDialog.Builder(requireContext())
                                    .setTitle("No registrado ❌")
                                    .setMessage("El código $code no existe en tu inventario.")
                                    .setPositiveButton("Añadir Nuevo") { _, _ ->
                                        val intent = Intent(requireContext(), AddProductActivity::class.java)
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
        
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar Varios")
            .setMessage("¿Borrar ${selected.size} productos seleccionados?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val sm = SyncManager(requireContext())
                    selected.forEach { p ->
                        p.isDeleted = true
                        p.isSynced = false
                        database.productDao().update(p)
                        sm.deleteProductFromCloud(p.id)
                    }
                    sm.scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        toggleMultiSelectMode(false)
                        Toast.makeText(requireContext(), "Productos eliminados", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showCategorySelector() {
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) { 
                val list = database.productDao().uniqueCategories.toMutableList()
                list.add(0, "Todos")
                list.toTypedArray()
            }
            AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle("Ordenar por")
            .setItems(options) { _, which ->
                currentSortAttribute = values[which]
                binding.btnSortAttribute.text = options[which]
                loadProducts()
            }.show()
    }

    private fun toggleSortOrder() {
        isAscending = !isAscending
        binding.btnSortOrder.setIconResource(if (isAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
        loadProducts()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Guía de Gestión de Inventario")
            .setMessage("Mantén tus existencias actualizadas para que Nexur pueda darte un análisis de rendimiento preciso.")
            .setPositiveButton("Entendido", null).show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchInventario.windowToken, 0)
    }

    private fun showProductLabel(p: ProductEntity) {
        val db = DialogViewLabelBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext(), R.style.Theme_Naxor_Dialog).setView(db.root).create()
        db.tvLabelProdName.text = p.nombre
        db.tvLabelProdDesc.text = p.descripcion ?: "Sin descripción."

        if (!p.photoPath.isNullOrEmpty()) {
            try {
                Glide.with(this)
                    .load(p.photoPath)
                    .placeholder(android.R.drawable.ic_menu_camera)
                    .into(db.ivLabelProdPhoto)
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

        val codes = p.codigo?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        db.tvLabelProdCode.text = if (codes.isNotEmpty()) codes.joinToString("\n") { "• $it" } else "---"
        db.cbShowLinkedCodes.setOnCheckedChangeListener { _, isChecked ->
            db.cardLinkedCodes.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

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
            }, "Compartir Ficha"))
        } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        voiceHelper.destroy()
    }
}
