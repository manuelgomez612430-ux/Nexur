package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.ProviderEntity
import com.naxor.app.databinding.ActivityProveedoresBinding
import com.naxor.app.databinding.ItemProveedorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class ProveedoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProveedoresBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: ProveedoresAdapter

    // Sync state
    private var isNetworkAvailable = false
    private var lastUnsyncedCount = 0
    private var currentIsSyncing = false
    private var syncStatusText = "Conectado"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProveedoresBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupNetworkMonitoring()
        setupSyncIndicator()
        loadProviders()
        
        SyncManager(this).startRealtimeProvidersSync { loadProviders() }
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
        database.providerDao().getUnsyncedCount().observe(this) { count ->
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
                color = android.graphics.Color.parseColor("#E0E0E0")
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

    private fun setupRecyclerView() {
        adapter = ProveedoresAdapter(
            onDelete = { provider -> showDeleteConfirmation(provider) },
            onWhatsApp = { provider -> contactProvider(provider) }
        )
        binding.rvProveedores.layoutManager = LinearLayoutManager(this)
        binding.rvProveedores.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackProveedores.setOnClickListener { finish() }
        binding.btnHelpProveedores.setOnClickListener { showHelpDialog() }
        binding.fabAddProveedor.setOnClickListener { showAddProviderDialog() }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Guía de Proveedores")
            .setMessage("• REGISTRO: Guarda aquí los contactos de quienes te venden mercancía.\n" +
                    "• PEDIDOS: Toca el icono de WhatsApp para enviarles un mensaje rápido de consulta.\n" +
                    "• ORGANIZACIÓN: Clasifícalos por categoría para encontrarlos más rápido.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun loadProviders() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.providerDao().getAllProviders()
            }
            adapter.submitList(list)
            binding.layoutEmptyProveedores.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showAddProviderDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nuevo Proveedor")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        
        val etNombre = EditText(this).apply { hint = "Empresa / Nombre" }
        val etContacto = EditText(this).apply { hint = "Persona de contacto" }
        val etTelefono = EditText(this).apply { hint = "Teléfono"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        
        val spinner = Spinner(this)
        val categories = resources.getStringArray(R.array.categorias_array)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        
        layout.addView(etNombre)
        layout.addView(etContacto)
        layout.addView(etTelefono)
        layout.addView(spinner)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val nombre = etNombre.text.toString()
            val contacto = etContacto.text.toString()
            val telf = etTelefono.text.toString()
            val cat = spinner.selectedItem.toString()
            
            if (nombre.isNotBlank() && telf.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val provider = ProviderEntity(nombre = nombre, contacto = contacto, telefono = telf, categoria = cat, isSynced = false)
                    database.providerDao().insert(provider)
                    SyncManager(this@ProveedoresActivity).syncProviderToCloud(provider)
                    SyncManager(this@ProveedoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadProviders() }
                }
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun contactProvider(provider: ProviderEntity) {
        val mensaje = "Hola ${provider.contacto}, te saludo de *Naxor*. Quisiera hacer una consulta sobre sus productos. 📦"
        try {
            val url = "https://api.whatsapp.com/send?phone=51${provider.telefono}&text=" + URLEncoder.encode(mensaje, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(provider: ProviderEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Proveedor")
            .setMessage("¿Deseas eliminar a ${provider.nombre}?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    provider.isDeleted = true
                    provider.isSynced = false
                    database.providerDao().update(provider)
                    SyncManager(this@ProveedoresActivity).scheduleOfflineSync()
                    withContext(Dispatchers.Main) { loadProviders() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class ProveedoresAdapter(
        private val onDelete: (ProviderEntity) -> Unit,
        private val onWhatsApp: (ProviderEntity) -> Unit
    ) : RecyclerView.Adapter<ProveedoresAdapter.ViewHolder>() {
        
        private var list: List<ProviderEntity> = emptyList()
        
        fun submitList(newList: List<ProviderEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemProveedorBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemProveedorBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = list[position]
            holder.b.tvProvNombre.text = p.nombre
            holder.b.tvProvCategoria.text = "Categoría: ${p.categoria}"
            holder.b.tvProvContacto.text = "Contacto: ${p.contacto}"
            
            holder.b.btnProvWhatsApp.setOnClickListener { onWhatsApp(p) }
            holder.b.btnProvDelete.setOnClickListener { onDelete(p) }
        }

        override fun getItemCount() = list.size
    }
}
