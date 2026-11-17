package com.gomezmart.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import androidx.core.view.doOnLayout
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayoutMain.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    return
                }

                if (lastActiveNavIndex != 0) {
                    navigateToInicio()
                    return
                }

                // Mover al fondo en lugar de matar la app
                moveTaskToBack(true)
            }
        })
        
        if (savedInstanceState != null) {
            lastActiveNavIndex = savedInstanceState.getInt("saved_nav_index", 0)
            binding.layoutCustomNav.post { refreshIndicatorPosition() }
        } else if (checkCurrentModuleInstalled()) {
            navigateToInicio()
        }
        
        // ASEGURAR IDENTIDAD: Registrarse en el buscador global al iniciar
        updatePublicIdentity()
        FavoritesManager.loadFromFirebase(this)

        lifecycleScope.launch {
            try {
                delay(5000) // Mucho más margen para el Updater
                if (intent.getBooleanExtra("START_UPDATE", false)) {
                    Updater(this@MainActivity).checkAndDownload(autoCheck = true)
                }
                subscribeToSalesTopic()
                schedulePeriodicSync()
            } catch (e: Exception) {}
        }
    }

    private fun updatePublicIdentity() {
        if (isGuestUser()) return
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
        val storedName = appPrefs.getString("user_full_name", null) ?: user.displayName ?: ""
        val realPhoto = appPrefs.getString("user_photo_url", null) ?: user.photoUrl?.toString() ?: ""

        val updates = mutableMapOf<String, Any>(
            "businessTitle" to title,
            "photoUrl" to realPhoto,
            "user_photo_url" to realPhoto,
            "email" to (user.email ?: "")
        )
        if (!SessionManager.isPlaceholderName(storedName)) {
            updates["name"] = storedName.trim()
        }
        
        if (publicLogo.isNotEmpty()) updates["businessLogoUrl"] = publicLogo

        db.collection("users").document(user.uid).collection("profile").document("info")
            .set(updates, SetOptions.merge())
            
        val rootUpdates = mutableMapOf<String, Any>(
            "photoUrl" to realPhoto,
            "businessTitle" to title,
            "email" to (user.email ?: ""),
            "phone" to (appPrefs.getString("user_phone", "") ?: "")
        )
        if (!SessionManager.isPlaceholderName(storedName)) {
            rootUpdates["name"] = storedName.trim()
        }
        db.collection("users").document(user.uid).set(rootUpdates, SetOptions.merge())
    }

    private fun startCloudSyncListeners() {
        if (isGuestUser()) return
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
        updateCustomNavUI(lastActiveNavIndex, animate = false)
    }

    private fun checkCollaboratorAccess() {
        if (isGuestUser()) return
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
                        editor.apply() 
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
            val btnCollabAccount = headerView.findViewById<View>(R.id.btnDrawerCollabAccount)

            if (isCollabMode) {
                btnCollabAccount?.visibility = View.VISIBLE
            } else {
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
        binding.btnNavConfig.isVisible = false

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
        
        binding.btnNavMarket.isVisible = isCollabMode || hasBiz || isGuestUser()
        
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
    private var pendingIndicatorRunnable: Runnable? = null

    private fun setupCustomBottomNav() {
        binding.btnNavInicio.setOnClickListener { hideFloatingInterfaceMenu(immediate = true); lastActiveNavIndex = 0; navigateToInicio() }
        binding.btnNavStock.setOnClickListener { hideFloatingInterfaceMenu(immediate = true); lastActiveNavIndex = 1; navigateToStock() }
        binding.btnNavMetricas.setOnClickListener { hideFloatingInterfaceMenu(immediate = true); lastActiveNavIndex = 2; navigateToMetricas() }
        binding.btnNavMarket.setOnClickListener {
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
            showFloatingInterfaceMenu()
        }

        binding.layoutCustomNav.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if ((right - left) != (oldRight - oldLeft) && (right - left) > 0) {
                binding.navIndicatorCustom.post {
                    if (this::binding.isInitialized && !isDestroyed && !isFinishing) {
                        refreshIndicatorPosition()
                    }
                }
            }
        }
        binding.navIndicatorCustom.post {
            if (this::binding.isInitialized && !isDestroyed && !isFinishing) {
                refreshIndicatorPosition()
            }
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

        updateCustomNavUI(4, animate = false, updateSavedIndex = false) // Salta instantáneamente al botón Interfaz sin sobrescribir la pestaña activa

        binding.cardInterfaceMenuOverlay.animate().setListener(null).withEndAction(null).cancel()
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
        optionsBinding.tvOption1Emoji.text = "🛒"
        optionsBinding.tvOption1Title.text = "Mercado en Línea"
        optionsBinding.tvOption1Subtitle.text = "Descubre y compra productos"

        val hasProductsInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS)
        val hasLoansInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.LOANS)
        val hasHotelInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.HOTEL)
        
        val installedCount = listOf(hasProductsInstalled, hasLoansInstalled, hasHotelInstalled).count { it }

        optionsBinding.cardOptionSwitchSystem.visibility = View.VISIBLE
        optionsBinding.cardOptionMyCollabs.visibility = View.GONE

        // Restablecer vista inicial
        optionsBinding.layoutInterfaceMainMenu.visibility = View.VISIBLE
        optionsBinding.layoutDownloadedSystemsView.visibility = View.GONE

        // Colores para el menú principal cuando estás dentro de un sistema
        val menuColorActive = ContextCompat.getColor(this, R.color.purple_600)
        val menuBgActive = ContextCompat.getColor(this, R.color.purple_50)
        val menuColorInactive = ContextCompat.getColor(this, R.color.purple_200)
        val menuBgInactive = ContextCompat.getColor(this, R.color.white)
        
        // Mercado no está activo, la Gestión (el sistema en el que estás) sí está activo
        optionsBinding.cardOptionMarket.apply {
            strokeColor = menuColorInactive
            setCardBackgroundColor(menuBgInactive)
        }
        
        optionsBinding.cardOptionSwitchSystem.apply {
            strokeColor = menuColorActive
            setCardBackgroundColor(menuBgActive)
        }

        val cards = mutableListOf<View>(
            optionsBinding.cardOptionMarket,
            optionsBinding.cardOptionSwitchSystem
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
        
        optionsBinding.cardOptionSwitchSystem.setOnClickListener {
            if (binding.cardInterfaceMenuOverlay.isLaidOut) {
                android.transition.TransitionManager.beginDelayedTransition(
                    binding.cardInterfaceMenuOverlay,
                    android.transition.TransitionSet().apply {
                        addTransition(android.transition.Fade())
                        addTransition(android.transition.ChangeBounds())
                        duration = 220
                    }
                )
            }
            optionsBinding.layoutInterfaceMainMenu.animate().cancel()
            optionsBinding.layoutDownloadedSystemsView.animate().cancel()
            optionsBinding.layoutInterfaceMainMenu.visibility = View.GONE
            optionsBinding.layoutDownloadedSystemsView.visibility = View.VISIBLE
            optionsBinding.layoutDownloadedSystemsView.alpha = 0f
            optionsBinding.layoutDownloadedSystemsView.translationX = 30f
            optionsBinding.layoutDownloadedSystemsView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220)
                .start()
        }

        // Botón retroceder dentro del recuadro con animación de transición
        optionsBinding.btnBackToInterfaceMenu.setOnClickListener {
            if (binding.cardInterfaceMenuOverlay.isLaidOut) {
                android.transition.TransitionManager.beginDelayedTransition(
                    binding.cardInterfaceMenuOverlay,
                    android.transition.TransitionSet().apply {
                        addTransition(android.transition.Fade())
                        addTransition(android.transition.ChangeBounds())
                        duration = 220
                    }
                )
            }
            optionsBinding.layoutInterfaceMainMenu.animate().cancel()
            optionsBinding.layoutDownloadedSystemsView.animate().cancel()
            optionsBinding.layoutDownloadedSystemsView.visibility = View.GONE
            optionsBinding.layoutInterfaceMainMenu.visibility = View.VISIBLE
            optionsBinding.layoutInterfaceMainMenu.alpha = 0f
            optionsBinding.layoutInterfaceMainMenu.translationX = -30f
            optionsBinding.layoutInterfaceMainMenu.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220)
                .start()
        }

        optionsBinding.cardSystemProducts.visibility = if (hasProductsInstalled) View.VISIBLE else View.GONE
        optionsBinding.cardSystemLoans.visibility = if (hasLoansInstalled) View.VISIBLE else View.GONE
        optionsBinding.cardSystemHotel.visibility = if (hasHotelInstalled) View.VISIBLE else View.GONE
        
        // Resaltar el sistema actual con un borde morado
        val currentSystem = getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        val colorActive = ContextCompat.getColor(this, R.color.purple_600)
        val colorInactive = ContextCompat.getColor(this, R.color.slate_200)
        val bgActive = ContextCompat.getColor(this, R.color.purple_50)
        val bgInactive = ContextCompat.getColor(this, R.color.white)

        optionsBinding.cardSystemProducts.apply {
            strokeColor = if (currentSystem == "PRODUCTS") colorActive else colorInactive
            setCardBackgroundColor(if (currentSystem == "PRODUCTS") bgActive else bgInactive)
        }
        
        optionsBinding.cardSystemLoans.apply {
            strokeColor = if (currentSystem == "LOANS") colorActive else colorInactive
            setCardBackgroundColor(if (currentSystem == "LOANS") bgActive else bgInactive)
        }
        
        optionsBinding.cardSystemHotel.apply {
            strokeColor = if (currentSystem == "HOTEL") colorActive else colorInactive
            setCardBackgroundColor(if (currentSystem == "HOTEL") bgActive else bgInactive)
        }

        val anyInstalled = hasProductsInstalled || hasLoansInstalled || hasHotelInstalled
        optionsBinding.tvNoInstalledSystems.visibility = if (anyInstalled) View.GONE else View.VISIBLE
        optionsBinding.tvDownloadedSystemsSubtitle.visibility = if (anyInstalled) View.VISIBLE else View.GONE
        optionsBinding.btnAddNewSystemFromInterface.visibility = if (hasProductsInstalled && hasLoansInstalled && hasHotelInstalled) View.GONE else View.VISIBLE

        // Selección de sistemas dentro del recuadro
        optionsBinding.cardSystemProducts.setOnClickListener {
            switchToBusinessSystem("PRODUCTS")
        }

        optionsBinding.cardSystemLoans.setOnClickListener {
            switchToBusinessSystem("LOANS")
        }

        optionsBinding.cardSystemHotel.setOnClickListener {
            switchToBusinessSystem("HOTEL")
        }

        optionsBinding.btnAddNewSystemFromInterface.setOnClickListener {
            hideFloatingInterfaceMenu()
            startActivity(Intent(this, BusinessSelectionActivity::class.java))
        }
    }

    private fun switchToBusinessSystem(type: String) {
        hideFloatingInterfaceMenu()
        ensureModuleInstalled(type) {
            getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).edit()
                .putString("business_type", type)
                .apply()
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
    }

    private fun hideFloatingInterfaceMenu(immediate: Boolean = false) {
        if (!this::binding.isInitialized) return
        binding.cardInterfaceMenuOverlay.animate().setListener(null).withEndAction(null).cancel()
        if (binding.cardInterfaceMenuOverlay.visibility == View.GONE && binding.viewInterfaceBackdrop.visibility == View.GONE) {
            return
        }

        if (immediate) {
            binding.cardInterfaceMenuOverlay.visibility = View.GONE
            binding.cardInterfaceMenuOverlay.alpha = 1f
            binding.cardInterfaceMenuOverlay.translationY = 0f
            binding.viewInterfaceBackdrop.visibility = View.GONE
            return
        }

        binding.cardInterfaceMenuOverlay.animate()
            .translationY(40f)
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!this::binding.isInitialized || isDestroyed || isFinishing) return@withEndAction
                binding.cardInterfaceMenuOverlay.visibility = View.GONE
                binding.viewInterfaceBackdrop.visibility = View.GONE
                updateCustomNavUI(lastActiveNavIndex, animate = false) // Salta instantáneamente al último botón activo
            }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("saved_nav_index", lastActiveNavIndex)
    }

    private fun updateCustomNavUI(index: Int, animate: Boolean = true, updateSavedIndex: Boolean = true) {
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
        if (updateSavedIndex) {
            lastActiveNavIndex = targetIdx
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

        pendingIndicatorRunnable?.let { binding.navIndicatorCustom.removeCallbacks(it) }
        val updateAction = Runnable {
            if (!this::binding.isInitialized || isDestroyed || isFinishing) return@Runnable
            val w = targetView.width
            val x = targetView.x
            if (w <= 0) return@Runnable

            val params = binding.navIndicatorCustom.layoutParams
            if (params.width != w) {
                params.width = w
                binding.navIndicatorCustom.layoutParams = params
                binding.navIndicatorCustom.requestLayout()
            }
            if (binding.navIndicatorCustom.visibility != View.VISIBLE) {
                binding.navIndicatorCustom.visibility = View.VISIBLE
            }

            if (animate) {
                binding.navIndicatorCustom.animate()
                    .translationX(x)
                    .setDuration(120)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            } else {
                binding.navIndicatorCustom.animate().cancel()
                binding.navIndicatorCustom.translationX = x
            }
        }
        pendingIndicatorRunnable = updateAction

        val navParentInLayout = (binding.navIndicatorCustom.parent as? View)?.isInLayout == true
        if (targetView.isLaidOut && targetView.width > 0 && !targetView.isInLayout && !binding.navIndicatorCustom.isInLayout && !navParentInLayout) {
            updateAction.run()
        } else {
            binding.navIndicatorCustom.post(updateAction)
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        if (binding.mainFragmentContainer.visibility != View.VISIBLE) {
            binding.mainFragmentContainer.visibility = View.VISIBLE
        }
        if (binding.cardBottomNavContainer.visibility != View.VISIBLE) {
            binding.cardBottomNavContainer.visibility = View.VISIBLE
        }
        val currentFragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
        if (currentFragment != null && currentFragment::class.java == fragment::class.java && currentFragment.isAdded) {
            currentFragment.view?.let { v ->
                if (v.width == 0 || v.height == 0) {
                    binding.mainFragmentContainer.post {
                        if (this::binding.isInitialized && !isDestroyed && !isFinishing) {
                            v.requestLayout()
                            binding.mainFragmentContainer.requestLayout()
                        }
                    }
                }
            }
            return
        }

        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.mainFragmentContainer, fragment)
        if (supportFragmentManager.isStateSaved) {
            transaction.commitAllowingStateLoss()
        } else {
            transaction.commit()
        }
        
        // Ejecutar las transacciones pendientes al instante para evitar vacíos
        supportFragmentManager.executePendingTransactions()
        
        binding.mainContentRoot.post {
            if (!this::binding.isInitialized || isDestroyed || isFinishing) return@post
            binding.mainFragmentContainer.visibility = View.GONE
            binding.mainFragmentContainer.visibility = View.VISIBLE
            binding.mainFragmentContainer.requestLayout()
            binding.mainContentRoot.requestLayout()
        }
    }

    fun getEffectiveBusinessType(): String {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bType = prefs.getString("business_type", null)
        if (!bType.isNullOrEmpty()) return bType

        val hasLoans = prefs.getString("LOANS_name", "")?.isNotEmpty() == true
        val hasHotel = prefs.getString("HOTEL_name", "")?.isNotEmpty() == true
        val hasProducts = prefs.getString("PRODUCTS_name", "")?.isNotEmpty() == true

        val activeType = when {
            hasProducts -> "PRODUCTS"
            hasLoans -> "LOANS"
            hasHotel -> "HOTEL"
            else -> "PRODUCTS"
        }
        prefs.edit().putString("business_type", activeType).apply()
        return activeType
    }

    private fun autoCorrectBusinessTypeIfNeeded() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val currentType = prefs.getString("business_type", null)
        if (!currentType.isNullOrEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val dbInstance = AppDatabase.getDatabase(this@MainActivity)
            val loansCount = try { dbInstance.loanDao().getAllLoansOnce().size } catch(e: Exception) { 0 }
            val clientsCount = try { dbInstance.loanDao().getAllClientsOnce().size } catch(e: Exception) { 0 }
            val productsCount = try { dbInstance.productDao().allProducts.size } catch(e: Exception) { 0 }
            val hasLoansPref = prefs.getString("LOANS_name", "")?.isNotEmpty() == true

            if ((loansCount > 0 || clientsCount > 0 || hasLoansPref) && productsCount == 0) {
                prefs.edit().putString("business_type", "LOANS").apply()
                withContext(Dispatchers.Main) {
                    updateMenuForBusinessType()
                    if (checkCurrentModuleInstalled()) {
                        navigateToInicio()
                    }
                }
            }
        }
    }

    override fun onResume() { 
        super.onResume()
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (appPrefs.getString("last_active_interface", null) != "BUSINESS" ||
            appPrefs.getBoolean("login_in_progress", false)
        ) {
            appPrefs.edit()
                .putString("last_active_interface", "BUSINESS")
                .putBoolean("login_in_progress", false)
                .apply()
        }
        autoCorrectBusinessTypeIfNeeded()
        if (!checkCurrentModuleInstalled()) {
            binding.mainFragmentContainer.visibility = View.INVISIBLE
            binding.cardBottomNavContainer.visibility = View.INVISIBLE
            return
        }
        if (binding.mainFragmentContainer.visibility != View.VISIBLE) {
            binding.mainFragmentContainer.visibility = View.VISIBLE
        }
        if (binding.cardBottomNavContainer.visibility != View.VISIBLE) {
            binding.cardBottomNavContainer.visibility = View.VISIBLE
        }
        hideFloatingInterfaceMenu(immediate = true)
        updateMenuForBusinessType()
        if (supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) == null) {
            navigateToInicio()
        }
        binding.mainContentRoot.post {
            if (!this::binding.isInitialized || isDestroyed || isFinishing) return@post
            ViewCompat.requestApplyInsets(binding.cardBottomNavContainer)
        }
        SyncManager(this).scheduleOfflineSync()
        checkAndShowGuestSystemWelcome()
    }

    private var isUninstalledDialogShowing = false
    private var isWelcomeDialogShowing = false

    private fun checkCurrentModuleInstalled(): Boolean {
        val bType = getEffectiveBusinessType()
        val module = when (bType) {
            "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
            "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
            else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
        }
        if (!dynamicModuleManager.isModuleInstalled(module)) {
            binding.mainFragmentContainer.visibility = View.INVISIBLE
            binding.cardBottomNavContainer.visibility = View.INVISIBLE
            showUninstalledModuleDialog(module, bType)
            return false
        }
        if (binding.mainFragmentContainer.visibility != View.VISIBLE) {
            binding.mainFragmentContainer.visibility = View.VISIBLE
        }
        if (binding.cardBottomNavContainer.visibility != View.VISIBLE) {
            binding.cardBottomNavContainer.visibility = View.VISIBLE
        }
        return true
    }

    private fun showUninstalledModuleDialog(module: com.gomezmart.app.utils.DynamicFeatureModule, bType: String) {
        if (isUninstalledDialogShowing) return
        isUninstalledDialogShowing = true

        ensureModuleInstalled(bType) {
            isUninstalledDialogShowing = false
            recreate()
        }
    }

    fun navigateToInicio() {
        if (!checkCurrentModuleInstalled()) return
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelHomeFragment(); "LOANS" -> LoansHomeFragment(); else -> HomeFragment() })
        updateCustomNavUI(0)
    }

    fun navigateToStock(forceGraph: Boolean = false) {
        if (!checkCurrentModuleInstalled()) return
        if (forceGraph) getSharedPreferences("HotelPrefs", MODE_PRIVATE).edit().putBoolean("FORCE_MAP_VIEW", true).apply()
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelRoomsFragment(); "LOANS" -> LoansClientsFragment(); else -> StockFragment() })
        updateCustomNavUI(1)
    }

    fun navigateToMetricas() {
        if (!checkCurrentModuleInstalled()) return
        val bType = getEffectiveBusinessType()
        replaceFragment(when(bType) { "HOTEL" -> HotelMetricsFragment(); "LOANS" -> LoansMetricsFragment(); else -> MetricasFragment() })
        updateCustomNavUI(2)
    }


    fun openSideMenu() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    fun navigateToGestion() = binding.drawerLayoutMain.openDrawer(GravityCompat.START)
    private fun startSyncService() {
        if (isGuestUser()) return
        try {
            SyncManager(this).scheduleOfflineSync()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error scheduling offline sync: ${e.message}")
        }
    }
    private fun subscribeToSalesTopic() {
        if (isGuestUser()) return
        val userId = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().subscribeToTopic("sales_$userId")
    }

    private val dynamicModuleManager by lazy { com.gomezmart.app.utils.DynamicModuleManager(this) }

    private fun setupListeners() {
        binding.navigationViewMain.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                // --- HOTEL ---
                R.id.menu_hotel_guests -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_hotel.HotelGuestHistoryActivity"))
                    true
                }
                R.id.menu_hotel_bookings -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_hotel.HotelBookingsActivity"))
                    true
                }
                R.id.menu_hotel_tools -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_hotel.HotelToolsActivity"))
                    true
                }
                R.id.menu_hotel_payments -> { startToolActivity(Intent(this, BusinessDebtsActivity::class.java)); true }
                R.id.menu_hotel_rooms_graph -> {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    navigateToStock(true)
                    true
                }
                R.id.menu_hotel_maintenance -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_hotel.HotelMaintenanceActivity"))
                    true
                }

                // --- PRÉSTAMOS ---
                R.id.menu_loans_clients -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_loans.LoansClientsActivity"))
                    true
                }
                R.id.menu_loans_new -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_loans.AddLoanActivity"))
                    true
                }
                R.id.menu_loans_collections -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_loans.LoansCollectionsActivity"))
                    true
                }
                R.id.menu_loans_profit -> {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    lastActiveNavIndex = 2
                    navigateToMetricas()
                    true
                }
                R.id.menu_loans_expenses -> {
                    startToolActivity(Intent().setClassName(packageName, "com.gomezmart.feature_loans.LoansExpensesActivity"))
                    true
                }

                // --- PRODUCTOS / COMÚN ---
                R.id.menu_manage_orders -> { startToolActivity(Intent(this, DeliveriesActivity::class.java)); true }
                R.id.menu_caja -> { startToolActivity(Intent(this, CajaActivity::class.java)); true }
                R.id.menu_gastos -> { startToolActivity(Intent(this, GastosActivity::class.java)); true }
                R.id.menu_fiados -> { startToolActivity(Intent(this, DeudoresActivity::class.java)); true }
                R.id.menu_commitments -> { startToolActivity(Intent(this, CommitmentsActivity::class.java)); true }
                R.id.menu_proveedores -> { startToolActivity(Intent(this, ProveedoresActivity::class.java)); true }
                R.id.menu_emitir_comprobante -> { startToolActivity(Intent(this, EmitirComprobanteActivity::class.java)); true }
                R.id.menu_lista_compras -> { startToolActivity(Intent(this, ListaComprasActivity::class.java)); true }
                R.id.menu_asignador -> { startToolActivity(Intent(this, AsignadorDePreciosActivity::class.java)); true }
                R.id.menu_customers -> { startToolActivity(Intent(this, CustomersActivity::class.java)); true }
                R.id.menu_sales_history -> { startToolActivity(Intent(this, SalesHistoryActivity::class.java)); true }
                R.id.menu_view_history -> { startToolActivity(Intent(this, HistorialActivity::class.java)); true }
                R.id.menu_instructions -> { startToolActivity(Intent(this, InstruccionesActivity::class.java)); true }
                R.id.menu_sync -> {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    if (!requireLoginForGuest("sincronizar tus datos en la nube")) {
                        Toast.makeText(this, "Sincronizando datos con la nube... ☁️", Toast.LENGTH_SHORT).show()
                        SyncManager(this).scheduleOfflineSync()
                    }
                    true
                }
                R.id.menu_customize_actions -> {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    lastActiveNavIndex = 0
                    navigateToInicio()
                    true
                }
                R.id.menu_account_settings -> {
                    if (!requireLoginForGuest("configurar tu cuenta")) {
                        startToolActivity(Intent(this, AccountSettingsActivity::class.java))
                    } else {
                        binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    }
                    true
                }
                R.id.menu_logout -> {
                    binding.drawerLayoutMain.closeDrawer(GravityCompat.START)
                    showLogoutDialog()
                    true
                }
                else -> false
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
        AlertDialog.Builder(this)
            .setTitle("Cerrar Sesión")
            .setMessage("¿Deseas salir?")
            .setPositiveButton("Salir") { _, _ ->
                lifecycleScope.launch {
                    SessionManager.signOutCompletely(this@MainActivity)
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun switchToPersonalBusiness() {
        if (requireLoginForGuest("cambiar de negocio en la nube")) return
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
        if (requireLoginForGuest("ver colaboraciones")) return
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
        if (isGuestUser()) return
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
        if (this::binding.isInitialized && binding.drawerLayoutMain.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayoutMain.closeDrawer(GravityCompat.START, false)
        }
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
        if (isGuestUser()) {
            try {
                WorkManager.getInstance(this).cancelUniqueWork("periodic_sync")
            } catch (e: Exception) {}
            return
        }
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

    private fun checkAndShowGuestSystemWelcome() {
        if (isUninstalledDialogShowing || isWelcomeDialogShowing) return
        val currentType = getEffectiveBusinessType()
        val module = when (currentType) {
            "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
            "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
            else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
        }
        if (!dynamicModuleManager.isModuleInstalled(module)) return

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val flagKey = "has_shown_guest_system_welcome_$currentType"
        val justInstalledKey = "just_installed_module_$currentType"
        val hasShown = prefs.getBoolean(flagKey, false)
        val justInstalled = prefs.getBoolean(justInstalledKey, false)

        if (!hasShown || justInstalled) {
            isWelcomeDialogShowing = true
            prefs.edit()
                .putBoolean(flagKey, true)
                .putBoolean(justInstalledKey, false)
                .putBoolean("just_installed_module", false)
                .apply()

            binding.btnNavMarket.visibility = View.VISIBLE
            highlightInterfazButton()

            val dialogView = layoutInflater.inflate(R.layout.dialog_guest_system_welcome, null)
            val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            dialog.setOnDismissListener {
                isWelcomeDialogShowing = false
            }

            val btnOk = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuestWelcomeOk)
            btnOk?.setOnClickListener {
                dialog.dismiss()
                highlightInterfazButton()
            }

            dialog.show()
        }
    }

    private fun highlightInterfazButton() {
        binding.btnNavMarket.visibility = View.VISIBLE
        binding.btnNavMarket.post {
            val scaleX = ObjectAnimator.ofFloat(binding.btnNavMarket, "scaleX", 1f, 1.35f, 1f, 1.35f, 1f)
            val scaleY = ObjectAnimator.ofFloat(binding.btnNavMarket, "scaleY", 1f, 1.35f, 1f, 1.35f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 1400
                start()
            }
        }
    }
}
