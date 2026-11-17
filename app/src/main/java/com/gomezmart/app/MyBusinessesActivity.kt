package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.StoreModel
import com.gomezmart.app.databinding.ActivityMyBusinessesBinding
import com.gomezmart.app.databinding.ItemBusinessProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MyBusinessesActivity : BaseActivity() {

    private lateinit var binding: ActivityMyBusinessesBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: BusinessesAdapter
    private var isManageStoreMode = false
    private var isOwnOnlyMode = false
    private var isCollabOnlyMode = false

    private var fullList = listOf<StoreModel>()
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyBusinessesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para que la cabecera y el contenido no choquen con las barras del sistema
        applyWindowInsets(binding.appBarMyBusinesses, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)

        isManageStoreMode = intent.getBooleanExtra("MANAGE_STORE_MODE", false)
        isOwnOnlyMode = intent.getBooleanExtra("OWN_ONLY_MODE", false)
        isCollabOnlyMode = intent.getBooleanExtra("COLLAB_ONLY_MODE", false)
        
        when {
            isCollabOnlyMode -> {
                binding.toolbarMyBusinesses.title = "Mis Colaboraciones 🤝"
                binding.tvSubtitleMyBusinesses.text = "Busca o selecciona una colaboración activa para entrar a trabajar."
                binding.btnAddSystem.visibility = View.GONE
            }
            isManageStoreMode -> {
                binding.toolbarMyBusinesses.title = "Mis Tiendas Online"
            }
            isOwnOnlyMode -> {
                binding.toolbarMyBusinesses.title = "Selecciona tu Negocio"
            }
        }

        binding.toolbarMyBusinesses.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        setupSearch()
        loadMyBusinesses()
        
        binding.btnAddSystem.setOnClickListener {
            startActivity(Intent(this, BusinessSelectionActivity::class.java))
        }

        binding.btnSkipToMarket.setOnClickListener {
            startActivity(Intent(this, BuyerHomeActivity::class.java))
            finish()
        }
    }

    private fun setupSearch() {
        binding.etSearchBusiness.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyFilter() {
        var filtered = fullList
        if (isCollabOnlyMode) {
            filtered = filtered.filter { it.phone == "COLLAB" }
        }
        if (currentSearchQuery.isNotEmpty()) {
            filtered = filtered.filter { 
                it.name.lowercase().contains(currentSearchQuery) || 
                it.description.lowercase().contains(currentSearchQuery) ||
                it.businessType.lowercase().contains(currentSearchQuery)
            }
        }
        adapter.setItems(filtered)
    }

    private fun setupRecyclerView() {
        adapter = BusinessesAdapter { store ->
            if (isManageStoreMode) {
                openStoreEditor(store)
            } else {
                switchSystem(store)
            }
        }
        binding.rvMyBusinesses.layoutManager = LinearLayoutManager(this)
        binding.rvMyBusinesses.adapter = adapter
    }

    private fun openStoreEditor(store: StoreModel) {
        val targetClass = if (store.businessType == "LOANS") LoanStoreEditorActivity::class.java else StoreProfileActivity::class.java
        val intent = Intent(this, targetClass).apply {
            putExtra("STORE_ID", auth.currentUser?.uid)
            putExtra("STORE_NAME", store.name)
            putExtra("IS_OWNER", true)
            putExtra("FORCE_RUBRO", store.businessType)
        }
        startActivity(intent)
    }

    private fun loadMyBusinesses() {
        if (isGuestUser()) {
            binding.btnSkipToMarket.visibility = View.VISIBLE
            return
        }
        val user = auth.currentUser ?: return
        val userId = user.uid
        val userEmail = user.email?.lowercase() ?: ""
        
        lifecycleScope.launch {
            try {
                // 1. Cargar Negocios Propios (Donde yo soy el dueño)
                val settingsSnap = db.collection("users").document(userId)
                    .collection("settings").get().await()
                
                val ownBusinesses = settingsSnap.documents.mapNotNull { doc ->
                    if (doc.id == "business") return@mapNotNull null
                    
                    val name = doc.getString("business_name") ?: doc.getString("${doc.id}_name") ?: ""
                    val type = doc.getString("business_type") ?: doc.id
                    val logo = doc.getString("business_logo_url")
                    val isOnline = doc.getBoolean("is_online") ?: false
                    
                    if (name.isEmpty() || name.equals("Mi Negocio", ignoreCase = true)) return@mapNotNull null

                    StoreModel(
                        id = userId,
                        name = name,
                        businessType = type,
                        logoUrl = logo,
                        address = doc.getString("business_address") ?: "",
                        phone = if (isOnline) "ONLINE" else "OFFLINE"
                    )
                }

                // 2. Cargar Colaboraciones (Donde soy invitado)
                val collaborations = if (!isManageStoreMode && !isOwnOnlyMode) {
                    val colabsSnap = db.collection("access_keys")
                        .whereEqualTo("email", userEmail).get().await()
                    
                    colabsSnap.documents.mapNotNull { doc ->
                        val ownerId = doc.getString("ownerId")
                        if (ownerId == userId) return@mapNotNull null

                        StoreModel(
                            id = ownerId ?: "",
                            name = doc.getString("businessName") ?: "Colaboración",
                            businessType = doc.getString("businessType") ?: "HOTEL",
                            phone = "COLLAB",
                            description = doc.getString("role") ?: "EQUIPO",
                            logoUrl = doc.getString("collab_business_logo")
                        )
                    }
                } else {
                    emptyList()
                }
                
                fullList = ownBusinesses + collaborations
                applyFilter()

                if (fullList.isEmpty()) {
                    binding.btnSkipToMarket.visibility = View.VISIBLE
                    if (!isManageStoreMode) {
                        binding.toolbarMyBusinesses.title = "Comenzar en GoMezMart"
                    }
                } else {
                    binding.btnSkipToMarket.visibility = View.VISIBLE
                }
            } catch (e: Exception) { }
        }
    }

    private fun switchSystem(store: StoreModel) {
        val dynamicManager = com.gomezmart.app.utils.DynamicModuleManager(this)
        val targetModule = when (store.businessType) {
            "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
            "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
            else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
        }

        if (!dynamicManager.isModuleInstalled(targetModule)) {
            val systemName = when (store.businessType) {
                "HOTEL" -> "Sistema de Hotel"
                "LOANS" -> "Sistema de Préstamos"
                else -> "Sistema de Productos"
            }
            AlertDialog.Builder(this)
                .setTitle("$systemName desinstalado")
                .setMessage("Este sistema no está instalado en tu dispositivo. ¿Deseas descargarlo e instalarlo para acceder a ${store.name}?")
                .setPositiveButton("Instalar e Ingresar") { _, _ ->
                    dynamicManager.launchOrDownloadModule(
                        module = targetModule,
                        onProgress = { progress ->
                            Toast.makeText(this, "Instalando $systemName: $progress%", Toast.LENGTH_SHORT).show()
                        },
                        onSuccess = {
                            switchSystem(store)
                        },
                        onError = { err ->
                            Toast.makeText(this, "Error al instalar: $err", Toast.LENGTH_LONG).show()
                        }
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        if (store.phone == "COLLAB") {
            switchToCollaborativeEnvironment(store.id, store.businessType, store.name, store.logoUrl)
            return
        }

        val loading = AlertDialog.Builder(this)
            .setMessage("Cambiando a ${store.name}...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@MyBusinessesActivity).clearAllTables()
                }

                val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                prefs.edit().apply {
                    putString("business_type", store.businessType)
                    putBoolean("is_collaborator_mode", false)
                    putString("${store.businessType}_name", store.name)
                    if (!store.logoUrl.isNullOrEmpty()) {
                        putString("${store.businessType}_logo_url", store.logoUrl)
                    }
                    apply()
                }

                SyncManager(this@MyBusinessesActivity).downloadBusinessSettingsFromCloud(store.businessType)
                SyncManager(this@MyBusinessesActivity).downloadEverythingFromCloud {
                    if (!isFinishing && !isDestroyed) {
                        loading.dismiss()
                        val intent = Intent(this@MyBusinessesActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            } catch (e: Exception) {
                loading.dismiss()
            }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String, name: String? = null, logo: String? = null) {
        val loading = AlertDialog.Builder(this)
            .setMessage("Entrando como colaborador...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@MyBusinessesActivity).clearAllTables()
                }
                
                getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().apply {
                    putBoolean("is_collaborator_mode", true)
                    putString("collaborator_owner_id", ownerId)
                    putString("business_type", businessType)
                    apply()
                }

                val collabPrefs = getSharedPreferences("CollabPermissions", MODE_PRIVATE)
                collabPrefs.edit().clear().apply()
                collabPrefs.edit().apply {
                    if (name != null) putString("collab_business_name", name)
                    if (logo != null) putString("collab_business_logo", logo)
                    apply()
                }
                
                SyncManager(this@MyBusinessesActivity).downloadEverythingFromCloud {
                    if (!isFinishing && !isDestroyed) {
                        loading.dismiss()
                        val intent = Intent(this@MyBusinessesActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            } catch (e: Exception) {
                loading.dismiss()
            }
        }
    }

    inner class BusinessesAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<BusinessesAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        fun setItems(list: List<StoreModel>) { items = list; notifyDataSetChanged() }

        inner class ViewHolder(val b: ItemBusinessProfileBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemBusinessProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvBusinessName.text = item.name
                
                if (item.phone == "COLLAB") {
                    tvBusinessType.text = "Participando como: ${item.description}"
                    tvBusinessType.setTextColor(root.context.getColor(R.color.purple_600))
                    tvBusinessStatus.visibility = View.VISIBLE
                    tvBusinessStatus.text = "ENTORNO COMPARTIDO ✨"
                    tvBusinessStatus.setTextColor(root.context.getColor(R.color.sky_600))
                } else {
                    tvBusinessType.text = when(item.businessType) {
                        "HOTEL" -> "Hospedaje y Turismo 🏨"
                        "LOANS" -> "Servicios de Préstamos 💰"
                        else -> "Tienda de Productos 🛒"
                    }
                    tvBusinessType.setTextColor(root.context.getColor(R.color.slate_500))
                    
                    if (item.phone == "ONLINE") {
                        tvBusinessStatus.visibility = View.VISIBLE
                        tvBusinessStatus.text = "Tienda Online Activa ●"
                        tvBusinessStatus.setTextColor(root.context.getColor(R.color.emerald_600))
                    } else {
                        tvBusinessStatus.visibility = View.GONE
                    }
                }
                
                if (!item.logoUrl.isNullOrEmpty()) {
                    Glide.with(ivBusinessLogo.context).load(item.logoUrl).into(ivBusinessLogo)
                } else {
                    ivBusinessLogo.setImageResource(R.drawable.logotipo_de_gomezmart)
                }
                
                root.setOnClickListener { onClick(item) }
            }
        }
        override fun getItemCount() = items.size
    }
}
