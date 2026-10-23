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
import com.gomezmart.app.network.GomezmartSyncService
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BuyerHomeActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerHomeBinding
    private val marketplaceFragment = BuyerMarketplaceFragment()
    private val chatsFragment = BuyerChatsFragment()
    private val accountFragment = BuyerAccountFragment()
    private val settingsFragment = BuyerSettingsFragment()
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.gomezmart_primary)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        applyWindowInsets(binding.mainContentRoot, applyTop = false, applyBottom = false)
        applyWindowInsetsMargins(binding.cardBottomNavContainer, applyTop = false, applyBottom = true)

        checkBusinessStatus()
        setupDrawer()
        setupViewPager()
        setupCustomBottomNav()
        startProfileListener()
        checkNotificationPermission()
        FavoritesManager.loadFromFirebase(this)

        startSyncService()
        listenForUnreadMessages()
        listenForActiveOrders()

        if (intent.getBooleanExtra("START_UPDATE", false)) {
            Updater(this).checkAndDownload(autoDownload = true)
        }
    }

    private fun setupViewPager() {
        val fragments = listOf(marketplaceFragment, chatsFragment, accountFragment, settingsFragment)
        binding.buyerViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        binding.buyerViewPager.isUserInputEnabled = true // Permitir desplazamiento táctil
        binding.buyerViewPager.offscreenPageLimit = 3 // Mantener fragmentos cargados para suavidad

        binding.buyerViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCustomNavUI(position)
            }
        })
    }

    private fun startSyncService() {
        try {
            val intent = Intent(this, GomezmartSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("BuyerHomeActivity", "Error starting sync service: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        checkBusinessStatus()
    }

    private fun refreshIndicatorPosition() {
        val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
        val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }
        if (activeIdx != -1) {
            updateCustomNavUI(activeIdx, animate = false)
        }
    }

    private fun checkBusinessStatus() {
        // El botón de gestión/interfaz debe estar SIEMPRE visible para permitir al usuario volver a su sistema en cualquier momento
        showGestionButton(true)
    }

    private fun listenForUnreadMessages() {
        SyncManager(this).startRealtimeUnreadCountSync { count ->
            if (count > 0) {
                binding.tvUnreadBadgeChats.visibility = View.VISIBLE
                binding.tvUnreadBadgeChats.text = if (count > 9) "9+" else count.toString()
            } else {
                binding.tvUnreadBadgeChats.visibility = View.GONE
            }
        }?.let { registrations.add(it) }
    }

    private fun listenForActiveOrders() {
        SyncManager(this).startRealtimeBuyerActiveOrdersCountSync { count ->
            if (count > 0) {
                binding.tvActiveOrdersBadge.visibility = View.VISIBLE
                binding.tvActiveOrdersBadge.text = if (count > 9) "9+" else count.toString()
            } else {
                binding.tvActiveOrdersBadge.visibility = View.GONE
            }
        }?.let { registrations.add(it) }
    }

    private fun startProfileListener() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val reg = FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val name = snapshot.getString("name")
                    val photo = snapshot.getString("photoUrl")
                    
                    if (photo.isNullOrEmpty() && user.photoUrl != null) {
                        FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
                            .update("photoUrl", user.photoUrl.toString())
                    }

                    val edit = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                    if (!name.isNullOrEmpty()) edit.putString("user_full_name", name)
                    if (!photo.isNullOrEmpty()) edit.putString("user_photo_url", photo)
                    edit.apply()
                    updatePublicIdentity()
                }
            }
        registrations.add(reg)
    }

    private fun updatePublicIdentity() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
        val businessType = bizPrefs.getString("business_type", "") ?: ""
        val bizName = bizPrefs.getString("${businessType}_name", null) ?: bizPrefs.getString("business_name", "") ?: ""
        val bizLogo = bizPrefs.getString("${businessType}_logo_url", null) ?: bizPrefs.getString("business_logo_url", "") ?: ""
        
        val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
        val collabBizName = collabPrefs.getString("collab_business_name", "") ?: ""
        val collabBizLogo = collabPrefs.getString("collab_business_logo", "") ?: ""

        val title = when {
            bizName.isNotEmpty() && collabBizName.isNotEmpty() -> "Gestionando y Colaborando"
            bizName.isNotEmpty() -> "Dueño de $bizName"
            collabBizName.isNotEmpty() -> "Colaborador en $collabBizName"
            else -> "Comprador"
        }

        val publicLogo = if (bizLogo.isNotEmpty()) bizLogo else collabBizLogo
        val realName = appPrefs.getString("user_full_name", null) ?: user.displayName ?: "Usuario Gomezmart"
        val realPhoto = appPrefs.getString("user_photo_url", null) ?: user.photoUrl?.toString() ?: ""

        val updates = mutableMapOf<String, Any>(
            "businessTitle" to title,
            "name" to realName,
            "photoUrl" to realPhoto,
            "user_photo_url" to realPhoto,
            "email" to (user.email ?: ""),
            "phone" to (appPrefs.getString("user_phone", "") ?: "")
        )
        
        if (publicLogo.isNotEmpty()) updates["businessLogoUrl"] = publicLogo

        FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("profile").document("info")
            .set(updates, SetOptions.merge())
            
        val rootUpdates = mapOf(
            "name" to realName,
            "photoUrl" to realPhoto,
            "businessTitle" to title,
            "email" to (user.email ?: ""),
            "phone" to (appPrefs.getString("user_phone", "") ?: "")
        )
        FirebaseFirestore.getInstance().collection("users").document(user.uid).set(rootUpdates, SetOptions.merge())
    }

    private fun showGestionButton(show: Boolean) {
        binding.btnNavGestion.isVisible = show
        val buttons = listOf(binding.btnNavHome, binding.btnNavChats, binding.btnNavAccount, binding.btnNavSettings, binding.btnNavGestion)
        val vCount = buttons.count { it.isVisible }
        binding.layoutCustomNav.weightSum = vCount.toFloat()

        binding.layoutCustomNav.post {
            val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
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
            
            if (user != null) {
                val tvName = header.findViewById<TextView>(R.id.tvBuyerDrawerName)
                val tvEmail = header.findViewById<TextView>(R.id.tvBuyerDrawerEmail)
                val ivLogo = header.findViewById<ImageView>(R.id.ivBuyerDrawerLogo)

                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                tvName?.text = prefs.getString("user_full_name", user.displayName ?: "Comprador")
                tvEmail?.text = user.email

                if (user.photoUrl != null) {
                    Glide.with(this).load(user.photoUrl).circleCrop().into(ivLogo!!)
                }
            }
        }

        binding.navBuyerHome.setNavigationItemSelectedListener { item ->
            binding.drawerBuyerHome.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.buyer_menu_bookings -> startActivity(Intent(this, BuyerBookingsActivity::class.java))
                R.id.buyer_menu_orders -> startActivity(Intent(this, BuyerOrdersActivity::class.java))
                R.id.buyer_menu_cart -> {
                    startActivity(Intent(this, CartCheckoutActivity::class.java).apply {
                        putExtra("STORE_ID", CartManager.currentStoreId)
                        putExtra("STORE_NAME", CartManager.currentStoreName)
                        putExtra("STORE_LOGO", CartManager.currentStoreLogo)
                    })
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
        binding.btnNavHome.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 0; navigateToMarketplace() }
        binding.btnNavChats.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 1; navigateToChats() }
        binding.btnNavAccount.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 2; navigateToAccount() }
        binding.btnNavSettings.setOnClickListener { hideFloatingInterfaceMenu(); lastActiveNavIndex = 3; navigateToSettings() }
        binding.btnNavGestion.setOnClickListener { 
            showFloatingInterfaceMenu()
        }
        binding.layoutCustomNav.post { updateCustomNavUI(0) }
    }

    private fun openMarketBusiness() {
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

        updateCustomNavUI(4, animate = false) // Fondo morado activo en "Gestión" (salto instantáneo)

        val optionsBinding = binding.includeInterfaceOptions
        optionsBinding.tvOption1Emoji.text = "💼"
        optionsBinding.tvOption1Title.text = "Ir a Gestión de Negocio"

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
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
            finish()
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
                updateCustomNavUI(lastActiveNavIndex, animate = false) // Salta instantáneamente al último botón
            }
            .start()
    }

    private fun updateCustomNavUI(index: Int, animate: Boolean = true) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8") 
        val buttons = listOf(binding.btnNavHome, binding.btnNavChats, binding.btnNavAccount, binding.btnNavSettings, binding.btnNavGestion)
        val icons = listOf(binding.tvIconHome, binding.tvIconChats, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
        val labels = listOf(binding.tvLabelHome, binding.tvLabelChats, binding.tvLabelAccount, binding.tvLabelSettings, binding.tvLabelGestion)
        
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
                        .setDuration(180)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    binding.navIndicatorCustom.animate().cancel()
                    binding.navIndicatorCustom.translationX = x
                }
            }
        }

        if (targetView.width > 0) alignAction.run() else targetView.post(alignAction)
    }

    private fun navigateToMarketplace() { binding.buyerViewPager.setCurrentItem(0, false); updateCustomNavUI(0) }
    private fun navigateToChats() { binding.buyerViewPager.setCurrentItem(1, false); updateCustomNavUI(1) }
    private fun navigateToAccount() { binding.buyerViewPager.setCurrentItem(2, false); updateCustomNavUI(2) }
    private fun navigateToSettings() { binding.buyerViewPager.setCurrentItem(3, false); updateCustomNavUI(3) }
    
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    fun openDrawer() = binding.drawerBuyerHome.openDrawer(GravityCompat.START)
}
