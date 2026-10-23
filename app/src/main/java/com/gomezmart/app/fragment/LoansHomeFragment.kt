package com.gomezmart.app.fragment

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import com.bumptech.glide.Glide
import com.gomezmart.app.AccountsActivity
import com.gomezmart.app.AddLoanActivity
import com.gomezmart.app.AiChatActivity
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.CommitmentsActivity
import com.gomezmart.app.MovementsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.MailboxActivity
import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.SyncManager
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.AppMessage
import com.gomezmart.app.data.LoanBudgetEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.FragmentLoansHomeBinding
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private val registrations = mutableListOf<ListenerRegistration>()
    private var budgetJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        loadDashboardData()
        loadBudgetData()

        val ctx = context ?: return
        val sm = SyncManager(ctx)
        sm.startRealtimeLoanClientsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoansSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeInstallmentsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoanPaymentsSync { loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoanExpensesSystemSync { loadBudgetData(); loadDashboardData() }?.let { registrations.add(it) }
        sm.startRealtimeLoanBudgetsSync { loadBudgetData(); loadDashboardData() }?.let { registrations.add(it) }

        listenForNewMessages()
    }

    override fun onResume() {
        super.onResume()
        loadDashboardData()
        loadBudgetData()
        // Refresca el nombre de la cabecera por si el usuario lo acaba de editar
        // en "Ajustes de Cuenta" (vuelve a esta pantalla al guardar).
        refreshHeaderUserName()
        // NOTA: Antes aquí se llamaba a downloadEverythingFromCloud() en CADA
        // regreso a la pantalla. Eso hacía ~15 lecturas completas de Firestore por
        // visita y, sobre todo, SOBRESCRIBÍA los cambios creados sin conexión
        // (isSynced = 0) por la versión de la nube, perdiéndolos.
        // Ahora los datos llegan mediante los listeners realtime que ya existen
        // (startRealtimeLoansSync, startRealtimeInstallmentsSync, ...), y la
        // descarga completa solo se lanza desde el botón "Sincronizar".
    }

    /** Actualiza el nombre mostrado en la cabecera (avatar + banderita). */
    private fun refreshHeaderUserName() {
        if (_binding == null) return
        val prefs = context?.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) ?: return
        val user = FirebaseAuth.getInstance().currentUser
        binding.tvHeaderUserName.text = prefs.getString("user_full_name", null)
            ?: user?.displayName
            ?: "Usuario"
    }

    override fun onStart() {
        super.onStart()
    }

    private fun listenForNewMessages() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = FirebaseFirestore.getInstance()
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val lastRead = prefs.getLong("last_mailbox_read_time", 0L)

        // 1. Escuchar mensajes globales
        val regGlobal = firestore.collection("app_messages")
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
        val regPrivate = firestore.collection("users").document(userId).collection("messages")
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

    private fun loadBudgetData() {
        val dbInstance = database ?: return
        budgetJob?.cancel()
        budgetJob = viewLifecycleOwner.lifecycleScope.launch {
            dbInstance.loanDao().getLatestBudget().collectLatest { budget ->
                if (budget == null) {
                    binding.tvBudgetRemaining.text = "S/ 0.00"
                    binding.tvBudgetDetail.text = "Pulsa 'Configurar' para establecer un presupuesto"
                    binding.progressBudget.progress = 0
                    return@collectLatest
                }

                val loansFlow = dbInstance.loanDao().getAllLoans()
                val expensesFlow = dbInstance.loanDao().getAllExpenses()
                val paymentsFlow = dbInstance.loanDao().getAllPaymentsFlowV2()
                val installmentsFlow = dbInstance.loanDao().getAllInstallmentsFlow()

                combine(loansFlow, expensesFlow, paymentsFlow, installmentsFlow) { loans, expenses, payments, installments ->
                    val loansMap = loans.associateBy { it.id }
                    val startTime = budget.startDate

                    // 1. Calcular Ganancia Cobrada Total
                    var collectedProfit = 0.0
                    payments.forEach { p ->
                        val loan = loansMap[p.loanId]
                        loan?.let {
                            val profitRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                            collectedProfit += (p.amount * profitRatio) + p.lateFeeAmount
                        }
                    }

                    // 2. Calcular Capital en la Calle ACTUAL (Basado en el principal del préstamo menos lo pagado a capital)
                    var capitalInStreet = 0.0
                    val activeBusinessLoans = loans.filter { !it.isDeleted && !it.isPersonal && (it.status == "ACTIVE" || it.status == "OVERDUE") && (budget.startDate == 0L || it.startDate >= budget.startDate) }
                    activeBusinessLoans.forEach { loan ->
                        val loanInsts = installments.filter { it.loanId == loan.id && !it.isDeleted }
                        val totalPaidToLoan = loanInsts.sumOf { it.amountPaid }
                        val totalToPay = loan.totalToPay
                        val interestRatio = if (totalToPay > 0.0) (totalToPay - loan.amount) / totalToPay else 0.0
                        val capitalRatio = 1.0 - interestRatio
                        val capitalPaid = totalPaidToLoan * capitalRatio
                        val remCapital = (loan.amount - capitalPaid).coerceAtLeast(0.0)
                        capitalInStreet += remCapital
                    }

                    val baseCapital = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).getFloat("base_budget_amount", 500f).toDouble()
                    if (capitalInStreet >= baseCapital * 0.90 && capitalInStreet <= baseCapital * 1.30) {
                        capitalInStreet = baseCapital
                    }

                    // 3. Gastos registrados DESDE que se inició este presupuesto
                    // FILTRAR: Solo gastos de negocio (No personales)
                    val totalSpent = expenses.filter { it.timestamp >= startTime && !it.isPersonal }.sumOf { it.amount }
                    
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
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val currentBudget = withContext(Dispatchers.IO) {
                dbInstance.loanDao().getLatestBudgetOnce()
            }
            
            withContext(Dispatchers.Main) {
                val layout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(60, 40, 60, 20)
                }
                
                val tvCurrentInfo = TextView(requireContext()).apply {
                    text = if (currentBudget != null) String.format(Locale.US, "Capital actual en marcha: S/ %.2f", currentBudget.amount) else "No hay presupuesto activo."
                    textSize = 12f
                    setTextColor(Color.parseColor("#059669"))
                    setPadding(0, 0, 0, 10)
                }

                val toggleModeGroup = MaterialButtonToggleGroup(requireContext()).apply {
                    id = View.generateViewId()
                    isSingleSelection = true
                    isSelectionRequired = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 5, 0, 15) }
                }

                val btnModeBase = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Definir Base"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnModeInject = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = "Inyección Extra"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                toggleModeGroup.addView(btnModeBase)
                toggleModeGroup.addView(btnModeInject)
                toggleModeGroup.check(btnModeBase.id)

                val tvExplanation = TextView(requireContext()).apply {
                    text = "Ingresa tu Capital Total de inversión. El sistema sumará las ganancias automáticamente."
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, 15)
                }

                val etAmount = EditText(requireContext()).apply {
                    hint = "Ej: 1500.00"
                    setTextColor(Color.BLACK)
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    currentBudget?.let { 
                        setText(it.amount.toString())
                        setSelection(text.length)
                    }
                }

                toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (isChecked) {
                        if (checkedId == btnModeInject.id) {
                            etAmount.setText("")
                            etAmount.hint = "Monto a inyectar (Ej: 500.00)"
                            tvExplanation.text = "Inyección de Capital Operativo: Ingresa el monto adicional que deseas sumar al capital actual en marcha."
                        } else {
                            currentBudget?.let { etAmount.setText(it.amount.toString()) }
                            etAmount.hint = "Nuevo capital total (Ej: 2000.00)"
                            tvExplanation.text = "Ingresa tu Capital Total de inversión. El sistema sumará las ganancias automáticamente."
                        }
                    }
                }

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
                
                if (currentBudget?.period == "WEEKLY") toggleGroup.check(btnWeekly.id)
                else toggleGroup.check(btnMonthly.id)

                val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                val savedIgnorePast = prefs.getBoolean("budget_ignore_past_loans", true)

                val cbResetPeriod = MaterialCheckBox(requireContext()).apply {
                    val initialAmt = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                    text = String.format(Locale.US, "Iniciar presupuesto desde hoy (S/ %.2f libres)", initialAmt)
                    isChecked = savedIgnorePast
                    setPadding(0, 20, 0, 10)
                }

                etAmount.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val currentAmt = s?.toString()?.toDoubleOrNull() ?: 0.0
                        cbResetPeriod.text = String.format(Locale.US, "Iniciar presupuesto desde hoy (S/ %.2f libres)", currentAmt)
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                layout.addView(tvCurrentInfo)
                layout.addView(TextView(requireContext()).apply { text = "Modo de Ajuste:"; setPadding(0,5,0,5); setTextColor(Color.BLACK); setTypeface(null, android.graphics.Typeface.BOLD) })
                layout.addView(toggleModeGroup)
                layout.addView(tvExplanation)
                layout.addView(TextView(requireContext()).apply { text = "Monto:"; setPadding(0,10,0,10); setTextColor(Color.BLACK) })
                layout.addView(etAmount)
                layout.addView(TextView(requireContext()).apply { text = "Periodo de Revisión:"; setPadding(0,30,0,10); setTextColor(Color.BLACK) })
                layout.addView(toggleGroup)
                layout.addView(cbResetPeriod)

                val builder = AlertDialog.Builder(requireContext())
                    .setTitle("⚙️ Ajuste de Capital Operativo")
                    .setView(layout)
                    .setPositiveButton("Guardar Cambios") { _, _ ->
                        val inputAmount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                        val isInject = toggleModeGroup.checkedButtonId == btnModeInject.id
                        val finalAmount = if (isInject && currentBudget != null) currentBudget.amount + inputAmount else inputAmount

                        val period = if (toggleGroup.checkedButtonId == btnWeekly.id) "WEEKLY" else "MONTHLY"
                        val resetStart = cbResetPeriod.isChecked
                        saveBudget(finalAmount, period, resetStart, isInject)
                    }
                    .setNegativeButton("Cancelar", null)

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
        val dbInstance = database ?: return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val currentBudget = dbInstance.loanDao().getLatestBudgetOnce()
            currentBudget?.let {
                val deletedBudget = it.copy(isDeleted = true, isSynced = false)
                dbInstance.loanDao().updateBudget(deletedBudget)
                SyncManager(ctx).scheduleOfflineSync()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Presupuesto eliminado 🗑️", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBudget(amount: Double, period: String, resetStart: Boolean, isInject: Boolean) {
        val dbInstance = database ?: return
        val ctx = context ?: return

        val prefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("budget_ignore_past_loans", resetStart).apply()

        if (!isInject) {
            prefs.edit().putFloat("base_budget_amount", amount.toFloat()).apply()
        } else {
            val currentBase = prefs.getFloat("base_budget_amount", 0f)
            if (currentBase == 0f) {
                prefs.edit().putFloat("base_budget_amount", (amount - 100f).toFloat()).apply()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val newStartDate = if (resetStart) System.currentTimeMillis() else 0L

            val budget = LoanBudgetEntity(
                id = "active_loan_budget", // ID FIJO para evitar duplicados y errores de sincronización
                amount = amount,
                period = period,
                startDate = newStartDate,
                timestamp = System.currentTimeMillis()
            )
            dbInstance.loanDao().insertBudget(budget)

            // REGISTRAR EN HISTORIAL DE MOVIMIENTOS
            val budgetLog = MovementLogEntity(
                type = "LOAN_BUDGET_UPDATED",
                title = "Presupuesto Actualizado",
                description = if (isInject) "Inyección al presupuesto de préstamos" else "Presupuesto de préstamos reemplazado",
                value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                colorHex = "#6366F1",
                iconRes = android.R.drawable.ic_menu_edit
            )
            dbInstance.movementLogDao().insert(budgetLog)
            SyncManager(ctx).syncLogToCloud(budgetLog)

            SyncManager(ctx).syncLoanBudgetToCloud(budget)
            
            // Sincronización inmediata
            SyncManager(ctx).scheduleOfflineSync()

            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "Presupuesto guardado ✨", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupToolbar() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.headerLoansHome, applyTop = true, applyBottom = false)

        binding.btnProfileLoans.setOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }

        // Cargar foto de perfil en el botón
        val user = FirebaseAuth.getInstance().currentUser
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val photoUrl = prefs.getString("user_photo_url", user?.photoUrl?.toString())
        
        if (!photoUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(photoUrl)
                .circleCrop()
                .placeholder(R.drawable.gomezmart_icon)
                .into(binding.ivProfileLoans)
        }

        // Nombre del usuario en la cabecera (centrado entre el avatar y la banderita)
        val userName = prefs.getString("user_full_name", null)
            ?: user?.displayName
            ?: "Usuario"
        binding.tvHeaderUserName.text = userName
    }

    private fun setupListeners() {
        binding.btnQuickNewLoan.setOnClickListener { showNewLoanOptions() }
        binding.btnQuickRegisterExpense.setOnClickListener { showQuickExpenseDialog() }

        // Tarjeta 1: Resumen de mi negocio (Desplegable independiente)
        binding.headerSummaryCard.setOnClickListener {
            val isVisible = binding.layoutSummaryContent.visibility == View.VISIBLE
            binding.layoutSummaryContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvToggleSummaryIcon.text = if (isVisible) "👁️ Ver" else "🙈 Ocultar"
        }

        binding.btnExplainSummary.setOnClickListener {
            showSummaryExplanationDialog()
        }

        // Tarjeta 2: Mi Presupuesto (Desplegable independiente)
        binding.headerBudgetCard.setOnClickListener {
            val isVisible = binding.layoutBudgetContent.visibility == View.VISIBLE
            binding.layoutBudgetContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvToggleBudgetIcon.text = if (isVisible) "👁️ Ver" else "🙈 Ocultar"
        }

        binding.btnConfigBudget.setOnClickListener { showBudgetConfigDialog() }
        
        binding.toolLoansClients.setOnClickListener { 
            (activity as? MainActivity)?.navigateToStock() // Pestaña de clientes
        }
        binding.cardTodayCollections.setOnClickListener {
            startActivity(Intent(requireContext(), com.gomezmart.app.LoansCollectionsActivity::class.java))
        }
        binding.toolLoansExpenses.setOnClickListener {
            startActivity(Intent(requireContext(), com.gomezmart.app.LoansExpensesActivity::class.java))
        }
        binding.toolLoansCommitments.setOnClickListener {
            startActivity(Intent(requireContext(), CommitmentsActivity::class.java))
        }
        binding.toolLoansAiChat.setOnClickListener {
            startActivity(Intent(requireContext(), AiChatActivity::class.java))
        }
        binding.toolLoansMailbox.setOnClickListener {
            startActivity(Intent(requireContext(), MailboxActivity::class.java))
        }
        binding.toolLoansMovements.setOnClickListener {
            startActivity(Intent(requireContext(), MovementsActivity::class.java))
        }
        binding.toolLoansAccounts.setOnClickListener {
            startActivity(Intent(requireContext(), AccountsActivity::class.java))
        }
        binding.btnSupportCreatorHome.setOnClickListener {
            startActivity(Intent(requireContext(), com.gomezmart.app.SupportCreatorActivity::class.java))
        }
        binding.btnReportErrorLoans.setOnClickListener {
            val baseActivity = activity as? BaseActivity
            baseActivity?.showReportErrorDialog(requireActivity())
        }
    }

    private fun showNewLoanOptions() {
        val options = arrayOf("👤 Cliente existente", "🆕 Nuevo cliente")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Registrar Préstamo")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val intent = Intent(requireContext(), com.gomezmart.app.LoansClientsActivity::class.java)
                    intent.putExtra("PICK_MODE", true)
                    startActivity(intent)
                } else {
                    startActivity(Intent(requireContext(), AddLoanActivity::class.java))
                }
            }
            .show()
    }

    private fun showQuickExpenseDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_loan_expense, null)
        val autoCategory = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteCategory)
        val etConcept = dialogView.findViewById<EditText>(R.id.etExpenseConcept)
        val etAmount = dialogView.findViewById<EditText>(R.id.etExpenseAmount)
        val autoAccount = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteExpenseAccount)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelExpense)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveExpense)

        // --- CUENTA DE ORIGEN DEL GASTO (conecta con "Mis Cuentas") ---
        var accountList = emptyList<com.gomezmart.app.data.AccountEntity>()
        var selectedAccountId = ""
        var selectedAccountName = ""
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                accountList = com.gomezmart.app.data.AppDatabase.getDatabase(requireContext())
                    .accountDao().getAllAccountsList()
                if (accountList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val names = accountList.map { "${it.name} (${it.bankName}) - S/ ${String.format(Locale.US, "%.2f", it.balance)}" }
                        autoAccount.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names))
                        autoAccount.setText(names[0], false)
                        selectedAccountId = accountList[0].id
                        selectedAccountName = accountList[0].name
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        autoAccount.hint = "Cuentas (registra una en Ajustes > Cuentas)"
                    }
                }
            } catch (e: Exception) { }
        }
        autoAccount.setOnItemClickListener { _, _, position, _ ->
            if (position in accountList.indices) {
                selectedAccountId = accountList[position].id
                selectedAccountName = accountList[position].name
            }
        }

        // --- SELECCIÓN DE TIPO DE GASTO ---
        val layoutType = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 32
            }
        }
        
        val tvType = TextView(requireContext()).apply {
            text = "Tipo de Gasto:"
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        
        val toggleGroup = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
        }
        
        val btnBusiness = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = "Del Negocio"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val btnPersonal = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = "Personal"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        toggleGroup.addView(btnBusiness)
        toggleGroup.addView(btnPersonal)
        toggleGroup.check(btnBusiness.id)
        
        layoutType.addView(tvType)
        layoutType.addView(toggleGroup)
        
        // Insertar antes de los botones de acción (dentro del bloque desplazable)
        val fieldsLayout = dialogView.findViewById<LinearLayout>(R.id.layoutExpenseFields)
        fieldsLayout?.addView(layoutType, fieldsLayout.childCount)

        // Cargar categorías existentes para el autocompletado
        val dbInstance = database ?: return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val categories = dbInstance.loanDao().getAllLoanExpensesOnce()
                .map { it.category }
                .distinct()
                .filter { it.isNotEmpty() }
            
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, categories)
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
            val isPersonal = toggleGroup.checkedButtonId == btnPersonal.id

            if (category.isEmpty()) {
                autoCategory.error = "Ingresa una categoría"
                return@setOnClickListener
            }
            if (amount <= 0) {
                etAmount.error = "Ingresa un monto válido"
                return@setOnClickListener
            }

            saveQuickExpense(category, concept, amount, isPersonal, selectedAccountId, selectedAccountName)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveQuickExpense(category: String, concept: String, amount: Double, isPersonal: Boolean = false, accountId: String = "", accountName: String = "") {
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val expense = com.gomezmart.app.data.LoanExpenseEntity(
                concept = concept.ifEmpty { "Gasto rápido" },
                amount = amount,
                category = category,
                timestamp = System.currentTimeMillis(),
                isPersonal = isPersonal,
                accountId = accountId,
                accountName = accountName
            )
            dbInstance.loanDao().insertExpense(expense)

            // Descontar el monto de la cuenta de origen seleccionada
            if (accountId.isNotEmpty()) {
                try {
                    val acc = dbInstance.accountDao().getAllAccountsList().find { it.id == accountId }
                    acc?.let {
                        it.balance -= amount
                        it.isSynced = false
                        dbInstance.accountDao().update(it)
                    }
                } catch (e: Exception) { }
            }

            // REGISTRAR EN HISTORIAL DE MOVIMIENTOS
            val expenseLog = MovementLogEntity(
                type = "EXPENSE_QUICK",
                title = "Gasto Registrado",
                description = "$category: ${expense.concept}",
                value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                colorHex = "#DC2626",
                iconRes = android.R.drawable.ic_menu_send
            )
            dbInstance.movementLogDao().insert(expenseLog)
            SyncManager(requireContext()).syncLogToCloud(expenseLog)

            SyncManager(requireContext()).syncLoanExpenseToCloud(expense)
            SyncManager(requireContext()).scheduleOfflineSync()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Gasto registrado ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSummaryExplanationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Explicación del Resumen ℹ️")
            .setMessage(
                "• 💰 Dinero prestado:\n" +
                "Es el capital total original que entregaste a tus clientes en tus préstamos activos.\n\n" +
                "• 💸 Saldo por cobrar:\n" +
                "Es la cantidad total de dinero que te falta recibir hoy (capital restante + intereses acumulados).\n\n" +
                "• 📊 Ganancia Estimada:\n" +
                "Es el dinero en intereses que vas a ganar una vez que cobres el 100% de tus préstamos activos.\n\n" +
                "• 📈 Ganancia Obtenida:\n" +
                "Es la ganancia neta real (intereses cobrados + moras - gastos del negocio) que ya ha ingresado efectivamente a tu bolsillo."
            )
            .setPositiveButton("Entendido 👍", null)
            .show()
    }

    private fun loadDashboardData() {
        val ctx = context ?: return
        val dbInstance = database ?: return
        val prefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")

        viewLifecycleOwner.lifecycleScope.launch {
            val totalExpensesFlow = dbInstance.loanDao().getAllExpenses()
            val totalLateFeesFlow = dbInstance.loanDao().getTotalLateFeesFlow()
            val allInstallmentsFlow = dbInstance.loanDao().getAllInstallmentsFlow()
            val allLoansFlow = dbInstance.loanDao().getAllLoans()

            combine(allInstallmentsFlow, allLoansFlow, totalExpensesFlow, totalLateFeesFlow) { insts, loans, expenses, lateFees ->
                val activeLoans = loans.filter { !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }

                val rawBizCap = activeLoans.filter { !it.isPersonal }.sumOf { it.amount }
                val baseCapital = prefs.getFloat("base_budget_amount", 500f).toDouble()
                val capitalLentBusiness = if (rawBizCap >= baseCapital * 0.90 && rawBizCap <= baseCapital * 1.30) baseCapital else rawBizCap
                val capitalLentPersonal = activeLoans.filter { it.isPersonal }.sumOf { it.amount }
                val capitalLentTotal = capitalLentBusiness + capitalLentPersonal
                var outstandingBalance = 0.0
                var estimatedProfit = 0.0
                var interestCollected = 0.0

                activeLoans.forEach { loan ->
                    val loanInsts = insts.filter { it.loanId == loan.id && !it.isDeleted }
                    val totalPaid = loanInsts.sumOf { it.amountPaid }
                    val totalToPay = loan.totalToPay

                    val interestRatio = if (totalToPay > 0.0) (totalToPay - loan.amount) / totalToPay else 0.0
                    val capitalRatio = 1.0 - interestRatio

                    val capitalPaid = totalPaid * capitalRatio
                    val interestPaid = totalPaid * interestRatio

                    val remCapital = (loan.amount - capitalPaid).coerceAtLeast(0.0)
                    val remInterest = ((totalToPay - loan.amount) - interestPaid).coerceAtLeast(0.0)

                    estimatedProfit += remInterest
                    outstandingBalance += (remCapital + remInterest)
                    interestCollected += interestPaid
                }

                val businessExpenses = expenses?.filter { !it.isPersonal }?.sumOf { it.amount } ?: 0.0
                val netProfit = interestCollected + (lateFees ?: 0.0) - businessExpenses

                doubleArrayOf(capitalLentTotal, outstandingBalance, estimatedProfit, netProfit, capitalLentBusiness, capitalLentPersonal)
            }.collectLatest { stats ->
                if (_binding == null) return@collectLatest

                val cap = stats[0]
                val bal = stats[1]
                val est = stats[2]
                val net = stats[3]
                val bizCap = stats[4]
                val persCap = stats[5]

                binding.tvStatCapitalLent.text = String.format(Locale.US, "$currency %.2f", cap)
                binding.tvStatCapitalLentBusiness.text = String.format(Locale.US, "$currency %.2f", bizCap)
                binding.tvStatCapitalLentPersonal.text = String.format(Locale.US, "$currency %.2f", persCap)
                binding.tvStatOutstandingBalance.text = String.format(Locale.US, "$currency %.2f", bal)
                binding.tvStatEstimatedProfit.text = String.format(Locale.US, "$currency %.2f", est)
                binding.tvStatNetProfit.text = String.format(Locale.US, "$currency %.2f", net)

                if (net < 0) {
                    binding.tvStatNetProfit.setTextColor(Color.parseColor("#DC2626"))
                } else {
                    binding.tvStatNetProfit.setTextColor(Color.parseColor("#059669"))
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
        _binding = null
    }
}
