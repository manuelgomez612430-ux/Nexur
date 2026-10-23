package com.gomezmart.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.CartManager
import com.gomezmart.app.data.StoreModel
import com.gomezmart.app.databinding.ActivityAccountSettingsBinding
import com.gomezmart.app.databinding.ItemBusinessProfileBinding
import com.gomezmart.app.network.SupabaseManager
import com.gomezmart.app.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class AccountSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityAccountSettingsBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var profileRegistration: ListenerRegistration? = null
    private var selectedPhotoUri: Uri? = null
    private var photoFile: File? = null
    private var initialPhotoUrl: String? = null

    private lateinit var systemsAdapter: SystemsAdapter

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                linkAccountWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Toast.makeText(this, "Error de Google: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateGoogleLinkStatus() {
        val user = auth.currentUser ?: return
        binding.tvEditAccountEmail.text = user.email
        
        val isLinked = user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        
        if (isLinked) {
            binding.tvEditGoogleStatus.text = "Vinculado con Google ✅"
            binding.tvEditGoogleStatus.setTextColor(getColor(R.color.emerald_600))
            binding.ivGoogleLinkedCheck.visibility = View.VISIBLE
            binding.btnLinkGoogle.visibility = View.GONE
        } else {
            binding.tvEditGoogleStatus.text = "No vinculado con Google"
            binding.tvEditGoogleStatus.setTextColor(getColor(R.color.slate_400))
            binding.ivGoogleLinkedCheck.visibility = View.GONE
            binding.btnLinkGoogle.visibility = View.VISIBLE
        }
    }

    private fun startGoogleSignInForLinking() {
        val webClientId = try {
            getString(resources.getIdentifier("default_web_client_id", "string", packageName))
        } catch (e: Exception) { "" }

        if (webClientId.isEmpty()) {
            Toast.makeText(this, "Error de configuración de Google", Toast.LENGTH_SHORT).show()
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun linkAccountWithGoogle(idToken: String) {
        val user = auth.currentUser ?: return
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val loading = AlertDialog.Builder(this).setMessage("Vinculando cuenta...").show()

        user.linkWithCredential(credential).addOnCompleteListener { task ->
            loading.dismiss()
            if (task.isSuccessful) {
                Toast.makeText(this, "Cuenta vinculada con éxito ✅", Toast.LENGTH_SHORT).show()
                updateGoogleLinkStatus()
            } else {
                Toast.makeText(this, "Error al vincular: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPhotoUri = uri
            binding.pbProfilePhotoLoading.visibility = View.VISIBLE
            Glide.with(this).load(uri).circleCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        binding.pbProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        binding.pbProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(binding.ivProfileImage)
            binding.ivProfileImage.visibility = View.VISIBLE
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            photoFile?.let {
                selectedPhotoUri = Uri.fromFile(it)
                binding.pbProfilePhotoLoading.visibility = View.VISIBLE
                Glide.with(this).load(it).circleCrop()
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                            binding.pbProfilePhotoLoading.visibility = View.GONE
                            return false
                        }
                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            binding.pbProfilePhotoLoading.visibility = View.GONE
                            return false
                        }
                    })
                    .into(binding.ivProfileImage)
                binding.ivProfileImage.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets de la barra de herramientas
        applyWindowInsets(binding.toolbarAccountSettings, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)

        binding.toolbarAccountSettings.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadUserData()
        loadSavedSystems()
        setupListeners()
    }

    private fun setupRecyclerView() {
        systemsAdapter = SystemsAdapter { store ->
            switchSystem(store)
        }
        binding.rvSavedSystems.layoutManager = LinearLayoutManager(this)
        binding.rvSavedSystems.adapter = systemsAdapter
    }

    private fun loadUserData() {
        val user = auth.currentUser
        if (user == null) {
            binding.tvProfileNameDisplay.text = "Modo Invitado"
            binding.tvProfileEmailDisplay.text = "Inicia sesión para más funciones"
            binding.tvProfileInitial.text = "?"
            binding.tvUserAccountType.text = "COMPRADOR"
            return
        }

        binding.tvProfileEmailDisplay.text = user.email
        updateGoogleLinkStatus()

        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        binding.etFullName.setText(appPrefs.getString("user_full_name", user.displayName))
        binding.etPhone.setText(appPrefs.getString("user_phone", ""))
        binding.etDni.setText(appPrefs.getString("user_dni", ""))
        binding.etAddress.setText(appPrefs.getString("user_address", ""))
        initialPhotoUrl = appPrefs.getString("user_photo_url", user.photoUrl?.toString())

        // Escuchar cambios en tiempo real del perfil en Firestore
        profileRegistration?.remove()
        profileRegistration = db.collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (isFinishing || isDestroyed || snapshot == null) return@addSnapshotListener

                val firestoreName = snapshot.getString("name")
                val firestorePhoto = snapshot.getString("photoUrl")
                val firestoreEmail = snapshot.getString("email") ?: user.email

                val finalName = firestoreName ?: user.displayName ?: "Usuario GoMezMart"
                val finalPhoto = firestorePhoto ?: user.photoUrl?.toString()

                binding.tvProfileNameDisplay.text = finalName
                binding.tvProfileEmailDisplay.text = firestoreEmail
                binding.tvProfileInitial.text = if (finalName.isNotEmpty()) finalName[0].uppercaseChar().toString() else "?"

                if (!finalPhoto.isNullOrEmpty()) {
                    binding.ivProfileImage.visibility = View.VISIBLE
                    Glide.with(this).load(finalPhoto).circleCrop().into(binding.ivProfileImage)
                } else {
                    binding.ivProfileImage.visibility = View.GONE
                }
            }

        // Determinar Tipo de Cuenta (Rol) en IO con timeout
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settingsSnap = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    db.collection("users").document(user.uid).collection("settings").get().await()
                }
                val isOwner = settingsSnap?.documents?.any { it.id != "business" && (it.getString("business_name")?.isNotEmpty() == true || it.getString("${it.id}_name")?.isNotEmpty() == true) } ?: true

                val email = user.email?.lowercase() ?: ""
                val collabSnap = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    db.collection("access_keys").whereEqualTo("email", email).get().await()
                }
                val isCollab = collabSnap?.isEmpty == false

                val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val hasLocalBiz = !prefs.getString("business_type", "").isNullOrEmpty()
                val isOnline = prefs.getBoolean("is_online_store", false)
                    || (settingsSnap?.documents?.any { it.getBoolean("is_online") == true || it.getBoolean("is_online_store") == true } == true)

                withContext(Dispatchers.Main) {
                    val roleText: String
                    val roleIcon: String
                    when {
                        (isOwner || hasLocalBiz) && isCollab -> {
                            roleIcon = if (isOnline) "👑" else "💼"
                            roleText = if (isOnline) "DUEÑO Y COLABORADOR" else "ADMINISTRADOR Y COLABORADOR"
                        }
                        (isOwner || hasLocalBiz) && isOnline -> {
                            roleIcon = "👑"
                            roleText = "DUEÑO DE NEGOCIO"
                        }
                        isOwner || hasLocalBiz -> {
                            roleIcon = "💼"
                            roleText = "ADMINISTRADOR"
                        }
                        isCollab -> {
                            roleIcon = "🤝"
                            roleText = "COLABORADOR"
                        }
                        else -> {
                            roleIcon = "🛒"
                            roleText = "COMPRADOR"
                        }
                    }
                    binding.tvUserAccountType.text = roleText
                    try {
                        binding.tvUserAccountRoleIcon.text = roleIcon
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvUserAccountType.text = "ADMINISTRADOR"
                }
            }
        }
    }

    private fun loadSavedSystems() {
        val user = auth.currentUser ?: return
        val userId = user.uid
        val userEmail = user.email?.lowercase() ?: ""
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val roomDb = AppDatabase.getDatabase(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val items = mutableListOf<StoreModel>()

            // 1. Cargar desde SharedPreferences locales (El sistema activo actual)
            try {
                val activeType = bizPrefs.getString("business_type", "LOANS") ?: "LOANS"
                val activeName = bizPrefs.getString("${activeType}_name", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: bizPrefs.getString("business_name", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: when(activeType) {
                        "LOANS" -> "Sistema de Préstamos"
                        "HOTEL" -> "Hospedaje y Hotel"
                        else -> "Tienda de Productos"
                    }
                val activeLogo = bizPrefs.getString("${activeType}_logo_url", "")
                    ?: bizPrefs.getString("business_logo_url", "")
                val isOnline = bizPrefs.getBoolean("is_online_store", false)

                items.add(
                    StoreModel(
                        id = userId,
                        name = activeName,
                        businessType = activeType.uppercase(),
                        logoUrl = activeLogo,
                        address = "",
                        phone = if (isOnline) "ONLINE" else "OFFLINE"
                    )
                )
            } catch (e: Exception) { }

            // 2. Cargar desde Firestore settings con timeout
            try {
                val settingsSnap = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    db.collection("users").document(userId).collection("settings").get().await()
                }
                settingsSnap?.documents?.forEach { doc ->
                    if (doc.id == "business") return@forEach
                    val name = doc.getString("business_name") 
                        ?: doc.getString("${doc.id}_name") 
                        ?: doc.getString("${doc.getString("business_type")}_name") 
                        ?: ""
                    val type = doc.getString("business_type") ?: doc.id
                    val logo = doc.getString("business_logo_url")
                    val isOnlineStore = doc.getBoolean("is_online") ?: doc.getBoolean("is_online_store") ?: false

                    if (name.isNotEmpty() && !name.equals("Mi Negocio", ignoreCase = true)) {
                        items.add(
                            StoreModel(
                                id = userId,
                                name = name,
                                businessType = type.uppercase(),
                                logoUrl = logo,
                                address = doc.getString("business_address") ?: "",
                                phone = if (isOnlineStore) "ONLINE" else "OFFLINE"
                            )
                        )
                    }
                }
            } catch (e: Exception) { }

            // 3. Cargar Colaboraciones de Firestore con timeout
            try {
                val colabsSnap = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    db.collection("access_keys").whereEqualTo("email", userEmail).get().await()
                }
                colabsSnap?.documents?.forEach { doc ->
                    val ownerId = doc.getString("ownerId")
                    if (ownerId == userId) return@forEach
                    items.add(
                        StoreModel(
                            id = ownerId ?: "",
                            name = doc.getString("businessName") ?: "Colaboración",
                            businessType = (doc.getString("businessType") ?: "HOTEL").uppercase(),
                            phone = "COLLAB",
                            description = doc.getString("role") ?: "EQUIPO",
                            logoUrl = doc.getString("collab_business_logo")
                        )
                    )
                }
            } catch (e: Exception) { }

            // 4. Cargar sistemas con datos locales Room DB si aún no están listados
            try {
                val loansCount = roomDb.loanDao().getAllLoansOnce().size
                val clientsCount = roomDb.loanDao().getAllClientsOnce().size
                if ((loansCount > 0 || clientsCount > 0) && items.none { it.businessType == "LOANS" }) {
                    val name = bizPrefs.getString("LOANS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Préstamos"
                    items.add(StoreModel(id = userId, name = name, businessType = "LOANS", phone = "OFFLINE"))
                }

                val roomsCount = roomDb.hotelDao().getAllRoomsOnce().size
                if (roomsCount > 0 && items.none { it.businessType == "HOTEL" }) {
                    val name = bizPrefs.getString("HOTEL_name", "")?.takeIf { it.isNotBlank() } ?: "Hospedaje y Hotel"
                    items.add(StoreModel(id = userId, name = name, businessType = "HOTEL", phone = "OFFLINE"))
                }

                val productsCount = roomDb.productDao().allProducts.size
                if (productsCount > 0 && items.none { it.businessType == "PRODUCTS" }) {
                    val name = bizPrefs.getString("PRODUCTS_name", "")?.takeIf { it.isNotBlank() } ?: "Tienda de Productos"
                    items.add(StoreModel(id = userId, name = name, businessType = "PRODUCTS", phone = "OFFLINE"))
                }
            } catch (e: Exception) { }

            val fullList = items.distinctBy { "${it.businessType}_${it.id}" }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                systemsAdapter.setItems(fullList)
            }
        }
    }

    private fun setupListeners() {
        binding.btnChangeProfilePhoto.setOnClickListener { showPhotoOptionsDialog() }
        binding.btnSaveProfile.setOnClickListener { validateAndSaveProfile() }
        binding.btnSaveAccountPassword.setOnClickListener { validateAndSavePassword() }
        
        binding.layoutAddress.setEndIconOnClickListener { detectCurrentCity() }
        binding.btnLinkGoogle.setOnClickListener { startGoogleSignInForLinking() }

        binding.cardSignOutAccount.setOnClickListener { showSignOutConfirmationDialog() }
        binding.btnDeleteAccountPermanent.setOnClickListener { showDeleteAccountConfirmationDialog() }
    }

    private fun validateAndSavePassword() {
        val newPassword = binding.etAccountPassword.text.toString().trim()
        if (newPassword.length < 6) {
            Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser ?: return
        val loading = AlertDialog.Builder(this).setMessage("Guardando contraseña...").setCancelable(false).show()

        val isEmailLinked = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }

        if (isEmailLinked) {
            user.updatePassword(newPassword).addOnCompleteListener { task ->
                loading.dismiss()
                if (task.isSuccessful) {
                    Toast.makeText(this, "Contraseña actualizada con éxito ✅", Toast.LENGTH_SHORT).show()
                    binding.etAccountPassword.setText("")
                } else {
                    Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val email = user.email ?: run {
                loading.dismiss()
                Toast.makeText(this, "No se encontró correo electrónico asociado", Toast.LENGTH_SHORT).show()
                return
            }
            val credential = EmailAuthProvider.getCredential(email, newPassword)
            user.linkWithCredential(credential).addOnCompleteListener { task ->
                loading.dismiss()
                if (task.isSuccessful) {
                    Toast.makeText(this, "Contraseña creada y vinculada con éxito ✅", Toast.LENGTH_SHORT).show()
                    binding.etAccountPassword.setText("")
                    updateGoogleLinkStatus()
                } else {
                    Toast.makeText(this, "Error al crear contraseña: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPhotoOptionsDialog() {
        val options = arrayOf("📸 Tomar Foto", "🖼️ Elegir de Galería")
        AlertDialog.Builder(this)
            .setTitle("Cambiar Foto de Perfil")
            .setItems(options) { _, which ->
                if (which == 0) checkCameraPermission() else pickImageLauncher.launch("image/*")
            }
            .show()
    }

    private fun checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile = File(getExternalFilesDir(null), "profile_edit_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile!!)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
    }

    private fun detectCurrentCity() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
            return
        }

        val loading = AlertDialog.Builder(this).setMessage("Detectando ubicación...").show()
        
        LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { loc ->
            loading.dismiss()
            if (loc != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val city = addr.locality ?: addr.subAdminArea ?: ""
                        val state = addr.adminArea ?: ""
                        val country = addr.countryName ?: ""
                        val postalCode = addr.postalCode ?: ""
                        
                        val displayText = listOfNotNull(city, state, country, postalCode)
                            .filter { it.isNotEmpty() }
                            .joinToString(", ")
                        
                        binding.etAddress.setText(displayText)
                        Toast.makeText(this, "Ubicación detectada: $city", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al obtener ciudad", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No se pudo obtener ubicación GPS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateAndSaveProfile() {
        val fullName = binding.etFullName.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val dni = binding.etDni.text.toString().trim()
        val address = binding.etAddress.text.toString().trim()

        if (fullName.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Nombre y Celular son obligatorios ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        saveAllProfileInfo(fullName, phone, dni, address)
    }

    private fun saveAllProfileInfo(fullName: String, phone: String, dni: String, address: String) {
        val loading = AlertDialog.Builder(this).setMessage("Guardando cambios de cuenta...").setCancelable(false).show()

        lifecycleScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val oldPhone = appPrefs.getString("user_phone", "")
                if (phone != oldPhone) {
                    val duplicatePhoneQuery = db.collection("users").whereEqualTo("phone", phone).get().await()
                    val duplicate = duplicatePhoneQuery.documents.find { it.id != userId }
                    if (duplicate != null) {
                        loading.dismiss()
                        Toast.makeText(this@AccountSettingsActivity, "Este número de celular ya está registrado 🚫", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                var finalPhotoUrl = initialPhotoUrl ?: ""

                selectedPhotoUri?.let { uri ->
                    val file = if (uri.scheme == "file") File(uri.path!!) else saveUriToFile(uri)
                    file?.let {
                        val path = "profiles/$userId/profile_edit.jpg"
                        finalPhotoUrl = SupabaseManager.uploadImage("naxor-imagenes-usuarios", path, it) ?: finalPhotoUrl
                    }
                }

                val updates = mapOf(
                    "name" to fullName,
                    "photoUrl" to finalPhotoUrl,
                    "user_photo_url" to finalPhotoUrl,
                    "phone" to phone,
                    "user_phone" to phone,
                    "dni" to dni,
                    "address" to address,
                    "user_address" to address,
                    "email" to (auth.currentUser?.email ?: ""),
                    "updatedAt" to System.currentTimeMillis()
                )

                db.collection("users").document(userId).collection("profile").document("info")
                    .set(updates, SetOptions.merge()).await()

                db.collection("users").document(userId)
                    .set(updates, SetOptions.merge()).await()

                appPrefs.edit().apply {
                    putString("user_full_name", fullName)
                    putString("user_photo_url", finalPhotoUrl)
                    putString("user_phone", phone)
                    putString("user_dni", dni)
                    putString("user_address", address)
                    apply()
                }

                withContext(Dispatchers.Main) {
                    loading.dismiss()
                    Toast.makeText(this@AccountSettingsActivity, "¡Información de cuenta actualizada! ✨", Toast.LENGTH_SHORT).show()
                    loadUserData()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading.dismiss()
                    Toast.makeText(this@AccountSettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveUriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(cacheDir, "temp_account_settings_profile.jpg")
            file.outputStream().use { output -> inputStream?.copyTo(output) }
            file
        } catch (e: Exception) { null }
    }

    private fun switchSystem(store: StoreModel) {
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
                    AppDatabase.getDatabase(this@AccountSettingsActivity).clearAllTables()
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

                SyncManager(this@AccountSettingsActivity).downloadBusinessSettingsFromCloud(store.businessType)
                SyncManager(this@AccountSettingsActivity).scheduleOfflineSync()

                loading.dismiss()

                val intent = Intent(this@AccountSettingsActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
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
                    AppDatabase.getDatabase(this@AccountSettingsActivity).clearAllTables()
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

                loading.dismiss()
                val intent = Intent(this@AccountSettingsActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                loading.dismiss()
            }
        }
    }

    private fun showSignOutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cerrar Sesión 🚪")
            .setMessage("¿Estás seguro de que deseas cerrar sesión en tu cuenta?")
            .setPositiveButton("Sí, Cerrar Sesión") { _, _ ->
                signOutAndGoToLogin()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun signOutAndGoToLogin() {
        lifecycleScope.launch {
            try {
                auth.signOut()
                SessionManager.clearSessionData(this@AccountSettingsActivity)
                getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("BuyerPrefs", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("CollabPermissions", MODE_PRIVATE).edit().clear().apply()
                CartManager.clear()

                val intent = Intent(this@AccountSettingsActivity, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AccountSettingsActivity, "Error al cerrar sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteAccountConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar Cuenta Definitivamente? ⚠️")
            .setMessage("Esta acción es IRREVERSIBLE. Se borrarán permanentemente:\n\n" +
                    "• Tu perfil de usuario y configuraciones.\n" +
                    "• Todos tus negocios, productos e inventario.\n" +
                    "• Todas las fotos subidas a la nube de Supabase.\n" +
                    "• Tus conversaciones, chats y mensajes.\n" +
                    "• Tus ubicaciones registradas e historial.\n\n" +
                    "¿Estás seguro de que deseas continuar?")
            .setPositiveButton("Sí, Eliminar Todo") { _, _ ->
                confirmFinalAccountDeletion()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmFinalAccountDeletion() {
        AlertDialog.Builder(this)
            .setTitle("Confirmación Final 🗑️")
            .setMessage("Por seguridad, presiona 'Confirmar' para iniciar el borrado total e irrecuperable de tu cuenta.")
            .setPositiveButton("Confirmar y Borrar") { _, _ ->
                executeFullAccountDeletion()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun executeFullAccountDeletion() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay sesión activa", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val userId = user.uid
        val userEmail = user.email?.lowercase() ?: ""

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Borrando Cuenta y Datos ⏳")
            .setMessage("Eliminando perfiles, inventario, chats e imágenes de la nube de Supabase. Por favor no cierres la app...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val supabasePaths = listOf(
                        "profiles/$userId.jpg",
                        "user_photos/$userId.jpg",
                        "logos/$userId/PRODUCTS.png",
                        "logos/$userId/SERVICES.png",
                        "logos/$userId/LOANS.png",
                        "covers/$userId/PRODUCTS.png",
                        "covers/$userId/SERVICES.png",
                        "covers/$userId/LOANS.png"
                    )
                    SupabaseManager.deleteFiles("naxor-imagenes-usuarios", supabasePaths)
                }

                val userRef = db.collection("users").document(userId)
                val subcollections = listOf("profile", "settings", "locations", "reviews", "messages", "blocked_users", "inventory", "hotel_rooms")
                for (sub in subcollections) {
                    val snap = userRef.collection(sub).get().await()
                    val batch = db.batch()
                    for (doc in snap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
                userRef.delete().await()

                if (userEmail.isNotEmpty()) {
                    val keysSnap = db.collection("access_keys")
                        .whereEqualTo("email", userEmail)
                        .get().await()
                    val batch = db.batch()
                    for (doc in keysSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }

                val directChatsSnap = db.collection("direct_chats")
                    .whereArrayContains("participants", userId)
                    .get().await()

                for (chatDoc in directChatsSnap.documents) {
                    val msgsSnap = chatDoc.reference.collection("messages").get().await()
                    val batch = db.batch()
                    for (msg in msgsSnap.documents) {
                        batch.delete(msg.reference)
                    }
                    batch.delete(chatDoc.reference)
                    batch.commit().await()
                }

                val ordersSnap = db.collection("orders")
                    .whereEqualTo("buyerId", userId)
                    .get().await()

                for (orderDoc in ordersSnap.documents) {
                    val msgsSnap = orderDoc.reference.collection("messages").get().await()
                    val batch = db.batch()
                    for (msg in msgsSnap.documents) {
                        batch.delete(msg.reference)
                    }
                    batch.delete(orderDoc.reference)
                    batch.commit().await()
                }

                withContext(Dispatchers.IO) {
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("BuyerPrefs", MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("CollabPermissions", MODE_PRIVATE).edit().clear().apply()
                    CartManager.clear()

                    val filesDir = getExternalFilesDir(null)
                    filesDir?.walkTopDown()?.forEach { if (it.isFile) it.delete() }
                    cacheDir.walkTopDown().forEach { if (it.isFile) it.delete() }
                }

                try {
                    user.delete().await()
                } catch (authError: Exception) {
                    Log.w("AccountSettings", "Auth delete requiere re-autenticación reciente: ${authError.message}")
                    auth.signOut()
                }

                progressDialog.dismiss()
                Toast.makeText(this@AccountSettingsActivity, "Tu cuenta y datos fueron eliminados totalmente 🗑️", Toast.LENGTH_LONG).show()

                val intent = Intent(this@AccountSettingsActivity, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("AccountSettings", "Error durante eliminación de cuenta: ${e.message}")
                Toast.makeText(this@AccountSettingsActivity, "Error al eliminar datos: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        profileRegistration?.remove()
    }

    inner class SystemsAdapter(private val onClick: (StoreModel) -> Unit) : RecyclerView.Adapter<SystemsAdapter.ViewHolder>() {
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