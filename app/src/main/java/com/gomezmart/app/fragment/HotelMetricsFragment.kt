package com.gomezmart.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.util.GeminiHelper
import com.google.android.material.datepicker.MaterialDatePicker
import com.gomezmart.app.BuildConfig
import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.adapter.HotelMovementAdapter
import com.gomezmart.app.adapter.HotelMovementItem
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelBookingEntity
import com.gomezmart.app.data.HotelPaymentEntity
import com.gomezmart.app.data.MovementLogEntity
import com.gomezmart.app.databinding.FragmentHotelMetricsBinding
import kotlinx.coroutines.Dispatchers
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupAI()
        setFilterToToday()
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
        
        // Manejar el click de forma independiente para evitar bloqueos o duplicidad
        binding.chipCustom.setOnClickListener {
            // Aseguramos que el chip esté marcado antes de abrir el calendario
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
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
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
            
            // Inicio del día seleccionado
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            startDate = cal.timeInMillis
            
            // Fin del día seleccionado
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            endDate = cal.timeInMillis
            
            // Actualizar el texto del Chip con la fecha elegida
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            binding.chipCustom.text = sdf.format(Date(selection))
            
            loadMetrics()
        }
        
        // Si el usuario cancela, no hacemos nada y el chip se mantiene en su estado
        picker.show(childFragmentManager, "DATE_PICKER")
    }

    private fun setupAI() {
        binding.btnRefreshAI.setOnClickListener {
            val res = lastResult ?: return@setOnClickListener
            analyzeWithAI(res)
        }
    }

    private fun analyzeWithAI(result: FinancialResult) {
        binding.tvAICounsel.text = "Gemini está analizando tus datos... 🧠"
        binding.btnRefreshAI.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val prompt = """
                    Actúa como un experto consultor financiero para hoteles. 
                    Analiza estos datos del periodo actual:
                    - Ingresos Totales: S/ ${result.totalIncome}
                    - Egresos Totales: S/ ${result.totalExpense}
                    - Utilidad Neta: S/ ${result.netProfit}
                    - Número de movimientos: ${result.movements.size}
                    
                    Dame 3 consejos breves, estratégicos y motivadores para mejorar el rendimiento. 
                    Usa un lenguaje cercano y profesional. Responde en español y usa emojis.
                """.trimIndent()

                // Se usa GeminiHelper (cascada de modelos + reintentos + llamada HTTP
                // directa). El SDK legacy "generativeai" usaba el modelo retirado
                // gemini-1.5-flash y además arrastraba ~2 MB de dex innecesarios.
                val response = GeminiHelper.askRaw(requireContext(), prompt)
                binding.tvAICounsel.text = response
                    ?: "No pude generar un análisis en este momento. Inténtalo de nuevo en unos segundos."
            } catch (e: Exception) {
                binding.tvAICounsel.text = "Error al conectar con la IA. Inténtalo de nuevo en unos segundos."
            } finally {
                binding.btnRefreshAI.isEnabled = true
            }
        }
    }

    private fun setupRecyclerView() {
        binding.rvFinancialMovements.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFinancialMovements.adapter = adapter
    }

    private fun loadMetrics() {
        val dbInstance = database ?: return
        lifecycleScope.launch {
            combine(
                dbInstance.hotelDao().getAllPaymentsFlow(),
                dbInstance.hotelDao().getAllBookings(),
                dbInstance.movementLogDao().getAllLogsFlow()
            ) { p, b, l -> processFinancialData(p, b, l) }.collect { result ->
                lastResult = result
                updateUI(result)
            }
        }
    }

    private fun processFinancialData(
        payments: List<HotelPaymentEntity>,
        bookings: List<HotelBookingEntity>,
        logs: List<MovementLogEntity>
    ): FinancialResult {
        val fP = payments.filter { it.timestamp in startDate..endDate }
        val fB = bookings.filter { it.timestamp in startDate..endDate }
        val fL = logs.filter { it.timestamp in startDate..endDate }

        val income = fP.sumOf { it.amount } + fB.sumOf { it.deposit }
        val expense = fL.filter { it.type.contains("EXPENSE") || it.type.contains("GASTO") }
            .sumOf { it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0 }

        val movements = mutableListOf<HotelMovementItem>()
        fP.forEach { movements.add(HotelMovementItem(it.id, "PAYMENT", "Pago Recibido", it.amount, it.timestamp)) }
        fB.forEach { if (it.deposit > 0) movements.add(HotelMovementItem(it.id + "_dep", "PAYMENT", "Anticipo: ${it.guestName}", it.deposit, it.timestamp)) }
        fL.forEach { val amount = it.value.replace("S/", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0; movements.add(HotelMovementItem(it.id.toString(), "CHARGE", it.title, amount, it.timestamp)) }

        return FinancialResult(income, expense, income - expense, movements.sortedByDescending { it.timestamp })
    }

    private fun updateUI(result: FinancialResult) {
        _binding?.let { b ->
            b.tvTotalIncome.text = String.format(Locale.US, "S/ %.2f", result.totalIncome)
            b.tvTotalExpense.text = String.format(Locale.US, "S/ %.2f", result.totalExpense)
            b.tvNetProfit.text = String.format(Locale.US, "S/ %.2f", result.netProfit)
            b.tvNetProfit.setTextColor(requireContext().getColor(if (result.netProfit < 0) R.color.red_600 else R.color.emerald_600))
            adapter.submitList(result.movements)
            setupChart(result)
        }
    }

    private fun setupChart(result: FinancialResult) {
        val entries = mutableListOf<BarEntry>()
        entries.add(BarEntry(0f, result.totalIncome.toFloat()))
        entries.add(BarEntry(1f, result.totalExpense.toFloat()))
        val dataSet = BarDataSet(entries, "Flujo")
        dataSet.colors = listOf(requireContext().getColor(R.color.emerald_600), requireContext().getColor(R.color.red_600))
        binding.barChartFinancial.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(listOf("Ingresos", "Egresos"))
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            animateY(800)
            invalidate()
        }
    }

    data class FinancialResult(val totalIncome: Double, val totalExpense: Double, val netProfit: Double, val movements: List<HotelMovementItem>)
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
