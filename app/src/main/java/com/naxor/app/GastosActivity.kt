package com.naxor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ExpenseEntity
import com.naxor.app.databinding.ActivityGastosBinding
import com.naxor.app.databinding.ItemGastoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class GastosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGastosBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: GastosAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGastosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadExpenses()
    }

    private fun setupRecyclerView() {
        adapter = GastosAdapter { expense -> showDeleteConfirmation(expense) }
        binding.rvGastos.layoutManager = LinearLayoutManager(this)
        binding.rvGastos.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackGastos.setOnClickListener { finish() }
        binding.btnHelpGastos.setOnClickListener { showHelpDialog() }
        binding.fabAddGasto.setOnClickListener { showAddExpenseDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Gastos")
            .setMessage("• REGISTRO: Usa '+' para anotar pagos de luz, alquiler, transporte, etc.\n" +
                    "• UTILIDAD: Estos gastos se restarán automáticamente de tus ventas en la pantalla de Resultados.\n" +
                    "• BORRAR: Mantén presionado un gasto para eliminarlo.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadExpenses() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.expenseDao().getAllExpenses()
            }
            adapter.submitList(list)
            binding.layoutEmptyGastos.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            
            val total = list.sumOf { it.monto }
            binding.tvTotalGastosMes.text = String.format(Locale.getDefault(), "Gastos acumulados: S/ %.2f", total)
        }
    }

    private fun showAddExpenseDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Registrar Gasto")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        
        val etConcepto = EditText(this).apply { hint = "Concepto (ej: Luz, Alquiler)" }
        val etMonto = EditText(this).apply { 
            hint = "Monto (S/)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL 
        }
        
        val spinner = Spinner(this)
        val categories = arrayOf("Servicios", "Alquiler", "Transporte", "Sueldos", "Mercancía", "Otros")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        
        layout.addView(etConcepto)
        layout.addView(etMonto)
        layout.addView(spinner)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val concepto = etConcepto.text.toString()
            val monto = etMonto.text.toString().toDoubleOrNull() ?: 0.0
            val categoria = spinner.selectedItem.toString()
            
            if (concepto.isNotBlank() && monto > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.expenseDao().insert(ExpenseEntity(concepto = concepto, monto = monto, categoria = categoria, isSynced = false))
                    SyncManager(this@GastosActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadExpenses() }
                }
            } else {
                Toast.makeText(this, "Completa los campos correctamente", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun showDeleteConfirmation(expense: ExpenseEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Gasto")
            .setMessage("¿Deseas eliminar '${expense.concepto}'?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.expenseDao().delete(expense)
                    withContext(Dispatchers.Main) { loadExpenses() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class GastosAdapter(private val onDelete: (ExpenseEntity) -> Unit) : RecyclerView.Adapter<GastosAdapter.ViewHolder>() {
        private var list: List<ExpenseEntity> = emptyList()
        fun submitList(newList: List<ExpenseEntity>) {
            list = newList
            notifyDataSetChanged()
        }
        inner class ViewHolder(val b: ItemGastoBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemGastoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val e = list[position]
            holder.b.tvGastoConcepto.text = e.concepto
            holder.b.tvGastoCategoria.text = e.categoria
            holder.b.tvGastoMonto.text = String.format("- S/ %.2f", e.monto)
            holder.b.root.setOnLongClickListener {
                onDelete(e)
                true
            }
        }
        override fun getItemCount() = list.size
    }
}
