package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentLoansHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LoansHomeFragment : Fragment() {

    private var _binding: FragmentLoansHomeBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val registrations = mutableListOf<ListenerRegistration>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        setupSyncIndicator()
        loadDashboardData()
        loadBudgetData()
        updateWelcomeMessage()

        val sm = com.naxor.app.SyncManager(requireContext())
        sm.startRealtimeLoanClientsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoansSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeInstallmentsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoanPaymentsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoanExpensesSystemSync { loadDashboardData() }?.let { registrations.add(it) }
    }

    private fun setupSyncIndicator() {
        // Monitorear Red
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                activity?.runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                activity?.runOnUiThread { updateSyncIconState() }
            }
        }
        networkCallback?.let { cm.registerDefaultNetworkCallback(it) }

        // Monitorear Cambios Locales
        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getUnsyncedCount().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Sincronización de Cartera")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus\n\nLos datos se guardan automáticamente en la nube cuando tienes internet.")
                .setPositiveButton("Sincronizar ahora") { _, _ -> 
                    com.naxor.app.SyncManager(requireContext()).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
        if (_binding == null) return
        
        val color: Int
        val iconRes: Int
        
        when {
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = android.graphics.Color.parseColor("#DC2626") // Rojo
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6") // Azul
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#059669") // Verde
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun loadBudgetData() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getLatestBudget().collectLatest { budget ->
                if (budget == null) {
                    binding.tvBudgetRemaining.text = "S/ 0.00"
                    binding.tvBudgetDetail.text = "Pulsa 'Configurar' para establecer un presupuesto"
                    binding.progressBudget.progress = 0
                    return@collectLatest
                }

                val startTime = budget.startDate
                val loansFlow = database.loanDao().getAllLoans()
                val expensesFlow = database.loanDao().getAllExpenses()

                kotlinx.coroutines.flow.combine(loansFlow, expensesFlow) { loans, expenses ->
                    val totalLent = loans.filter { it.startDate >= startTime }.sumOf { it.amount }
                    val totalSpent = expenses.filter { it.timestamp >= startTime }.sumOf { it.amount }
                    
                    val used = totalLent + totalSpent
                    val remaining = budget.amount - used
                    val percent = if (budget.amount > 0) (used / budget.amount * 100).toInt() else 0

                    withContext(Dispatchers.Main) {
                        binding.tvBudgetTitle.text = "Presupuesto ${if(budget.period == "MONTHLY") "Mensual" else "Semanal"}"
                        binding.tvBudgetRemaining.text = String.format(Locale.US, "S/ %.2f disponibles", if(remaining > 0) remaining else 0.0)
                        binding.tvBudgetDetail.text = String.format(Locale.US, "Has usado S/ %.2f de S/ %.2f", used, budget.amount)
                        
                        binding.progressBudget.progress = percent.coerceIn(0, 100)
                        if (percent >= 90) {
                            binding.progressBudget.setIndicatorColor(android.graphics.Color.parseColor("#DC2626"))
                            binding.tvBudgetRemaining.setTextColor(android.graphics.Color.parseColor("#DC2626"))
                        } else {
                            binding.progressBudget.setIndicatorColor(android.graphics.Color.parseColor("#059669"))
                            binding.tvBudgetRemaining.setTextColor(android.graphics.Color.parseColor("#059669"))
                        }
                    }
                }.collectLatest { }
            }
        }
    }

    private fun showBudgetConfigDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        val etAmount = android.widget.EditText(requireContext()).apply { 
            hint = "Monto del presupuesto (S/)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL 
        }

        // Usaremos botones para una selección más clara y evitar errores del spinner
        val toggleGroup = com.google.android.material.button.MaterialButtonToggleGroup(requireContext()).apply {
            id = View.generateViewId()
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 20, 0, 20) }
        }

        val btnWeekly = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = "Semanal"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMonthly = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = "Mensual"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        toggleGroup.addView(btnWeekly)
        toggleGroup.addView(btnMonthly)
        toggleGroup.check(btnMonthly.id) // Mensual por defecto

        layout.addView(android.widget.TextView(requireContext()).apply { text = "Monto Total:"; setPadding(0,10,0,10) })
        layout.addView(etAmount)
        layout.addView(android.widget.TextView(requireContext()).apply { text = "Periodo:"; setPadding(0,30,0,10) })
        layout.addView(toggleGroup)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Configurar Presupuesto")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    val period = if (toggleGroup.checkedButtonId == btnWeekly.id) "WEEKLY" else "MONTHLY"
                    saveBudget(amount, period)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveBudget(amount: Double, period: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val budget = com.naxor.app.data.LoanBudgetEntity(
                amount = amount,
                period = period,
                startDate = System.currentTimeMillis()
            )
            database.loanDao().insertBudget(budget)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Presupuesto guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbarLoansHome.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
        binding.toolbarLoansHome.inflateMenu(R.menu.menu_mailbox)
        binding.toolbarLoansHome.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_mailbox) {
                startActivity(Intent(requireContext(), com.naxor.app.MailboxActivity::class.java))
                true
            } else false
        }
    }

    private fun updateWelcomeMessage() {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "LOANS") ?: "LOANS"
        
        val businessName = prefs.getString("business_name", "") ?: ""
        val systemName = prefs.getString("${businessType}_name", "") ?: ""
        val ownerName = prefs.getString("${businessType}_owner_name", "") ?: ""
        
        val finalBusinessName = when {
            systemName.isNotEmpty() -> systemName
            businessName.isNotEmpty() -> businessName
            else -> ""
        }

        if (ownerName.isNotEmpty()) {
            binding.tvWelcomeLoans.text = "¡Hola, $ownerName! 👋"
            if (finalBusinessName.isNotEmpty()) {
                binding.tvLoansDate.text = "Bienvenido al sistema de $finalBusinessName"
            } else {
                val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
                binding.tvLoansDate.text = "Hoy es " + sdf.format(Date()).replaceFirstChar { it.uppercase() }
            }
        } else {
            val displayName = if (finalBusinessName.isNotEmpty()) finalBusinessName else "Gestor"
            binding.tvWelcomeLoans.text = "¡Hola, $displayName! 👋"
            val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
            binding.tvLoansDate.text = "Hoy es " + sdf.format(Date()).replaceFirstChar { it.uppercase() }
        }
    }

    private fun setupListeners() {
        binding.btnQuickNewLoan.setOnClickListener { showNewLoanOptions() }
        binding.btnQuickRegisterExpense.setOnClickListener { showQuickExpenseDialog() }

        binding.btnConfigBudget.setOnClickListener { showBudgetConfigDialog() }
        binding.cardLoanBudget.setOnClickListener { showBudgetConfigDialog() }
        
        binding.toolLoansClients.setOnClickListener { 
            (activity as? MainActivity)?.navigateToStock() // Pestaña de clientes
        }
        binding.cardTodayCollections.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.LoansCollectionsActivity::class.java))
        }
        binding.toolLoansExpenses.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.LoansExpensesActivity::class.java))
        }
        binding.toolLoansCommitments.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.CommitmentsActivity::class.java))
        }
        binding.btnSupportCreatorHome.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.SupportCreatorActivity::class.java))
        }
    }

    private fun showNewLoanOptions() {
        val options = arrayOf("👤 Cliente existente", "🆕 Nuevo cliente")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Registrar Préstamo")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val intent = Intent(requireContext(), com.naxor.app.LoansClientsActivity::class.java)
                    intent.putExtra("PICK_MODE", true)
                    startActivity(intent)
                } else {
                    startActivity(Intent(requireContext(), com.naxor.app.AddLoanActivity::class.java))
                }
            }
            .show()
    }

    private fun showQuickExpenseDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_loan_expense, null)
        val autoCategory = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteCategory)
        val etConcept = dialogView.findViewById<EditText>(R.id.etExpenseConcept)
        val etAmount = dialogView.findViewById<EditText>(R.id.etExpenseAmount)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelExpense)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveExpense)

        // Cargar categorías existentes para el autocompletado
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val categories = database.loanDao().getAllLoanExpensesOnce()
                .map { it.category }
                .distinct()
                .filter { it.isNotEmpty() }
            
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
                autoCategory.setAdapter(adapter)
                
                // Mostrar sugerencias al tocar el campo
                autoCategory.setOnClickListener {
                    autoCategory.showDropDown()
                }
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val category = autoCategory.text.toString().trim()
            val concept = etConcept.text.toString().trim()
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0

            if (category.isEmpty()) {
                autoCategory.error = "Ingresa una categoría"
                return@setOnClickListener
            }
            if (amount <= 0) {
                etAmount.error = "Ingresa un monto válido"
                return@setOnClickListener
            }

            saveQuickExpense(category, concept, amount)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveQuickExpense(category: String, concept: String, amount: Double) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val expense = com.naxor.app.data.LoanExpenseEntity(
                concept = concept.ifEmpty { "Gasto rápido" },
                amount = amount,
                category = category,
                timestamp = System.currentTimeMillis()
            )
            database.loanDao().insertExpense(expense)
            com.naxor.app.SyncManager(requireContext()).scheduleOfflineSync()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Gasto registrado ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadDashboardData() {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")

        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getCapitalInStreetFlow().collectLatest { capital ->
                binding.tvStatCapitalLent.text = String.format(Locale.US, "$currency %.2f", capital ?: 0.0)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getTotalOutstandingBalanceFlow().collectLatest { balance ->
                binding.tvStatOutstandingBalance.text = String.format(Locale.US, "$currency %.2f", balance ?: 0.0)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val totalExpensesFlow = database.loanDao().getTotalExpensesFlow()
            val totalLateFeesFlow = database.loanDao().getTotalLateFeesFlow()
            val allInstallmentsFlow = database.loanDao().getAllInstallmentsFlow()
            val allLoansFlow = database.loanDao().getAllLoans()
            
            kotlinx.coroutines.flow.combine(allInstallmentsFlow, allLoansFlow, totalExpensesFlow, totalLateFeesFlow) { insts, loans, expenses, lateFees ->
                var interestCollected = 0.0
                
                loans.forEach { loan ->
                    val loanInstallments = insts.filter { it.loanId == loan.id }
                    val totalPaid = loanInstallments.sumOf { it.amountPaid }
                    if (totalPaid > 0) {
                        val interestRatio = (loan.totalToPay - loan.amount) / loan.totalToPay
                        interestCollected += totalPaid * interestRatio
                    }
                }
                
                interestCollected + (lateFees ?: 0.0) - (expenses ?: 0.0)
            }.collectLatest { netProfit ->
                binding.tvStatNetProfit.text = String.format(Locale.US, "$currency %.2f", netProfit)
                if (netProfit < 0) {
                    binding.tvStatNetProfit.setTextColor(android.graphics.Color.parseColor("#DC2626")) // Rojo (red_600)
                } else {
                    binding.tvStatNetProfit.setTextColor(android.graphics.Color.parseColor("#059669")) // Verde (emerald_600)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        networkCallback?.let {
            val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        _binding = null
    }
}
