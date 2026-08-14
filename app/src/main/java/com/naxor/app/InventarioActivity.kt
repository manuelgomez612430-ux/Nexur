package com.naxor.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.naxor.app.adapter.ProductAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityInventarioBinding
import com.naxor.app.databinding.DialogAddProductBinding
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.naxor.app.databinding.DialogViewLabelBinding
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage

class InventarioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventarioBinding
    private lateinit var adapter: ProductAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentDialogBinding: DialogAddProductBinding? = null
    private var photoUri: android.net.Uri? = null
    private var currentPhotoPath: String? = null
    private var currentSortAttribute = "NOMBRE"
    private var isAscending = true
    private var currentCategory = "Todos"
    private var currentSearchQuery = ""

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportInventoryToUri(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importInventoryFromUri(it) }
    }

    private val takeProductPhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoPath != null) {
            currentDialogBinding?.ivDialogProdPhoto?.let {
                it.setImageURI(android.net.Uri.parse(currentPhotoPath))
                it.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                it.setPadding(0, 0, 0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventarioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnFilterCategory.text = "📂 Todos"
        binding.btnSortAttribute.text = "🔤 Nombre"

        setupRecyclerView()
        setupListeners()
        loadProducts()
    }

    private fun setupRecyclerView() {
        adapter = ProductAdapter(
            items = emptyList(),
            onEdit = { product -> showProductDialog(product) },
            onViewLabel = { product -> showProductLabel(product) }
        )
        binding.rvInventario.layoutManager = LinearLayoutManager(this)
        binding.rvInventario.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackInventario.setOnClickListener { finish() }
        binding.btnHelpInventario.setOnClickListener { showHelpDialog() }
        binding.fabAddProducto.setOnClickListener { showProductDialog() }
        
        binding.etSearchInventario.addTextChangedListener { text -> 
            currentSearchQuery = text.toString()
            loadProducts() 
        }
        
        binding.btnFilterCategory.setOnClickListener { showCategorySelector() }
        binding.btnSortAttribute.setOnClickListener { showSortAttributeSelector() }
        binding.btnSortOrder.setOnClickListener { toggleSortOrder() }

        binding.btnOpenMenuInventario.setOnClickListener { binding.drawerLayoutInventario.openDrawer(GravityCompat.END) }

        binding.navigationViewInventario.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_export_excel -> {
                    val dateStr = SimpleDateFormat("dd_MM_yyyy", Locale.getDefault()).format(Date())
                    exportLauncher.launch("Inventario_$dateStr.csv")
                    binding.drawerLayoutInventario.closeDrawer(GravityCompat.END)
                    true
                }
                R.id.menu_import_excel -> {
                    importLauncher.launch("text/*")
                    binding.drawerLayoutInventario.closeDrawer(GravityCompat.END)
                    true
                }
                else -> false
            }
        }
    }

    private fun importInventoryFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = inputStream.bufferedReader()
                    reader.readLine() // Saltar cabecera
                    var line = reader.readLine()
                    var count = 0
                    while (line != null) {
                        val parts = line.split(",")
                        if (parts.size >= 6) {
                            val newP = ProductEntity(
                                parts[0], // codigo
                                parts[1], // nombre
                                parts[2], // categoria
                                parts[3].toIntOrNull() ?: 0, // stock
                                parts[4].toDoubleOrNull() ?: 0.0, // costo
                                parts[5].toDoubleOrNull() ?: 0.0  // venta
                            )
                            database.productDao().insert(newP)
                            count++
                        }
                        line = reader.readLine()
                    }
                    withContext(Dispatchers.Main) { 
                        loadProducts()
                        Toast.makeText(this@InventarioActivity, "¡Se recuperaron $count productos!", Toast.LENGTH_LONG).show() 
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@InventarioActivity, "Error al importar", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showCategorySelector() {
        lifecycleScope.launch {
            val dbCategories = withContext(Dispatchers.IO) { database.productDao().uniqueCategories }
            val popup = androidx.appcompat.widget.PopupMenu(this@InventarioActivity, binding.btnFilterCategory)
            
            popup.menu.add(0, 0, 0, "📂 Todos").apply { if(currentCategory == "Todos") isEnabled = false }
            popup.menu.add(0, 1, 1, "⚠️ Agotados").apply { if(currentCategory == "Agotados") isEnabled = false }
            
            dbCategories.forEachIndexed { index, cat ->
                popup.menu.add(1, index + 2, index + 2, "📁 $cat").apply {
                    if(currentCategory == cat) { title = "✓ $cat"; isEnabled = false }
                }
            }

            popup.setOnMenuItemClickListener { item ->
                currentCategory = when (item.groupId) {
                    0 -> if (item.itemId == 0) "Todos" else "Agotados"
                    else -> item.title.toString().replace("✓ ", "").replace("📁 ", "")
                }
                binding.btnFilterCategory.text = when(currentCategory) {
                    "Todos" -> "📂 Todos"
                    "Agotados" -> "⚠️ Agotados"
                    else -> "📁 $currentCategory"
                }
                loadProducts()
                true
            }
            popup.show()
        }
    }

    private fun showSortAttributeSelector() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnSortAttribute)
        val menuItems = mapOf("NOMBRE" to "🔤 Nombre", "STOCK" to "📦 Stock", "PRECIO" to "💰 Precio", "FECHA" to "📅 Fecha")
        menuItems.forEach { (id, title) ->
            popup.menu.add(0, 0, 0, title).apply {
                if (currentSortAttribute == id) { this.title = "✓ $title"; isEnabled = false }
                intent = Intent().apply { putExtra("attr_id", id) }
            }
        }
        popup.setOnMenuItemClickListener { item ->
            currentSortAttribute = item.intent?.getStringExtra("attr_id") ?: "NOMBRE"
            binding.btnSortAttribute.text = item.title.toString().replace("✓ ", "")
            loadProducts()
            true
        }
        popup.show()
    }

    private fun toggleSortOrder() {
        isAscending = !isAscending
        binding.btnSortOrder.setIconResource(if (isAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
        loadProducts()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Inventario")
            .setMessage("• REGISTRO: Usa '+' para añadir. Escanea códigos de barras con el icono de cámara.\n" +
                    "• RESUMEN: Mira el valor total de tu mercadería y tu inversión real en la barra lateral.")
            .setPositiveButton("Entendido", null).show()
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            var list = withContext(Dispatchers.IO) {
                if (currentSearchQuery.isNotBlank()) {
                    database.productDao().searchProducts("%$currentSearchQuery%")
                } else {
                    when (currentCategory) {
                        "Todos" -> database.productDao().allProducts
                        "Agotados" -> database.productDao().getAgotados()
                        else -> database.productDao().getProductsByCategory(currentCategory)
                    }
                }
            }

            if (currentSearchQuery.isNotBlank() && currentCategory != "Todos") {
                list = if (currentCategory == "Agotados") list.filter { it.stock <= 0 } else list.filter { it.categoria == currentCategory }
            }

            list = when (currentSortAttribute) {
                "NOMBRE" -> if (isAscending) list.sortedBy { it.nombre.lowercase() } else list.sortedByDescending { it.nombre.lowercase() }
                "STOCK" -> if (isAscending) list.sortedBy { it.stock } else list.sortedByDescending { it.stock }
                "PRECIO" -> if (isAscending) list.sortedBy { it.precioVenta } else list.sortedByDescending { it.precioVenta }
                "FECHA" -> if (isAscending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp }
                else -> list.sortedBy { it.nombre.lowercase() }
            }

            adapter.updateList(list)
            
            // AUTO-SCROLL AL INICIO
            if (list.isNotEmpty()) {
                binding.rvInventario.scrollToPosition(0)
            }
            
            val inversionTotal = list.sumOf { it.precioCosto }
            val valorVentaTotal = list.sumOf { it.stock * it.precioVenta }
            updateDrawerHeader(valorVentaTotal, inversionTotal)
        }
    }

    private fun updateDrawerHeader(valorTotal: Double, inversion: Double) {
        try {
            val headerView = binding.navigationViewInventario.getHeaderView(0)
            val tvValor = headerView.findViewById<TextView>(R.id.tvDrawerValorTotal)
            val tvInversion = headerView.findViewById<TextView>(R.id.tvDrawerInversionTotal)
            tvValor?.text = String.format(Locale.getDefault(), "S/ %.2f", valorTotal)
            tvInversion?.text = String.format(Locale.getDefault(), "S/ %.2f", inversion)
        } catch (e: Exception) {}
    }

    private fun showProductDialog(product: ProductEntity? = null) {
        val dialogBinding = DialogAddProductBinding.inflate(LayoutInflater.from(this))
        currentDialogBinding = dialogBinding
        var selectedExpiration: Long = 0
        var photoPath: String? = product?.photoPath
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val addedCodes = mutableListOf<String>()

        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(dialogBinding.root).create()

        fun refreshCodesUIOnly() {
            dialogBinding.layoutCodesList.removeAllViews()
            for (code in addedCodes) {
                val itemView = LayoutInflater.from(this@InventarioActivity).inflate(android.R.layout.simple_list_item_1, dialogBinding.layoutCodesList, false) as TextView
                itemView.text = "• $code (Quitar)"
                itemView.setOnClickListener { 
                    addedCodes.remove(code)
                    val currentVal = dialogBinding.etProdStock.text.toString().toIntOrNull() ?: 0
                    if (currentVal > 0) dialogBinding.etProdStock.setText((currentVal - 1).toString())
                    refreshCodesUIOnly() 
                }
                dialogBinding.layoutCodesList.addView(itemView)
            }
        }

        fun refreshUI() {
            lifecycleScope.launch(Dispatchers.IO) {
                var duplicateFound: ProductEntity? = null
                for (code in addedCodes) {
                    val existing = database.productDao().getProductByCode(code)
                    if (existing != null && existing.id != (product?.id ?: -1)) {
                        duplicateFound = existing
                        break
                    }
                }
                val currentTextCode = dialogBinding.etProdCodigo.text.toString().trim()
                if (duplicateFound == null && currentTextCode.isNotEmpty()) {
                    val existing = database.productDao().getProductByCode(currentTextCode)
                    if (existing != null && existing.id != (product?.id ?: -1)) duplicateFound = existing
                }

                withContext(Dispatchers.Main) {
                    refreshCodesUIOnly()
                    if (duplicateFound != null) {
                        dialogBinding.tvAlertStockDialog.text = "⚠️ El código pertenece a: ${duplicateFound.nombre}"
                        dialogBinding.tvAlertStockDialog.visibility = View.VISIBLE
                        dialogBinding.btnConfirmAdd.isEnabled = false
                        dialogBinding.btnConfirmAdd.alpha = 0.5f
                    } else {
                        dialogBinding.btnConfirmAdd.isEnabled = true
                        dialogBinding.btnConfirmAdd.alpha = 1.0f
                        val stockInput = dialogBinding.etProdStock.text.toString().toIntOrNull() ?: 0
                        actualizarAlertaStockDialog(dialogBinding, stockInput)
                    }
                }
            }
        }

        if (product != null) {
            dialogBinding.tvDialogTitle.text = "Editar producto"
            dialogBinding.btnDeleteProduct.visibility = View.VISIBLE
            dialogBinding.btnDeleteProduct.setOnClickListener { showDeleteConfirmation(product); dialog.dismiss() }
            dialogBinding.etProdNombre.setText(product.nombre)
            dialogBinding.etProdDescripcion.setText(product.descripcion) // CARGAR DESCRIPCIÓN
            dialogBinding.autoProdCategoria.setText(product.categoria, false)
            dialogBinding.etProdStock.setText(product.stock.toString())
            if (!product.location.isNullOrEmpty()) { dialogBinding.cbShowLocation.isChecked = true; dialogBinding.layoutProdLocation.visibility = View.VISIBLE; dialogBinding.etProdLocation.setText(product.location) }
            if (!product.photoPath.isNullOrEmpty()) { dialogBinding.ivDialogProdPhoto.setImageURI(android.net.Uri.parse(product.photoPath)); dialogBinding.ivDialogProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; dialogBinding.ivDialogProdPhoto.setPadding(0, 0, 0, 0) }
            if (product.precioCosto > 0) { dialogBinding.cbShowBatchCost.isChecked = true; dialogBinding.layoutProdCosto.visibility = View.VISIBLE; dialogBinding.etProdCosto.setText(product.precioCosto.toString()) }
            dialogBinding.etProdVenta.setText(product.precioVenta.toString())
            product.codigo?.split(",")?.filter { it.isNotBlank() }?.let { addedCodes.addAll(it) }
            refreshCodesUIOnly() 
            if (product.expirationDate > 0) { selectedExpiration = product.expirationDate; dialogBinding.cbShowExpiration.isChecked = true; dialogBinding.layoutProdExpiration.visibility = View.VISIBLE; dialogBinding.etProdExpiration.setText(sdf.format(Date(product.expirationDate))) }
            dialogBinding.btnConfirmAdd.text = "Actualizar"
        }

        dialogBinding.cbShowBatchCost.setOnCheckedChangeListener { _, isChecked -> dialogBinding.layoutProdCosto.visibility = if (isChecked) View.VISIBLE else View.GONE }
        dialogBinding.cbShowExpiration.setOnCheckedChangeListener { _, isChecked -> dialogBinding.layoutProdExpiration.visibility = if (isChecked) View.VISIBLE else View.GONE }
        dialogBinding.cbShowLocation.setOnCheckedChangeListener { _, isChecked -> dialogBinding.layoutProdLocation.visibility = if (isChecked) View.VISIBLE else View.GONE }
        dialogBinding.cardProdPhoto.setOnClickListener {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "prod_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            currentPhotoPath = file.absolutePath
            photoPath = currentPhotoPath
            takeProductPhotoLauncher.launch(uri)
        }

        dialogBinding.layoutProdCodigo.setEndIconOnClickListener {
            val scanner = GmsBarcodeScanning.getClient(this)
            scanner.startScan().addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                if (code.isNotEmpty()) {
                    if (!addedCodes.contains(code)) {
                        addedCodes.add(code)
                        val currentVal = dialogBinding.etProdStock.text.toString().toIntOrNull() ?: 0
                        dialogBinding.etProdStock.setText((currentVal + 1).toString())
                        refreshUI()
                    }
                }
            }
        }

        // FIX: BOTÓN + PARA GENERAR CÓDIGO
        dialogBinding.layoutProdCodigo.setStartIconOnClickListener {
            generarCodigoAutomatico(dialogBinding, addedCodes) {
                val currentVal = dialogBinding.etProdStock.text.toString().toIntOrNull() ?: 0
                dialogBinding.etProdStock.setText((currentVal + 1).toString())
                refreshUI()
            }
        }

        // FIX: BOTÓN CANCELAR
        dialogBinding.btnCancelAdd.setOnClickListener { dialog.dismiss() }

        // --- CONTROL DEFINITIVO: SIEMPRE ABAJO Y MANUAL ---
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

        fun mostrarCategoriasAbajo() {
            imm.hideSoftInputFromWindow(dialogBinding.autoProdCategoria.windowToken, 0)
            dialogBinding.autoProdCategoria.clearFocus()
            dialogBinding.autoProdCategoria.postDelayed({
                // Forzar hacia abajo con offset y limitando altura del dropdown
                dialogBinding.autoProdCategoria.setDropDownVerticalOffset(5)
                dialogBinding.autoProdCategoria.showDropDown()
            }, 250) 
        }

        dialogBinding.layoutProdCategoria.setEndIconOnClickListener {
            val oldThreshold = dialogBinding.autoProdCategoria.threshold
            dialogBinding.autoProdCategoria.threshold = 0
            mostrarCategoriasAbajo()
            dialogBinding.autoProdCategoria.postDelayed({
                dialogBinding.autoProdCategoria.threshold = oldThreshold
            }, 600)
        }

        dialogBinding.autoProdCategoria.setOnClickListener {
            // Solo abrimos si escribió algo (suplanta el threshold 1 visualmente)
            if (dialogBinding.autoProdCategoria.text.isNotEmpty()) {
                dialogBinding.autoProdCategoria.showDropDown()
            }
        }

        lifecycleScope.launch {
            val dbCategories = withContext(Dispatchers.IO) { database.productDao().uniqueCategories }
            val catAdapter = ArrayAdapter(this@InventarioActivity, android.R.layout.simple_dropdown_item_1line, dbCategories)
            dialogBinding.autoProdCategoria.setAdapter(catAdapter)
            dialogBinding.autoProdCategoria.threshold = 1 
        }

        dialogBinding.etProdCodigo.addTextChangedListener { refreshUI() }

        dialogBinding.btnConfirmAdd.setOnClickListener {
            val name = dialogBinding.etProdNombre.text.toString().trim()
            val desc = dialogBinding.etProdDescripcion.text.toString().trim() // CAPTURAR DESCRIPCIÓN
            val cat = dialogBinding.autoProdCategoria.text.toString().trim()
            val stock = dialogBinding.etProdStock.text.toString().toIntOrNull() ?: 0
            val cost = if (dialogBinding.cbShowBatchCost.isChecked) dialogBinding.etProdCosto.text.toString().toDoubleOrNull() ?: 0.0 else 0.0
            val sale = dialogBinding.etProdVenta.text.toString().toDoubleOrNull() ?: 0.0
            val loc = dialogBinding.etProdLocation.text.toString().trim()
            val codes = addedCodes.joinToString(",")

            if (name.isNotBlank() && cat.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val existingByName = database.productDao().getProductByName(name)
                    val currentId = product?.id ?: -1
                    withContext(Dispatchers.Main) {
                        if (existingByName != null && existingByName.id != currentId) {
                            AlertDialog.Builder(this@InventarioActivity)
                                .setTitle("Producto existente")
                                .setMessage("Ya existe un producto con el nombre '$name'. ¿Deseas unificar los códigos y sumar el stock?")
                                .setPositiveButton("Sí, Unificar") { _, _ ->
                                    unificarProductos(existingByName, product, codes, stock, cost, sale, selectedExpiration, photoPath, loc, desc, dialog)
                                }
                                .setNegativeButton("No", null)
                                .show()
                        } else {
                            saveOrUpdateProduct(product, codes, name, cat, stock, cost, sale, selectedExpiration, photoPath, loc, desc, dialog)
                        }
                    }
                }
            } else Toast.makeText(this, "Nombre y categoría obligatorios", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 1.0).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun saveOrUpdateProduct(product: ProductEntity?, code: String, name: String, cat: String, stock: Int, cost: Double, sale: Double, exp: Long, photo: String?, location: String, desc: String, dialog: AlertDialog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val finalProduct = if (product != null) {
                product.apply { codigo = code; nombre = name; categoria = cat; this.stock = stock; precioCosto = cost; precioVenta = sale; expirationDate = exp; photoPath = photo; this.location = location; descripcion = desc }
                database.productDao().update(product)
                product
            } else {
                val newP = ProductEntity(code, name, cat, stock, cost, sale).apply { expirationDate = exp; photoPath = photo; this.location = location; descripcion = desc }
                database.productDao().insert(newP)
                newP
            }
            
            // SINCRONIZAR A LA NUBE
            SyncManager(this@InventarioActivity).syncProductToCloud(finalProduct)
            
            withContext(Dispatchers.Main) { loadProducts(); dialog.dismiss(); Toast.makeText(this@InventarioActivity, "Guardado y Sincronizado", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun unificarProductos(existing: ProductEntity, productToDelete: ProductEntity?, newCode: String, newStock: Int, newCost: Double, newSalePrice: Double, newExp: Long, photo: String?, location: String, desc: String, dialog: AlertDialog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentCodes = existing.codigo?.split(",")?.map { it.trim() }?.toMutableList() ?: mutableListOf()
            val newCodes = newCode.split(",").map { it.trim() }
            for (c in newCodes) if (c.isNotEmpty() && !currentCodes.contains(c)) currentCodes.add(c)
            existing.codigo = currentCodes.joinToString(",")
            existing.stock += newStock
            existing.precioCosto += newCost
            existing.precioVenta = newSalePrice
            if (newExp > 0) existing.expirationDate = newExp
            if (!photo.isNullOrEmpty()) existing.photoPath = photo
            if (location.isNotEmpty()) existing.location = location
            if (desc.isNotEmpty()) existing.descripcion = desc

            database.productDao().update(existing)
            if (productToDelete != null && productToDelete.id != existing.id) database.productDao().delete(productToDelete)

            withContext(Dispatchers.Main) { loadProducts(); dialog.dismiss(); Toast.makeText(this@InventarioActivity, "¡Producto unificado!", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun exportInventoryToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csv = StringBuilder("Código,Producto,Categoría,Stock,Inversión Lote,Precio Venta Unitario,Valor Venta Total\n")
                for (p in database.productDao().allProducts) {
                    val valorVentaTotal = p.stock * p.precioVenta
                    csv.append("${p.codigo},${p.nombre},${p.categoria},${p.stock},${p.precioCosto},${p.precioVenta},$valorVentaTotal\n")
                }
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toString().toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(this@InventarioActivity, "Inventario Exportado a Excel (CSV)", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showProductLabel(product: ProductEntity) {
        val dialogBinding = com.naxor.app.databinding.DialogViewLabelBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this, R.style.Theme_Naxor_Dialog).setView(dialogBinding.root).create()
        
        dialogBinding.tvLabelProdName.text = product.nombre
        dialogBinding.tvLabelProdDesc.text = if (!product.descripcion.isNullOrEmpty()) product.descripcion else "Sin descripción disponible."
        
        // 1. Mostrar Foto (Si existe)
        if (!product.photoPath.isNullOrEmpty()) {
            try {
                val uri = if (product.photoPath.startsWith("/")) android.net.Uri.fromFile(File(product.photoPath)) else android.net.Uri.parse(product.photoPath)
                dialogBinding.ivLabelProdPhoto.setImageURI(uri)
                dialogBinding.ivLabelProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                dialogBinding.ivLabelProdPhoto.alpha = 1.0f
            } catch (e: Exception) {}
        }

        // 2. Generar QR
        val content = if (!product.codigo.isNullOrBlank()) product.codigo!!.split(",")[0] else product.nombre
        val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 500, 500)
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.RGB_565)
        for (x in 0 until 500) for (y in 0 until 500) bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        dialogBinding.ivProductCode.setImageBitmap(bitmap)

        // 3. Códigos vinculados
        val codes = product.codigo?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        dialogBinding.tvLabelProdCode.text = if (codes.isNotEmpty()) codes.joinToString("\n") { "• $it" } else "---"
        
        dialogBinding.cbShowLinkedCodes.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.cardLinkedCodes.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 4. Compartir (Fix)
        dialogBinding.btnShareLabel.setOnClickListener {
            shareLabelBitmap(bitmap, product.nombre)
        }

        dialogBinding.btnCloseLabel.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 1.0).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun generarCodigoAutomatico(dialogBinding: DialogAddProductBinding, addedCodes: MutableList<String>, onRefresh: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = database.productDao().allProducts
            var max = 0
            all.forEach { p -> p.codigo?.split(",")?.forEach { it.trim().toIntOrNull()?.let { n -> if(n>max) max=n } } }
            val next = String.format(Locale.getDefault(), "%03d", max + 1)
            withContext(Dispatchers.Main) { if(!addedCodes.contains(next)) { addedCodes.add(next); onRefresh() } }
        }
    }

    private fun shareLabelBitmap(bitmap: Bitmap, name: String) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Ficha_$name.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir Ficha del Producto"))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showDeleteConfirmation(p: ProductEntity) {
        AlertDialog.Builder(this).setTitle("Eliminar").setMessage("¿Borrar ${p.nombre}?").setPositiveButton("Sí") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) { 
                database.productDao().delete(p)
                SyncManager(this@InventarioActivity).deleteProductFromCloud(p.id)
                withContext(Dispatchers.Main) { loadProducts() } 
            }
        }.setNegativeButton("No", null).show()
    }

    private fun actualizarAlertaStockDialog(b: DialogAddProductBinding, stock: Int) {
        b.tvAlertStockDialog.visibility = if (stock <= 5) View.VISIBLE else View.GONE
        b.tvAlertStockDialog.text = if (stock <= 0) "AGOTADO" else "Pocas unidades ($stock)"
    }
}
