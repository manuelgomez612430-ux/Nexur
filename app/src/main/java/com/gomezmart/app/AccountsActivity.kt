package com.gomezmart.app

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.gomezmart.app.data.AccountEntity
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanBudgetEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.ActivityAccountsBinding
import com.gomezmart.app.databinding.ItemAccountBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AccountsActivity : BaseActivity() {

    private lateinit var binding: ActivityAccountsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: AccountsAdapter
    private var currentAccounts: List<AccountEntity> = emptyList()
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.appBarAccounts, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.fabAddAccount, applyTop = false, applyBottom = true)

        binding.toolbarAccounts.setNavigationOnClickListener { finish() }

        setupSyncIndicator()
        setupRecyclerView()
        setupQuickActions()
        loadAccounts()

        binding.fabAddAccount.setOnClickListener { showAddEditAccountDialog(null) }
    }

    private fun setupQuickActions() {
        binding.btnTransferBetweenAccounts.setOnClickListener {
            showTransferDialog(null)
        }
        binding.btnQuickCreateCash.setOnClickListener {
            showAddEditAccountDialog(null, presetBank = "Efectivo", presetName = "Caja Efectivo")
        }
        binding.btnQuickCreateYape.setOnClickListener {
            showAddEditAccountDialog(null, presetBank = "Yape", presetName = "Yape Negocio")
        }
        binding.btnQuickCreateBank.setOnClickListener {
            showAddEditAccountDialog(null, presetBank = "BCP", presetName = "Cuenta BCP")
        }
    }

    private fun setupSyncIndicator() {
        if (isGuestUser()) {
            binding.btnSyncIndicatorAccounts.visibility = View.GONE
            return
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                runOnUiThread { updateSyncIconState() }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback)

        lifecycleScope.launch {
            database.accountDao().getUnsyncedCountFlow().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicatorAccounts.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            AlertDialog.Builder(this)
                .setTitle("Sincronización de Cuentas")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus")
                .setPositiveButton("Sincronizar ahora") { _, _ ->
                    SyncManager(this).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
        if (isGuestUser()) {
            binding.btnSyncIndicatorAccounts.visibility = View.GONE
            return
        }
        val color: Int
        val iconRes: Int
        when {
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión"
                color = android.graphics.Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6")
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }
        binding.btnSyncIndicatorAccounts.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun setupRecyclerView() {
        adapter = AccountsAdapter(
            onClick = { account -> showAccountActionsDialog(account) },
            onLongClick = { account -> showDeleteConfirmation(account) }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter
    }

    private fun loadAccounts() {
        lifecycleScope.launch {
            database.accountDao().getAllAccounts().collectLatest { accounts ->
                currentAccounts = accounts
                adapter.submitList(accounts)

                val total = accounts.sumOf { it.balance }
                val businessTotal = accounts.filter { it.origin != "PERSONAL" }.sumOf { it.balance }
                val personalTotal = accounts.filter { it.origin == "PERSONAL" }.sumOf { it.balance }

                binding.tvTotalAccountsBalance.text = String.format(Locale.US, "S/ %.2f", total)
                binding.tvBusinessAccountsBalance.text = String.format(Locale.US, "S/ %.2f", businessTotal)
                binding.tvPersonalAccountsBalance.text = String.format(Locale.US, "S/ %.2f", personalTotal)

                val isEmpty = accounts.isEmpty()
                binding.layoutEmptyAccounts.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvAccounts.visibility = if (isEmpty) View.GONE else View.VISIBLE
                binding.tvAccountsHelperHint.visibility = if (isEmpty) View.GONE else View.VISIBLE
                binding.btnTransferBetweenAccounts.visibility = if (accounts.size >= 2) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Sincroniza el presupuesto operativo (`LoanBudgetEntity`) sumando la liquidez
     * actual en cuentas del negocio más el capital del negocio actualmente prestado en la calle,
     * evitando que el capital ya prestado se descuente dos veces en las métricas.
     */
    private suspend fun syncBusinessBudgetWithAccounts() = withContext(Dispatchers.IO) {
        val allAccounts = database.accountDao().getAllAccountsList().filter { !it.isDeleted }
        val businessAccounts = allAccounts.filter { it.origin != "PERSONAL" }
        val businessCashAvailable = businessAccounts.sumOf { it.balance }

        val fundingMap = database.loanDao().getAllFundingSources().associateBy { it.loanId }
        val activeLoans = database.loanDao().getAllLoansOnce().filter {
            !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE")
        }
        val allInstallments = database.loanDao().getAllInstallmentsOnce()

        var bizCapitalInStreet = 0.0
        activeLoans.forEach { loan ->
            val isPersonal = fundingMap[loan.id]?.isPersonal ?: loan.isPersonal
            if (!isPersonal) {
                val loanInsts = allInstallments.filter { it.loanId == loan.id && !it.isDeleted }
                val totalPaid = loanInsts.sumOf { it.amountPaid }
                val interestRatio = if (loan.totalToPay > 0.0) (loan.totalToPay - loan.amount) / loan.totalToPay else 0.0
                val capitalRatio = 1.0 - interestRatio
                val capitalPaid = totalPaid * capitalRatio
                val remCapital = (loan.amount - capitalPaid).coerceAtLeast(0.0)
                bizCapitalInStreet += remCapital
            }
        }

        val workingCapital = (businessCashAvailable + bizCapitalInStreet).coerceAtLeast(0.0)
        val newBudget = LoanBudgetEntity(
            id = UUID.randomUUID().toString(),
            amount = workingCapital,
            period = "MONTHLY",
            includedAccountIds = businessAccounts.joinToString(",") { it.id },
            timestamp = System.currentTimeMillis()
        )
        database.loanDao().insertBudget(newBudget)
    }

    private fun showAccountActionsDialog(account: AccountEntity) {
        val originLabel = if (account.origin == "PERSONAL") "👤 Personal" else "💼 Negocio"
        val balanceStr = String.format(Locale.US, "S/ %.2f", account.balance)

        val options = arrayOf(
            "📋 Ver Movimientos de esta Cuenta",
            "➕ Ingresar Dinero (Inyección)",
            "➖ Retirar Dinero",
            "🔄 Transferir a otra Cuenta",
            "✏️ Editar Cuenta",
            "🗑️ Eliminar Cuenta"
        )

        AlertDialog.Builder(this)
            .setTitle("${account.name} • $originLabel ($balanceStr)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAccountMovementsDialog(account)
                    1 -> showAdjustBalanceDialog(account, isDeposit = true)
                    2 -> showAdjustBalanceDialog(account, isDeposit = false)
                    3 -> showTransferDialog(account)
                    4 -> showAddEditAccountDialog(account)
                    5 -> showDeleteConfirmation(account)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAccountMovementsDialog(account: AccountEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val allPayments = database.loanDao().getAllPaymentsOnce().filter {
                !it.isDeleted && (it.accountId == account.id || (it.accountId.isEmpty() && it.accountName.equals(account.name, ignoreCase = true)))
            }
            val allExpenses = database.loanDao().getAllLoanExpensesOnce().filter {
                !it.isDeleted && (it.accountId == account.id || (it.accountId.isEmpty() && it.accountName.equals(account.name, ignoreCase = true)))
            }
            val clientsMap = database.loanDao().getAllClientsOnce().associateBy { it.id }
            val loansMap = database.loanDao().getAllLoansOnce().associateBy { it.id }

            data class AccountMovRow(val timestamp: Long, val title: String, val amountStr: String)
            val rows = mutableListOf<AccountMovRow>()

            allPayments.forEach { p ->
                val loan = loansMap[p.loanId]
                val clientName = loan?.let { clientsMap[it.clientId]?.name } ?: "Cliente"
                val total = p.amount + p.lateFeeAmount
                if (p.type == 1) {
                    rows.add(AccountMovRow(p.timestamp, "📤 Aumento de préstamo a $clientName", "- S/ ${String.format(Locale.US, "%.2f", total)}"))
                } else {
                    rows.add(AccountMovRow(p.timestamp, "📥 Cobro recibido de $clientName", "+ S/ ${String.format(Locale.US, "%.2f", total)}"))
                }
            }

            allExpenses.forEach { e ->
                rows.add(AccountMovRow(e.timestamp, "🧾 Gasto: ${e.concept} (${e.category})", "- S/ ${String.format(Locale.US, "%.2f", e.amount)}"))
            }

            val sorted = rows.sortedByDescending { it.timestamp }
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            withContext(Dispatchers.Main) {
                val msg = if (sorted.isEmpty()) {
                    "Aún no hay cobros ni gastos vinculados a '${account.name}'.\n\nCuando registres un préstamo, cobro o gasto eligiendo esta cuenta, aparecerán aquí."
                } else {
                    sorted.take(25).joinToString("\n\n") { r ->
                        "${r.title}\n${sdf.format(Date(r.timestamp))}   •   ${r.amountStr}"
                    }
                }

                AlertDialog.Builder(this@AccountsActivity)
                    .setTitle("📋 Movimientos: ${account.name}")
                    .setMessage(msg)
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun showAdjustBalanceDialog(account: AccountEntity, isDeposit: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val etAmount = EditText(this).apply {
            hint = "Monto en S/ (Ej: 100.00)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etNote = EditText(this).apply {
            hint = if (isDeposit) "Motivo (Ej: Aporte de capital)" else "Motivo (Ej: Retiro a efectivo)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        container.addView(etAmount)
        container.addView(etNote)

        val title = if (isDeposit) "➕ Ingresar Dinero a ${account.name}" else "➖ Retirar Dinero de ${account.name}"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Saldo actual: S/ ${String.format(Locale.US, "%.2f", account.balance)}")
            .setView(container)
            .setPositiveButton(if (isDeposit) "Ingresar" else "Retirar") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                val note = etNote.text.toString().trim()
                if (amount <= 0.0) {
                    Toast.makeText(this, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!isDeposit && amount > account.balance) {
                    Toast.makeText(this, "El monto supera el saldo disponible en esta cuenta", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val newBalance = if (isDeposit) account.balance + amount else (account.balance - amount).coerceAtLeast(0.0)
                    val updated = account.copy(
                        balance = newBalance,
                        updatedAt = System.currentTimeMillis(),
                        isSynced = false
                    )
                    database.accountDao().update(updated)
                    syncBusinessBudgetWithAccounts()

                    val log = MovementLogEntity(
                        type = if (isDeposit) "ACCOUNT_DEPOSIT" else "ACCOUNT_WITHDRAW",
                        title = if (isDeposit) "Ingreso a ${account.name}" else "Retiro de ${account.name}",
                        description = note.ifEmpty { if (isDeposit) "Inyección manual de saldo" else "Retiro manual de saldo" },
                        value = "${if (isDeposit) "+" else "-"} S/ ${String.format(Locale.US, "%.2f", amount)}",
                        colorHex = if (isDeposit) "#059669" else "#DC2626",
                        iconRes = android.R.drawable.ic_menu_agenda
                    )
                    database.movementLogDao().insert(log)
                    SyncManager(this@AccountsActivity).syncLogToCloud(log)
                    SyncManager(this@AccountsActivity).scheduleOfflineSync()

                    Toast.makeText(this@AccountsActivity, "Saldo actualizado: S/ ${String.format(Locale.US, "%.2f", newBalance)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTransferDialog(preselectedFrom: AccountEntity?) {
        if (currentAccounts.size < 2) {
            Toast.makeText(this, "Necesitas al menos 2 cuentas registradas para transferir entre ellas", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val accountLabels = currentAccounts.map { "${it.name} (S/ ${String.format(Locale.US, "%.2f", it.balance)})" }

        val tvFrom = TextView(this).apply {
            text = "Cuenta de Origen (de donde sale):"
            textSize = 12f
        }
        val spinnerFrom = Spinner(this).apply {
            adapter = ArrayAdapter(this@AccountsActivity, android.R.layout.simple_spinner_dropdown_item, accountLabels)
        }
        if (preselectedFrom != null) {
            val idx = currentAccounts.indexOfFirst { it.id == preselectedFrom.id }
            if (idx >= 0) spinnerFrom.setSelection(idx)
        }

        val tvTo = TextView(this).apply {
            text = "Cuenta de Destino (a donde entra):"
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        val spinnerTo = Spinner(this).apply {
            adapter = ArrayAdapter(this@AccountsActivity, android.R.layout.simple_spinner_dropdown_item, accountLabels)
            val defaultToIdx = if (spinnerFrom.selectedItemPosition == 0 && currentAccounts.size > 1) 1 else 0
            setSelection(defaultToIdx)
        }

        val etAmount = EditText(this).apply {
            hint = "Monto a transferir (S/)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        container.addView(tvFrom)
        container.addView(spinnerFrom)
        container.addView(tvTo)
        container.addView(spinnerTo)
        container.addView(etAmount)

        AlertDialog.Builder(this)
            .setTitle("🔄 Transferir entre Cuentas")
            .setView(container)
            .setPositiveButton("Transferir") { _, _ ->
                val fromIdx = spinnerFrom.selectedItemPosition
                val toIdx = spinnerTo.selectedItemPosition
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0

                if (fromIdx !in currentAccounts.indices || toIdx !in currentAccounts.indices) return@setPositiveButton
                if (fromIdx == toIdx) {
                    Toast.makeText(this, "Elige dos cuentas diferentes", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (amount <= 0.0) {
                    Toast.makeText(this, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val fromAcc = currentAccounts[fromIdx]
                val toAcc = currentAccounts[toIdx]

                if (amount > fromAcc.balance) {
                    Toast.makeText(this, "Saldo insuficiente en ${fromAcc.name}", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    val now = System.currentTimeMillis()
                    database.accountDao().update(fromAcc.copy(balance = fromAcc.balance - amount, updatedAt = now, isSynced = false))
                    database.accountDao().update(toAcc.copy(balance = toAcc.balance + amount, updatedAt = now, isSynced = false))
                    syncBusinessBudgetWithAccounts()

                    val log = MovementLogEntity(
                        type = "ACCOUNT_TRANSFER",
                        title = "Transferencia entre Cuentas",
                        description = "${fromAcc.name} ➔ ${toAcc.name}",
                        value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                        colorHex = "#6366F1",
                        iconRes = android.R.drawable.ic_popup_sync
                    )
                    database.movementLogDao().insert(log)
                    SyncManager(this@AccountsActivity).syncLogToCloud(log)
                    SyncManager(this@AccountsActivity).scheduleOfflineSync()

                    Toast.makeText(this@AccountsActivity, "Transferencia realizada: ${fromAcc.name} ➔ ${toAcc.name} ✅", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddEditAccountDialog(
        accountToEdit: AccountEntity?,
        presetBank: String? = null,
        presetName: String? = null
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_account, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvAccountDialogTitle)
        val toggleOrigin = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleAccountOrigin)
        val etName = dialogView.findViewById<EditText>(R.id.etAccountName)
        val etBank = dialogView.findViewById<EditText>(R.id.etAccountBank)
        val etNumber = dialogView.findViewById<EditText>(R.id.etAccountNumber)
        val etBalance = dialogView.findViewById<EditText>(R.id.etAccountBalance)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAccount)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveAccount)

        val btnPresetCash = dialogView.findViewById<MaterialButton>(R.id.btnPresetCash)
        val btnPresetYape = dialogView.findViewById<MaterialButton>(R.id.btnPresetYape)
        val btnPresetPlin = dialogView.findViewById<MaterialButton>(R.id.btnPresetPlin)
        val btnPresetBcp = dialogView.findViewById<MaterialButton>(R.id.btnPresetBcp)
        val btnPresetBbva = dialogView.findViewById<MaterialButton>(R.id.btnPresetBbva)
        val btnPresetInterbank = dialogView.findViewById<MaterialButton>(R.id.btnPresetInterbank)

        fun applyPreset(bank: String, baseName: String) {
            val isPersonal = toggleOrigin?.checkedButtonId == R.id.btnOriginPersonal
            val suffix = if (isPersonal) "Personal" else "Negocio"
            etBank.setText(bank)
            if (etName.text.isNullOrBlank() || etName.text.toString().endsWith("Negocio") || etName.text.toString().endsWith("Personal") || etName.text.toString().startsWith("Caja")) {
                etName.setText(if (bank == "Efectivo") "Caja Efectivo ($suffix)" else "$baseName $suffix")
            }
            etBalance.requestFocus()
        }

        btnPresetCash?.setOnClickListener { applyPreset("Efectivo", "Efectivo") }
        btnPresetYape?.setOnClickListener { applyPreset("Yape", "Yape") }
        btnPresetPlin?.setOnClickListener { applyPreset("Plin", "Plin") }
        btnPresetBcp?.setOnClickListener { applyPreset("BCP", "Cuenta BCP") }
        btnPresetBbva?.setOnClickListener { applyPreset("BBVA", "Cuenta BBVA") }
        btnPresetInterbank?.setOnClickListener { applyPreset("Interbank", "Cuenta Interbank") }

        if (accountToEdit != null) {
            tvTitle.text = "Editar Cuenta 💳"
            if (accountToEdit.origin == "PERSONAL") {
                toggleOrigin?.check(R.id.btnOriginPersonal)
            } else {
                toggleOrigin?.check(R.id.btnOriginBusiness)
            }
            etName.setText(accountToEdit.name)
            etBank.setText(accountToEdit.bankName)
            etNumber.setText(accountToEdit.accountNumber)
            etBalance.setText(String.format(Locale.US, "%.2f", accountToEdit.balance))
        } else if (presetBank != null) {
            etBank.setText(presetBank)
            etName.setText(presetName ?: presetBank)
            etBalance.requestFocus()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val bank = etBank.text.toString().trim()
            val number = etNumber.text.toString().trim()
            val balance = etBalance.text.toString().toDoubleOrNull() ?: 0.0
            val selectedOrigin = if (toggleOrigin?.checkedButtonId == R.id.btnOriginPersonal) "PERSONAL" else "BUSINESS"

            if (name.isEmpty()) {
                etName.error = "Ingrese un nombre"
                return@setOnClickListener
            }

            val resolvedBank = bank.ifEmpty { "General" }
            val resolvedType = when (resolvedBank.uppercase(Locale.ROOT)) {
                "YAPE", "PLIN" -> "WALLET"
                "EFECTIVO", "CAJA", "BOLSILLO" -> "CASH"
                else -> "BANK"
            }

            lifecycleScope.launch {
                val account = accountToEdit?.copy(
                    name = name,
                    bankName = resolvedBank,
                    accountNumber = number,
                    balance = balance,
                    accountType = resolvedType,
                    origin = selectedOrigin,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                ) ?: AccountEntity(
                    name = name,
                    bankName = resolvedBank,
                    accountNumber = number,
                    balance = balance,
                    accountType = resolvedType,
                    origin = selectedOrigin,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )

                database.accountDao().insert(account)
                syncBusinessBudgetWithAccounts()

                SyncManager(this@AccountsActivity).scheduleOfflineSync()
                Toast.makeText(this@AccountsActivity, "Cuenta guardada correctamente ✅", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmation(account: AccountEntity) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ ¿Eliminar Cuenta?")
            .setMessage("¿Deseas eliminar '${account.name}' de tus cuentas registradas?")
            .setPositiveButton("Sí, Eliminar") { _, _ ->
                lifecycleScope.launch {
                    val deleted = account.copy(isDeleted = true, isSynced = false)
                    database.accountDao().update(deleted)
                    syncBusinessBudgetWithAccounts()

                    SyncManager(this@AccountsActivity).scheduleOfflineSync()
                    Toast.makeText(this@AccountsActivity, "Cuenta eliminada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class AccountsAdapter(
        private val onClick: (AccountEntity) -> Unit,
        private val onLongClick: (AccountEntity) -> Unit
    ) : RecyclerView.Adapter<AccountsAdapter.ViewHolder>() {
        private var items = emptyList<AccountEntity>()
        fun submitList(newItems: List<AccountEntity>) { items = newItems; notifyDataSetChanged() }

        inner class ViewHolder(val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvAccountName.text = item.name
                tvAccountBank.text = if (item.accountNumber.isNotEmpty()) "${item.bankName} • ${item.accountNumber}" else item.bankName
                tvAccountBalance.text = "S/ ${String.format(Locale.US, "%.2f", item.balance)}"

                val bankUpper = item.bankName.uppercase(Locale.ROOT)
                tvAccountType.text = when {
                    bankUpper.contains("YAPE") || bankUpper.contains("PLIN") || item.accountType == "WALLET" -> "Billetera digital"
                    bankUpper.contains("EFECTIVO") || bankUpper.contains("CAJA") || item.accountType == "CASH" -> "Efectivo en mano"
                    else -> "Cuenta bancaria"
                }

                if (item.origin == "PERSONAL") {
                    tvAccountOrigin.text = "👤 Personal"
                    tvAccountOrigin.setBackgroundResource(R.drawable.bg_pill_purple_light)
                    tvAccountOrigin.setTextColor(getColor(R.color.purple_600))
                } else {
                    tvAccountOrigin.text = "💼 Caja Negocio"
                    tvAccountOrigin.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                    tvAccountOrigin.setTextColor(getColor(R.color.emerald_600))
                }

                val emoji = when {
                    bankUpper.contains("YAPE") -> "📱"
                    bankUpper.contains("PLIN") -> "⚡"
                    bankUpper.contains("BCP") -> "🟦"
                    bankUpper.contains("BBVA") -> "🔷"
                    bankUpper.contains("INTERBANK") -> "🟩"
                    bankUpper.contains("EFECTIVO") || bankUpper.contains("BOLSILLO") || bankUpper.contains("CAJA") -> "💵"
                    else -> "💳"
                }
                tvAccountIcon.text = emoji

                root.setOnClickListener { onClick(item) }
                root.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
