package com.gomezmart.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanClientEntity
import com.gomezmart.app.data.LoanClientWithDebt
import com.gomezmart.app.databinding.ActivityLoansClientsBinding
import com.gomezmart.app.databinding.ItemLoanClientBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class LoansClientsActivity : BaseActivity() {

    private lateinit var binding: ActivityLoansClientsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ClientsAdapter
    private var currentFilter = "ACTIVE"
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Sincronizado"
    private val registrations = mutableListOf<ListenerRegistration>()
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoansClientsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ajuste adaptativo: evita que las barras del sistema tapen la cabecera y el listado
        applyWindowInsetsMargins(binding.appBarLoansClients, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.rvLoanClients, applyTop = false, applyBottom = true)

        val pickMode = intent.getBooleanExtra("PICK_MODE", false)
        if (pickMode) {
            binding.toolbarClients.title = "Seleccionar Cliente"
            binding.chipFilterAll.isChecked = true
            currentFilter = "ALL"
        } else {
            binding.chipFilterActive.isChecked = true
            currentFilter = "ACTIVE"
        }

        binding.toolbarClients.setNavigationOnClickListener { finish() }
        setupRecyclerView(pickMode)
        setupSearch()
        setupFilters()
        setupSyncIndicator()
        
        val sm = SyncManager(this)
        sm.startRealtimeLoanClientsSync { loadClients(binding.etSearchClients.text.toString()) }?.let { registrations.add(it) }
        sm.startRealtimeLoansSync { loadClients(binding.etSearchClients.text.toString()) }?.let { registrations.add(it) }
        sm.startRealtimeInstallmentsSync { loadClients(binding.etSearchClients.text.toString()) }?.let { registrations.add(it) }
        sm.startRealtimeLoanPaymentsSync { loadClients(binding.etSearchClients.text.toString()) }?.let { registrations.add(it) }

        loadClients()
    }

    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            loadClients(binding.etSearchClients.text.toString())
        }
    }

    private fun setupSyncIndicator() {
        // Monitorear Red
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isNetworkAvailable = true
                runOnUiThread { updateSyncIconState() }
            }
            override fun onLost(network: android.net.Network) {
                isNetworkAvailable = false
                runOnUiThread { updateSyncIconState() }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback)

        // Monitorear Cambios Locales
        lifecycleScope.launch {
            database.loanDao().getUnsyncedCount().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        // Monitorear Trabajo de Sincronización
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("offline_sync")
            .observe(this) { infoList ->
                val isSyncing = infoList != null && infoList.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
                updateSyncIconState(isSyncing)
            }

        binding.btnSyncIndicatorClients.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sincronización de Cartera")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus\n\nLos datos de tus clientes y préstamos se guardan automáticamente en la nube cuando tienes internet.")
                .setPositiveButton("Sincronizar ahora") { _, _ -> forceSync() }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun forceSync() {
        Toast.makeText(this, "Iniciando sincronización...", Toast.LENGTH_SHORT).show()
        SyncManager(this).scheduleOfflineSync()
    }

    private fun updateSyncIconState(isSyncing: Boolean = currentIsSyncing) {
        currentIsSyncing = isSyncing
        val color: Int
        val iconRes: Int
        val animation: android.view.animation.Animation?
        
        when {
            isSyncing && isNetworkAvailable -> {
                syncStatusText = "Sincronizando..."
                color = android.graphics.Color.parseColor("#3B82F6") // Azul (Sincronizando)
                iconRes = android.R.drawable.stat_notify_sync
                animation = android.view.animation.RotateAnimation(0f, 360f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión (modo local)"
                color = android.graphics.Color.parseColor("#DC2626") // Rojo (Error/Desconectado)
                iconRes = android.R.drawable.ic_menu_upload
                animation = null
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6") // Azul (Datos por subir)
                iconRes = android.R.drawable.stat_notify_sync
                animation = null
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#3B82F6") // Azul (Todo OK)
                iconRes = android.R.drawable.stat_sys_download_done
                animation = null
            }
        }

        binding.btnSyncIndicatorClients.apply {
            setImageResource(iconRes)
            setColorFilter(color)
            clearAnimation()
            if (animation != null) startAnimation(animation)
        }
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener { group, checkedIds ->
            val firstId = if (checkedIds.isNotEmpty()) checkedIds[0] else -1
            currentFilter = when(firstId) {
                R.id.chipFilterActive -> "ACTIVE"
                R.id.chipFilterOverdue -> "OVERDUE"
                R.id.chipFilterPaid -> "PAID"
                else -> "ALL"
            }
            loadClients(binding.etSearchClients.text.toString())
        }
    }

    private fun setupSearch() {
        binding.etSearchClients.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadClients(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val inputLayout = binding.etSearchClients.parent.parent as? com.google.android.material.textfield.TextInputLayout
        inputLayout?.setEndIconOnClickListener {
            startVoiceSearch()
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Diga el nombre del cliente...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "La búsqueda por voz no está disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.get(0)
            binding.etSearchClients.setText(spokenText)
        }
    }

    private fun loadClients(query: String = "") {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    database.loanDao().sanitizeAllLoans()
                } catch (e: Exception) {}
            }
            val now = System.currentTimeMillis()
            val flow = if (query.isEmpty()) database.loanDao().getAllClientsWithDebtFlow(now) 
                       else database.loanDao().searchClientsWithDebtFlow(query, now)
            
            flow.collectLatest { clientsWithDebt ->
                val resultList = withContext(Dispatchers.Default) {
                    clientsWithDebt.filter { item ->
                        val hasRealDebt = item.totalDebt > 0.01
                        val isMoroso = item.totalOverdueCount > 0

                        when(currentFilter) {
                            "ACTIVE" -> hasRealDebt
                            "OVERDUE" -> isMoroso
                            "PAID" -> !hasRealDebt && (item.totalPaidOnTimeCount > 0)
                            else -> true
                        }
                    }
                }
                adapter.submitList(resultList)
            }
        }
    }

    private fun setupRecyclerView(pickMode: Boolean) {
        adapter = ClientsAdapter { clientWithDebt ->
            val client = clientWithDebt.client
            if (pickMode) {
                val intent = Intent(this, AddLoanActivity::class.java)
                intent.putExtra("SELECTED_CLIENT_ID", client.id)
                startActivity(intent)
                finish()
            } else {
                val intent = Intent(this, LoanDetailsActivity::class.java)
                intent.putExtra("CLIENT_ID", client.id)
                startActivity(intent)
            }
        }
        binding.rvLoanClients.layoutManager = LinearLayoutManager(this)
        binding.rvLoanClients.adapter = adapter
    }

    inner class ClientsAdapter(private val onClick: (LoanClientWithDebt) -> Unit) : RecyclerView.Adapter<ClientsAdapter.ViewHolder>() {
        private var items = emptyList<LoanClientWithDebt>()
        fun submitList(newItems: List<LoanClientWithDebt>) { items = newItems; notifyDataSetChanged() }
        
        inner class ViewHolder(val b: ItemLoanClientBinding) : RecyclerView.ViewHolder(b.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemLoanClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val itemWithDebt = items[position]
            val item = itemWithDebt.client
            val balance = itemWithDebt.totalDebt
            
            val stars = when {
                itemWithDebt.totalOverdueCount > 5 -> "⭐"
                itemWithDebt.totalOverdueCount > 2 -> "⭐⭐"
                itemWithDebt.totalPaidOnTimeCount > 20 -> "⭐⭐⭐⭐⭐"
                itemWithDebt.totalPaidOnTimeCount > 10 -> "⭐⭐⭐⭐"
                else -> "⭐⭐⭐"
            }

            with(holder.b) {
                tvClientName.text = item.name

                if (!item.photoPath.isNullOrEmpty() && java.io.File(item.photoPath).exists()) {
                    ivClientPhoto.visibility = View.VISIBLE
                    tvClientInitial.visibility = View.GONE
                    com.bumptech.glide.Glide.with(holder.itemView.context)
                        .load(java.io.File(item.photoPath))
                        .circleCrop()
                        .into(ivClientPhoto)
                } else {
                    ivClientPhoto.visibility = View.GONE
                    tvClientInitial.visibility = View.VISIBLE
                    tvClientInitial.text = item.name.take(1).uppercase(Locale.ROOT)
                }

                tvClientRating.text = stars
                tvClientInfo.text = "DNI: ${item.doc} | 📱 ${item.phone}"
                tvClientPendingBalance.text = "Deuda: S/ ${String.format(Locale.US, "%.2f", balance)}"
                tvClientPendingBalance.visibility = if (balance > 0.01) View.VISIBLE else View.GONE
                
                if (itemWithDebt.totalOverdueCount >= 1) {
                    chipClientScore.text = "¡CON MORA! ⚡"
                    chipClientScore.setChipBackgroundColorResource(R.color.red_600)
                    chipClientScore.setTextColor(getColor(R.color.white))
                } else if (balance <= 0.01) {
                    chipClientScore.text = "Finalizado ✅"
                    chipClientScore.setChipBackgroundColorResource(R.color.slate_100)
                    chipClientScore.setTextColor(getColor(R.color.slate_600))
                } else {
                    chipClientScore.text = "Al día ✅"
                    chipClientScore.setChipBackgroundColorResource(R.color.emerald_50)
                    chipClientScore.setTextColor(getColor(R.color.emerald_600))
                }
                root.setOnClickListener { onClick(itemWithDebt) }
            }
        }
        override fun getItemCount() = items.size
    }
}
