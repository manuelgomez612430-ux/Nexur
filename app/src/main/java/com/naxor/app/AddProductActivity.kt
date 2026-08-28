package com.naxor.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.MovementLogEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityAddProductBinding
import com.naxor.app.util.VoiceRecognitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AddProductActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProductBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentProduct: ProductEntity? = null
    private val addedCodes = mutableListOf<String>()
    private var currentPhotoPath: String? = null
    private val voiceHelper by lazy { VoiceRecognitionHelper(this) }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoPath != null) {
            binding.ivDialogProdPhoto.setImageURI(Uri.parse(currentPhotoPath))
            binding.ivDialogProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.ivDialogProdPhoto.setPadding(0, 0, 0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val productId = intent.getStringExtra("PRODUCT_ID")
        if (productId != null) {
            loadProduct(productId)
        }

        setupListeners()
        setupCategories()
    }

    private fun loadProduct(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val p = database.productDao().allProducts.find { it.id == id }
            withContext(Dispatchers.Main) {
                if (p != null) {
                    currentProduct = p
                    binding.tvAddProductTitle.text = "Editar Producto"
                    binding.etProdNombre.setText(p.nombre)
                    binding.etProdDescripcion.setText(p.descripcion)
                    binding.autoProdCategoria.setText(p.categoria, false)
                    binding.etProdStock.setText(p.stock.toString())
                    binding.etProdVenta.setText(p.precioVenta.toString())
                    p.codigo?.split(",")?.filter { it.isNotBlank() }?.let { addedCodes.addAll(it) }
                    refreshCodesUI()
                    
                    if (!p.location.isNullOrEmpty()) {
                        binding.cbShowLocation.isChecked = true
                        binding.layoutProdLocation.visibility = View.VISIBLE
                        binding.etProdLocation.setText(p.location)
                    }
                    if (p.precioCosto > 0) {
                        binding.cbShowBatchCost.isChecked = true
                        binding.layoutProdCostoContainer.visibility = View.VISIBLE
                        binding.etProdCosto.setText(p.precioCosto.toString())
                    }
                    if (p.expirationDate > 0) {
                        binding.cbShowExpiration.isChecked = true
                        binding.layoutProdExpiration.visibility = View.VISIBLE
                        binding.etProdExpiration.setText(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(p.expirationDate)))
                    }
                    if (!p.photoPath.isNullOrEmpty()) {
                        binding.ivDialogProdPhoto.setImageURI(Uri.parse(p.photoPath))
                        binding.ivDialogProdPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        binding.ivDialogProdPhoto.setPadding(0, 0, 0, 0)
                        currentPhotoPath = p.photoPath
                    }
                    binding.btnDeleteProduct.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnBackAddProduct.setOnClickListener { finish() }
        
        binding.btnDeleteProduct.setOnClickListener {
            currentProduct?.let { showDeleteConfirmation(it) }
        }

        binding.cbShowBatchCost.setOnCheckedChangeListener { _, isChecked -> 
            binding.layoutProdCostoContainer.visibility = if (isChecked) View.VISIBLE else View.GONE 
        }
        binding.btnInfoCosto.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Qué es el costo?")
                .setMessage("Es lo que tú pagaste por el producto. El sistema lo usa para decirte cuánta ganancia real tienes.")
                .setPositiveButton("Entendido", null).show()
        }
        binding.cbShowExpiration.setOnCheckedChangeListener { _, isChecked -> 
            binding.layoutProdExpiration.visibility = if (isChecked) View.VISIBLE else View.GONE 
        }
        binding.cbShowLocation.setOnCheckedChangeListener { _, isChecked -> 
            binding.layoutProdLocation.visibility = if (isChecked) View.VISIBLE else View.GONE 
        }

        binding.cardProdPhoto.setOnClickListener {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "prod_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            currentPhotoPath = file.absolutePath
            takePhotoLauncher.launch(uri)
        }

        binding.btnScanCode.setOnClickListener {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .enableAutoZoom()
                .build()

            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode ->
                    barcode.rawValue?.let { code ->
                        validarYAgregarCodigo(code)
                    }
                }
        }

        binding.btnGenerateCode.setOnClickListener {
            generarCodigoAutomatico()
        }

        binding.layoutProdNombre.setEndIconOnClickListener {
            voiceHelper.startListening { text ->
                binding.etProdNombre.setText(text.replaceFirstChar { it.uppercase() })
            }
        }

        binding.etProdVenta.addTextChangedListener { updateProfitPreview() }
        binding.etProdCosto.addTextChangedListener { updateProfitPreview() }

        binding.btnConfirmAdd.setOnClickListener {
            saveProduct()
        }
    }

    private fun updateProfitPreview() {
        val venta = binding.etProdVenta.text.toString().toDoubleOrNull() ?: 0.0
        val costo = binding.etProdCosto.text.toString().toDoubleOrNull() ?: 0.0
        
        if (venta > 0 && costo > 0) {
            val ganancia = venta - costo
            val porcentaje = (ganancia / venta) * 100
            binding.cardProfitPreview.visibility = View.VISIBLE
            binding.tvProfitPreview.text = String.format(Locale.getDefault(), "Ganancia estimada: S/ %.2f (%.1f%%)", ganancia, porcentaje)
            
            if (ganancia < 0) {
                binding.cardProfitPreview.setCardBackgroundColor(getColor(R.color.red_700).let { android.content.res.ColorStateList.valueOf(it) })
                binding.tvProfitPreview.setTextColor(Color.WHITE)
            } else {
                binding.cardProfitPreview.setCardBackgroundColor(getColor(R.color.emerald_50).let { android.content.res.ColorStateList.valueOf(it) })
                binding.tvProfitPreview.setTextColor(getColor(R.color.emerald_600))
            }
        } else {
            binding.cardProfitPreview.visibility = View.GONE
        }
    }

    private fun setupCategories() {
        lifecycleScope.launch {
            val dbCategories = withContext(Dispatchers.IO) { database.productDao().uniqueCategories }
            val catAdapter = ArrayAdapter(this@AddProductActivity, android.R.layout.simple_dropdown_item_1line, dbCategories)
            binding.autoProdCategoria.setAdapter(catAdapter)
        }

        binding.layoutProdCategoria.setEndIconOnClickListener {
            binding.autoProdCategoria.showDropDown()
        }
    }

    private fun refreshCodesUI() {
        binding.layoutCodesPreview.removeAllViews()
        binding.tvEmptyCodesHint.visibility = if (addedCodes.isEmpty()) View.VISIBLE else View.GONE

        addedCodes.forEach { code ->
            val codeView = LayoutInflater.from(this).inflate(R.layout.item_code_preview, binding.layoutCodesPreview, false)
            val tvValue = codeView.findViewById<TextView>(R.id.tvCodeValue)
            val ivBarcode = codeView.findViewById<android.widget.ImageView>(R.id.ivCodeBarcode)
            val btnRemove = codeView.findViewById<android.view.View>(R.id.btnRemoveCode)

            tvValue.text = code
            
            // Generar imagen de barras (CODE_128 es el más legible)
            try {
                val bitMatrix = MultiFormatWriter().encode(code, BarcodeFormat.CODE_128, 600, 150)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                ivBarcode.setImageBitmap(bitmap)
            } catch (e: Exception) {
                ivBarcode.visibility = View.GONE
            }

            btnRemove.setOnClickListener {
                addedCodes.remove(code)
                refreshCodesUI()
            }

            binding.layoutCodesPreview.addView(codeView)
        }
    }

    private fun validarYAgregarCodigo(code: String) {
        if (addedCodes.contains(code)) {
            Toast.makeText(this, "Este código ya está en la lista", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val p = database.productDao().allProducts.find { it.codigo?.split(",")?.contains(code) == true }
            withContext(Dispatchers.Main) {
                if (p != null && p.id != (currentProduct?.id ?: "")) {
                    AlertDialog.Builder(this@AddProductActivity)
                        .setTitle("Código Duplicado")
                        .setMessage("Este código ya pertenece al producto:\n\n${p.nombre}")
                        .setPositiveButton("Entendido", null)
                        .show()
                    beepError()
                } else {
                    beep()
                    addedCodes.add(code)
                    refreshCodesUI()
                }
            }
        }
    }

    private fun saveProduct() {
        val name = binding.etProdNombre.text.toString().trim()
        val cat = binding.autoProdCategoria.text.toString().trim()
        
        if (name.isBlank() || cat.isNotBlank()) {
            val stock = binding.etProdStock.text.toString().toIntOrNull() ?: 0
            val sale = binding.etProdVenta.text.toString().toDoubleOrNull() ?: 0.0
            val cost = if (binding.cbShowBatchCost.isChecked) binding.etProdCosto.text.toString().toDoubleOrNull() ?: 0.0 else 0.0
            val loc = binding.etProdLocation.text.toString().trim()
            val desc = binding.etProdDescripcion.text.toString().trim()
            val codes = addedCodes.joinToString(",")

            lifecycleScope.launch(Dispatchers.IO) {
                val existing = database.productDao().getProductByName(name)
                withContext(Dispatchers.Main) {
                    if (existing != null && existing.id != (currentProduct?.id ?: "")) {
                        AlertDialog.Builder(this@AddProductActivity)
                            .setTitle("Producto existente")
                            .setMessage("¿Unificar stock con '$name'?")
                            .setPositiveButton("Sí") { _, _ -> 
                                unificarProductos(existing, currentProduct, codes, stock, cost, sale, currentPhotoPath, loc, desc) 
                            }
                            .setNegativeButton("No", null).show()
                    } else {
                        performSave(currentProduct, codes, name, cat, stock, cost, sale, currentPhotoPath, loc, desc)
                    }
                }
            }
        } else {
            Toast.makeText(this, "Nombre y categoría requeridos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSave(p: ProductEntity?, code: String, name: String, cat: String, stock: Int, cost: Double, sale: Double, photo: String?, loc: String, desc: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isNew = p == null
            val changes = mutableListOf<String>()
            
            if (p != null) {
                if (p.nombre != name) changes.add("Nombre")
                if (p.categoria != cat) changes.add("Categoría")
                if (p.stock != stock) changes.add("Stock (${p.stock} -> $stock)")
                if (p.precioVenta != sale) changes.add("Precio (S/ ${p.precioVenta} -> S/ $sale)")
                if (p.precioCosto != cost) changes.add("Costo")
                if (p.codigo != code) changes.add("Código")
            }

            val productToSync = if (p != null) {
                p.apply { codigo = code; nombre = name; categoria = cat; this.stock = stock; precioCosto = cost; precioVenta = sale; photoPath = photo; location = loc; descripcion = desc; isSynced = false }
                database.productDao().update(p)
                p
            } else {
                val newP = ProductEntity(code, name, cat, stock, cost, sale).apply { photoPath = photo; location = loc; descripcion = desc; isSynced = false }
                database.productDao().insert(newP)
                newP
            }

            val log = MovementLogEntity(
                type = if (isNew) "PRODUCT_CREATED" else "PRODUCT_UPDATED",
                title = if (isNew) "Nuevo Producto" else "Producto Modificado",
                description = if (isNew) "${productToSync.categoria}: ${productToSync.nombre}" else "En ${productToSync.nombre} se cambió: ${changes.joinToString(", ")}",
                value = if (isNew) "Stock: ${productToSync.stock}" else "Stock act: ${productToSync.stock}",
                colorHex = "#8E44AD",
                iconRes = android.R.drawable.ic_menu_edit
            )
            database.movementLogDao().insert(log)
            SyncManager(this@AddProductActivity).syncLogToCloud(log)
            SyncManager(this@AddProductActivity).syncProductToCloud(productToSync)
            SyncManager(this@AddProductActivity).scheduleOfflineSync()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddProductActivity, "Producto guardado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun unificarProductos(e: ProductEntity, p: ProductEntity?, code: String, s: Int, c: Double, sp: Double, ph: String?, loc: String, desc: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val oldStock = e.stock
            val codes = e.codigo?.split(",")?.toMutableList() ?: mutableListOf()
            code.split(",").forEach { if (it.isNotBlank() && !codes.contains(it)) codes.add(it) }
            e.apply { codigo = codes.joinToString(","); stock += s; precioCosto += c; precioVenta = sp; photoPath = ph; location = loc; descripcion = desc; isSynced = false }
            database.productDao().update(e)
            
            val log = MovementLogEntity(
                type = "PRODUCT_UPDATED",
                title = "Productos Unificados",
                description = "Se sumó stock a ${e.nombre}. Cambio: $oldStock -> ${e.stock}",
                value = "+$s uds",
                colorHex = "#8E44AD",
                iconRes = android.R.drawable.ic_menu_edit
            )
            database.movementLogDao().insert(log)
            val sm = SyncManager(this@AddProductActivity)
            sm.syncLogToCloud(log)
            sm.syncProductToCloud(e)

            if (p != null && p.id != e.id) { 
                p.isDeleted = true
                p.isSynced = false
                database.productDao().update(p)
                sm.deleteProductFromCloud(p.id)
            }
            sm.scheduleOfflineSync()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AddProductActivity, "Productos unificados", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun generarCodigoAutomatico() {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = database.productDao().allProducts
            var max = 0
            all.forEach { p -> 
                p.codigo?.split(",")?.forEach { part ->
                    val cleanCode = part.trim().filter { it.isDigit() }
                    if (cleanCode.isNotEmpty() && cleanCode.length <= 10) {
                        cleanCode.toIntOrNull()?.let { n -> if(n > max) max = n }
                    }
                }
            }
            
            // Generar un número de 13 dígitos para estándar
            val nextNum = max + 1
            val next = String.format(Locale.getDefault(), "775%010d", nextNum) 
            
            withContext(Dispatchers.Main) { 
                validarYAgregarCodigo(next)
            }
        }
    }

    private fun showDeleteConfirmation(p: ProductEntity) {
        AlertDialog.Builder(this).setTitle("Eliminar").setMessage("¿Borrar ${p.nombre}?").setPositiveButton("Sí") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) { 
                p.isDeleted = true
                p.isSynced = false
                database.productDao().update(p)
                
                val log = MovementLogEntity(
                    type = "PRODUCT_DELETED",
                    title = "Producto Eliminado",
                    description = p.nombre,
                    value = "ELIMINADO",
                    colorHex = "#E11D48", // Rojo vibrante
                    iconRes = android.R.drawable.ic_menu_delete,
                    timestamp = System.currentTimeMillis()
                )
                database.movementLogDao().insert(log)
                val sm = SyncManager(this@AddProductActivity)
                sm.syncLogToCloud(log)
                sm.deleteProductFromCloud(p.id)
                sm.scheduleOfflineSync()
                withContext(Dispatchers.Main) { finish() } 
            }
        }.setNegativeButton("No", null).show()
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(android.media.AudioManager.STREAM_RING, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {}
    }

    private fun beepError() {
        try {
            val tg = ToneGenerator(android.media.AudioManager.STREAM_RING, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.destroy()
    }
}
