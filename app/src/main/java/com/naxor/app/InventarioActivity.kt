package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.media.ToneGenerator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.naxor.app.adapter.ProductAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityInventarioBinding
import com.naxor.app.databinding.DialogAddProductBinding
import com.naxor.app.databinding.DialogViewLabelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class InventarioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventarioBinding
    private lateinit var adapter: ProductAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentDialogBinding: DialogAddProductBinding? = null
    private var currentPhotoPath: String? = null
    private var currentSortAttribute = "NOMBRE"
    private var isAscending = true
    private var currentCategory = "Todos"
    private var currentSearchQuery = ""
    private var isEditorMode = false
    private var isMultiSelectMode = false
    private var syncStatusText = "Sincronizado"
    private var lastUnsyncedCount = 0
    private var speechRecognizer: SpeechRecognizer? = null

    // LANZADORES PARA RECONOCIMIENTO DE VOZ (COMO RESPALDO)
    private val voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
            binding.etSearchInventario.setText(spokenText)
        }
    }

    private val voiceRegisterLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
            currentDialogBinding?.etProdNombre?.setText(spokenText)
        }
    }

    private val voiceDescLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
            currentDialogBinding?.etProdDescripcion?.setText(spokenText)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportInventoryToUri(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importInventoryFromUri(it) }
    }

    private val takeProductPhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoPath != null) {
            currentDialogBinding?.ivDialogProdPhoto?.let {
                it.setImageURI(android.net.Uri.fromFile(File(currentPhotoPath!!)))
                it.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                it.setPadding(0, 0, 0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventarioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) Toast.makeText(this, "Se requiere permiso de cámara y audio", Toast.LENGTH_LONG).show()
        }
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        // Solicitar audio tambien
        requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)

        setupRecyclerView()
        setupListeners()
        setupSyncIndicator()
        updateFABState() 
        loadProducts()
        
        SyncManager(this).startRealtimeInventorySync { loadProducts() }
    }

    private fun setupSyncIndicator() {
        database.productDao().unsyncedCount.observe(this) { count ->
            lastUnsyncedCount = count ?: 0
            updateSyncIconState(lastUnsyncedCount, false)
        }

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("offline_sync")
            .observe(this) { infoList ->
                val isSyncing = infoList != null && infoList.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                updateSyncIconState(lastUnsyncedCount, isSyncing)
            }

        binding.btnSyncIndicator.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sincronización")
                .setMessage("Estado: $syncStatusText\n\n¿Deseas forzar la subida de todos los datos ahora?")
                .setPositiveButton("Sincronizar Todo") { _, _ -> forceFullUpload() }
                .setNeutralButton("Probar Conexión") { _, _ -> testFirebaseWrite() }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun testFirebaseWrite() {
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val testData = mapOf("test_time" to System.currentTimeMillis(), "msg" to "Prueba")
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("test").add(testData)
            .addOnSuccessListener { Toast.makeText(this, "¡Conexión Exitosa!", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun forceFullUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.productDao().allProducts.forEach { it.isSynced = false; database.productDao().update(it) }
            database.saleDao().allSales.forEach { it.isSynced = false; database.saleDao().update(it) }
            SyncManager(this@InventarioActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) { Toast.makeText(this@InventarioActivity, "Sincronizando...", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateSyncIconState(unsyncedCount: Int, isSyncing: Boolean) {
        val color: Int
        val animation: android.view.animation.Animation?
        val iconRes: Int
        
        when {
            isSyncing -> {
                syncStatusText = "Sincronizando..."
                color = getColor(R.color.sky_600)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            unsyncedCount > 0 -> {
                syncStatusText = "Pendiente ($unsyncedCount)"
                color = getColor(R.color.red_600)
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            else -> {
                syncStatusText = "Sincronizado"
                color = getColor(R.color.emerald_600)
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
        }

        binding.btnSyncIndicator.setImageResource(iconRes)
        binding.btnSyncIndicator.imageTintList = android.content.res.ColorStateList.valueOf(color)
        if (animation != null) binding.btnSyncIndicator.startAnimation(animation) else binding.btnSyncIndicator.clearAnimation()
    }

    private fun setupRecyclerView() {
        adapter = ProductAdapter(
            items = emptyList(),
            onEdit = { product -> showProductDialog(product) },
            onViewLabel = { product -> showProductLabel(product) },
            onSelectionChanged = { count ->
                if (count == -1) adapter.setMultiSelectMode(true)
                updateMultiSelectMenu(count)
            }
        )
        binding.rvInventario.layoutManager = LinearLayoutManager(this)
        binding.rvInventario.adapter = adapter
    }

    private fun updateMultiSelectMenu(count: Int) {
        if (count == 0) adapter.setMultiSelectMode(false)
        isMultiSelectMode = count > 0 || count == -1
        updateFABState(if (count == -1) 1 else count)
    }

    private fun updateFABState(selectionCount: Int = 0) {
        with(binding.fabAddProducto) {
            when {
                selectionCount > 0 -> {
                    visibility = View.VISIBLE
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setOnClickListener { showMultiDeleteConfirmation() }
                }
                isEditorMode -> visibility = View.GONE
                else -> {
                    visibility = View.VISIBLE
                    setImageResource(android.R.drawable.ic_input_add)
                    setOnClickListener { showProductDialog() }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnBackInventario.setOnClickListener { finish() }
        binding.btnHelpInventario.setOnClickListener { showHelpDialog() }
        binding.fabAddProducto.setOnClickListener { showProductDialog() }
        binding.btnExitEditorMode.setOnClickListener { toggleEditorMode(false) }

        // MICRÓFONO INTEGRADO
        binding.btnVoiceSearch.visibility = View.VISIBLE // Asegurar visibilidad inicial
        binding.btnVoiceSearch.setOnClickListener { startVoiceRecognition(1) }

        // BOTÓN LIMPIAR MANUAL
        binding.btnSearchClear.setOnClickListener {
            binding.etSearchInventario.setText("")
            binding.etSearchInventario.clearFocus()
            hideKeyboard()
            updateControlsLayout(false)
        }

        binding.etSearchInventario.addTextChangedListener { text -> 
            currentSearchQuery = text.toString()
            loadProducts() 
            
            // Mostrar/Ocultar botón X
            binding.btnSearchClear.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
            
            if (currentSearchQuery.isEmpty() && !binding.etSearchInventario.hasFocus()) {
                hideKeyboard()
            }
            val isExpanded = currentSearchQuery.isNotEmpty() || binding.etSearchInventario.hasFocus()
            updateControlsLayout(isExpanded)
        }

        binding.etSearchInventario.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                binding.etSearchInventario.clearFocus()
                hideKeyboard()
                true
            } else false
        }

        binding.etSearchInventario.setOnFocusChangeListener { _, hasFocus ->
            updateControlsLayout(currentSearchQuery.isNotEmpty() || hasFocus)
        }

        binding.root.setOnClickListener {
            binding.etSearchInventario.clearFocus()
            hideKeyboard()
        }
        
        binding.rvInventario.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    binding.etSearchInventario.clearFocus()
                    hideKeyboard()
                }
            }
        })
        
        binding.layoutVentaBusqueda.setEndIconOnClickListener {
            binding.etSearchInventario.setText("")
            binding.etSearchInventario.clearFocus()
            hideKeyboard()
            updateControlsLayout(false)
        }

        binding.btnFilterCategory.setOnClickListener { showCategorySelector() }
        binding.btnSortAttribute.setOnClickListener { showSortAttributeSelector() }
        binding.btnSortOrder.setOnClickListener { toggleSortOrder() }
        binding.btnOpenMenuInventario.setOnClickListener { binding.drawerLayoutInventario.openDrawer(GravityCompat.END) }

        binding.navigationViewInventario.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_export_excel -> { exportLauncher.launch("Inventario.csv"); true }
                R.id.menu_import_excel -> { importLauncher.launch("text/*"); true }
                R.id.menu_edit_mode -> { toggleEditorMode(!menuItem.isChecked); true }
                else -> false
            }.also { binding.drawerLayoutInventario.closeDrawer(GravityCompat.END) }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchInventario.windowToken, 0)
    }

    private fun toggleEditorMode(enabled: Boolean) {
        isEditorMode = enabled
        adapter.setEditorMode(enabled)
        updateFABState()
        binding.cardEditorBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.navigationViewInventario.menu.findItem(R.id.menu_edit_mode)?.isChecked = enabled
        updateControlsLayout(currentSearchQuery.isNotEmpty() || binding.etSearchInventario.hasFocus())
    }

    private fun updateControlsLayout(isExpanded: Boolean) {
        val actuallyExpanded = !isEditorMode || isExpanded
        val set = androidx.constraintlayout.widget.ConstraintSet()
        set.clone(binding.layoutControlsContainer)

        if (actuallyExpanded) {
            set.clear(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            set.constrainWidth(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
            binding.layoutVentaBusqueda.hint = "Buscar producto..."

            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)

            set.connect(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.setMargin(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)
            
            set.connect(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.setMargin(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 12)
            binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory
        } else {
            // MODO COMPACTO
            set.clear(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.constrainWidth(binding.layoutVentaBusqueda.id, 100.dpToPx()) 
            binding.layoutVentaBusqueda.hint = ""

            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.START, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END)
            set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.START, 8)

            set.connect(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.setMargin(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            
            set.connect(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.setMargin(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            binding.btnFilterCategory.text = "Cat"
            binding.btnVoiceSearch.visibility = View.VISIBLE
        }
        
        androidx.transition.TransitionManager.beginDelayedTransition(binding.layoutControlsContainer, androidx.transition.AutoTransition().apply { duration = 250 })
        set.applyTo(binding.layoutControlsContainer)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showMultiDeleteConfirmation() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Eliminar").setMessage("¿Borrar ${selected.size} productos?").setPositiveButton("Eliminar") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                selected.forEach { it.isDeleted = true; it.isSynced = false; database.productDao().update(it) }
                SyncManager(this@InventarioActivity).scheduleOfflineSync()
                withContext(Dispatchers.Main) { adapter.setMultiSelectMode(false); updateMultiSelectMenu(0); loadProducts() }
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun importInventoryFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = inputStream.bufferedReader(); reader.readLine()
                    var line = reader.readLine(); var count = 0
                    while (line != null) {
                        val parts = line.split(",")
                        if (parts.size >= 6) {
                            database.productDao().insert(ProductEntity(parts[0], parts[1], parts[2], parts[3].toIntOrNull() ?: 0, parts[4].toDoubleOrNull() ?: 0.0, parts[5].toDoubleOrNull() ?: 0.0).apply { isSynced = false })
                            count++
                        }; line = reader.readLine()
                    }
                    SyncManager(this@InventarioActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadProducts(); Toast.makeText(this@InventarioActivity, "Importados: $count", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun exportInventoryToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csv = StringBuilder("Cód,Producto,Cat,Stock,Costo,Venta\n")
                database.productDao().allProducts.forEach { p -> csv.append("${p.codigo},${p.nombre},${p.categoria},${p.stock},${p.precioCosto},${p.precioVenta}\n") }
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toString().toByteArray()) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showCategorySelector() {
        lifecycleScope.launch {
            val cats = withContext(Dispatchers.IO) { database.productDao().uniqueCategories }
            val popup = androidx.appcompat.widget.PopupMenu(this@InventarioActivity, binding.btnFilterCategory)
            popup.menu.add(0, 0, 0, "📂 Todos")
            cats.forEachIndexed { i, c -> popup.menu.add(1, i + 1, i + 1, c) }
            popup.setOnMenuItemClickListener { item ->
                currentCategory = if (item.itemId == 0) "Todos" else item.title.toString()
                loadProducts(); true
            }; popup.show()
        }
    }

    private fun showSortAttributeSelector() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnSortAttribute)
        listOf("NOMBRE", "STOCK", "PRECIO", "FECHA").forEach { popup.menu.add(0, 0, 0, it) }
        popup.setOnMenuItemClickListener { item -> currentSortAttribute = item.title.toString(); loadProducts(); true }; popup.show()
    }

    private fun toggleSortOrder() {
        isAscending = !isAscending
        val rotation = if (isAscending) 0f else 180f
        binding.btnSortOrder.animate().rotation(rotation).setDuration(250).start()
        loadProducts()
    }

    private fun showHelpDialog() { AlertDialog.Builder(this).setTitle("Ayuda").setMessage("Modo normal: Ver. Modo editor: Editar.").setPositiveButton("OK", null).show() }

    private fun loadProducts() {
        lifecycleScope.launch {
            var list = withContext(Dispatchers.IO) {
                if (currentSearchQuery.isNotBlank()) database.productDao().searchProducts("%$currentSearchQuery%")
                else if (currentCategory == "Todos") database.productDao().allProducts
                else database.productDao().getProductsByCategory(currentCategory)
            }
            list = when (currentSortAttribute) {
                "STOCK" -> if (isAscending) list.sortedBy { it.stock } else list.sortedByDescending { it.stock }
                "PRECIO" -> if (isAscending) list.sortedBy { it.precioVenta } else list.sortedByDescending { it.precioVenta }
                "FECHA" -> if (isAscending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp }
                else -> if (isAscending) list.sortedBy { it.nombre.lowercase() } else list.sortedByDescending { it.nombre.lowercase() }
            }
            adapter.updateList(list)
            val inv = list.sumOf { it.precioCosto }; val valV = list.sumOf { it.stock * it.precioVenta }
            updateDrawerHeader(valV, inv)
        }
    }

    private fun updateDrawerHeader(v: Double, i: Double) {
        try {
            val h = binding.navigationViewInventario.getHeaderView(0)
            h.findViewById<TextView>(R.id.tvDrawerValorTotal)?.text = String.format(Locale.getDefault(), "S/ %.2f", v)
            h.findViewById<TextView>(R.id.tvDrawerInversionTotal)?.text = String.format(Locale.getDefault(), "S/ %.2f", i)
        } catch (e: Exception) {}
    }

    private fun showProductDialog(p: ProductEntity? = null) {
        val db = DialogAddProductBinding.inflate(layoutInflater); currentDialogBinding = db
        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        val addedCodes = mutableListOf<String>()

        fun refreshCodesUI() {
            db.layoutCodesList.removeAllViews()
            addedCodes.forEach { code ->
                val itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, db.layoutCodesList, false) as TextView
                itemView.text = "• $code (Quitar)"
                itemView.setOnClickListener { addedCodes.remove(code); refreshCodesUI() }
                db.layoutCodesList.addView(itemView)
            }
        }

        if (p != null) {
            db.etProdNombre.setText(p.nombre)
            db.etProdDescripcion.setText(p.descripcion)
            db.autoProdCategoria.setText(p.categoria, false)
            db.etProdStock.setText(p.stock.toString())
            db.etProdVenta.setText(p.precioVenta.toString())
            p.codigo?.split(",")?.filter { it.isNotBlank() }?.let { addedCodes.addAll(it) }
            refreshCodesUI()
            if (!p.location.isNullOrEmpty()) { db.cbShowLocation.isChecked = true; db.layoutProdLocation.visibility = View.VISIBLE; db.etProdLocation.setText(p.location) }
            if (p.precioCosto > 0) { db.cbShowBatchCost.isChecked = true; db.layoutProdCosto.visibility = View.VISIBLE; db.etProdCosto.setText(p.precioCosto.toString()) }
            if (p.expirationDate > 0) { db.cbShowExpiration.isChecked = true; db.layoutProdExpiration.visibility = View.VISIBLE; db.etProdExpiration.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(p.expirationDate))) }
            if (!p.photoPath.isNullOrEmpty()) {
                db.ivDialogProdPhoto.setImageURI(android.net.Uri.parse(p.photoPath))
                db.ivDialogProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                db.ivDialogProdPhoto.setPadding(0,0,0,0)
            }
            db.btnDeleteProduct.visibility = View.VISIBLE
            db.btnDeleteProduct.setOnClickListener { showDeleteConfirmation(p); dialog.dismiss() }
        }

        db.cbShowBatchCost.setOnCheckedChangeListener { _, isChecked -> db.layoutProdCosto.visibility = if (isChecked) View.VISIBLE else View.GONE }
        db.cbShowExpiration.setOnCheckedChangeListener { _, isChecked -> db.layoutProdExpiration.visibility = if (isChecked) View.VISIBLE else View.GONE }
        db.cbShowLocation.setOnCheckedChangeListener { _, isChecked -> db.layoutProdLocation.visibility = if (isChecked) View.VISIBLE else View.GONE }
        
        db.cardProdPhoto.setOnClickListener {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "prod_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            currentPhotoPath = file.absolutePath
            takeProductPhotoLauncher.launch(uri)
        }

        db.layoutProdCodigo.setEndIconOnClickListener {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_QR_CODE
                )
                .enableAutoZoom()
                .build()

            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode ->
                    barcode.rawValue?.let { code ->
                        if (code.length >= 4 && !addedCodes.contains(code)) {
                            try {
                                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    val vibratorManager = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                                    vibratorManager.defaultVibrator
                                } else {
                                    @Suppress("DEPRECATION")
                                    getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                }
                                
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(120)
                                }
                                val tg = ToneGenerator(android.media.AudioManager.STREAM_RING, 100)
                                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                                Toast.makeText(this, "Código capturado: $code", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {}
                            addedCodes.add(code)
                            refreshCodesUI()
                        }
                    }
                }
        }

        db.layoutProdCodigo.setStartIconOnClickListener {
            generarCodigoAutomatico(db, addedCodes) { refreshCodesUI() }
        }

        lifecycleScope.launch {
            val dbCategories = withContext(Dispatchers.IO) { database.productDao().uniqueCategories }
            val catAdapter = ArrayAdapter(this@InventarioActivity, android.R.layout.simple_dropdown_item_1line, dbCategories)
            db.autoProdCategoria.setAdapter(catAdapter)
            db.autoProdCategoria.threshold = 1
        }

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        
        fun mostrarCategorias() {
            imm.hideSoftInputFromWindow(db.autoProdCategoria.windowToken, 0)
            db.autoProdCategoria.clearFocus()
            db.autoProdCategoria.postDelayed({ db.autoProdCategoria.showDropDown() }, 250)
        }

        db.layoutProdCategoria.setEndIconOnClickListener {
            val oldThreshold = db.autoProdCategoria.threshold
            db.autoProdCategoria.threshold = 0
            mostrarCategorias()
            db.autoProdCategoria.postDelayed({ db.autoProdCategoria.threshold = oldThreshold }, 600)
        }

        db.autoProdCategoria.setOnClickListener { if (db.autoProdCategoria.text.isNotEmpty()) db.autoProdCategoria.showDropDown() }

        db.layoutProdNombre.setEndIconOnClickListener { startVoiceRecognition(2) }
        db.layoutProdDesc.setEndIconOnClickListener { startVoiceRecognition(3) }

        db.btnConfirmAdd.setOnClickListener {
            val name = db.etProdNombre.text.toString().trim()
            val cat = db.autoProdCategoria.text.toString().trim()
            if (name.isNotBlank() && cat.isNotBlank()) {
                val stock = db.etProdStock.text.toString().toIntOrNull() ?: 0
                val sale = db.etProdVenta.text.toString().toDoubleOrNull() ?: 0.0
                val cost = if (db.cbShowBatchCost.isChecked) db.etProdCosto.text.toString().toDoubleOrNull() ?: 0.0 else 0.0
                val loc = db.etProdLocation.text.toString().trim()
                val desc = db.etProdDescripcion.text.toString().trim()
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val existing = database.productDao().getProductByName(name)
                    withContext(Dispatchers.Main) {
                        if (existing != null && existing.id != (p?.id ?: "")) {
                            AlertDialog.Builder(this@InventarioActivity)
                                .setTitle("Producto existente")
                                .setMessage("¿Unificar stock con '$name'?")
                                .setPositiveButton("Sí") { _, _ -> unificarProductos(existing, p, addedCodes.joinToString(","), stock, cost, sale, 0, p?.photoPath, loc, desc, dialog) }
                                .setNegativeButton("No", null).show()
                        } else {
                            saveOrUpdateProduct(p, addedCodes.joinToString(","), name, cat, stock, cost, sale, 0, p?.photoPath, loc, desc, dialog)
                        }
                    }
                }
            } else Toast.makeText(this, "Nombre y categoría requeridos", Toast.LENGTH_SHORT).show()
        }
        
        db.btnCancelAdd.setOnClickListener { dialog.dismiss() }
        dialog.show()
        
        dialog.window?.let {
            it.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun saveOrUpdateProduct(p: ProductEntity?, code: String, name: String, cat: String, stock: Int, cost: Double, sale: Double, exp: Long, photo: String?, loc: String, desc: String, d: AlertDialog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val productToSync = if (p != null) {
                p.apply { codigo = code; nombre = name; categoria = cat; this.stock = stock; precioCosto = cost; precioVenta = sale; photoPath = photo; location = loc; descripcion = desc; isSynced = false }
                database.productDao().update(p)
                p
            } else {
                val newP = ProductEntity(code, name, cat, stock, cost, sale).apply { photoPath = photo; location = loc; descripcion = desc; isSynced = false }
                database.productDao().insert(newP)
                newP
            }
            SyncManager(this@InventarioActivity).syncProductToCloud(productToSync)
            SyncManager(this@InventarioActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) { loadProducts(); d.dismiss() }
        }
    }

    private fun unificarProductos(e: ProductEntity, p: ProductEntity?, code: String, s: Int, c: Double, sp: Double, exp: Long, ph: String?, loc: String, desc: String, d: AlertDialog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val codes = e.codigo?.split(",")?.toMutableList() ?: mutableListOf()
            code.split(",").forEach { if (it.isNotBlank() && !codes.contains(it)) codes.add(it) }
            e.apply { codigo = codes.joinToString(","); stock += s; precioCosto += c; precioVenta = sp; photoPath = ph; location = loc; descripcion = desc; isSynced = false }
            database.productDao().update(e)
            if (p != null && p.id != e.id) { p.isDeleted = true; p.isSynced = false; database.productDao().update(p) }
            SyncManager(this@InventarioActivity).scheduleOfflineSync()
            withContext(Dispatchers.Main) { loadProducts(); d.dismiss() }
        }
    }

    private fun showProductLabel(p: ProductEntity) {
        val db = DialogViewLabelBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        db.tvLabelProdName.text = p.nombre
        db.tvLabelProdDesc.text = p.descripcion ?: "Sin descripción."

        if (!p.photoPath.isNullOrEmpty()) {
            try {
                val uri = if (p.photoPath.startsWith("/")) android.net.Uri.fromFile(File(p.photoPath)) else android.net.Uri.parse(p.photoPath)
                db.ivLabelProdPhoto.setImageURI(uri)
                db.ivLabelProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                db.ivLabelProdPhoto.alpha = 1.0f
            } catch (e: Exception) {}
        }

        val content = if (!p.codigo.isNullOrBlank()) p.codigo!!.split(",")[0] else p.nombre
        try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 500, 500)
            val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.RGB_565)
            for (x in 0 until 500) for (y in 0 until 500) bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            db.ivProductCode.setImageBitmap(bitmap)
            db.btnShareLabel.setOnClickListener { shareLabelBitmap(bitmap, p.nombre) }
        } catch (e: Exception) {}

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

    private fun startVoiceRecognition(targetFieldId: Int) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        // Feedback táctil al iniciar
        try {
            val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {}

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES") // Forzar español estándar
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@InventarioActivity, "Escuchando...", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("Speech", "Error detectado: $error")
                if (error == 12 || error == 5) {
                    showVoiceErrorDialog() // Si no hay idioma o el servicio falla, mostrar la guía
                } else {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NETWORK -> "Sin conexión a internet"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No te escuché bien"
                        else -> "Voz no disponible momentáneamente"
                    }
                    Toast.makeText(this@InventarioActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    when (targetFieldId) {
                        1 -> binding.etSearchInventario.setText(text)
                        2 -> currentDialogBinding?.etProdNombre?.setText(text)
                        3 -> currentDialogBinding?.etProdDescripcion?.setText(text)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun generarCodigoAutomatico(db: DialogAddProductBinding, codes: MutableList<String>, onRefresh: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = database.productDao().allProducts
            var max = 0
            all.forEach { p -> p.codigo?.split(",")?.forEach { it.trim().toIntOrNull()?.let { n -> if(n > max) max = n } } }
            val next = String.format(Locale.getDefault(), "%03d", max + 1)
            withContext(Dispatchers.Main) { if(!codes.contains(next)) { codes.add(next); onRefresh() } }
        }
    }

    private fun showDeleteConfirmation(p: ProductEntity) {
        AlertDialog.Builder(this).setTitle("Eliminar").setMessage("¿Borrar ${p.nombre}?").setPositiveButton("Sí") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) { 
                p.isDeleted = true
                p.isSynced = false
                database.productDao().update(p)
                SyncManager(this@InventarioActivity).scheduleOfflineSync()
                withContext(Dispatchers.Main) { loadProducts() } 
            }
        }.setNegativeButton("No", null).show()
    }

    private fun showVoiceErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("Configuración de Voz")
            .setMessage("Para usar el micrófono sin internet, Nexur necesita que el paquete de idioma esté descargado.\n\n¿Deseas activarlo ahora?")
            .setPositiveButton("Sí, Activar") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.setClassName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.settings_v2.SettingsRootActivity")
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Luego", null)
            .show()
    }

    private fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
