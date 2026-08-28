package com.naxor.app

import android.content.Intent
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

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCajaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackCaja.setOnClickListener { finish() }
        binding.btnHelpCaja.setOnClickListener { showHelpDialog() }
        binding.btnMailboxCaja.setOnClickListener {
            startActivity(Intent(this, MailboxActivity::class.java))
        }
        setupNetworkMonitoring()
        setupSyncIndicator()
        checkSession()
        
        SyncManager(this).startRealtimeCashSync { checkSession() }
    }

    private fun setupNetworkMonitoring() {
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    isNetworkAvailable = true
                    updateSyncIconState()
                }
            }

            override fun onLost(network: android.net.Network) {
                runOnUiThread {
                    isNetworkAvailable = false
                    updateSyncIconState()
                }
            }
        })
        
        val activeInfo = connectivityManager.activeNetworkInfo
        isNetworkAvailable = activeInfo != null && activeInfo.isConnected
    }

    private fun setupSyncIndicator() {
        database.cashDao().getUnsyncedCount().observe(this) { count ->
            lastUnsyncedCount = count ?: 0
            updateSyncIconState()
        }

        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("offline_sync")
            .observe(this) { infoList ->
                val isSyncing = infoList != null && infoList.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                updateSyncIconState(isSyncing)
            }

        binding.btnSyncIndicator.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            AlertDialog.Builder(this)
                .setTitle("Sincronización")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus\n\n¿Deseas forzar la subida de todos los datos ahora?")
                .setPositiveButton("Sincronizar Todo") { _, _ -> forceFullUpload() }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState(isSyncing: Boolean = currentIsSyncing) {
        currentIsSyncing = isSyncing
        val color: Int
        val animation: android.view.animation.Animation?
        val iconRes: Int
        
        when {
            isSyncing && isNetworkAvailable -> {
                syncStatusText = "Sincronizando..."
                color = androidx.core.content.ContextCompat.getColor(this, R.color.white)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = android.graphics.Color.parseColor("#FFCDD2")
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#FFF9C4")
                iconRes = android.R.drawable.stat_notify_sync
                animation = null
            }
            else -> {
                syncStatusText = "Todo sincronizado"
                color = android.graphics.Color.WHITE
                iconRes = android.R.drawable.stat_sys_download_done
                animation = null
            }
        }

        binding.btnSyncIndicator.apply {
            setImageResource(iconRes)
            setColorFilter(color)
            clearAnimation()
            if (animation != null) startAnimation(animation)
        }
    }

    private fun forceFullUpload() {
        Toast.makeText(this, "Iniciando subida forzada...", Toast.LENGTH_SHORT).show()
        SyncManager(this).uploadAllLocalToCloud {
            Toast.makeText(this, "Sincronización finalizada", Toast.LENGTH_SHORT).show()
            updateSyncIconState()
        }
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
            binding.tvCajaStatus.text = "Estado: CERRADA 🔴"
            binding.tvCajaPrompt.text = "Ingresa el monto inicial para abrir el día:"
            binding.etCajaMonto.text?.clear()
            binding.btnCajaAction.text = "Abrir Caja"
            binding.btnCajaAction.setIconResource(android.R.drawable.ic_menu_add)
            binding.layoutCajaResumen.visibility = View.GONE
            binding.btnCajaAction.setOnClickListener { openCaja() }
        } else {
            binding.tvCajaStatus.text = "Estado: EN PROCESO 🟢"
            binding.tvCajaPrompt.text = "Ingresa el total de DINERO FÍSICO contado para CERRAR:"
            binding.btnCajaAction.text = "Cerrar Caja"
            binding.btnCajaAction.setIconResource(android.R.drawable.ic_lock_power_off)
            binding.layoutCajaResumen.visibility = View.VISIBLE
            loadRealTimeData(session)
            binding.btnCajaAction.setOnClickListener { closeCaja(session) }
        }
        
        binding.btnVerHistorialCaja.setOnClickListener {
            Toast.makeText(this, "Historial de Cierres (Próximamente)", Toast.LENGTH_SHORT).show()
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
            val session = CashSessionEntity(initialAmount = monto, isSynced = false)
            database.cashDao().insert(session)
            // Note: Since session ID is auto-generated, we might need the generated ID to sync it correctly.
            // But for now we rely on the next sync cycle or we can fetch the inserted session.
            val inserted = database.cashDao().getOpenSession()
            if (inserted != null) {
                SyncManager(this@CajaActivity).syncCashSessionToCloud(inserted)
            }
            SyncManager(this@CajaActivity).scheduleOfflineSync()
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
                        val updatedSession = session.copy(
                            isOpen = false,
                            endTime = System.currentTimeMillis(),
                            totalSales = sales.sumOf { it.total },
                            totalExpenses = totalExpenses,
                            actualAmount = physicalAmount,
                            isSynced = false
                        )
                        database.cashDao().update(updatedSession)
                        SyncManager(this@CajaActivity).syncCashSessionToCloud(updatedSession)
                        SyncManager(this@CajaActivity).scheduleOfflineSync()
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
