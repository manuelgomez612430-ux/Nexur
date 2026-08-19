package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.QuickAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val database by lazy { AppDatabase.getDatabase(this) }

    private lateinit var globalGestureDetector: GestureDetector


    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Las notificaciones están desactivadas. No recibirás avisos de ventas en tiempo real.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGlobalGesture()
        checkNotificationPermission()
        setupListeners()
        startSyncService()
        subscribeToSalesTopic()

        if (savedInstanceState == null) {
            navigateToInicio()
        }
    }

    private fun setupGlobalGesture() {
        globalGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                // Si el movimiento es horizontal y hacia la derecha
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (diffX > 150 && Math.abs(velocityX) > 200) {
                        // Solo abrir si no hay un menú ya abierto
                        if (!binding.drawerLayoutMain.isDrawerOpen(GravityCompat.START)) {
                            openAnyDrawer()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) globalGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openAnyDrawer() {
        val currentFrag = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
        
        when (currentFrag) {
            is com.naxor.app.fragment.HomeFragment -> {
                binding.drawerLayoutMain.openDrawer(GravityCompat.START)
            }
            is com.naxor.app.fragment.StockFragment -> {
                currentFrag.openDrawer()
            }
            is com.naxor.app.fragment.MetricasFragment -> {
                currentFrag.openDrawer()
            }
            is com.naxor.app.fragment.SettingsFragment -> {
                // No hacer nada, barra lateral desactivada en Ajustes
            }
            else -> {
                // Comportamiento por defecto
                binding.drawerLayoutMain.openDrawer(GravityCompat.START)
            }
        }
    }

    fun navigateToInicio() {
        if (supportFragmentManager.findFragmentByTag("HOME")?.isVisible == true) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, com.naxor.app.fragment.HomeFragment(), "HOME")
            .commit()
    }

    fun navigateToStock() {
        if (supportFragmentManager.findFragmentByTag("STOCK")?.isVisible == true) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, com.naxor.app.fragment.StockFragment(), "STOCK")
            .commit()
    }

    fun navigateToMetricas() {
        if (supportFragmentManager.findFragmentByTag("METRICAS")?.isVisible == true) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, com.naxor.app.fragment.MetricasFragment(), "METRICAS")
            .commit()
    }

    fun navigateToSettings() {
        if (supportFragmentManager.findFragmentByTag("SETTINGS")?.isVisible == true) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, com.naxor.app.fragment.SettingsFragment(), "SETTINGS")
            .commit()
    }

    fun navigateToGestion() {
        binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    }

    private fun startSyncService() {
        val serviceIntent = Intent(this, com.naxor.app.network.NexurSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun subscribeToSalesTopic() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val topic = "sales_$userId"
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM", "Suscrito con éxito al tema: $topic")
                } else {
                    Log.e("FCM", "Error al suscribirse al tema")
                }
            }
    }

    override fun onResume() {
        super.onResume()
        SyncManager(this).scheduleOfflineSync()
    }

    private fun setupListeners() {
        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_gastos -> { checkPinAndNavigate { startActivity(Intent(this, GastosActivity::class.java)) }; true }
                R.id.menu_caja -> { startActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_fiados -> { startActivity(Intent(this, DeudoresActivity::class.java)); true }
                R.id.menu_proveedores -> { startActivity(Intent(this, ProveedoresActivity::class.java)); true }
                R.id.menu_emitir_comprobante -> { startActivity(Intent(this, EmitirComprobanteActivity::class.java)); true }
                R.id.menu_customize_actions -> { showActionsConfigDialog(); true }
                R.id.menu_catalogo -> { generatePDFCatalog(); true }
                R.id.menu_sync -> { manualSync(); true }
                R.id.menu_sales_history -> { checkPinAndNavigate { startActivity(Intent(this, SalesHistoryActivity::class.java)) }; true }
                R.id.menu_lista_compras -> { startActivity(Intent(this, ListaComprasActivity::class.java)); true }
                R.id.menu_asignador -> { startActivity(Intent(this, AsignadorDePreciosActivity::class.java)); true }
                R.id.menu_customers -> { startActivity(Intent(this, CustomersActivity::class.java)); true }
                R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
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

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inicio -> {
                    navigateToInicio()
                    true
                }
                R.id.nav_stock -> {
                    navigateToStock()
                    true
                }
                R.id.nav_metricas -> {
                    navigateToMetricas()
                    true
                }
                R.id.nav_config -> {
                    navigateToSettings()
                    true
                }
                else -> false
            }
        }
    }

    fun startDirectScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                if (code.isNotEmpty()) {
                    beep(ToneGenerator.TONE_PROP_BEEP)
                    val intent = Intent(this, VentasActivity::class.java)
                    intent.putExtra("EXTRA_SCANNED_CODE", code)
                    startActivity(intent)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error del escáner: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun beep(type: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(type, 200)
        } catch (e: Exception) {}
    }






    fun manualSync() {
        val loading = AlertDialog.Builder(this)
            .setTitle("Sincronizando")
            .setMessage("Descargando datos actualizados de la nube...")
            .setCancelable(false)
            .show()
            
        SyncManager(this).downloadEverythingFromCloud {
            loading.dismiss()
            navigateToInicio() // Recargar el fragmento para refrescar datos
            Toast.makeText(this, "¡Datos sincronizados correctamente!", Toast.LENGTH_SHORT).show()
        }
    }

    fun generatePDFCatalog() {
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

    fun showActionsConfigDialog() {
        val actionNames = arrayOf(
            "💸 Gastos", "💰 Caja", "👥 Deudores", "🚚 Proveedores", 
            "👤 Clientes", "📖 Catálogo", "🔄 Sincronizar", "📬 Buzón", 
            "🛒 Lista Compras", "⚖️ Asignador Precios", "📜 Historial Ventas", 
            "🕒 Historial Cálculos", "💡 Instrucciones"
        )
        val actionIds = arrayOf(
            "gastos", "caja", "fiados", "proveedores", 
            "clientes", "catalogo", "sync", "mailbox", 
            "lista_compras", "asignador", "sales_history", 
            "view_history", "instructions"
        )
        
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val savedActions = prefs.getString("quick_actions_list", "stock,gastos,caja,fiados,proveedores") ?: ""
        val tempSelection = if (savedActions.isEmpty()) mutableListOf<String>() else savedActions.split(",").toMutableSet().toMutableList()

        val rv = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ConfigActionsAdapter(this@MainActivity, actionNames, actionIds, tempSelection)
            setPadding(0, 16, 0, 16)
            clipToPadding = false
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Personalización de Herramientas (Exactamente 5)")
            .setView(rv)
            .setPositiveButton("Guardar", null) // Set null to override later
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (tempSelection.size == 5) {
                prefs.edit().putString("quick_actions_list", tempSelection.joinToString(",")).apply()
                navigateToInicio()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "¡Atención! Debes seleccionar exactamente 5 herramientas para mantener la estética del menú.", Toast.LENGTH_LONG).show()
                // Animación de feedback en el diálogo si es necesario
            }
        }
    }

    fun checkPinAndNavigate(onSuccess: () -> Unit) {
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
