package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanClientEntity
import com.naxor.app.databinding.FragmentLoansClientsBinding
import com.naxor.app.databinding.ItemLoanClientBinding
import com.naxor.app.LoanDetailsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.*

class LoansClientsFragment : Fragment() {

    private var _binding: FragmentLoansClientsBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private lateinit var adapter: ClientsAdapter
    private var currentFilter = "ACTIVE"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansClientsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        
        // Sincronizar UI con el filtro por defecto
        binding.chipFilterActiveFrag.isChecked = true
        
        loadClients()
    }

    private fun setupToolbar() {
        binding.toolbarClientsFrag.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupFilters() {
        binding.chipGroupFiltersFrag.setOnCheckedStateChangeListener { _, checkedIds ->
            val firstId = if (checkedIds.isNotEmpty()) checkedIds[0] else -1
            currentFilter = when(firstId) {
                R.id.chipFilterActiveFrag -> "ACTIVE"
                R.id.chipFilterOverdueFrag -> "OVERDUE"
                R.id.chipFilterPaidFrag -> "PAID"
                else -> "ALL"
            }
            loadClients(binding.etSearchClientsFrag.text.toString())
        }
    }

    private fun setupSearch() {
        binding.etSearchClientsFrag.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadClients(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val inputLayout = binding.etSearchClientsFrag.parent.parent as? com.google.android.material.textfield.TextInputLayout
        inputLayout?.setEndIconOnClickListener {
            startVoiceSearch()
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Diga el nombre del cliente...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "La búsqueda por voz no está disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.get(0)
            binding.etSearchClientsFrag.setText(spokenText)
        }
    }

    private fun loadClients(query: String = "") {
        viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (query.isEmpty()) database.loanDao().getAllClients() 
                       else database.loanDao().searchClients(query)
            
            flow.collectLatest { clients ->
                val resultList = mutableListOf<LoanClientEntity>()
                
                clients.forEach { client ->
                    val loans = database.loanDao().getLoansByClientSync(client.id)
                    var hasRealDebt = false
                    var hasAnyLoan = loans.isNotEmpty()
                    var isMoroso = false

                    loans.forEach { loan ->
                        val installments = database.loanDao().getInstallmentsByLoanSync(loan.id)
                        val balance = installments.sumOf { it.amount - it.amountPaid }
                        
                        if (balance > 0.01) {
                            hasRealDebt = true
                            val now = System.currentTimeMillis()
                            if (installments.any { it.status != "PAID" && it.dueDate < now }) {
                                isMoroso = true
                            }
                        }
                    }
                    
                    val shouldInclude = when(currentFilter) {
                        "ACTIVE" -> hasRealDebt
                        "OVERDUE" -> isMoroso
                        "PAID" -> !hasRealDebt && hasAnyLoan
                        else -> true
                    }
                    if (shouldInclude) resultList.add(client)
                }
                
                adapter.submitList(resultList)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ClientsAdapter { client ->
            val intent = Intent(requireContext(), LoanDetailsActivity::class.java)
            intent.putExtra("CLIENT_ID", client.id)
            startActivity(intent)
        }
        binding.rvLoanClientsFrag.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLoanClientsFrag.adapter = adapter
    }

    inner class ClientsAdapter(private val onClick: (LoanClientEntity) -> Unit) : RecyclerView.Adapter<ClientsAdapter.ViewHolder>() {
        private var items = emptyList<LoanClientEntity>()
        fun submitList(newItems: List<LoanClientEntity>) { items = newItems; notifyDataSetChanged() }
        inner class ViewHolder(val b: ItemLoanClientBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            viewLifecycleOwner.lifecycleScope.launch {
                val loans = database.loanDao().getLoansByClient(item.id).firstOrNull() ?: emptyList()
                var totalOverdue = 0
                var totalPaidOnTime = 0
                var totalPendingBalance = 0.0
                loans.forEach { loan ->
                    val installments = database.loanDao().getInstallmentsByLoan(loan.id).firstOrNull() ?: emptyList()
                    totalOverdue += installments.count { it.status != "PAID" && it.dueDate < System.currentTimeMillis() }
                    totalPaidOnTime += installments.count { it.status == "PAID" }
                    totalPendingBalance += installments.sumOf { it.amount - it.amountPaid }
                }

                val stars = when {
                    totalOverdue > 5 -> "⭐"
                    totalOverdue > 2 -> "⭐⭐"
                    totalPaidOnTime > 20 -> "⭐⭐⭐⭐⭐"
                    totalPaidOnTime > 10 -> "⭐⭐⭐⭐"
                    else -> "⭐⭐⭐"
                }

                withContext(Dispatchers.Main) {
                    with(holder.b) {
                        tvClientName.text = item.name
                        tvClientInitial.text = item.name.take(1).uppercase()
                        tvClientRating.text = stars
                        tvClientInfo.text = "DNI: ${item.doc} | 📱 ${item.phone}"
                        tvClientPendingBalance.text = "Deuda: S/ ${String.format(Locale.US, "%.2f", totalPendingBalance)}"
                        tvClientPendingBalance.visibility = if (totalPendingBalance > 0) View.VISIBLE else View.GONE
                        
                        if (totalOverdue >= 1) {
                            chipClientScore.text = "¡CON MORA! ⚡"
                            chipClientScore.setChipBackgroundColorResource(R.color.red_600)
                            chipClientScore.setTextColor(requireContext().getColor(R.color.white))
                        } else {
                            chipClientScore.text = "Al día ✅"
                            chipClientScore.setChipBackgroundColorResource(R.color.emerald_50)
                            chipClientScore.setTextColor(requireContext().getColor(R.color.emerald_600))
                        }
                        root.setOnClickListener { onClick(item) }
                    }
                }
            }
        }
        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
