package com.gomezmart.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.gomezmart.app.data.CartManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.databinding.ActivityBuyerHomeBinding
import com.gomezmart.app.fragment.BuyerAccountFragment
import com.gomezmart.app.fragment.BuyerChatsFragment
import com.gomezmart.app.fragment.BuyerMarketplaceFragment
import com.gomezmart.app.fragment.BuyerSettingsFragment
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BuyerHomeActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerHomeBinding
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        applyWindowInsets(binding.mainContentRoot, applyTop = false, applyBottom = false)
        applyWindowInsetsMargins(binding.cardBottomNavContainer, applyTop = false, applyBottom = true)

        checkBusinessStatus()
        setupDrawer()
        setupViewPager()
        setupCustomBottomNav()
        startProfileListener()
        updatePublicIdentity()
        FavoritesManager.loadFromFirebase(this)

        startSyncService()
        listenForUnreadMessages()
        listenForActiveOrders()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerBuyerHome.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerBuyerHome.closeDrawer(GravityCompat.START)
                    return
                }

                if (binding.buyerViewPager.currentItem != 0) {
                    navigateToMarketplace()
                    return
                }

                // Mover al fondo en lugar de matar el proceso de la app
                moveTaskToBack(true)
            }
        })

        if (intent.getBooleanExtra("START_UPDATE", false)) {
            Updater(this).checkAndDownload(autoCheck = true)
        }
    }

    private fun setupViewPager() {
        binding.buyerViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> BuyerMarketplaceFragment()
                    1 -> BuyerChatsFragment()
                    else -> BuyerAccountFragment()
                }
            }
        }
        binding.buyerViewPager.isUserInputEnabled = false // Desactivar deslizamiento táctil horizontal
        binding.buyerViewPager.offscreenPageLimit = 2 // Mantener fragmentos cargados para suavidad
        
        binding.buyerViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCustomNavUI(position)
            }
        })
    }

    private fun startSyncService() {
        try {
            SyncManager(this).scheduleOfflineSync()
        } catch (e: Exception) {
            Log.e("BuyerHomeActivity", "Error scheduling offline sync: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (appPrefs.getString("last_active_interface", null) != "MARKET" ||
            appPrefs.getBoolean("login_in_progress", false)
        ) {
            appPrefs.edit()
                .putString("last_active_interface", "MARKET")
                .putBoolean("login_in_progress", false)
                .commit()
        }
        checkBusinessStatus()
    }

    private fun refreshIndicatorPosition() {
        val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
        val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }
        if (activeIdx != -1) {
            updateCustomNavUI(activeIdx, animate = false)
        }
    }

    private fun hasAnySystemInstalled(): Boolean {
        val manager = com.gomezmart.app.utils.DynamicModuleManager(this)
        return manager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.HOTEL) ||
               manager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.LOANS) ||
               manager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS)
    }

    private fun checkBusinessStatus() {
        val hasInstalledSystem = hasAnySystemInstalled()
        showGestionButton(hasInstalledSystem)
    }

    private fun listenForUnreadMessages() {
        try {
            SyncManager(this).startRealtimeUnreadCountSync { count ->
                try {
                    if (count > 0) {
                        binding.tvUnreadBadgeChats.visibility = View.VISIBLE
                        binding.tvUnreadBadgeChats.text = if (count > 9) "9+" else count.toString()
                    } else {
                        binding.tvUnreadBadgeChats.visibility = View.GONE
                    }
                } catch (e: Exception) {}
            }?.let { registrations.add(it) }
        } catch (e: Exception) {
            Log.e("BuyerHomeActivity", "Error listenForUnreadMessages: ${e.message}")
        }
    }

    private fun listenForActiveOrders() {
        try {
            SyncManager(this).startRealtimeBuyerActiveOrdersCountSync { count ->
                try {
                    if (count > 0) {
                        binding.tvActiveOrdersBadge.visibility = View.VISIBLE
                        binding.tvActiveOrdersBadge.text = if (count > 9) "9+" else count.toString()
                    } else {
                        binding.tvActiveOrdersBadge.visibility = View.GONE
                    }
                } catch (e: Exception) {}
            }?.let { registrations.add(it) }
        } catch (e: Exception) {
            Log.e("BuyerHomeActivity", "Error listenForActiveOrders: ${e.message}")
        }
    }

    private fun startProfileListener() {
        if (isGuestUser()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val reg = FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val name = snapshot.getString("name") ?: snapshot.getString("user_full_name")
                    val photo = snapshot.getString("photoUrl") ?: snapshot.getString("user_photo_url")
                    val phone = snapshot.getString("phone") ?: snapshot.getString("user_phone")
                    val email = snapshot.getString("email") ?: snapshot.getString("user_email") ?: user.email
                    val dni = snapshot.getString("dni") ?: snapshot.getString("user_dni")
                    val address = snapshot.getString("address") ?: snapshot.getString("user_address")
                    
                    if (photo.isNullOrEmpty() && user.photoUrl != null) {
                        FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
                            .update("photoUrl", user.photoUrl.toString())
                    }

                    val edit = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                    edit.putBoolean("is_guest", false)
                    if (!name.isNullOrEmpty()) edit.putString("user_full_name", name)
                    if (!photo.isNullOrEmpty()) edit.putString("user_photo_url", photo)
                    if (!phone.isNullOrEmpty()) edit.putString("user_phone", phone)
                    if (!email.isNullOrEmpty()) edit.putString("user_email", email)
                    if (!dni.isNullOrEmpty()) edit.putString("user_dni", dni)
                    if (!address.isNullOrEmpty()) edit.putString("user_address", address)
                    edit.apply()

                    runOnUiThread { setupDrawer() }
                }
            }
        registrations.add(reg)
    }

    private fun updatePublicIdentity() {
        if (isGuestUser()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
        val businessType = bizPrefs.getString("business_type", "") ?: ""
        val bizName = bizPrefs.getString("${businessType}_name", null) ?: bizPrefs.getString("business_name", "") ?: ""
        val bizLogo = bizPrefs.getString("${businessType}_logo_url", null) ?: bizPrefs.getString("business_logo_url", "") ?: ""
        
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
        val collabBizName = collabPrefs.getString("collab_business_name", "") ?: ""
        val collabBizLogo = collabPrefs.getString("collab_business_logo", "") ?: ""

        val isOnline = bizPrefs.getBoolean("${businessType}_is_online", bizPrefs.getBoolean("is_online_store", false))

        val title = when {
            bizName.isNotEmpty() && collabBizName.isNotEmpty() -> "Gestionando y Colaborando"
            bizName.isNotEmpty() && isOnline -> "Dueño de $bizName"
            collabBizName.isNotEmpty() -> "Colaborador en $collabBizName"
            else -> "Comprador"
        }

        val publicLogo = if (bizLogo.isNotEmpty()) bizLogo else collabBizLogo
        val storedName = appPrefs.getString("user_full_name", null) ?: user.displayName ?: ""
        val realPhoto = appPrefs.getString("user_photo_url", null) ?: user.photoUrl?.toString() ?: ""

        val updates = mutableMapOf<String, Any>(
            "businessTitle" to title,
            "photoUrl" to realPhoto,
            "user_photo_url" to realPhoto,
            "email" to (user.email ?: ""),
            "phone" to (appPrefs.getString("user_phone", "") ?: "")
        )
        if (!SessionManager.isPlaceholderName(storedName)) {
            updates["name"] = storedName.trim()
        }
        
        if (publicLogo.isNotEmpty()) updates["businessLogoUrl"] = publicLogo

        FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
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
        FirebaseFirestore.getInstance().collection("users").document(user.uid).set(rootUpdates, SetOptions.merge())
    }

    private fun showGestionButton(show: Boolean) {
        binding.btnNavSettings.visibility = View.GONE
        binding.btnNavGestion.isVisible = show
        val buttons = listOf(binding.btnNavHome, binding.btnNavChats, binding.btnNavAccount, binding.btnNavGestion)
        val vCount = buttons.count { it.isVisible }
        binding.layoutCustomNav.weightSum = vCount.toFloat()

        binding.layoutCustomNav.post {
            val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconGestion)
            val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }.coerceAtLeast(0)
            updateCustomNavUI(activeIdx)
        }
    }

    private fun setupDrawer() {
        binding.navBuyerHome.menu.clear()
        binding.navBuyerHome.inflateMenu(R.menu.menu_buyer_drawer)
        binding.navBuyerHome.itemIconTintList = null 
        val user = FirebaseAuth.getInstance().currentUser
        if (binding.navBuyerHome.headerCount > 0) {
            val header = binding.navBuyerHome.getHeaderView(0)
            applyWindowInsets(header, applyTop = true, applyBottom = false) // Proteger cabecera lateral
            
            val tvName = header.findViewById<TextView>(R.id.tvBuyerDrawerName)
            val tvEmail = header.findViewById<TextView>(R.id.tvBuyerDrawerEmail)
            val tvBadge = header.findViewById<TextView>(R.id.tvBuyerRoleBadge)
            val ivLogo = header.findViewById<ImageView>(R.id.ivBuyerDrawerLogo)

            if (isGuestUser()) {
                tvName?.text = "Modo Invitado"
                tvEmail?.text = "Sin cuenta registrada"
                tvBadge?.text = "👀 INVITADO"
                ivLogo?.setImageResource(R.drawable.gomezmart_icon)
            } else if (user != null) {
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                tvName?.text = prefs.getString("user_full_name", user.displayName ?: "Comprador")
                tvEmail?.text = prefs.getString("user_email", user.email ?: "")
                tvBadge?.text = "🛒 COMPRADOR"

                val photoUrl = prefs.getString("user_photo_url", null)?.takeIf { it.isNotBlank() } ?: user.photoUrl?.toString()
                if (!photoUrl.isNullOrEmpty() && ivLogo != null) {
                    Glide.with(this).load(photoUrl).circleCrop().into(ivLogo)
                }
            }
        }

        binding.navBuyerHome.setNavigationItemSelectedListener { item ->
            binding.drawerBuyerHome.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.buyer_menu_bookings -> {
                    if (!requireLoginForGuest("ver tus reservas")) {
                        startActivity(Intent(this, BuyerBookingsActivity::class.java))
                    }
                }
                R.id.buyer_menu_orders -> {
                    if (!requireLoginForGuest("ver tus pedidos")) {
                        startActivity(Intent(this, BuyerOrdersActivity::class.java))
                    }
                }
                R.id.buyer_menu_cart -> {
                    if (!requireLoginForGuest("acceder a tu carrito")) {
                        startActivity(Intent(this, CartCheckoutActivity::class.java).apply {
                            putExtra("STORE_ID", CartManager.currentStoreId)
                            putExtra("STORE_NAME", CartManager.currentStoreName)
                            putExtra("STORE_LOGO", CartManager.currentStoreLogo)
                        })
                    }
                }
                R.id.buyer_menu_categories -> navigateToMarketplace()
                R.id.buyer_menu_report_error -> showReportErrorDialog(this)
            }
            true
        }
    }

    // NOTA: El diálogo de reporte de errores vive ahora en BaseActivity
    // (showReportErrorDialog / sendReportViaEmail), de modo que TODA la app
    // puede abrirlo con una sola línea y no hay dos copias que mantener.
    // La implementación anterior específica del comprador se eliminó para evitar
    // que las dos versiones se desincronizasen.

    private var lastActiveNavIndex = 0

    private fun setupCustomBottomNav() {
        binding.btnNavSettings.visibility = View.GONE
        binding.btnNavHome.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 0; navigateToMarketplace() }
        binding.btnNavChats.setOnClickListener { 
            hideFloatingInterfaceMenu(); lastActiveNavIndex = 1; navigateToChats() 
        }
        binding.btnNavAccount.setOnClickListener { 
            hideFloatingInterfaceMenu(); lastActiveNavIndex = 2; navigateToAccount() 
        }
        binding.btnNavGestion.setOnClickListener { 
            showFloatingInterfaceMenu()
        }
        binding.layoutCustomNav.post { updateCustomNavUI(0) }
    }

    private fun openMarketBusiness() {
        if (requireLoginForGuest("publicar o administrar tu tienda en el Mercado en Línea")) return
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isOnline = prefs.getBoolean("is_online_store", false)
        val auth = FirebaseAuth.getInstance()

        if (businessType == "LOANS") {
            if (isOnline) {
                startActivity(Intent(this, LoanStoreProfileActivity::class.java).apply { 
                    putExtra("STORE_ID", auth.currentUser?.uid)
                    putExtra("IS_OWNER", true) 
                })
            } else {
                startActivity(Intent(this, LoanStoreEditorActivity::class.java))
            }
        } else {
            startActivity(Intent(this, StoreProfileActivity::class.java).apply { 
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

        updateCustomNavUI(3, animate = false) // Fondo morado activo en "Gestión" (salto instantáneo)

        val optionsBinding = binding.includeInterfaceOptions
        optionsBinding.tvOption1Emoji.text = "🛒"
        optionsBinding.tvOption1Title.text = "Mercado en Línea"
        optionsBinding.tvOption1Subtitle.text = "Descubre y compra productos"

        val dynamicModuleManager = com.gomezmart.app.utils.DynamicModuleManager(this)
        val hasProductsInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS)
        val hasLoansInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.LOANS)
        val hasHotelInstalled = dynamicModuleManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.HOTEL)

        val installedCount = listOf(hasProductsInstalled, hasLoansInstalled, hasHotelInstalled).count { it }

        val isGuest = isGuestUser()
        optionsBinding.cardOptionMarket.visibility = View.VISIBLE
        optionsBinding.cardOptionSwitchSystem.visibility = View.VISIBLE
        optionsBinding.cardOptionMyCollabs.visibility = View.GONE

        // Restablecer vista inicial
        optionsBinding.layoutInterfaceMainMenu.visibility = View.VISIBLE
        optionsBinding.layoutDownloadedSystemsView.visibility = View.GONE

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

        // Mercado resaltado
        val menuColorActive = ContextCompat.getColor(this, R.color.purple_600)
        val menuBgActive = ContextCompat.getColor(this, R.color.purple_50)
        val menuColorInactive = ContextCompat.getColor(this, R.color.purple_200)
        val menuBgInactive = ContextCompat.getColor(this, R.color.white)

        optionsBinding.cardOptionMarket.apply {
            strokeColor = menuColorActive
            setCardBackgroundColor(menuBgActive)
        }
        optionsBinding.cardOptionSwitchSystem.apply {
            strokeColor = menuColorInactive
            setCardBackgroundColor(menuBgInactive)
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

        val dynamicManager = com.gomezmart.app.utils.DynamicModuleManager(this)

        // Comportamiento del botón de Mercado Online
        optionsBinding.cardOptionMarket.setOnClickListener {
            hideFloatingInterfaceMenu()
            navigateToMarketplace()
        }

        // 1. Al presionar "Gestión", mostrar únicamente los sistemas descargados dentro del mismo recuadro
        optionsBinding.cardOptionSwitchSystem.setOnClickListener {
            val hasProducts = dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS)
            val hasLoans = dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.LOANS)
            val hasHotel = dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.HOTEL)

            optionsBinding.cardSystemProducts.visibility = if (hasProducts) View.VISIBLE else View.GONE
            optionsBinding.cardSystemLoans.visibility = if (hasLoans) View.VISIBLE else View.GONE
            optionsBinding.cardSystemHotel.visibility = if (hasHotel) View.VISIBLE else View.GONE

            val anyInstalled = hasProducts || hasLoans || hasHotel
            optionsBinding.tvNoInstalledSystems.visibility = if (anyInstalled) View.GONE else View.VISIBLE
            optionsBinding.tvDownloadedSystemsSubtitle.visibility = if (anyInstalled) View.VISIBLE else View.GONE
            optionsBinding.btnAddNewSystemFromInterface.visibility = if (hasProducts && hasLoans && hasHotel) View.GONE else View.VISIBLE

            android.transition.TransitionManager.beginDelayedTransition(
                binding.cardInterfaceMenuOverlay,
                android.transition.TransitionSet().apply {
                    addTransition(android.transition.Fade())
                    addTransition(android.transition.ChangeBounds())
                    duration = 220
                }
            )
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

        // Botón retroceder dentro del recuadro con animación
        optionsBinding.btnBackToInterfaceMenu.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(
                binding.cardInterfaceMenuOverlay,
                android.transition.TransitionSet().apply {
                    addTransition(android.transition.Fade())
                    addTransition(android.transition.ChangeBounds())
                    duration = 220
                }
            )
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
            com.gomezmart.app.util.SystemLoadingDialog.show(this, type) {
                getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).edit()
                    .putString("business_type", type)
                    .apply()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                finish()
            }
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
                updateCustomNavUI(lastActiveNavIndex, animate = false) // Salta instantáneamente al último botón
            }
            .start()
    }

    private fun updateCustomNavUI(index: Int, animate: Boolean = true) {
        binding.btnNavSettings.visibility = View.GONE
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8") 
        val buttons = listOf(binding.btnNavHome, binding.btnNavChats, binding.btnNavAccount, binding.btnNavGestion)
        val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconGestion)
        val labels = listOf(binding.tvLabelHome, binding.tvLabelChats, binding.tvLabelAccount, binding.tvLabelGestion)
        
        val visibleButtons = buttons.filter { it.isVisible }
        if (visibleButtons.isEmpty()) return

        var targetIdx = index
        if (targetIdx !in buttons.indices || !buttons[targetIdx].isVisible) {
            targetIdx = buttons.indexOf(visibleButtons.firstOrNull() ?: buttons[0])
        }

        labels.forEachIndexed { i, tv -> 
            val color = if (i == targetIdx) activeColor else inactiveColor
            if (tv.currentTextColor != color) tv.setTextColor(color)
        }
        icons.forEachIndexed { i, iv -> 
            val alpha = if (i == targetIdx) 1.0f else 0.6f
            if (iv.alpha != alpha) iv.alpha = alpha
        }

        val targetView = buttons[targetIdx]

        val applyIndicator = Runnable {
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
                    .setDuration(180)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            } else {
                binding.navIndicatorCustom.animate().cancel()
                binding.navIndicatorCustom.translationX = x
            }
        }

        val navParentInLayout = (binding.navIndicatorCustom.parent as? View)?.isInLayout == true
        if (targetView.isLaidOut && targetView.width > 0 && !targetView.isInLayout && !binding.navIndicatorCustom.isInLayout && !navParentInLayout) {
            applyIndicator.run()
        } else {
            binding.navIndicatorCustom.post(applyIndicator)
        }
    }

    private fun navigateToMarketplace() { binding.buyerViewPager.setCurrentItem(0, false); updateCustomNavUI(0) }
    private fun navigateToChats() { binding.buyerViewPager.setCurrentItem(1, false); updateCustomNavUI(1) }
    private fun navigateToAccount() { binding.buyerViewPager.setCurrentItem(2, false); updateCustomNavUI(2) }
    private fun navigateToSettings() { binding.buyerViewPager.setCurrentItem(3, false); updateCustomNavUI(3) }
    
    // Función para mostrar el menú de opciones (igual que en MainActivity)





    


    override fun onDestroy() {
        super.onDestroy()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    fun openDrawer() = binding.drawerBuyerHome.openDrawer(GravityCompat.START)
}
