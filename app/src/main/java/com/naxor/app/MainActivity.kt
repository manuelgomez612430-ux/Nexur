package com.naxor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.naxor.app.data.CollaboratorEntity
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityMainBinding
import com.naxor.app.fragment.*
import com.naxor.app.util.SessionManager
import com.naxor.app.network.GoMezMartSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var currentTutorialStep = 0
    private val tutorialSteps = listOf(
        "¡Bienvenido a GoMezMart!" to "Hemos actualizado tu sistema para que sea más potente y profesional. Sigue esta guía rápida para conocer las novedades.",
        "Nuevo Panel de Control" to "Ahora tienes acceso directo a tus productos, métricas y configuración desde la barra inferior.",
        "Sistema Inteligente" to "El menú lateral se adapta automáticamente a tu tipo de negocio (Tienda, Hotel o Préstamos).",
        "Entorno Colaborativo" to "Invita a tu equipo y asigna permisos específicos para que te ayuden a gestionar tu negocio.",
        "Mercado GoMezMart" to "Tu negocio ahora puede estar online. Actívalo en Ajustes para que aparezca en nuestra red global.",
        "Buzón de Mensajes" to "Recibe avisos importantes y mensajes directos en tu nuevo centro de notificaciones.",
        "¡Éxito en tus cobros!" to "Ya puedes empezar. Recuerda que puedes ver esta guía de nuevo en Ajustes."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupCustomBottomNav()
        
        if (savedInstanceState == null) {
            navigateToInicio()
        }

        if (intent.getBooleanExtra("START_UPDATE", false)) {
            Updater(this).checkAndDownload(autoDownload = true)
        }

        checkCollaboratorAccess()
        startSyncService()
        subscribeToSalesTopic()
        schedulePeriodicSync()
    }

    private fun checkCollaboratorAccess() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val isCollab = prefs.getBoolean("is_collaborator_mode", false)
        val ownerId = prefs.getString("collaborator_owner_id", null)

        if (isCollab && ownerId != null) {
            val userEmail = auth.currentUser?.email?.lowercase() ?: ""
            db.collection("users").document(ownerId)
                .collection("collaborators").document(userEmail)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.e("MainActivity", "Error collab listener: ${e.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot == null || !snapshot.exists()) {
                        // BLOQUEO INMEDIATO: El colaborador ha sido removido
                        handleCollaboratorLockout(ownerId)
                        return@addSnapshotListener
                    }
                    
                    val collab = snapshot.toObject(CollaboratorEntity::class.java)
                    collab?.let {
                        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
                        val editor = collabPrefs.edit()
                        it.permissions.forEach { (key, value) -> editor.putBoolean(key, value) }
                        editor.putString("collab_name", it.name)
                        editor.putString("collab_role", if (it.roles.isNotEmpty()) it.roles.joinToString(", ") else it.role)
                        editor.commit() 
                        
                        runOnUiThread {
                            updateMenuForBusinessType()
                        }
                    }
                }
        }
    }

    private fun handleCollaboratorLockout(ownerId: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            // 1. Obtener nombre del dueño antes de cerrar todo
            var ownerName = "el administrador"
            try {
                val ownerDoc = db.collection("users").document(ownerId).get().await()
                ownerName = ownerDoc.getString("business_owner_name") ?: ownerDoc.getString("business_name") ?: ownerName
            } catch (e: Exception) {}

            // 2. Limpiar rastro de colaboración
            val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_collaborator_mode", false)
                remove("collaborator_owner_id")
                apply()
            }
            
            // 3. Limpiar base de datos local (SEGURIDAD CRÍTICA - Previene ver datos del dueño en sistema personal)
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).clearAllTables()
            }
            
            // Reiniciar servicio de sincronización para que tome el nuevo ID de usuario
            startSyncService()

            // 4. Mostrar aviso y redirigir
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Acceso Revocado 🚫")
                .setMessage("Has sido retirado del sistema como colaborador por: $ownerName.\n\nSe ha cerrado el acceso al negocio y se han limpiado todos los datos compartidos de este dispositivo para proteger la privacidad del dueño.")
                .setCancelable(false)
                .setPositiveButton("Entendido") { _, _ ->
                    updateMenuForBusinessType()
                    navigateToInicio()
                    // Re-instanciar actividad para asegurar limpieza total de memoria y estados
                    val intent = Intent(this@MainActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .show()
        }
    }

    private fun updateMenuForBusinessType() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)

        // --- 1. Botones de la Cabecera del Menú (Drawer Header) ---
        if (binding.navigationViewMain.headerCount > 0) {
            val headerView = binding.navigationViewMain.getHeaderView(0)
            val tvHeaderTitle = headerView.findViewById<TextView>(R.id.tvHeaderTitle)
            val ivDrawerLogo = headerView.findViewById<ImageView>(R.id.ivDrawerLogo)
            val btnPublish = headerView.findViewById<View>(R.id.btnDrawerPublishService)
            val btnTeam = headerView.findViewById<View>(R.id.btnDrawerManageTeam)
            val btnCollab = headerView.findViewById<View>(R.id.btnDrawerSearchToCollab)
            val btnSwitchSystem = headerView.findViewById<View>(R.id.btnDrawerSwitchSystem)
            val btnMarket = headerView.findViewById<View>(R.id.btnDrawerGoToMarket)
            val btnMyCollabs = headerView.findViewById<View>(R.id.btnDrawerMyCollaborations)
            val btnCollabAccount = headerView.findViewById<View>(R.id.btnDrawerCollabAccount)
            val tvCollabTitle = headerView.findViewById<TextView>(R.id.tvDrawerCollabTitle)
            val tvCollabDesc = headerView.findViewById<TextView>(R.id.tvDrawerCollabDesc)

            // Configurar visibilidad según el modo
            if (isCollabMode) {
                btnTeam?.visibility = View.GONE
                
                // Permitir a colaboradores con permiso editar la tienda del dueño
                // Si es ADMINISTRACIÓN, habilitamos por defecto para facilitar la transición
                val collabRole = collabPrefs.getString("collab_role", "") ?: ""
                val canEditStore = collabPrefs.getBoolean("can_edit_store_info", false) || 
                                   collabRole.contains("ADMINISTRACIÓN")
                
                btnPublish?.visibility = if (canEditStore) View.VISIBLE else View.GONE
                
                btnSwitchSystem?.visibility = View.GONE
                btnCollabAccount?.visibility = View.VISIBLE
                tvCollabTitle?.text = "🏠 Volver a mi Negocio"
                tvCollabDesc?.text = "Regresa a tu panel de gestión personal."
            } else {
                btnTeam?.visibility = if (businessType == "HOTEL") View.VISIBLE else View.GONE
                btnPublish?.visibility = View.VISIBLE
                btnSwitchSystem?.visibility = View.VISIBLE
                btnCollabAccount?.visibility = View.GONE
                tvCollabTitle?.text = "🤝 Mis Colaboraciones"
                tvCollabDesc?.text = "Accede a los negocios donde eres parte del equipo."
            }

            // Asignar listeners una sola vez o re-asignarlos (es seguro en Android)
            btnPublish?.setOnClickListener { 
                binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                if (isCollabMode) {
                    val ownerId = prefs.getString("collaborator_owner_id", null)
                    startToolActivity(Intent(this, StoreProfileActivity::class.java).apply {
                        putExtra("STORE_ID", ownerId)
                        putExtra("IS_OWNER", true)
                        putExtra("FORCE_RUBRO", businessType)
                    })
                } else {
                    startActivity(Intent(this, MyBusinessesActivity::class.java).apply {
                        putExtra("MANAGE_STORE_MODE", true)
                    }) 
                }
            }
            btnTeam?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, HotelManageTeamActivity::class.java)) }
            btnCollab?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, SearchBusinessActivity::class.java)) }
            btnSwitchSystem?.setOnClickListener { 
                binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, MyBusinessesActivity::class.java)) 
            }
            btnMarket?.setOnClickListener { 
                binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                startToolActivity(Intent(this, BuyerHomeActivity::class.java)) 
            }
            btnMyCollabs?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); if (isCollabMode) switchToPersonalBusiness() else showCollaborationsDialog() }
            btnCollabAccount?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, CollabAccountActivity::class.java)) }

            // Identidad del Negocio
            val businessName = when(businessType) {
                "HOTEL" -> prefs.getString("HOTEL_name", "Mi Hospedaje")
                "LOANS" -> prefs.getString("LOANS_name", "Nexur Préstamos")
                else -> prefs.getString("PRODUCTS_name", "Mi Tienda")
            }
            if (isCollabMode) {
                val collabName = collabPrefs.getString("collab_name", "Colaborador")
                val collabRole = collabPrefs.getString("collab_role", "EQUIPO")
                tvHeaderTitle?.text = "$collabName ($collabRole)\n@ $businessName"
            } else {
                tvHeaderTitle?.text = businessName
            }

            // Logo
            val logoPath = prefs.getString("${businessType}_logo_local", null)
            if (!logoPath.isNullOrEmpty()) {
                val file = File(logoPath)
                if (file.exists()) Glide.with(this).load(file).into(ivDrawerLogo!!)
                else ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
            } else {
                ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
            }
        }

        // --- 2. Menú Lateral (Drawer Menu Items) ---
        binding.navigationViewMain.menu.clear()
        if (isCollabMode) {
            if (businessType == "HOTEL") {
                binding.navigationViewMain.inflateMenu(R.menu.menu_hotel_drawer)
                val menu = binding.navigationViewMain.menu
                menu.findItem(R.id.menu_hotel_guests)?.isVisible = collabPrefs.getBoolean("can_view_history", true)
                menu.findItem(R.id.menu_hotel_bookings)?.isVisible = collabPrefs.getBoolean("can_manage_bookings", true)
                menu.findItem(R.id.menu_hotel_payments)?.isVisible = collabPrefs.getBoolean("can_manage_debts", false)
                menu.findItem(R.id.menu_hotel_maintenance)?.isVisible = collabPrefs.getBoolean("can_manage_failures", true)
                menu.findItem(R.id.menu_hotel_tools)?.isVisible = collabPrefs.getBoolean("can_manage_tools", true)
                menu.findItem(R.id.menu_hotel_rooms_graph)?.isVisible = collabPrefs.getBoolean("can_manage_rooms", true)
            } else if (businessType == "LOANS") {
                binding.navigationViewMain.inflateMenu(R.menu.menu_loans_drawer)
            } else {
                binding.navigationViewMain.inflateMenu(R.menu.menu_main_drawer)
            }
        } else {
            if (businessType == "HOTEL") binding.navigationViewMain.inflateMenu(R.menu.menu_hotel_drawer)
            else if (businessType == "LOANS") binding.navigationViewMain.inflateMenu(R.menu.menu_loans_drawer)
            else binding.navigationViewMain.inflateMenu(R.menu.menu_main_drawer)
        }

        // --- 3. Navegación Inferior (Bottom Nav) ---
        if (isCollabMode) {
            val canViewMetrics = collabPrefs.getBoolean("can_view_metrics", false)
            val canManageRooms = collabPrefs.getBoolean("can_manage_rooms", true)
            val canManageInventory = collabPrefs.getBoolean("can_manage_inventory", false)
            binding.btnNavMetricas.visibility = if (canViewMetrics) View.VISIBLE else View.GONE
            binding.btnNavStock.visibility = if (canManageRooms || canManageInventory) View.VISIBLE else View.GONE
            val visibleCount = 2 + (if (canViewMetrics) 1 else 0) + (if (canManageRooms || canManageInventory) 1 else 0)
            binding.layoutCustomNav.weightSum = visibleCount.toFloat()
        } else {
            binding.btnNavMetricas.visibility = View.VISIBLE
            binding.btnNavStock.visibility = View.VISIBLE
            binding.layoutCustomNav.weightSum = 4f
        }

        // Labels dinámicos
        binding.tvLabelStock.text = when(businessType) {
            "HOTEL" -> "Hotel"; "LOANS" -> "Clientes"; else -> "Stock"
        }
        binding.tvIconStock.text = when(businessType) {
            "HOTEL" -> "🏨"; "LOANS" -> "👥"; else -> "📦"
        }
    }

    private fun setupCustomBottomNav() {
        binding.btnNavInicio.setOnClickListener { navigateToInicio() }
        binding.btnNavStock.setOnClickListener { navigateToStock() }
        binding.btnNavMetricas.setOnClickListener { navigateToMetricas() }
        binding.btnNavConfig.setOnClickListener { navigateToSettings() }

        binding.layoutCustomNav.post { updateCustomNavUI(0) }
    }

    private fun updateCustomNavUI(index: Int) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8")
        listOf(binding.tvLabelInicio, binding.tvLabelStock, binding.tvLabelMetricas, binding.tvLabelConfig).forEach { it.setTextColor(inactiveColor) }
        listOf(binding.tvIconInicio, binding.tvIconStock, binding.tvIconMetricas, binding.tvIconConfig).forEach { it.alpha = 0.6f }
        when(index) {
            0 -> { binding.tvLabelInicio.setTextColor(activeColor); binding.tvIconInicio.alpha = 1.0f }
            1 -> { binding.tvLabelStock.setTextColor(activeColor); binding.tvIconStock.alpha = 1.0f }
            2 -> { binding.tvLabelMetricas.setTextColor(activeColor); binding.tvIconMetricas.alpha = 1.0f }
            3 -> { binding.tvLabelConfig.setTextColor(activeColor); binding.tvIconConfig.alpha = 1.0f }
        }

        val totalWidth = binding.layoutCustomNav.width
        val count = if (binding.layoutCustomNav.weightSum > 0) binding.layoutCustomNav.weightSum.toInt() else 4
        if (count > 0 && totalWidth > 0) {
            val step = totalWidth / count
            
            // Ajustar el ancho del indicador dinámicamente según la cantidad de botones visibles
            val params = binding.navIndicatorCustom.layoutParams as ConstraintLayout.LayoutParams
            params.width = step
            params.matchConstraintPercentWidth = 1.0f / count // Alternativa para ConstraintLayout
            binding.navIndicatorCustom.layoutParams = params

            val visibleButtons = mutableListOf<View>()
            if (binding.btnNavInicio.visibility == View.VISIBLE) visibleButtons.add(binding.btnNavInicio)
            if (binding.btnNavStock.visibility == View.VISIBLE) visibleButtons.add(binding.btnNavStock)
            if (binding.btnNavMetricas.visibility == View.VISIBLE) visibleButtons.add(binding.btnNavMetricas)
            if (binding.btnNavConfig.visibility == View.VISIBLE) visibleButtons.add(binding.btnNavConfig)
            val actualIndex = when(index) {
                0 -> visibleButtons.indexOf(binding.btnNavInicio)
                1 -> visibleButtons.indexOf(binding.btnNavStock)
                2 -> visibleButtons.indexOf(binding.btnNavMetricas)
                3 -> visibleButtons.indexOf(binding.btnNavConfig)
                else -> 0
            }
            if (actualIndex >= 0) {
                binding.navIndicatorCustom.animate().translationX((actualIndex * step).toFloat()).setDuration(250).start()
            }
        }
    }

    fun navigateToInicio() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        val fragment = when(businessType) {
            "HOTEL" -> HotelHomeFragment(); "LOANS" -> LoansHomeFragment(); else -> HomeFragment()
        }
        replaceFragment(fragment)
        updateCustomNavUI(0)
    }

    fun navigateToStock(forceGraph: Boolean = false) {
        if (forceGraph) getSharedPreferences("HotelPrefs", MODE_PRIVATE).edit().putBoolean("FORCE_MAP_VIEW", true).apply()
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        val fragment = when(businessType) {
            "HOTEL" -> HotelRoomsFragment(); "LOANS" -> LoansClientsFragment(); else -> StockFragment()
        }
        replaceFragment(fragment)
        updateCustomNavUI(1)
    }

    fun navigateToMetricas() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        val fragment = when(businessType) {
            "HOTEL" -> HotelMetricsFragment(); "LOANS" -> LoansMetricsFragment(); else -> MetricasFragment()
        }
        replaceFragment(fragment)
        updateCustomNavUI(2)
    }

    fun navigateToSettings() {
        replaceFragment(SettingsFragment())
        updateCustomNavUI(3)
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.mainFragmentContainer, fragment).commit()
    }

    fun openSideMenu() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    fun navigateToGestion() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)

    private fun startSyncService() {
        val serviceIntent = Intent(this, GoMezMartSyncService::class.java)
        // Cambiado a startService normal para evitar la notificación persistente
        startService(serviceIntent)
    }

    private fun subscribeToSalesTopic() {
        val userId = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().subscribeToTopic("sales_$userId")
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
                R.id.menu_hotel_guests -> { startToolActivity(Intent(this, HotelGuestHistoryActivity::class.java)); true }
                R.id.menu_hotel_bookings -> { startToolActivity(Intent(this, HotelBookingsActivity::class.java)); true }
                R.id.menu_hotel_payments, R.id.menu_business_debts -> { startToolActivity(Intent(this, BusinessDebtsActivity::class.java)); true }
                R.id.menu_hotel_rooms_graph -> { navigateToStock(true); true }
                R.id.menu_hotel_maintenance -> { startToolActivity(Intent(this, HotelMaintenanceActivity::class.java)); true }
                R.id.menu_hotel_tools -> { startToolActivity(Intent(this, HotelToolsActivity::class.java)); true }
                R.id.menu_loans_clients -> { navigateToStock(); true }
                R.id.menu_loans_collections -> { startToolActivity(Intent(this, LoansCollectionsActivity::class.java)); true }
                R.id.menu_caja -> { startToolActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_gastos -> { startToolActivity(Intent(this, GastosActivity::class.java)); true }
                R.id.menu_settings -> { navigateToSettings(); true }
                R.id.menu_logout -> { showLogoutDialog(); true }
                else -> false
            }
        }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }

    fun checkPinAndNavigate(onSuccess: () -> Unit) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val savedPin = prefs.getString("user_pin", "") ?: ""
        if (savedPin.isEmpty()) { onSuccess(); return }
        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
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

    private fun showLogoutDialog() {
        AlertDialog.Builder(this).setTitle("Cerrar Sesión").setMessage("¿Deseas salir?")
            .setPositiveButton("Salir") { _, _ ->
                lifecycleScope.launch {
                    SessionManager.clearSessionData(this@MainActivity)
                    startActivity(Intent(this@MainActivity, WelcomeRoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun switchToPersonalBusiness() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        
        lifecycleScope.launch {
            // 1. Limpiar base de datos local antes de volver a modo personal
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).clearAllTables()
            }
            
            // 2. Cambiar modo
            prefs.edit().putBoolean("is_collaborator_mode", false).apply()
            
            // Reiniciar servicio de sincronización
            startSyncService()
            
            // 3. Refrescar UI y Sincronizar datos personales
            updateMenuForBusinessType()
            navigateToInicio()
            
            Toast.makeText(this@MainActivity, "Volviendo a tu negocio personal. Sincronizando...", Toast.LENGTH_SHORT).show()
            SyncManager(this@MainActivity).scheduleOfflineSync()
        }
    }

    private fun showCollaborationsDialog() {
        lifecycleScope.launch {
            try {
                val email = auth.currentUser?.email?.lowercase() ?: return@launch
                val accessKeys = db.collection("access_keys").whereEqualTo("email", email).get().await()
                if (accessKeys.isEmpty) {
                    Toast.makeText(this@MainActivity, "No tienes invitaciones activas", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val items = accessKeys.documents.map { doc -> "${doc.getString("businessName")} (${doc.getString("role")})" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("Tus Colaboraciones").setItems(items) { _, which ->
                    val doc = accessKeys.documents[which]
                    switchToCollaborativeEnvironment(doc.getString("ownerId") ?: "", doc.getString("businessType") ?: "HOTEL")
                }.setNegativeButton("Cancelar", null).show()
            } catch (e: Exception) { }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        
        lifecycleScope.launch {
            // 1. Limpiar base de datos local para no mezclar datos personales con los del dueño
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).clearAllTables()
            }
            
            // 2. Establecer nuevo entorno
            prefs.edit().apply {
                putBoolean("is_collaborator_mode", true)
                putString("collaborator_owner_id", ownerId)
                putString("business_type", businessType)
                apply()
            }
            
            // Reiniciar servicio de sincronización
            startSyncService()
            
            // 3. Actualizar UI y Sincronizar datos del dueño
            updateMenuForBusinessType()
            navigateToInicio()
            SyncManager(this@MainActivity).scheduleOfflineSync()
            
            Toast.makeText(this@MainActivity, "Entorno de equipo activado ✨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun schedulePeriodicSync() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("periodic_sync", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    fun startInteractiveTutorial() { showTutorialDialog() }
    fun startDirectScanner() = Toast.makeText(this, "Iniciando Escáner...", Toast.LENGTH_SHORT).show()
    fun generatePDFCatalog() = Toast.makeText(this, "Generando Catálogo PDF...", Toast.LENGTH_SHORT).show()
    fun manualSync() { Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show(); SyncManager(this).scheduleOfflineSync() }

    private fun showTutorialDialog() { currentTutorialStep = 0; showNextTutorialStep() }

    private fun showNextTutorialStep() {
        if (currentTutorialStep < tutorialSteps.size) {
            val step = tutorialSteps[currentTutorialStep]
            val builder = AlertDialog.Builder(this, R.style.Theme_GoMezMart_Dialog)
            builder.setTitle(step.first).setMessage(step.second).setCancelable(false)
                .setPositiveButton(if (currentTutorialStep == tutorialSteps.size - 1) "Empezar" else "Siguiente") { _, _ -> currentTutorialStep++; showNextTutorialStep() }
            if (currentTutorialStep > 0) builder.setNeutralButton("Atrás") { _, _ -> currentTutorialStep--; showNextTutorialStep() }
            builder.create().show()
        } else { getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("tutorial_shown_v5", true).apply() }
    }
}
