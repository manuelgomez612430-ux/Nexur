package com.gomezmart.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanExpenseEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.ActivityLoansExpensesBinding
import com.gomezmart.app.databinding.ItemLoanExpenseBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LoansExpensesActivity : BaseActivity() {

    private lateinit var binding: ActivityLoansExpensesBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ExpensesAdapter
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansExpensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdaptiveInsets()

        binding.toolbarLoansExpenses.setNavigationOnClickListener { finish() }
        
        setupSyncIndicator()
        setupRecyclerView()
        setupListeners()
        loadExpenses()
    }

    /**
     * Ajusta la interfaz a cualquier tipo de pantalla (grande, angosta, alta, etc.)
     * evitando que las barras del sistema de Android (notificaciones y navegación)
     * tapen el contenido o los botones.
     */
    private fun setupAdaptiveInsets() {
        // 1) La cabecera se desplaza hacia abajo para no quedar bajo la barra de notificaciones
        applyWindowInsetsMargins(binding.appBarLoansExpenses, applyTop = true, applyBottom = false)

        // 2) El botón flotante sube para no quedar tapado por la barra de navegación de Android
        applyWindowInsetsMargins(binding.fabAddLoanExpense, applyTop = false, applyBottom = true)

        // 3) El contenido scrolleable recibe espacio extra abajo (nav bars + altura del FAB)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.scrollLoansExpenses) { v, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val extra = (if (imeBars > 0) imeBars else navBars) + v.paddingTop
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, extra)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.scrollLoansExpenses)
    }

    private fun setupSyncIndicator() {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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
            database.loanDao().getUnsyncedCount().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sincronización de Cartera")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus")
                .setPositiveButton("Sincronizar ahora") { _, _ -> 
                    SyncManager(this).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
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

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun setupRecyclerView() {
        adapter = ExpensesAdapter()
        binding.rvLoansExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvLoansExpenses.adapter = adapter
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupListeners() {
        binding.fabAddLoanExpense.setOnClickListener {
            showAddExpenseDialog()
        }
    }

    private fun loadExpenses() {
        lifecycleScope.launch {
            database.loanDao().getAllExpenses().collectLatest { expenses ->
                adapter.submitList(expenses)
                val total = expenses.sumOf { it.amount }
                binding.tvTotalLoansExpenses.text = String.format(Locale.US, "S/ %.2f", total)
                
                // Actualizar lista de categorías únicas para el próximo diálogo
                val uniqueCategories = expenses.map { it.category }.distinct().sorted()
                currentCategories = uniqueCategories
            }
        }
    }

    private var currentCategories: List<String> = emptyList()

    private fun showAddExpenseDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_loan_expense, null)
        val autoCategory = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteCategory)
        val etConcept = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etExpenseConcept)
        val etAmount = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etExpenseAmount)
        val autoAccount = dialogView.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.autoCompleteExpenseAccount)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelExpense)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveExpense)

        // Configurar Autocompletado con las categorías que el usuario ya creó
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentCategories)
        autoCategory.setAdapter(adapter)

        var accountList = emptyList<com.gomezmart.app.data.AccountEntity>()
        var selectedAccountId = ""
        var selectedAccountName = ""

        lifecycleScope.launch {
            accountList = database.accountDao().getAllAccountsList()
            val accountNames = accountList.map { "${it.name} (${it.bankName}) - S/ ${String.format(Locale.US, "%.2f", it.balance)}" }
            val accAdapter = android.widget.ArrayAdapter(this@LoansExpensesActivity, android.R.layout.simple_dropdown_item_1line, accountNames)
            autoAccount.setAdapter(accAdapter)
            if (accountList.isNotEmpty()) {
                autoAccount.setText(accountNames[0], false)
                selectedAccountId = accountList[0].id
                selectedAccountName = accountList[0].name
            }
        }

        autoAccount.setOnItemClickListener { _, _, position, _ ->
            if (position in accountList.indices) {
                selectedAccountId = accountList[position].id
                selectedAccountName = accountList[position].name
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val category = autoCategory.text.toString().trim()
            val concept = etConcept.text.toString().trim()
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0

            if (category.isNotEmpty() && amount > 0) {
                hideKeyboard()
                saveExpense(concept, category, amount, selectedAccountId, selectedAccountName)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "⚠️ Ingrese al menos la categoría y el monto", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun saveExpense(concept: String, category: String, amount: Double, accountId: String, accountName: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            database.loanDao().insertExpense(LoanExpenseEntity(
                concept = concept, 
                category = category, 
                amount = amount,
                accountId = accountId,
                accountName = accountName
            ))

            if (accountId.isNotEmpty()) {
                val acc = database.accountDao().getAllAccountsList().find { it.id == accountId }
                acc?.let {
                    it.balance -= amount
                    it.isSynced = false
                    database.accountDao().update(it)
                }
            }

            // REGISTRAR EN HISTORIAL DE MOVIMIENTOS
            val expenseLog = MovementLogEntity(
                type = "EXPENSE_LOAN",
                title = "Gasto Registrado",
                description = "$category: $concept ${if (accountName.isNotEmpty()) "(Cuenta: $accountName)" else ""}",
                value = "S/ ${String.format(Locale.US, "%.2f", amount)}",
                colorHex = "#DC2626",
                iconRes = android.R.drawable.ic_menu_send
            )
            database.movementLogDao().insert(expenseLog)
            SyncManager(this@LoansExpensesActivity).syncLogToCloud(expenseLog)
            SyncManager(this@LoansExpensesActivity).scheduleOfflineSync()

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(this@LoansExpensesActivity, "✅ Gasto registrado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus
        if (view != null) {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    inner class ExpensesAdapter : RecyclerView.Adapter<ExpensesAdapter.ViewHolder>() {
        private var items = emptyList<LoanExpenseEntity>()
        fun submitList(newItems: List<LoanExpenseEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanExpenseBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            with(holder.b) {
                // Si hay concepto, mostramos Categoría pequeña arriba y Concepto grande.
                // Si NO hay concepto, mostramos solo Categoría en grande.
                if (item.concept.isNullOrEmpty()) {
                    tvLoanExpenseCategory.visibility = android.view.View.GONE
                    tvLoanExpenseConcept.text = item.category
                } else {
                    tvLoanExpenseCategory.visibility = android.view.View.VISIBLE
                    tvLoanExpenseCategory.text = item.category.uppercase()
                    tvLoanExpenseConcept.text = item.concept
                }
                
                tvLoanExpenseDate.text = sdf.format(Date(item.timestamp))
                tvLoanExpenseAmount.text = "- S/ ${String.format(Locale.US, "%.2f", item.amount)}"
            }
        }
        override fun getItemCount() = items.size
    }
}
