package com.naxor.app

import android.content.Context
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
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.appCheck
import com.google.firebase.initialize
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
import androidx.work.*
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

    // SISTEMA DE TUTORIAL CONTEXTUAL
    private var currentTutorialStep = 0
    private val tutorialSteps = listOf(
        "¡Bienvenido a Naxor! 🚀" to "Hemos rediseñado todo para que tu negocio crezca. Vamos a darte un recorrido rápido.",
        "Navegación Central" to "Usa la barra inferior para moverte entre tu Inventario, el Rendimiento de tu negocio y los Ajustes.",
        "Menú de Gestión" to "Desliza tu dedo hacia la derecha en cualquier parte de la pantalla para abrir herramientas como Gastos, Caja y Deudores.",
        "Herramientas de Control" to "Dentro del menú encontrarás funciones para controlar tus deudas, proveedores y comprobantes electrónicos.",
        "Tu Dashboard" to "Aquí ves tus Ventas, Utilidad y Gastos de HOY. Todo se actualiza en tiempo real.",
        "Registro de Ventas" to "Usa el botón central para vender. ¡Puedes usar la cámara para escanear códigos de barras!",
        "Buzón de Mensajes" to "Mira el sobre arriba a la derecha. Ahí te enviaremos novedades y consejos para tu negocio.",
        "¡Todo listo!" to "Ya puedes empezar a usar Naxor. Recuerda que puedes ver esta guía de nuevo en Ajustes."
    )

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

        updateMenuForBusinessType()
        hideBottomNavIndicator()
        setupCustomBottomNav()
        setupGlobalGesture()
        checkNotificationPermission()
        setupListeners()
        startSyncService()
        subscribeToSalesTopic()
        scheduleReminders()

        if (savedInstanceState == null) {
            navigateToInicio()
            checkTutorial()
        }
    }

    private fun updateMenuForBusinessType() {
        val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        
        // --- 1. Bottom Navigation ---
        val menu = binding.bottomNavigation.menu
        val stockItem = menu.findItem(R.id.nav_stock)
        
        stockItem?.let { item ->
            if (businessType == "SERVICES" || businessType == "HOTEL") {
                item.title = if (businessType == "HOTEL") "Habitaciones" else "Servicios"
                item.setIcon(android.R.drawable.ic_menu_agenda)
                
                // --- 2. Side Drawer ---
                binding.navigationViewMain.menu.clear()
                if (businessType == "HOTEL") {
                    binding.navigationViewMain.inflateMenu(R.menu.menu_hotel_drawer)
                } else {
                    binding.navigationViewMain.inflateMenu(R.menu.menu_main_drawer)
                }
            } else {
                item.title = "Inventario"
                item.setIcon(android.R.drawable.ic_menu_save)
                
                binding.navigationViewMain.menu.clear()
                binding.navigationViewMain.inflateMenu(R.menu.menu_main_drawer)
            }
        }
    }

    private fun hideBottomNavIndicator() {
        binding.bottomNavigation.post {
            try {
                val menuView = binding.bottomNavigation.getChildAt(0) as? android.view.ViewGroup
                for (i in 0 until (menuView?.childCount ?: 0)) {
                    val item = menuView?.getChildAt(i) as? android.view.ViewGroup
                    val indicator = item?.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_active_indicator_view)
                    indicator?.visibility = View.GONE
                    indicator?.alpha = 0f
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupCustomBottomNav() {
        // Inicializar posición 0 (Inicio)
        binding.bottomNavigation.post {
            val itemWidth = binding.bottomNavigation.width / 4
            binding.navIndicatorCustom.translationX = 0f
        }
    }

    private fun moveNavIndicator(index: Int) {
        hideBottomNavIndicator() // Asegurar que el indicador por defecto estÃ© oculto
        binding.bottomNavigation.post {
            val itemWidth = binding.bottomNavigation.width / 4
            binding.navIndicatorCustom.animate()
                .translationX(index * itemWidth.toFloat())
                .setDuration(400)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                .start()
        }
    }

    private fun scheduleReminders() {
        val reminderRequest = PeriodicWorkRequestBuilder<ReminderWorker>(3, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "debt_reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderRequest
        )
    }

    private fun checkTutorial() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val shown = prefs.getBoolean("tutorial_shown_v3", false)
        if (!shown) {
            startInteractiveTutorial()
        }
    }

    fun startInteractiveTutorial() {
        currentTutorialStep = 0
        binding.layoutTutorialOverlay.visibility = View.VISIBLE
        updateTutorialUI()
        
        binding.btnNextTutorialStep.setOnClickListener {
            currentTutorialStep++
            if (currentTutorialStep < tutorialSteps.size) {
                updateTutorialUI()
            } else {
                finishTutorial()
            }
        }
    }

    private fun updateTutorialUI() {
        val step = tutorialSteps[currentTutorialStep]
        val title = step.first
        val desc = step.second
        
        binding.tvTutorialStepTitle.text = title
        binding.tvTutorialStepDesc.text = desc
        
        // Lógica de resaltado (Spotlight)
        when(currentTutorialStep) {
            1 -> highlightView(binding.bottomNavigation)
            2 -> highlightView(null)
            3 -> {
                binding.drawerLayoutMain.openDrawer(GravityCompat.START)
                highlightView(null)
            }
            4 -> {
                binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                highlightView(findViewById(R.id.cardDashboard))
            }
            5 -> highlightView(findViewById(R.id.layoutVentaActions))
            6 -> highlightView(findViewById(R.id.btnMailboxHome))
            else -> highlightView(null)
        }
    }

    private fun highlightView(view: View?) {
        val spotlight = findViewById<com.naxor.app.util.TutorialSpotlightView>(R.id.viewTutorialSpotlight) ?: return
        
        if (view == null) {
            spotlight.clearTarget()
            val params = binding.cardTutorialInfo.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.verticalBias = 0.5f
            binding.cardTutorialInfo.layoutParams = params
            return
        }

        view.post {
            spotlight.setTarget(view)
            
            val location = IntArray(2)
            view.getLocationInWindow(location)
            
            val params = binding.cardTutorialInfo.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val screenHeight = resources.displayMetrics.heightPixels
            
            // Posicionamiento dinámico más cercano al centro
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            
            if (location[1] > screenHeight / 2) {
                // Si el botón está abajo, poner el mensaje arriba (pero no al extremo)
                params.verticalBias = 0.42f
            } else {
                // Si el botón está arriba, poner el mensaje abajo (pero no al extremo)
                params.verticalBias = 0.58f
            }
            binding.cardTutorialInfo.layoutParams = params
        }
    }

    private fun finishTutorial() {
        binding.layoutTutorialOverlay.visibility = View.GONE
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("tutorial_shown_v3", true).apply()
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

    fun openSideMenu() {
        binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    }

    private fun openAnyDrawer() {
        val currentFrag = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
        
        // Buscar por tag si el ID no es suficiente (debido a la nueva lÃ³gica de show/hide)
        val homeFrag = supportFragmentManager.findFragmentByTag("HOME")
        val stockFrag = supportFragmentManager.findFragmentByTag("STOCK")
        val metricsFrag = supportFragmentManager.findFragmentByTag("METRICAS")

        when {
            homeFrag != null && homeFrag.isVisible -> {
                binding.drawerLayoutMain.openDrawer(GravityCompat.START)
            }
            stockFrag != null && stockFrag.isVisible -> {
                (stockFrag as? com.naxor.app.fragment.StockFragment)?.openDrawer()
            }
            metricsFrag != null && metricsFrag.isVisible -> {
                (metricsFrag as? com.naxor.app.fragment.MetricasFragment)?.openDrawer()
            }
            else -> {
                // No hacer nada o comportamiento por defecto
            }
        }
    }

    private var isNavigatingInternally = false

    private fun showFragment(tag: String) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        
        // Animación suave de entrada
        transaction.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)

        val fragments = listOf("HOME", "STOCK", "METRICAS", "SETTINGS")
        
        // Ocultar todos los fragmentos
        fragments.forEach { t ->
            fm.findFragmentByTag(t)?.let { transaction.hide(it) }
        }

        val target = fm.findFragmentByTag(tag)
        if (target != null) {
            transaction.show(target)
        } else {
            val prefs = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            val businessType = prefs.getString("business_type", "PRODUCTS")
            
            val newFrag = when(tag) {
                "HOME" -> {
                    if (businessType == "HOTEL") com.naxor.app.fragment.HotelHomeFragment()
                    else com.naxor.app.fragment.HomeFragment()
                }
                "STOCK" -> {
                    if (businessType == "HOTEL") com.naxor.app.fragment.HotelRoomsFragment()
                    else com.naxor.app.fragment.StockFragment()
                }
                "METRICAS" -> {
                    if (businessType == "HOTEL") com.naxor.app.fragment.HotelMetricsFragment()
                    else com.naxor.app.fragment.MetricasFragment()
                }
                "SETTINGS" -> com.naxor.app.fragment.SettingsFragment()
                else -> com.naxor.app.fragment.HomeFragment()
            }
            transaction.add(R.id.mainFragmentContainer, newFrag, tag)
        }

        transaction.commitAllowingStateLoss()
    }

    fun navigateToInicio() {
        if (isNavigatingInternally) return
        isNavigatingInternally = true
        if (binding.bottomNavigation.selectedItemId != R.id.nav_inicio) {
            binding.bottomNavigation.selectedItemId = R.id.nav_inicio
        }
        moveNavIndicator(0)
        showFragment("HOME")
        isNavigatingInternally = false
    }

    fun navigateToStock() {
        if (isNavigatingInternally) return
        isNavigatingInternally = true
        if (binding.bottomNavigation.selectedItemId != R.id.nav_stock) {
            binding.bottomNavigation.selectedItemId = R.id.nav_stock
        }
        moveNavIndicator(1)
        showFragment("STOCK")
        isNavigatingInternally = false
    }

    fun navigateToMetricas() {
        if (isNavigatingInternally) return
        isNavigatingInternally = true
        if (binding.bottomNavigation.selectedItemId != R.id.nav_metricas) {
            binding.bottomNavigation.selectedItemId = R.id.nav_metricas
        }
        moveNavIndicator(2)
        showFragment("METRICAS")
        isNavigatingInternally = false
    }

    fun navigateToSettings() {
        if (isNavigatingInternally) return
        isNavigatingInternally = true
        if (binding.bottomNavigation.selectedItemId != R.id.nav_config) {
            binding.bottomNavigation.selectedItemId = R.id.nav_config
        }
        moveNavIndicator(3)
        showFragment("SETTINGS")
        isNavigatingInternally = false
    }

    fun navigateToGestion() {
        binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    }

    private fun startSyncService() {
        val serviceIntent = Intent(this, com.naxor.app.network.NaxorSyncService::class.java)
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
        updateMenuForBusinessType()
        SyncManager(this).scheduleOfflineSync()
    }

    private fun setupListeners() {
        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                // Hotel Specific
                R.id.menu_hotel_guests -> { startToolActivity(Intent(this, HotelGuestHistoryActivity::class.java)); true }
                R.id.menu_hotel_bookings -> { navigateToMetricas(); true }
                R.id.menu_hotel_payments -> { startToolActivity(Intent(this, BusinessDebtsActivity::class.java)); true }
                R.id.menu_hotel_rooms_graph -> { navigateToStock(); true }
                R.id.menu_hotel_maintenance -> { startToolActivity(Intent(this, HotelMaintenanceActivity::class.java)); true }
                R.id.menu_hotel_tools -> { Toast.makeText(this, "Inventario de Herramientas", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_hotel_guide -> { Toast.makeText(this, "Guía de la Ciudad", Toast.LENGTH_SHORT).show(); true }
                
                // General
                R.id.menu_gastos -> { checkPinAndNavigate { startToolActivity(Intent(this, GastosActivity::class.java)) }; true }
                R.id.menu_caja -> { startToolActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_fiados -> { startToolActivity(Intent(this, DeudoresActivity::class.java)); true }
                R.id.menu_business_debts -> { startToolActivity(Intent(this, BusinessDebtsActivity::class.java)); true }
                R.id.menu_proveedores -> { startToolActivity(Intent(this, ProveedoresActivity::class.java)); true }
                R.id.menu_emitir_comprobante -> { startToolActivity(Intent(this, EmitirComprobanteActivity::class.java)); true }
                R.id.menu_customize_actions -> { showActionsConfigDialog(); true }
                R.id.menu_catalogo -> { generatePDFCatalog(); true }
                R.id.menu_sync -> { manualSync(); true }
                R.id.menu_sales_history -> { checkPinAndNavigate { startToolActivity(Intent(this, SalesHistoryActivity::class.java)) }; true }
                R.id.menu_lista_compras -> { startToolActivity(Intent(this, ListaComprasActivity::class.java)); true }
                R.id.menu_asignador -> { startToolActivity(Intent(this, AsignadorDePreciosActivity::class.java)); true }
                R.id.menu_customers -> { startToolActivity(Intent(this, CustomersActivity::class.java)); true }
                R.id.menu_settings -> { startToolActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.menu_view_history -> { startToolActivity(Intent(this, HistorialActivity::class.java)); true }
                R.id.menu_instructions -> { startToolActivity(Intent(this, InstruccionesActivity::class.java)); true }
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
            val currentId = binding.bottomNavigation.selectedItemId
            if (currentId == item.itemId) return@setOnItemSelectedListener true

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
            "💸 Gastos", "💰 Caja", "👥 Deudores", "💸 Mis Cuentas", "🚚 Proveedores", 
            "👤 Clientes", "📖 Catálogo", "🔄 Sincronizar", "📬 Buzón", 
            "🛒 Lista Compras", "⚖️ Asignador Precios", "📜 Historial Ventas", 
            "🕒 Historial Cálculos", "💡 Instrucciones"
        )
        val actionIds = arrayOf(
            "gastos", "caja", "fiados", "business_debts", "proveedores", 
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

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_out_down)
    }
}
