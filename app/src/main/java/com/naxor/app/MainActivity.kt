package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
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
        renderQuickTools()
    }

    override fun onResume() {
        super.onResume()
        loadDashboardData()
        updateMailboxBadge()
        renderQuickTools()
        SyncManager(this).scheduleOfflineSync()
    }

    private fun updateMailboxBadge() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val hasNewMessage = prefs.getBoolean("has_new_message", true)

        binding.viewMenuBadge.visibility = if (hasNewMessage) View.VISIBLE else View.GONE

        val menuItem = binding.navigationViewMain.menu.findItem(R.id.menu_mailbox)
        val actionView = menuItem.actionView
        val badge = actionView?.findViewById<View>(R.id.badge_view)
        badge?.visibility = if (hasNewMessage) View.VISIBLE else View.GONE
    }

    private fun loadDashboardData() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        binding.tvMainBusinessName.text = prefs.getString("business_name", "Mi Negocio")
        val currency = prefs.getString("currency_symbol", "S/")

        lifecycleScope.launch {
            val totalSales = withContext(Dispatchers.IO) { database.saleDao().getSalesAmountFrom(0) }
            val totalProfit = withContext(Dispatchers.IO) { database.saleDao().getProfitFrom(0) }
            val totalExpenses = withContext(Dispatchers.IO) { database.expenseDao().getTotalExpenses() ?: 0.0 }

            withContext(Dispatchers.Main) {
                binding.tvVentasHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalSales)}"
                binding.tvUtilidadHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalProfit)}"
                binding.tvGastosHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalExpenses)}"
            }
        }
    }

    private fun setupListeners() {
        binding.btnIrVentas.setOnClickListener { startActivity(Intent(this, VentasActivity::class.java)) }
        binding.btnMainInventario.setOnClickListener { startActivity(Intent(this, InventarioActivity::class.java)) }
        binding.btnMainGastos.setOnClickListener { checkPinAndNavigate { startActivity(Intent(this, GastosActivity::class.java)) } }
        binding.btnMainHistory.setOnClickListener { startActivity(Intent(this, MovementsActivity::class.java)) }
        
        binding.btnMainMoreOptions.setOnClickListener { toggleMoreOptions() }
        binding.viewDimBackground.setOnClickListener { if (isMoreOptionsOpen) toggleMoreOptions() }
        
        binding.btnOpenMenuMain.setOnClickListener { binding.drawerLayoutMain.openDrawer(GravityCompat.END) }

        binding.btnInfoDashboard.setOnClickListener { showFinancialInfoDialog() }

        binding.cardDashboard.setOnClickListener { 
            checkPinAndNavigate { startActivity(Intent(this, ResumenActivity::class.java)) } 
        }

        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutMain.closeDrawer(GravityCompat.END)
            when (menuItem.itemId) {
                R.id.menu_mailbox -> {
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("has_new_message", false).apply()
                    updateMailboxBadge()
                    Toast.makeText(this, "Buzón de Mensajes (Próximamente)", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_caja -> { startActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_fiados -> { startActivity(Intent(this, DeudoresActivity::class.java)); true }
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

    private fun renderQuickTools() {
        val container = binding.containerQuickTools
        container.removeAllViews()

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val selectedTools = prefs.getStringSet("quick_tools", setOf("catalogo", "ajustes", "sync")) ?: setOf()

        val toolDefinitions = mapOf(
            "catalogo" to Triple("📖 Catálogo", "#0284C7") { generatePDFCatalog() },
            "ajustes" to Triple("⚙️ Ajustes", "#475569") { startActivity(Intent(this, SettingsActivity::class.java)) },
            "sync" to Triple("🔄 Sincronizar", "#10B981") { manualSync() },
            "caja" to Triple("💰 Caja", "#F59E0B") { startActivity(Intent(this, CajaActivity::class.java)) },
            "fiados" to Triple("👥 Fiados", "#EF4444") { startActivity(Intent(this, DeudoresActivity::class.java)) },
            "proveedores" to Triple("🚚 Proveedores", "#0EA5E9") { startActivity(Intent(this, ProveedoresActivity::class.java)) },
            "compras" to Triple("🛒 Lista Compras", "#6366F1") { startActivity(Intent(this, ListaComprasActivity::class.java)) },
            "historial" to Triple("📜 Historial Ventas", "#F43F5E") { checkPinAndNavigate { startActivity(Intent(this, SalesHistoryActivity::class.java)) } },
            "clientes" to Triple("👤 Directorio Clientes", "#10B981") { startActivity(Intent(this, CustomersActivity::class.java)) },
            "calc" to Triple("🧮 Calculadora Precios", "#D946EF") { startActivity(Intent(this, AsignadorDePreciosActivity::class.java)) }
        )

        selectedTools.forEach { toolId ->
            toolDefinitions[toolId]?.let { (name, color, action) ->
                val btn = com.google.android.material.button.MaterialButton(this).apply {
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dpToPx())
                    lp.setMargins(0, 0, 0, 8.dpToPx())
                    layoutParams = lp
                    text = name
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
                    cornerRadius = 20.dpToPx()
                    elevation = 0f
                    translationZ = 10.dpToPx().toFloat()
                    stateListAnimator = null
                    setOnClickListener { 
                        toggleMoreOptions()
                        action()
                    }
                }
                container.addView(btn)
            }
        }

        // Botón de configuración
        val btnConfig = com.google.android.material.button.MaterialButton(this).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dpToPx())
            layoutParams = lp
            text = "➕ Añadir herramienta"
            textSize = 14f
            setTextColor(Color.parseColor("#8E44AD"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F3E5F5"))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#8E44AD"))
            strokeWidth = 2.dpToPx()
            cornerRadius = 16.dpToPx()
            elevation = 0f
            translationZ = 10.dpToPx().toFloat()
            stateListAnimator = null
            setOnClickListener { 
                toggleMoreOptions()
                showToolsConfigDialog()
            }
        }
        container.addView(btnConfig)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showToolsConfigDialog() {
        val toolNames = arrayOf("📖 Catálogo", "⚙️ Ajustes", "🔄 Sincronizar", "💰 Caja", "👥 Fiados", "🚚 Proveedores", "🛒 Lista Compras", "📜 Historial Ventas", "👤 Clientes", "🧮 Calculadora")
        val toolIds = arrayOf("catalogo", "ajustes", "sync", "caja", "fiados", "proveedores", "compras", "historial", "clientes", "calc")
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val selected = prefs.getStringSet("quick_tools", setOf("catalogo", "ajustes", "sync")) ?: setOf()
        
        val checkedItems = BooleanArray(toolIds.size) { i -> selected.contains(toolIds[i]) }

        AlertDialog.Builder(this)
            .setTitle("Configurar Herramientas Rápidas")
            .setMultiChoiceItems(toolNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Guardar") { _, _ ->
                val newSelected = mutableSetOf<String>()
                checkedItems.forEachIndexed { index, isChecked ->
                    if (isChecked) newSelected.add(toolIds[index])
                }
                prefs.edit().putStringSet("quick_tools", newSelected).apply()
                renderQuickTools()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFinancialInfoDialog() {
        val message = """
            <b>💰 Venta total:</b> Es todo el dinero que ha ingresado por tus ventas. Es el ingreso bruto antes de descontar costos o gastos.
            <br><br>
            <b>💸 Gasto total:</b> Es la suma de todos los gastos operativos que has registrado (alquiler, luz, personal, etc.).
            <br><br>
            <b>📈 Utilidad:</b> Es tu ganancia real. Se calcula restando el costo de los productos a la venta total.
            <br><br>
            <b>💡 ¿Cómo interpretarlo?</b>
            La <b>Venta total</b> muestra el movimiento de tu negocio. El <b>Gasto total</b> muestra cuánto cuesta mantenerlo abierto. La <b>Utilidad</b> te dice cuánto dinero estás ganando realmente después de pagar tus productos. 
            <br><br>
            <i>Recuerda: Un negocio sano busca maximizar la utilidad controlando los gastos y optimizando las ventas.</i>
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Interpretación Financiera")
            .setMessage(android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton("Entendido", null)
            .show()
    }

    private var isMoreOptionsOpen = false
    private fun toggleMoreOptions() {
        isMoreOptionsOpen = !isMoreOptionsOpen
        
        if (isMoreOptionsOpen) {
            // Aplicar desenfoque (Blur) en Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                binding.layoutMainContent.setRenderEffect(blurEffect)
            }
            
            // Animación Abrir
            binding.viewDimBackground.visibility = View.VISIBLE
            binding.viewDimBackground.alpha = 0f
            binding.viewDimBackground.animate().alpha(1f).setDuration(300).start()

            binding.layoutMoreOptionsExpanded.visibility = View.VISIBLE
            binding.layoutMoreOptionsExpanded.alpha = 0f
            binding.layoutMoreOptionsExpanded.translationY = 50f
            binding.layoutMoreOptionsExpanded.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
            
            binding.ivMoreOptionsIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            binding.ivMoreOptionsIcon.animate().rotation(90f).setDuration(300).start()
            
            // Re-renderizar para asegurar clics
            renderQuickTools()
        } else {
            // Quitar desenfoque
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.layoutMainContent.setRenderEffect(null)
            }

            // Animación Cerrar
            binding.viewDimBackground.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.viewDimBackground.visibility = View.GONE }
                .start()

            binding.layoutMoreOptionsExpanded.animate()
                .alpha(0f)
                .translationY(50f)
                .setDuration(250)
                .withEndAction { binding.layoutMoreOptionsExpanded.visibility = View.GONE }
                .start()
            
            binding.ivMoreOptionsIcon.setImageResource(android.R.drawable.ic_menu_add)
            binding.ivMoreOptionsIcon.animate().rotation(0f).setDuration(300).start()
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
