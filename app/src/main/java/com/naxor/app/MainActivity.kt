package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadDashboardData()
    }

    private fun loadDashboardData() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        binding.tvMainBusinessName.text = prefs.getString("business_name", "Mi Negocio")
        val currency = prefs.getString("currency_symbol", "S/")

        lifecycleScope.launch {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val startOfDay = cal.timeInMillis

            val totalToday = withContext(Dispatchers.IO) { database.saleDao().getSalesAmountFrom(startOfDay) }
            val profitToday = withContext(Dispatchers.IO) { database.saleDao().getProfitFrom(startOfDay) }

            withContext(Dispatchers.Main) {
                binding.tvVentasHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalToday)}"
                binding.tvUtilidadHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", profitToday)}"
            }
        }
    }

    private fun setupListeners() {
        binding.btnIrVentas.setOnClickListener { startActivity(Intent(this, VentasActivity::class.java)) }
        binding.btnMainInventario.setOnClickListener { startActivity(Intent(this, InventarioActivity::class.java)) }
        
        binding.btnMainMoreOptions.setOnClickListener { toggleMoreOptions() }
        binding.btnMainCatalogo.setOnClickListener { toggleMoreOptions(); generatePDFCatalog() }
        binding.btnMainAjustes.setOnClickListener { toggleMoreOptions(); startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnMainSync.setOnClickListener { 
            toggleMoreOptions()
            manualSync()
        }

        binding.btnOpenMenuMain.setOnClickListener { binding.drawerLayoutMain.openDrawer(GravityCompat.END) }

        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutMain.closeDrawer(GravityCompat.END)
            when (menuItem.itemId) {
                R.id.menu_caja -> { startActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_fiados -> { startActivity(Intent(this, DeudoresActivity::class.java)); true }
                R.id.menu_gastos -> { checkPinAndNavigate { startActivity(Intent(this, GastosActivity::class.java)) }; true }
                R.id.menu_ganancias -> { checkPinAndNavigate { startActivity(Intent(this, ResumenActivity::class.java)) }; true }
                R.id.menu_sales_history -> { checkPinAndNavigate { startActivity(Intent(this, SalesHistoryActivity::class.java)) }; true }
                R.id.menu_lista_compras -> { startActivity(Intent(this, ListaComprasActivity::class.java)); true }
                R.id.menu_proveedores -> { startActivity(Intent(this, ProveedoresActivity::class.java)); true }
                R.id.menu_asignador -> { startActivity(Intent(this, AsignadorDePreciosActivity::class.java)); true }
                R.id.menu_customers -> { startActivity(Intent(this, CustomersActivity::class.java)); true }
                R.id.menu_view_history -> { startActivity(Intent(this, HistorialActivity::class.java)); true }
                R.id.menu_instructions -> { startActivity(Intent(this, InstruccionesActivity::class.java)); true }
                R.id.menu_logout -> {
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private var isMoreOptionsOpen = false
    private fun toggleMoreOptions() {
        isMoreOptionsOpen = !isMoreOptionsOpen
        
        if (isMoreOptionsOpen) {
            // Animación Abrir: Aparece y sube
            binding.layoutMoreOptionsExpanded.visibility = View.VISIBLE
            binding.layoutMoreOptionsExpanded.alpha = 0f
            binding.layoutMoreOptionsExpanded.translationY = 50f
            binding.layoutMoreOptionsExpanded.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
            
            binding.btnMainMoreOptions.animate().rotation(180f).setDuration(300).start()
        } else {
            // Animación Cerrar: Desaparece y baja
            binding.layoutMoreOptionsExpanded.animate()
                .alpha(0f)
                .translationY(50f)
                .setDuration(250)
                .withEndAction { binding.layoutMoreOptionsExpanded.visibility = View.GONE }
                .start()
            
            binding.btnMainMoreOptions.animate().rotation(0f).setDuration(300).start()
        }
    }

    private fun showMoreOptionsPopup() {
        // Obsoleto, reemplazado por toggleMoreOptions()
    }

    private fun manualSync() {
        val loading = AlertDialog.Builder(this)
            .setTitle("Sincronizando")
            .setMessage("Descargando datos actualizados de la nube...")
            .setCancelable(false)
            .show()
            
        SyncManager(this).downloadEverythingFromCloud {
            loading.dismiss()
            loadDashboardData()
            Toast.makeText(this, "¡Datos sincronizados correctamente!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generatePDFCatalog() {
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("Generando Catálogo")
            .setMessage("Por favor espera, estamos preparando tus productos con imágenes HD...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val name = prefs.getString("business_name", "Mi Negocio")
                val phone = prefs.getString("business_phone", "Sin teléfono")
                val address = prefs.getString("business_address", "Dirección no registrada")
                val ruc = prefs.getString("business_ruc", "RUC no registrado")
                val curr = prefs.getString("currency_symbol", "S/")
                val products = database.productDao().allProducts.sortedBy { it.categoria }
                
                if (products.isEmpty()) {
                    withContext(Dispatchers.Main) { 
                        loadingDialog.dismiss()
                        Toast.makeText(this@MainActivity, "No hay productos", Toast.LENGTH_SHORT).show() 
                    }
                    return@launch
                }

                val doc = android.graphics.pdf.PdfDocument()
                val paint = android.graphics.Paint()
                val boldPaint = android.graphics.Paint().apply { isFakeBoldText = true }
                
                var pageNumber = 1
                var pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                var page = doc.startPage(pageInfo)
                var canvas = page.canvas
                
                boldPaint.textSize = 40f; boldPaint.color = Color.parseColor("#0284C7")
                canvas.drawText(name?.uppercase() ?: "MI NEGOCIO", 100f, 300f, boldPaint)
                paint.textSize = 18f; paint.color = Color.GRAY
                canvas.drawText("CATÁLOGO DIGITAL DE PRODUCTOS", 100f, 340f, paint)
                paint.textSize = 14f; paint.color = Color.DKGRAY
                canvas.drawText("📞 Pedidos: $phone", 100f, 450f, paint)
                canvas.drawText("📍 Ubicación: $address", 100f, 480f, paint)
                canvas.drawText("🆔 RUC: $ruc", 100f, 510f, paint)
                paint.color = Color.parseColor("#0284C7")
                canvas.drawRect(100f, 360f, 495f, 365f, paint)
                doc.finishPage(page)

                pageNumber++; pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                page = doc.startPage(pageInfo); canvas = page.canvas
                boldPaint.textSize = 24f; boldPaint.color = Color.BLACK
                canvas.drawText("ÍNDICE", 50f, 80f, boldPaint)
                var indexY = 130f
                val categories = products.map { it.categoria }.distinct()
                var currentPageForIndex = 3
                categories.forEach { cat ->
                    paint.textSize = 14f; paint.color = Color.BLACK
                    canvas.drawText("• ${cat.uppercase()}", 70f, indexY, paint)
                    paint.color = Color.LTGRAY
                    canvas.drawText("......................................................................", 200f, indexY, paint)
                    paint.color = Color.parseColor("#0284C7")
                    canvas.drawText("Pág. $currentPageForIndex", 480f, indexY, paint)
                    val count = products.count { it.categoria == cat }
                    currentPageForIndex += Math.ceil(Math.ceil(count / 2.0) / 2.0).toInt().coerceAtLeast(1)
                    indexY += 30f
                }
                doc.finishPage(page)

                val itemHeight = 340f
                categories.forEach { categoryName ->
                    pageNumber++; pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = doc.startPage(pageInfo); canvas = page.canvas
                    var currentY = 60f; var isLeftCol = true
                    boldPaint.textSize = 20f; boldPaint.color = Color.parseColor("#0284C7")
                    canvas.drawText("📂 ${categoryName.uppercase()}", 40f, currentY, boldPaint)
                    paint.color = Color.parseColor("#0284C7")
                    canvas.drawRect(40f, currentY + 10f, 555f, currentY + 13f, paint)
                    currentY += 50f

                    products.filter { it.categoria == categoryName }.forEach { prod ->
                        if (currentY > 700f) {
                            doc.finishPage(page); pageNumber++
                            pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                            page = doc.startPage(pageInfo); canvas = page.canvas; currentY = 60f
                        }
                        val startX = if (isLeftCol) 40f else 40f + 245f + 25f
                        paint.color = Color.WHITE; canvas.drawRoundRect(startX, currentY, startX + 245f, currentY + 320f, 25f, 25f, paint)
                        paint.color = Color.parseColor("#F1F5F9"); paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2f
                        canvas.drawRoundRect(startX, currentY, startX + 245f, currentY + 320f, 25f, 25f, paint)
                        paint.style = android.graphics.Paint.Style.FILL

                        if (!prod.photoPath.isNullOrEmpty()) {
                            try {
                                // 1. Intentar carga por Stream (Más seguro para permisos)
                                val cleanPath = prod.photoPath.replace("file://", "")
                                val inputStream = if (cleanPath.startsWith("content://")) {
                                    contentResolver.openInputStream(android.net.Uri.parse(cleanPath))
                                } else {
                                    File(cleanPath).inputStream()
                                }

                                inputStream?.use { stream ->
                                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                                    val original = BitmapFactory.decodeStream(stream)
                                    if (original != null) {
                                        // DIBUJAR FOTO CON BORDES REDONDEADOS (Efecto Premium)
                                        val scaled = Bitmap.createScaledBitmap(original, 220, 200, true)
                                        
                                        val roundedPaint = android.graphics.Paint().apply { isAntiAlias = true }
                                        val shader = android.graphics.BitmapShader(scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                                        roundedPaint.shader = shader
                                        
                                        val rect = android.graphics.RectF(startX + 12f, currentY + 12f, startX + 232f, currentY + 212f)
                                        canvas.drawRoundRect(rect, 25f, 25f, roundedPaint)
                                        
                                        original.recycle(); scaled.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                // 2. Segundo intento: Carga directa si el stream falló
                                try {
                                    val bitmap = BitmapFactory.decodeFile(prod.photoPath.replace("file://", ""))
                                    if (bitmap != null) {
                                        val scaled = Bitmap.createScaledBitmap(bitmap, 220, 200, true)
                                        canvas.drawBitmap(scaled, startX + 12f, currentY + 12f, null)
                                        bitmap.recycle(); scaled.recycle()
                                    }
                                } catch (e2: Exception) {}
                            }
                        }
                        paint.color = Color.BLACK; paint.textSize = 14f
                        val nameStr = if(prod.nombre.length > 25) prod.nombre.take(22)+"..." else prod.nombre
                        canvas.drawText(nameStr, startX + 15f, currentY + 245f, paint)
                        paint.color = Color.parseColor("#0284C7")
                        canvas.drawRoundRect(startX + 15f, currentY + 265f, startX + 160f, currentY + 305f, 12f, 12f, paint)
                        boldPaint.textSize = 16f; boldPaint.color = Color.WHITE
                        canvas.drawText("$curr ${String.format(Locale.getDefault(), "%.2f", prod.precioVenta)}", startX + 25f, currentY + 293f, boldPaint)
                        if (!isLeftCol) currentY += itemHeight
                        isLeftCol = !isLeftCol
                    }
                    doc.finishPage(page)
                }
                
                val fileName = "Catalogo_${name?.replace(" ", "_")}.pdf"
                val file = File(getExternalFilesDir(null), fileName)
                try { 
                    doc.writeTo(file.outputStream()); doc.close()
                    withContext(Dispatchers.Main) { 
                        loadingDialog.dismiss()
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply { 
                            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) 
                        }
                        startActivity(Intent.createChooser(intent, "Enviar Catálogo HD")) 
                    }
                } catch (e: Exception) { 
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { 
                        loadingDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Error al guardar el archivo", Toast.LENGTH_SHORT).show() 
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace()
                withContext(Dispatchers.Main) { 
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error al generar catálogo", Toast.LENGTH_SHORT).show() 
                }
            }
        }
    }

    private fun checkPinAndNavigate(onSuccess: () -> Unit) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val savedPin = prefs.getString("user_pin", "") ?: ""
        if (savedPin.isEmpty()) { onSuccess(); return }
        val etPin = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Escribe el PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Acceso Restringido")
            .setMessage("Para ver esta sección, ingresa tu PIN de seguridad:")
            .setView(etPin)
            .setPositiveButton("Entrar") { _, _ ->
                if (etPin.text.toString() == savedPin) onSuccess()
                else Toast.makeText(this, "PIN Incorrecto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }
}
