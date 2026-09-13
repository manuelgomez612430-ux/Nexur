package com.naxor.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.databinding.ActivityBuyerHomeBinding
import com.naxor.app.fragment.BuyerAccountFragment
import com.naxor.app.fragment.BuyerMarketplaceFragment
import com.naxor.app.fragment.BuyerSettingsFragment
import com.naxor.app.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BuyerHomeActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerHomeBinding
    private val marketplaceFragment = BuyerMarketplaceFragment()
    private val accountFragment = BuyerAccountFragment()
    private val settingsFragment = BuyerSettingsFragment()
    private var activeFragment: Fragment = marketplaceFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkBusinessStatus()
        setupDrawer()
        setupCustomBottomNav()
        
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, settingsFragment, "3").hide(settingsFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, accountFragment, "2").hide(accountFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, marketplaceFragment, "1").commit()

        if (intent.getBooleanExtra("START_UPDATE", false)) {
            Updater(this).checkAndDownload(autoDownload = true)
        }
    }

    override fun onResume() {
        super.onResume()
        checkBusinessStatus()
    }

    private fun refreshIndicatorPosition() {
        val icons = listOf(binding.tvIconHome, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
        val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }
        if (activeIdx != -1) {
            updateCustomNavUI(activeIdx, animate = false)
        }
    }

    private fun checkBusinessStatus() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)
        val bType = prefs.getString("business_type", "") ?: ""
        
        // Check local first (fast)
        val hasHotel = !prefs.getString("HOTEL_name", "").isNullOrEmpty()
        val hasProducts = !prefs.getString("PRODUCTS_name", "").isNullOrEmpty()
        val hasLoans = !prefs.getString("LOANS_name", "").isNullOrEmpty()
        val isLocalOwner = hasHotel || hasProducts || hasLoans || bType.isNotEmpty()

        if (isCollabMode || isLocalOwner) {
            showGestionButton(true)
        } else {
            // Check cloud (robust) - If local fails, maybe it's not synced
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                FirebaseFirestore.getInstance().collection("users").document(user.uid).collection("settings").get()
                    .addOnSuccessListener { snapshot ->
                        val isCloudOwner = snapshot.documents.any { it.id != "business" && (it.getString("business_name") ?: "").isNotEmpty() }
                        if (isCloudOwner) showGestionButton(true)
                        else {
                            // Finally check collaborations in cloud
                            val email = user.email?.lowercase() ?: ""
                            FirebaseFirestore.getInstance().collection("access_keys").whereEqualTo("email", email).get()
                                .addOnSuccessListener { colSnap ->
                                    showGestionButton(!colSnap.isEmpty)
                                }
                        }
                    }
            } else {
                showGestionButton(false)
            }
        }
    }

    private fun showGestionButton(show: Boolean) {
        binding.btnNavGestion.isVisible = show
        
        // Ajustar el peso total para que los botones visibles llenen la barra equitativamente
        val buttons = listOf(binding.btnNavHome, binding.btnNavAccount, binding.btnNavSettings, binding.btnNavGestion)
        val vCount = buttons.count { it.isVisible }
        binding.layoutCustomNav.weightSum = vCount.toFloat()

        // Re-sincronizar el indicador en el próximo frame
        binding.layoutCustomNav.post {
            val icons = listOf(binding.tvIconHome, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
            val activeIdx = icons.indexOfFirst { it.alpha == 1.0f }.coerceAtLeast(0)
            updateCustomNavUI(activeIdx)
        }
    }

    private fun setupDrawer() {
        binding.navBuyerHome.setNavigationItemSelectedListener { item ->
            binding.drawerBuyerHome.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.buyer_menu_bookings -> startActivity(Intent(this, BuyerBookingsActivity::class.java))
                R.id.buyer_menu_categories -> navigateToMarketplace()
                R.id.buyer_menu_report_error -> showReportErrorDialog()
            }
            true
        }
    }

    private fun showReportErrorDialog() {
        val etError = EditText(this).apply { hint = "Cuéntanos qué sucedió..."; minLines = 3; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Reportar un Error 🛠️").setView(etError).setPositiveButton("Enviar") { _, _ -> if (etError.text.toString().trim().isNotEmpty()) Toast.makeText(this, "Gracias por tu reporte ✨", Toast.LENGTH_LONG).show() }.setNegativeButton("Cancelar", null).show()
    }

    private fun setupCustomBottomNav() {
        binding.btnNavHome.setOnClickListener { navigateToMarketplace() }
        binding.btnNavAccount.setOnClickListener { navigateToAccount() }
        binding.btnNavSettings.setOnClickListener { navigateToSettings() }
        binding.btnNavGestion.setOnClickListener { startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }); finish() }
        binding.layoutCustomNav.post { updateCustomNavUI(0) }
    }

    private fun updateCustomNavUI(index: Int, animate: Boolean = true) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8") // Slate 400
        val buttons = listOf(binding.btnNavHome, binding.btnNavAccount, binding.btnNavSettings, binding.btnNavGestion)
        val icons = listOf(binding.tvIconHome, binding.tvIconAccount, binding.tvIconSettings, binding.tvIconGestion)
        val labels = listOf(binding.tvLabelHome, binding.tvLabelAccount, binding.tvLabelSettings, binding.tvLabelGestion)
        
        val visibleButtons = buttons.filter { it.isVisible }
        if (visibleButtons.isEmpty()) return

        var targetIdx = index
        if (targetIdx !in buttons.indices || !buttons[targetIdx].isVisible) {
            targetIdx = buttons.indexOf(visibleButtons.firstOrNull() ?: buttons[0])
        }

        // Sincronización visual inmediata
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
                    binding.navIndicatorCustom.translationX = x
                }
            }
        }

        if (targetView.width > 0) alignAction.run() else targetView.post(alignAction)
    }

    private fun navigateToMarketplace() { supportFragmentManager.beginTransaction().hide(activeFragment).show(marketplaceFragment).commit(); activeFragment = marketplaceFragment; updateCustomNavUI(0) }
    private fun navigateToAccount() { supportFragmentManager.beginTransaction().hide(activeFragment).show(accountFragment).commit(); activeFragment = accountFragment; updateCustomNavUI(1) }
    private fun navigateToSettings() { supportFragmentManager.beginTransaction().hide(activeFragment).show(settingsFragment).commit(); activeFragment = settingsFragment; updateCustomNavUI(2) }
    fun openDrawer() = binding.drawerBuyerHome.openDrawer(GravityCompat.START)
}
