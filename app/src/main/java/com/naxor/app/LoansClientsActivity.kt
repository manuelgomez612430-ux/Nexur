package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.LoanClientEntity
import com.naxor.app.databinding.ActivityLoansClientsBinding
import com.naxor.app.databinding.ItemLoanClientBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class LoansClientsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoansClientsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ClientsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansClientsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarClients.setNavigationOnClickListener { finish() }
        setupRecyclerView()
        setupSearch()
        loadClients()
    }

    private fun setupSearch() {
        binding.etSearchClients.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadClients(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Configurar Voz
        val inputLayout = binding.etSearchClients.parent.parent as? com.google.android.material.textfield.TextInputLayout
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
            Toast.makeText(this, "La búsqueda por voz no está disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.get(0)
            binding.etSearchClients.setText(spokenText)
        }
    }

    private fun loadClients(query: String = "") {
        lifecycleScope.launch {
            if (query.isEmpty()) {
                database.loanDao().getAllClients().collectLatest { clients ->
                    adapter.submitList(clients)
                }
            } else {
                database.loanDao().searchClients(query).collectLatest { clients ->
                    adapter.submitList(clients)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ClientsAdapter { client ->
            val intent = Intent(this, LoanDetailsActivity::class.java)
            intent.putExtra("CLIENT_ID", client.id)
            startActivity(intent)
        }
        binding.rvLoanClients.layoutManager = LinearLayoutManager(this)
        binding.rvLoanClients.adapter = adapter
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
            lifecycleScope.launch {
                val loans = database.loanDao().getLoansByClient(item.id).firstOrNull() ?: emptyList()
                var totalOverdue = 0
                loans.forEach { loan: com.naxor.app.data.LoanEntity ->
                    val installments = database.loanDao().getInstallmentsByLoan(loan.id).firstOrNull() ?: emptyList()
                    totalOverdue += installments.count { inst: com.naxor.app.data.LoanInstallmentEntity -> inst.status != "PAID" && inst.dueDate < System.currentTimeMillis() }
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    with(holder.b) {
                        tvClientName.text = item.name
                        tvClientInitial.text = item.name.take(1).uppercase()
                        tvClientInfo.text = "DNI: ${item.doc} | 📱 ${item.phone}"
                        
                        if (totalOverdue >= 3) {
                            chipClientScore.text = "¡MOROSO! ⚡"
                            chipClientScore.setChipBackgroundColorResource(R.color.red_600)
                            chipClientScore.setTextColor(getColor(R.color.white))
                        } else {
                            chipClientScore.text = "${item.score} pts"
                            chipClientScore.setChipBackgroundColorResource(R.color.emerald_50)
                            chipClientScore.setTextColor(getColor(R.color.emerald_600))
                        }
                        root.setOnClickListener { onClick(item) }
                    }
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
