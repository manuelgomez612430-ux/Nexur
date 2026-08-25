package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanInstallmentEntity
import com.naxor.app.databinding.ActivityLoansCollectionsBinding
import com.naxor.app.databinding.ItemLoanCollectionBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

class LoansCollectionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoansCollectionsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CollectionsAdapter
    private var allTodayPending: List<LoanInstallmentEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansCollectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarCollections.inflateMenu(R.menu.menu_loans_collections)
        binding.toolbarCollections.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_export_daily) {
                exportDailyReport()
                true
            } else false
        }

        binding.toolbarCollections.setNavigationOnClickListener { finish() }
        setupRecyclerView()
        setupSearch()
        loadCollections()
    }

    private fun exportDailyReport() {
        if (allTodayPending.isEmpty()) {
            Toast.makeText(this, "No hay cobros hoy para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val pdfFile = com.naxor.app.util.LoanPdfGenerator(this@LoansCollectionsActivity).generateDailyCollectionReport(allTodayPending)
            pdfFile?.let { file ->
                val uri = androidx.core.content.FileProvider.getUriForFile(this@LoansCollectionsActivity, "$packageName.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Compartir Reporte del Día"))
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = CollectionsAdapter { installment ->
            sendWhatsappReminder(installment)
        }
        binding.rvTodayCollections.layoutManager = LinearLayoutManager(this)
        binding.rvTodayCollections.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchCollections.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCollections(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun loadCollections() {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0)
        val start = now.timeInMillis
        now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59); now.set(Calendar.SECOND, 59)
        val end = now.timeInMillis

        lifecycleScope.launch {
            database.loanDao().getInstallmentsInRange(start, end).collectLatest { installments ->
                allTodayPending = installments.filter { it.status != "PAID" }
                filterCollections(binding.etSearchCollections.text.toString())
                
                val total = allTodayPending.sumOf { it.amount - it.amountPaid }
                binding.tvTotalToCollectToday.text = "S/ ${String.format(Locale.US, "%.2f", total)}"
            }
        }
    }

    private fun filterCollections(query: String) {
        lifecycleScope.launch {
            val filtered = if (query.isEmpty()) {
                allTodayPending
            } else {
                allTodayPending.filter { inst ->
                    val loan = database.loanDao().getLoanById(inst.loanId)
                    val client = loan?.let { database.loanDao().getClientById(it.clientId) }
                    client?.name?.contains(query, ignoreCase = true) == true
                }
            }
            adapter.submitList(filtered)
        }
    }

    private fun sendWhatsappReminder(installment: LoanInstallmentEntity) {
        lifecycleScope.launch {
            val loan = database.loanDao().getLoanById(installment.loanId)
            val client = loan?.let { database.loanDao().getClientById(it.clientId) }
            
            client?.let {
                val message = "Hola ${it.name}, te recordamos que hoy vence tu cuota de Naxor por S/ ${String.format(Locale.US, "%.2f", installment.amount)}. ¡Que tengas un buen día!"
                val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=${it.phone}&text=${android.net.Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            } ?: Toast.makeText(this@LoansCollectionsActivity, "Error al buscar datos del cliente", Toast.LENGTH_SHORT).show()
        }
    }

    inner class CollectionsAdapter(private val onWhatsapp: (LoanInstallmentEntity) -> Unit) : RecyclerView.Adapter<CollectionsAdapter.ViewHolder>() {
        private var items = emptyList<LoanInstallmentEntity>()
        fun submitList(newItems: List<LoanInstallmentEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanCollectionBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            lifecycleScope.launch {
                val loan = database.loanDao().getLoanById(item.loanId)
                val client = loan?.let { database.loanDao().getClientById(it.clientId) }
                
                with(holder.b) {
                    tvCollClientName.text = client?.name ?: "Cargando..."
                    tvCollInstallmentInfo.text = "Cuota ${item.installmentNumber} | Vence hoy"
                    tvCollAmount.text = "S/ ${String.format(Locale.US, "%.2f", item.amount)}"
                    btnCollWhatsapp.setOnClickListener { onWhatsapp(item) }
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
