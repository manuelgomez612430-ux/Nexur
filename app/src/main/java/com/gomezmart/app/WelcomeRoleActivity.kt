package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.ActivityWelcomeRoleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class WelcomeRoleActivity : BaseActivity() {

    private lateinit var binding: ActivityWelcomeRoleBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityWelcomeRoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.root, applyTop = true, applyBottom = true)

        checkExistingData()
        setupListeners()
    }

    private fun checkExistingData() {
        val user = auth.currentUser
        if (user == null) {
            binding.layoutLoadingSystems.visibility = View.GONE
            binding.layoutEnvironmentSelectionContent.visibility = View.GONE
            binding.layoutRoleSelectionContent.visibility = View.VISIBLE
            return
        }

        binding.layoutLoadingSystems.visibility = View.VISIBLE
        binding.layoutEnvironmentSelectionContent.visibility = View.GONE
        binding.layoutRoleSelectionContent.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val allEnvironments = withContext(Dispatchers.IO) {
                    loadAllEnvironments(user)
                }

                binding.layoutLoadingSystems.visibility = View.GONE

                if (allEnvironments.isNotEmpty()) {
                    showInlineEnvironmentSelection(allEnvironments)
                } else {
                    // Si es un usuario completamente nuevo sin sistemas
                    binding.layoutRoleSelectionContent.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.layoutLoadingSystems.visibility = View.GONE
                binding.layoutRoleSelectionContent.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun loadAllEnvironments(user: FirebaseUser): List<EnvironmentItem> {
        val items = mutableListOf<EnvironmentItem>()
        val roomDb = AppDatabase.getDatabase(this)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)

        // 0. Intentar descargar configuraciones del negocio de la nube primero
        try {
            SyncManager(this@WelcomeRoleActivity).downloadBusinessSettingsFromCloud("LOANS")
        } catch (e: Exception) {}

        // 1. Cargar Negocios Propios de Firestore (Settings)
        try {
            val settingsSnap = db.collection("users").document(user.uid).collection("settings").get().await()
            settingsSnap.documents.forEach { doc ->
                val docId = doc.id.uppercase()
                val typeFromDoc = (doc.getString("business_type") ?: docId).uppercase()
                
                val name = doc.getString("business_name")
                    ?: doc.getString("${docId}_name")
                    ?: doc.getString("${typeFromDoc}_name")
                    ?: doc.getString("LOANS_name")
                    ?: doc.getString("HOTEL_name")
                    ?: doc.getString("PRODUCTS_name")
                    ?: doc.getString("${typeFromDoc}_owner_name")
                    ?: ""

                val isOnline = doc.getBoolean("is_online") ?: doc.getBoolean("is_online_store") ?: false
                val logo = doc.getString("business_logo_url") ?: doc.getString("${typeFromDoc}_logo_url")

                val targetType = when {
                    typeFromDoc.contains("LOAN") || docId.contains("LOAN") -> "LOANS"
                    typeFromDoc.contains("HOTEL") || docId.contains("HOTEL") -> "HOTEL"
                    typeFromDoc.contains("PRODUCT") || docId.contains("PRODUCT") -> "PRODUCTS"
                    else -> typeFromDoc
                }

                if (targetType != "BUSINESS" && (name.isNotEmpty() || docId == "LOANS" || docId == "HOTEL" || docId == "PRODUCTS")) {
                    val finalName = if (name.isNotEmpty()) name else when(targetType) {
                        "LOANS" -> "Sistema de Préstamos"
                        "HOTEL" -> "Sistema de Hospedaje"
                        else -> "Sistema de Tienda"
                    }
                    items.add(
                        EnvironmentItem(
                            id = user.uid,
                            name = finalName,
                            businessType = targetType,
                            logoUrl = logo,
                            isCollab = false,
                            isOnline = isOnline
                        )
                    )
                }
            }

            // 1.1 Verificar colecciones específicas de PRÉSTAMOS en Firestore
            if (items.none { it.businessType == "LOANS" }) {
                val cloudLoans = db.collection("users").document(user.uid).collection("loans").limit(1).get().await()
                val cloudClients = db.collection("users").document(user.uid).collection("loan_clients").limit(1).get().await()
                val cloudDebtors = db.collection("users").document(user.uid).collection("debtors").limit(1).get().await()
                if (!cloudLoans.isEmpty || !cloudClients.isEmpty || !cloudDebtors.isEmpty) {
                    val name = bizPrefs.getString("LOANS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Préstamos"
                    items.add(EnvironmentItem(id = user.uid, name = name, businessType = "LOANS", isCollab = false, isOnline = false))
                }
            }

            // 1.2 Verificar colecciones específicas de HOTEL en Firestore
            if (items.none { it.businessType == "HOTEL" }) {
                val cloudRooms = db.collection("users").document(user.uid).collection("hotel_rooms").limit(1).get().await()
                val cloudBookings = db.collection("users").document(user.uid).collection("hotel_bookings").limit(1).get().await()
                if (!cloudRooms.isEmpty || !cloudBookings.isEmpty) {
                    val name = bizPrefs.getString("HOTEL_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Hospedaje"
                    items.add(EnvironmentItem(id = user.uid, name = name, businessType = "HOTEL", isCollab = false, isOnline = false))
                }
            }

            // 1.3 Verificar colecciones específicas de PRODUCTOS en Firestore
            if (items.none { it.businessType == "PRODUCTS" }) {
                val cloudProducts = db.collection("users").document(user.uid).collection("products").limit(1).get().await()
                val cloudInventory = db.collection("users").document(user.uid).collection("inventory").limit(1).get().await()
                val cloudSales = db.collection("users").document(user.uid).collection("sales").limit(1).get().await()
                if (!cloudProducts.isEmpty || !cloudInventory.isEmpty || !cloudSales.isEmpty) {
                    val name = bizPrefs.getString("PRODUCTS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Tienda"
                    items.add(EnvironmentItem(id = user.uid, name = name, businessType = "PRODUCTS", isCollab = false, isOnline = false))
                }
            }
        } catch (e: Exception) { }

        // 2. Cargar Colaboraciones de Firestore
        try {
            val email = user.email?.lowercase() ?: ""
            if (email.isNotEmpty()) {
                val colabsSnap = db.collection("access_keys").whereEqualTo("email", email).get().await()
                colabsSnap.documents.forEach { doc ->
                    val ownerId = doc.getString("ownerId") ?: ""
                    if (ownerId != user.uid) {
                        items.add(
                            EnvironmentItem(
                                id = ownerId,
                                name = doc.getString("businessName") ?: "Colaboración",
                                businessType = (doc.getString("businessType") ?: "HOTEL").uppercase(),
                                logoUrl = doc.getString("collab_business_logo"),
                                isCollab = true,
                                role = doc.getString("role") ?: "EQUIPO",
                                isOnline = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { }

        // 3. Cargar sistemas con datos locales en Room DB
        try {
            // A) Préstamos
            val loansCount = roomDb.loanDao().getAllLoansOnce().size
            val clientsCount = roomDb.loanDao().getAllClientsOnce().size
            val debtorsCount = roomDb.debtorDao().getAllDebtors().size
            val hasLoansPref = bizPrefs.getString("LOANS_name", "")?.isNotEmpty() == true

            if ((loansCount > 0 || clientsCount > 0 || debtorsCount > 0 || hasLoansPref) && items.none { it.businessType == "LOANS" }) {
                val name = bizPrefs.getString("LOANS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Préstamos"
                items.add(EnvironmentItem(id = user.uid, name = name, businessType = "LOANS", isCollab = false, isOnline = false))
            }

            // B) Hotel
            val roomsCount = roomDb.hotelDao().getAllRoomsOnce().size
            val hasHotelPref = bizPrefs.getString("HOTEL_name", "")?.isNotEmpty() == true

            if ((roomsCount > 0 || hasHotelPref) && items.none { it.businessType == "HOTEL" }) {
                val name = bizPrefs.getString("HOTEL_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Hospedaje"
                items.add(EnvironmentItem(id = user.uid, name = name, businessType = "HOTEL", isCollab = false, isOnline = false))
            }

            // C) Productos
            val productsCount = roomDb.productDao().allProducts.size
            val salesCount = roomDb.saleDao().allSales.size
            val hasProductsPref = bizPrefs.getString("PRODUCTS_name", "")?.isNotEmpty() == true

            if ((productsCount > 0 || salesCount > 0 || hasProductsPref) && items.none { it.businessType == "PRODUCTS" }) {
                val name = bizPrefs.getString("PRODUCTS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Tienda"
                items.add(EnvironmentItem(id = user.uid, name = name, businessType = "PRODUCTS", isCollab = false, isOnline = false))
            }
        } catch (e: Exception) { }

        return items.distinctBy { "${it.businessType}_${it.id}" }
    }

    private fun showInlineEnvironmentSelection(items: List<EnvironmentItem>) {
        val user = auth.currentUser
        val userPhoto = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_photo_url", user?.photoUrl?.toString())
        val userName = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_full_name", user?.displayName ?: "Usuario Gomezmart")
        val userEmail = user?.email ?: ""

        binding.tvUserNameInline.text = userName
        binding.tvUserEmailInline.text = userEmail

        if (!userPhoto.isNullOrEmpty()) {
            binding.ivUserPhotoInline.visibility = View.VISIBLE
            Glide.with(this).load(userPhoto).circleCrop().into(binding.ivUserPhotoInline)
        }

        binding.cardUserProfileInline.setOnClickListener {
            saveRoleAndProceed("BUYER")
        }

        binding.btnAddNewSystemInline.setOnClickListener {
            startActivity(Intent(this, BusinessSelectionActivity::class.java))
        }

        binding.btnCancelAccountInline.setOnClickListener {
            auth.signOut()
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.rvEnvironmentsInline.layoutManager = LinearLayoutManager(this)
        binding.rvEnvironmentsInline.adapter = EnvironmentAdapter(items) { selectedItem ->
            selectEnvironmentAndLaunch(selectedItem)
        }

        binding.layoutLoadingSystems.visibility = View.GONE
        binding.layoutRoleSelectionContent.visibility = View.GONE
        binding.layoutEnvironmentSelectionContent.visibility = View.VISIBLE
    }

    private fun selectEnvironmentAndLaunch(item: EnvironmentItem) {
        val appEdit = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
        val bizEdit = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()

        appEdit.putBoolean("is_profile_verified", true)

        val newTitle: String
        if (item.isCollab) {
            newTitle = "Colaborador en ${item.name}"
            appEdit.putString("user_role", "COLLABORATOR")
            bizEdit.putBoolean("is_collaborator_mode", true)
            bizEdit.putString("collaborator_owner_id", item.id)
            bizEdit.putString("collaborator_role", item.role)
            bizEdit.putString("business_type", item.businessType)

            getSharedPreferences("CollabPermissions", MODE_PRIVATE).edit()
                .putString("collab_business_name", item.name)
                .putString("collab_business_logo", item.logoUrl ?: "")
                .apply()
        } else {
            newTitle = if (item.isOnline) "Dueño de ${item.name}" else "Gestor de ${item.name}"
            appEdit.putString("user_role", "SELLER")
            bizEdit.putBoolean("is_collaborator_mode", false)
            bizEdit.putString("business_type", item.businessType)
            bizEdit.putString("${item.businessType}_name", item.name)
            if (!item.logoUrl.isNullOrEmpty()) {
                bizEdit.putString("${item.businessType}_logo_url", item.logoUrl)
            }
        }

        appEdit.apply()
        bizEdit.apply()

        val user = auth.currentUser
        if (user != null) {
            val titleUpdate = mapOf("businessTitle" to newTitle)
            db.collection("users").document(user.uid).collection("profile").document("info")
                .set(titleUpdate, SetOptions.merge())
            db.collection("users").document(user.uid)
                .set(titleUpdate, SetOptions.merge())
        }

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    inner class EnvironmentAdapter(
        private val list: List<EnvironmentItem>,
        private val onItemClick: (EnvironmentItem) -> Unit
    ) : RecyclerView.Adapter<EnvironmentAdapter.ViewHolder>() {

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val ivLogo: ImageView = view.findViewById(R.id.ivEnvLogo)
            val tvName: TextView = view.findViewById(R.id.tvEnvName)
            val tvRole: TextView = view.findViewById(R.id.tvEnvRole)
            val tvType: TextView = view.findViewById(R.id.tvEnvType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_select_environment, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            when {
                item.isCollab -> {
                    holder.tvRole.text = "🛎️ Colaborador (${item.role})"
                    holder.tvRole.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.purple_600))
                }
                item.isOnline -> {
                    holder.tvRole.text = "👔 Dueño del Negocio"
                    holder.tvRole.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.emerald_600))
                }
                else -> {
                    holder.tvRole.text = "💼 Administrador"
                    holder.tvRole.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.sky_600))
                }
            }

            holder.tvType.text = when (item.businessType) {
                "HOTEL" -> "Hospedaje y Turismo 🏨"
                "LOANS" -> "Servicios de Préstamos 💰"
                "SERVICES" -> "Servicios Profesionales ⚙️"
                else -> "Tienda de Productos 🛒"
            }

            if (!item.logoUrl.isNullOrEmpty()) {
                Glide.with(holder.itemView.context)
                    .load(item.logoUrl)
                    .centerCrop()
                    .placeholder(R.drawable.logotipo_de_gomezmart)
                    .into(holder.ivLogo)
            } else {
                holder.ivLogo.setImageResource(R.drawable.logotipo_de_gomezmart)
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }

    private fun setupListeners() {
        binding.cardSellerRole.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                showMandatoryAccountDialog()
            } else {
                saveRoleAndProceed("SELLER")
            }
        }

        binding.cardBuyerRole.setOnClickListener {
            saveRoleAndProceed("BUYER")
        }

        binding.cardCollabRole.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                showMandatoryAccountDialog()
            } else {
                saveRoleAndProceed("COLLABORATOR")
            }
        }

        binding.btnBackToProfileSetup.setOnClickListener {
            startActivity(Intent(this, ProfileSetupActivity::class.java))
            finish()
        }
    }

    private fun showMandatoryAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cuenta Obligatoria 💼")
            .setMessage("Para gestionar un negocio en GoMezMart es necesario crear una cuenta. Esto asegura que tus productos, préstamos y ventas estén siempre a salvo en la nube.")
            .setPositiveButton("Crear mi Cuenta") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("FORCE_REGISTER", true)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Luego", null)
            .show()
    }

    private fun saveRoleAndProceed(role: String) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putString("user_role", role)
            .apply()
        
        if (role == "BUYER") {
            getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                .edit()
                .remove("business_type")
                .apply()
        }
        
        proceedWithRole(role)
    }

    private fun proceedWithRole(role: String) {
        val startUpdate = intent.getBooleanExtra("START_UPDATE", false)
        val intent = when (role) {
            "SELLER" -> Intent(this, BusinessSelectionActivity::class.java)
            "COLLABORATOR" -> Intent(this, SearchBusinessActivity::class.java)
            else -> Intent(this, BuyerHomeActivity::class.java)
        }
        if (startUpdate) intent.putExtra("START_UPDATE", true)
        startActivity(intent)
        finish()
    }
}