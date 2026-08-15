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
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.naxor.app.adapter.ProductAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityInventarioBinding
import com.naxor.app.databinding.DialogAddProductBinding
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
            if (!isGranted) Toast.makeText(this, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show()
        }
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)

        setupRecyclerView()
        setupListeners()
        setupSyncIndicator()
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
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Error: No has iniciado sesión", Toast.LENGTH_LONG).show()
            return
        }
        
        val testData = mapOf("test_time" to System.currentTimeMillis(), "msg" to "Prueba de conexión desde Nexur")
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("test").add(testData)
            .addOnSuccessListener {
                Toast.makeText(this, "¡Éxito! Firebase recibió la prueba", Toast.LENGTH_LONG).show()
                Log.d("FirebaseTest", "Prueba escrita con éxito")
            }
            .addOnFailureListener { e ->
                AlertDialog.Builder(this)
                    .setTitle("Error de Conexión")
                    .setMessage("Firebase rechazó la prueba: ${e.message}")
                    .setPositiveButton("OK", null).show()
                Log.e("FirebaseTest", "Fallo en prueba: ${e.message}")
            }
    }

    private fun forceFullUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val products = database.productDao().allProducts
            products.forEach { it.isSynced = false; database.productDao().update(it) }
            val sales = database.saleDao().allSales
            sales.forEach { it.isSynced = false; database.saleDao().update(it) }
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
        binding.btnExitEditorMode.setOnClickListener { toggleEditorMode(false) }

        binding.etSearchInventario.addTextChangedListener { text -> 
            currentSearchQuery = text.toString()
            loadProducts() 
            updateControlsLayout(currentSearchQuery.isNotEmpty() || binding.etSearchInventario.hasFocus())
        }

        binding.etSearchInventario.setOnFocusChangeListener { _, hasFocus ->
            updateControlsLayout(currentSearchQuery.isNotEmpty() || hasFocus)
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
            set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 8)
            set.connect(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.setMargin(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 8)
            set.connect(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
            set.setMargin(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 8)
            binding.btnFilterCategory.text = if (currentCategory == "Todos") "Categoría" else currentCategory
            binding.btnSortAttribute.text = currentSortAttribute
        } else {
            set.connect(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END, binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.START)
            set.setMargin(binding.layoutVentaBusqueda.id, androidx.constraintlayout.widget.ConstraintSet.END, 8)
            set.connect(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.setMargin(binding.btnFilterCategory.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            set.connect(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.setMargin(binding.btnSortAttribute.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            set.connect(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            set.setMargin(binding.btnSortOrder.id, androidx.constraintlayout.widget.ConstraintSet.TOP, 0)
            binding.btnFilterCategory.text = "Cat"
            binding.btnSortAttribute.text = "Nom"
        }
        androidx.transition.TransitionManager.beginDelayedTransition(binding.layoutControlsContainer, androidx.transition.AutoTransition().apply { duration = 200 })
        set.applyTo(binding.layoutControlsContainer)
    }

    private fun showMultiDeleteConfirmation() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Eliminar").setMessage("¿Borrar ${selected.size} productos?").setPositiveButton("Eliminar") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                selected.forEach { database.productDao().delete(it); SyncManager(this@InventarioActivity).deleteProductFromCloud(it.id) }
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

    private fun toggleSortOrder() { isAscending = !isAscending; loadProducts() }
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
            h.findViewById<TextView>(R.id.tvDrawerValorTotal)?.text = String.format("S/ %.2f", v)
            h.findViewById<TextView>(R.id.tvDrawerInversionTotal)?.text = String.format("S/ %.2f", i)
        } catch (e: Exception) {}
    }

    private fun showProductDialog(p: ProductEntity? = null) {
        val db = DialogAddProductBinding.inflate(layoutInflater); currentDialogBinding = db
        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        if (p != null) {
            db.etProdNombre.setText(p.nombre); db.etProdDescripcion.setText(p.descripcion); db.autoProdCategoria.setText(p.categoria, false); db.etProdStock.setText(p.stock.toString()); db.etProdVenta.setText(p.precioVenta.toString())
        }
        db.btnConfirmAdd.setOnClickListener {
            val name = db.etProdNombre.text.toString().trim(); val cat = db.autoProdCategoria.text.toString().trim()
            if (name.isNotBlank() && cat.isNotBlank()) {
                saveOrUpdateProduct(p, p?.codigo ?: "", name, cat, db.etProdStock.text.toString().toIntOrNull() ?: 0, 0.0, db.etProdVenta.text.toString().toDoubleOrNull() ?: 0.0, 0, p?.photoPath, "", db.etProdDescripcion.text.toString(), dialog)
            } else Toast.makeText(this, "Nombre y categoría requeridos", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun saveOrUpdateProduct(p: ProductEntity?, code: String, name: String, cat: String, stock: Int, cost: Double, sale: Double, exp: Long, photo: String?, loc: String, desc: String, d: AlertDialog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val syncManager = SyncManager(this@InventarioActivity)
            val productToSync = if (p != null) {
                p.apply { codigo = code; nombre = name; categoria = cat; this.stock = stock; precioVenta = sale; photoPath = photo; descripcion = desc; isSynced = false }
                database.productDao().update(p)
                p
            } else {
                val newP = ProductEntity(code, name, cat, stock, cost, sale).apply { photoPath = photo; descripcion = desc; isSynced = false }
                database.productDao().insert(newP)
                newP
            }
            
            // Intento de subida inmediata
            syncManager.syncProductToCloud(productToSync)
            
            // Programar para el futuro por si fallÃ³ o no hay red
            syncManager.scheduleOfflineSync()
            
            withContext(Dispatchers.Main) { loadProducts(); d.dismiss() }
        }
    }

    private fun showProductLabel(p: ProductEntity) {
        val db = com.naxor.app.databinding.DialogViewLabelBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(db.root).create()
        db.tvLabelProdName.text = p.nombre
        db.tvLabelProdDesc.text = p.descripcion ?: "Sin descripción."
        val content = if (!p.codigo.isNullOrBlank()) p.codigo!!.split(",")[0] else p.nombre
        val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 500, 500)
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.RGB_565)
        for (x in 0 until 500) for (y in 0 until 500) bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        db.ivProductCode.setImageBitmap(bitmap)
        db.btnCloseLabel.setOnClickListener { d.dismiss() }
        d.show()
    }

    private fun shareLabelBitmap(b: Bitmap, n: String) {}
    private fun generarCodigoAutomatico(db: DialogAddProductBinding, c: MutableList<String>, r: () -> Unit) {}
}
