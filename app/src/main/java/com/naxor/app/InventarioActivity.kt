package com.naxor.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
import java.util.*

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
            onSelectionChanged = { count -> updateMultiSelectUI(count) }
        )
    }
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentSortAttribute: String = "NOMBRE"
    private var isAscending: Boolean = true
    private var currentCategory: String = "Todos"
    private var currentSearchQuery: String = ""
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
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.ic_menu_upload)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                } else {
                    binding.btnSyncIndicator.setImageResource(android.R.drawable.ic_menu_upload)
                    binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
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
            updateControlsLayout(isExpanded)
        }

        binding.etSearchInventario.setOnFocusChangeListener { _, hasFocus ->
            val isExpanded = currentSearchQuery.isNotEmpty() || hasFocus
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
                R.id.menu_export_excel -> { Toast.makeText(this, "Exportando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_import_excel -> { Toast.makeText(this, "Importando...", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_multi_delete -> { toggleMultiSelectMode(true); true }
                R.id.menu_settings_inventario -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }.also { binding.drawerLayoutInventario.closeDrawer(GravityCompat.START) }
        }
    }

    private fun toggleEditorMode(enabled: Boolean) {
        if (isMultiSelectMode) toggleMultiSelectMode(false)
        isEditorMode = enabled
        adapter.setEditorMode(enabled)
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        
        if (isEditorMode) {
            // Icono de X en color intenso (Magenta)
            binding.fabToggleEditor.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            binding.fabToggleEditor.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C026D3"))
            binding.fabToggleEditor.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else {
            // Icono de Lápiz original
            binding.fabToggleEditor.setImageResource(android.R.drawable.ic_menu_edit)
            binding.fabToggleEditor.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
            binding.fabToggleEditor.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6B21A8"))
        }
        updateControlsLayout(currentSearchQuery.isNotEmpty() || binding.etSearchInventario.hasFocus())
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
            binding.fabAddProducto.setOnClickListener { startActivity(Intent(this, AddProductActivity::class.java)) }
            loadProducts()
        }
    }

    private fun updateMultiSelectUI(count: Int) {
        if (count == -1) { // Long click signal
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

    private fun updateControlsLayout(isExpanded: Boolean) {
        val set = androidx.constraintlayout.widget.ConstraintSet()
        set.clone(binding.layoutControlsContainer)

        // Siempre ancho completo para evitar distorsiones
        set.clear(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.START)
        set.clear(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END)
        set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
        set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
        set.constrainWidth(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
        
        binding.layoutVentaBusqueda.hint = if (isExpanded) "Buscar producto..." else "Buscar..."

        set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        set.connect(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        set.connect(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        
        set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)
        set.setMargin(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)
        set.setMargin(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)
        
        binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory

        androidx.transition.TransitionManager.beginDelayedTransition(binding.layoutControlsContainer)
        set.applyTo(binding.layoutControlsContainer)
    }

    private fun loadProducts() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val search = if (currentSearchQuery.isBlank()) "%%" else "%$currentSearchQuery%"
                    val dbList = database.productDao().getFilteredAndSorted(search, currentCategory)
                    
                    val sortedList = when (currentSortAttribute) {
                        "STOCK" -> if (isAscending) dbList.sortedBy { it.stock } else dbList.sortedByDescending { it.stock }
                        "PRECIO" -> if (isAscending) dbList.sortedBy { it.precioVenta } else dbList.sortedByDescending { it.precioVenta }
                        else -> if (isAscending) {
                            dbList.sortedBy { it.nombre?.lowercase()?.trim() ?: "" }
                        } else {
                            dbList.sortedByDescending { it.nombre?.lowercase()?.trim() ?: "" }
                        }
                    }

                    val totalInversion = sortedList.sumOf { it.precioCosto }
                    val totalValorVenta = sortedList.sumOf { it.stock * it.precioVenta }
                    
                    Triple(sortedList, totalInversion, totalValorVenta)
                }
                
                if (!isActive) return@launch

                adapter.updateList(result.first)
                
                // Forzar scroll al inicio para mostrar los resultados desde arriba
                binding.rvInventario.scrollToPosition(0) 
                
                updateDrawerHeader(result.third, result.second)
                updateSyncIconState()
            } catch (e: Exception) {
                android.util.Log.e("INVENTARIO", "Error cargando productos", e)
            }
        }
    }

    private fun updateDrawerHeader(v: Double, i: Double) {
        try {
            if (binding.navigationViewInventario.headerCount > 0) {
                val h = binding.navigationViewInventario.getHeaderView(0)
                val tvValor = h.findViewById<TextView>(R.id.tvDrawerValorTotal)
                val tvInversion = h.findViewById<TextView>(R.id.tvDrawerInversionTotal)
                
                tvValor?.text = String.format(Locale.getDefault(), "S/ %.2f", v)
                tvInversion?.text = String.format(Locale.getDefault(), "S/ %.2f", i)
            }
        } catch (e: Exception) {
            android.util.Log.e("INVENTARIO", "Error actualizando header", e)
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

                    // REGISTRAR EN HISTORIAL
                    val log = MovementLogEntity(
                        type = "PRODUCT_DELETED",
                        title = "Eliminación Múltiple",
                        description = "Se eliminaron $count productos: $names",
                        value = "BORRADO",
                        colorHex = "#E11D48", // Rojo vibrante para resaltar
                        iconRes = android.R.drawable.ic_menu_delete,
                        timestamp = System.currentTimeMillis()
                    )
                    database.movementLogDao().insert(log)
                    sm.syncLogToCloud(log)

                    sm.scheduleOfflineSync()
                    withContext(Dispatchers.Main) {
                        toggleMultiSelectMode(false)
                        Toast.makeText(this@InventarioActivity, "Productos eliminados", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showCategorySelector() {
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) { 
                val rawCategories = database.productDao().uniqueCategories
                val list = rawCategories.filterNotNull().filter { it.isNotBlank() }.toMutableList()
                list.add(0, "Todos")
                list.toTypedArray()
            }
            if (categories.isNotEmpty()) {
                AlertDialog.Builder(this@InventarioActivity)
                    .setTitle("Seleccionar Categoría")
                    .setItems(categories) { _, which ->
                        currentCategory = categories[which]
                        binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory
                        loadProducts()
                    }.show()
            }
        }
    }

    private fun showSortAttributeSelector() {
        val options = arrayOf("Nombre", "Stock", "Precio")
        val values = arrayOf("NOMBRE", "STOCK", "PRECIO")
        AlertDialog.Builder(this)
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
        val helpMessage = """
            📦 IMPORTANCIA DEL INVENTARIO
            Un inventario bien gestionado es el corazón de tu negocio. Te permite conocer tu inversión real, evitar quiebres de stock (quedarte sin productos para vender) y detectar pérdidas o mermas a tiempo.

            💎 CARACTERÍSTICAS DE NAXOR
            • Identificación Dual: Genera o escanea códigos QR y de Barras (CODE_128) para cada producto.
            • Inteligencia de Costos: Naxor calcula tu inversión basándose en el costo del lote y el stock actual.
            • Categorización: Organiza tus productos para encontrarlos en segundos.

            🛠️ CÓMO USAR ESTE MÓDULO
            1. Búsqueda: Usa la lupa para filtrar por nombre, categoría o código.
            2. Modo Editor (Icono Lápiz): Actívalo para cambiar precios o stock tocando directamente el producto en la lista.
            3. Selección Múltiple: Úsala desde el menú lateral para borrar varios productos a la vez.
            4. Ficha Técnica: Toca cualquier producto en modo normal para ver y compartir su código identificador.

            🚀 TIP: Mantén tus existencias actualizadas para que Naxor pueda darte estadísticas de utilidad precisas en la pantalla principal.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Guía de Gestión de Inventario")
            .setMessage(helpMessage)
            .setPositiveButton("Entendido", null).show()
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
