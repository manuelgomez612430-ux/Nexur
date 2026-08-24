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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
                calculateFinancials(p, b, c, l)
            }.collect { items ->
                adapter.submitList(items)
            }
        }
    }

    private fun calculateFinancials(
        payments: List<com.naxor.app.data.HotelPaymentEntity>,
        bookings: List<com.naxor.app.data.HotelBookingEntity>,
        charges: List<com.naxor.app.data.HotelChargeEntity>,
        logs: List<com.naxor.app.data.MovementLogEntity>
    ): List<HotelMovementItem> {
        // 1. Ingresos por Estancias y Pagos
        val depositIncome = bookings.sumOf { it.deposit }
        val paymentIncome = payments.sumOf { it.amount }
        val totalIncome = depositIncome + paymentIncome

        // 2. Egresos (De logs que sean tipo EXPENSE o similar)
        val hotelExpenses = logs.filter { it.type.contains("EXPENSE") || it.type.contains("GASTO") }
        val totalExpense = hotelExpenses.sumOf { 
            // Extraer valor numérico del string "S/ 10.00"
            it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
        }

        val netProfit = totalIncome - totalExpense

        // Actualizar UI de Totales
        _binding?.let { b ->
            b.tvTotalIncome.text = "S/ ${String.format(Locale.US, "%.2f", totalIncome)}"
            b.tvTotalExpense.text = "S/ ${String.format(Locale.US, "%.2f", totalExpense)}"
            b.tvNetProfit.text = "S/ ${String.format(Locale.US, "%.2f", netProfit)}"
            
            if (netProfit < 0) b.tvNetProfit.setTextColor(requireContext().getColor(R.color.red_600))
            else b.tvNetProfit.setTextColor(requireContext().getColor(R.color.emerald_600))
        }

        // 3. Crear lista de movimientos para el historial inferior
        val movements = mutableListOf<HotelMovementItem>()
        
        // Agregar Pagos
        payments.forEach { 
            movements.add(HotelMovementItem(it.id, "PAYMENT", "Pago Recibido", it.amount, it.timestamp)) 
        }
        
        // Agregar Depósitos iniciales
        bookings.forEach {
            if (it.deposit > 0) {
                movements.add(HotelMovementItem(it.id + "_dep", "PAYMENT", "Anticipo: ${it.guestName}", it.deposit, it.timestamp))
            }
        }

        // Agregar Gastos
        hotelExpenses.forEach {
            val amount = it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
            movements.add(HotelMovementItem(it.id.toString(), "CHARGE", it.title, amount, it.timestamp))
        }

        return movements.sortedByDescending { it.timestamp }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
