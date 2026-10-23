package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.gomezmart.app.databinding.ActivityLoginBinding
import com.gomezmart.app.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class EnvironmentItem(
    val id: String,
    val name: String,
    val businessType: String,
    val logoUrl: String? = null,
    val isCollab: Boolean = false,
    val role: String = "EQUIPO",
    val isOnline: Boolean = false
)

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var isLoginMode = true

    private val googleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, gso)
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Toast.makeText(this, "Error de Google: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Si venimos forzados desde la selección de rol, mostrar registro
        if (intent.getBooleanExtra("FORCE_REGISTER", false)) {
            isLoginMode = false
            updateUI()
        }

        setupListeners()
        animateEntry()
        setupValidation()
    }

    private fun setupValidation() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateButtonState() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        binding.etLoginEmail.addTextChangedListener(watcher)
        binding.etLoginPassword.addTextChangedListener(watcher)
        binding.etConfirmPassword.addTextChangedListener(watcher)
        
        updateButtonState()
    }

    private fun updateButtonState() {
        val email = binding.etLoginEmail.text.toString().trim()
        val password = binding.etLoginPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val isPasswordValid = password.length >= 6
        
        val isValid = if (isLoginMode) {
            isEmailValid && isPasswordValid
        } else {
            isEmailValid && isPasswordValid && password == confirmPassword && confirmPassword.isNotEmpty()
        }

        binding.btnLoginAction.isEnabled = isValid
        
        val color = if (isValid) {
            ContextCompat.getColor(this, R.color.purple_600)
        } else {
            ContextCompat.getColor(this, R.color.slate_300)
        }
        binding.btnLoginAction.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun animateEntry() {
        binding.cardLogoContainer.alpha = 0f
        binding.cardLogoContainer.scaleX = 0.5f
        binding.cardLogoContainer.scaleY = 0.5f
        
        binding.cardInfoLogin.alpha = 0f
        binding.cardInfoLogin.translationY = 100f
        
        binding.layoutForm.alpha = 0f
        
        binding.cardLogoContainer.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(1000)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        binding.cardInfoLogin.animate()
            .alpha(1f).translationY(0f)
            .setDuration(800)
            .setStartDelay(200)
            .start()

        binding.layoutForm.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(400)
            .start()
    }

    private fun setupListeners() {
        binding.btnToggleLoginMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateUI()
        }

        binding.btnLoginAction.setOnClickListener {
            val email = binding.etLoginEmail.text.toString().trim()
            val password = binding.etLoginPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isLoginMode) {
                login(email, password)
            } else {
                val confirm = binding.etConfirmPassword.text.toString().trim()
                if (password != confirm) {
                    Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                register(email, password)
            }
        }

        binding.btnGoogleSignIn.setOnClickListener {
            signInWithGoogle()
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.btnContinueGuest.setOnClickListener {
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                .putString("user_role", "BUYER")
                .apply()
            startActivity(Intent(this, BuyerHomeActivity::class.java))
            finish()
        }
    }

    private fun signInWithGoogle() {
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val loading = AlertDialog.Builder(this)
            .setMessage("Accediendo con Google... 🚀")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener(this@LoginActivity) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user == null) {
                        loading.dismiss()
                        Toast.makeText(this@LoginActivity, "Error al obtener usuario de Google", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    val uid = user.uid
                    val email = user.email ?: ""
                    val googleName = user.displayName ?: ""
                    val googlePhoto = user.photoUrl?.toString() ?: ""

                    // Consultar Firestore para verificar perfil (Subcolección y Raíz)
                    db.collection("users").document(uid).collection("profile").document("info")
                        .get()
                        .addOnSuccessListener { profileDoc ->
                            var phone = profileDoc.getString("phone") ?: profileDoc.getString("user_phone") ?: ""
                            var name = profileDoc.getString("name") ?: profileDoc.getString("user_full_name") ?: googleName
                            var photo = profileDoc.getString("photoUrl") ?: profileDoc.getString("user_photo_url") ?: googlePhoto
                            val bizTitle = profileDoc.getString("businessTitle") ?: "Dueño de Negocio"

                            val finalizeGoogleAuth = {
                                val basicProfile = mutableMapOf<String, Any>(
                                    "name" to name,
                                    "photoUrl" to photo,
                                    "user_photo_url" to photo,
                                    "email" to email,
                                    "businessTitle" to bizTitle
                                )
                                if (phone.isNotBlank()) {
                                    basicProfile["phone"] = phone
                                    basicProfile["user_phone"] = phone
                                }

                                db.collection("users").document(uid).collection("profile").document("info")
                                    .set(basicProfile, SetOptions.merge())
                                db.collection("users").document(uid)
                                    .set(basicProfile, SetOptions.merge())

                                getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().apply {
                                    putString("user_full_name", name)
                                    putString("user_photo_url", photo)
                                    putString("user_email", email)
                                    if (phone.isNotBlank()) {
                                        putString("user_phone", phone)
                                        putBoolean("is_profile_verified", true)
                                    }
                                    apply()
                                }

                                // DETECCION INTELIGENTE: Buscar negocios propios y colaboraciones en Firestore
                                checkForUserSystemsAndProceed(loading, phone.length != 9)
                            }

                            if (phone.isBlank()) {
                                // Fallback a documento raíz de usuario si no estaba en la subcolección
                                db.collection("users").document(uid).get().addOnSuccessListener { rootDoc ->
                                    val rootPhone = rootDoc.getString("phone") ?: rootDoc.getString("user_phone") ?: ""
                                    if (rootPhone.isNotBlank()) phone = rootPhone
                                    val rootName = rootDoc.getString("name") ?: rootDoc.getString("user_full_name") ?: ""
                                    if (rootName.isNotBlank() && name == googleName) name = rootName
                                    val rootPhoto = rootDoc.getString("photoUrl") ?: rootDoc.getString("user_photo_url") ?: ""
                                    if (rootPhoto.isNotBlank() && photo == googlePhoto) photo = rootPhoto

                                    finalizeGoogleAuth()
                                }.addOnFailureListener {
                                    finalizeGoogleAuth()
                                }
                            } else {
                                finalizeGoogleAuth()
                            }
                        }
                        .addOnFailureListener {
                            loading.dismiss()
                            val intent = Intent(this@LoginActivity, ProfileSetupActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            startActivity(intent)
                            finish()
                        }
                } else {
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error al autenticar con Google", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun login(e: String, p: String) {
        val loading = AlertDialog.Builder(this).setMessage("Iniciando sesión...").setCancelable(false).show()
        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)
            auth.signInWithEmailAndPassword(e, p).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    checkForUserSystemsAndProceed(loading, false)
                } else {
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun register(e: String, p: String) {
        val loading = AlertDialog.Builder(this).setMessage("Creando cuenta...").setCancelable(false).show()
        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)
            auth.createUserWithEmailAndPassword(e, p).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    performFullSyncAndStart()
                } else {
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkForUserSystemsAndProceed(loadingDialog: AlertDialog?, requiresPhoneSetup: Boolean) {
        loadingDialog?.dismiss()
        if (requiresPhoneSetup) {
            val intent = Intent(this@LoginActivity, ProfileSetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finish()
        } else {
            val intent = Intent(this@LoginActivity, WelcomeRoleActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finish()
        }
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
            newTitle = "Dueño de ${item.name}"
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

        // Actualizar el título de perfil en Firestore para mantener coherencia total
        val user = auth.currentUser
        if (user != null) {
            val titleUpdate = mapOf("businessTitle" to newTitle)
            db.collection("users").document(user.uid).collection("profile").document("info")
                .set(titleUpdate, SetOptions.merge())
            db.collection("users").document(user.uid)
                .set(titleUpdate, SetOptions.merge())
        }

        performFullSyncAndStart()
    }

    private fun showRedesignedEnvironmentDialog(items: List<EnvironmentItem>) {
        if (isFinishing || isDestroyed) return

        val user = auth.currentUser
        val userPhoto = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_photo_url", user?.photoUrl?.toString())
        val userName = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("user_full_name", user?.displayName ?: "Usuario Gomezmart")
        val userEmail = user?.email ?: ""

        val rootLayout = findViewById<ViewGroup>(android.R.id.content)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_environment, rootLayout, false)
        val cardUserProfile = dialogView.findViewById<View>(R.id.cardUserDialogProfile)
        val ivUserPhoto = dialogView.findViewById<ImageView>(R.id.ivUserDialogPhoto)
        val tvUserName = dialogView.findViewById<TextView>(R.id.tvUserDialogName)
        val tvUserEmail = dialogView.findViewById<TextView>(R.id.tvUserDialogEmail)
        val rvEnvironments = dialogView.findViewById<RecyclerView>(R.id.rvEnvironmentsDialog)
        val btnAddNewSystem = dialogView.findViewById<View>(R.id.btnAddNewSystem)
        val btnCancelDialog = dialogView.findViewById<View>(R.id.btnCancelDialog)

        tvUserName?.text = userName
        tvUserEmail?.text = userEmail

        if (!userPhoto.isNullOrEmpty() && ivUserPhoto != null) {
            Glide.with(this).load(userPhoto).circleCrop().into(ivUserPhoto)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Ir al Mercado
        cardUserProfile?.setOnClickListener {
            dialog.dismiss()
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putString("user_role", "BUYER").apply()
            startActivity(Intent(this, BuyerHomeActivity::class.java))
            finish()
        }

        // Añadir nuevo sistema
        btnAddNewSystem?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, BusinessSelectionActivity::class.java))
            finish()
        }

        // Cancelar y cerrar sesión si se equivocó de cuenta
        btnCancelDialog?.setOnClickListener {
            dialog.dismiss()
            auth.signOut()
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit().clear().apply()
            recreate()
        }

        rvEnvironments?.layoutManager = LinearLayoutManager(this)
        rvEnvironments?.adapter = EnvironmentAdapter(items) { selectedItem ->
            dialog.dismiss()
            selectEnvironmentAndLaunch(selectedItem)
        }

        dialog.show()
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

    private fun performFullSyncAndStart() {
        val loading = AlertDialog.Builder(this)
            .setMessage("Sincronizando datos...")
            .setCancelable(false)
            .show()

        SyncManager(this).downloadEverythingFromCloud {
            loading.dismiss()
            checkBusinessConfigAndStart()
        }
    }

    private fun checkBusinessConfigAndStart() {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        
        val role = appPrefs.getString("user_role", null)
        val isVerified = appPrefs.getBoolean("is_profile_verified", false)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val bizType = bizPrefs.getString("business_type", null)

        if (!isVerified) {
            startActivity(Intent(this, ProfileSetupActivity::class.java))
            finish()
            return
        }

        if (bizType != null && (role == "SELLER" || role == "COLLABORATOR" || isCollab)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            // Al iniciar sesión, siempre verificar con WelcomeRoleActivity si la cuenta tiene datos de sistema guardados
            startActivity(Intent(this, WelcomeRoleActivity::class.java))
        }
        finish()
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(this).apply {
            hint = "correo@ejemplo.com"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        AlertDialog.Builder(this)
            .setTitle("Restablecer Contraseña 🔑")
            .setMessage("Ingresa el correo asociado a tu cuenta de GoMezMart:")
            .setView(input)
            .setPositiveButton("Enviar Correo") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    auth.sendPasswordResetEmail(email)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Correo de recuperación enviado ✅", Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this, "Ingresa un correo válido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateUI() {
        if (isLoginMode) {
            binding.tvLoginTitle.text = "Inicia Sesión"
            binding.btnLoginAction.text = "Iniciar Sesión"
            binding.btnToggleLoginMode.text = "¿No tienes cuenta? Regístrate aquí"
            binding.layoutConfirmPassword.visibility = View.GONE
            binding.btnForgotPassword.visibility = View.VISIBLE
        } else {
            binding.tvLoginTitle.text = "Crea tu Cuenta"
            binding.btnLoginAction.text = "Registrarme"
            binding.btnToggleLoginMode.text = "¿Ya tienes cuenta? Inicia sesión"
            binding.layoutConfirmPassword.visibility = View.VISIBLE
            binding.btnForgotPassword.visibility = View.GONE
        }
        updateButtonState()
    }
}
