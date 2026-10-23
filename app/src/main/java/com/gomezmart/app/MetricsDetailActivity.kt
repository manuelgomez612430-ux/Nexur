package com.gomezmart.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanBudgetEntity
import com.gomezmart.app.data.LoanEntity
import com.gomezmart.app.data.LoanExpenseEntity
import com.gomezmart.app.data.LoanInstallmentEntity
import com.gomezmart.app.data.LoanPaymentEntity
import com.gomezmart.app.databinding.ActivityMetricsDetailBinding
import com.gomezmart.app.util.GeminiHelper
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pantalla DEDICADA al detalle del rendimiento financiero.
 *
 * La vista previa (Rendimiento Financiero) muestra solo dos gráficos pequeños.
 * Al tocarlos se abre esta pantalla con el MISMO dato ampliado y desglosado
 * en cifras y porcentajes, más una batería de indicadores útiles.
 */
class MetricsDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityMetricsDetailBinding
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }
    private val nf: NumberFormat = DecimalFormat("#,##0.00")

    // Datos cacheados para el análisis con IA
    private var lastTotalLent = 0.0
    private var lastPendingBalance = 0.0
    private var lastNetProfit = 0.0
    private var lastExpenses = 0.0
    private var lastClientsCount = 0
    private var lastOverdueCount = 0
    private var lastCommitments = 0.0

    // Totales de la ÚLTIMA SEMANA (los que muestra el gráfico de barras)
    private var weekCollected = 0.0
    private var weekSpent = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.headerMetricsDetail, applyTop = true, applyBottom = false)
        binding.btnBackMetricsDetail.setOnClickListener { finish() }

        buildBreakdownHeader(binding.layoutCapitalBreakdown, "Concepto")
        buildBreakdownHeader(binding.layoutWeeklyBreakdown, "Día")

        binding.btnRefreshLoansAi.setOnClickListener {
            binding.progressLoansAi.visibility = View.VISIBLE
            binding.tvLoansAiContent.alpha = 0.5f
            generateAiAnalysis()
        }

        binding.btnChatLoansAi.setOnClickListener {
            startActivityWithFade(android.content.Intent(this, AiChatActivity::class.java))
        }

        loadDetailData()
    }

    /** Encabezado de columnas del desglose (primera columna / Monto / %). */
    private fun buildBreakdownHeader(parent: LinearLayout, firstColumnLabel: String) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4; bottomMargin = 10 }
        }
        header.addView(TextView(this).apply {
            text = firstColumnLabel
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_500))
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        header.addView(TextView(this).apply {
            text = "Monto"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_500))
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (14 * resources.displayMetrics.density).toInt() }
        })
        header.addView(TextView(this).apply {
            text = "% del total"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_500))
            gravity = Gravity.END
        })
        parent.addView(header)
        parent.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_100))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 0; bottomMargin = 6 }
        })
    }

    /**
     * Elimina SOLO las filas de datos del desglose, conservando el encabezado
     * (2 vistas: cabecera + divisor). Es seguro aunque el contenedor esté vacío.
     */
    private fun clearBreakdownRows(parent: LinearLayout) {
        while (parent.childCount > 2) {
            parent.removeViewAt(parent.childCount - 1)
        }
    }

    private fun loadDetailData() {
        val db = database
        lifecycleScope.launch {
            // Datos auxiliares (suspend) fuera del combine
            val clientsCount = withContext(Dispatchers.IO) { db.loanDao().getAllClientsOnce().size }
            val commitments = withContext(Dispatchers.IO) {
                db.commitmentDao().getTotalPendingAmountFlow().first() ?: 0.0
            }
            val allInstallments = withContext(Dispatchers.IO) { db.loanDao().getAllInstallmentsOnce() }

            val loansFlow = db.loanDao().getAllLoans()
            val installmentsFlow = db.loanDao().getAllInstallmentsFlow()
            val paymentsFlow = db.loanDao().getAllPaymentsFlowV2()
            val expensesFlow = db.loanDao().getAllExpenses()
            val budgetFlow = db.loanDao().getLatestBudget()

            combine(loansFlow, installmentsFlow, paymentsFlow, expensesFlow, budgetFlow) { args ->
                val loans = args[0] as List<LoanEntity>
                val installments = args[1] as List<LoanInstallmentEntity>
                val payments = args[2] as List<LoanPaymentEntity>
                val expensesList = args[3] as List<LoanExpenseEntity>
                val budget = args[4] as? LoanBudgetEntity

                // Solo gastos de negocio
                val businessExpensesTotal = expensesList.filter { !it.isPersonal }.sumOf { it.amount }

                // 1. Ganancia cobrada (intereses + moras ya pagados)
                var collectedProfit = 0.0
                payments.forEach { p ->
                    val loan = loans.find { it.id == p.loanId }
                    loan?.let {
                        val profitRatio = if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                        collectedProfit += (p.amount * profitRatio) + p.lateFeeAmount
                    }
                }

                // 2. Total cobrado bruto
                val totalCollected = payments.sumOf { it.amount + it.lateFeeAmount }

                // 3. Capital en la calle (dinero real prestado que falta recuperar)
                var capitalInStreet = 0.0
                val activeLoans = loans.filter {
                    !it.isDeleted && !it.isPersonal &&
                        (it.status == "ACTIVE" || it.status == "OVERDUE")
                }
                activeLoans.forEach { loan ->
                    val loanInsts = installments.filter { it.loanId == loan.id && !it.isDeleted }
                    val totalPaidToLoan = loanInsts.sumOf { it.amountPaid }
                    val totalToPay = loan.totalToPay
                    val interestRatio =
                        if (totalToPay > 0.0) (totalToPay - loan.amount) / totalToPay else 0.0
                    val capitalRatio = 1.0 - interestRatio
                    val capitalPaid = totalPaidToLoan * capitalRatio
                    val remCapital = (loan.amount - capitalPaid).coerceAtLeast(0.0)
                    capitalInStreet += remCapital
                }

                // 4. Ganancia pendiente (intereses que faltan cobrar)
                var pendingProfit = 0.0
                installments.filter { it.status != "PAID" }.forEach { inst ->
                    val loan = loans.find { it.id == inst.loanId }
                    loan?.let {
                        val profitRatio =
                            if (it.totalToPay > 0.0) (it.totalToPay - it.amount) / it.totalToPay else 0.0
                        pendingProfit += (inst.amount - inst.amountPaid) * profitRatio
                    }
                }

                // 5. Caja disponible (presupuesto + cobrado - en calle - gastos)
                var availableBudget = 0.0
                budget?.let {
                    if (capitalInStreet > it.amount && capitalInStreet <= it.amount * 1.25) {
                        capitalInStreet = it.amount
                    }
                    val initialCapital = it.amount
                    val startTime = it.startDate
                    val bizExpensesInPeriod = expensesList
                        .filter { e -> e.timestamp >= startTime && !e.isPersonal }
                        .sumOf { e -> e.amount }
                    availableBudget = (initialCapital + collectedProfit - capitalInStreet - bizExpensesInPeriod)
                        .coerceAtLeast(0.0)
                }

                // --- Datos cacheados para la IA ---
                val netProfit = collectedProfit - businessExpensesTotal
                lastTotalLent = loans.filter { it.status != "PAID" }.sumOf { it.amount }
                lastPendingBalance = installments.filter { it.status != "PAID" }
                    .sumOf { it.amount - it.amountPaid }
                lastNetProfit = netProfit
                lastExpenses = businessExpensesTotal
                lastClientsCount = clientsCount
                lastCommitments = commitments
                val now = System.currentTimeMillis()
                val overdueLoanIds = allInstallments
                    .filter { it.status != "PAID" && it.dueDate < now }
                    .map { it.loanId }.distinct()
                lastOverdueCount = loans.filter { overdueLoanIds.contains(it.id) }
                    .map { it.clientId }.distinct().size

                withContext(Dispatchers.Main) {
                    binding.tvDetailTotalCollected.text = nf.format(totalCollected)
                    binding.tvDetailNetProfit.text = nf.format(netProfit)

                    renderDonut(
                        capitalInStreet, pendingProfit, collectedProfit,
                        businessExpensesTotal, availableBudget
                    )
                    renderWeekly(payments, expensesList)
                    renderKeyIndicators(
                        totalCollected, netProfit, businessExpensesTotal,
                        capitalInStreet, pendingProfit, collectedProfit
                    )
                }
            }.collectLatest { }
        }
    }

    // ------------------------------------------------------------------ //
    //  GRÁFICO 1: DISTRIBUCIÓN DE CAPITAL                                  //
    // ------------------------------------------------------------------ //
    private fun renderDonut(
        capitalInStreet: Double,
        pendingProfit: Double,
        collectedProfit: Double,
        businessExpensesTotal: Double,
        availableBudget: Double
    ) {
        val entries = mutableListOf<PieEntry>()
        val colorList = mutableListOf<Int>()

        addEntry(entries, colorList, capitalInStreet, "Capital prestado",
            ContextCompat.getColor(this, R.color.indigo_500))
        addEntry(entries, colorList, pendingProfit, "Interés x cobrar",
            ContextCompat.getColor(this, R.color.orange_600))
        addEntry(entries, colorList, collectedProfit, "Ganancia cobrada",
            ContextCompat.getColor(this, R.color.emerald_600))
        addEntry(entries, colorList, businessExpensesTotal, "Gastos",
            ContextCompat.getColor(this, R.color.red_600))
        addEntry(entries, colorList, availableBudget, "Caja disponible",
            ContextCompat.getColor(this, R.color.vibrant_purple))

        val totalGeneral = capitalInStreet + pendingProfit + availableBudget

        val dataSet = PieDataSet(entries, "").apply {
            colors = colorList
            valueTextColor = android.graphics.Color.WHITE
            valueTextSize = 12f
            sliceSpace = 3f
        }
        binding.chartDetailCapital.data = PieData(dataSet)
        binding.chartDetailCapital.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 55f
            transparentCircleRadius = 0f
            setHoleColor(android.graphics.Color.TRANSPARENT)
            legend.isEnabled = true
            legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            legend.textColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_500)
            legend.orientation = Legend.LegendOrientation.HORIZONTAL
            legend.isWordWrapEnabled = true
            setCenterTextColor(ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_700))
            setCenterTextSize(14f)
            setDrawEntryLabels(false)
            if (entries.isNotEmpty()) animateXY(700, 700)
            invalidate()
        }
        binding.chartDetailCapital.setCenterText("S/ ${nf.format(totalGeneral)}\nTotal")

        // Filas del desglose
        val breakdown = binding.layoutCapitalBreakdown
        clearBreakdownRows(breakdown)

        val items = listOf(
            Triple("Capital prestado", capitalInStreet,
                ContextCompat.getColor(this, R.color.indigo_500)),
            Triple("Interés x cobrar", pendingProfit,
                ContextCompat.getColor(this, R.color.orange_600)),
            Triple("Ganancia cobrada", collectedProfit,
                ContextCompat.getColor(this, R.color.emerald_600)),
            Triple("Gastos del negocio", businessExpensesTotal,
                ContextCompat.getColor(this, R.color.red_600)),
            Triple("Caja disponible", availableBudget,
                ContextCompat.getColor(this, R.color.vibrant_purple))
        )

        items.forEach { (name, amount, color) ->
            val pct = if (totalGeneral > 0) amount / totalGeneral * 100 else 0.0
            val row = addBreakdownRow(breakdown, name, color)
            row.amount.text = nf.format(amount)
            row.percent.text = "${oneDecimal(pct)} %"
        }
    }

    private fun addEntry(
        entries: MutableList<PieEntry>,
        colorList: MutableList<Int>,
        value: Double,
        label: String,
        color: Int
    ) {
        if (value > 0.1) {
            entries.add(PieEntry(value.toFloat(), label))
            colorList.add(color)
        }
    }

    /** Referencias a los TextView editables de una fila de desglose. */
    private class RowRefs(val amount: TextView, val percent: TextView)

    private fun addBreakdownRow(parent: LinearLayout, name: String, color: Int): RowRefs {
        val row = LayoutInflater.from(this).inflate(R.layout.item_metric_breakdown, parent, false)
        row.findViewById<View>(R.id.viewColorDot).setBackgroundColor(color)
        row.findViewById<TextView>(R.id.tvRowTitle).text = name
        row.findViewById<TextView>(R.id.tvRowSubtitle).text = subtitulo(name)
        parent.addView(row)
        return RowRefs(
            row.findViewById(R.id.tvRowAmount),
            row.findViewById(R.id.tvRowPercent)
        )
    }

    private fun subtitulo(name: String): String = when (name) {
        "Capital prestado" -> "Dinero que está en la calle"
        "Interés x cobrar" -> "Intereses pendientes de cobrar"
        "Ganancia cobrada" -> "Ganancia ya recibida"
        "Gastos del negocio" -> "Gastos registrados"
        "Caja disponible" -> "Presupuesto restante"
        else -> ""
    }

    // ------------------------------------------------------------------ //
    //  GRÁFICO 2: COBROS SEMANALES                                        //
    // ------------------------------------------------------------------ //
    private fun renderWeekly(
        payments: List<LoanPaymentEntity>,
        expensesList: List<LoanExpenseEntity>
    ) {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59)
        val end = now.timeInMillis
        now.add(Calendar.DAY_OF_YEAR, -6)
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        val start = now.timeInMillis

        val dailyCobro = mutableListOf<Double>()
        val dailyGasto = mutableListOf<Double>()
        val days = mutableListOf<String>()
        val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = start
        for (i in 0..6) {
            val dayStart = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            val dayEnd = cal.timeInMillis
            dailyCobro.add(
                payments.filter { it.timestamp in dayStart..dayEnd }
                    .sumOf { it.amount + it.lateFeeAmount }
            )
            dailyGasto.add(
                expensesList.filter { it.timestamp in dayStart..dayEnd }
                    .sumOf { it.amount }
            )
            days.add(sdf.format(Date(dayStart)))
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        }

        val entriesCobro = mutableListOf<BarEntry>()
        val entriesGasto = mutableListOf<BarEntry>()
        for (i in days.indices) {
            entriesCobro.add(BarEntry(i.toFloat(), dailyCobro[i].toFloat()))
            entriesGasto.add(BarEntry(i.toFloat(), dailyGasto[i].toFloat()))
        }

        val setCobro = BarDataSet(entriesCobro, "Cobrado").apply {
            color = ContextCompat.getColor(this@MetricsDetailActivity, R.color.emerald_600)
            setDrawValues(true)
            valueTextColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_700)
            valueTextSize = 9f
        }
        val setGasto = BarDataSet(entriesGasto, "Gastos").apply {
            color = ContextCompat.getColor(this@MetricsDetailActivity, R.color.red_600)
            setDrawValues(true)
            valueTextColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_700)
            valueTextSize = 9f
        }

        val barData = BarData(setCobro, setGasto)
        barData.barWidth = 0.3f

        binding.chartDetailWeekly.apply {
            data = barData
            groupBars(0f, 0.3f, 0.05f)
            description.isEnabled = false
            legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.TOP
                horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_500)
                valueFormatter = IndexAxisValueFormatter(days)
                granularity = 1f
                isGranularityEnabled = true
                axisMinimum = 0f
                axisMaximum = 7f
                setCenterAxisLabels(true)
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_100)
                textColor = ContextCompat.getColor(this@MetricsDetailActivity, R.color.slate_400)
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
            animateY(900)
            invalidate()
        }

        // Desglose diario
        val breakdown = binding.layoutWeeklyBreakdown
        clearBreakdownRows(breakdown)
        val totalCobro = dailyCobro.sum()
        val totalGasto = dailyGasto.sum()
        weekCollected = totalCobro
        weekSpent = totalGasto
        val granTotal = totalCobro + totalGasto
        for (i in days.indices) {
            val c = dailyCobro[i]
            val g = dailyGasto[i]
            val dayTotal = c + g
            val row = addWeeklyRow(breakdown, days[i], c, g)
            row.amount.text = nf.format(dayTotal)
            row.percent.text =
                if (granTotal > 0) "${oneDecimal(dayTotal / granTotal * 100)} %" else "0 %"
        }

        // Totales de la semana
        val summary = LayoutInflater.from(this)
            .inflate(R.layout.item_metric_breakdown, breakdown, false)
        summary.findViewById<View>(R.id.viewColorDot)
            .setBackgroundColor(ContextCompat.getColor(this, R.color.slate_500))
        summary.findViewById<TextView>(R.id.tvRowTitle).text = "Semana completa"
        summary.findViewById<TextView>(R.id.tvRowSubtitle).text =
            "Cobro ${nf.format(totalCobro)} / Gasto ${nf.format(totalGasto)}"
        summary.findViewById<TextView>(R.id.tvRowAmount).text = nf.format(granTotal)
        summary.findViewById<TextView>(R.id.tvRowPercent).text = "100 %"
        summary.setPadding(14, 14, 14, 0)
        summary.setBackgroundColor(ContextCompat.getColor(this, R.color.slate_100))
        breakdown.addView(summary)
    }

    private fun addWeeklyRow(parent: LinearLayout, day: String, cobro: Double, gasto: Double): RowRefs {
        val row = LayoutInflater.from(this).inflate(R.layout.item_metric_breakdown, parent, false)
        row.findViewById<View>(R.id.viewColorDot)
            .setBackgroundColor(ContextCompat.getColor(this, R.color.emerald_600))
        row.findViewById<TextView>(R.id.tvRowTitle).text = day
        row.findViewById<TextView>(R.id.tvRowSubtitle).text =
            "Cobro ${nf.format(cobro)}  ·  Gasto ${nf.format(gasto)}"
        parent.addView(row)
        return RowRefs(
            row.findViewById(R.id.tvRowAmount),
            row.findViewById(R.id.tvRowPercent)
        )
    }

    // ------------------------------------------------------------------ //
    //  INDICADORES CLAVE                                                   //
    // ------------------------------------------------------------------ //
    private fun renderKeyIndicators(
        totalCollected: Double,
        netProfit: Double,
        businessExpensesTotal: Double,
        capitalInStreet: Double,
        pendingProfit: Double,
        collectedProfit: Double
    ) {
        val parent = binding.layoutKeyIndicators
        parent.removeAllViews()

        val margin = if (totalCollected > 0) netProfit / totalCollected * 100 else 0.0
        val avgDay = weekCollected / 7.0
        val roi = if (capitalInStreet > 0) collectedProfit / capitalInStreet * 100 else 0.0
        val pendingTotal = pendingProfit + capitalInStreet
        val health = if (pendingTotal > 0) collectedProfit / pendingTotal * 100 else 0.0

        addIndicator(parent, "Cobrado (7 días)", "S/ ${nf.format(weekCollected)}",
            "Cobros de la última semana")
        addIndicator(parent, "Cobrado (histórico)", "S/ ${nf.format(totalCollected)}",
            "Todos los cobros registrados")
        addIndicator(parent, "Gastos (7 días)", "S/ ${nf.format(weekSpent)}",
            "Gastos de la última semana")
        addIndicator(parent, "Ganancia neta", "S/ ${nf.format(netProfit)}",
            "Cobrado menos gastos del negocio")
        addIndicator(parent, "Margen de ganancia", "${oneDecimal(margin)} %",
            "Ganancia sobre lo cobrado")
        addIndicator(parent, "Gastos del negocio", "S/ ${nf.format(businessExpensesTotal)}",
            "Registrados en el periodo")
        addIndicator(parent, "Promedio cobrado / día", "S/ ${nf.format(avgDay)}",
            "Media de la última semana")
        addIndicator(parent, "Capital en la calle", "S/ ${nf.format(capitalInStreet)}",
            "Préstamos activos por recuperar")
        addIndicator(parent, "Interés por cobrar", "S/ ${nf.format(pendingProfit)}",
            "Ganancia aún no cobrada")
        addIndicator(parent, "Rentabilidad (ROI)", "${oneDecimal(roi)} %",
            "Ganancia cobrada / capital prestado")
        addIndicator(parent, "Recuperación", "${oneDecimal(health)} %",
            "Ganancia cobrada sobre lo invertido")
        addIndicator(parent, "Por cobrar", "S/ ${nf.format(lastPendingBalance)}",
            "Saldo pendiente de cuotas")
        addIndicator(parent, "Capital prestado", "S/ ${nf.format(lastTotalLent)}",
            "En préstamos activos")
        addIndicator(parent, "Compromisos", "S/ ${nf.format(lastCommitments)}",
            "Compromisos pendientes")
        addIndicator(parent, "Clientes", lastClientsCount.toString(),
            "Registrados en total")
        addIndicator(parent, "Clientes morosos", lastOverdueCount.toString(),
            "Con al menos una cuota vencida")
    }

    private fun addIndicator(parent: LinearLayout, title: String, value: String, subtitle: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_metric_indicator, parent, false)
        row.findViewById<TextView>(R.id.tvIndicatorTitle).text = title
        row.findViewById<TextView>(R.id.tvIndicatorSubtitle).text = subtitle
        row.findViewById<TextView>(R.id.tvIndicatorValue).text = value
        parent.addView(row)
    }

    // ------------------------------------------------------------------ //
    //  ANALISTA IA                                                         //
    // ------------------------------------------------------------------ //
    private fun generateAiAnalysis() {
        lifecycleScope.launch {
            val analysis = GeminiHelper.getLoanPortfolioAnalysis(
                lastTotalLent, lastPendingBalance, lastNetProfit, lastExpenses,
                lastClientsCount, lastOverdueCount, lastCommitments
            )
            withContext(Dispatchers.Main) {
                binding.progressLoansAi.visibility = View.GONE
                binding.tvLoansAiContent.alpha = 1.0f
                binding.tvLoansAiContent.text = GeminiHelper.formatMarkdown(analysis)
            }
        }
    }

    /** Redondea a un decimal evitando el "-0.0". */
    private fun oneDecimal(value: Double): String {
        val v = if (abs(value) < 0.05) 0.0 else value
        return String.format(Locale.US, "%.1f", v)
    }
}