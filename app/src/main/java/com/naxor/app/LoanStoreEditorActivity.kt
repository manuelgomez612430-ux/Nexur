package com.naxor.app

import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.naxor.app.databinding.ActivityLoanStoreEditorBinding
import com.naxor.app.network.SupabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

class LoanStoreEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityLoanStoreEditorBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var selectedLogoUri: Uri? = null
    private var selectedCoverUri: Uri? = null
    private var hasCloudLogo = false
    private var hasCloudCover = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanStoreEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarLoanEditor.setNavigationOnClickListener { finish() }
        
        loadCurrentData()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnUploadLoanLogo.setOnClickListener { pickLogoLauncher.launch("image/*") }
        binding.btnUploadLoanCover.setOnClickListener { pickCoverLauncher.launch("image/*") }
        binding.btnSaveLoanStore.setOnClickListener { saveChanges() }

        binding.switchActivateLoanMarket.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val validation = isDataComplete()
                if (!validation.first) {
                    buttonView.isChecked = false
                    Toast.makeText(this, "⚠️ Incompleto: ${validation.second}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isDataComplete(): Pair<Boolean, String> {
        val name = binding.etLoanStoreName.text.toString().trim()
        val desc = binding.etLoanStoreDesc.text.toString().trim()
        val reqs = binding.etLoanRequirements.text.toString().trim()

        if (name.isEmpty()) return false to "Falta el nombre"
        if (desc.length < 20) return false to "La descripción es muy corta"
        if (reqs.length < 10) return false to "Detalla los requisitos"
        if (selectedLogoUri == null && !hasCloudLogo) return false to "Sube un Logo"
        if (selectedCoverUri == null && !hasCloudCover) return false to "Sube una Portada"

        return true to ""
    }

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedLogoUri = uri
        uri?.let { Toast.makeText(this, "Logo seleccionado ✅", Toast.LENGTH_SHORT).show() }
    }

    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedCoverUri = uri
        uri?.let { Toast.makeText(this, "Portada seleccionada ✅", Toast.LENGTH_SHORT).show() }
    }

    private fun loadCurrentData() {
        val userId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(userId)
                    .collection("settings").document("LOANS").get().await()

                if (doc.exists()) {
                    binding.etLoanStoreName.setText(doc.getString("business_name") ?: "")
                    binding.etLoanStoreDesc.setText(doc.getString("business_description") ?: "")
                    binding.etLoanRequirements.setText(doc.getString("loan_requirements") ?: "")
                    binding.switchActivateLoanMarket.isChecked = doc.getBoolean("is_online") ?: false
                    
                    hasCloudLogo = !doc.getString("business_logo_url").isNullOrEmpty()
                    hasCloudCover = !doc.getString("business_cover_url").isNullOrEmpty()
                }
            } catch (e: Exception) {
                Log.e("LoanStoreEditor", "Error loading data: ${e.message}")
            }
        }
    }

    private fun saveChanges() {
        val name = binding.etLoanStoreName.text.toString().trim()
        val desc = binding.etLoanStoreDesc.text.toString().trim()
        val reqs = binding.etLoanRequirements.text.toString().trim()
        val isOnline = binding.switchActivateLoanMarket.isChecked

        if (name.isEmpty()) {
            Toast.makeText(this, "El nombre es obligatorio", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setMessage("Publicando en el mercado... 🚀")
            setCancelable(false)
            show()
        }

        val userId = auth.currentUser?.uid ?: ""
        val data = mutableMapOf<String, Any>(
            "business_name" to name,
            "business_description" to desc,
            "loan_requirements" to reqs,
            "is_online" to isOnline,
            "business_type" to "LOANS",
            "business_category" to "SERVICIOS FINANCIEROS"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bucket = "gomezmart-imagenes-usuarios"

                // 1. Subir Logo si hay nuevo
                selectedLogoUri?.let { uri ->
                    saveImageLocally(uri, "loan_logo_tmp.png")?.let { path ->
                        SupabaseManager.uploadImage(bucket, "logos/$userId/LOANS.png", File(path))?.let { url ->
                            data["business_logo_url"] = url
                        }
                    }
                }

                // 2. Subir Portada si hay nueva
                selectedCoverUri?.let { uri ->
                    saveImageLocally(uri, "loan_cover_tmp.png")?.let { path ->
                        SupabaseManager.uploadImage(bucket, "covers/$userId/LOANS.png", File(path))?.let { url ->
                            data["business_cover_url"] = url
                        }
                    }
                }

                // 3. Guardar en Firestore
                db.collection("users").document(userId)
                    .collection("settings").document("LOANS")
                    .set(data, SetOptions.merge()).await()
                
                // Actualizar estado local
                getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                    .putBoolean("is_online_store", isOnline)
                    .apply()

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(this@LoanStoreEditorActivity, "¡Negocio de Préstamos actualizado! ✨", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(this@LoanStoreEditorActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveImageLocally(uri: Uri, fileName: String): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) { null }
    }
}
