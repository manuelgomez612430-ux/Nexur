package com.naxor.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentLoansMetricsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*

class LoansMetricsFragment : Fragment() {

    private var _binding: FragmentLoansMetricsBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        loadCharts()
        loadTotals()
    }

    private fun setupToolbar() {
        binding.toolbarMetricsFrag.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
        binding.toolbarMetricsFrag.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_mailbox) {
                startActivity(android.content.Intent(requireContext(), com.naxor.app.MailboxActivity::class.java))
                true
            } else false
        }
    }

    private fun loadTotals() {
        viewLifecycleOwner.lifecycleScope.launch {
            val paymentsFlow = database.loanDao().getAllPaymentsFlowV2()
            val loansFlow = database.loanDao().getAllLoans()
            val expensesFlow = database.loanDao().getTotalExpensesFlow()

            combine(paymentsFlow, loansFlow, expensesFlow) { payments, loans, expenses ->
                val totalCollected = payments.sumOf { it.amount + it.lateFeeAmount }
                
                var totalProfit = 0.0
                payments.forEach { payment ->
                    val loan = loans.find { it.id == payment.loanId }
                    loan?.let {
                        val interestRatio = (it.totalToPay - it.amount) / it.totalToPay
                        totalProfit += (payment.amount * interestRatio) + payment.lateFeeAmount
                    }
                }
                
                val netProfit = totalProfit - (expenses ?: 0.0)
                
                withContext(Dispatchers.Main) {
                    binding.tvMetricsTotalCollected.text = String.format(Locale.US, "S/ %.2f", totalCollected)
                    binding.tvMetricsTotalProfit.text = String.format(Locale.US, "S/ %.2f", netProfit)
                }
            }.collectLatest { }
        }
    }

    private fun loadCharts() {
        viewLifecycleOwner.lifecycleScope.launch {
            val capitalFlow = database.loanDao().getCapitalInStreetFlow()
            val balanceFlow = database.loanDao().getTotalOutstandingBalanceFlow()

            combine(capitalFlow, balanceFlow) { capital, balance ->
                val capValue = capital ?: 0.0
                val balValue = balance ?: 0.0

                withContext(Dispatchers.Main) {
                    val entries = mutableListOf<com.github.mikephil.charting.data.PieEntry>()
                    
                    // Si no hay datos, mostrar una rebanada gris de "Sin Deuda"
                    if (capValue == 0.0 && balValue == 0.0) {
                        entries.add(com.github.mikephil.charting.data.PieEntry(1f, "Sin préstamos"))
                        val dataSet = com.github.mikephil.charting.data.PieDataSet(entries, "")
                        dataSet.color = requireContext().getColor(R.color.slate_200)
                        dataSet.setDrawValues(false)
                        binding.chartMetricsRisk.data = com.github.mikephil.charting.data.PieData(dataSet)
                        binding.chartMetricsRisk.setCenterText("S/ 0.00\nTotal")
                    } else {
                        if (capValue > 0) entries.add(com.github.mikephil.charting.data.PieEntry(capValue.toFloat(), "Dinero Prestado"))
                        if (balValue > 0) entries.add(com.github.mikephil.charting.data.PieEntry(balValue.toFloat(), "Falta Cobrar"))

                        val dataSet = com.github.mikephil.charting.data.PieDataSet(entries, "")
                        dataSet.colors = listOf(
                            requireContext().getColor(R.color.indigo_500),
                            requireContext().getColor(R.color.sky_600)
                        )
                        dataSet.valueTextColor = android.graphics.Color.WHITE
                        dataSet.valueTextSize = 12f
                        binding.chartMetricsRisk.data = com.github.mikephil.charting.data.PieData(dataSet)
                        binding.chartMetricsRisk.setCenterText("S/ ${String.format(Locale.US, "%.2f", capValue + balValue)}\nCartera")
                    }

                    binding.chartMetricsRisk.apply {
                        description.isEnabled = false
                        isDrawHoleEnabled = true
                        holeRadius = 58f
                        transparentCircleRadius = 0f
                        setHoleColor(android.graphics.Color.TRANSPARENT)
                        
                        legend.apply {
                            isEnabled = true
                            verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
                            horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
                            textColor = requireContext().getColor(R.color.slate_500)
                        }

                        setCenterTextColor(requireContext().getColor(R.color.slate_700))
                        // Corrección para Modo Oscuro en texto central
                        val isDark = requireContext().getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE).getBoolean("dark_mode", false)
                        if (isDark) setCenterTextColor(android.graphics.Color.WHITE)
                        
                        setCenterTextSize(14f)
                        setDrawEntryLabels(false)
                        animateXY(1000, 1000, com.github.mikephil.charting.animation.Easing.EaseInOutQuad)
                        invalidate()
                    }
                }
            }.collectLatest { }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Gráfico de Barras BASADO EN PAGOS REALES Y GASTOS REALES
            val now = Calendar.getInstance()
            now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59)
            val end = now.timeInMillis
            now.add(Calendar.DAY_OF_YEAR, -6)
            now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
            val start = now.timeInMillis

            val paymentsFlow = database.loanDao().getPaymentsInRange(start, end)
            val expensesFlow = database.loanDao().getExpensesInRange(start, end)

            combine(paymentsFlow, expensesFlow) { payments, expenses ->
                val entriesCobros = mutableListOf<com.github.mikephil.charting.data.BarEntry>()
                val entriesGastos = mutableListOf<com.github.mikephil.charting.data.BarEntry>()
                val days = mutableListOf<String>()
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                
                val cal = Calendar.getInstance()
                cal.timeInMillis = start
                
                for (i in 0..6) {
                    val dayStart = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                    val dayEnd = cal.timeInMillis
                    
                    val dailyCobro = payments.filter { it.timestamp in dayStart..dayEnd }.sumOf { it.amount + it.lateFeeAmount }
                    val dailyGasto = expenses.filter { it.timestamp in dayStart..dayEnd }.sumOf { it.amount }
                    
                    entriesCobros.add(com.github.mikephil.charting.data.BarEntry(i.toFloat(), dailyCobro.toFloat()))
                    entriesGastos.add(com.github.mikephil.charting.data.BarEntry(i.toFloat(), dailyGasto.toFloat()))
                    days.add(sdf.format(Date(dayStart)))
                    
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                }

                withContext(Dispatchers.Main) {
                    val setCobros = com.github.mikephil.charting.data.BarDataSet(entriesCobros, "Cobrado").apply {
                        color = requireContext().getColor(R.color.emerald_600)
                        setDrawValues(true)
                        valueTextColor = requireContext().getColor(R.color.slate_700)
                        valueTextSize = 9f
                    }
                    
                    val setGastos = com.github.mikephil.charting.data.BarDataSet(entriesGastos, "Gastos").apply {
                        color = requireContext().getColor(R.color.red_600)
                        setDrawValues(true)
                        valueTextColor = requireContext().getColor(R.color.slate_700)
                        valueTextSize = 9f
                    }

                    val groupSpace = 0.3f
                    val barSpace = 0.05f
                    val barWidth = 0.3f
                    // (barWidth + barSpace) * 2 + groupSpace = 1.00 -> (0.3+0.05)*2 + 0.3 = 1.00

                    val barData = com.github.mikephil.charting.data.BarData(setCobros, setGastos)
                    barData.barWidth = barWidth

                    binding.chartMetricsCollection.apply {
                        data = barData
                        groupBars(0f, groupSpace, barSpace)
                        
                        description.isEnabled = false
                        legend.apply {
                            isEnabled = true
                            verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.TOP
                            horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.RIGHT
                        }
                        
                        xAxis.apply {
                            position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                            setDrawGridLines(false)
                            textColor = requireContext().getColor(R.color.slate_500)
                            valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(days)
                            granularity = 1f
                            isGranularityEnabled = true
                            axisMinimum = 0f
                            axisMaximum = 7f
                            setCenterAxisLabels(true)
                        }

                        axisLeft.apply {
                            setDrawGridLines(true)
                            gridColor = requireContext().getColor(R.color.slate_100)
                            textColor = requireContext().getColor(R.color.slate_400)
                            axisMinimum = 0f
                        }
                        axisRight.isEnabled = false
                        
                        animateY(1200)
                        invalidate()
                    }
                }
            }.collectLatest { }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
