package com.naxor.app

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.StoreSection
import com.naxor.app.databinding.ActivityStoreEditorBinding
import com.naxor.app.network.SupabaseManager
import com.naxor.app.util.GeminiHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class StoreEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityStoreEditorBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var selectedLogoUri: Uri? = null
    private var selectedCoverUri: Uri? = null
    
    private var businessLat = 0.0
    private var businessLng = 0.0

    private var currentTheme = mutableMapOf<String, String>()
    private val extraSections = mutableListOf<StoreSection>()

    // --- LÓGICA DE RECORTE (CROP) ---
    private var currentCropType = "" // "LOGO", "COVER", "SECTION"
    private var currentTargetSectionId: String? = null
    private var currentRequestedRatio: String = "16:9"
    
    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uriContent = result.uriContent
            when (currentCropType) {
                "LOGO" -> {
                    selectedLogoUri = uriContent
                    Glide.with(this).load(uriContent).circleCrop().into(binding.ivEditLogo)
                    validateAiButton()
                }
                "COVER" -> {
                    selectedCoverUri = uriContent
                    Glide.with(this).load(uriContent).centerCrop().into(binding.ivEditCover)
                    validateAiButton()
                }
                "SECTION" -> {
                    currentTargetSectionId?.let { id ->
                        val index = extraSections.indexOfFirst { it.id == id }
                        if (index != -1) {
                            val section = extraSections[index]
                            val newPaths = section.imageLocalPaths.toMutableList()
                            newPaths.add(uriContent.toString())
                            extraSections[index] = section.copy(imageLocalPaths = newPaths)
                            renderExtraSections()
                        }
                    }
                }
            }
        }
    }

    private fun startCrop(uri: Uri, type: String, ratio: String = "16:9") {
        currentCropType = type
        val options = CropImageOptions().apply {
            guidelines = CropImageView.Guidelines.ON
            activityTitle = "Ajustar Imagen"
            fixAspectRatio = ratio != "FREE"
            if (ratio != "FREE") {
                val parts = ratio.split(":")
                aspectRatioX = parts[0].toInt()
                aspectRatioY = parts[1].toInt()
            }
            cropShape = if (type == "LOGO") CropImageView.CropShape.OVAL else CropImageView.CropShape.RECTANGLE
            showProgressBar = true
            activityMenuIconColor = Color.WHITE
        }
        cropImage.launch(CropImageContractOptions(uri, options))
    }

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "LOGO", "1:1") }
    }

    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "COVER", "16:9") }
    }

    private val pickSectionImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "SECTION", currentRequestedRatio) }
    }

    private val mapSelectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            businessLat = data?.getDoubleExtra("LAT", 0.0) ?: 0.0
            businessLng = data?.getDoubleExtra("LNG", 0.0) ?: 0.0
            val addr = data?.getStringExtra("ADDRESS") ?: ""
            if (addr.isNotEmpty()) {
                binding.etEditStoreAddress.setText(addr)
            }
            validateAiButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoreEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarStoreEditor.setNavigationOnClickListener { finish() }
        
        val forcedRubro = intent.getStringExtra("FORCE_RUBRO")
        loadCurrentData(forcedRubro)

        setupValidation()
        setupListeners()
    }

    private fun setupValidation() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { validateAiButton() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.etEditStoreName.addTextChangedListener(watcher)
        binding.etEditStoreDesc.addTextChangedListener(watcher)
        binding.etEditStoreAddress.addTextChangedListener(watcher)
    }

    private fun validateAiButton() {
        val name = binding.etEditStoreName.text.toString().trim()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val addr = binding.etEditStoreAddress.text.toString().trim()
        
        val hasLogo = selectedLogoUri != null || !binding.ivEditLogo.tag?.toString().isNullOrEmpty()
        val hasCover = selectedCoverUri != null || !binding.ivEditCover.tag?.toString().isNullOrEmpty()

        val isValid = name.isNotEmpty() && desc.length > 10 && addr.isNotEmpty() && hasLogo && hasCover

        binding.btnAiDesigner.isEnabled = isValid
        val color = if (isValid) Color.parseColor("#6366F1") else Color.parseColor("#94A3B8")
        binding.btnAiDesigner.setTextColor(color)
        binding.btnAiDesigner.iconTint = ColorStateList.valueOf(color)
    }

    private fun setupListeners() {
        binding.cardEditLogo.setOnClickListener { pickLogoLauncher.launch("image/*") }
        binding.cardEditCover.setOnClickListener { pickCoverLauncher.launch("image/*") }
        
        binding.btnSelectLocationOnMap.setOnClickListener {
            val intent = Intent(this, BusinessMapSelectorActivity::class.java)
            mapSelectorLauncher.launch(intent)
        }

        binding.btnAddExtraSection.setOnClickListener {
            extraSections.add(StoreSection(id = UUID.randomUUID().toString()))
            renderExtraSections()
        }

        binding.btnAiDesigner.setOnClickListener { generateAiDesign() }
        binding.btnSaveStoreChanges.setOnClickListener { saveChanges(forcedRubro = intent.getStringExtra("FORCE_RUBRO")) }
    }

    private fun renderExtraSections() {
        binding.layoutExtraSectionsContainer.removeAllViews()
        extraSections.forEach { section ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_editor_extra_section, binding.layoutExtraSectionsContainer, false)
            
            val etTitle = itemView.findViewById<TextInputEditText>(R.id.etSectionTitle)
            val etContent = itemView.findViewById<TextInputEditText>(R.id.etSectionContent)
            val layoutThumbnails = itemView.findViewById<LinearLayout>(R.id.layoutSectionThumbnails)
            val btnAddImage = itemView.findViewById<MaterialButton>(R.id.btnAddImageToSection)
            val btnRatio = itemView.findViewById<MaterialButton>(R.id.btnChangeAspectRatio)
            val btnRemove = itemView.findViewById<ImageButton>(R.id.btnRemoveSection)

            etTitle.setText(section.title)
            etContent.setText(section.description)
            btnRatio.text = "Formato: ${section.aspectRatio}"

            layoutThumbnails.removeAllViews()
            val allImages = section.imageUrls + section.imageLocalPaths
            allImages.forEach { path ->
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 12, 0) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    setBackgroundResource(R.drawable.bg_rounded_8)
                }
                Glide.with(this).load(Uri.parse(path)).into(iv)
                layoutThumbnails.addView(iv)
            }

            btnAddImage.setOnClickListener {
                currentTargetSectionId = section.id
                currentRequestedRatio = section.aspectRatio
                pickSectionImageLauncher.launch("image/*")
            }

            btnRatio.setOnClickListener {
                val options = arrayOf("16:9 (Panorámico)", "1:1 (Cuadrado)", "LIBRE")
                AlertDialog.Builder(this)
                    .setTitle("Elegir Formato de Recorte")
                    .setItems(options) { _, which ->
                        val newRatio = when(which) {
                            0 -> "16:9"
                            1 -> "1:1"
                            else -> "FREE"
                        }
                        val idx = extraSections.indexOfFirst { it.id == section.id }
                        if (idx != -1) {
                            extraSections[idx] = extraSections[idx].copy(aspectRatio = newRatio)
                            btnRatio.text = "Formato: $newRatio"
                        }
                    }.show()
            }

            btnRemove.setOnClickListener {
                extraSections.removeIf { it.id == section.id }
                renderExtraSections()
            }

            binding.layoutExtraSectionsContainer.addView(itemView)
        }
    }

    private fun loadCurrentData(forcedRubro: String?) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bType = forcedRubro ?: prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isCollab = prefs.getBoolean("is_collaborator_mode", false)
        val ownerId = if (isCollab) prefs.getString("collaborator_owner_id", null) else auth.currentUser?.uid

        val rubroText = when(bType) {
            "LOANS" -> "SERVICIOS / PRÉSTAMOS"
            "HOTEL" -> "HOSPEDAJE / HOTELES"
            else -> "TIENDA DE PRODUCTOS"
        }
        binding.etEditStoreRubro.setText(rubroText)

        if (ownerId != null) fetchStoreData(ownerId, bType)
    }

    private fun fetchStoreData(ownerId: String, bType: String, tryFallback: Boolean = true) {
        val docId = if (tryFallback) bType else "business"
        binding.btnSaveStoreChanges.isEnabled = false
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(ownerId).collection("settings").document(docId).get().await()
                if (doc.exists()) {
                    binding.etEditStoreName.setText(doc.getString("business_name") ?: "")
                    binding.etEditStoreDesc.setText(doc.getString("business_description") ?: "")
                    binding.etEditOwnerName.setText(doc.getString("business_owner_name") ?: "")
                    binding.etEditStoreAddress.setText(doc.getString("business_address") ?: "")
                    binding.switchActivateOnlineStore.isChecked = doc.getBoolean("is_online") ?: false
                    businessLat = doc.getDouble("latitude") ?: 0.0
                    businessLng = doc.getDouble("longitude") ?: 0.0
                    
                    val logo = doc.getString("business_logo_url")
                    val cover = doc.getString("business_cover_url")
                    if (!logo.isNullOrEmpty()) { binding.ivEditLogo.tag = logo; Glide.with(this@StoreEditorActivity).load(logo).circleCrop().into(binding.ivEditLogo) }
                    if (!cover.isNullOrEmpty()) { binding.ivEditCover.tag = cover; Glide.with(this@StoreEditorActivity).load(cover).centerCrop().into(binding.ivEditCover) }

                    val sections = doc.get("extraSections") as? List<Map<String, Any>>
                    if (sections != null) {
                        extraSections.clear()
                        sections.forEach { m ->
                            extraSections.add(StoreSection(
                                id = m["id"] as? String ?: UUID.randomUUID().toString(),
                                title = m["title"] as? String ?: "",
                                description = m["description"] as? String ?: "",
                                imageUrls = (m["imageUrls"] as? List<String>) ?: emptyList(),
                                aspectRatio = m["aspectRatio"] as? String ?: "16:9"
                            ))
                        }
                        renderExtraSections()
                    }
                    val theme = doc.get("theme") as? Map<String, String>
                    if (theme != null) { currentTheme.clear(); currentTheme.putAll(theme) }
                    validateAiButton()
                } else if (tryFallback && docId != "business") fetchStoreData(ownerId, "business", false)
            } catch (e: Exception) { } finally { binding.btnSaveStoreChanges.isEnabled = true }
        }
    }

    private fun saveChanges(forcedRubro: String?) {
        val name = binding.etEditStoreName.text.toString().trim()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        val addr = binding.etEditStoreAddress.text.toString().trim()
        val isOnline = binding.switchActivateOnlineStore.isChecked
        if (name.isEmpty()) { Toast.makeText(this, "El nombre es obligatorio", Toast.LENGTH_SHORT).show(); return }
        syncSectionsFromLayout()
        val progress = ProgressDialog(this).apply { setMessage("Sincronizando..."); setCancelable(false); show() }
        val bType = forcedRubro ?: getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isCollab = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getBoolean("is_collaborator_mode", false)
        val ownerId = if (isCollab) getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("collaborator_owner_id", "")!! else auth.currentUser?.uid ?: ""
        val data = mutableMapOf<String, Any>(
            "business_name" to name, "business_description" to desc, "business_owner_name" to owner,
            "business_address" to addr, "is_online" to isOnline, "latitude" to businessLat,
            "longitude" to businessLng, "business_type" to bType, "theme" to currentTheme
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bucket = "gomezmart-logos"
                selectedLogoUri?.let { u -> saveImageLocally(u, "logo_tmp.png")?.let { path -> SupabaseManager.uploadImage(bucket, "logos/$ownerId/$bType.png", File(path))?.let { url -> data["business_logo_url"] = url } } }
                selectedCoverUri?.let { u -> saveImageLocally(u, "cover_tmp.png")?.let { path -> SupabaseManager.uploadImage(bucket, "covers/$ownerId/$bType.png", File(path))?.let { url -> data["business_cover_url"] = url } } }
                val finalSections = extraSections.map { section ->
                    val finalUrls = section.imageUrls.toMutableList()
                    section.imageLocalPaths.forEach { local ->
                        try { saveImageLocally(Uri.parse(local), "sec_${UUID.randomUUID()}.png")?.let { lp -> SupabaseManager.uploadImage(bucket, "sections/$ownerId/${UUID.randomUUID()}.png", File(lp))?.let { url -> finalUrls.add(url) } } } catch (e: Exception) {}
                    }
                    mapOf("id" to section.id, "title" to section.title, "description" to section.description, "imageUrls" to finalUrls, "aspectRatio" to section.aspectRatio)
                }
                data["extraSections"] = finalSections
                db.collection("users").document(ownerId).collection("settings").document(bType).set(data, SetOptions.merge()).await()
                withContext(Dispatchers.Main) { progress.dismiss(); Toast.makeText(this@StoreEditorActivity, "Tienda actualizada ✨", Toast.LENGTH_SHORT).show(); finish() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { progress.dismiss(); Toast.makeText(this@StoreEditorActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun syncSectionsFromLayout() {
        for (i in 0 until binding.layoutExtraSectionsContainer.childCount) {
            val view = binding.layoutExtraSectionsContainer.getChildAt(i)
            val etTitle = view.findViewById<TextInputEditText>(R.id.etSectionTitle)
            val etContent = view.findViewById<TextInputEditText>(R.id.etSectionContent)
            if (i < extraSections.size) extraSections[i] = extraSections[i].copy(title = etTitle.text.toString(), description = etContent.text.toString())
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

    private fun generateAiDesign() {
        val name = binding.etEditStoreName.text.toString().trim()
        val cat = binding.etEditStoreRubro.text.toString()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        if (name.isEmpty() || desc.isEmpty()) { Toast.makeText(this, "Completa los datos básicos ✨", Toast.LENGTH_LONG).show(); return }
        val progress = ProgressDialog(this).apply { setMessage("La IA está RE-DISEÑANDO tu tienda... 🪄🤖"); setCancelable(false); show() }
        lifecycleScope.launch {
            try {
                val jsonResponse = GeminiHelper.generateStoreDesignProposal(name, cat, desc, owner)
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val responseMap: Map<String, Any> = Gson().fromJson(jsonResponse, type)
                val businessInfo = responseMap["businessInfo"] as? Map<String, String> ?: emptyMap()
                val themeMap = responseMap["theme"] as? Map<String, String> ?: emptyMap()
                val sectionsList = responseMap["sections"] as? List<Map<String, String>> ?: emptyList()
                progress.dismiss()
                if (sectionsList.isNotEmpty() || themeMap.isNotEmpty() || businessInfo.isNotEmpty()) showAiPreviewDialog(businessInfo, themeMap, sectionsList)
                else Toast.makeText(this@StoreEditorActivity, "Error en propuesta IA", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { progress.dismiss(); Toast.makeText(this@StoreEditorActivity, "Error IA: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showAiPreviewDialog(proposedInfo: Map<String, String>, proposedTheme: Map<String, String>, proposedSections: List<Map<String, String>>) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("✨ Propuesta de RE-DISEÑO TOTAL")
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 30, 50, 30) }
        scrollView.addView(container)
        proposedSections.forEach { sec ->
            val tv = TextView(this).apply { text = "📍 ${sec["title"]}\n${sec["description"]}"; textSize = 14f; setTextColor(Color.parseColor("#334155")); setPadding(0, 10, 0, 20) }
            container.addView(tv)
        }
        builder.setView(scrollView)
        builder.setPositiveButton("REEMPLAZAR Y APLICAR") { _, _ ->
            proposedInfo["name"]?.let { binding.etEditStoreName.setText(it) }
            proposedInfo["description"]?.let { binding.etEditStoreDesc.setText(it) }
            currentTheme.clear(); currentTheme.putAll(proposedTheme)
            extraSections.clear()
            proposedSections.forEach { sec -> extraSections.add(StoreSection(id = UUID.randomUUID().toString(), title = sec["title"] ?: "", description = sec["description"] ?: "")) }
            renderExtraSections()
            Toast.makeText(this, "¡Tienda transformada! 🪄✨", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
}
