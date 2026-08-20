package com.naxor.app.fragment

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.*
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityResumenBinding
import com.naxor.app.util.GeminiHelper
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MetricasFragment : Fragment() {

    private var _binding: ActivityResumenBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    private var startDate: Long? = null
    private var endDate: Long? = null

    // Variables para almacenar las métricas actuales y pasarlas a la IA
    private var currentVentas = 0.0
    private var currentUtilidad = 0.0
    private var currentGastos = 0.0
    private var currentDeudores = 0.0
    private var currentDeudas = 0.0

    fun openDrawer() {
        if (_binding != null) {
            binding.drawerLayoutResumen.openDrawer(GravityCompat.START)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityResumenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inicializar con la fecha de HOY por defecto
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        startDate = calendar.timeInMillis
        
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        endDate = calendar.timeInMillis

        updateFilterLabel()

        binding.btnFilterDateMetricas.setOnClickListener {
            showFilterOptionsDialog()
        }

        binding.btnOpenMenuResumen.setOnClickListener {
            openDrawer()
        }

        binding.btnRefreshAiAnalysis.setOnClickListener {
            generateAiAnalysis(currentVentas, currentUtilidad, currentGastos, currentDeudores, currentDeudas)
        }
        
        setupCharts()
        loadStatistics()
    }

    private fun showFilterOptionsDialog() {
        val options = mutableListOf(
            "📍 Elegir un día específico",
            "↔️ Elegir un rango (Inicio - Fin)",
            "🌎 Todo el historial"
        )
        
        // Agregar opción de volver a Hoy solo si no estamos viendo Hoy actualmente
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val todayStr = sdf.format(Date())
        val currentStartStr = if (startDate != null) sdf.format(Date(startDate!!)) else ""
        val currentEndStr = if (endDate != null) sdf.format(Date(endDate!!)) else ""
        
        val isShowingToday = currentStartStr == todayStr && currentEndStr == todayStr

        if (!isShowingToday) {
            options.add(0, "📅 Volver a ver HOY")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Filtrar Rendimiento")
            .setItems(options.toTypedArray()) { _, which ->
                val selected = options[which]
                when {
                    selected.contains("HOY") -> setFilterToday()
                    selected.contains("específico") -> showSingleDatePicker()
                    selected.contains("rango") -> showDateRangePicker()
                    selected.contains("historial") -> setFilterAllTime()
                }
            }
            .show()
    }

    private fun setFilterToday() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        endDate = cal.timeInMillis
        applyFilter()
    }

    private fun showSingleDatePicker() {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(requireContext(), { _, y, m, d ->
            val cal = Calendar.getInstance()
            cal.set(y, m, d, 0, 0, 0)
            startDate = cal.timeInMillis
            cal.set(y, m, d, 23, 59, 59)
            endDate = cal.timeInMillis
            applyFilter()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("Seleccionar el día")
            show()
        }
    }

    private fun setFilterAllTime() {
        startDate = null
        endDate = null
        applyFilter()
    }

    private fun applyFilter() {
        updateFilterLabel()
        loadStatistics()
    }

    private fun showDateRangePicker() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val startPicker = android.app.DatePickerDialog(requireContext(), { _, y1, m1, d1 ->
            val startCal = Calendar.getInstance()
            startCal.set(y1, m1, d1, 0, 0, 0)
            val tempStart = startCal.timeInMillis

            val endPicker = android.app.DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val endCal = Calendar.getInstance()
                endCal.set(y2, m2, d2, 23, 59, 59)
                
                startDate = tempStart
                endDate = endCal.timeInMillis
                updateFilterLabel()
                loadStatistics()
            }, y1, m1, d1)
            endPicker.setTitle("Seleccionar fecha final")
            endPicker.show()
        }, year, month, day)
        startPicker.setTitle("Seleccionar fecha inicial")
        startPicker.show()
    }

    private fun updateFilterLabel() {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val today = sdf.format(Date())
        
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(cal.time)

        if (startDate != null && endDate != null) {
            val startStr = sdf.format(Date(startDate!!))
            val endStr = sdf.format(Date(endDate!!))
            
            when {
                startStr == today && endStr == today -> {
                    binding.tvCurrentFilterRange.text = "Mostrando: Solo Hoy ($today)"
                }
                startStr == yesterday && endStr == yesterday -> {
                    binding.tvCurrentFilterRange.text = "Mostrando: Ayer ($yesterday)"
                }
                startStr == endStr -> {
                    binding.tvCurrentFilterRange.text = "Mostrando: Día $startStr"
                }
                else -> {
                    binding.tvCurrentFilterRange.text = "Mostrando: del $startStr al $endStr"
                }
            }
            binding.tvCurrentFilterRange.setTextColor(Color.parseColor("#7C3AED"))
        } else {
            binding.tvCurrentFilterRange.text = "Mostrando: Historial completo"
            binding.tvCurrentFilterRange.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun setupCharts() {
        binding.pieChartCategorias.description.isEnabled = false
        val legend = binding.pieChartCategorias.legend
        legend.isEnabled = true
        legend.verticalAlignment = Legend.LegendVerticalAlignment.CENTER
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        legend.orientation = Legend.LegendOrientation.VERTICAL
        legend.setDrawInside(false)
        legend.textColor = Color.GRAY
        
        binding.pieChartCategorias.setHoleColor(Color.TRANSPARENT)
        binding.pieChartCategorias.setEntryLabelColor(Color.TRANSPARENT)
        
        binding.barChartSemana.description.isEnabled = false
        binding.barChartSemana.setDrawGridBackground(false)
        binding.barChartSemana.xAxis.position = XAxis.XAxisPosition.BOTTOM
        binding.barChartSemana.xAxis.setDrawGridLines(false)
        binding.barChartSemana.axisLeft.setDrawGridLines(false)
        binding.barChartSemana.axisRight.isEnabled = false
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            try {
                val sales = withContext(Dispatchers.IO) { 
                    if (startDate != null && endDate != null) {
                        database.saleDao().getSalesInRange(startDate!!, endDate!!)
                    } else {
                        database.saleDao().allSales 
                    }
                }
                val products = withContext(Dispatchers.IO) { database.productDao().allProducts }
                val expenses = withContext(Dispatchers.IO) { 
                    if (startDate != null && endDate != null) {
                        database.expenseDao().getExpensesInRange(startDate!!, endDate!!)
                    } else {
                        database.expenseDao().getAllExpenses()
                    }
                }

                var ventasTotales = 0.0
                var gananciaBruta = 0.0
                for (sale in sales) {
                    ventasTotales += sale.total
                    gananciaBruta += (sale.total - (sale.costoUnitario * sale.cantidad))
                }

                val ticketPromedio = if (sales.isNotEmpty()) ventasTotales / sales.size else 0.0
                val costoVentas = ventasTotales - gananciaBruta
                val gastosTotales = expenses.sumOf { it.monto }
                val utilidadNeta = gananciaBruta - gastosTotales
                val inversionTotal = products.sumOf { it.precioCosto * it.stock }
                val valorInventario = products.sumOf { it.precioVenta * it.stock }

                val entriesPie = sales.groupBy { it.categoria }
                    .map { PieEntry(it.value.sumOf { s -> s.total }.toFloat(), it.key) }
                
                val days = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
                val daySales = FloatArray(7) { 0f }
                val dayExpenses = FloatArray(7) { 0f }
                val cal = Calendar.getInstance()
                
                for (sale in sales) {
                    cal.timeInMillis = sale.timestamp
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
                    daySales[dayOfWeek] += sale.total.toFloat()
                }
                
                for (expense in expenses) {
                    cal.timeInMillis = expense.fecha
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
                    dayExpenses[dayOfWeek] += expense.monto.toFloat()
                }

                val entriesSales = daySales.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }
                val entriesExpenses = dayExpenses.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }

                val topProducts = sales.groupBy { it.nombreProducto }
                    .mapValues { entry -> entry.value.sumOf { it.cantidad } }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(3)

                // NUEVO: Cargar deudas y deudores
                val totalDeudores = withContext(Dispatchers.IO) { 
                    database.debtorDao().getAllDebtors().sumOf { it.deudaTotal }
                }
                val totalDeudasPropias = withContext(Dispatchers.IO) { 
                    database.businessDebtDao().getTotalOwed() ?: 0.0
                }

                // Guardar métricas actuales para el análisis de IA bajo demanda
                currentVentas = ventasTotales
                currentUtilidad = gananciaBruta
                currentGastos = gastosTotales
                currentDeudores = totalDeudores
                currentDeudas = totalDeudasPropias

                if (_binding != null) {
                    updateUI(ventasTotales, gananciaBruta, gastosTotales, utilidadNeta, inversionTotal, valorInventario, topProducts, ticketPromedio, costoVentas, totalDeudores, totalDeudasPropias)
                    updateChartsUI(entriesPie, entriesSales, entriesExpenses, days)
                    
                    // Ya NO se dispara el análisis automáticamente
                    // generateAiAnalysis(ventasTotales, gananciaBruta, gastosTotales, totalDeudores, totalDeudasPropias)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateAiAnalysis(v: Double, u: Double, g: Double, dr: Double, dp: Double) {
        val range = binding.tvCurrentFilterRange.text.toString().replace("Mostrando: ", "")
        
        binding.progressAiAnalysis.visibility = View.VISIBLE
        binding.tvAiAnalysisContent.alpha = 0.5f

        lifecycleScope.launch {
            val analysis = GeminiHelper.getBusinessAnalysis(v, u, g, dr, dp, range)
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    binding.tvAiAnalysisContent.text = analysis
                    binding.tvAiAnalysisContent.alpha = 1.0f
                    binding.progressAiAnalysis.visibility = View.GONE
                }
            }
        }
    }

    private fun updateUI(ventas: Double, bruta: Double, gastos: Double, neta: Double, inversion: Double, valorInv: Double, top: List<Pair<String, Int>>, ticket: Double, cogs: Double, deudores: Double, deudas: Double) {
        val currency = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE).getString("currency_symbol", "S/")
        
        binding.tvVentasTotalesResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", ventas)}"
        binding.tvUtilidadNeta.text = "$currency ${String.format(Locale.getDefault(), "%.2f", neta)}"
        binding.tvGastosTotalesResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", gastos)}"
        
        binding.tvInversionResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", inversion)}"
        binding.tvValorInventarioResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", valorInv)}"
        binding.tvGananciaResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", bruta)}"
        
        binding.tvTicketPromedio.text = "$currency ${String.format(Locale.getDefault(), "%.2f", ticket)}"
        binding.tvCostoVentas.text = "$currency ${String.format(Locale.getDefault(), "%.2f", cogs)}"

        binding.tvTotalDeudoresResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", deudores)}"
        binding.tvTotalDeudasPropiasResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", deudas)}"
        
        binding.tvUtilidadNeta.setTextColor(if(neta >= 0) resources.getColor(R.color.emerald_700, null) else resources.getColor(R.color.red_700, null))

        if (top.isNotEmpty()) {
            binding.layoutTopProducts.removeAllViews()
            for (item in top) {
                val itemView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_2, binding.layoutTopProducts, false)
                val text1 = itemView.findViewById<TextView>(android.R.id.text1)
                val text2 = itemView.findViewById<TextView>(android.R.id.text2)
                text1.text = item.first
                text1.setTextColor(resources.getColor(R.color.slate_900, null))
                text2.text = "Vendidos: ${item.second} unidades"
                text2.setTextColor(resources.getColor(R.color.slate_500, null))
                binding.layoutTopProducts.addView(itemView)
            }
        }
    }

    private fun updateChartsUI(pieEntries: List<PieEntry>, salesEntries: List<BarEntry>, expensesEntries: List<BarEntry>, days: Array<String>) {
        val pieDataSet = PieDataSet(pieEntries, "")
        val colors = listOf(Color.parseColor("#0284C7"), Color.parseColor("#10B981"), Color.parseColor("#F59E0B"), Color.parseColor("#6366F1"), Color.parseColor("#F43F5E"))
        pieDataSet.colors = colors
        pieDataSet.valueTextSize = 12f
        pieDataSet.valueTextColor = Color.WHITE
        pieDataSet.sliceSpace = 3f
        
        binding.pieChartCategorias.data = PieData(pieDataSet)
        binding.pieChartCategorias.animateY(1000)
        binding.pieChartCategorias.invalidate()

        val salesDataSet = BarDataSet(salesEntries, "Ventas")
        salesDataSet.color = Color.parseColor("#0284C7")
        salesDataSet.valueTextSize = 0f

        val expensesDataSet = BarDataSet(expensesEntries, "Gastos")
        expensesDataSet.color = Color.parseColor("#F43F5E")
        expensesDataSet.valueTextSize = 0f

        val barData = BarData(salesDataSet, expensesDataSet)
        barData.barWidth = 0.35f
        binding.barChartSemana.data = barData
        
        binding.barChartSemana.xAxis.valueFormatter = IndexAxisValueFormatter(days)
        binding.barChartSemana.xAxis.setCenterAxisLabels(true)
        binding.barChartSemana.xAxis.granularity = 1f
        binding.barChartSemana.xAxis.axisMinimum = 0f
        binding.barChartSemana.xAxis.axisMaximum = 7f
        
        binding.barChartSemana.groupBars(0f, 0.20f, 0.05f)
        binding.barChartSemana.animateY(1000)
        binding.barChartSemana.invalidate()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadStatistics()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
