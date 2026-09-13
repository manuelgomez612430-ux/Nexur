package com.naxor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.databinding.ActivityLoanStoreProfileBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoanStoreProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityLoanStoreProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var storeId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanStoreProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storeId = intent.getStringExtra("STORE_ID") ?: ""
        val isOwner = intent.getBooleanExtra("IS_OWNER", false)

        binding.toolbarLoanStore.setNavigationOnClickListener { finish() }
        
        loadStoreData()

        if (isOwner) {
            binding.btnEditLoanStore.visibility = View.VISIBLE
            binding.btnEditLoanStore.setOnClickListener {
                startActivity(Intent(this, LoanStoreEditorActivity::class.java))
            }
        }
    }

    private fun loadStoreData() {
        if (storeId.isEmpty()) return

        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(storeId)
                    .collection("settings").document("LOANS").get().await()

                if (doc.exists()) {
                    val name = doc.getString("business_name") ?: "Financiera GoMezMart"
                    val desc = doc.getString("business_description") ?: ""
                    val reqs = doc.getString("loan_requirements") ?: "No se especificaron requisitos."
                    val addr = doc.getString("business_address") ?: "Dirección no disponible"
                    val phone = doc.getString("business_phone") ?: ""
                    val logo = doc.getString("business_logo_url")
                    val cover = doc.getString("business_cover_url")

                    binding.tvLoanStoreName.text = name
                    binding.tvLoanStoreDesc.text = desc
                    binding.tvLoanRequirements.text = reqs
                    binding.tvLoanStoreAddress.text = "📍 $addr"

                    if (!logo.isNullOrEmpty()) {
                        Glide.with(this@LoanStoreProfileActivity)
                            .load(logo)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .circleCrop()
                            .into(binding.ivLoanStoreLogo)
                    }

                    if (!cover.isNullOrEmpty()) {
                        Glide.with(this@LoanStoreProfileActivity)
                            .load(cover)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .into(binding.ivLoanStoreCover)
                    }

                    binding.btnLoanContactWhatsApp.setOnClickListener {
                        if (phone.isNotEmpty()) {
                            val url = "https://api.whatsapp.com/send?phone=$phone&text=Hola, vi tu negocio de préstamos en GoMezMart y deseo más información."
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } else {
                            Toast.makeText(this@LoanStoreProfileActivity, "Contacto no disponible", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoanStoreProfileActivity, "Error al cargar perfil", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
