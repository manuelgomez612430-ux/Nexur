package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityResumenBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ResumenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResumenBinding
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Variables para IA bajo demanda
    private var currentVentas = 0.0
    private var currentUtilidad = 0.0
    private var currentGastos = 0.0
    private var currentDeudores = 0.0
    private var currentDeudas = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackResumen.setOnClickListener { finish() }

        binding.navigationViewResumen.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutResumen.closeDrawer(androidx.core.view.GravityCompat.START)
            when (menuItem.itemId) {
                R.id.menu_financial_guide -> { showDetailedFinancialGuide(); true }
                R.id.menu_export_pdf -> { generateBusinessReportPDF(); true }
                R.id.menu_settings_resumen -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        binding.btnRefreshAiAnalysis.setOnClickListener {
            generateAiAnalysis()
        }
        
        setupCharts()
        loadStatistics()
    }

    private fun showDetailedFinancialGuide() {
        val guideText = """
            <b>📊 Guía Financiera Esencial</b><br><br>
            
            <b>1. Control de Ingresos y Ventas</b><br>
            • <b>Venta Total:</b> Dinero bruto que ingresa (efectivo, transferencias, tarjetas).<br>
            • <b>Ticket Promedio:</b> Cuánto gasta cada cliente por compra. Ayuda a decidir si vender más cantidad o subir el valor por cliente.<br><br>
            
            <b>2. Costos y Gastos</b><br>
            • <b>Costo de Ventas:</b> Lo que te cuesta comprar la mercadería más gastos directos (traslado, empaque).<br>
            • <b>Gastos Operativos:</b> Gastos fijos (alquiler, luz) y variables (comisiones, delivery).<br><br>
            
            <b>3. Márgenes de Ganancia</b><br>
            • <b>Ganancia Bruta:</b> Diferencia entre precio de venta y costo de adquisición.<br>
            • <b>Utilidad Neta:</b> Dinero real en bolsa después de pagar absolutamente todo.<br><br>
            
            <b>4. Control de Inventario</b><br>
            • <b>Inversión en Stock:</b> Dinero "parado" en mercadería. Saber qué rota rápido es clave para no estancar el capital.<br><br>
            
            <i>💡 Un negocio sano busca maximizar la utilidad controlando los gastos y optimizando las ventas.</i>
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Interpretación de Estadísticas")
            .setMessage(android.text.Html.fromHtml(guideText, android.text.Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun generateBusinessReportPDF() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
            val businessName = prefs.getString("business_name", "Mi Negocio")
            val currency = prefs.getString("currency_symbol", "S/")

            val sales = database.saleDao().allSales
            val expenses = database.expenseDao().getAllExpenses()
            
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint()
            val titlePaint = android.graphics.Paint()
            
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // Título dinámico
            titlePaint.textSize = 24f
            titlePaint.isFakeBoldText = true
            canvas.drawText("REPORTE FINANCIERO - $businessName", 50f, 60f, titlePaint)
            
            paint.textSize = 12f
            canvas.drawText("Fecha de emisión: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}", 50f, 90f, paint)

            // Resumen Numérico
            var y = 140f
            paint.isFakeBoldText = true
            canvas.drawText("RESUMEN GENERAL", 50f, y, paint)
            paint.isFakeBoldText = false
            y += 30f
            
            val brut = sales.sumOf { it.total - (it.costoUnitario * it.cantidad) }
            val gast = expenses.sumOf { it.monto }
            
            canvas.drawText("Ventas Totales: $currency ${String.format("%.2f", sales.sumOf { it.total })}", 50f, y, paint)
            y += 20f
            canvas.drawText("Ganancia Bruta: $currency ${String.format("%.2f", brut)}", 50f, y, paint)
            y += 20f
            canvas.drawText("Gastos Totales: $currency ${String.format("%.2f", gast)}", 50f, y, paint)
            y += 30f
            
            paint.isFakeBoldText = true
            paint.textSize = 16f
            canvas.drawText("UTILIDAD NETA: $currency ${String.format("%.2f", brut - gast)}", 50f, y, paint)
            
            // Listado de Ventas Recientes
            y += 60f
            paint.textSize = 12f
            canvas.drawText("DETALLE DE ÚLTIMAS VENTAS", 50f, y, paint)
            y += 25f
            paint.isFakeBoldText = false
            
            for (sale in sales.take(15)) {
                canvas.drawText("${sale.nombreProducto} (x${sale.cantidad})", 50f, y, paint)
                canvas.drawText("$currency ${String.format("%.2f", sale.total)}", 450f, y, paint)
                y += 20f
                if (y > 800) break
            }

            pdfDocument.finishPage(page)

            val file = java.io.File(getExternalFilesDir(null), "Reporte_Negocio.pdf")
            try {
                pdfDocument.writeTo(file.outputStream())
                pdfDocument.close()
                withContext(Dispatchers.Main) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@ResumenActivity, "$packageName.provider", file)
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "application/pdf"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(intent, "Compartir Reporte PDF"))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setupCharts() {
        // Estética básica para los gráficos
        binding.pieChartCategorias.description.isEnabled = false
        
        // Configuración de la Leyenda
        val legend = binding.pieChartCategorias.legend
        legend.isEnabled = true
        legend.verticalAlignment = Legend.LegendVerticalAlignment.CENTER
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        legend.orientation = Legend.LegendOrientation.VERTICAL
        legend.setDrawInside(false)
        legend.textColor = Color.GRAY
        legend.textSize = 10f
        legend.form = Legend.LegendForm.CIRCLE
        
        binding.pieChartCategorias.setHoleColor(Color.TRANSPARENT)
        binding.pieChartCategorias.setEntryLabelColor(Color.TRANSPARENT) // Ocultar etiquetas sobre el gráfico para evitar desorden
        
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
                // 1. Obtener datos de la DB
                val sales = withContext(Dispatchers.IO) { database.saleDao().allSales }
                val products = withContext(Dispatchers.IO) { database.productDao().allProducts }
                val expenses = withContext(Dispatchers.IO) { database.expenseDao().getAllExpenses() }

                // 2. Calcular métricas básicas
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

                // 3. Procesar datos para gráficos
                val entriesPie = sales.groupBy { it.categoria }
                    .map { PieEntry(it.value.sumOf { s -> s.total }.toFloat(), it.key) }
                
                // Ventas y Gastos por día de la semana
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

                // 4. Identificar productos estrella (Top 3)
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

                // Guardar para IA
                currentVentas = ventasTotales
                currentUtilidad = gananciaBruta
                currentGastos = gastosTotales
                currentDeudores = totalDeudores
                currentDeudas = totalDeudasPropias

                // 5. Actualizar UI
                updateUI(ventasTotales, gananciaBruta, gastosTotales, utilidadNeta, inversionTotal, valorInventario, topProducts, ticketPromedio, costoVentas, totalDeudores, totalDeudasPropias)
                updateChartsUI(entriesPie, entriesSales, entriesExpenses, days)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateAiAnalysis() {
        val range = binding.tvCurrentFilterRange.text.toString()
        
        binding.progressAiAnalysis.visibility = android.view.View.VISIBLE
        binding.tvAiAnalysisContent.alpha = 0.5f

        lifecycleScope.launch {
            val analysis = com.naxor.app.util.GeminiHelper.getBusinessAnalysis(
                currentVentas, currentUtilidad, currentGastos, currentDeudores, currentDeudas, range
            )
            withContext(Dispatchers.Main) {
                binding.tvAiAnalysisContent.text = analysis
                binding.tvAiAnalysisContent.alpha = 1.0f
                binding.progressAiAnalysis.visibility = android.view.View.GONE
            }
        }
    }

    private fun updateUI(ventas: Double, bruta: Double, gastos: Double, neta: Double, inversion: Double, valorInv: Double, top: List<Pair<String, Int>>, ticket: Double, cogs: Double, deudores: Double, deudas: Double) {
        val currency = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("currency_symbol", "S/")
        
        binding.tvVentasTotalesResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", ventas)}"
        binding.tvUtilidadNeta.text = "$currency ${String.format(Locale.getDefault(), "%.2f", neta)}"
        binding.tvGastosTotalesResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", gastos)}"
        
        binding.tvInversionResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", inversion)}"
        binding.tvValorInventarioResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", valorInv)}"
        binding.tvGananciaResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", bruta)}"
        
        // Nuevas métricas de la guía
        binding.tvTicketPromedio.text = "$currency ${String.format(Locale.getDefault(), "%.2f", ticket)}"
        binding.tvCostoVentas.text = "$currency ${String.format(Locale.getDefault(), "%.2f", cogs)}"
        
        binding.tvTotalDeudoresResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", deudores)}"
        binding.tvTotalDeudasPropiasResumen.text = "$currency ${String.format(Locale.getDefault(), "%.2f", deudas)}"
        
        binding.tvUtilidadNeta.setTextColor(if(neta >= 0) resources.getColor(R.color.emerald_700, theme) else resources.getColor(R.color.red_700, theme))

        if (top.isNotEmpty()) {
            binding.layoutTopProducts.removeAllViews()
            for (item in top) {
                val itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, binding.layoutTopProducts, false)
                val text1 = itemView.findViewById<TextView>(android.R.id.text1)
                val text2 = itemView.findViewById<TextView>(android.R.id.text2)
                text1.text = item.first
                text1.setTextColor(resources.getColor(R.color.slate_900, theme))
                text2.text = "Vendidos: ${item.second} unidades"
                text2.setTextColor(resources.getColor(R.color.slate_500, theme))
                binding.layoutTopProducts.addView(itemView)
            }
        }
    }

    private fun updateChartsUI(pieEntries: List<PieEntry>, salesEntries: List<BarEntry>, expensesEntries: List<BarEntry>, days: Array<String>) {
        // Pie Chart
        val pieDataSet = PieDataSet(pieEntries, "")
        val colors = listOf(
            Color.parseColor("#0284C7"), // Sky
            Color.parseColor("#10B981"), // Emerald
            Color.parseColor("#F59E0B"), // Amber
            Color.parseColor("#6366F1"), // Indigo
            Color.parseColor("#F43F5E")  // Rose
        )
        pieDataSet.colors = colors
        pieDataSet.valueTextSize = 12f
        pieDataSet.valueTextColor = Color.WHITE
        pieDataSet.sliceSpace = 3f
        
        binding.pieChartCategorias.data = PieData(pieDataSet)
        binding.pieChartCategorias.animateY(1000)
        binding.pieChartCategorias.invalidate()

        // Bar Chart (Agrupado: Ventas vs Gastos)
        val salesDataSet = BarDataSet(salesEntries, "Ventas")
        salesDataSet.color = Color.parseColor("#0284C7") // Azul
        salesDataSet.valueTextSize = 0f // Ocultar valores sobre barras para evitar desorden

        val expensesDataSet = BarDataSet(expensesEntries, "Gastos")
        expensesDataSet.color = Color.parseColor("#F43F5E") // Rojo
        expensesDataSet.valueTextSize = 0f

        val barData = BarData(salesDataSet, expensesDataSet)
        val barWidth = 0.35f
        val barSpace = 0.05f
        val groupSpace = 0.20f
        
        barData.barWidth = barWidth
        binding.barChartSemana.data = barData
        
        // Configurar Eje X para grupos
        binding.barChartSemana.xAxis.valueFormatter = IndexAxisValueFormatter(days)
        binding.barChartSemana.xAxis.setCenterAxisLabels(true)
        binding.barChartSemana.xAxis.granularity = 1f
        binding.barChartSemana.xAxis.axisMinimum = 0f
        binding.barChartSemana.xAxis.axisMaximum = 7f
        
        binding.barChartSemana.groupBars(0f, groupSpace, barSpace)
        binding.barChartSemana.animateY(1000)
        
        // Habilitar Leyenda para el BarChart
        val barLegend = binding.barChartSemana.legend
        barLegend.isEnabled = true
        barLegend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        barLegend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        
        binding.barChartSemana.invalidate()
    }
}

