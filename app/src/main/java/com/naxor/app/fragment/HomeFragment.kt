package com.naxor.app.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.naxor.app.*
import com.naxor.app.adapter.RecentActivityAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.AppMessage
import com.naxor.app.data.OrderModel
import com.naxor.app.data.QuickAction
import com.naxor.app.databinding.FragmentHomeBinding
import com.naxor.app.databinding.ItemOrderCardBinding
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val homeBinding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private val db = FirebaseFirestore.getInstance()
    private var activityFilters = mutableSetOf<String>()
    private lateinit var quickActionsAdapter: QuickActionsAdapter
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return homeBinding.root
    }

    override fun onStart() {
        super.onStart()
        listenForNewMessages()
        val sm = SyncManager(requireContext())
        sm.startRealtimeSalesSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeUnreadCountSync { count ->
            if (_binding != null) {
                if (count > 0) {
                    homeBinding.tvUnreadBadgeHome.visibility = View.VISIBLE
                    homeBinding.tvUnreadBadgeHome.text = if (count > 9) "9+" else count.toString()
                } else {
                    homeBinding.tvUnreadBadgeHome.visibility = View.GONE
                }
            }
        }?.let { registrations.add(it) }
    }

    override fun onStop() {
        super.onStop()
        // Limpieza agresiva de escuchadores para evitar calentamiento en segundo plano
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        loadUserLogo()
        loadActivityFilters()
        updateUIForBusinessType()
        setupListeners()
        setupActivityListeners()
        loadDashboardData()
    }

    private fun listenForNewMessages() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val lastRead = prefs.getLong("last_mailbox_read_time", 0L)

        // 1. Escuchar mensajes globales
        val regGlobal = FirebaseFirestore.getInstance().collection("app_messages")
            .addSnapshotListener { snapshot, _ ->
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val id = doc.id
                        val title = doc.getString("title") ?: ""
                        val content = doc.getString("content") ?: ""
                        val timestamp = doc.get("timestamp")
                        AppMessage(id, title, content, timestamp)
                    } catch (e: Exception) { null }
                } ?: emptyList()
                checkIfAnyNew(messages, lastRead)
            }
        registrations.add(regGlobal)

        // 2. Escuchar mensajes privados
        val regPrivate = FirebaseFirestore.getInstance().collection("users").document(userId).collection("messages")
            .addSnapshotListener { snapshotPriv, _ ->
                val messages = snapshotPriv?.documents?.mapNotNull { doc ->
                    try {
                        val id = doc.id
                        val title = doc.getString("title") ?: ""
                        val content = doc.getString("content") ?: ""
                        val timestamp = doc.get("timestamp")
                        AppMessage(id, title, content, timestamp)
                    } catch (e: Exception) { null }
                } ?: emptyList()
                checkIfAnyNew(messages, lastRead)
            }
        registrations.add(regPrivate)
    }

    private fun checkIfAnyNew(messages: List<AppMessage>, lastRead: Long) {
        val hasNew = messages.any { message ->
            val ts = when (val t = message.timestamp) {
                is Timestamp -> t.toDate().time
                is String -> try { t.toLong() } catch (e: Exception) { 0L }
                is Long -> t
                else -> 0L
            }
            ts > lastRead
        }
        if (hasNew) {
            requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("has_new_message", true).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCollabBadge()
        loadUserLogo()
    }

    private fun updateCollabBadge() {
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val hasNew = prefs.getBoolean("has_new_request", false)
        homeBinding.viewCollabBadgeMain.visibility = if (hasNew) View.VISIBLE else View.GONE
    }

    private fun clearCollabBadge() {
        requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_new_request", false).apply()
        homeBinding.viewCollabBadgeMain.visibility = View.GONE
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadDashboardData()
        }
    }



    private fun loadActivityFilters() {
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("activity_filters_v2", "TODOS") ?: "TODOS"
        activityFilters = saved.split(",").filter { it.isNotEmpty() }.toMutableSet()
        if (activityFilters.isEmpty()) activityFilters.add("TODOS")
    }

    private fun updateUIForBusinessType() {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")
        
        if (businessType == "SERVICES") {
            homeBinding.tvRealizarVentaText.text = "REGISTRAR SERVICIO"
        } else {
            homeBinding.tvRealizarVentaText.text = "REALIZAR VENTA"
        }
    }

    private fun setupListeners() {
        homeBinding.btnIrVentas.setOnClickListener { startToolActivity(Intent(requireContext(), VentasActivity::class.java)) }
        homeBinding.btnScanVenta.setOnClickListener { (activity as? MainActivity)?.startDirectScanner() }

        homeBinding.ivLogoHome.setOnClickListener { 
            clearCollabBadge()
            (activity as? MainActivity)?.navigateToGestion()
        }
        homeBinding.cardLogoHome.setOnClickListener { 
            clearCollabBadge()
            (activity as? MainActivity)?.navigateToGestion()
        }
        homeBinding.btnInfoDashboard.setOnClickListener { showFinancialInfoDialog() }
        
        homeBinding.btnMensajesCentral.setOnClickListener { 
            startToolActivity(Intent(requireContext(), MailboxActivity::class.java)) 
        }

        homeBinding.btnGastosCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), GastosActivity::class.java))
        }

        homeBinding.btnDeliveriesCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), DeliveriesActivity::class.java))
        }

        homeBinding.btnCompromisosCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), CommitmentsActivity::class.java))
        }

        homeBinding.btnCajaCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), CajaActivity::class.java))
        }

        homeBinding.btnDeudoresCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), DeudoresActivity::class.java))
        }

        homeBinding.btnHistorialCentral.setOnClickListener {
            startToolActivity(Intent(requireContext(), SalesHistoryActivity::class.java))
        }

        homeBinding.cardDashboard.setOnClickListener { 
            (activity as? MainActivity)?.checkPinAndNavigate { 
                (activity as? MainActivity)?.navigateToMetricas()
            }
        }
    }

    private fun loadUserLogo() {
        if (_binding == null || !isAdded) return
        
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val cloudLogo = prefs.getString("${businessType}_logo_url", null)
        val localPath = prefs.getString("business_logo_local", null)
        
        val glide = Glide.with(this)
        
        when {
            // 1. Prioridad: Logo en la nube
            !cloudLogo.isNullOrEmpty() -> {
                glide.load(cloudLogo)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .into(homeBinding.ivLogoHome)
            }
            // 2. Respaldo: Logo local
            !localPath.isNullOrEmpty() -> {
                val file = File(localPath)
                if (file.exists()) {
                    glide.load(file)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(homeBinding.ivLogoHome)
                }
            }
            // 3. Fallback: Logo por defecto
            else -> {
                glide.load(R.drawable.gomezmart_icon)
                    .circleCrop()
                    .into(homeBinding.ivLogoHome)
            }
        }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
        activity?.overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }

    private var lastDashboardLoad: Long = 0
    private fun loadDashboardData() {
        val now = System.currentTimeMillis()
        if (now - lastDashboardLoad < 1000) return
        lastDashboardLoad = now
        
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)

        val businessType = bizPrefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val systemName = bizPrefs.getString("${businessType}_name", "") ?: ""
        val businessName = bizPrefs.getString("business_name", "") ?: ""

        val userFullName = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("user_full_name", null) ?: FirebaseAuth.getInstance().currentUser?.displayName ?: "Usuario"

        if (isCollab) {
            val collabName = collabPrefs.getString("collab_name", "Colaborador")
            homeBinding.tvMainBusinessName.text = "¡Bienvenido, $collabName!"
            homeBinding.tvWelcomeSubtitle.visibility = View.GONE
        } else {
            homeBinding.tvMainBusinessName.text = "¡Bienvenido, $userFullName!"
            homeBinding.tvWelcomeSubtitle.visibility = View.GONE
        }
        
        val currency = bizPrefs.getString("currency_symbol", "S/") ?: "S/"
        val capital = bizPrefs.getFloat("business_capital", 0f)

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfToday = calendar.timeInMillis

                val totalSales = withContext(Dispatchers.IO) { database.saleDao().getSalesAmountFrom(startOfToday) }
                val totalProfit = withContext(Dispatchers.IO) { database.saleDao().getProfitFrom(startOfToday) }
                val totalExpenses = withContext(Dispatchers.IO) { database.expenseDao().getExpensesAmountFrom(startOfToday) ?: 0.0 }

                if (_binding != null) {
                    homeBinding.tvVentasHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalSales)}"
                    homeBinding.tvUtilidadHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalProfit)}"
                    homeBinding.tvGastosHoyMain.text = "- $currency ${String.format(Locale.getDefault(), "%.2f", totalExpenses)}"
                    homeBinding.tvCapitalMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", capital)}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshQuickActions() {
        if (!isAdded) return
        loadDashboardData()
    }

    private fun showFinancialInfoDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Dashboard Financiero")
            .setMessage("Resumen de tus operaciones de hoy.")
            .setPositiveButton("Cerrar", null).show()
    }

    fun checkPinAndNavigate(onSuccess: () -> Unit) {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("user_pin", "") ?: ""
        if (savedPin.isEmpty()) { onSuccess(); return }
        val etPin = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Escribe el PIN"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Acceso Restringido")
            .setMessage("Para ver esta sección, ingresa tu PIN de seguridad:")
            .setView(etPin)
            .setPositiveButton("Entrar") { _, _ ->
                if (etPin.text.toString() == savedPin) onSuccess()
                else Toast.makeText(requireContext(), "PIN Incorrecto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun setupActivityListeners() {
        // Asumiendo que homeBinding está disponible y tiene el ID correcto
        homeBinding.btnSupportCreatorMain.setOnClickListener {
            startToolActivity(Intent(requireContext(), com.naxor.app.SupportCreatorActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
