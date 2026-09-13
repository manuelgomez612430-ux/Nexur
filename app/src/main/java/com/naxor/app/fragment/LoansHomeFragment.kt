package com.naxor.app.fragment

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.SyncManager
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanBudgetEntity
import com.naxor.app.databinding.FragmentLoansHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

                val loansFlow = database.loanDao().getAllLoans()
                val expensesFlow = database.loanDao().getAllExpenses()
                val paymentsFlow = database.loanDao().getAllPaymentsFlowV2()
                val installmentsFlow = database.loanDao().getAllInstallmentsFlow()

                combine(loansFlow, expensesFlow, paymentsFlow, installmentsFlow) { loans, expenses, payments, installments ->
                    // Filtrar por periodo actual si el budget lo requiere
                    val startTime = budget.startDate

                    // 1. Calcular Ganancia Cobrada Total
                    var collectedProfit = 0.0
                    payments.forEach { p ->
                        val loan = loans.find { it.id == p.loanId }
                        loan?.let {
                            val profitRatio = (it.totalToPay - it.amount) / it.totalToPay
                            collectedProfit += (p.amount * profitRatio) + p.lateFeeAmount
                        }
                    }

                    // 2. Calcular Capital en la Calle ACTUAL
                    var capitalInStreet = 0.0
                    installments.filter { it.status != "PAID" }.forEach { inst ->
                        val loan = loans.find { it.id == inst.loanId }
                        loan?.let {
                            val capitalRatio = it.amount / it.totalToPay
                            capitalInStreet += (inst.amount - inst.amountPaid) * capitalRatio
                        }
                    }

                    // 3. Gastos registrados DESDE que se inició este presupuesto
                    val totalSpent = expenses.filter { it.timestamp >= startTime }.sumOf { it.amount }
                    
                    // 4. Calcular presupuesto disponible y uso
                    // El capital total disponible para el negocio es Presupuesto + Ganancias
                    val totalCapital = budget.amount + collectedProfit
                    
                    // El capital usado es lo que está prestado actualmente + gastos del periodo
                    val used = capitalInStreet + totalSpent
                    
                    // Lo disponible es el capital total menos lo que ya está fuera
                    val remaining = (totalCapital - used).coerceAtLeast(0.0)
                    
                    val percent = if (totalCapital > 0) (used / totalCapital * 100).toInt() else 0

                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        
                        binding.tvBudgetTitle.text = "Presupuesto ${if(budget.period == "MONTHLY") "Mensual" else "Semanal"}"
                        binding.tvBudgetRemaining.text = String.format(Locale.US, "S/ %.2f disponibles", remaining)
                        binding.tvBudgetDetail.text = String.format(Locale.US, "Has usado S/ %.2f de S/ %.2f", used, totalCapital)
                        
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
        viewLifecycleOwner.lifecycleScope.launch {
            // Obtenemos el presupuesto actual para pre-rellenar el campo
            val currentBudget = withContext(Dispatchers.IO) {
                database.loanDao().getLatestBudgetOnce()
            }
            
            withContext(Dispatchers.Main) {
                val layout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(60, 40, 60, 20)
                }
                
                val tvExplanation = TextView(requireContext()).apply {
                    text = "Ingresa tu Capital Total de inversión. El sistema sumará las ganancias automáticamente."
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, 20)
                }

                val etAmount = EditText(requireContext()).apply {
                    hint = "Ej: 1500.00"
                    setTextColor(Color.BLACK) // Asegurar visibilidad
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    // PRE-RELLENAR con el valor actual
                    currentBudget?.let { 
                        setText(it.amount.toString())
                        setSelection(text.length)
                    }
                }

                // Usaremos botones para una selección más clara
                val toggleGroup = MaterialButtonToggleGroup(requireContext()).apply {
                    id = View.generateViewId()
                    isSingleSelection = true
                    isSelectionRequired = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 20, 0, 20) }
                }

                val btnWeekly = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Semanal"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnMonthly = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Mensual"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                toggleGroup.addView(btnWeekly)
                toggleGroup.addView(btnMonthly)
                
                // Seleccionar periodo actual o mensual por defecto
                if (currentBudget?.period == "WEEKLY") toggleGroup.check(btnWeekly.id)
                else toggleGroup.check(btnMonthly.id)

                layout.addView(tvExplanation)
                layout.addView(TextView(requireContext()).apply { text = "Monto de Capital:"; setPadding(0,10,0,10); setTextColor(Color.BLACK) })
                layout.addView(etAmount)
                layout.addView(TextView(requireContext()).apply { text = "Periodo de Revisión:"; setPadding(0,30,0,10); setTextColor(Color.BLACK) })
                layout.addView(toggleGroup)

                val builder = AlertDialog.Builder(requireContext())
                    .setTitle("Configurar Capital de Trabajo")
                    .setView(layout)
                    .setPositiveButton("Guardar") { _, _ ->
                        val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                        val period = if (toggleGroup.checkedButtonId == btnWeekly.id) "WEEKLY" else "MONTHLY"
                        saveBudget(amount, period)
                    }
                    .setNegativeButton("Cancelar", null)

                // Botón neutral para eliminar solo si existe
                if (currentBudget != null) {
                    builder.setNeutralButton("Eliminar") { _, _ ->
                        showDeleteBudgetConfirmation()
                    }
                }
                
                builder.show()
            }
        }
    }

    private fun showDeleteBudgetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Eliminar presupuesto?")
            .setMessage("Se borrará tu capital de trabajo configurado. Los cálculos de disponibilidad volverán a cero.")
            .setPositiveButton("Eliminar") { _, _ -> deleteBudget() }
            .setNegativeButton("No, mantener", null)
            .show()
    }

    private fun deleteBudget() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val currentBudget = database.loanDao().getLatestBudgetOnce()
            currentBudget?.let {
                val deletedBudget = it.copy(isDeleted = true, isSynced = false)
                database.loanDao().updateBudget(deletedBudget)
                SyncManager(requireContext()).scheduleOfflineSync()
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Presupuesto eliminado 🗑️", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBudget(amount: Double, period: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Buscamos si ya existe uno para mantener la fecha de inicio si es el mismo periodo
            val existing = database.loanDao().getLatestBudgetOnce()
            
            val newStartDate = if (existing != null && existing.period == period) {
                existing.startDate // Mantener fecha de inicio si solo actualiza monto
            } else {
                // Si cambia de periodo (ej: de Semanal a Mensual), resetear a hoy
                System.currentTimeMillis()
            }

            val budget = LoanBudgetEntity(
                id = "active_loan_budget", // ID FIJO para evitar duplicados y errores de sincronización
                amount = amount,
                period = period,
                startDate = newStartDate,
                timestamp = System.currentTimeMillis()
            )
            database.loanDao().insertBudget(budget)
            
            // Sincronización inmediata
            SyncManager(requireContext()).scheduleOfflineSync()

            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Presupuesto guardado ✨", Toast.LENGTH_SHORT).show()
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
