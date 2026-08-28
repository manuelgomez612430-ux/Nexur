package com.naxor.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.databinding.ActivityLoginBinding
import com.naxor.app.util.SessionManager
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var isLoginMode = true

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
            getColor(R.color.purple_600)
        } else {
            getColor(R.color.slate_300)
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
            .setInterpolator(OvershootInterpolator())
            .start()

        binding.cardInfoLogin.animate()
            .alpha(1f).translationY(0f)
            .setDuration(800)
            .setStartDelay(500)
            .start()

        binding.layoutForm.animate()
            .alpha(1f)
            .setDuration(1000)
            .setStartDelay(800)
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
            if (isLoginMode) login(email, password) else register(email, password)
        }

        binding.btnGoogleSignIn.setOnClickListener {
            signInWithGoogle()
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun showForgotPasswordDialog() {
        val email = binding.etLoginEmail.text.toString().trim()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Recuperar Contraseña")
        builder.setMessage("Te enviaremos un enlace a tu correo para que puedas cambiar tu contraseña.")

        val input = EditText(this)
        input.hint = "Ingresa tu correo"
        input.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        if (email.isNotEmpty()) input.setText(email)
        
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(60, 20, 60, 0)
        input.layoutParams = lp
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Enviar enlace") { _, _ ->
            val resetEmail = input.text.toString().trim()
            if (resetEmail.isNotEmpty()) {
                auth.sendPasswordResetEmail(resetEmail).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Enlace enviado a $resetEmail 📧", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "Ingresa un correo válido", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun updateUI() {
        if (isLoginMode) {
            binding.tvLoginTitle.text = "Acceso al Sistema"
            binding.btnLoginAction.text = "Iniciar Sesión"
            binding.btnToggleLoginMode.text = "¿No tienes cuenta? Regístrate aquí"
            binding.layoutConfirmPassword.visibility = View.GONE
            binding.btnForgotPassword.visibility = View.VISIBLE
        } else {
            binding.tvLoginTitle.text = "Crear mi Cuenta"
            binding.btnLoginAction.text = "Registrarme Ahora"
            binding.btnToggleLoginMode.text = "¿Ya tienes cuenta? Inicia sesión"
            binding.layoutConfirmPassword.visibility = View.VISIBLE
            binding.btnForgotPassword.visibility = View.GONE
        }
        updateButtonState()
    }

    private fun signInWithGoogle() {
        val webClientId = try {
            getString(resources.getIdentifier("default_web_client_id", "string", packageName))
        } catch (e: Exception) {
            ""
        }

        if (webClientId.isEmpty()) {
            Toast.makeText(this, "Error: Configuración de Google no encontrada", Toast.LENGTH_LONG).show()
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()

        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val loading = AlertDialog.Builder(this)
            .setMessage("Accediendo...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            SessionManager.clearSessionData(this@LoginActivity)

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener(this@LoginActivity) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    val email = user?.email ?: ""
                    
                    checkForInvitations(email) { invitations ->
                        loading.dismiss()
                        if (invitations.isNotEmpty()) {
                            showContextSelectionDialog(invitations)
                        } else {
                            performFullSyncAndStart()
                        }
                    }
                } else {
                    loading.dismiss()
                    Toast.makeText(this@LoginActivity, "Error al autenticar", Toast.LENGTH_SHORT).show()
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
                    checkForInvitations(e) { invitations ->
                        loading.dismiss()
                        if (invitations.isNotEmpty()) showContextSelectionDialog(invitations)
                        else performFullSyncAndStart()
                    }
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

    private fun checkForInvitations(email: String, onChecked: (List<Map<String, Any>>) -> Unit) {
        FirebaseFirestore.getInstance().collection("access_keys")
            .document(email.lowercase()).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val data = doc.data
                    if (data != null) onChecked(listOf(data))
                    else onChecked(emptyList())
                } else {
                    onChecked(emptyList())
                }
            }
            .addOnFailureListener { onChecked(emptyList()) }
    }

    private fun showContextSelectionDialog(invitations: List<Map<String, Any>>) {
        val options = mutableListOf("Mi Negocio Personal")
        invitations.forEach { inv ->
            val bizName = inv["businessName"] as? String ?: "Hospedaje"
            options.add("Colaborar en: $bizName")
        }

        AlertDialog.Builder(this)
            .setTitle("Seleccionar Entorno")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                        .putBoolean("is_collaborator_mode", false)
                        .apply()
                    performFullSyncAndStart()
                } else {
                    val invitation = invitations[which - 1]
                    val ownerId = invitation["ownerId"] as? String ?: ""
                    val role = invitation["role"] as? String ?: "RECEPTIONIST"
                    val bType = invitation["businessType"] as? String ?: "HOTEL"
                    
                    // 1. Configurar modo colaborador
                    getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                        .putBoolean("is_collaborator_mode", true)
                        .putString("collaborator_owner_id", ownerId)
                        .putString("collaborator_role", role)
                        .putString("business_type", bType)
                        .apply()

                    // 2. Establecer el rol de usuario en AppPrefs para evitar la pantalla de selección
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                        .putString("user_role", "COLLABORATOR")
                        .apply()
                    
                    performFullSyncAndStart()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun checkBusinessConfigAndStart() {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        
        val role = appPrefs.getString("user_role", null)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val bizType = bizPrefs.getString("business_type", null)

        when {
            // Prioridad 1: Colaboradores (Entran directo si el negocio tiene rubro)
            isCollab || role == "COLLABORATOR" -> {
                if (bizType != null) {
                    startActivity(Intent(this, MainActivity::class.java))
                } else {
                    // Si es colaborador pero no sabe el rubro (raro), lo mandamos a buscar
                    startActivity(Intent(this, SearchBusinessActivity::class.java))
                }
            }
            
            // Prioridad 2: Dueños (SELLER)
            role == "SELLER" -> {
                if (bizType != null) {
                    startActivity(Intent(this, MainActivity::class.java))
                } else {
                    startActivity(Intent(this, BusinessSelectionActivity::class.java))
                }
            }
            
            // Prioridad 3: Compradores
            role == "BUYER" -> {
                startActivity(Intent(this, BuyerHomeActivity::class.java))
            }
            
            // Fallback: Primera vez o datos borrados
            else -> {
                startActivity(Intent(this, WelcomeRoleActivity::class.java))
            }
        }
        finish()
    }
}
