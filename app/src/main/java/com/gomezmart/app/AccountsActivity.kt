package com.gomezmart.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.gomezmart.app.data.AccountEntity
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.ActivityAccountsBinding
import com.gomezmart.app.databinding.ItemAccountBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

class AccountsActivity : BaseActivity() {

    private lateinit var binding: ActivityAccountsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: AccountsAdapter
    private var isNetworkAvailable = true
    private var lastUnsyncedCount = 0
    private var syncStatusText = "Sincronizado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.appBarAccounts, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.fabAddAccount, applyTop = false, applyBottom = true)

        binding.toolbarAccounts.setNavigationOnClickListener { finish() }

        setupSyncIndicator()
        setupRecyclerView()
        loadAccounts()

        binding.fabAddAccount.setOnClickListener { showAddEditAccountDialog(null) }
    }

    private fun setupSyncIndicator() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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

        lifecycleScope.launch {
            database.accountDao().getUnsyncedCountFlow().collectLatest { count ->
                lastUnsyncedCount = count
                updateSyncIconState()
            }
        }

        binding.btnSyncIndicatorAccounts.setOnClickListener {
            val networkStatus = if (isNetworkAvailable) "📡 Conectado" else "📵 Sin Internet"
            AlertDialog.Builder(this)
                .setTitle("Sincronización de Cuentas")
                .setMessage("Estado: $syncStatusText\nRed: $networkStatus")
                .setPositiveButton("Sincronizar ahora") { _, _ ->
                    SyncManager(this).scheduleOfflineSync()
                }
                .setNegativeButton("Cerrar", null).show()
        }
    }

    private fun updateSyncIconState() {
        val color: Int
        val iconRes: Int
        when {
            !isNetworkAvailable -> {
                syncStatusText = if (lastUnsyncedCount > 0) "Sin internet ($lastUnsyncedCount pendientes)" else "Sin conexión"
                color = android.graphics.Color.parseColor("#DC2626")
                iconRes = android.R.drawable.ic_menu_upload
            }
            lastUnsyncedCount > 0 -> {
                syncStatusText = "Hay $lastUnsyncedCount cambios pendientes"
                color = android.graphics.Color.parseColor("#3B82F6")
                iconRes = android.R.drawable.stat_notify_sync
            }
            else -> {
                syncStatusText = "Datos a salvo en la nube"
                color = android.graphics.Color.parseColor("#059669")
                iconRes = android.R.drawable.stat_sys_download_done
            }
        }
        binding.btnSyncIndicatorAccounts.apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }
    }

    private fun setupRecyclerView() {
        adapter = AccountsAdapter(
            onEdit = { account -> showAddEditAccountDialog(account) },
            onDelete = { account -> showDeleteConfirmation(account) }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter
    }

    private fun loadAccounts() {
        lifecycleScope.launch {
            launch {
                database.accountDao().getAllAccounts().collectLatest { accounts ->
                    adapter.submitList(accounts)
                }
            }
            launch {
                database.accountDao().getTotalBalanceFlow().collectLatest { total ->
                    val sum = total ?: 0.0
                    binding.tvTotalAccountsBalance.text = "S/ ${String.format(Locale.US, "%.2f", sum)}"
                }
            }
        }
    }

    private fun showAddEditAccountDialog(accountToEdit: AccountEntity?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_account, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvAccountDialogTitle)
        val toggleOrigin = dialogView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleAccountOrigin)
        val etName = dialogView.findViewById<EditText>(R.id.etAccountName)
        val etBank = dialogView.findViewById<EditText>(R.id.etAccountBank)
        val etNumber = dialogView.findViewById<EditText>(R.id.etAccountNumber)
        val etBalance = dialogView.findViewById<EditText>(R.id.etAccountBalance)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAccount)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveAccount)

        if (accountToEdit != null) {
            tvTitle.text = "Editar Cuenta 💳"
            if (accountToEdit.origin == "PERSONAL") {
                toggleOrigin?.check(R.id.btnOriginPersonal)
            } else {
                toggleOrigin?.check(R.id.btnOriginBusiness)
            }
            etName.setText(accountToEdit.name)
            etBank.setText(accountToEdit.bankName)
            etNumber.setText(accountToEdit.accountNumber)
            etBalance.setText(String.format(Locale.US, "%.2f", accountToEdit.balance))
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val bank = etBank.text.toString().trim()
            val number = etNumber.text.toString().trim()
            val balance = etBalance.text.toString().toDoubleOrNull() ?: 0.0
            val selectedOrigin = if (toggleOrigin?.checkedButtonId == R.id.btnOriginPersonal) "PERSONAL" else "BUSINESS"

            if (name.isEmpty()) {
                etName.error = "Ingrese un nombre"
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val account = accountToEdit?.copy(
                    name = name,
                    bankName = bank.ifEmpty { "General" },
                    accountNumber = number,
                    balance = balance,
                    origin = selectedOrigin,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                ) ?: AccountEntity(
                    name = name,
                    bankName = bank.ifEmpty { "General" },
                    accountNumber = number,
                    balance = balance,
                    origin = selectedOrigin,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )

                database.accountDao().insert(account)
                SyncManager(this@AccountsActivity).scheduleOfflineSync()
                Toast.makeText(this@AccountsActivity, "Cuenta guardada correctamente ✅", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmation(account: AccountEntity) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ ¿Eliminar Cuenta?")
            .setMessage("¿Deseas eliminar '${account.name}' de tus cuentas registradas?")
            .setPositiveButton("Sí, Eliminar") { _, _ ->
                lifecycleScope.launch {
                    val deleted = account.copy(isDeleted = true, isSynced = false)
                    database.accountDao().update(deleted)
                    SyncManager(this@AccountsActivity).scheduleOfflineSync()
                    Toast.makeText(this@AccountsActivity, "Cuenta eliminada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class AccountsAdapter(
        private val onEdit: (AccountEntity) -> Unit,
        private val onDelete: (AccountEntity) -> Unit
    ) : RecyclerView.Adapter<AccountsAdapter.ViewHolder>() {
        private var items = emptyList<AccountEntity>()
        fun submitList(newItems: List<AccountEntity>) { items = newItems; notifyDataSetChanged() }

        inner class ViewHolder(val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvAccountName.text = item.name
                tvAccountBank.text = if (item.accountNumber.isNotEmpty()) "${item.bankName} • ${item.accountNumber}" else item.bankName
                tvAccountBalance.text = "S/ ${String.format(Locale.US, "%.2f", item.balance)}"
                
                if (item.origin == "PERSONAL") {
                    tvAccountOrigin.text = "👤 Personal"
                    tvAccountOrigin.setBackgroundResource(R.drawable.bg_pill_purple_light)
                    tvAccountOrigin.setTextColor(getColor(R.color.purple_600))
                } else {
                    tvAccountOrigin.text = "💼 Presupuesto"
                    tvAccountOrigin.setBackgroundResource(R.drawable.bg_pill_emerald_light)
                    tvAccountOrigin.setTextColor(getColor(R.color.emerald_600))
                }

                val emoji = when(item.bankName.uppercase(Locale.ROOT)) {
                    "YAPE" -> "📱"
                    "BCP" -> "🟦"
                    "BBVA" -> "🔷"
                    "INTERBANK" -> "🟩"
                    "PLIN" -> "⚡"
                    "EFECTIVO", "BOLSILLO", "PERSONAL" -> "💵"
                    else -> "💳"
                }
                tvAccountIcon.text = emoji

                root.setOnClickListener { onEdit(item) }
                root.setOnLongClickListener {
                    onDelete(item)
                    true
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
