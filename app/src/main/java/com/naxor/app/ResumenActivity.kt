package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.SaleEntity
import com.naxor.app.databinding.ActivityResumenBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackResumen.setOnClickListener { finish() }
        binding.btnHelpResumen.setOnClickListener { showHelpDialog() }
        binding.btnExportPdfReport.setOnClickListener { generateBusinessReportPDF() }
        setupCharts()
        loadStatistics()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Resultados")
            .setMessage("• UTILIDAD NETA: Es tu ganancia real (Ventas - Costos de productos - Gastos del local).\n" +
                    "• GRÁFICOS: Mira visualmente tus categorías más rentables y tus días de mayor venta.\n" +
                    "• PDF: Toca el icono de guardado (superior derecha) para generar un reporte financiero formal.")
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
        binding.pieChartCategorias.legend.isEnabled = false
        binding.pieChartCategorias.setHoleColor(Color.TRANSPARENT)
        
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

                val gastosTotales = expenses.sumOf { it.monto }
                val utilidadNeta = gananciaBruta - gastosTotales
                val inversionStock = products.sumOf { it.precioCosto }

                // 3. Procesar datos para gráficos
                val entriesPie = sales.groupBy { it.categoria }
                    .map { PieEntry(it.value.sumOf { s -> s.total }.toFloat(), it.key) }
                
                // Ventas por día de la semana
                val days = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
                val daySales = FloatArray(7) { 0f }
                val cal = Calendar.getInstance()
                for (sale in sales) {
                    cal.timeInMillis = sale.timestamp
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
                    daySales[dayOfWeek] += sale.total.toFloat()
                }
                val entriesBar = daySales.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }

                // 4. Identificar productos estrella (Top 3)
                val topProducts = sales.groupBy { it.nombreProducto }
                    .mapValues { entry -> entry.value.sumOf { it.cantidad } }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(3)

                // 5. Actualizar UI
                updateUI(ventasTotales, gananciaBruta, gastosTotales, utilidadNeta, inversionStock, topProducts)
                updateChartsUI(entriesPie, entriesBar, days)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateUI(ventas: Double, bruta: Double, gastos: Double, neta: Double, inversion: Double, top: List<Pair<String, Int>>) {
        binding.tvVentasTotalesResumen.text = String.format(Locale.getDefault(), "S/ %.0f", ventas)
        binding.tvGananciaResumen.text = String.format(Locale.getDefault(), "S/ %.2f", bruta)
        binding.tvGastosTotalesResumen.text = String.format(Locale.getDefault(), "S/ %.0f", gastos)
        binding.tvUtilidadNeta.text = String.format(Locale.getDefault(), "S/ %.2f", neta)
        binding.tvInversionResumen.text = String.format(Locale.getDefault(), "S/ %.0f", inversion)
        
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

    private fun updateChartsUI(pieEntries: List<PieEntry>, barEntries: List<BarEntry>, days: Array<String>) {
        // Pie Chart
        val pieDataSet = PieDataSet(pieEntries, "")
        pieDataSet.colors = ColorTemplate.COLORFUL_COLORS.toList()
        pieDataSet.valueTextSize = 12f
        pieDataSet.valueTextColor = Color.WHITE
        binding.pieChartCategorias.data = PieData(pieDataSet)
        binding.pieChartCategorias.invalidate()

        // Bar Chart
        val barDataSet = BarDataSet(barEntries, "Ventas S/")
        barDataSet.color = resources.getColor(R.color.indigo_500, theme)
        barDataSet.valueTextSize = 10f
        
        binding.barChartSemana.xAxis.valueFormatter = IndexAxisValueFormatter(days)
        binding.barChartSemana.data = BarData(barDataSet)
        binding.barChartSemana.invalidate()
    }
}

