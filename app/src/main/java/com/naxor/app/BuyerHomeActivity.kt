package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.databinding.ActivityBuyerHomeBinding
import com.naxor.app.fragment.BuyerAccountFragment
import com.naxor.app.fragment.BuyerMarketplaceFragment
import com.naxor.app.fragment.BuyerSettingsFragment
import com.naxor.app.util.SessionManager
import kotlinx.coroutines.launch

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

        setupDrawer()
        setupCustomBottomNav()
        
        // Inicializar fragmentos sin recrearlos
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, settingsFragment, "3").hide(settingsFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, accountFragment, "2").hide(accountFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.buyerFragmentContainer, marketplaceFragment, "1").commit()

        if (intent.getBooleanExtra("START_UPDATE", false)) {
            Updater(this).checkAndDownload(autoDownload = true)
        }
    }

    private fun setupDrawer() {
        binding.navBuyerHome.setNavigationItemSelectedListener { item ->
            binding.drawerBuyerHome.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.buyer_menu_bookings -> {
                    startActivity(Intent(this, BuyerBookingsActivity::class.java))
                }
                R.id.buyer_menu_categories -> {
                    navigateToMarketplace()
                    Toast.makeText(this, "Explora los rubros arriba 👆", Toast.LENGTH_SHORT).show()
                }
                R.id.buyer_menu_report_error -> {
                    showReportErrorDialog()
                }
            }
            true
        }
    }

    private fun showReportErrorDialog() {
        val etError = EditText(this).apply {
            hint = "Cuéntanos qué sucedió..."
            minLines = 3
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Reportar un Error 🛠️")
            .setMessage("Tu opinión nos ayuda a mejorar. Describe el problema:")
            .setView(etError)
            .setPositiveButton("Enviar") { _, _ ->
                val errorMsg = etError.text.toString().trim()
                if (errorMsg.isNotEmpty()) {
                    Toast.makeText(this, "Gracias por tu reporte. Lo revisaremos pronto ✨", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupCustomBottomNav() {
        binding.btnNavHome.setOnClickListener { navigateToMarketplace() }
        binding.btnNavAccount.setOnClickListener { navigateToAccount() }
        binding.btnNavSettings.setOnClickListener { navigateToSettings() }

        binding.layoutCustomNav.post {
            updateCustomNavUI(0)
        }
    }

    private fun updateCustomNavUI(index: Int) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#94A3B8") // Slate 400

        // Reset
        val labels = listOf(binding.tvLabelHome, binding.tvLabelAccount, binding.tvLabelSettings)
        val icons = listOf(binding.tvIconHome, binding.tvIconAccount, binding.tvIconSettings)
        
        labels.forEach { it.setTextColor(inactiveColor) }
        icons.forEach { it.alpha = 0.6f }

        // Active
        if (index in labels.indices) {
            labels[index].setTextColor(activeColor)
            icons[index].alpha = 1.0f
        }

        // Move indicator
        val totalWidth = binding.layoutCustomNav.width
        if (totalWidth <= 0) return
        
        val step = totalWidth / 3
        
        binding.navIndicatorCustom.animate()
            .translationX((index * step).toFloat())
            .setDuration(250)
            .start()
            
        val params = binding.navIndicatorCustom.layoutParams as ConstraintLayout.LayoutParams
        params.width = step
        binding.navIndicatorCustom.layoutParams = params
    }

    fun navigateToMarketplace() {
        if (activeFragment == marketplaceFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(marketplaceFragment).commit()
        activeFragment = marketplaceFragment
        updateCustomNavUI(0)
    }

    fun navigateToAccount() {
        if (activeFragment == accountFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(accountFragment).commit()
        activeFragment = accountFragment
        updateCustomNavUI(1)
    }

    fun navigateToSettings() {
        if (activeFragment == settingsFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(settingsFragment).commit()
        activeFragment = settingsFragment
        updateCustomNavUI(2)
    }

    fun openDrawer() {
        if (binding.navBuyerHome.headerCount > 0) {
            val headerView = binding.navBuyerHome.getHeaderView(0)
            
            // Títulos del Mercado
            headerView.findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Mercado GoMezMart"
            headerView.findViewById<TextView>(R.id.tvHeaderLabel)?.text = "RED LOCAL ACTIVA"
            
            // Logo Estándar
            headerView.findViewById<ImageView>(R.id.ivDrawerLogo)?.setImageResource(R.drawable.logotipo_de_gomezmart)
            
            // Ocultar botones de gestión (Solo para Compradores)
            val buttonsToHide = listOf(
                R.id.btnDrawerPublishService,
                R.id.btnDrawerSwitchSystem,
                R.id.btnDrawerManageTeam,
                R.id.btnDrawerGoToMarket,
                R.id.btnDrawerMyCollaborations,
                R.id.btnDrawerCollabAccount
            )
            
            buttonsToHide.forEach { id ->
                headerView.findViewById<View>(id)?.visibility = View.GONE
            }
        }

        binding.drawerBuyerHome.open()
    }
}
