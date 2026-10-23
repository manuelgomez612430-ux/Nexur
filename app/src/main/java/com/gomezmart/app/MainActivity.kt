package com.gomezmart.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.gomezmart.app.data.CollaboratorEntity
import com.gomezmart.app.data.AppDatabase
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.gomezmart.app.databinding.ActivityMainBinding
import com.gomezmart.app.fragment.*
import com.gomezmart.app.util.SessionManager
import com.gomezmart.app.network.GomezmartSyncService
import com.gomezmart.app.util.FavoritesManager
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

    private var loadJob: Job? = null
    private val registrations = mutableListOf<ListenerRegistration>()
    private var doubleBackToExitPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar Insets para evitar que la interfaz sea obstruida por botones del sistema o notificaciones
        // Quitamos applyTop = true porque los fragmentos manejarán su propia cabecera (Edge-to-Edge)
        applyWindowInsets(binding.mainContentRoot, applyTop = false, applyBottom = false)
        applyWindowInsetsMargins(binding.cardBottomNavContainer, applyTop = false, applyBottom = true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.navigationViewMain) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, statusBars.top, 0, navBars.bottom)
            insets
        }

        setupListeners()
        setupCustomBottomNav()
        checkNotificationPermission()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayoutMain.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    return
                }

                if (lastActiveNavIndex != 0) {
                    navigateToInicio()
                } else {
                    if (doubleBackToExitPressedOnce) {
                        finish()
                        return
                    }
                    doubleBackToExitPressedOnce = true
                    Toast.makeText(this@MainActivity, "Presiona otra vez para salir", Toast.LENGTH_SHORT).show()
                    window.decorView.postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
                }
            }
        })
        
        if (savedInstanceState == null) {
            navigateToInicio()
        }
        
        // ASEGURAR IDENTIDAD: Registrarse en el buscador global al iniciar
        updatePublicIdentity()
        FavoritesManager.loadFromFirebase(this)

        lifecycleScope.launch {
            try {
                delay(5000) // Mucho más margen para el Updater
                if (intent.getBooleanExtra("START_UPDATE", false)) {
                    Updater(this@MainActivity).checkAndDownload(autoDownload = true)
                }
                subscribeToSalesTopic()
                schedulePeriodicSync()
            } catch (e: Exception) {}
        }
    }

    private fun updatePublicIdentity() {
        val user = auth.currentUser ?: return
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
        val businessType = bizPrefs.getString("business_type", "") ?: ""
        val bizName = bizPrefs.getString("${businessType}_name", null) ?: bizPrefs.getString("business_name", "") ?: ""
        val bizLogo = bizPrefs.getString("${businessType}_logo_url", null) ?: bizPrefs.getString("business_logo_url", "") ?: ""
        
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
        val collabBizName = collabPrefs.getString("collab_business_name", "") ?: ""
        val collabBizLogo = collabPrefs.getString("collab_business_logo", "") ?: ""
        
        // [NUEVO] VERIFICACIÓN DE ESTADO PÚBLICO
        // Solo si el negocio está marcado como ONLINE y tiene nombre, aparece como dueño
        val isOnline = bizPrefs.getBoolean("is_online_store", false)

        val title = when {
            bizName.isNotEmpty() && collabBizName.isNotEmpty() -> "Gestionando y Colaborando"
            bizName.isNotEmpty() && isOnline -> "Dueño de $bizName" 
            collabBizName.isNotEmpty() -> "Colaborador en $collabBizName"
            else -> "Comprador"
        }

        val publicLogo = if (bizLogo.isNotEmpty()) bizLogo else collabBizLogo

        // Obtener datos reales de identidad desde AppPrefs o Auth
        val realName = appPrefs.getString("user_full_name", null) ?: user.displayName ?: "Usuario Gomezmart"
        val realPhoto = appPrefs.getString("user_photo_url", null) ?: user.photoUrl?.toString() ?: ""

        val updates = mutableMapOf<String, Any>(
            "businessTitle" to title,
            "name" to realName,
            "photoUrl" to realPhoto,
            "user_photo_url" to realPhoto,
            "email" to (user.email ?: "")
        )
        
        if (publicLogo.isNotEmpty()) updates["businessLogoUrl"] = publicLogo

        db.collection("users").document(user.uid).collection("profile").document("info")
            .set(updates, SetOptions.merge())
            
        val rootUpdates = mapOf(
            "name" to realName,
            "photoUrl" to realPhoto,
            "businessTitle" to title,
            "email" to (user.email ?: ""),
            "phone" to (appPrefs.getString("user_phone", "") ?: "")
        )
        db.collection("users").document(user.uid).set(rootUpdates, SetOptions.merge())
    }

    private fun startCloudSyncListeners() {
        val user = auth.currentUser ?: return
        
        // 1. Escuchar info del Perfil (Nombre completo desde la cuenta)
        val regProfile = db.collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val name = snapshot.getString("name")
                    val photo = snapshot.getString("photoUrl")
                    
                    // Asegurar que Firestore tenga la foto de Google si está vacía
                    if (photo.isNullOrEmpty() && user.photoUrl != null) {
                        db.collection("users").document(user.uid).collection("profile").document("info")
                            .update("photoUrl", user.photoUrl.toString())
                    }

                    val edit = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                    if (!name.isNullOrEmpty()) edit.putString("user_full_name", name)
                    if (!photo.isNullOrEmpty()) edit.putString("user_photo_url", photo)
                    edit.apply()
                    
                    // [CORRECCIÓN] Eliminamos updatePublicIdentity() de aquí para evitar bucles infinitos
                    runOnUiThread { updateMenuForBusinessType() }
                }
            }
        registrations.add(regProfile)

        // 2. Escuchar info del Negocio actual (Nombre, Logo y Nombre del Dueño)
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        val syncBusinessInfo = { docId: String ->
            val regBiz = db.collection("users").document(user.uid).collection("settings").document(docId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val bizName = snapshot.getString("business_name") ?: snapshot.getString("${docId}_name")
                        val logoUrl = snapshot.getString("business_logo_url")
                        val ownerName = snapshot.getString("business_owner_name")
                        
                        val editor = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                        if (!bizName.isNullOrEmpty()) editor.putString("${businessType}_name", bizName)
                        if (!logoUrl.isNullOrEmpty()) editor.putString("${businessType}_logo_url", logoUrl)
                        
                        // Sincronizar el nombre del dueño con el perfil si este está vacío
                        if (!ownerName.isNullOrEmpty()) {
                            val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                            if (appPrefs.getString("user_full_name", null).isNullOrEmpty()) {
                                appPrefs.edit().putString("user_full_name", ownerName).apply()
                            }
                        }
                        
                        editor.apply()
                        runOnUiThread { updateMenuForBusinessType() }
                    }
                }
            registrations.add(regBiz)
        }

        syncBusinessInfo(businessType)
        if (businessType != "business") syncBusinessInfo("business") // Fallback
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
            val regCollab = db.collection("users").document(ownerId)
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
            registrations.add(regCollab)
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
            prefs.edit().apply { 
                putBoolean("is_collaborator_mode", false)
                remove("collaborator_owner_id")
                apply() 
            }
            
            withContext(Dispatchers.IO) { 
                AppDatabase.getDatabase(this@MainActivity).clearAllTables() 
            }
            
            // Cerrar escuchadores activos antes de reiniciar
            registrations.forEach { it.remove() }
            registrations.clear()
            
            startSyncService()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Acceso Revocado 🚫")
                .setMessage("Has sido retirado del sistema por: $ownerName.")
                .setCancelable(false)
                .setPositiveButton("Entendido") { _, _ ->
                    val intent = Intent(this@MainActivity, WelcomeRoleActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }.show()
        }
    }

    fun updateMenuForBusinessType() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = getEffectiveBusinessType()
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)

        if (binding.navigationViewMain.headerCount > 0) {
            val headerView = binding.navigationViewMain.getHeaderView(0)
            
            val tvHeaderTitle = headerView.findViewById<TextView>(R.id.tvHeaderTitle)
            val ivDrawerLogo = headerView.findViewById<ImageView>(R.id.ivDrawerLogo)
            val btnTeam = headerView.findViewById<View>(R.id.btnDrawerManageTeam)
            val btnCollab = headerView.findViewById<View>(R.id.btnDrawerSearchToCollab)
            val btnCollabAccount = headerView.findViewById<View>(R.id.btnDrawerCollabAccount)

            if (isCollabMode) {
                btnTeam?.visibility = View.GONE
                btnCollabAccount?.visibility = View.VISIBLE
            } else {
                btnTeam?.visibility = if (businessType == "HOTEL") View.VISIBLE else View.GONE
                btnCollabAccount?.visibility = View.GONE
            }

            if (isCollabMode) {
                val businessName = collabPrefs.getString("collab_business_name", "Negocio Externo")
                val collabName = collabPrefs.getString("collab_name", "Colaborador")
                val collabRole = collabPrefs.getString("collab_role", "EQUIPO")
                tvHeaderTitle?.text = "$collabName ($collabRole)\n@ $businessName"
            } else {
                // CORRECCIÓN: Respetar strictly el sistema activo para evitar mezclar datos
                val bizName = if (businessType != "PRODUCTS") {
                    prefs.getString("${businessType}_name", "Mi Negocio")
                } else {
                    prefs.getString("${businessType}_name", null) ?: prefs.getString("business_name", "Mi Negocio")
                }
                tvHeaderTitle?.text = if (bizName.isNullOrEmpty() || bizName == "Mi Negocio") "Mi Negocio" else bizName
            }

            if (isCollabMode) {
                val collabLogo = collabPrefs.getString("collab_business_logo", null)
                if (!collabLogo.isNullOrEmpty()) {
                    ivDrawerLogo?.let { Glide.with(this).load(collabLogo).circleCrop().into(it) }
                } else {
                    ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
                }
            } else {
                val logoUrl = prefs.getString("${businessType}_logo_url", null)
                val logoPath = prefs.getString("${businessType}_logo_local", null)
                
                when {
                    !logoUrl.isNullOrEmpty() -> {
                        ivDrawerLogo?.let { Glide.with(this).load(logoUrl).circleCrop().into(it) }
                    }
                    !logoPath.isNullOrEmpty() -> {
                        ivDrawerLogo?.let { Glide.with(this).load(File(logoPath)).circleCrop().into(it) }
                    }
                    else -> {
                        ivDrawerLogo?.setImageResource(R.drawable.logotipo_de_gomezmart)
                    }
                }
            }

            btnTeam?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, HotelManageTeamActivity::class.java)) }
            btnCollab?.setOnClickListener { binding.drawerLayoutMain.closeDrawer(GravityCompat.START); startToolActivity(Intent(this, SearchBusinessActivity::class.java)) }
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
        
        // CORRECCIÓN: Labels dinámicos para el menú inferior
        binding.tvLabelStock.text = when(businessType) { 
            "HOTEL" -> "Hotel"
            "LOANS" -> "Clientes"
            else -> "Productos" 
        }
        binding.tvIconStock.text = when(businessType) { 
            "HOTEL" -> "🏨"
            "LOANS" -> "👥"
            else -> "📦" 
        }

        // Sincronización del indicador
        refreshIndicatorPosition()
    }

    private var lastActiveNavIndex = 0

    private fun setupCustomBottomNav() {
        binding.btnNavInicio.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 0; navigateToInicio(); updateCustomNavUI(0) }
        binding.btnNavStock.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 1; navigateToStock(); updateCustomNavUI(1) }
        binding.btnNavMetricas.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 2; navigateToMetricas(); updateCustomNavUI(2) }
        binding.btnNavConfig.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 3; navigateToSettings(); updateCustomNavUI(3) }
        binding.btnNavMarket.setOnClickListener { 
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            showFloatingInterfaceMenu()
        }

        binding.layoutCustomNav.post {
            refreshIndicatorPosition()
        }
    }

    private fun openMarketBusiness() {
        binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val isOnline = prefs.getBoolean("is_online_store", false)

        if (isCollabMode) {
            val ownerId = prefs.getString("collaborator_owner_id", null)
            startToolActivity(Intent(this, StoreProfileActivity::class.java).apply { 
                putExtra("STORE_ID", ownerId)
                putExtra("IS_OWNER", true)
                putExtra("FORCE_RUBRO", businessType) 
            })
        } else if (businessType == "LOANS") {
            if (isOnline) {
                startToolActivity(Intent(this, LoanStoreProfileActivity::class.java).apply { 
                    putExtra("STORE_ID", auth.currentUser?.uid)
                    putExtra("IS_OWNER", true) 
                })
            } else {
                startToolActivity(Intent(this, LoanStoreEditorActivity::class.java))
            }
        } else {
            startToolActivity(Intent(this, StoreProfileActivity::class.java).apply { 
                putExtra("STORE_ID", auth.currentUser?.uid)
                putExtra("IS_OWNER", true)
                putExtra("FORCE_RUBRO", businessType)
                putExtra("STORE_NAME", prefs.getString("${businessType}_name", "Mi Negocio"))
            })
        }
    }

    private fun showFloatingInterfaceMenu() {
        if (binding.cardInterfaceMenuOverlay.visibility == View.VISIBLE) {
            hideFloatingInterfaceMenu()
            return
        }

        updateCustomNavUI(4, animate = false) // Salta instantáneamente al botón Interfaz sin animación de deslizamiento

        binding.viewInterfaceBackdrop.visibility = View.VISIBLE
        binding.cardInterfaceMenuOverlay.visibility = View.VISIBLE
        binding.cardInterfaceMenuOverlay.translationY = 60f
        binding.cardInterfaceMenuOverlay.alpha = 0f

        binding.cardInterfaceMenuOverlay.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val optionsBinding = binding.includeInterfaceOptions
        val cards = listOf(
            optionsBinding.cardOptionMarket,
            optionsBinding.cardOptionOpenStore,
            optionsBinding.cardOptionSwitchSystem,
            optionsBinding.cardOptionMyCollabs
        )

        cards.forEachIndexed { i, card ->
            card.translationY = 40f
            card.alpha = 0f
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(200)
                .setStartDelay((i * 50).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.viewInterfaceBackdrop.setOnClickListener {
            hideFloatingInterfaceMenu()
        }

        optionsBinding.cardOptionMarket.setOnClickListener {
            hideFloatingInterfaceMenu()
            startToolActivity(Intent(this, BuyerHomeActivity::class.java))
        }

        optionsBinding.cardOptionOpenStore.setOnClickListener {
            hideFloatingInterfaceMenu()
            openMarketBusiness()
        }

        optionsBinding.cardOptionSwitchSystem.setOnClickListener {
            hideFloatingInterfaceMenu()
            startActivity(Intent(this, MyBusinessesActivity::class.java))
        }

        optionsBinding.cardOptionMyCollabs.setOnClickListener {
            hideFloatingInterfaceMenu()
            val intent = Intent(this, MyBusinessesActivity::class.java).apply {
                putExtra("COLLAB_ONLY_MODE", true)
            }
            startActivity(intent)
        }
    }

    private fun hideFloatingInterfaceMenu() {
        if (!this::binding.isInitialized || binding.cardInterfaceMenuOverlay.visibility == View.GONE) return

        binding.cardInterfaceMenuOverlay.animate()
            .translationY(40f)
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.cardInterfaceMenuOverlay.visibility = View.GONE
                binding.viewInterfaceBackdrop.visibility = View.GONE
                updateCustomNavUI(lastActiveNavIndex, animate = false) // Salta instantáneamente al último botón activo
            }
            .start()
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

        // 2. Sincronización visual inmediata (Evita jank visual)
        labels.forEachIndexed { i, tv -> 
            val color = if (i == targetIdx) activeColor else inactiveColor
            if (tv.currentTextColor != color) tv.setTextColor(color)
        }
        icons.forEachIndexed { i, iv -> 
            val alpha = if (i == targetIdx) 1.0f else 0.6f
            if (iv.alpha != alpha) iv.alpha = alpha
        }

        val targetView = buttons[targetIdx]
        
        // Función de alineación milimétrica optimizada
        val r = Runnable {
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
                        .setDuration(120) // Más rápido para mejor sensación
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    binding.navIndicatorCustom.animate().cancel()
                    binding.navIndicatorCustom.translationX = x
                }
            }
        }

        if (targetView.width > 0) r.run() else targetView.post(r)
    }

    private fun replaceFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        // Animaciones suaves y ligeras para evitar congelamientos al navegar
        transaction.setCustomAnimations(
            android.R.anim.fade_in, 
            android.R.anim.fade_out
        )
        transaction.replace(R.id.mainFragmentContainer, fragment)
        transaction.commit()
    }

    fun getEffectiveBusinessType(): String {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bType = prefs.getString("business_type", null)
        val hasLoans = prefs.getString("LOANS_name", "")?.isNotEmpty() == true
        val hasHotel = prefs.getString("HOTEL_name", "")?.isNotEmpty() == true

        if (bType.isNullOrEmpty() || bType == "PRODUCTS") {
            if (hasLoans) {
                prefs.edit().putString("business_type", "LOANS").apply()
                return "LOANS"
            }
        }

        if (!bType.isNullOrEmpty()) return bType

        val activeType = if (hasLoans) "LOANS" else if (hasHotel) "HOTEL" else "LOANS"
        prefs.edit().putString("business_type", activeType).apply()
        return activeType
    }

    private fun autoCorrectBusinessTypeIfNeeded() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            val currentType = prefs.getString("business_type", null)
            val dbInstance = AppDatabase.getDatabase(this@MainActivity)
            
            val loansCount = try { dbInstance.loanDao().getAllLoansOnce().size } catch(e: Exception) { 0 }
            val clientsCount = try { dbInstance.loanDao().getAllClientsOnce().size } catch(e: Exception) { 0 }
            val productsCount = try { dbInstance.productDao().allProducts.size } catch(e: Exception) { 0 }
            val hasLoansPref = prefs.getString("LOANS_name", "")?.isNotEmpty() == true

            if ((loansCount > 0 || clientsCount > 0 || hasLoansPref) && productsCount == 0) {
                if (currentType != "LOANS") {
                    prefs.edit().putString("business_type", "LOANS").apply()
                    withContext(Dispatchers.Main) {
                        updateMenuForBusinessType()
                        navigateToInicio()
                    }
                }
            }
        }
    }

    override fun onResume() { 
        super.onResume()
        autoCorrectBusinessTypeIfNeeded()
        updateMenuForBusinessType()
        SyncManager(this).scheduleOfflineSync() 
    }

    fun navigateToInicio() {
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelHomeFragment(); "LOANS" -> LoansHomeFragment(); else -> HomeFragment() })
        updateCustomNavUI(0)
    }

    fun navigateToStock(forceGraph: Boolean = false) {
        if (forceGraph) getSharedPreferences("HotelPrefs", MODE_PRIVATE).edit().putBoolean("FORCE_MAP_VIEW", true).apply()
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelRoomsFragment(); "LOANS" -> LoansClientsFragment(); else -> StockFragment() })
        updateCustomNavUI(1)
    }

    fun navigateToMetricas() {
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelMetricsFragment(); "LOANS" -> LoansMetricsFragment(); else -> MetricasFragment() })
        updateCustomNavUI(2)
    }

    fun navigateToSettings() { replaceFragment(SettingsFragment()); updateCustomNavUI(3) }


    fun openSideMenu() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    fun navigateToGestion() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    private fun startSyncService() {
        try {
            val intent = Intent(this, GomezmartSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting sync service: ${e.message}")
        }
    }
    private fun subscribeToSalesTopic() { val userId = auth.currentUser?.uid ?: return; FirebaseMessaging.getInstance().subscribeToTopic("sales_$userId") }

    private fun setupListeners() {
        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_hotel_guests -> { startToolActivity(Intent(this, HotelGuestHistoryActivity::class.java)); true }
                R.id.menu_hotel_bookings -> { startToolActivity(Intent(this, HotelBookingsActivity::class.java)); true }
                R.id.menu_hotel_payments -> { startToolActivity(Intent(this, BusinessDebtsActivity::class.java)); true }
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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun startToolActivity(intent: Intent) { startActivityWithFade(intent) }

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

    override fun onStart() {
        super.onStart()
        
        lifecycleScope.launch {
            try {
                // Retardo de seguridad para dispositivos con GMS inestable (Honor/Huawei)
                delay(2500)
                
                // Activar sincronización en segundo plano
                startSyncService()
                
                // ASEGURAR IDENTIDAD: Registrarse en el buscador global al iniciar
                updatePublicIdentity()
                
                checkCollaboratorAccess()
                startCloudSyncListeners()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting background services: ${e.message}")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Ya NO detenemos los escuchadores al salir, para que lleguen notificaciones en segundo plano
        
        // Limpiar escuchadores de la actividad para evitar fugas
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ya no detenemos el servicio para que siga notificando
    }

    private fun schedulePeriodicSync() {
        // IMPORTANTE: Sin constraint de red el trabajo se ejecutaba cada 15 min
        // aunque no hubiera conexión, fallando ~40 escrituras y agotando la
        // batería y los datos móviles. Ahora solo corre con red disponible.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "periodic_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
    fun startInteractiveTutorial() { showTutorialDialog() }
    fun startDirectScanner() = Toast.makeText(this, "Escáner...", Toast.LENGTH_SHORT).show()

    fun manualSync() { SyncManager(this).scheduleOfflineSync() }
    private fun showTutorialDialog() { currentTutorialStep = 0; showNextTutorialStep() }
    private fun showNextTutorialStep() { if (currentTutorialStep < tutorialSteps.size) { val step = tutorialSteps[currentTutorialStep]; AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog).setTitle(step.first).setMessage(step.second).setCancelable(false).setPositiveButton(if (currentTutorialStep == tutorialSteps.size - 1) "Empezar" else "Siguiente") { _, _ -> currentTutorialStep++; showNextTutorialStep() }.create().show() } }
}
