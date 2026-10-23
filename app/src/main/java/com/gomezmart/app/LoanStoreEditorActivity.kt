package com.gomezmart.app

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
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
import com.gomezmart.app.data.PaymentMethod
import com.gomezmart.app.data.StoreSection
import com.gomezmart.app.databinding.ActivityLoanStoreEditorBinding
import com.gomezmart.app.network.SupabaseManager
import com.gomezmart.app.util.GeminiHelper
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
    
    private var businessLat = 0.0
    private var businessLng = 0.0

    private val currentTheme = mutableMapOf<String, String>()
    private val extraSections = mutableListOf<StoreSection>()
    private val paymentMethods = mutableListOf<PaymentMethod>()

    private var currentCropType = "" // "LOGO", "COVER", "SECTION"
    private var currentTargetSectionId: String? = null
    private var replacingImageIndex: Int = -1
    private var replacingIsLocal: Boolean = false
    private var cameraImageUri: Uri? = null

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
                    updateButtonState()
                }
                "COVER" -> {
                    selectedCoverUri = uriContent
                    Glide.with(this)
                        .load(uriContent)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .into(binding.ivEditCover)
                    updateButtonState()
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
            data?.getStringExtra("ADDRESS")?.let { if (it.isNotEmpty()) binding.etEditStoreAddress.setText(it) }
            updateButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            cameraImageUri = savedInstanceState.getParcelable("camera_uri")
            currentCropType = savedInstanceState.getString("crop_type") ?: ""
            currentTargetSectionId = savedInstanceState.getString("target_section_id")
        }
        binding = ActivityLoanStoreEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para barra de estado y barra de navegación
        applyWindowInsets(binding.toolbarLoanEditor, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.layoutLoanEditorContent, applyTop = false, applyBottom = true)

        binding.toolbarLoanEditor.setNavigationOnClickListener { finish() }
        
        loadCurrentData()
        setupValidation()
        setupListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraImageUri?.let { outState.putParcelable("camera_uri", it) }
        outState.putString("crop_type", currentCropType)
        outState.putString("target_section_id", currentTargetSectionId)
    }

    private fun setupValidation() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateButtonState() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.etLoanStoreName.addTextChangedListener(watcher)
        binding.etLoanStoreDesc.addTextChangedListener(watcher)
        binding.etLoanRequirements.addTextChangedListener(watcher)
        binding.etEditStoreAddress.addTextChangedListener(watcher)
    }

    private fun isDataComplete(): Pair<Boolean, String> {
        val name = binding.etLoanStoreName.text.toString().trim()
        val desc = binding.etLoanStoreDesc.text.toString().trim()
        val reqs = binding.etLoanRequirements.text.toString().trim()

        if (name.isEmpty()) return false to "Falta el nombre"
        if (desc.length < 10) return false to "La descripción es muy corta"
        if (reqs.length < 5) return false to "Detalla los requisitos"

        val hasLogo = selectedLogoUri != null || !binding.ivEditLogo.tag?.toString().isNullOrEmpty()
        val hasCover = selectedCoverUri != null || !binding.ivEditCover.tag?.toString().isNullOrEmpty()

        if (!hasLogo) return false to "Sube un Logo"
        if (!hasCover) return false to "Sube una Portada"

        return true to ""
    }

    private fun updateButtonState() {
        val complete = isDataComplete().first
        if (complete) {
            binding.btnSaveLoanStore.text = "GUARDAR Y PUBLICAR ✨"
            binding.switchActivateLoanMarket.isChecked = true
        } else {
            binding.btnSaveLoanStore.text = "GUARDAR CAMBIOS 💾"
        }

        // IA Button validation
        val name = binding.etLoanStoreName.text.toString().trim()
        val desc = binding.etLoanStoreDesc.text.toString().trim()
        val reqs = binding.etLoanRequirements.text.toString().trim()
        val hasLogo = selectedLogoUri != null || !binding.ivEditLogo.tag?.toString().isNullOrEmpty()
        val hasCover = selectedCoverUri != null || !binding.ivEditCover.tag?.toString().isNullOrEmpty()
        val isAiValid = name.isNotEmpty() && desc.length >= 10 && reqs.isNotEmpty() && hasLogo && hasCover
        binding.btnAiDesigner.isEnabled = isAiValid
        val color = if (isAiValid) Color.parseColor("#6366F1") else Color.parseColor("#94A3B8")
        binding.btnAiDesigner.setTextColor(color)
        binding.btnAiDesigner.iconTint = ColorStateList.valueOf(color)
    }

    private fun setupListeners() {
        binding.cardEditLogo.setOnClickListener { showImagePickerOptions("LOGO") }
        binding.cardEditLogo.setOnLongClickListener { showMainImageOptions("LOGO"); true }
        binding.cardEditCover.setOnClickListener { showImagePickerOptions("COVER") }
        binding.cardEditCover.setOnLongClickListener { showMainImageOptions("COVER"); true }
        
        binding.btnSelectLocationOnMap.setOnClickListener {
            mapSelectorLauncher.launch(Intent(this, BusinessMapSelectorActivity::class.java))
        }

        binding.btnAddPaymentMethod.setOnClickListener {
            syncPaymentMethodsFromLayout()
            paymentMethods.add(PaymentMethod())
            renderPaymentMethods()
        }

        binding.btnAddExtraSection.setOnClickListener {
            syncSectionsFromLayout()
            extraSections.add(StoreSection(id = UUID.randomUUID().toString()))
            renderExtraSections()
        }

        binding.btnAiDesigner.setOnClickListener { generateAiDesign() }
        binding.btnSaveLoanStore.setOnClickListener { saveChanges() }

        binding.switchActivateLoanMarket.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val validation = isDataComplete()
                if (!validation.first) {
                    buttonView.isChecked = false
                    Toast.makeText(this, "⚠️ Para publicar en el mercado, completa: ${validation.second}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showMainImageOptions(type: String) {
        val options = arrayOf("🗑️ Quitar Imagen", "🔄 Reemplazar Imagen")
        AlertDialog.Builder(this).setTitle("Gestionar Imagen").setItems(options) { _, which ->
            if (which == 0) {
                if (type == "LOGO") {
                    selectedLogoUri = null
                    binding.ivEditLogo.tag = null
                    binding.ivEditLogo.setImageResource(R.drawable.gomezmart_icon)
                } else {
                    selectedCoverUri = null
                    binding.ivEditCover.tag = null
                    binding.ivEditCover.setImageResource(R.drawable.gomezmart_full_bg)
                }
                updateButtonState()
            } else {
                showImagePickerOptions(type)
            }
        }.show()
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
            tvIcon.text = if (lower.contains("yape")) "🟣" else if (lower.contains("plin")) "🟢" else if (lower.contains("bcp") || lower.contains("banco")) "🏦" else "💳"
            itemView.findViewById<ImageButton>(R.id.btnRemovePaymentMethod).setOnClickListener {
                paymentMethods.removeAt(index)
                renderPaymentMethods()
            }
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
            section.imageUrls.forEachIndexed { i, url ->
                val iv = createThumbnailView(url, true)
                iv.setOnLongClickListener { showImageOptions(section.id, i, false); true }
                layoutThumbnails.addView(iv)
            }
            section.imageLocalPaths.forEachIndexed { i, path ->
                val iv = createThumbnailView(path, false)
                iv.setOnLongClickListener { showImageOptions(section.id, i, true); true }
                layoutThumbnails.addView(iv)
            }
            itemView.findViewById<MaterialButton>(R.id.btnAddImageToSection).setOnClickListener {
                syncSectionsFromLayout()
                currentTargetSectionId = section.id
                replacingImageIndex = -1
                showImagePickerOptions("SECTION")
            }
            itemView.findViewById<ImageButton>(R.id.btnRemoveSection).setOnClickListener {
                extraSections.removeIf { it.id == section.id }
                renderExtraSections()
            }
            binding.layoutExtraSectionsContainer.addView(itemView)
        }
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
                if (isLocal) {
                    val newList = section.imageLocalPaths.toMutableList()
                    newList.removeAt(imageIndex)
                    extraSections[idx] = section.copy(imageLocalPaths = newList)
                } else {
                    val newList = section.imageUrls.toMutableList()
                    newList.removeAt(imageIndex)
                    extraSections[idx] = section.copy(imageUrls = newList)
                }
                renderExtraSections()
            } else {
                currentTargetSectionId = sectionId
                replacingImageIndex = imageIndex
                replacingIsLocal = isLocal
                showImagePickerOptions("SECTION")
            }
        }.show()
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
                    binding.etEditOwnerName.setText(doc.getString("business_owner_name") ?: "")
                    binding.etEditStorePhone.setText(doc.getString("business_phone") ?: "")
                    binding.etEditStoreAddress.setText(doc.getString("business_address") ?: "")
                    binding.switchActivateLoanMarket.isChecked = doc.getBoolean("is_online") ?: false
                    
                    businessLat = doc.getDouble("latitude") ?: 0.0
                    businessLng = doc.getDouble("longitude") ?: 0.0

                    doc.getString("business_logo_url")?.let {
                        binding.ivEditLogo.tag = it
                        Glide.with(this@LoanStoreEditorActivity)
                            .load(it)
                            .placeholder(R.drawable.gomezmart_icon)
                            .priority(Priority.HIGH)
                            .circleCrop()
                            .into(binding.ivEditLogo)
                    }
                    doc.getString("business_cover_url")?.let {
                        binding.ivEditCover.tag = it
                        Glide.with(this@LoanStoreEditorActivity)
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
                                imageUrls = (m["imageUrls"] as? List<*>)?.map { it.toString() } ?: emptyList()
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
                    renderExtraSections()
                    renderPaymentMethods()
                    updateButtonState()
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
        val owner = binding.etEditOwnerName.text.toString().trim()
        val phone = binding.etEditStorePhone.text.toString().trim()
        val addr = binding.etEditStoreAddress.text.toString().trim()
        
        val isComplete = isDataComplete().first
        // Si está completo se publica automáticamente, si no, respeta el estado del switch
        val isOnline = if (isComplete) true else binding.switchActivateLoanMarket.isChecked

        if (name.isEmpty() && desc.isEmpty()) {
            Toast.makeText(this, "Ingresa al menos el nombre del negocio para guardar", Toast.LENGTH_SHORT).show()
            return
        }

        syncSectionsFromLayout()
        syncPaymentMethodsFromLayout()

        val progress = ProgressDialog(this).apply {
            setMessage(if (isComplete) "Guardando y publicando en el mercado... 🚀✨" else "Guardando cambios... 💾")
            setCancelable(false)
            show()
        }

        val userId = auth.currentUser?.uid ?: ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var detectedCity = ""
                var detectedPostalCode = ""
                try {
                    val geocoder = Geocoder(this@LoanStoreEditorActivity, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(businessLat, businessLng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addrRes = addresses[0]
                        detectedCity = addrRes.locality ?: addrRes.subAdminArea ?: ""
                        detectedPostalCode = addrRes.postalCode ?: ""
                    }
                } catch (e: Exception) {
                    Log.e("LoanStoreEditor", "Error detectando zona: ${e.message}")
                }

                val bucket = "naxor-imagenes-usuarios"
                val data = mutableMapOf<String, Any>(
                    "business_name" to name,
                    "business_description" to desc,
                    "loan_requirements" to reqs,
                    "business_owner_name" to owner,
                    "business_phone" to phone,
                    "business_address" to addr,
                    "is_online" to isOnline,
                    "business_type" to "LOANS",
                    "business_category" to "SERVICIOS FINANCIEROS",
                    "latitude" to businessLat,
                    "longitude" to businessLng,
                    "business_city" to detectedCity,
                    "business_postal_code" to detectedPostalCode,
                    "theme" to currentTheme,
                    "paymentMethods" to paymentMethods
                )

                val curLogo = withContext(Dispatchers.Main) { binding.ivEditLogo.tag?.toString() ?: "" }
                val curCover = withContext(Dispatchers.Main) { binding.ivEditCover.tag?.toString() ?: "" }
                if (curLogo.isNotEmpty()) data["business_logo_url"] = curLogo
                if (curCover.isNotEmpty()) data["business_cover_url"] = curCover

                selectedLogoUri?.let { uri ->
                    saveImageLocally(uri, "loan_logo_tmp.png")?.let { path ->
                        SupabaseManager.uploadImage(bucket, "logos/$userId/LOANS.png", File(path))?.let { url ->
                            data["business_logo_url"] = url
                        }
                    }
                }

                selectedCoverUri?.let { uri ->
                    saveImageLocally(uri, "loan_cover_tmp.png")?.let { path ->
                        SupabaseManager.uploadImage(bucket, "covers/$userId/LOANS.png", File(path))?.let { url ->
                            data["business_cover_url"] = url
                        }
                    }
                }

                val finalSections = extraSections.map { s ->
                    val urls = s.imageUrls.toMutableList()
                    s.imageLocalPaths.forEach { l ->
                        try {
                            val uri = Uri.parse(l)
                            saveImageLocally(uri, "sec_${UUID.randomUUID()}.png")?.let { lp ->
                                SupabaseManager.uploadImage(bucket, "sections/$userId/${UUID.randomUUID()}.png", File(lp))?.let {
                                    urls.add(it)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("LoanStoreEditor", "Error subiendo imagen de sección: ${e.message}")
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

                db.collection("users").document(userId)
                    .collection("settings").document("LOANS")
                    .set(data, SetOptions.merge()).await()

                if (owner.isNotEmpty()) {
                    db.collection("users").document(userId).collection("profile").document("info")
                        .set(mapOf("name" to owner), SetOptions.merge()).await()
                }

                getSharedPreferences("BusinessPrefs", MODE_PRIVATE).edit()
                    .putBoolean("is_online_store", isOnline)
                    .apply()

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    val msg = if (isComplete) "¡Negocio guardado y publicado en el mercado! 🚀✨" else "¡Cambios guardados correctamente! 💾"
                    Toast.makeText(this@LoanStoreEditorActivity, msg, Toast.LENGTH_SHORT).show()
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
        for (i in 0 until binding.layoutExtraSectionsContainer.childCount) {
            val view = binding.layoutExtraSectionsContainer.getChildAt(i)
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
        val name = binding.etLoanStoreName.text.toString().trim()
        val desc = binding.etLoanStoreDesc.text.toString().trim()
        val owner = binding.etEditOwnerName.text.toString().trim()
        if (name.isEmpty() || desc.isEmpty()) {
            Toast.makeText(this, "Completa los datos básicos ✨", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(this).apply {
            setMessage("La IA está RE-DISEÑANDO tu financiera... 🪄🤖")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            try {
                val jsonResponse = GeminiHelper.generateStoreDesignProposal(name, "SERVICIOS FINANCIEROS", desc, owner)
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val responseMap: Map<String, Any> = Gson().fromJson(jsonResponse, type)
                val businessInfo = responseMap["businessInfo"] as? Map<String, String> ?: emptyMap()
                val themeMap = responseMap["theme"] as? Map<String, String> ?: emptyMap()
                val sectionsList = responseMap["sections"] as? List<Map<String, String>> ?: emptyList()
                progress.dismiss()
                if (sectionsList.isNotEmpty() || themeMap.isNotEmpty() || businessInfo.isNotEmpty()) {
                    showAiPreviewDialog(businessInfo, themeMap, sectionsList)
                } else {
                    Toast.makeText(this@LoanStoreEditorActivity, "Error en propuesta IA", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@LoanStoreEditorActivity, "Error IA: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAiPreviewDialog(proposedInfo: Map<String, String>, proposedTheme: Map<String, String>, proposedSections: List<Map<String, String>>) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("✨ Propuesta de RE-DISEÑO TOTAL")
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 30, 50, 30) }
        scrollView.addView(container)
        proposedSections.forEach { sec ->
            val tv = TextView(this).apply {
                text = "📍 ${sec["title"]}\n${sec["description"]}"
                textSize = 14f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, 10, 0, 20)
            }
            container.addView(tv)
        }
        builder.setView(scrollView)
        builder.setPositiveButton("REEMPLAZAR Y APLICAR") { _, _ ->
            proposedInfo["name"]?.let { binding.etLoanStoreName.setText(it) }
            proposedInfo["description"]?.let { binding.etLoanStoreDesc.setText(it) }
            currentTheme.clear()
            currentTheme.putAll(proposedTheme)
            extraSections.clear()
            proposedSections.forEach { sec ->
                extraSections.add(StoreSection(id = UUID.randomUUID().toString(), title = sec["title"] ?: "", description = sec["description"] ?: ""))
            }
            renderExtraSections()
            Toast.makeText(this, "¡Financiera transformada por la IA! 🪄✨", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
}
