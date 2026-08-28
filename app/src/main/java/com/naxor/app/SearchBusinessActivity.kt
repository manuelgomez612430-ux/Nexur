package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.StoreModel
import com.naxor.app.databinding.ActivitySearchBusinessBinding
import com.naxor.app.databinding.ItemStoreBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SearchBusinessActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBusinessBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: SearchStoreAdapter
    private var allStores = mutableListOf<StoreModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBusinessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadStores()
        
        binding.etSearchBusiness.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnMyCollabsSearch.setOnClickListener {
            showCollaborationsDialog()
        }
    }

    private fun showCollaborationsDialog() {
        lifecycleScope.launch {
            try {
                val email = auth.currentUser?.email?.lowercase() ?: return@launch
                val accessKeys = db.collection("access_keys")
                    .whereEqualTo("email", email)
                    .get().await()
                
                if (accessKeys.isEmpty) {
                    Toast.makeText(this@SearchBusinessActivity, "No tienes invitaciones activas", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val items = accessKeys.documents.map { doc -> 
                    "${doc.getString("businessName")} (${doc.getString("role")})" 
                }.toTypedArray()
                
                AlertDialog.Builder(this@SearchBusinessActivity)
                    .setTitle("Mis Colaboraciones")
                    .setItems(items) { _, which ->
                        val doc = accessKeys.documents[which]
                        switchToCollaborativeEnvironment(
                            doc.getString("ownerId") ?: "", 
                            doc.getString("businessType") ?: "HOTEL"
                        )
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SearchBusinessActivity, "Error al cargar colaboraciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        
        lifecycleScope.launch {
            try {
                // 1. Limpiar base de datos local para no mezclar datos
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@SearchBusinessActivity).clearAllTables()
                }
                
                // 2. Establecer nuevo entorno y rubro
                prefs.edit().apply {
                    putBoolean("is_collaborator_mode", true)
                    putString("collaborator_owner_id", ownerId)
                    putString("business_type", businessType)
                    apply()
                }
                
                // Asegurar que el rol en AppPrefs sea SELLER para entrar al dashboard corporativo
                getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                    .putString("user_role", "SELLER").apply()

                Toast.makeText(this@SearchBusinessActivity, "Entorno de equipo activado ✨", Toast.LENGTH_SHORT).show()

                // 3. Ir a MainActivity reiniciando el stack
                val intent = Intent(this@SearchBusinessActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SearchBusinessActivity, "Error al activar entorno", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbarSearch.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = SearchStoreAdapter { store -> showJoinDialog(store) }
        binding.rvBusinesses.layoutManager = LinearLayoutManager(this)
        binding.rvBusinesses.adapter = adapter
    }

    private fun loadStores() {
        binding.pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // Obtenemos una muestra de negocios publicados para evitar saturación
                val storesSnap = db.collectionGroup("settings")
                    .limit(60)
                    .get().await()
                
                allStores.clear()
                for (doc in storesSnap.documents) {
                    if (doc.id != "business") continue
                    
                    // Verificamos si el negocio está marcado como público (is_online)
                    val isOnline = doc.get("is_online")
                    val isPublished = when (isOnline) {
                        is Boolean -> isOnline
                        is String -> isOnline.toBoolean()
                        else -> false
                    }
                    
                    if (!isPublished) continue
                    
                    val sName = doc.getString("business_name") ?: continue
                    if (sName.equals("Mi Negocio", ignoreCase = true)) continue

                    val pathParts = doc.reference.path.split("/")
                    if (pathParts.size < 2) continue
                    val sId = pathParts[1]
                    
                    // No mostrar mi propio negocio
                    if (sId == auth.currentUser?.uid) continue
                    
                    val sAddr = doc.getString("business_address") ?: "Sin dirección"
                    val sLogo = doc.getString("business_logo_url")
                    
                    allStores.add(StoreModel(id = sId, name = sName, address = sAddr, logoUrl = sLogo))
                }
                adapter.setStores(allStores)
            } catch (e: Exception) {
                Log.e("SearchBusiness", "Error loading stores: ${e.message}")
                Toast.makeText(this@SearchBusinessActivity, "Red GoMezMart no disponible en este momento", Toast.LENGTH_SHORT).show()
            } finally {
                binding.pbLoading.visibility = View.GONE
            }
        }
    }

    private fun showJoinDialog(store: StoreModel) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Colaborar con ${store.name}")
        builder.setMessage("¿Deseas enviar una solicitud para unirte al equipo de este negocio?")
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        
        val etName = EditText(this).apply { hint = "Tu nombre completo" }
        val etDni = EditText(this).apply { hint = "Tu DNI" }
        
        layout.addView(etName)
        layout.addView(etDni)
        builder.setView(layout)

        builder.setPositiveButton("Enviar Solicitud") { _, _ ->
            val name = etName.text.toString().trim()
            val dni = etDni.text.toString().trim()
            
            if (name.isNotEmpty() && dni.isNotEmpty()) {
                sendCollaboratorRequest(store, name, dni)
            } else {
                Toast.makeText(this, "Por favor, completa todos los datos", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun sendCollaboratorRequest(store: StoreModel, name: String, dni: String) {
        val user = auth.currentUser ?: return
        val request = hashMapOf(
            "requesterId" to user.uid,
            "requesterEmail" to user.email,
            "requesterName" to name,
            "requesterDni" to dni,
            "status" to "PENDING",
            "businessName" to store.name,
            "timestamp" to System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("users").document(store.id)
                    .collection("collaborator_requests").document(user.uid)
                    .set(request).await()
                
                Toast.makeText(this@SearchBusinessActivity, "Solicitud enviada con éxito ✨", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@SearchBusinessActivity, "Error al enviar solicitud", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class SearchStoreAdapter(private val onStoreClick: (StoreModel) -> Unit) : RecyclerView.Adapter<SearchStoreAdapter.ViewHolder>() {
        private var items = listOf<StoreModel>()
        private var filtered = listOf<StoreModel>()

        fun setStores(list: List<StoreModel>) { items = list; filtered = list; notifyDataSetChanged() }
        
        fun filter(q: String) {
            filtered = if (q.isEmpty()) items else items.filter { it.name.lowercase().contains(q.lowercase()) }
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemStoreBinding) : RecyclerView.ViewHolder(b.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            ViewHolder(ItemStoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = filtered[position]
            with(holder.b) {
                tvStoreName.text = s.name
                tvStoreCategory.text = "Negocio en GoMezMart"
                tvStoreAddress.text = "📍 ${s.address}"
                if (!s.logoUrl.isNullOrEmpty()) Glide.with(ivStoreLogo.context).load(s.logoUrl).into(ivStoreLogo)
                else ivStoreLogo.setImageResource(R.drawable.gomezmart_icon)
                root.setOnClickListener { onStoreClick(s) }
            }
        }
        override fun getItemCount() = filtered.size
    }
}
