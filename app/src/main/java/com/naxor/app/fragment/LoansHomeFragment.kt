package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentLoansHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LoansHomeFragment : Fragment() {

    private var _binding: FragmentLoansHomeBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        loadDashboardData()
        updateWelcomeMessage()
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
        val name = prefs.getString("business_name", "Gestor")
        binding.tvWelcomeLoans.text = "¡Hola, $name! 👋"
        
        val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
        binding.tvLoansDate.text = "Hoy es " + sdf.format(Date()).replaceFirstChar { it.uppercase() }
    }

    private fun setupListeners() {
        binding.toolLoansNew.setOnClickListener { 
            showNewLoanOptions()
        }
        binding.toolLoansClients.setOnClickListener { 
            (activity as? MainActivity)?.navigateToStock() // Pestaña de clientes
        }
        binding.cardTodayCollections.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.LoansCollectionsActivity::class.java))
        }
        binding.toolLoansExpenses.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.LoansExpensesActivity::class.java))
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
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
