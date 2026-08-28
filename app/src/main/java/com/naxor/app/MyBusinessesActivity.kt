package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.StoreModel
import com.naxor.app.databinding.ActivityMyBusinessesBinding
import com.naxor.app.databinding.ItemBusinessProfileBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyBusinessesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isManageStoreMode = intent.getBooleanExtra("MANAGE_STORE_MODE", false)
        
        if (isManageStoreMode) {
            binding.toolbarMyBusinesses.title = "Mis Tiendas Online"
        }

        binding.toolbarMyBusinesses.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        loadMyBusinesses()
        
        binding.btnAddSystem.setOnClickListener {
            startActivity(Intent(this, BusinessSelectionActivity::class.java))
        }

        binding.btnSkipToMarket.setOnClickListener {
            startActivity(Intent(this, BuyerHomeActivity::class.java))
            finish()
        }
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
        val intent = Intent(this, StoreProfileActivity::class.java).apply {
            putExtra("STORE_ID", auth.currentUser?.uid)
            putExtra("STORE_NAME", store.name)
            putExtra("IS_OWNER", true)
            putExtra("FORCE_RUBRO", store.businessType)
        }
        startActivity(intent)
    }

    private fun loadMyBusinesses() {
        val user = auth.currentUser ?: return
        val userId = user.uid
        val userEmail = user.email?.lowercase() ?: ""
        
        lifecycleScope.launch {
            try {
                // 1. Cargar Negocios Propios
                val settingsSnap = db.collection("users").document(userId)
                    .collection("settings").get().await()
                
                val ownBusinesses = settingsSnap.documents.mapNotNull { doc ->
                    val name = doc.getString("${doc.id}_name") ?: doc.getString("business_name") ?: "Mi Negocio"
                    val type = doc.getString("business_type") ?: doc.id
                    val logo = doc.getString("business_logo_url")
                    val isOnline = doc.getBoolean("is_online") ?: false
                    
                    if (doc.id == "business") return@mapNotNull null

                    StoreModel(
                        id = doc.id,
                        name = name,
                        businessType = type,
                        logoUrl = logo,
                        address = doc.getString("business_address") ?: "",
                        phone = if (isOnline) "ONLINE" else "OFFLINE"
                    )
                }

                // 2. Cargar Colaboraciones
                val colabsSnap = db.collection("access_keys")
                    .whereEqualTo("email", userEmail).get().await()
                
                val collaborations = colabsSnap.documents.map { doc ->
                    StoreModel(
                        id = doc.getString("ownerId") ?: "",
                        name = doc.getString("businessName") ?: "Colaboración",
                        businessType = doc.getString("businessType") ?: "HOTEL",
                        phone = "COLLAB", // Marca especial
                        description = doc.getString("role") ?: "EQUIPO"
                    )
                }
                
                val fullList = ownBusinesses + collaborations
                adapter.setItems(fullList)

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
        if (store.phone == "COLLAB") {
            switchToCollaborativeEnvironment(store.id, store.businessType)
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
                    apply()
                }

                SyncManager(this@MyBusinessesActivity).downloadBusinessSettingsFromCloud(store.businessType)
                SyncManager(this@MyBusinessesActivity).scheduleOfflineSync()

                loading.dismiss()
                
                val intent = Intent(this@MyBusinessesActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                loading.dismiss()
            }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String) {
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
                
                loading.dismiss()
                val intent = Intent(this@MyBusinessesActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
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
