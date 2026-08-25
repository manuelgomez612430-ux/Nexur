package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentLoansHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LoansHomeFragment : Fragment() {

    private var _binding: FragmentLoansHomeBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoansHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        loadDashboardData()
    }

    private fun setupToolbar() {
        binding.toolbarLoans.inflateMenu(R.menu.menu_loans_home)
        binding.toolbarLoans.setOnMenuItemClickListener { item: android.view.MenuItem ->
            if (item.itemId == R.id.action_export_csv) {
                exportDataToCSV()
                true
            } else false
        }
        binding.toolbarLoans.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupListeners() {
        binding.toolLoansNew.setOnClickListener { 
            startActivity(Intent(requireContext(), com.naxor.app.AddLoanActivity::class.java))
        }
        binding.toolLoansClients.setOnClickListener { 
            startActivity(Intent(requireContext(), com.naxor.app.LoansClientsActivity::class.java))
        }
        binding.cardTodayCollections.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.LoansCollectionsActivity::class.java))
        }
        binding.btnRegisterLoanExpense.setOnClickListener {
            showRegisterExpenseDialog()
        }
        binding.btnViewCollectionRoute.setOnClickListener {
            openCollectionRouteInMaps()
        }
    }

    private fun openCollectionRouteInMaps() {
        lifecycleScope.launch {
            val now = Calendar.getInstance()
            now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59)
            val pendingInstallments = database.loanDao().getPendingCollections(now.timeInMillis)
            
            if (pendingInstallments.isEmpty()) {
                Toast.makeText(requireContext(), "No hay cobros pendientes para hoy", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val clientIds = pendingInstallments.map { 
                database.loanDao().getLoanById(it.loanId)?.clientId 
            }.filterNotNull().distinct()

            val locations = clientIds.mapNotNull { 
                database.loanDao().getClientById(it) 
            }.filter { it.latitude != null && it.longitude != null }

            if (locations.isEmpty()) {
                Toast.makeText(requireContext(), "Los clientes pendientes no tienen ubicación guardada", Toast.LENGTH_LONG).show()
                return@launch
            }

            val origin = "${locations[0].latitude},${locations[0].longitude}"
            val waypoints = if (locations.size > 1) {
                "&waypoints=" + locations.drop(1).joinToString("|") { "${it.latitude},${it.longitude}" }
            } else ""

            val gmmIntentUri = android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$origin$waypoints&travelmode=driving")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        }
    }

    private fun exportDataToCSV() {
        lifecycleScope.launch(Dispatchers.IO) {
            val clients = database.loanDao().getAllClients().firstOrNull() ?: emptyList()
            val loans = database.loanDao().getAllLoans().firstOrNull() ?: emptyList()
            
            val csv = StringBuilder("ID_PRESTAMO,CLIENTE,CAPITAL,INTERES,TOTAL,ESTADO\n")
            loans.forEach { loan ->
                val clientName = clients.find { it.id == loan.clientId }?.name ?: "Desconocido"
                csv.append("${loan.id},${clientName},${loan.amount},${loan.interestRate}%,${loan.totalToPay},${loan.status}\n")
            }
            
            val file = File(requireContext().getExternalFilesDir(null), "Cartera_Naxor_${System.currentTimeMillis()}.csv")
            file.writeText(csv.toString())
            
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Exportar Cartera"))
            }
        }
    }

    private fun showRegisterExpenseDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val etConcept = android.widget.EditText(requireContext()).apply { hint = "Concepto (Gasolina, Cobrador...)" }
        val etAmount = android.widget.EditText(requireContext()).apply { 
            hint = "Monto"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        layout.addView(etConcept); layout.addView(etAmount)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Registrar Gasto de Ruta")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val concept = etConcept.text.toString()
                val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                if (concept.isNotEmpty() && amount > 0) {
                    lifecycleScope.launch {
                        database.loanDao().insertExpense(com.naxor.app.data.LoanExpenseEntity(concept = concept, amount = amount))
                        Toast.makeText(requireContext(), "Gasto registrado", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun loadDashboardData() {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
        val currency = prefs.getString("currency_symbol", "S/")

        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getCapitalInStreetFlow().collectLatest { capital ->
                binding.tvStatCapitalLent.text = String.format(Locale.US, "$currency %.2f", capital ?: 0.0)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            database.loanDao().getTotalCollectedFlow().collectLatest { total ->
                binding.tvStatInterests.text = String.format(Locale.US, "$currency %.2f", total ?: 0.0)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val totalCollectedFlow = database.loanDao().getTotalCollectedFlow()
            val totalExpensesFlow = database.loanDao().getTotalExpensesFlow()
            val totalLateFeesFlow = database.loanDao().getTotalLateFeesFlow()
            
            kotlinx.coroutines.flow.combine(totalCollectedFlow, totalExpensesFlow, totalLateFeesFlow) { collected, expenses, lateFees ->
                Triple(collected ?: 0.0, expenses ?: 0.0, lateFees ?: 0.0)
            }.collectLatest { (collected, expenses, lateFees) ->
                val netProfit = collected + lateFees - expenses
                binding.tvStatNetProfit.text = String.format(Locale.US, "$currency %.2f", netProfit)
            }
        }

        setupWeeklyChart(currency)
    }

    private fun setupWeeklyChart(currency: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val now = Calendar.getInstance()
            now.set(Calendar.HOUR_OF_DAY, 23); now.set(Calendar.MINUTE, 59)
            val end = now.timeInMillis
            now.add(Calendar.DAY_OF_YEAR, -6)
            now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
            val start = now.timeInMillis

            database.loanDao().getPaidInstallmentsInRange(start, end).collectLatest { installments ->
                val entries = mutableListOf<com.github.mikephil.charting.data.BarEntry>()
                val days = mutableListOf<String>()
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                
                val cal = Calendar.getInstance()
                cal.timeInMillis = start
                
                for (i in 0..6) {
                    val dayStart = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                    val dayEnd = cal.timeInMillis
                    
                    val dailyTotal = installments.filter { it.dueDate in dayStart..dayEnd }.sumOf { it.amountPaid + it.lateFeePaid }
                    entries.add(com.github.mikephil.charting.data.BarEntry(i.toFloat(), dailyTotal.toFloat()))
                    days.add(sdf.format(Date(dayStart)))
                    
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                }

                withContext(Dispatchers.Main) {
                    val dataSet = com.github.mikephil.charting.data.BarDataSet(entries, "Recaudación")
                    dataSet.color = requireContext().getColor(R.color.emerald_600)
                    dataSet.setDrawValues(true)
                    val barData = com.github.mikephil.charting.data.BarData(dataSet)
                    binding.chartWeeklyCollection.data = barData
                    binding.chartWeeklyCollection.xAxis.valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(days)
                    binding.chartWeeklyCollection.animateY(1000)
                    binding.chartWeeklyCollection.invalidate()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
