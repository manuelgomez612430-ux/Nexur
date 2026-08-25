package com.naxor.app.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.naxor.app.*
import com.naxor.app.adapter.RecentActivityAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.AppMessage
import com.naxor.app.data.QuickAction
import com.naxor.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val homeBinding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private var activityFilters = mutableSetOf<String>()
    private lateinit var quickActionsAdapter: QuickActionsAdapter
    private lateinit var recentActivityAdapter: RecentActivityAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return homeBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Cargar logo con Glide para evitar pixelado por gran tamaño del PNG
        Glide.with(this)
            .load(R.drawable.logo_naxor_full) // Cambiado a full para mejor calidad en cabecera
            .into(homeBinding.ivLogoHome)

        loadActivityFilters()
        updateUIForBusinessType()
        setupListeners()
        setupRealtimeActivityFeed()
        setupQuickActionsRecyclerView()
        setupRecentActivityRecyclerView()
        renderQuickActions()
        loadDashboardData()
        listenForNewMessages()

        val sm = SyncManager(requireContext())
        sm.startRealtimeSalesSync { loadDashboardData() }
        sm.startRealtimeLogsSync { setupRealtimeActivityFeed() }
    }

    private fun listenForNewMessages() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val lastRead = prefs.getLong("last_mailbox_read_time", 0L)

        // 1. Escuchar mensajes globales
        FirebaseFirestore.getInstance().collection("app_messages")
            .addSnapshotListener { snapshot, _ ->
                val messages = snapshot?.toObjects(AppMessage::class.java) ?: emptyList()
                checkIfAnyNew(messages, lastRead)
            }

        // 2. Escuchar mensajes privados
        FirebaseFirestore.getInstance().collection("users").document(userId).collection("messages")
            .addSnapshotListener { snapshot, _ ->
                val messages = snapshot?.toObjects(AppMessage::class.java) ?: emptyList()
                checkIfAnyNew(messages, lastRead)
            }
    }

    private fun checkIfAnyNew(messages: List<AppMessage>, lastRead: Long) {
        val hasNew = messages.any { (it.timestamp?.toDate()?.time ?: 0L) > lastRead }
        if (hasNew) {
            requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("has_new_message", true).apply()
            updateMailboxBadge()
        }
    }

    override fun onResume() {
        super.onResume()
        updateMailboxBadge()
        renderQuickActions()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            renderQuickActions()
            loadDashboardData()
        }
    }

    private fun updateMailboxBadge() {
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val hasNew = prefs.getBoolean("has_new_message", false)
        homeBinding.viewMailboxBadge.visibility = if (hasNew) View.VISIBLE else View.GONE
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

        homeBinding.btnMailboxHome.setOnClickListener {
            requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("has_new_message", false)
                .putLong("last_mailbox_read_time", System.currentTimeMillis())
                .apply()
            updateMailboxBadge()
            startToolActivity(Intent(requireContext(), MailboxActivity::class.java))
        }

        homeBinding.ivLogoHome.setOnClickListener { 
            (activity as? MainActivity)?.navigateToGestion()
        }
        homeBinding.cardLogoHome.setOnClickListener { 
            (activity as? MainActivity)?.navigateToGestion()
        }
        homeBinding.btnInfoDashboard.setOnClickListener { showFinancialInfoDialog() }
        homeBinding.btnFilterActivityMain.setOnClickListener { showActivityFilterDialog() }
        homeBinding.cardActivityPreview.setOnClickListener { startToolActivity(Intent(requireContext(), MovementsActivity::class.java)) }
        homeBinding.btnViewAllActivity.setOnClickListener { startToolActivity(Intent(requireContext(), MovementsActivity::class.java)) }
        homeBinding.cardDashboard.setOnClickListener { 
            (activity as? MainActivity)?.checkPinAndNavigate { 
                (activity as? MainActivity)?.navigateToMetricas()
            }
        }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
        activity?.overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
    }

    private fun loadDashboardData() {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        homeBinding.tvMainBusinessName.text = prefs.getString("business_name", "Mi Negocio")
        val currency = prefs.getString("currency_symbol", "S/")
        val capital = prefs.getFloat("business_capital", 0f)

        lifecycleScope.launch {
            try {
                // Calcular el inicio del día actual (00:00:00)
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfToday = calendar.timeInMillis

                val totalSalesAsync = async(Dispatchers.IO) { database.saleDao().getSalesAmountFrom(startOfToday) }
                val totalProfitAsync = async(Dispatchers.IO) { database.saleDao().getProfitFrom(startOfToday) }
                val totalExpensesAsync = async(Dispatchers.IO) { database.expenseDao().getExpensesAmountFrom(startOfToday) ?: 0.0 }

                val totalSales = totalSalesAsync.await()
                val totalProfit = totalProfitAsync.await()
                val totalExpenses = totalExpensesAsync.await()

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        homeBinding.tvVentasHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalSales)}"
                        homeBinding.tvUtilidadHoyMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", totalProfit)}"
                        homeBinding.tvGastosHoyMain.text = "- $currency ${String.format(Locale.getDefault(), "%.2f", totalExpenses)}"
                        homeBinding.tvCapitalMain.text = "$currency ${String.format(Locale.getDefault(), "%.2f", capital)}"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupRealtimeActivityFeed() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logs = database.movementLogDao().getLastMovementsOnce().take(6)
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    recentActivityAdapter.updateData(logs)
                    homeBinding.tvEmptyRecentActivity.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                    homeBinding.rvRecentActivity.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun setupRecentActivityRecyclerView() {
        recentActivityAdapter = RecentActivityAdapter(emptyList())
        homeBinding.rvRecentActivity.layoutManager = LinearLayoutManager(requireContext())
        homeBinding.rvRecentActivity.adapter = recentActivityAdapter
    }

    private fun setupQuickActionsRecyclerView() {
        homeBinding.containerQuickActions.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        quickActionsAdapter = QuickActionsAdapter(mutableListOf()) { newOrder ->
            requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("quick_actions_list", newOrder.joinToString(","))
                .apply()
        }
        homeBinding.containerQuickActions.adapter = quickActionsAdapter

        // Re-implementar el reordenamiento por arrastre
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                quickActionsAdapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(homeBinding.containerQuickActions)
    }

    fun refreshQuickActions() {
        if (!isAdded) return
        renderQuickActions()
        loadDashboardData()
        setupRealtimeActivityFeed()
    }

    private fun renderQuickActions() {
        if (!isAdded) return
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        // Usar un valor por defecto consistente
        val defaultActions = "gastos,caja,fiados,business_debts,proveedores"
        val savedActions = prefs.getString("quick_actions_list", defaultActions) ?: defaultActions
        val selectedActions = savedActions.split(",").filter { it.isNotEmpty() }

        val actionDefinitions = mapOf(
            "gastos" to Triple("💸 GASTOS", "#E11D48") { (activity as? MainActivity)?.checkPinAndNavigate { startToolActivity(Intent(requireContext(), GastosActivity::class.java)) } },
            "caja" to Triple("💰 CAJA", "#F59E0B") { startToolActivity(Intent(requireContext(), CajaActivity::class.java)) },
            "fiados" to Triple("👥 DEUDORES", "#4F46E5") { startToolActivity(Intent(requireContext(), DeudoresActivity::class.java)) },
            "business_debts" to Triple("💸 CUENTAS", "#E11D48") { startToolActivity(Intent(requireContext(), BusinessDebtsActivity::class.java)) },
            "proveedores" to Triple("🚚 PROVEED.", "#0284C7") { startToolActivity(Intent(requireContext(), ProveedoresActivity::class.java)) },
            "clientes" to Triple("👤 CLIENTES", "#059669") { startToolActivity(Intent(requireContext(), CustomersActivity::class.java)) },
            "catalogo" to Triple("📖 CATÁLOGO", "#D946EF") { (activity as? MainActivity)?.generatePDFCatalog() },
            "sync" to Triple("🔄 SYNC", "#10B981") { (activity as? MainActivity)?.manualSync() },
            "mailbox" to Triple("📬 MENSAJES", "#6366F1") { startToolActivity(Intent(requireContext(), MailboxActivity::class.java)) },
            "lista_compras" to Triple("🛒 COMPRAS", "#F97316") { startToolActivity(Intent(requireContext(), ListaComprasActivity::class.java)) },
            "asignador" to Triple("⚖️ PRECIOS", "#64748B") { startToolActivity(Intent(requireContext(), AsignadorDePreciosActivity::class.java)) },
            "sales_history" to Triple("📜 VENTAS", "#3B82F6") { (activity as? MainActivity)?.checkPinAndNavigate { startToolActivity(Intent(requireContext(), SalesHistoryActivity::class.java)) } },
            "view_history" to Triple("🕒 CÁLCULOS", "#8B5CF6") { startToolActivity(Intent(requireContext(), HistorialActivity::class.java)) },
            "instructions" to Triple("💡 AYUDA", "#14B8A6") { startToolActivity(Intent(requireContext(), InstruccionesActivity::class.java)) }
        )

        // Filtrar solo las que existen en las definiciones actuales
        val quickActionList = selectedActions.mapNotNull { actionId ->
            actionDefinitions[actionId]?.let { (name, color, action) ->
                QuickAction(actionId, name, color, { action() })
            }
        }.toMutableList()
        
        // Si por alguna razón (cambio de versión) quedaron menos de 5, rellenar con las de por defecto
        if (quickActionList.size < 5) {
            val currentIds = quickActionList.map { it.id }
            defaultActions.split(",").forEach { defId ->
                if (quickActionList.size < 5 && !currentIds.contains(defId)) {
                    actionDefinitions[defId]?.let { (name, color, action) ->
                        quickActionList.add(QuickAction(defId, name, color, { action() }))
                    }
                }
            }
        }

        if (::quickActionsAdapter.isInitialized) {
            quickActionsAdapter.updateData(quickActionList.take(5))
        }
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

    private fun showActivityFilterDialog() {
        val options = arrayOf("TODOS", "VENTAS", "GASTOS", "STOCK", "SISTEMA")
        val checked = options.map { activityFilters.contains(it) }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Filtrar Actividad")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                if (isChecked) activityFilters.add(options[which]) else activityFilters.remove(options[which])
            }
            .setPositiveButton("Aplicar") { _, _ -> setupRealtimeActivityFeed() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
