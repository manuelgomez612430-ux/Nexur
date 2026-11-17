package com.gomezmart.app.fragment

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
import com.gomezmart.app.BaseActivity
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanClientEntity
import com.gomezmart.app.data.LoanClientWithDebt
import com.gomezmart.app.databinding.FragmentLoansClientsBinding
import com.gomezmart.app.databinding.ItemLoanClientBinding
import com.gomezmart.app.LoanDetailsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class LoansClientsFragment : Fragment() {

    private var _binding: FragmentLoansClientsBinding? = null
    private val binding get() = _binding!!
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private lateinit var adapter: ClientsAdapter
    private var currentFilter = "ACTIVE"
    private val registrations = mutableListOf<ListenerRegistration>()
    private var searchJob: Job? = null

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
        
        binding.btnAddNewLoanClientEmptyFrag.setOnClickListener {
            val intent = Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_loans.AddLoanActivity")
            startActivity(intent)
        }
        
        // Sincronizar UI con el filtro por defecto
        binding.chipFilterActiveFrag.isChecked = true
        
        val dbInstance = database
        if (dbInstance != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    dbInstance.loanDao().sanitizeAllLoans()
                } catch (e: Exception) {}
            }
        }

        loadClients()
    }

    override fun onStart() {
        super.onStart()
        if (registrations.isEmpty()) {
            val sm = com.gomezmart.app.SyncManager(requireContext())
            sm.startRealtimeLoanClientsSync { if (_binding != null) loadClients(binding.etSearchClientsFrag.text.toString()) }?.let { registrations.add(it) }
            sm.startRealtimeLoansSync { if (_binding != null) loadClients(binding.etSearchClientsFrag.text.toString()) }?.let { registrations.add(it) }
            sm.startRealtimeInstallmentsSync { if (_binding != null) loadClients(binding.etSearchClientsFrag.text.toString()) }?.let { registrations.add(it) }
            sm.startRealtimeLoanPaymentsSync { if (_binding != null) loadClients(binding.etSearchClientsFrag.text.toString()) }?.let { registrations.add(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            loadClients(binding.etSearchClientsFrag.text.toString())
        }
    }

    private fun setupToolbar() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.toolbarClientsFrag, applyTop = true, applyBottom = false)

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
        val dbInstance = database ?: return
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    dbInstance.loanDao().sanitizeAllLoans()
                } catch (e: Exception) {}
            }
            val now = System.currentTimeMillis()
            val flow = if (query.isEmpty()) dbInstance.loanDao().getAllClientsWithDebtFlow(now) 
                       else dbInstance.loanDao().searchClientsWithDebtFlow(query, now)
            
            flow.collectLatest { clientsWithDebt ->
                val resultList = withContext(Dispatchers.Default) {
                    clientsWithDebt.filter { item ->
                        val hasRealDebt = item.totalDebt > 0.01
                        val isMoroso = item.totalOverdueCount > 0

                        when(currentFilter) {
                            "ACTIVE" -> hasRealDebt
                            "OVERDUE" -> isMoroso
                            "PAID" -> !hasRealDebt && (item.totalPaidOnTimeCount > 0)
                            else -> true
                        }
                    }
                }
                adapter.submitList(resultList)
                if (resultList.isEmpty()) {
                    binding.rvLoanClientsFrag.visibility = View.GONE
                    binding.layoutEmptyLoanClientsFrag.visibility = View.VISIBLE
                    if (query.isNotEmpty()) {
                        binding.tvEmptyLoanClientsTitleFrag.text = "No se encontraron clientes"
                        binding.tvEmptyLoanClientsSubtitleFrag.text = "No hay coincidencias para '$query'."
                    } else {
                        binding.tvEmptyLoanClientsTitleFrag.text = "No hay ningún cliente registrado"
                        binding.tvEmptyLoanClientsSubtitleFrag.text = "Toca el botón para registrar tu primer cliente o préstamo."
                    }
                } else {
                    binding.rvLoanClientsFrag.visibility = View.VISIBLE
                    binding.layoutEmptyLoanClientsFrag.visibility = View.GONE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ClientsAdapter { clientWithDebt ->
            val intent = Intent(requireContext(), LoanDetailsActivity::class.java)
            intent.putExtra("CLIENT_ID", clientWithDebt.client.id)
            startActivity(intent)
        }
        binding.rvLoanClientsFrag.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLoanClientsFrag.adapter = adapter
    }

    inner class ClientsAdapter(private val onClick: (LoanClientWithDebt) -> Unit) : RecyclerView.Adapter<ClientsAdapter.ViewHolder>() {
        private var items = emptyList<LoanClientWithDebt>()
        fun submitList(newItems: List<LoanClientWithDebt>) { items = newItems; notifyDataSetChanged() }
        
        inner class ViewHolder(val b: ItemLoanClientBinding) : RecyclerView.ViewHolder(b.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val itemWithDebt = items[position]
            val item = itemWithDebt.client
            val balance = itemWithDebt.totalDebt

            val stars = when {
                itemWithDebt.totalOverdueCount > 5 -> "⭐"
                itemWithDebt.totalOverdueCount > 2 -> "⭐⭐"
                itemWithDebt.totalPaidOnTimeCount > 20 -> "⭐⭐⭐⭐⭐"
                itemWithDebt.totalPaidOnTimeCount > 10 -> "⭐⭐⭐⭐"
                else -> "⭐⭐⭐"
            }

            with(holder.b) {
                tvClientName.text = item.name
                
                if (!item.photoPath.isNullOrEmpty() && java.io.File(item.photoPath).exists()) {
                    ivClientPhoto.visibility = View.VISIBLE
                    tvClientInitial.visibility = View.GONE
                    com.bumptech.glide.Glide.with(holder.itemView.context)
                        .load(java.io.File(item.photoPath))
                        .circleCrop()
                        .into(ivClientPhoto)
                } else {
                    ivClientPhoto.visibility = View.GONE
                    tvClientInitial.visibility = View.VISIBLE
                    tvClientInitial.text = item.name.take(1).uppercase(Locale.ROOT)
                }

                tvClientRating.text = stars
                tvClientInfo.text = "DNI: ${item.doc} | 📱 ${item.phone}"
                tvClientPendingBalance.text = "Deuda: S/ ${String.format(Locale.US, "%.2f", balance)}"
                tvClientPendingBalance.visibility = if (balance > 0.01) View.VISIBLE else View.GONE

                // Origen de los fondos del préstamo (Personal / Presupuesto / Mixto)
                when (itemWithDebt.fundingSource) {
                    "PERSONAL" -> {
                        tvClientFundingSource.text = "👤 Capital Personal"
                        tvClientFundingSource.background = requireContext().getDrawable(R.drawable.bg_pill_purple_light)
                        tvClientFundingSource.setTextColor(requireContext().getColor(R.color.purple_600))
                        tvClientFundingSource.visibility = View.VISIBLE
                    }
                    "BUDGET" -> {
                        tvClientFundingSource.text = "💼 Presupuesto del Negocio"
                        tvClientFundingSource.background = requireContext().getDrawable(R.drawable.bg_pill_emerald_light)
                        tvClientFundingSource.setTextColor(requireContext().getColor(R.color.emerald_600))
                        tvClientFundingSource.visibility = View.VISIBLE
                    }
                    "MIXED" -> {
                        tvClientFundingSource.text = "🔀 Mixto"
                        tvClientFundingSource.background = requireContext().getDrawable(R.drawable.bg_pill_amber_light)
                        tvClientFundingSource.setTextColor(requireContext().getColor(R.color.orange_600))
                        tvClientFundingSource.visibility = View.VISIBLE
                    }
                    else -> tvClientFundingSource.visibility = View.GONE
                }
                
                if (itemWithDebt.totalOverdueCount >= 1) {
                    chipClientScore.text = "¡CON MORA! ⚡"
                    chipClientScore.setChipBackgroundColorResource(R.color.red_600)
                    chipClientScore.setTextColor(requireContext().getColor(R.color.white))
                } else if (balance <= 0.01) {
                    chipClientScore.text = "Finalizado ✅"
                    chipClientScore.setChipBackgroundColorResource(R.color.slate_100)
                    chipClientScore.setTextColor(requireContext().getColor(R.color.slate_600))
                } else {
                    chipClientScore.text = "Al día ✅"
                    chipClientScore.setChipBackgroundColorResource(R.color.emerald_50)
                    chipClientScore.setTextColor(requireContext().getColor(R.color.emerald_600))
                }
                chipClientScore.setOnClickListener { onClick(itemWithDebt) }
                root.setOnClickListener { onClick(itemWithDebt) }
            }
        }
        override fun getItemCount() = items.size
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
