package com.naxor.app

import android.Manifest
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
import com.naxor.app.data.PaymentMethod
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
    private val paymentMethods = mutableListOf<PaymentMethod>()

    private var currentCropType = "" // "LOGO", "COVER", "SECTION"
    private var currentTargetSectionId: String? = null
    private var replacingImageIndex: Int = -1
    private var replacingIsLocal: Boolean = false
    
    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uriContent = result.uriContent
            when (currentCropType) {
                "LOGO" -> {
                    selectedLogoUri = uriContent
                    Glide.with(this)
                        .load(uriContent)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .into(binding.ivEditLogo)
                    validateAiButton()
                }
                "COVER" -> {
                    selectedCoverUri = uriContent
                    Glide.with(this)
                        .load(uriContent)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .into(binding.ivEditCover)
                    validateAiButton()
                }
                "SECTION" -> {
                    currentTargetSectionId?.let { id ->
                        val index = extraSections.indexOfFirst { it.id == id }
                        if (index != -1) {
                            val section = extraSections[index]
                            if (replacingImageIndex != -1) {
                                if (replacingIsLocal) {
                                    val newPaths = section.imageLocalPaths.toMutableList()
                                    newPaths[replacingImageIndex] = uriContent.toString()
                                    extraSections[index] = section.copy(imageLocalPaths = newPaths)
                                } else {
                                    val newUrls = section.imageUrls.toMutableList()
                                    newUrls.removeAt(replacingImageIndex)
                                    val newPaths = section.imageLocalPaths.toMutableList()
                                    newPaths.add(uriContent.toString())
                                    extraSections[index] = section.copy(imageUrls = newUrls, imageLocalPaths = newPaths)
                                }
                            } else {
                                val newPaths = section.imageLocalPaths.toMutableList()
                                newPaths.add(uriContent.toString())
                                extraSections[index] = section.copy(imageLocalPaths = newPaths)
                            }
                            renderExtraSections()
                        }
                    }
                }
            }
        }
    }

    private fun startCrop(uri: Uri, type: String) {
        currentCropType = type
        val options = CropImageOptions().apply {
            guidelines = CropImageView.Guidelines.ON
            activityTitle = "Ajustar Imagen"
            borderCornerOffset = 20f
            borderCornerThickness = 6f
            borderCornerLength = 20f
            allowRotation = true
            allowFlipping = true
            
            if (type == "LOGO") {
                cropShape = CropImageView.CropShape.OVAL
                fixAspectRatio = true 
                aspectRatioX = 1
                aspectRatioY = 1
            } else {
                cropShape = CropImageView.CropShape.RECTANGLE
                fixAspectRatio = false 
                aspectRatioX = 16
                aspectRatioY = 9
            }
            showProgressBar = true
            activityMenuIconColor = Color.WHITE
            backgroundColor = Color.parseColor("#CC000000")
        }
        cropImage.launch(CropImageContractOptions(uri, options))
    }

    private fun showImagePickerOptions(type: String) {
        val options = arrayOf("📸 Tomar Fotografía", "🖼️ Elegir de Galería")
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Imagen")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera(type)
                    1 -> openGallery(type)
                }
            }.show()
    }

    private fun openGallery(type: String) {
        when (type) {
            "LOGO" -> pickLogoLauncher.launch("image/*")
            "COVER" -> pickCoverLauncher.launch("image/*")
            "SECTION" -> pickSectionImageLauncher.launch("image/*")
        }
    }

    private var cameraImageUri: Uri? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraImageUri?.let { outState.putParcelable("camera_uri", it) }
        outState.putString("crop_type", currentCropType)
        outState.putString("target_section_id", currentTargetSectionId)
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            startCrop(cameraImageUri!!, currentCropType)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            openCamera(currentCropType)
        } else {
            Toast.makeText(this, "Se requiere permiso de cámara para tomar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera(type: String) {
        currentCropType = type
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            val photoFile = File(filesDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            if (photoFile.parentFile?.exists() == false) photoFile.parentFile?.mkdirs()
            
            cameraImageUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
            takePhotoLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir cámara: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "LOGO") }
    }

    private val pickCoverLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "COVER") }
    }

    private val pickSectionImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCrop(it, "SECTION") }
    }

    private val mapSelectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            businessLat = data?.getDoubleExtra("LAT", 0.0) ?: 0.0
            businessLng = data?.getDoubleExtra("LNG", 0.0) ?: 0.0
            data?.getStringExtra("ADDRESS")?.let { if(it.isNotEmpty()) binding.etEditStoreAddress.setText(it) }
            validateAiButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            cameraImageUri = savedInstanceState.getParcelable("camera_uri")
            currentCropType = savedInstanceState.getString("crop_type") ?: ""
            currentTargetSectionId = savedInstanceState.getString("target_section_id")
        }
        binding = ActivityStoreEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbarStoreEditor.setNavigationOnClickListener { finish() }
        loadCurrentData(intent.getStringExtra("FORCE_RUBRO"))
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
        val isValid = name.isNotEmpty() && desc.length >= 10 && addr.isNotEmpty() && hasLogo && hasCover
        binding.btnAiDesigner.isEnabled = isValid
        val color = if (isValid) Color.parseColor("#6366F1") else Color.parseColor("#94A3B8")
        binding.btnAiDesigner.setTextColor(color)
        binding.btnAiDesigner.iconTint = ColorStateList.valueOf(color)
    }

    private fun setupListeners() {
        binding.cardEditLogo.setOnClickListener { showImagePickerOptions("LOGO") }
        binding.cardEditLogo.setOnLongClickListener { showMainImageOptions("LOGO"); true }
        binding.cardEditCover.setOnClickListener { showImagePickerOptions("COVER") }
        binding.cardEditCover.setOnLongClickListener { showMainImageOptions("COVER"); true }
        binding.btnSelectLocationOnMap.setOnClickListener { mapSelectorLauncher.launch(Intent(this, BusinessMapSelectorActivity::class.java)) }
        binding.btnAddExtraSection.setOnClickListener { syncSectionsFromLayout(); extraSections.add(StoreSection(id = UUID.randomUUID().toString())); renderExtraSections() }
        binding.btnAddPaymentMethod.setOnClickListener { syncPaymentMethodsFromLayout(); paymentMethods.add(PaymentMethod()); renderPaymentMethods() }
        binding.btnAiDesigner.setOnClickListener { generateAiDesign() }
        binding.btnSaveStoreChanges.setOnClickListener { saveChanges(intent.getStringExtra("FORCE_RUBRO")) }

        binding.switchActivateOnlineStore.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val validation = isDataCompleteForMarket()
                if (!validation.first) {
                    buttonView.isChecked = false
                    Toast.makeText(this, "⚠️ Incompleto: ${validation.second}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isDataCompleteForMarket(): Pair<Boolean, String> {
        val name = binding.etEditStoreName.text.toString().trim()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        val addr = binding.etEditStoreAddress.text.toString().trim()

        if (name.isEmpty()) return false to "Falta el nombre"
        if (desc.length < 20) return false to "La descripción es muy corta"
        if (owner.isEmpty()) return false to "Indica el Fundador/Dueño"
        if (addr.isEmpty()) return false to "Ingresa una dirección"
        
        val hasLogo = selectedLogoUri != null || !binding.ivEditLogo.tag?.toString().isNullOrEmpty()
        val hasCover = selectedCoverUri != null || !binding.ivEditCover.tag?.toString().isNullOrEmpty()
        
        if (!hasLogo) return false to "Sube un Logo"
        if (!hasCover) return false to "Sube una Portada"

        return true to ""
    }

    private fun renderPaymentMethods() {
        binding.layoutPaymentMethodsContainer.removeAllViews()
        paymentMethods.forEachIndexed { index, method ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_editor_payment_method, binding.layoutPaymentMethodsContainer, false)
            val etTitle = itemView.findViewById<TextInputEditText>(R.id.etPaymentMethodTitle)
            val etAccount = itemView.findViewById<TextInputEditText>(R.id.etPaymentMethodAccount)
            val tvIcon = itemView.findViewById<TextView>(R.id.tvPaymentMethodIcon)
            etTitle.setText(method.title)
            etAccount.setText(method.accountInfo)
            val lower = method.title.lowercase()
            tvIcon.text = if(lower.contains("yape")) "🟣" else if(lower.contains("plin")) "🟢" else if(lower.contains("bcp") || lower.contains("banco")) "🏦" else "💳"
            itemView.findViewById<ImageButton>(R.id.btnRemovePaymentMethod).setOnClickListener { paymentMethods.removeAt(index); renderPaymentMethods() }
            binding.layoutPaymentMethodsContainer.addView(itemView)
        }
    }

    private fun renderExtraSections() {
        binding.layoutExtraSectionsContainer.removeAllViews()
        extraSections.forEach { section ->
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_editor_extra_section, binding.layoutExtraSectionsContainer, false)
            val etTitle = itemView.findViewById<TextInputEditText>(R.id.etSectionTitle)
            val etContent = itemView.findViewById<TextInputEditText>(R.id.etSectionContent)
            val layoutThumbnails = itemView.findViewById<LinearLayout>(R.id.layoutSectionThumbnails)
            etTitle.setText(section.title)
            etContent.setText(section.description)
            layoutThumbnails.removeAllViews()
            section.imageUrls.forEachIndexed { i, url -> val iv = createThumbnailView(url, true); iv.setOnLongClickListener { showImageOptions(section.id, i, false); true }; layoutThumbnails.addView(iv) }
            section.imageLocalPaths.forEachIndexed { i, path -> val iv = createThumbnailView(path, false); iv.setOnLongClickListener { showImageOptions(section.id, i, true); true }; layoutThumbnails.addView(iv) }
            itemView.findViewById<MaterialButton>(R.id.btnAddImageToSection).setOnClickListener { syncSectionsFromLayout(); currentTargetSectionId = section.id; replacingImageIndex = -1; showImagePickerOptions("SECTION") }
            itemView.findViewById<ImageButton>(R.id.btnRemoveSection).setOnClickListener { extraSections.removeIf { it.id == section.id }; renderExtraSections() }
            binding.layoutExtraSectionsContainer.addView(itemView)
        }
    }

    private fun showMainImageOptions(type: String) {
        val options = arrayOf("🗑️ Quitar Imagen", "🔄 Reemplazar Imagen")
        AlertDialog.Builder(this).setTitle("Gestionar Imagen").setItems(options) { _, which ->
            if (which == 0) {
                if (type == "LOGO") { selectedLogoUri = null; binding.ivEditLogo.tag = null; binding.ivEditLogo.setImageResource(R.drawable.gomezmart_icon) }
                else { selectedCoverUri = null; binding.ivEditCover.tag = null; binding.ivEditCover.setImageResource(R.drawable.gomezmart_full_bg) }
            } else showImagePickerOptions(type)
        }.show()
    }

    private fun createThumbnailView(path: String, isRemote: Boolean): ImageView {
        val size = (80 * resources.displayMetrics.density).toInt()
        val iv = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, 16, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.bg_rounded_8) 
        }
        
        val request = if (isRemote) Glide.with(this).load(path) else Glide.with(this).load(Uri.parse(path))
        request.placeholder(R.drawable.bg_rounded_8)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .thumbnail(0.1f)
            .into(iv)
            
        return iv
    }

    private fun showImageOptions(sectionId: String, imageIndex: Int, isLocal: Boolean) {
        val options = arrayOf("🗑️ Eliminar Imagen", "🔄 Reemplazar Imagen")
        AlertDialog.Builder(this).setTitle("Gestionar Imagen").setItems(options) { _, which ->
            val idx = extraSections.indexOfFirst { it.id == sectionId }
            if (idx == -1) return@setItems
            if (which == 0) {
                val section = extraSections[idx]
                if (isLocal) { val newList = section.imageLocalPaths.toMutableList(); newList.removeAt(imageIndex); extraSections[idx] = section.copy(imageLocalPaths = newList) }
                else { val newList = section.imageUrls.toMutableList(); newList.removeAt(imageIndex); extraSections[idx] = section.copy(imageUrls = newList) }
                renderExtraSections()
            } else { currentTargetSectionId = sectionId; replacingImageIndex = imageIndex; replacingIsLocal = isLocal; showImagePickerOptions("SECTION") }
        }.show()
    }

    private fun loadCurrentData(forcedRubro: String?) {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val bType = forcedRubro ?: prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val ownerId = if (prefs.getBoolean("is_collaborator_mode", false)) prefs.getString("collaborator_owner_id", null) else auth.currentUser?.uid
        binding.etEditStoreRubro.setText(when(bType) { "LOANS" -> "SERVICIOS / PRÉSTAMOS"; "HOTEL" -> "HOSPEDAJE / HOTELES"; else -> "TIENDA DE PRODUCTOS" })
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
                    binding.etEditStorePhone.setText(doc.getString("business_phone") ?: "")
                    binding.switchActivateOnlineStore.isChecked = doc.getBoolean("is_online") ?: false
                    businessLat = doc.getDouble("latitude") ?: 0.0
                    businessLng = doc.getDouble("longitude") ?: 0.0
                    doc.getString("business_logo_url")?.let { 
                        binding.ivEditLogo.tag = it
                        Glide.with(this@StoreEditorActivity)
                            .load(it)
                            .placeholder(R.drawable.gomezmart_icon)
                            .priority(Priority.HIGH)
                            .circleCrop()
                            .into(binding.ivEditLogo) 
                    }
                    doc.getString("business_cover_url")?.let { 
                        binding.ivEditCover.tag = it
                        Glide.with(this@StoreEditorActivity)
                            .load(it)
                            .placeholder(R.drawable.gomezmart_full_bg)
                            .priority(Priority.IMMEDIATE)
                            .centerCrop()
                            .into(binding.ivEditCover) 
                    }
                    
                    val sections = doc.get("extraSections") as? List<Map<String, Any>>
                    if (sections != null) {
                        extraSections.clear()
                        sections.forEach { m ->
                            extraSections.add(StoreSection(
                                id = m["id"] as? String ?: UUID.randomUUID().toString(),
                                title = m["title"] as? String ?: "",
                                description = m["description"] as? String ?: "",
                                imageUrls = (m["imageUrls"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                                aspectRatio = m["aspectRatio"] as? String ?: "16:9"
                            ))
                        }
                    }
                    
                    val payments = doc.get("paymentMethods") as? List<Map<String, Any>>
                    if (payments != null) {
                        paymentMethods.clear()
                        payments.forEach { m ->
                            paymentMethods.add(PaymentMethod(
                                type = m["type"] as? String ?: "BANK",
                                title = m["title"] as? String ?: "",
                                accountInfo = m["accountInfo"] as? String ?: ""
                            ))
                        }
                    }

                    (doc.get("theme") as? Map<String, String>)?.let { currentTheme.putAll(it) }
                    renderExtraSections(); renderPaymentMethods(); validateAiButton()
                } else if (tryFallback && docId != "business") fetchStoreData(ownerId, "business", false)
            } catch (e: Exception) { } finally { binding.btnSaveStoreChanges.isEnabled = true }
        }
    }

    private fun saveChanges(forcedRubro: String?) {
        val name = binding.etEditStoreName.text.toString().trim()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        val addr = binding.etEditStoreAddress.text.toString().trim()
        val phone = binding.etEditStorePhone.text.toString().trim()
        val isOnline = binding.switchActivateOnlineStore.isChecked
        
        if (name.isEmpty()) { 
            Toast.makeText(this, "El nombre es obligatorio", Toast.LENGTH_SHORT).show()
            return 
        }
        
        syncSectionsFromLayout()
        syncPaymentMethodsFromLayout()

        val progress = ProgressDialog(this).apply { 
            setMessage("Sincronizando tienda y fotos... ⏳✨")
            setCancelable(false) 
            show() 
        }

        val bType = forcedRubro ?: getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        val isCollab = getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getBoolean("is_collaborator_mode", false)
        val ownerId = if (isCollab) getSharedPreferences("BusinessPrefs", MODE_PRIVATE).getString("collaborator_owner_id", "")!! else auth.currentUser?.uid ?: ""
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // USAR EL BUCKET QUE FUNCIONA PARA TODOS LOS USUARIOS
                val bucket = "gomezmart-imagenes-usuarios"
                
                val data = mutableMapOf<String, Any>(
                    "business_name" to name, 
                    "business_description" to desc, 
                    "business_owner_name" to owner,
                    "business_address" to addr, 
                    "business_phone" to phone, 
                    "is_online" to isOnline, 
                    "latitude" to businessLat,
                    "longitude" to businessLng, 
                    "business_type" to bType, 
                    "theme" to currentTheme, 
                    "paymentMethods" to paymentMethods
                )

                // Preservar URLs existentes si no hay selección nueva
                val curLogo = withContext(Dispatchers.Main) { binding.ivEditLogo.tag?.toString() ?: "" }
                val curCover = withContext(Dispatchers.Main) { binding.ivEditCover.tag?.toString() ?: "" }
                
                if (curLogo.isNotEmpty()) data["business_logo_url"] = curLogo
                if (curCover.isNotEmpty()) data["business_cover_url"] = curCover

                // 1. Subir Logo y Portada (Si hay nuevos seleccionados)
                selectedLogoUri?.let { u -> 
                    saveImageLocally(u, "logo_tmp.png")?.let { p -> 
                        SupabaseManager.uploadImage(bucket, "logos/$ownerId/$bType.png", File(p))?.let { 
                            data["business_logo_url"] = it 
                        } 
                    } 
                }
                selectedCoverUri?.let { u -> 
                    saveImageLocally(u, "cover_tmp.png")?.let { p -> 
                        SupabaseManager.uploadImage(bucket, "covers/$ownerId/$bType.png", File(p))?.let { 
                            data["business_cover_url"] = it 
                        } 
                    } 
                }
                
                // 2. Procesar Secciones Extra (Subida de fotos nuevas)
                val finalSections = extraSections.map { s ->
                    val urls = s.imageUrls.toMutableList()
                    s.imageLocalPaths.forEach { l ->
                        try {
                            val uri = Uri.parse(l)
                            saveImageLocally(uri, "sec_${UUID.randomUUID()}.png")?.let { lp ->
                                SupabaseManager.uploadImage(bucket, "sections/$ownerId/${UUID.randomUUID()}.png", File(lp))?.let { 
                                    urls.add(it) 
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("StoreEditor", "Error subiendo imagen de sección: ${e.message}")
                        }
                    }
                    mapOf(
                        "id" to s.id, 
                        "title" to s.title, 
                        "description" to s.description, 
                        "imageUrls" to urls, 
                        "aspectRatio" to s.aspectRatio
                    )
                }
                
                data["extraSections"] = finalSections

                // 3. Guardar en Firestore con MERGE para no borrar otros campos si los hubiera
                db.collection("users").document(ownerId).collection("settings").document(bType)
                    .set(data, SetOptions.merge()).await()

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(this@StoreEditorActivity, "¡Tienda y fotos guardadas! 🌐✨", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    progress.dismiss()
                    Toast.makeText(this@StoreEditorActivity, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show() 
                    Log.e("StoreEditor", "Fallo crítico al guardar", e)
                }
            }
        }
    }

    private fun syncSectionsFromLayout() {
        for (i in 0 until binding.layoutExtraSectionsContainer.childCount) {
            val view = binding.layoutExtraSectionsContainer.getChildAt(i)
            val etTitle = view.findViewById<TextInputEditText>(R.id.etSectionTitle)
            val etContent = view.findViewById<TextInputEditText>(R.id.etSectionContent)
            if (i < extraSections.size) {
                extraSections[i] = extraSections[i].copy(title = etTitle.text.toString(), description = etContent.text.toString())
            }
        }
    }

    private fun syncPaymentMethodsFromLayout() {
        for (i in 0 until binding.layoutPaymentMethodsContainer.childCount) {
            val view = binding.layoutPaymentMethodsContainer.getChildAt(i)
            val etTitle = view.findViewById<TextInputEditText>(R.id.etPaymentMethodTitle)
            val etAccount = view.findViewById<TextInputEditText>(R.id.etPaymentMethodAccount)
            if (i < paymentMethods.size) {
                paymentMethods[i] = paymentMethods[i].copy(title = etTitle.text.toString(), accountInfo = etAccount.text.toString())
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

    private fun generateAiDesign() {
        val name = binding.etEditStoreName.text.toString().trim()
        val cat = binding.etEditStoreRubro.text.toString()
        val desc = binding.etEditStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        if (name.isEmpty() || desc.isEmpty()) { Toast.makeText(this, "Completa los datos básicos ✨", Toast.LENGTH_LONG).show(); return }
        val progress = ProgressDialog(this).apply { setMessage("La IA está RE-DISEÑANDO tu tienda completa... 🪄🤖"); setCancelable(false); show() }
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
            Toast.makeText(this, "¡Tienda transformada por la IA! 🪄✨", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
}
