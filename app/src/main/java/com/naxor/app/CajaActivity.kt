package com.naxor.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.CashSessionEntity
import com.naxor.app.databinding.ActivityCajaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CajaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCajaBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var currentSession: CashSessionEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCajaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackCaja.setOnClickListener { finish() }
        binding.btnHelpCaja.setOnClickListener { showHelpDialog() }
        checkSession()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Arqueo de Caja")
            .setMessage("• DINERO FÍSICO: Es el dinero que tienes en monedas y billetes. Se calcula sumando la base inicial + ventas en efectivo - gastos registrados.\n" +
                    "• CUENTAS DIGITALES: Es lo que deberías tener en tu Yape, Plin o cuenta bancaria.\n" +
                    "• CIERRE: Al cerrar, solo debes contar el dinero físico. La app te avisará si coincide con lo esperado en el cajón.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun checkSession() {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { database.cashDao().getOpenSession() }
            currentSession = session
            updateUI(session)
        }
    }

    private fun updateUI(session: CashSessionEntity?) {
        if (session == null) {
            binding.tvCajaStatus.text = "Estado: CERRADA"
            binding.tvCajaPrompt.text = "Ingresa el monto inicial para abrir el día:"
            binding.etCajaMonto.text?.clear()
            binding.btnCajaAction.text = "Abrir Caja"
            binding.layoutCajaResumen.visibility = View.GONE
            binding.btnCajaAction.setOnClickListener { openCaja() }
        } else {
            binding.tvCajaStatus.text = "Estado: ABIERTA"
            binding.tvCajaPrompt.text = "Cuenta el dinero físico (billetes/monedas) y escribe el total para CERRAR:"
            binding.btnCajaAction.text = "Cerrar Caja"
            binding.layoutCajaResumen.visibility = View.VISIBLE
            loadRealTimeData(session)
            binding.btnCajaAction.setOnClickListener { closeCaja(session) }
        }
    }

    private fun loadRealTimeData(session: CashSessionEntity) {
        lifecycleScope.launch {
            val sales = withContext(Dispatchers.IO) { database.saleDao().allSales.filter { it.timestamp >= session.startTime } }
            val expenses = withContext(Dispatchers.IO) { database.expenseDao().getAllExpenses().filter { it.fecha >= session.startTime } }
            
            val totalCash = sales.filter { it.paymentMethod == "EFECTIVO" }.sumOf { it.total }
            val totalDigital = sales.filter { it.paymentMethod == "DIGITAL" }.sumOf { it.total }
            val totalCard = sales.filter { it.paymentMethod == "TARJETA" }.sumOf { it.total }
            val totalExpenses = expenses.sumOf { it.monto }
            
            val expectedInDrawer = session.initialAmount + totalCash - totalExpenses

            binding.tvCajaBase.text = String.format("S/ %.2f", session.initialAmount)
            binding.tvCajaVentasEfectivo.text = String.format("+ S/ %.2f", totalCash)
            binding.tvCajaGastos.text = String.format("- S/ %.2f", totalExpenses)
            binding.tvCajaEsperado.text = String.format("S/ %.2f", expectedInDrawer)
            
            binding.tvCajaDigital.text = String.format("S/ %.2f", totalDigital)
            binding.tvCajaTarjeta.text = String.format("S/ %.2f", totalCard)
        }
    }

    private fun openCaja() {
        val monto = binding.etCajaMonto.text.toString().toDoubleOrNull() ?: 0.0
        lifecycleScope.launch(Dispatchers.IO) {
            database.cashDao().insert(CashSessionEntity(initialAmount = monto))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CajaActivity, "¡Caja abierta con éxito!", Toast.LENGTH_SHORT).show()
                checkSession()
            }
        }
    }

    private fun closeCaja(session: CashSessionEntity) {
        val physicalAmount = binding.etCajaMonto.text.toString().toDoubleOrNull() ?: 0.0
        
        lifecycleScope.launch {
            val sales = withContext(Dispatchers.IO) { database.saleDao().allSales.filter { it.timestamp >= session.startTime } }
            val expenses = withContext(Dispatchers.IO) { database.expenseDao().getAllExpenses().filter { it.fecha >= session.startTime } }
            
            val totalCash = sales.filter { it.paymentMethod == "EFECTIVO" }.sumOf { it.total }
            val totalDigital = sales.filter { it.paymentMethod == "DIGITAL" }.sumOf { it.total }
            val totalCard = sales.filter { it.paymentMethod == "TARJETA" }.sumOf { it.total }
            val totalExpenses = expenses.sumOf { it.monto }
            
            val expectedInDrawer = session.initialAmount + totalCash - totalExpenses
            val diff = physicalAmount - expectedInDrawer

            val resultMsg = if (diff == 0.0) "¡Caja perfecta! Todo coincide."
            else if (diff > 0) "Sobran S/ ${String.format("%.2f", diff)}"
            else "Faltan S/ ${String.format("%.2f", Math.abs(diff))}"

            AlertDialog.Builder(this@CajaActivity)
                .setTitle("Confirmar Cierre")
                .setMessage("Resumen Físico:\n- Esperado en cajón: S/ ${String.format("%.2f", expectedInDrawer)}\n- Contado por ti: S/ ${String.format("%.2f", physicalAmount)}\n\n$resultMsg\n\nResumen Digital (Banco):\n- Yape/Plin: S/ ${String.format("%.2f", totalDigital)}\n- Tarjeta: S/ ${String.format("%.2f", totalCard)}\n\n¿Deseas finalizar el día?")
                .setPositiveButton("Sí, Cerrar") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        session.isOpen = false
                        session.endTime = System.currentTimeMillis()
                        session.totalSales = sales.sumOf { it.total }
                        session.totalExpenses = totalExpenses
                        session.actualAmount = physicalAmount
                        database.cashDao().update(session)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@CajaActivity, "Día finalizado", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
                .setNegativeButton("Revisar de nuevo", null)
                .show()
        }
    }
}
