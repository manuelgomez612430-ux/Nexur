package com.gomezmart.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.databinding.ActivityLoginBinding
import com.gomezmart.app.util.FavoritesManager
import com.gomezmart.app.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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

    private val googleSignInClient by lazy {
        val webClientId = try {
            val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
            if (resId != 0) getString(resId) else "147763245278-arn7adv7ko9jhdrfli1os4fqa8fqtb8f.apps.googleusercontent.com"
        } catch (_: Exception) {
            "147763245278-arn7adv7ko9jhdrfli1os4fqa8fqtb8f.apps.googleusercontent.com"
        }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, gso)
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (!idToken.isNullOrEmpty()) {
                firebaseAuthWithGoogle(idToken)
            } else {
                Toast.makeText(this, "No se pudo obtener la credencial de Google", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google Sign In ApiException code ${e.statusCode}: ${e.message}")
            if (e.statusCode != 12501) { // 12501 = usuario canceló la selección de cuenta
                Toast.makeText(this, "Error de Google (${e.statusCode}): ${e.message ?: "Verifica tu conexión y Play Services"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SessionManager.setLoginInProgress(this, false)

        // Si venimos forzados desde la selección de rol, ir al registro
        if (intent.getBooleanExtra("FORCE_REGISTER", false)) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
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

        updateButtonState()
    }

    private fun updateButtonState() {
        val email = binding.etLoginEmail.text.toString().trim()
        val password = binding.etLoginPassword.text.toString().trim()

        val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val isPasswordValid = password.length >= 6
        val isValid = isEmailValid && isPasswordValid

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
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnLoginAction.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "Sin conexión a internet. Verifica tu red.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val email = binding.etLoginEmail.text.toString().trim()
            val password = binding.etLoginPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            login(email, password)
        }

        binding.btnGoogleSignIn.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "Sin conexión a internet. Verifica tu red.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            signInWithGoogle()
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.btnContinueGuest.setOnClickListener {
            SessionManager.setLoginInProgress(this, false)
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                .putString("user_role", "BUYER")
                .putBoolean("is_guest", true)
                .apply()

            try {
                auth.signInAnonymously()
            } catch (_: Exception) {}

            val intent = Intent(this@LoginActivity, BuyerHomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun signInWithGoogle() {
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val loading = AlertDialog.Builder(this)
            .setView(android.widget.ProgressBar(this).apply { setPadding(50, 50, 50, 50) })
            .setMessage("Accediendo con Google... 🚀")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)
            SessionManager.setLoginInProgress(this@LoginActivity, true)

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener(this@LoginActivity) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user == null) {
                        SessionManager.setLoginInProgress(this@LoginActivity, false)
                        loading.dismiss()
                        Toast.makeText(this@LoginActivity, "Error al obtener usuario de Google", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    syncUserProfileAndProceed(user, loading, isGoogleSignIn = true)
                } else {
                    SessionManager.setLoginInProgress(this@LoginActivity, false)
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error al autenticar con Google", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun login(e: String, p: String) {
        val loading = AlertDialog.Builder(this)
            .setView(android.widget.ProgressBar(this).apply { setPadding(50, 50, 50, 50) })
            .setMessage("Iniciando sesión...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)
            SessionManager.setLoginInProgress(this@LoginActivity, true)

            auth.signInWithEmailAndPassword(e, p).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        syncUserProfileAndProceed(user, loading, isGoogleSignIn = false)
                    } else {
                        SessionManager.setLoginInProgress(this@LoginActivity, false)
                        loading.dismiss()
                        Toast.makeText(this@LoginActivity, "No se pudo iniciar sesión", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    SessionManager.setLoginInProgress(this@LoginActivity, false)
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun syncUserProfileAndProceed(
        user: FirebaseUser,
        loading: AlertDialog,
        isGoogleSignIn: Boolean
    ) {
        lifecycleScope.launch {
            val uid = user.uid
            val email = user.email ?: ""
            val defaultName = user.displayName ?: ""
            val defaultPhoto = user.photoUrl?.toString() ?: ""

            try {
                val profileDoc = db.collection("users").document(uid).collection("profile").document("info").get().await()
                val rootDoc = db.collection("users").document(uid).get().await()

                val cloudName = profileDoc.getString("name")
                    ?: profileDoc.getString("user_full_name")
                    ?: rootDoc.getString("name")
                    ?: rootDoc.getString("user_full_name")
                    ?: ""

                val cloudEmail = profileDoc.getString("email")
                    ?: profileDoc.getString("user_email")
                    ?: rootDoc.getString("email")
                    ?: rootDoc.getString("user_email")
                    ?: email

                val hasExistingAccountInCloud = SessionManager.isProfileComplete(cloudName, cloudEmail)

                val name = cloudName.ifBlank { defaultName }
                val photo = profileDoc.getString("photoUrl")
                    ?: profileDoc.getString("user_photo_url")
                    ?: rootDoc.getString("photoUrl")
                    ?: rootDoc.getString("user_photo_url")
                    ?: defaultPhoto
                val phone = profileDoc.getString("phone")
                    ?: profileDoc.getString("user_phone")
                    ?: rootDoc.getString("phone")
                    ?: rootDoc.getString("user_phone")
                    ?: ""
                val dni = profileDoc.getString("dni")
                    ?: profileDoc.getString("user_dni")
                    ?: rootDoc.getString("dni")
                    ?: rootDoc.getString("user_dni")
                    ?: ""
                val address = profileDoc.getString("address")
                    ?: profileDoc.getString("user_address")
                    ?: rootDoc.getString("address")
                    ?: rootDoc.getString("user_address")
                    ?: ""
                val allowDirectorySearch = profileDoc.getBoolean("allowDirectorySearch")
                    ?: rootDoc.getBoolean("allowDirectorySearch")
                    ?: true
                val shareContactWithFriends = profileDoc.getBoolean("shareContactWithFriends")
                    ?: rootDoc.getBoolean("shareContactWithFriends")
                    ?: false
                val messagePrivacy = profileDoc.getString("messagePrivacy")
                    ?: rootDoc.getString("messagePrivacy")
                val bizTitle = profileDoc.getString("businessTitle")
                    ?: rootDoc.getString("businessTitle")
                    ?: "Comprador"

                // CASO ESPECIAL: Usuario nuevo que entra por primera vez con Google
                // No tiene perfil registrado en Firestore -> llevarlo al flujo de confirmación de registro y privacidad
                if (isGoogleSignIn && !hasExistingAccountInCloud) {
                    loading.dismiss()
                    val intent = Intent(this@LoginActivity, RegisterActivity::class.java).apply {
                        putExtra("EXTRA_GOOGLE_PREFILL", true)
                    }
                    startActivity(intent)
                    finish()
                    return@launch
                }

                val profileComplete = SessionManager.isProfileComplete(name, email)

                // Guardar y sincronizar datos básicos
                val basicProfile = mutableMapOf<String, Any>(
                    "email" to email,
                    "user_email" to email,
                    "businessTitle" to bizTitle,
                    "allowDirectorySearch" to allowDirectorySearch,
                    "shareContactWithFriends" to shareContactWithFriends
                )
                if (name.isNotBlank()) {
                    basicProfile["name"] = name
                    basicProfile["user_full_name"] = name
                }
                if (photo.isNotBlank()) {
                    basicProfile["photoUrl"] = photo
                    basicProfile["user_photo_url"] = photo
                }
                if (phone.isNotBlank()) {
                    basicProfile["phone"] = phone
                    basicProfile["user_phone"] = phone
                }
                if (dni.isNotBlank()) {
                    basicProfile["dni"] = dni
                    basicProfile["user_dni"] = dni
                }
                if (address.isNotBlank()) {
                    basicProfile["address"] = address
                    basicProfile["user_address"] = address
                }
                if (!messagePrivacy.isNullOrBlank()) {
                    basicProfile["messagePrivacy"] = messagePrivacy
                }

                db.collection("users").document(uid).collection("profile").document("info")
                    .set(basicProfile, SetOptions.merge())
                db.collection("users").document(uid)
                    .set(basicProfile, SetOptions.merge())

                SessionManager.saveProfileLocally(
                    context = this@LoginActivity,
                    fullName = name,
                    email = email,
                    phone = phone,
                    dni = dni,
                    address = address,
                    photoUrl = photo,
                    allowDirectorySearch = allowDirectorySearch,
                    shareContactWithFriends = shareContactWithFriends,
                    messagePrivacy = messagePrivacy,
                    verified = profileComplete
                )

                FavoritesManager.loadFromFirebase(this@LoginActivity)
                if (profileComplete) {
                    SessionManager.setLoginInProgress(this@LoginActivity, false)
                }
                loading.dismiss()

                // Si el perfil ya está completo (nombre + correo), ir a WelcomeRoleActivity.
                // Si le falta nombre o correo, ir a ProfileSetupActivity.
                val targetActivity = if (profileComplete) {
                    WelcomeRoleActivity::class.java
                } else {
                    ProfileSetupActivity::class.java
                }

                val intent = Intent(this@LoginActivity, targetActivity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                Log.e("LoginActivity", "Error syncing user profile: ${e.message}")
                val profileComplete = SessionManager.isProfileComplete(defaultName, email)
                SessionManager.saveProfileLocally(
                    context = this@LoginActivity,
                    fullName = defaultName,
                    email = email,
                    photoUrl = defaultPhoto,
                    verified = profileComplete
                )
                loading.dismiss()

                val targetActivity = if (profileComplete) {
                    WelcomeRoleActivity::class.java
                } else {
                    ProfileSetupActivity::class.java
                }
                val intent = Intent(this@LoginActivity, targetActivity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(this).apply {
            hint = "correo@ejemplo.com"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Restablecer Contraseña 🔑")
            .setMessage("Ingresa tu correo electrónico y te enviaremos un enlace para crear una nueva contraseña.")
            .setView(container)
            .setPositiveButton("Enviar Enlace") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    auth.sendPasswordResetEmail(email)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Enlace enviado a $email 📩", Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener { err ->
                            Toast.makeText(this, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    Toast.makeText(this, "Ingresa un correo válido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
