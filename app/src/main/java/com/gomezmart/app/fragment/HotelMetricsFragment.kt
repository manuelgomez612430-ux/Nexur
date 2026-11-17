package com.gomezmart.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.datepicker.MaterialDatePicker
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.adapter.HotelMovementAdapter
import com.gomezmart.app.adapter.HotelMovementItem
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.ExpenseEntity
import com.gomezmart.app.data.HotelBookingEntity
import com.gomezmart.app.data.HotelPaymentEntity
import com.gomezmart.app.databinding.FragmentHotelMetricsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class HotelMetricsFragment : Fragment() {

    private var _binding: FragmentHotelMetricsBinding? = null
    private val binding get() = _binding!!
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private val adapter = HotelMovementAdapter()

    private var startDate: Long = 0
    private var endDate: Long = Long.MAX_VALUE
    private var lastResult: FinancialResult? = null
    private var metricsJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setFilterToToday()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null && metricsJob != null) {
            loadMetrics()
        }
    }

    private fun setupToolbar() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.toolbarMetrics, applyTop = true, applyBottom = false)

        binding.toolbarMetrics.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarMetrics.setNavigationOnClickListener { (activity as? MainActivity)?.openSideMenu() }
    }

    private fun setupFilters() {
        binding.chipGroupTime.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipToday -> setFilterToToday()
                R.id.chipYesterday -> setFilterToYesterday()
                R.id.chipMonth -> setFilterToMonth()
            }
        }

        binding.chipCustom.setOnClickListener {
            if (!binding.chipCustom.isChecked) {
                binding.chipGroupTime.check(R.id.chipCustom)
            }
            showDatePicker()
        }
    }

    private fun setFilterToToday() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        endDate = System.currentTimeMillis()
        binding.chipCustom.text = "Elegir Fecha"
        loadMetrics()
    }

    private fun setFilterToYesterday() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        endDate = cal.timeInMillis
        binding.chipCustom.text = "Elegir Fecha"
        loadMetrics()
    }

    private fun setFilterToMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        endDate = System.currentTimeMillis()
        binding.chipCustom.text = "Elegir Fecha"
        loadMetrics()
    }

    private fun showDatePicker() {
        if (childFragmentManager.findFragmentByTag("DATE_PICKER") != null) return

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Día para Reporte")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = selection

            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            startDate = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            endDate = cal.timeInMillis

            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            _binding?.chipCustom?.text = sdf.format(Date(selection))

            loadMetrics()
        }

        picker.show(childFragmentManager, "DATE_PICKER")
    }

    private fun setupRecyclerView() {
        val ctx = context ?: return
        binding.rvFinancialMovements.layoutManager = LinearLayoutManager(ctx)
        binding.rvFinancialMovements.adapter = adapter
        binding.barChartFinancial.setNoDataText("Sin flujo financiero en este período")
        binding.barChartFinancial.setNoDataTextColor(ContextCompat.getColor(ctx, R.color.slate_400))
    }

    private fun loadMetrics() {
        val dbInstance = database ?: return
        metricsJob?.cancel()
        metricsJob = viewLifecycleOwner.lifecycleScope.launch {
            combine(
                dbInstance.hotelDao().getAllPaymentsFlow(),
                dbInstance.hotelDao().getAllBookings()
            ) { p, b -> Pair(p, b) }.collectLatest { (payments, bookings) ->
                val result = withContext(Dispatchers.IO) {
                    val expenses = dbInstance.expenseDao().getExpensesInRange(startDate, endDate)
                        .filter { !it.isPersonal }
                    processFinancialData(payments, bookings, expenses)
                }
                lastResult = result
                updateUI(result)
            }
        }
    }

    private fun processFinancialData(
        payments: List<HotelPaymentEntity>,
        bookings: List<HotelBookingEntity>,
        expenses: List<ExpenseEntity>
    ): FinancialResult {
        val fP = payments.filter { it.timestamp in startDate..endDate }
        val fB = bookings.filter { it.timestamp in startDate..endDate && it.status != "CANCELLED" }

        val income = fP.sumOf { it.amount } + fB.sumOf { it.deposit }
        val expense = expenses.sumOf { it.monto }

        val movements = mutableListOf<HotelMovementItem>()
        fP.forEach {
            val label = if (it.nightsPaid > 0) "Pago (${it.nightsPaid} noches)" else "Pago Recibido"
            movements.add(HotelMovementItem(it.id, "PAYMENT", label, it.amount, it.timestamp))
        }
        fB.forEach {
            if (it.deposit > 0) {
                movements.add(HotelMovementItem(it.id + "_dep", "PAYMENT", "Anticipo: ${it.guestName}", it.deposit, it.timestamp))
            }
        }
        expenses.forEach {
            movements.add(HotelMovementItem("exp_${it.id}", "CHARGE", "${it.categoria}: ${it.concepto}", it.monto, it.fecha))
        }

        return FinancialResult(income, expense, income - expense, movements.sortedByDescending { it.timestamp })
    }

    private fun updateUI(result: FinancialResult) {
        val ctx = context ?: return
        val b = _binding ?: return
        b.tvTotalIncome.text = String.format(Locale.US, "S/ %.2f", result.totalIncome)
        b.tvTotalExpense.text = String.format(Locale.US, "S/ %.2f", result.totalExpense)
        b.tvNetProfit.text = String.format(Locale.US, "S/ %.2f", result.netProfit)
        b.tvNetProfit.setTextColor(ContextCompat.getColor(ctx, if (result.netProfit < 0) R.color.red_600 else R.color.emerald_600))

        val isEmpty = result.movements.isEmpty()
        b.rvFinancialMovements.visibility = if (isEmpty) View.GONE else View.VISIBLE
        b.layoutEmptyMovements.visibility = if (isEmpty) View.VISIBLE else View.GONE
        adapter.submitList(result.movements)
        setupChart(result)
    }

    private fun setupChart(result: FinancialResult) {
        val ctx = context ?: return
        val b = _binding ?: return
        if (result.totalIncome == 0.0 && result.totalExpense == 0.0) {
            b.barChartFinancial.clear()
            b.barChartFinancial.invalidate()
            return
        }
        val entries = listOf(
            BarEntry(0f, result.totalIncome.toFloat()),
            BarEntry(1f, result.totalExpense.toFloat())
        )
        val dataSet = BarDataSet(entries, "Flujo")
        dataSet.colors = listOf(
            ContextCompat.getColor(ctx, R.color.emerald_600),
            ContextCompat.getColor(ctx, R.color.red_600)
        )
        b.barChartFinancial.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(listOf("Ingresos", "Egresos"))
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            animateY(600)
            invalidate()
        }
    }

    data class FinancialResult(val totalIncome: Double, val totalExpense: Double, val netProfit: Double, val movements: List<HotelMovementItem>)

    override fun onDestroyView() {
        super.onDestroyView()
        metricsJob?.cancel()
        metricsJob = null
        _binding = null
    }
}
