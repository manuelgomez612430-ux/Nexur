package com.gomezmart.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.databinding.ActivityLoanStoreProfileBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoanStoreProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityLoanStoreProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var storeId: String = ""
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoanStoreProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.toolbarLoanStore, applyTop = true, applyBottom = false)

        storeId = intent.getStringExtra("STORE_ID") ?: (auth.currentUser?.uid ?: "")
        val isOwner = intent.getBooleanExtra("IS_OWNER", false) || (storeId.isNotEmpty() && storeId == auth.currentUser?.uid)

        binding.btnBackLoanStore.setOnClickListener { finish() }
        binding.toolbarLoanStore.setNavigationOnClickListener { finish() }
        binding.btnLoanChatOwner.setOnClickListener {
            val sName = binding.tvLoanStoreName.text?.toString()?.takeIf { it.isNotBlank() } ?: "Financiera"
            openChatWithStoreOwner(storeId, sName)
        }
        binding.tvLoanStoreAddress.setOnClickListener {
            openLocationInMaps()
        }

        if (isOwner) {
            binding.btnEditLoanStore.visibility = View.VISIBLE
            binding.btnEditLoanStore.setOnClickListener {
                if (requireLoginForGuest("editar tu financiera en el Mercado en Línea")) return@setOnClickListener
                startActivity(Intent(this, LoanStoreEditorActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadStoreData()
    }

    private fun openLocationInMaps() {
        try {
            val uri = if (currentLat != 0.0 && currentLng != 0.0) {
                Uri.parse("geo:$currentLat,$currentLng?q=$currentLat,$currentLng(${Uri.encode(binding.tvLoanStoreName.text.toString())})")
            } else if (currentAddress.isNotEmpty()) {
                Uri.parse("geo:0,0?q=${Uri.encode(currentAddress)}")
            } else return
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el mapa", Toast.LENGTH_SHORT).show()
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
                    currentLat = doc.getDouble("latitude") ?: 0.0
                    currentLng = doc.getDouble("longitude") ?: 0.0
                    currentAddress = addr

                    binding.tvLoanStoreName.text = name
                    binding.tvLoanStoreDesc.text = desc
                    binding.tvLoanRequirements.text = reqs
                    binding.tvLoanStoreAddress.text = "📍 $addr"

                    if (!logo.isNullOrEmpty()) {
                        Glide.with(this@LoanStoreProfileActivity)
                            .load(logo)
                            .placeholder(R.drawable.logo_sistema_prestamos)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .circleCrop()
                            .into(binding.ivLoanStoreLogo)
                    } else {
                        binding.ivLoanStoreLogo.setImageResource(R.drawable.logo_sistema_prestamos)
                    }

                    if (!cover.isNullOrEmpty()) {
                        Glide.with(this@LoanStoreProfileActivity)
                            .load(cover)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .into(binding.ivLoanStoreCover)
                    }

                    // Renderizar secciones personalizadas y métodos de pago en layoutLoanServices
                    binding.layoutLoanServices.removeAllViews()
                    val rawSections = doc.get("extraSections") as? List<Map<String, Any>>
                    rawSections?.forEach { sec ->
                        val title = sec["title"]?.toString()?.trim().orEmpty()
                        val content = sec["description"]?.toString()?.trim().orEmpty()
                        if (title.isNotEmpty() || content.isNotEmpty()) {
                            val itemLayout = LinearLayout(this@LoanStoreProfileActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, 12, 0, 12)
                            }
                            if (title.isNotEmpty()) {
                                itemLayout.addView(TextView(this@LoanStoreProfileActivity).apply {
                                    text = "• $title"
                                    textSize = 14f
                                    setTypeface(null, Typeface.BOLD)
                                    setTextColor(Color.parseColor("#0F172A"))
                                })
                            }
                            if (content.isNotEmpty()) {
                                itemLayout.addView(TextView(this@LoanStoreProfileActivity).apply {
                                    text = content
                                    textSize = 13f
                                    setTextColor(Color.parseColor("#475569"))
                                    setPadding(16, 4, 0, 0)
                                })
                            }
                            binding.layoutLoanServices.addView(itemLayout)
                        }
                    }

                    val rawMethods = doc.get("paymentMethods") as? List<Map<String, Any>>
                    if (!rawMethods.isNullOrEmpty()) {
                        val methodsNames = rawMethods.mapNotNull { it["type"]?.toString() }.distinct()
                        if (methodsNames.isNotEmpty()) {
                            binding.layoutLoanServices.addView(TextView(this@LoanStoreProfileActivity).apply {
                                text = "💳 Medios de desembolso / pago: ${methodsNames.joinToString(", ")}"
                                textSize = 13f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#059669"))
                                setPadding(0, 16, 0, 4)
                            })
                        }
                    }

                    binding.btnLoanContactWhatsApp.setOnClickListener {
                        if (phone.isNotEmpty()) {
                            val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
                            val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode("Hola, vi tu negocio de préstamos ($name) en GoMezMart y deseo más información.")}"
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

    private fun openChatWithStoreOwner(sId: String, sName: String) {
        if (requireLoginForGuest("chatear con la financiera")) return
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para chatear con la financiera 🔐", Toast.LENGTH_SHORT).show()
            return
        }
        val myUid = user.uid
        if (sId.isEmpty() || sId == myUid) {
            Toast.makeText(this, "Esta es tu propia financiera", Toast.LENGTH_SHORT).show()
            return
        }

        val chatId = if (myUid < sId) "${myUid}_${sId}" else "${sId}_${myUid}"
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId)
            putExtra("OTHER_USER_ID", sId)
            putExtra("OTHER_USER_NAME", sName)
            putExtra("OTHER_USER_TITLE", "Dueño de $sName")
            putExtra("IS_DIRECT", true)
        }
        startActivityWithFade(intent)
    }
}
