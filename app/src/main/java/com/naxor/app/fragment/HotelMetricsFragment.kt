package com.naxor.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.adapter.HotelMovementAdapter
import com.naxor.app.adapter.HotelMovementItem
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentHotelMetricsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HotelMetricsFragment : Fragment() {

    private var _binding: FragmentHotelMetricsBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private val adapter = HotelMovementAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        loadMetrics()
    }

    private fun setupToolbar() {
        binding.toolbarMetrics.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarMetrics.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
        binding.toolbarMetrics.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_mailbox) {
                startActivity(android.content.Intent(requireContext(), com.naxor.app.MailboxActivity::class.java))
                true
            } else false
        }
    }

    private fun setupRecyclerView() {
        binding.rvFinancialMovements.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFinancialMovements.adapter = adapter
    }

    private fun loadMetrics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val f1 = database.hotelDao().getAllPaymentsFlow()
            val f2 = database.hotelDao().getAllBookings()
            val f3 = database.hotelDao().getAllChargesFlow()
            val f4 = database.movementLogDao().getAllLogsFlow()

            combine(f1, f2, f3, f4) { p, b, c, l ->
                // Realizar cálculos en segundo plano si la lista es grande
                processFinancialData(p, b, l)
            }.collect { result ->
                updateUI(result)
            }
        }
    }

    data class FinancialResult(
        val totalIncome: Double,
        val totalExpense: Double,
        val netProfit: Double,
        val movements: List<HotelMovementItem>
    )

    private fun processFinancialData(
        payments: List<com.naxor.app.data.HotelPaymentEntity>,
        bookings: List<com.naxor.app.data.HotelBookingEntity>,
        logs: List<com.naxor.app.data.MovementLogEntity>
    ): FinancialResult {
        val depositIncome = bookings.sumOf { it.deposit }
        val paymentIncome = payments.sumOf { it.amount }
        val totalIncome = depositIncome + paymentIncome

        val hotelExpenses = logs.filter { it.type.contains("EXPENSE") || it.type.contains("GASTO") }
        val totalExpense = hotelExpenses.sumOf { 
            it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
        }

        val netProfit = totalIncome - totalExpense

        val movements = mutableListOf<HotelMovementItem>()
        payments.forEach { movements.add(HotelMovementItem(it.id, "PAYMENT", "Pago Recibido", it.amount, it.timestamp)) }
        bookings.forEach {
            if (it.deposit > 0) {
                movements.add(HotelMovementItem(it.id + "_dep", "PAYMENT", "Anticipo: ${it.guestName}", it.deposit, it.timestamp))
            }
        }
        hotelExpenses.forEach {
            val amount = it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
            movements.add(HotelMovementItem(it.id.toString(), "CHARGE", it.title, amount, it.timestamp))
        }

        return FinancialResult(totalIncome, totalExpense, netProfit, movements.sortedByDescending { it.timestamp })
    }

    private fun updateUI(result: FinancialResult) {
        _binding?.let { b ->
            b.tvTotalIncome.text = "S/ ${String.format(Locale.US, "%.2f", result.totalIncome)}"
            b.tvTotalExpense.text = "S/ ${String.format(Locale.US, "%.2f", result.totalExpense)}"
            b.tvNetProfit.text = "S/ ${String.format(Locale.US, "%.2f", result.netProfit)}"
            
            context?.let { ctx ->
                if (result.netProfit < 0) b.tvNetProfit.setTextColor(ctx.getColor(R.color.red_600))
                else b.tvNetProfit.setTextColor(ctx.getColor(R.color.emerald_600))
            }
            
            adapter.submitList(result.movements)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
