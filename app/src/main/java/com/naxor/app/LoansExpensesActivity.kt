package com.naxor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanExpenseEntity
import com.naxor.app.databinding.ActivityLoansExpensesBinding
import com.naxor.app.databinding.ItemLoanExpenseBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LoansExpensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoansExpensesBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ExpensesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansExpensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarLoansExpenses.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        setupListeners()
        loadExpenses()
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
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelExpense)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveExpense)

        // Configurar Autocompletado con las categorías que el usuario ya creó
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currentCategories)
        autoCategory.setAdapter(adapter)

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
                saveExpense(concept, category, amount)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "⚠️ Ingrese al menos la categoría y el monto", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun saveExpense(concept: String, category: String, amount: Double) {
        lifecycleScope.launch {
            database.loanDao().insertExpense(LoanExpenseEntity(concept = concept, category = category, amount = amount))
            Toast.makeText(this@LoansExpensesActivity, "✅ Gasto registrado", Toast.LENGTH_SHORT).show()
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
