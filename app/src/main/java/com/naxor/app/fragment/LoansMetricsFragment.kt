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
import com.naxor.app.util.GeminiHelper
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

    // Variables para IA
    private var lastTotalLent = 0.0
    private var lastPendingBalance = 0.0
    private var lastNetProfit = 0.0
    private var lastExpenses = 0.0
    private var lastClientsCount = 0
    private var lastOverdueCount = 0
    private var lastCommitments = 0.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        loadCharts()
        loadTotals()

        binding.btnRefreshLoansAi.setOnClickListener {
            generateAiAnalysis()
        }

        binding.btnCloseLoansAi.setOnClickListener {
            binding.tvLoansAiContent.text = "¿Quieres un análisis de tu rentabilidad y riesgo? Toca el botón para que Gemini revise tu cartera."
            binding.btnCloseLoansAi.visibility = View.GONE
        }
    }

    private fun generateAiAnalysis() {
        binding.progressLoansAi.visibility = View.VISIBLE
        binding.tvLoansAiContent.alpha = 0.5f

        viewLifecycleOwner.lifecycleScope.launch {
            val analysis = GeminiHelper.getLoanPortfolioAnalysis(
                lastTotalLent,
                lastPendingBalance,
                lastNetProfit,
                lastExpenses,
                lastClientsCount,
                lastOverdueCount,
                lastCommitments
            )
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    val formattedText = GeminiHelper.formatMarkdown(analysis)
                    binding.tvLoansAiContent.text = formattedText
                    binding.tvLoansAiContent.alpha = 1.0f
                    binding.progressLoansAi.visibility = View.GONE
                    binding.btnCloseLoansAi.visibility = View.VISIBLE
                }
            }
        }
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
            val clientsFlow = database.loanDao().getAllClients()
            val installmentsFlow = database.loanDao().getAllInstallmentsFlow()
            val commitmentsFlow = database.commitmentDao().getTotalPendingAmountFlow()

            combine(paymentsFlow, loansFlow, expensesFlow, clientsFlow, installmentsFlow, commitmentsFlow) { args ->
                val payments = args[0] as List<com.naxor.app.data.LoanPaymentEntity>
                val loans = args[1] as List<com.naxor.app.data.LoanEntity>
                val expenses = args[2] as? Double
                val clients = args[3] as List<com.naxor.app.data.LoanClientEntity>
                val installments = args[4] as List<com.naxor.app.data.LoanInstallmentEntity>
                val commitments = args[5] as? Double

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
                
                // Guardar datos para IA
                lastTotalLent = loans.filter { it.status != "PAID" }.sumOf { it.amount }
                lastPendingBalance = installments.filter { it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
                lastNetProfit = netProfit
                lastExpenses = expenses ?: 0.0
                lastClientsCount = clients.size
                lastCommitments = commitments ?: 0.0
                
                // Clientes morosos (tienen al menos una cuota vencida)
                val now = System.currentTimeMillis()
                val overdueLoanIds = installments.filter { it.status != "PAID" && it.dueDate < now }
                    .map { it.loanId }.distinct()
                val overdueClientIds = loans.filter { overdueLoanIds.contains(it.id) }
                    .map { it.clientId }.distinct()
                lastOverdueCount = overdueClientIds.size
                
                withContext(Dispatchers.Main) {
                    binding.tvMetricsTotalCollected.text = String.format(Locale.US, "S/ %.2f", totalCollected)
                    binding.tvMetricsTotalProfit.text = String.format(Locale.US, "S/ %.2f", netProfit)
                }
            }.collectLatest { }
        }
    }

    private fun loadCharts() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loansFlow = database.loanDao().getAllLoans()
            val installmentsFlow = database.loanDao().getAllInstallmentsFlow()
            val paymentsFlow = database.loanDao().getAllPaymentsFlowV2()
            val expensesFlow = database.loanDao().getTotalExpensesFlow()
            val budgetFlow = database.loanDao().getLatestBudget()

            combine(loansFlow, installmentsFlow, paymentsFlow, expensesFlow, budgetFlow) { args ->
                val loans = args[0] as List<com.naxor.app.data.LoanEntity>
                val installments = args[1] as List<com.naxor.app.data.LoanInstallmentEntity>
                val payments = args[2] as List<com.naxor.app.data.LoanPaymentEntity>
                val expenses = args[3] as? Double ?: 0.0
                val budget = args[4] as? com.naxor.app.data.LoanBudgetEntity

                // 1. Calcular Ganancia Cobrada (Intereses + Moras ya pagados)
                var collectedProfit = 0.0
                payments.forEach { p ->
                    val loan = loans.find { it.id == p.loanId }
                    loan?.let {
                        val profitRatio = (it.totalToPay - it.amount) / it.totalToPay
                        collectedProfit += (p.amount * profitRatio) + p.lateFeeAmount
                    }
                }

                // 2. Calcular Capital en la Calle (Dinero real prestado que falta recuperar)
                var capitalInStreet = 0.0
                installments.filter { it.status != "PAID" }.forEach { inst ->
                    val loan = loans.find { it.id == inst.loanId }
                    loan?.let {
                        val capitalRatio = it.amount / it.totalToPay
                        capitalInStreet += (inst.amount - inst.amountPaid) * capitalRatio
                    }
                }

                // 3. Calcular Ganancia Pendiente (Intereses que faltan cobrar)
                var pendingProfit = 0.0
                installments.filter { it.status != "PAID" }.forEach { inst ->
                    val loan = loans.find { it.id == inst.loanId }
                    loan?.let {
                        val profitRatio = (it.totalToPay - it.amount) / it.totalToPay
                        pendingProfit += (inst.amount - inst.amountPaid) * profitRatio
                    }
                }

                // 4. Calcular presupuesto disponible (Caja disponible)
                // Fórmula: Capital Inicial + Ganancia Cobrada - Capital en la Calle - Gastos
                var availableBudget = 0.0
                budget?.let {
                    // Consideramos el presupuesto como el "Capital de Trabajo" inicial
                    val initialCapital = it.amount
                    
                    // Calculamos gastos totales registrados desde el inicio de este presupuesto
                    val startTime = it.startDate
                    val totalExpensesInPeriod = database.loanDao().getAllLoanExpensesOnce().filter { e -> e.timestamp >= startTime }.sumOf { e -> e.amount }
                    
                    // La caja disponible es lo que queda del capital inicial + ganancias - lo que está en la calle - gastos
                    availableBudget = (initialCapital + collectedProfit - capitalInStreet - totalExpensesInPeriod).coerceAtLeast(0.0)
                }

                withContext(Dispatchers.Main) {
                    val entries = mutableListOf<com.github.mikephil.charting.data.PieEntry>()
                    val colorsList = mutableListOf<Int>()
                    
                    if (capitalInStreet < 0.1 && pendingProfit < 0.1 && collectedProfit < 0.1 && expenses < 0.1 && availableBudget < 0.1) {
                        entries.add(com.github.mikephil.charting.data.PieEntry(1f, "Sin movimientos"))
                        colorsList.add(requireContext().getColor(R.color.slate_200))
                        
                        val dataSet = com.github.mikephil.charting.data.PieDataSet(entries, "").apply {
                            colors = colorsList
                            setDrawValues(false)
                        }
                        binding.chartMetricsRisk.data = com.github.mikephil.charting.data.PieData(dataSet)
                        binding.chartMetricsRisk.setCenterText("S/ 0.00\nTotal")
                    } else {
                        if (capitalInStreet > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(capitalInStreet.toFloat(), "Capital prestado"))
                            colorsList.add(requireContext().getColor(R.color.indigo_500))
                        }
                        if (pendingProfit > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(pendingProfit.toFloat(), "Interés x cobrar"))
                            colorsList.add(requireContext().getColor(R.color.orange_600))
                        }
                        if (collectedProfit > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(collectedProfit.toFloat(), "Ganancia cobrada"))
                            colorsList.add(requireContext().getColor(R.color.emerald_600))
                        }
                        if (expenses > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(expenses.toFloat(), "Gastos"))
                            colorsList.add(requireContext().getColor(R.color.red_600))
                        }
                        if (availableBudget > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(availableBudget.toFloat(), "Caja disponible"))
                            colorsList.add(requireContext().getColor(R.color.vibrant_purple))
                        }

                        val dataSet = com.github.mikephil.charting.data.PieDataSet(entries, "").apply {
                            colors = colorsList
                            valueTextColor = android.graphics.Color.WHITE
                            valueTextSize = 11f
                        }
                        binding.chartMetricsRisk.data = com.github.mikephil.charting.data.PieData(dataSet)
                        
                        // El Total es el valor actual del negocio (Capital Inicial + Ganancias - Gastos)
                        // Que es igual a: capitalInStreet + availableBudget + pendingProfit
                        val totalGeneral = capitalInStreet + pendingProfit + availableBudget
                        binding.chartMetricsRisk.setCenterText("S/ ${String.format(Locale.US, "%.2f", totalGeneral)}\nTotal")
                    }

                    binding.chartMetricsRisk.apply {
                        description.isEnabled = false
                        isDrawHoleEnabled = true
                        holeRadius = 55f
                        transparentCircleRadius = 0f
                        setHoleColor(android.graphics.Color.TRANSPARENT)
                        legend.isEnabled = true
                        legend.verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
                        legend.horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
                        legend.textColor = requireContext().getColor(R.color.slate_500)
                        legend.orientation = com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL
                        legend.isWordWrapEnabled = true
                        setCenterTextColor(requireContext().getColor(R.color.slate_700))
                        setCenterTextSize(13f)
                        setDrawEntryLabels(false)
                        animateXY(800, 800)
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
