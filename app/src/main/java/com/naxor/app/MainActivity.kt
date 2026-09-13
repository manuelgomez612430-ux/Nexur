package com.naxor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
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

    private fun refreshIndicatorPosition() {
        val icons = listOf(binding.tvIconInicio, binding.tvIconStock, binding.tvIconMetricas, binding.tvIconConfig, binding.tvIconMarket)
        val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }
        if (activeIdx != -1) {
            updateCustomNavUI(activeIdx, animate = false)
        }
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
                    if (e != null) return@addSnapshotListener
                    if (snapshot == null || !snapshot.exists()) {
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
                        runOnUiThread { updateMenuForBusinessType() }
                    }
                }
        }
    }

    private fun handleCollaboratorLockout(ownerId: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            var ownerName = "el administrador"
            try {
                val ownerDoc = db.collection("users").document(ownerId).get().await()
                ownerName = ownerDoc.getString("business_owner_name") ?: ownerDoc.getString("business_name") ?: ownerName
            } catch (e: Exception) {}

            val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            prefs.edit().apply { putBoolean("is_collaborator_mode", false); remove("collaborator_owner_id"); apply() }
            withContext(Dispatchers.IO) { AppDatabase.getDatabase(this@MainActivity).clearAllTables() }
            startSyncService()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Acceso Revocado 🚫")
                .setMessage("Has sido retirado del sistema por: $ownerName.")
                .setCancelable(false)
                .setPositiveButton("Entendido") { _, _ ->
                    updateMenuForBusinessType()
                    navigateToInicio()
                    val intent = Intent(this@MainActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }.show()
        }
    }

    fun updateMenuForBusinessType() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)

        if (binding.navigationViewMain.headerCount > 0) {
            val headerView = binding.navigationViewMain.getHeaderView(0)
            val tvHeaderTitle = headerView.findViewById<TextView>(R.id.tvHeaderTitle)
            val ivDrawerLogo = headerView.findViewById<ImageView>(R.id.ivDrawerLogo)
            val btnPublish = headerView.findViewById<View>(R.id.btnDrawerPublishService)
            val btnTeam = headerView.findViewById<View>(R.id.btnDrawerManageTeam)
            val btnCollab = headerView.findViewById<View>(R.id.btnDrawerSearchToCollab)
            val btnSwitchSystem = headerView.findViewById<View>(R.id.btnDrawerSwitchSystem)
            val btnMyCollabs = headerView.findViewById<View>(R.id.btnDrawerMyCollaborations)
            val btnCollabAccount = headerView.findViewById<View>(R.id.btnDrawerCollabAccount)
            val tvCollabTitle = headerView.findViewById<TextView>(R.id.tvDrawerCollabTitle)
            val tvCollabDesc = headerView.findViewById<TextView>(R.id.tvDrawerCollabDesc)
            val tvPublishTitle = headerView.findViewById<TextView>(R.id.tvDrawerPublishTitle)

            val isOnline = prefs.getBoolean("is_online_store", false)

            if (isCollabMode) {
                btnTeam?.visibility = View.GONE
                val collabRole = collabPrefs.getString("collab_role", "") ?: ""
                val canEditStore = collabPrefs.getBoolean("can_edit_store_info", false) || collabRole.contains("ADMINISTRACIÓN")
                btnPublish?.visibility = if (canEditStore) View.VISIBLE else View.GONE
                btnSwitchSystem?.visibility = View.GONE
                btnCollabAccount?.visibility = View.VISIBLE
                tvCollabTitle?.text = "🏠 Volver a mi Negocio"
                tvCollabDesc?.text = "Regresa a tu panel de gestión personal."
                tvPublishTitle?.text = "🌐 Ver tienda del dueño"
            } else {
                btnTeam?.visibility = if (businessType == "HOTEL") View.VISIBLE else View.GONE
                btnPublish?.visibility = View.VISIBLE
                btnSwitchSystem?.visibility = View.VISIBLE
                btnCollabAccount?.visibility = View.GONE
                tvCollabTitle?.text = "🤝 Mis Colaboraciones"
                tvCollabDesc?.text = "Accede a los negocios donde eres parte del equipo."
                tvPublishTitle?.text = if (businessType == "LOANS" && isOnline) "👁️ Ver negocio online" else if (businessType == "LOANS") "🚀 Abrir negocio en mercado" else "🌐 Gestionar tienda online"
            }

            if (isCollabMode) {
                val businessName = collabPrefs.getString("collab_business_name", "Negocio Externo")
                val collabName = collabPrefs.getString("collab_name", "Colaborador")
                val collabRole = collabPrefs.getString("collab_role", "EQUIPO")
                tvHeaderTitle?.text = "$collabName ($collabRole)\n@ $businessName"
            } else {
                tvHeaderTitle?.text = if (businessType == "LOANS") "GoMezMart Préstamos" else prefs.getString("${businessType}_name", "Mi Negocio")
            }

            if (isCollabMode) {
                val collabLogo = collabPrefs.getString("collab_business_logo", null)
                if (!collabLogo.isNullOrEmpty()) Glide.with(this).load(collabLogo).circleCrop().into(ivDrawerLogo!!)
                else ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
            } else {
                val logoPath = prefs.getString("${businessType}_logo_local", null)
                if (!logoPath.isNullOrEmpty()) Glide.with(this).load(File(logoPath)).circleCrop().into(ivDrawerLogo!!)
                else ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
            }

            btnPublish?.setOnClickListener { 
                binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                if (isCollabMode) {
                    val ownerId = prefs.getString("collaborator_owner_id", null)
                    startToolActivity(Intent(this, StoreProfileActivity::class.java).apply { putExtra("STORE_ID", ownerId); putExtra("IS_OWNER", true); putExtra("FORCE_RUBRO", businessType) })
                } else if (businessType == "LOANS") {
                    if (isOnline) startToolActivity(Intent(this, LoanStoreProfileActivity::class.java).apply { putExtra("STORE_ID", auth.currentUser?.uid); putExtra("IS_OWNER", true) })
                    else startToolActivity(Intent(this, LoanStoreEditorActivity::class.java))
                } else {
                    startActivity(Intent(this, MyBusinessesActivity::class.java).apply { putExtra("MANAGE_STORE_MODE", true) }) 
                }
            }
            btnTeam?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, HotelManageTeamActivity::class.java)) }
            btnCollab?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, SearchBusinessActivity::class.java)) }
            btnSwitchSystem?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startActivity(Intent(this, MyBusinessesActivity::class.java)) }
            btnMyCollabs?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); if (isCollabMode) switchToPersonalBusiness() else showCollaborationsDialog() }
            btnCollabAccount?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, CollabAccountActivity::class.java)) }
        }

        binding.navigationViewMain.menu.clear()
        if (isCollabMode && businessType == "HOTEL") {
            binding.navigationViewMain.inflateMenu(R.menu.menu_hotel_drawer)
            val menu = binding.navigationViewMain.menu
            menu.findItem(R.id.menu_hotel_guests)?.isVisible = collabPrefs.getBoolean("can_view_history", true)
            menu.findItem(R.id.menu_hotel_bookings)?.isVisible = collabPrefs.getBoolean("can_manage_bookings", true)
            menu.findItem(R.id.menu_hotel_rooms_graph)?.isVisible = collabPrefs.getBoolean("can_manage_rooms", true)
        } else {
            binding.navigationViewMain.inflateMenu(if(businessType == "HOTEL") R.menu.menu_hotel_drawer else if(businessType == "LOANS") R.menu.menu_loans_drawer else R.menu.menu_main_drawer)
        }

        // --- Navegación Inferior Dinámica (PESO EXACTO) ---
        binding.btnNavInicio.isVisible = true
        binding.btnNavConfig.isVisible = true

        if (isCollabMode) {
            binding.btnNavMetricas.isVisible = collabPrefs.getBoolean("can_view_metrics", false)
            binding.btnNavStock.isVisible = collabPrefs.getBoolean("can_manage_rooms", true) || collabPrefs.getBoolean("can_manage_inventory", false)
        } else {
            binding.btnNavMetricas.isVisible = true
            binding.btnNavStock.isVisible = true
        }

        val hasHotel = !prefs.getString("HOTEL_name", "").isNullOrEmpty()
        val hasProducts = !prefs.getString("PRODUCTS_name", "").isNullOrEmpty()
        val hasLoans = !prefs.getString("LOANS_name", "").isNullOrEmpty()
        val hasBiz = hasHotel || hasProducts || hasLoans || !prefs.getString("business_type", "").isNullOrEmpty()
        
        binding.btnNavMarket.isVisible = isCollabMode || hasBiz

        // Sincronización del indicador
        refreshIndicatorPosition()
    }

    private fun setupCustomBottomNav() {
        binding.btnNavInicio.setOnClickListener { navigateToInicio(); updateCustomNavUI(0) }
        binding.btnNavStock.setOnClickListener { navigateToStock(); updateCustomNavUI(1) }
        binding.btnNavMetricas.setOnClickListener { navigateToMetricas(); updateCustomNavUI(2) }
        binding.btnNavConfig.setOnClickListener { navigateToSettings(); updateCustomNavUI(3) }
        binding.btnNavMarket.setOnClickListener { 
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            startToolActivity(Intent(this, BuyerHomeActivity::class.java)) 
        }

        binding.layoutCustomNav.post {
            refreshIndicatorPosition()
        }
    }

    private fun refreshIndicatorPosition() {
        val icons = listOf(binding.tvIconInicio, binding.tvIconStock, binding.tvIconMetricas, binding.tvIconConfig, binding.tvIconMarket)
        val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }
        if (activeIdx != -1) {
            updateCustomNavUI(activeIdx, animate = false)
        }
    }

    private fun updateCustomNavUI(index: Int, animate: Boolean = true) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8")
        val buttons = listOf(binding.btnNavInicio, binding.btnNavStock, binding.btnNavMetricas, binding.btnNavConfig, binding.btnNavMarket)
        val icons = listOf(binding.tvIconInicio, binding.tvIconStock, binding.tvIconMetricas, binding.tvIconConfig, binding.tvIconMarket)
        val labels = listOf(binding.tvLabelInicio, binding.tvLabelStock, binding.tvLabelMetricas, binding.tvLabelConfig, binding.tvLabelMarket)
        
        // 1. Identificar el botón objetivo real (debe ser visible)
        val visibleButtons = buttons.filter { it.isVisible }
        if (visibleButtons.isEmpty()) return

        var targetIdx = index
        if (targetIdx !in buttons.indices || !buttons[targetIdx].isVisible) {
            targetIdx = buttons.indexOf(visibleButtons.firstOrNull() ?: buttons[0])
        }

        // 2. Sincronización visual inmediata (Solo si cambió el estado)
        labels.forEachIndexed { i, tv -> 
            val color = if (i == targetIdx) activeColor else inactiveColor
            if (tv.currentTextColor != color) tv.setTextColor(color)
        }
        icons.forEachIndexed { i, iv -> 
            val alpha = if (i == targetIdx) 1.0f else 0.6f
            if (iv.alpha != alpha) iv.alpha = alpha
        }

        val targetView = buttons[targetIdx]
        
        // Función de alineación milimétrica
        val alignAction = Runnable {
            if (!this::binding.isInitialized) return@Runnable
            
            val w = targetView.width
            val x = targetView.x
            
            if (w > 0) {
                val params = binding.navIndicatorCustom.layoutParams
                if (params.width != w) {
                    params.width = w
                    binding.navIndicatorCustom.layoutParams = params
                }
                
                if (animate) {
                    binding.navIndicatorCustom.animate()
                        .translationX(x)
                        .setDuration(150) // Reducido para más agilidad
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    binding.navIndicatorCustom.translationX = x
                }
            }
        }

        if (targetView.width > 0) alignAction.run() else targetView.post(alignAction)
    }

    fun navigateToInicio() {
        val bType = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_type", "PRODUCTS")
        replaceFragment(when(bType) { "HOTEL" -> HotelHomeFragment(); "LOANS" -> LoansHomeFragment(); else -> HomeFragment() })
        updateCustomNavUI(0)
    }

    fun navigateToStock(forceGraph: Boolean = false) {
        if (forceGraph) getSharedPreferences("HotelPrefs", MODE_PRIVATE).edit().putBoolean("FORCE_MAP_VIEW", true).apply()
        val bType = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_type", "PRODUCTS")
        replaceFragment(when(bType) { "HOTEL" -> HotelRoomsFragment(); "LOANS" -> LoansClientsFragment(); else -> StockFragment() })
        updateCustomNavUI(1)
    }

    fun navigateToMetricas() {
        val bType = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_type", "PRODUCTS")
        replaceFragment(when(bType) { "HOTEL" -> HotelMetricsFragment(); "LOANS" -> LoansMetricsFragment(); else -> MetricasFragment() })
        updateCustomNavUI(2)
    }

    fun navigateToSettings() { replaceFragment(SettingsFragment()); updateCustomNavUI(3) }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.mainFragmentContainer, fragment)
            .commit()
    }
    fun openSideMenu() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    fun navigateToGestion() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    private fun startSyncService() { startService(Intent(this, GoMezMartSyncService::class.java)) }
    private fun subscribeToSalesTopic() { val userId = auth.currentUser?.uid ?: return; FirebaseMessaging.getInstance().subscribeToTopic("sales_$userId") }
    override fun onResume() { super.onResume(); updateMenuForBusinessType(); SyncManager(this).scheduleOfflineSync() }

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

    private fun startToolActivity(intent: Intent) { startActivity(intent); overridePendingTransition(R.anim.slide_in_up, R.anim.stay) }

    fun checkPinAndNavigate(onSuccess: () -> Unit) {
        val savedPin = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("user_pin", "") ?: ""
        if (savedPin.isEmpty()) { onSuccess(); return }
        val etPin = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Escribe el PIN" }
        AlertDialog.Builder(this).setTitle("Acceso Restringido").setMessage("Ingresa tu PIN:").setView(etPin).setPositiveButton("Entrar") { _, _ -> if (etPin.text.toString() == savedPin) onSuccess() else Toast.makeText(this, "PIN Incorrecto", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancelar", null).show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this).setTitle("Cerrar Sesión").setMessage("¿Deseas salir?").setPositiveButton("Salir") { _, _ -> lifecycleScope.launch { SessionManager.clearSessionData(this@MainActivity); startActivity(Intent(this@MainActivity, WelcomeRoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() } }.setNegativeButton("Cancelar", null).show()
    }

    private fun switchToPersonalBusiness() {
        val user = auth.currentUser ?: return
        lifecycleScope.launch {
            val loading = AlertDialog.Builder(this@MainActivity).setMessage("Cambiando...").setCancelable(false).show()
            try {
                val settingsSnap = db.collection("users").document(user.uid).collection("settings").get().await()
                val ownBusinesses = settingsSnap.documents.filter { doc -> doc.id != "business" && (doc.getString("business_name") ?: "").isNotEmpty() }
                withContext(Dispatchers.IO) { AppDatabase.getDatabase(this@MainActivity).clearAllTables() }
                getSharedPreferences("CollabPermissions", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().putBoolean("is_collaborator_mode", false).apply()
                loading.dismiss()
                if (ownBusinesses.size == 1) { getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().putString("business_type", ownBusinesses[0].id).apply(); startSyncService(); updateMenuForBusinessType(); navigateToInicio(); SyncManager(this@MainActivity).scheduleOfflineSync() }
                else if (ownBusinesses.size > 1) { val intent = Intent(this@MainActivity, MyBusinessesActivity::class.java); intent.putExtra("OWN_ONLY_MODE", true); intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK; startActivity(intent); finish() }
                else { startActivity(Intent(this@MainActivity, BusinessSelectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() }
            } catch (e: Exception) { loading.dismiss() }
        }
    }

    private fun showCollaborationsDialog() {
        lifecycleScope.launch {
            try {
                val email = auth.currentUser?.email?.lowercase() ?: return@launch
                val accessKeys = db.collection("access_keys").whereEqualTo("email", email).get().await()
                if (accessKeys.isEmpty) { Toast.makeText(this@MainActivity, "No tienes invitaciones", Toast.LENGTH_SHORT).show(); return@launch }
                val items = accessKeys.documents.map { "${it.getString("businessName")} (${it.getString("role")})" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("Tus Colaboraciones").setItems(items) { _, which -> val doc = accessKeys.documents[which]; switchToCollaborativeEnvironment(doc.getString("ownerId") ?: "", doc.getString("businessType") ?: "HOTEL", doc.getString("businessName")) }.setNegativeButton("Cancelar", null).show()
            } catch (e: Exception) { }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String, name: String? = null) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AppDatabase.getDatabase(this@MainActivity).clearAllTables() }
            getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().apply { putBoolean("is_collaborator_mode", true); putString("collaborator_owner_id", ownerId); putString("business_type", businessType); apply() }
            if (name != null) getSharedPreferences("CollabPermissions", MODE_PRIVATE).edit().putString("collab_business_name", name).apply()
            startSyncService(); updateMenuForBusinessType(); navigateToInicio(); SyncManager(this@MainActivity).scheduleOfflineSync()
        }
    }

    private fun schedulePeriodicSync() { val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS).build(); WorkManager.getInstance(this).enqueueUniquePeriodicWork("periodic_sync", ExistingPeriodicWorkPolicy.KEEP, syncRequest) }
    fun startInteractiveTutorial() { showTutorialDialog() }
    fun startDirectScanner() = Toast.makeText(this, "Escáner...", Toast.LENGTH_SHORT).show()
    fun generatePDFCatalog() = Toast.makeText(this, "PDF...", Toast.LENGTH_SHORT).show()
    fun manualSync() { SyncManager(this).scheduleOfflineSync() }
    private fun showTutorialDialog() { currentTutorialStep = 0; showNextTutorialStep() }
    private fun showNextTutorialStep() { if (currentTutorialStep < tutorialSteps.size) { val step = tutorialSteps[currentTutorialStep]; AlertDialog.Builder(this, R.style.Theme_GoMezMart_Dialog).setTitle(step.first).setMessage(step.second).setCancelable(false).setPositiveButton(if (currentTutorialStep == tutorialSteps.size - 1) "Empezar" else "Siguiente") { _, _ -> currentTutorialStep++; showNextTutorialStep() }.create().show() } }
}
