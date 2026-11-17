package com.gomezmart.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.AiChatActivity
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.MainActivity
import com.gomezmart.app.MetricsDetailActivity
import com.gomezmart.app.R
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanBudgetEntity
import com.gomezmart.app.data.LoanClientEntity
import com.gomezmart.app.data.LoanEntity
import com.gomezmart.app.data.LoanExpenseEntity
import com.gomezmart.app.data.LoanFundingSourceEntity
import com.gomezmart.app.data.LoanInstallmentEntity
import com.gomezmart.app.data.LoanPaymentEntity
import com.gomezmart.app.databinding.FragmentLoansMetricsBinding
import com.gomezmart.app.util.GeminiHelper
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
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }

    // Variables para IA
    private var lastTotalLent = 0.0
    private var lastPendingBalance = 0.0
    private var lastNetProfit = 0.0
    private var lastExpenses = 0.0
    private var lastClientsCount = 0
    private var lastOverdueCount = 0
    private var lastCommitments = 0.0
    private var hasAnimatedCharts = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupDetailNavigation()
        loadCharts()
        loadTotals()
    }

    /**
     * Los dos gráficos de esta pantalla son una VISTA PREVIA. Al tocarlos se abre
     * la pantalla de detalle con los mismos datos ampliados, desglosados en
     * cifras y porcentajes, y con indicadores extra que sí son accionables.
     */
    private fun setupDetailNavigation() {
        val openDetail = View.OnClickListener {
            val intent = android.content.Intent(requireContext(), MetricsDetailActivity::class.java)
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }
        binding.cardCapitalDistribution.setOnClickListener(openDetail)
        binding.cardWeeklyCollections.setOnClickListener(openDetail)
    }

    private fun setupToolbar() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.toolbarMetricsFrag, applyTop = true, applyBottom = false)

        binding.toolbarMetricsFrag.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun loadTotals() {
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val paymentsFlow = dbInstance.loanDao().getAllPaymentsFlowV2()
            val loansFlow = dbInstance.loanDao().getAllLoans()
            val expensesFlow = dbInstance.loanDao().getAllExpenses() // Cambiar a lista para filtrar
            val clientsFlow = dbInstance.loanDao().getAllClients()
            val installmentsFlow = dbInstance.loanDao().getAllInstallmentsFlow()
            val commitmentsFlow = dbInstance.commitmentDao().getTotalPendingAmountFlow()

            combine(paymentsFlow, loansFlow, expensesFlow, clientsFlow, installmentsFlow, commitmentsFlow) { args ->
                val payments = args[0] as List<LoanPaymentEntity>
                val loans = args[1] as List<LoanEntity>
                val expensesList = args[2] as List<LoanExpenseEntity>
                val clients = args[3] as List<LoanClientEntity>
                val installments = args[4] as List<LoanInstallmentEntity>
                val commitments = args[5] as? Double

                val totalCollected = payments.filter { !it.isDeleted && it.type == 0 }.sumOf { it.amount + it.lateFeeAmount }
                
                var totalProfit = 0.0
                payments.filter { !it.isDeleted && it.type == 0 }.forEach { payment ->
                    val loan = loans.find { id -> id.id == payment.loanId && !id.isDeleted }
                    loan?.let {
                        val interestRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                        totalProfit += (payment.amount * interestRatio) + payment.lateFeeAmount
                    }
                }
                
                // SOLO GASTOS DE NEGOCIO
                val businessExpenses = expensesList.filter { !it.isDeleted && !it.isPersonal }.sumOf { it.amount }
                val netProfit = totalProfit - businessExpenses
                
                // Guardar datos para IA
                lastTotalLent = loans.filter { !it.isDeleted && it.status != "PAID" }.sumOf { it.amount }
                lastPendingBalance = installments.filter { !it.isDeleted && it.status != "PAID" }.sumOf { it.amount - it.amountPaid }
                lastNetProfit = netProfit
                lastExpenses = businessExpenses
                lastClientsCount = clients.filter { !it.isDeleted }.size
                lastCommitments = commitments ?: 0.0
                
                // Clientes morosos (tienen al menos una cuota vencida)
                val now = System.currentTimeMillis()
                val overdueLoanIds = installments.filter { !it.isDeleted && it.status != "PAID" && it.dueDate < now }
                    .map { it.loanId }.distinct()
                val overdueClientIds = loans.filter { !it.isDeleted && overdueLoanIds.contains(it.id) }
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
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val loansFlow = dbInstance.loanDao().getAllLoans()
            val installmentsFlow = dbInstance.loanDao().getAllInstallmentsFlow()
            val paymentsFlow = dbInstance.loanDao().getAllPaymentsFlowV2()
            val expensesFlow = dbInstance.loanDao().getAllExpenses()
            val budgetFlow = dbInstance.loanDao().getLatestBudget()
            val fundingFlow = dbInstance.loanDao().getAllFundingSourcesFlow()

            combine(loansFlow, installmentsFlow, paymentsFlow, expensesFlow, budgetFlow, fundingFlow) { args ->
                val loans = args[0] as List<LoanEntity>
                val installments = args[1] as List<com.gomezmart.app.data.LoanInstallmentEntity>
                val payments = args[2] as List<LoanPaymentEntity>
                val expensesList = args[3] as List<LoanExpenseEntity>
                val budget = args[4] as? LoanBudgetEntity
                val fundingSources = args[5] as List<LoanFundingSourceEntity>
                val fundingMap = fundingSources.associateBy { it.loanId }

                // SOLO GASTOS DE NEGOCIO
                val businessExpensesTotal = expensesList.filter { !it.isDeleted && !it.isPersonal }.sumOf { it.amount }

                // 1. Calcular Ganancia Cobrada (Intereses + Moras ya pagados)
                var collectedProfit = 0.0
                payments.filter { !it.isDeleted && it.type == 0 }.forEach { p ->
                    val loan = loans.find { it.id == p.loanId && !it.isDeleted }
                    loan?.let {
                        val profitRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                        collectedProfit += (p.amount * profitRatio) + p.lateFeeAmount
                    }
                }

                // 2. Calcular Capital en la Calle (Dinero real prestado que falta recuperar - negocio y personal)
                var capitalInStreet = 0.0
                var bizCapitalInStreet = 0.0
                val activeMetricsLoans = loans.filter { !it.isDeleted && (it.status == "ACTIVE" || it.status == "OVERDUE") }
                activeMetricsLoans.forEach { loan ->
                    val isLoanPersonal = fundingMap[loan.id]?.isPersonal ?: loan.isPersonal
                    val loanInsts = installments.filter { it.loanId == loan.id && !it.isDeleted }
                    val totalPaidToLoan = loanInsts.sumOf { it.amountPaid }
                    val totalToPay = loan.totalToPay
                    val interestRatio = if (totalToPay > 0.0) (totalToPay - loan.amount) / totalToPay else 0.0
                    val capitalRatio = 1.0 - interestRatio
                    val capitalPaid = totalPaidToLoan * capitalRatio
                    val remCapital = (loan.amount - capitalPaid).coerceAtLeast(0.0)
                    capitalInStreet += remCapital
                    if (!isLoanPersonal) {
                        bizCapitalInStreet += remCapital
                    }
                }

                // 3. Calcular Ganancia Pendiente (Intereses que faltan cobrar)
                var pendingProfit = 0.0
                val activeLoanIds = activeMetricsLoans.map { it.id }.toSet()
                installments.filter { !it.isDeleted && it.status != "PAID" && activeLoanIds.contains(it.loanId) }.forEach { inst ->
                    val loan = activeMetricsLoans.find { it.id == inst.loanId }
                    loan?.let {
                        val profitRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                        pendingProfit += (inst.amount - inst.amountPaid) * profitRatio
                    }
                }

                // 4. Calcular presupuesto disponible (Caja disponible del negocio)
                // Fórmula: Capital Inicial + Ganancia Cobrada de Negocio - Capital en la Calle de Negocio - Gastos
                var availableBudget = 0.0
                budget?.let {
                    val initialCapital = it.amount
                    val startTime = it.startDate
                    val bizExpensesInPeriod = expensesList.filter { e -> !e.isDeleted && e.timestamp >= startTime && !e.isPersonal }.sumOf { e -> e.amount }
                    val bizCollectedProfit = payments.filter { !it.isDeleted && it.type == 0 }.sumOf { p ->
                        val loan = loans.find { l -> l.id == p.loanId && !l.isDeleted }
                        val isPersonal = loan?.let { l -> fundingMap[l.id]?.isPersonal ?: l.isPersonal } ?: false
                        if (loan != null && !isPersonal && loan.totalToPay > 0.0) {
                            val profitRatio = (loan.totalToPay - loan.amount) / loan.totalToPay
                            (p.amount * profitRatio) + p.lateFeeAmount
                        } else 0.0
                    }
                    availableBudget = (initialCapital + bizCollectedProfit - bizCapitalInStreet - bizExpensesInPeriod).coerceAtLeast(0.0)
                }

                withContext(Dispatchers.Main) {
                    val entries = mutableListOf<com.github.mikephil.charting.data.PieEntry>()
                    val colorsList = mutableListOf<Int>()
                    
                    if (capitalInStreet < 0.1 && pendingProfit < 0.1 && collectedProfit < 0.1 && businessExpensesTotal < 0.1 && availableBudget < 0.1) {
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
                        if (businessExpensesTotal > 0.1) {
                            entries.add(com.github.mikephil.charting.data.PieEntry(businessExpensesTotal.toFloat(), "Gastos"))
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
                        if (!hasAnimatedCharts) {
                            animateXY(800, 800)
                        } else {
                            invalidate()
                        }
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

            val dbInstance = database ?: return@launch
            val paymentsFlow = dbInstance.loanDao().getPaymentsInRange(start, end)
            val expensesFlow = dbInstance.loanDao().getExpensesInRange(start, end)

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
                        
                        if (!hasAnimatedCharts) {
                            animateY(1200)
                            hasAnimatedCharts = true
                        } else {
                            invalidate()
                        }
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
