package com.gomezmart.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.gomezmart.app.databinding.ActivityProfileSetupBinding
import com.gomezmart.app.network.SupabaseManager
import com.gomezmart.app.util.SessionManager
import com.gomezmart.app.util.UserNameGenerator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class ProfileSetupActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileSetupBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var selectedPhotoUri: Uri? = null
    private var photoFile: File? = null
    private var initialPhotoUrl: String? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPhotoUri = uri
            binding.pbSetupProfilePhotoLoading.visibility = View.VISIBLE
            Glide.with(this).load(uri).circleCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(binding.ivSetupProfilePhoto)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            photoFile?.let {
                selectedPhotoUri = Uri.fromFile(it)
                binding.pbSetupProfilePhotoLoading.visibility = View.VISIBLE
                Glide.with(this).load(it).circleCrop()
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                            binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                            return false
                        }
                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                            return false
                        }
                    })
                    .into(binding.ivSetupProfilePhoto)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.root, applyTop = true, applyBottom = true)

        SessionManager.setLoginInProgress(this, true)

        onBackPressedDispatcher.addCallback(this) {
            Toast.makeText(this@ProfileSetupActivity, "Por favor completa tu perfil para continuar", Toast.LENGTH_SHORT).show()
        }

        setupListeners()
        loadExistingData()
    }

    private fun loadExistingData() {
        val user = auth.currentUser ?: return
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        val existingName = appPrefs.getString("user_full_name", user.displayName) ?: ""
        val existingPhone = appPrefs.getString("user_phone", "") ?: ""
        val existingEmail = appPrefs.getString("user_email", user.email) ?: ""
        initialPhotoUrl = appPrefs.getString("user_photo_url", user.photoUrl?.toString())

        if (existingEmail.isNotEmpty()) {
            binding.etSetupEmail.setText(existingEmail)
        }

        if (existingName.isNotEmpty()) {
            val parts = existingName.split(" ", limit = 2)
            binding.etSetupNames.setText(parts[0])
            if (parts.size > 1) binding.etSetupSurnames.setText(parts[1])
        }

        if (existingPhone.isNotEmpty()) {
            binding.etSetupPhone.setText(existingPhone)
        }

        if (!initialPhotoUrl.isNullOrEmpty()) {
            binding.pbSetupProfilePhotoLoading.visibility = View.VISIBLE
            Glide.with(this).load(initialPhotoUrl).circleCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        binding.pbSetupProfilePhotoLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(binding.ivSetupProfilePhoto)
        } else {
            binding.pbSetupProfilePhotoLoading.visibility = View.GONE
        }

        // Si ya tiene nombre completo y correo (el celular es opcional), habilitar botón de mantener
        if ((existingName.contains(" ") && existingEmail.isNotEmpty()) && (existingPhone.isEmpty() || existingPhone.length == 9)) {
            binding.btnSetupKeepInfo.visibility = View.VISIBLE
        }

        validateFormRealtime()
    }

    private fun setupListeners() {
        binding.btnSetupChangePhoto.setOnClickListener { showPhotoOptionsDialog() }
        binding.btnSetupSave.setOnClickListener { validateAndSave() }
        binding.btnSetupKeepInfo.setOnClickListener {
            getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
                .putBoolean("is_guest", false)
                .putBoolean("is_profile_verified", true)
                .putBoolean("login_in_progress", false)
                .commit()
            navigateToHome()
        }

        binding.btnBackToLogin.setOnClickListener {
            lifecycleScope.launch {
                SessionManager.signOutCompletely(this@ProfileSetupActivity)
                startActivity(Intent(this@ProfileSetupActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }

        binding.etSetupEmail.addTextChangedListener { validateFormRealtime() }
        binding.etSetupNames.addTextChangedListener { validateFormRealtime() }
        binding.etSetupSurnames.addTextChangedListener { validateFormRealtime() }
        binding.etSetupPhone.addTextChangedListener { validateFormRealtime() }
    }

    private fun validateFormRealtime() {
        val email = binding.etSetupEmail.text.toString().trim()
        val names = binding.etSetupNames.text.toString().trim()
        val surnames = binding.etSetupSurnames.text.toString().trim()
        val phone = binding.etSetupPhone.text.toString().trim()

        val isEmailValid = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val isPhoneValid = phone.isEmpty() || (phone.length == 9 && phone.all { it.isDigit() })
        val isFormValid = isEmailValid && names.isNotEmpty() && surnames.isNotEmpty() && isPhoneValid

        binding.btnSetupSave.isEnabled = isFormValid
        binding.btnSetupSave.alpha = if (isFormValid) 1.0f else 0.5f
    }

    private fun showPhotoOptionsDialog() {
        val options = arrayOf("📸 Tomar Foto", "🖼️ Elegir de Galería")
        AlertDialog.Builder(this)
            .setTitle("Foto de Perfil")
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
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        photoFile = File(getExternalFilesDir(null), "profile_setup_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile!!)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
    }

    private fun validateAndSave() {
        val email = binding.etSetupEmail.text.toString().trim()
        val names = binding.etSetupNames.text.toString().trim()
        val surnames = binding.etSetupSurnames.text.toString().trim()
        val phone = binding.etSetupPhone.text.toString().trim()

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Ingresa un correo electrónico válido 📧", Toast.LENGTH_SHORT).show()
            return
        }

        if (names.isEmpty() || surnames.isEmpty()) {
            Toast.makeText(this, "Completa tus nombres y apellidos ⚠️", Toast.LENGTH_SHORT).show()
            return
        }

        if (phone.isNotEmpty() && (phone.length != 9 || !phone.all { it.isDigit() })) {
            Toast.makeText(this, "Si ingresas un celular, debe tener 9 dígitos numéricos 📱", Toast.LENGTH_LONG).show()
            return
        }

        var fullName = "$names $surnames".trim()
        if (fullName.isBlank() || fullName.equals("Usuario Gomezmart", ignoreCase = true) || fullName.equals("Usuario GoMezMart", ignoreCase = true)) {
            fullName = UserNameGenerator.getOrGenerateName(this, null, auth.currentUser?.uid)
        }
        saveAll(fullName, phone, email)
    }

    private fun saveAll(fullName: String, phone: String, email: String) {
        val loading = AlertDialog.Builder(this).setMessage("Guardando perfil...").setCancelable(false).show()

        lifecycleScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                // VALIDACIÓN DE CELULAR ÚNICO (solo si ingresó número de celular)
                if (phone.isNotEmpty()) {
                    val duplicatePhoneQuery = db.collection("users")
                        .whereEqualTo("phone", phone)
                        .get().await()

                    val duplicate = duplicatePhoneQuery.documents.find { it.id != userId }
                    if (duplicate != null) {
                        loading.dismiss()
                        Toast.makeText(this@ProfileSetupActivity, "Este número de celular ya está registrado en otra cuenta 🚫", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                var finalPhotoUrl = initialPhotoUrl ?: ""

                // 1. Subir foto si se seleccionó una nueva
                selectedPhotoUri?.let { uri ->
                    val file = if (uri.scheme == "file") File(uri.path!!) else saveUriToFile(uri)
                    file?.let {
                        val path = "profiles/$userId/profile_v2.jpg"
                        finalPhotoUrl = SupabaseManager.uploadImage("naxor-imagenes-usuarios", path, it) ?: finalPhotoUrl
                    }
                }

                // 2. Guardar en Firestore (Ambas ubicaciones)
                val updates = mapOf(
                    "name" to fullName,
                    "user_full_name" to fullName,
                    "photoUrl" to finalPhotoUrl,
                    "user_photo_url" to finalPhotoUrl,
                    "email" to email,
                    "user_email" to email,
                    "phone" to phone,
                    "user_phone" to phone,
                    "updatedAt" to System.currentTimeMillis()
                )

                db.collection("users").document(userId).collection("profile").document("info")
                    .set(updates, SetOptions.merge()).await()

                db.collection("users").document(userId)
                    .set(updates, SetOptions.merge()).await()

                // 3. Guardar en SharedPreferences
                SessionManager.saveProfileLocally(
                    context = this@ProfileSetupActivity,
                    fullName = fullName,
                    email = email,
                    phone = phone,
                    photoUrl = finalPhotoUrl,
                    verified = true
                )
                SessionManager.setLoginInProgress(this@ProfileSetupActivity, false)

                loading.dismiss()
                Toast.makeText(this@ProfileSetupActivity, "¡Perfil guardado con éxito! ✨", Toast.LENGTH_SHORT).show()
                navigateToHome()

            } catch (e: Exception) {
                loading.dismiss()
                Log.e("ProfileSetup", "Error saving: ${e.message}")
                Toast.makeText(this@ProfileSetupActivity, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveUriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(cacheDir, "temp_profile.jpg")
            file.outputStream().use { output -> inputStream?.copyTo(output) }
            file
        } catch (_: Exception) { null }
    }

    private fun navigateToHome() {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)

        val role = appPrefs.getString("user_role", null)
        val bizType = bizPrefs.getString("business_type", null)

        val intent = when (role) {
            "SELLER", "COLLABORATOR" -> if (bizType != null) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, WelcomeRoleActivity::class.java)
            }
            "BUYER" -> Intent(this, BuyerHomeActivity::class.java)
            else -> Intent(this, WelcomeRoleActivity::class.java)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
