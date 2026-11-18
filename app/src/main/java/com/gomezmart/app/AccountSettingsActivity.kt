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
    private var isUpdatingPrivacyUi = false
    private var currentMessagePrivacy = com.gomezmart.app.data.MessagePrivacy.EVERYONE_WITH_REQUEST

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
        val user = auth.currentUser
        val isGuest = user == null || user.isAnonymous || isGuestUser()

        if (isGuest || user == null) {
            binding.tvEditAccountEmail.text = "Sin correo (Modo Invitado)"
            binding.tvEditGoogleStatus.text = "No vinculado con Google"
            binding.tvEditGoogleStatus.setTextColor(getColor(R.color.slate_400))
            binding.ivGoogleLinkedCheck.visibility = View.GONE
            binding.btnLinkGoogle.visibility = View.VISIBLE
            binding.btnLinkGoogle.isEnabled = true
            binding.btnLinkGoogle.alpha = 1.0f
            binding.btnLinkGoogle.text = "Vincular"
            return
        }

        binding.tvEditAccountEmail.text = user.email ?: "Sin correo"
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
            binding.btnLinkGoogle.text = "Vincular"
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
                val linkedUser = auth.currentUser
                if (linkedUser != null) {
                    val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    val name = appPrefs.getString("user_full_name", null)?.takeIf { it.isNotBlank() } ?: linkedUser.displayName ?: ""
                    val photo = appPrefs.getString("user_photo_url", null)?.takeIf { it.isNotBlank() } ?: linkedUser.photoUrl?.toString() ?: ""
                    val email = linkedUser.email ?: ""

                    appPrefs.edit().apply {
                        putBoolean("is_guest", false)
                        if (name.isNotBlank()) putString("user_full_name", name)
                        if (photo.isNotBlank()) putString("user_photo_url", photo)
                        if (email.isNotBlank()) putString("user_email", email)
                        apply()
                    }

                    val updates = mutableMapOf<String, Any>("email" to email)
                    if (name.isNotBlank()) {
                        updates["name"] = name
                        updates["user_full_name"] = name
                    }
                    if (photo.isNotBlank()) {
                        updates["photoUrl"] = photo
                        updates["user_photo_url"] = photo
                    }
                    db.collection("users").document(linkedUser.uid).collection("profile").document("info")
                        .set(updates, SetOptions.merge())
                    db.collection("users").document(linkedUser.uid)
                        .set(updates, SetOptions.merge())

                    SyncManager(this).scheduleOfflineSync()
                    loadUserData()
                    loadSavedSystems()
                }
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

        // Manejar insets en el AppBarLayout (en lugar de comprimir el MaterialToolbar de altura fija)
        applyWindowInsets(binding.appBarAccountSettings, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)

        binding.toolbarAccountSettings.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadUserData()
        setupListeners()

        // Diferir consultas pesadas (Firestore y Room DB) hasta terminar la animación de entrada (180ms)
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) {
                syncRemoteProfileAndRole()
                loadSavedSystems()
            }
        }, 200L)
    }

    private fun setupRecyclerView() {
        systemsAdapter = SystemsAdapter { store ->
            switchSystem(store)
        }
        binding.rvSavedSystems.layoutManager = LinearLayoutManager(this)
        binding.rvSavedSystems.adapter = systemsAdapter

        // Pre-cargar el sistema local activo inmediatamente para evitar saltos de diseño tras la transición
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val activeType = bizPrefs.getString("business_type", null)
        if (!activeType.isNullOrEmpty()) {
            val isGuest = isGuestUser() || auth.currentUser == null || auth.currentUser?.isAnonymous == true
            val userId = if (isGuest) "guest" else (auth.currentUser?.uid ?: "")
            val activeName = bizPrefs.getString("${activeType}_name", "")
                ?.takeIf { it.isNotBlank() }
                ?: bizPrefs.getString("business_name", "")
                ?.takeIf { it.isNotBlank() }
                ?: when (activeType) {
                    "LOANS" -> "Sistema de Préstamos"
                    "HOTEL" -> "Hospedaje y Hotel"
                    else -> "Tienda de Productos"
                }
            val activeLogo = bizPrefs.getString("${activeType}_logo_url", "")
                ?: bizPrefs.getString("business_logo_url", "")
            val isOnline = if (isGuest) false else bizPrefs.getBoolean("is_online_store", false)

            binding.rvSavedSystems.visibility = View.VISIBLE
            binding.layoutEmptySystems.visibility = View.GONE
            systemsAdapter.setItems(
                listOf(
                    StoreModel(
                        id = userId,
                        name = activeName,
                        businessType = activeType.uppercase(),
                        logoUrl = activeLogo,
                        address = "",
                        phone = if (isOnline) "ONLINE" else "OFFLINE"
                    )
                )
            )
        }
    }

    private fun loadUserData() {
        val user = auth.currentUser
        val isGuest = isGuestUser() || user == null || user.isAnonymous

        if (isGuest) {
            binding.cardGuestBannerAccountSettings.visibility = View.VISIBLE
            binding.btnGuestLoginInSettings.setOnClickListener {
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }

            binding.tvProfileNameDisplay.text = "Modo Invitado"
            binding.tvProfileEmailDisplay.text = "Sin cuenta registrada"
            binding.tvProfileInitial.text = "?"
            binding.tvUserAccountType.text = "INVITADO"

            // Ocultar campos de información personal, privacidad y contraseña para Modo Invitado
            binding.tvHeaderPersonalInfo.visibility = View.GONE
            binding.cardPersonalInfoContainer.visibility = View.GONE
            binding.tvHeaderPrivacySettings.visibility = View.GONE
            binding.cardPrivacySettingsContainer.visibility = View.GONE
            binding.cardPasswordSettingsContainer.visibility = View.GONE

            binding.btnChangeProfilePhoto.isEnabled = false
            binding.btnChangeProfilePhoto.alpha = 0.5f

            binding.btnLinkGoogle.isEnabled = true
            binding.btnLinkGoogle.alpha = 1.0f

            binding.tvSignOutLabel.text = "Salir del Modo Invitado"

            updateGoogleLinkStatus()
            return
        } else {
            binding.cardGuestBannerAccountSettings.visibility = View.GONE
            binding.tvHeaderPersonalInfo.visibility = View.VISIBLE
            binding.cardPersonalInfoContainer.visibility = View.VISIBLE
            binding.tvHeaderPrivacySettings.visibility = View.VISIBLE
            binding.cardPrivacySettingsContainer.visibility = View.VISIBLE
            binding.cardPasswordSettingsContainer.visibility = View.VISIBLE

            binding.etFullName.isEnabled = true
            binding.etDni.isEnabled = true
            binding.etPhone.isEnabled = true
            binding.etAddress.isEnabled = true
            binding.btnSaveProfile.isEnabled = true
            binding.btnSaveProfile.alpha = 1.0f

            binding.btnChangeProfilePhoto.isEnabled = true
            binding.btnChangeProfilePhoto.alpha = 1.0f

            binding.btnLinkGoogle.isEnabled = true
            binding.btnLinkGoogle.alpha = 1.0f

            binding.etAccountPassword.isEnabled = true
            binding.btnSaveAccountPassword.isEnabled = true
            binding.btnSaveAccountPassword.alpha = 1.0f

            binding.tvSignOutLabel.text = "Cerrar Sesión"
        }

        binding.tvProfileEmailDisplay.text = user.email
        updateGoogleLinkStatus()

        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val nameToDisplay = com.gomezmart.app.util.UserNameGenerator.getOrGenerateName(this, appPrefs.getString("user_full_name", user.displayName), user.uid)

        val textInputLayouts = listOf(binding.etFullName, binding.etDni, binding.etPhone, binding.etAddress)
            .mapNotNull { it.parent?.parent as? com.google.android.material.textfield.TextInputLayout }
        textInputLayouts.forEach { it.isHintAnimationEnabled = false }

        binding.etFullName.setText(nameToDisplay)
        binding.tvProfileNameDisplay.text = nameToDisplay
        binding.tvProfileInitial.text = if (nameToDisplay.isNotEmpty()) nameToDisplay[0].uppercaseChar().toString() else "?"

        binding.etPhone.setText(appPrefs.getString("user_phone", ""))
        binding.etDni.setText(appPrefs.getString("user_dni", ""))
        binding.etAddress.setText(appPrefs.getString("user_address", ""))

        binding.root.post {
            textInputLayouts.forEach { it.isHintAnimationEnabled = true }
        }

        initialPhotoUrl = appPrefs.getString("user_photo_url", user.photoUrl?.toString())
        if (!initialPhotoUrl.isNullOrEmpty()) {
            binding.ivProfileImage.visibility = View.VISIBLE
            Glide.with(this).load(initialPhotoUrl).circleCrop().into(binding.ivProfileImage)
        } else {
            binding.ivProfileImage.visibility = View.GONE
        }

        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val hasLocalBiz = !bizPrefs.getString("business_type", "").isNullOrEmpty()
        val isOnlineLocal = bizPrefs.getBoolean("is_online_store", false)
        val isCollabLocal = bizPrefs.getBoolean("is_collaborator_mode", false)
        binding.tvUserAccountType.text = when {
            isCollabLocal -> "COLABORADOR"
            hasLocalBiz && isOnlineLocal -> "DUEÑO DE NEGOCIO"
            hasLocalBiz -> "ADMINISTRADOR"
            else -> "ADMINISTRADOR"
        }

        isUpdatingPrivacyUi = true
        binding.switchAllowDirectorySearch.isChecked = appPrefs.getBoolean("allow_directory_search", true)
        binding.switchShareContactWithFriends.isChecked = appPrefs.getBoolean("share_contact_with_friends", false)
        currentMessagePrivacy = com.gomezmart.app.data.MessagePrivacy.fromValue(
            appPrefs.getString("message_privacy", com.gomezmart.app.data.MessagePrivacy.EVERYONE_WITH_REQUEST.value)
        )
        binding.tvMessagePrivacyCurrent.text = currentMessagePrivacy.label
        isUpdatingPrivacyUi = false
    }

    private fun syncRemoteProfileAndRole() {
        val user = auth.currentUser ?: return
        if (isGuestUser() || user.isAnonymous) return
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        // Escuchar cambios en tiempo real del perfil en Firestore
        profileRegistration?.remove()
        profileRegistration = db.collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (isFinishing || isDestroyed || snapshot == null) return@addSnapshotListener

                val firestoreName = snapshot.getString("name")
                val firestorePhoto = snapshot.getString("photoUrl")
                val firestoreEmail = snapshot.getString("email") ?: user.email

                val finalName = com.gomezmart.app.util.UserNameGenerator.getOrGenerateName(this, firestoreName ?: user.displayName, user.uid)
                val finalPhoto = firestorePhoto ?: user.photoUrl?.toString()

                binding.tvProfileNameDisplay.text = finalName
                if (binding.etFullName.text?.toString() != finalName && !binding.etFullName.hasFocus()) {
                    binding.etFullName.setText(finalName)
                }
                binding.tvProfileEmailDisplay.text = firestoreEmail
                binding.tvProfileInitial.text = if (finalName.isNotEmpty()) finalName[0].uppercaseChar().toString() else "?"

                if (!finalPhoto.isNullOrEmpty()) {
                    binding.ivProfileImage.visibility = View.VISIBLE
                    if (finalPhoto != initialPhotoUrl) {
                        initialPhotoUrl = finalPhoto
                        Glide.with(this).load(finalPhoto).circleCrop().into(binding.ivProfileImage)
                    }
                } else {
                    initialPhotoUrl = null
                    binding.ivProfileImage.visibility = View.GONE
                }

                val allowDir = snapshot.getBoolean("allowDirectorySearch") ?: true
                val shareContact = snapshot.getBoolean("shareContactWithFriends") ?: false
                val msgPrivacy = com.gomezmart.app.data.MessagePrivacy.fromValue(snapshot.getString("messagePrivacy"))

                isUpdatingPrivacyUi = true
                binding.switchAllowDirectorySearch.isChecked = allowDir
                binding.switchShareContactWithFriends.isChecked = shareContact
                currentMessagePrivacy = msgPrivacy
                binding.tvMessagePrivacyCurrent.text = msgPrivacy.label
                isUpdatingPrivacyUi = false

                appPrefs.edit()
                    .putBoolean("allow_directory_search", allowDir)
                    .putBoolean("share_contact_with_friends", shareContact)
                    .putString("message_privacy", msgPrivacy.value)
                    .apply()
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
                    if (isFinishing || isDestroyed) return@withContext
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
                    if (!isFinishing && !isDestroyed) {
                        binding.tvUserAccountType.text = "ADMINISTRADOR"
                    }
                }
            }
        }
    }

    private fun loadSavedSystems() {
        val user = auth.currentUser
        val isGuest = isGuestUser() || user == null || user.isAnonymous
        val userId = if (isGuest) "guest" else (user?.uid ?: "")
        val userEmail = if (isGuest) "" else (user?.email?.lowercase() ?: "")
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val roomDb = AppDatabase.getDatabase(this)
        val dynamicManager = com.gomezmart.app.utils.DynamicModuleManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val items = mutableListOf<StoreModel>()

            // 1. Cargar desde SharedPreferences locales (El sistema activo actual)
            try {
                val activeType = bizPrefs.getString("business_type", null)
                if (!activeType.isNullOrEmpty()) {
                    val module = when(activeType) {
                        "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
                        "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
                        else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
                    }
                    if (dynamicManager.isModuleInstalled(module)) {
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
                        val isOnline = if (isGuest) false else bizPrefs.getBoolean("is_online_store", false)

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
                    }
                }
            } catch (e: Exception) { }

            if (!isGuest && userId.isNotEmpty()) {
                // 2. Cargar desde Firestore settings con timeout (Solo cuentas registradas)
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

                        val module = when(type.uppercase()) {
                            "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
                            "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
                            else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
                        }
                        if (dynamicManager.isModuleInstalled(module) && name.isNotEmpty() && !name.equals("Mi Negocio", ignoreCase = true)) {
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

                // 3. Cargar Colaboraciones de Firestore con timeout (Solo cuentas registradas)
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
            }

            // 4. Cargar sistemas con datos locales Room DB si aún no están listados (solo si el módulo está instalado)
            try {
                if (dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.LOANS)) {
                    val loansCount = roomDb.loanDao().getAllLoansOnce().size
                    val clientsCount = roomDb.loanDao().getAllClientsOnce().size
                    if ((loansCount > 0 || clientsCount > 0) && items.none { it.businessType == "LOANS" }) {
                        val name = bizPrefs.getString("LOANS_name", "")?.takeIf { it.isNotBlank() } ?: "Sistema de Préstamos"
                        items.add(StoreModel(id = userId.ifEmpty { "guest" }, name = name, businessType = "LOANS", phone = "OFFLINE"))
                    }
                }

                if (dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.HOTEL)) {
                    val roomsCount = roomDb.hotelDao().getAllRoomsOnce().size
                    if (roomsCount > 0 && items.none { it.businessType == "HOTEL" }) {
                        val name = bizPrefs.getString("HOTEL_name", "")?.takeIf { it.isNotBlank() } ?: "Hospedaje y Hotel"
                        items.add(StoreModel(id = userId.ifEmpty { "guest" }, name = name, businessType = "HOTEL", phone = "OFFLINE"))
                    }
                }

                if (dynamicManager.isModuleInstalled(com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS)) {
                    val productsCount = roomDb.productDao().allProducts.size
                    if (productsCount > 0 && items.none { it.businessType == "PRODUCTS" }) {
                        val name = bizPrefs.getString("PRODUCTS_name", "")?.takeIf { it.isNotBlank() } ?: "Tienda de Productos"
                        items.add(StoreModel(id = userId.ifEmpty { "guest" }, name = name, businessType = "PRODUCTS", phone = "OFFLINE"))
                    }
                }
            } catch (e: Exception) { }

            val fullList = items.distinctBy { "${it.businessType}_${it.id}" }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (fullList.isEmpty()) {
                    binding.rvSavedSystems.visibility = View.GONE
                    binding.layoutEmptySystems.visibility = View.VISIBLE
                } else {
                    binding.rvSavedSystems.visibility = View.VISIBLE
                    binding.layoutEmptySystems.visibility = View.GONE
                    systemsAdapter.setItems(fullList)
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnChangeProfilePhoto.setOnClickListener { showPhotoOptionsDialog() }
        binding.btnSaveProfile.setOnClickListener { validateAndSaveProfile() }
        binding.btnSaveAccountPassword.setOnClickListener { validateAndSavePassword() }
        
        binding.layoutAddress.setEndIconOnClickListener { detectCurrentCity() }
        binding.btnLinkGoogle.setOnClickListener { startGoogleSignInForLinking() }

        binding.btnDownloadManagementSystem.setOnClickListener {
            val intent = Intent(this, DownloadedModulesActivity::class.java)
            startActivity(intent)
        }
        binding.btnManageAllModules.setOnClickListener {
            val intent = Intent(this, DownloadedModulesActivity::class.java)
            startActivity(intent)
        }

        binding.cardCheckUpdateAccount.setOnClickListener {
            Updater(this).checkAndDownload(autoCheck = false)
        }

        binding.cardSignOutAccount.setOnClickListener { showSignOutConfirmationDialog() }
        binding.cardOpenDeleteOptions.setOnClickListener {
            val intent = Intent(this, DeleteDataOrAccountActivity::class.java)
            startActivity(intent)
        }

        binding.switchAllowDirectorySearch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingPrivacyUi) savePrivacySettings(allowDirectory = isChecked)
        }
        binding.switchShareContactWithFriends.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingPrivacyUi) savePrivacySettings(shareContact = isChecked)
        }
        binding.layoutMessagePrivacySelector.setOnClickListener {
            showMessagePrivacyDialog()
        }
    }

    private fun showMessagePrivacyDialog() {
        val options = com.gomezmart.app.data.MessagePrivacy.entries
        val labels = options.map { it.label }.toTypedArray()
        val currentIndex = options.indexOf(currentMessagePrivacy).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Quién puede enviarme mensajes 🔒")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen = options[which]
                currentMessagePrivacy = chosen
                binding.tvMessagePrivacyCurrent.text = chosen.label
                savePrivacySettings(messagePrivacy = chosen)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun savePrivacySettings(
        allowDirectory: Boolean = binding.switchAllowDirectorySearch.isChecked,
        shareContact: Boolean = binding.switchShareContactWithFriends.isChecked,
        messagePrivacy: com.gomezmart.app.data.MessagePrivacy = currentMessagePrivacy
    ) {
        val userId = auth.currentUser?.uid ?: return
        if (isGuestUser()) return

        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .putBoolean("allow_directory_search", allowDirectory)
            .putBoolean("share_contact_with_friends", shareContact)
            .putString("message_privacy", messagePrivacy.value)
            .apply()

        val privacyUpdates = mapOf(
            "allowDirectorySearch" to allowDirectory,
            "shareContactWithFriends" to shareContact,
            "messagePrivacy" to messagePrivacy.value,
            "updatedAt" to System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("users").document(userId).collection("profile").document("info")
                    .set(privacyUpdates, SetOptions.merge()).await()
                db.collection("users").document(userId)
                    .set(privacyUpdates, SetOptions.merge()).await()
                Toast.makeText(this@AccountSettingsActivity, "Privacidad actualizada 🔒", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AccountSettingsActivity, "No se pudo guardar privacidad en la nube", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateAndSavePassword() {
        if (requireLoginForGuest("configurar una contraseña")) return
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
        if (requireLoginForGuest("cambiar tu foto de perfil")) return
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
        if (requireLoginForGuest("guardar tu perfil en la nube")) return
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
        if (isGuestUser()) return
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
                    "allowDirectorySearch" to binding.switchAllowDirectorySearch.isChecked,
                    "shareContactWithFriends" to binding.switchShareContactWithFriends.isChecked,
                    "messagePrivacy" to currentMessagePrivacy.value,
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
        ensureModuleInstalled(store.businessType) {
            if (store.phone == "COLLAB") {
                switchToCollaborativeEnvironment(store.id, store.businessType, store.name, store.logoUrl)
                return@ensureModuleInstalled
            }

            val loading = AlertDialog.Builder(this)
                .setMessage("Cambiando a ${store.name}...")
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                try {
                    if (!isGuestUser()) {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(this@AccountSettingsActivity).clearAllTables()
                        }
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

                if (!isGuestUser()) {
                    SyncManager(this@AccountSettingsActivity).downloadBusinessSettingsFromCloud(store.businessType)
                    SyncManager(this@AccountSettingsActivity).downloadEverythingFromCloud {
                        if (!isFinishing && !isDestroyed) {
                            loading.dismiss()
                            val intent = Intent(this@AccountSettingsActivity, MainActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                    }
                } else {
                    loading.dismiss()
                    val intent = Intent(this@AccountSettingsActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                } catch (e: Exception) {
                    loading.dismiss()
                }
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

                SyncManager(this@AccountSettingsActivity).downloadEverythingFromCloud {
                    if (!isFinishing && !isDestroyed) {
                        loading.dismiss()
                        val intent = Intent(this@AccountSettingsActivity, MainActivity::class.java)
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
                SessionManager.signOutCompletely(this@AccountSettingsActivity)

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