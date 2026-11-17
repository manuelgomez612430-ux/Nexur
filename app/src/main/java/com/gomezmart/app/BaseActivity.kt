package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.CartManager
import com.gomezmart.app.util.FloatingCartHelper
import com.gomezmart.app.util.SpanishInputHelper
import java.util.Locale

open class BaseActivity : AppCompatActivity() {

    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus is EditText) {
            SpanishInputHelper.configureEditText(newFocus, attachInlineBarIfPossible = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            // Ignorar si el motor MIUI/HyperOS o la versión del sistema no soporta Edge-to-Edge
        }
        super.onCreate(savedInstanceState)
        try {
            window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener(globalFocusListener)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            window?.decorView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(globalFocusListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focusedEditText = currentFocus as? EditText
        if (focusedEditText != null && SpanishInputHelper.dispatchActivityOrDialogKeyEvent(focusedEditText, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try {
            SpanishInputHelper.hookAllOpenWindows()
            window?.decorView?.postDelayed({
                try {
                    SpanishInputHelper.hookAllOpenWindows()
                } catch (_: Exception) {}
            }, 80)
        } catch (_: Exception) {}
    }

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(null)
            return
        }
        val contextToAttach = try {
            val esLocale = SpanishInputHelper.getSpanishLocale()
            Locale.setDefault(esLocale)
            val config = Configuration(newBase.resources.configuration)
            config.fontScale = 1.0f
            config.setLocale(esLocale)
            config.setLocales(LocaleList(esLocale, Locale.forLanguageTag("es")))
            newBase.createConfigurationContext(config)
        } catch (e: Exception) {
            newBase
        }
        super.attachBaseContext(contextToAttach)
        try {
            com.google.android.play.core.splitcompat.SplitCompat.installActivity(this)
        } catch (_: Exception) {}
    }

    // Asegurar que si la configuración cambia dinámicamente, se mantenga el bloqueo
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            overrideConfiguration.fontScale = 1.0f
            val esLocale = SpanishInputHelper.getSpanishLocale()
            overrideConfiguration.setLocale(esLocale)
            overrideConfiguration.setLocales(LocaleList(esLocale, Locale.forLanguageTag("es")))
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onResume() {
        super.onResume()
        if (shouldShowFloatingCart()) {
            FloatingCartHelper.show(this)
        } else {
            FloatingCartHelper.hide()
        }
        try {
            SpanishInputHelper.configureAllEditTextsInTree(window?.decorView)
        } catch (_: Exception) {}
        com.gomezmart.app.network.FcmHelper.initUserFcm(this)
    }

    override fun onPause() {
        super.onPause()
        CartManager.save(this)
    }

    open fun shouldShowFloatingCart(): Boolean {
        val name = this::class.java.simpleName
        // Whitelist: El carrito flotante solo debe mostrarse en pantallas de navegación/compras del comprador
        val buyerShoppingActivities = listOf(
            "BuyerHomeActivity",
            "ProductDetailActivity",
            "StoreProfileActivity",
            "LoanStoreProfileActivity",
            "BuyerFavoritesActivity",
            "ViewedHistoryActivity"
        )
        return buyerShoppingActivities.contains(name)
    }

    /**
     * Aplica relleno (padding) dinámico para evitar que la UI sea tapada por barras del sistema.
     * [AJUSTE] Ahora se pega al borde de los botones sin dejar espacio extra.
     */
    fun applyWindowInsets(view: View, applyTop: Boolean = true, applyBottom: Boolean = true) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            val initialTop = v.getTag(R.id.tag_initial_padding_top) as? Int ?: v.paddingTop.also { v.setTag(R.id.tag_initial_padding_top, it) }
            val initialBottom = v.getTag(R.id.tag_initial_padding_bottom) as? Int ?: v.paddingBottom.also { v.setTag(R.id.tag_initial_padding_bottom, it) }

            val safeTop = if (applyTop) initialTop + statusBars.top else initialTop
            val safeBottom = if (applyBottom) initialBottom + kotlin.math.max(navBars.bottom, ime.bottom) else initialBottom

            v.setPadding(v.paddingLeft, safeTop, v.paddingRight, safeBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Aplica márgenes dinámicos para mover elementos enteros lejos de las barras del sistema.
     * [AJUSTE] Ahora se pega al borde de los botones sin dejar espacio extra.
     */
    fun applyWindowInsetsMargins(view: View, applyTop: Boolean = true, applyBottom: Boolean = true) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val params = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@setOnApplyWindowInsetsListener insets
            
            val initialTop = v.getTag(R.id.tag_initial_margin_top) as? Int ?: params.topMargin.also { v.setTag(R.id.tag_initial_margin_top, it) }
            val initialBottom = v.getTag(R.id.tag_initial_margin_bottom) as? Int ?: params.bottomMargin.also { v.setTag(R.id.tag_initial_margin_bottom, it) }

            val targetTop = if (applyTop) initialTop + statusBars.top else initialTop
            val targetBottom = if (applyBottom) initialBottom + kotlin.math.max(navBars.bottom, ime.bottom) else initialBottom

            if (params.topMargin != targetTop || params.bottomMargin != targetBottom) {
                params.topMargin = targetTop
                params.bottomMargin = targetBottom
                if (v.isInLayout) {
                    v.post { if (v.isAttachedToWindow) v.layoutParams = params }
                } else {
                    v.layoutParams = params
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Ajusta la altura de una vista sumándole el tamaño de las barras del sistema.
     * Útil para fondos de cabecera.
     */
    fun applyWindowInsetsHeight(view: View, applyTop: Boolean = true, applyBottom: Boolean = false) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val params = v.layoutParams
            
            val initialHeight = v.getTag(R.id.tag_initial_height) as? Int ?: params.height.also { v.setTag(R.id.tag_initial_height, it) }

            var extra = 0
            if (applyTop) extra += statusBars.top
            if (applyBottom) extra += navBars.bottom
            
            val targetHeight = if (initialHeight > 0) initialHeight + extra else params.height

            if (params.height != targetHeight) {
                params.height = targetHeight
                if (v.isInLayout) {
                    v.post { if (v.isAttachedToWindow) v.layoutParams = params }
                } else {
                    v.layoutParams = params
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Inicia una actividad con un efecto de desvanecimiento suave sin parpadeo de fondo.
     */
    fun startActivityWithFade(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.stay)
    }

    private var reportImageUri: android.net.Uri? = null
    private var reportDialogView: View? = null

    private val reportImageLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val uri = uri ?: return@registerForActivityResult
        reportImageUri = uri
        val view = reportDialogView ?: return@registerForActivityResult
        val previewContainer = view.findViewById<View>(R.id.cardReportPhotoPreviewContainer) ?: return@registerForActivityResult
        val ivPreview = view.findViewById<android.widget.ImageView>(R.id.ivReportPhotoPreview) ?: return@registerForActivityResult
        previewContainer.visibility = View.VISIBLE
        try {
            com.bumptech.glide.Glide.with(this).load(uri).centerCrop().into(ivPreview)
        } catch (e: Exception) { }
    }

    /**
     * Muestra el bottom sheet de reporte de errores / sugerencias.
     * Reutiliza la misma lógica que el panel del comprador (Mercado Pago style).
     */
    fun showReportErrorDialog(activity: androidx.fragment.app.FragmentActivity) {
        reportImageUri = null
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_report_error, null)
        reportDialogView = view
        bottomSheet.setContentView(view)

        val etDesc = view.findViewById<TextInputEditText>(R.id.etReportDescription)
        val chipBug = view.findViewById<Chip>(R.id.chipReportBug)
        val chipSuggestion = view.findViewById<Chip>(R.id.chipReportSuggestion)
        val btnAttach = view.findViewById<MaterialButton>(R.id.btnAttachReportPhoto)
        val btnRemove = view.findViewById<View>(R.id.btnRemoveReportPhoto)
        val previewContainer = view.findViewById<View>(R.id.cardReportPhotoPreviewContainer)
        val btnSend = view.findViewById<MaterialButton>(R.id.btnSendReport)
        val btnCancel = view.findViewById<View>(R.id.btnCancelReport)

        btnAttach?.setOnClickListener {
            reportImageLauncher.launch("image/*")
        }

        btnRemove?.setOnClickListener {
            reportImageUri = null
            previewContainer?.visibility = View.GONE
        }

        btnCancel?.setOnClickListener { bottomSheet.dismiss() }

        btnSend?.setOnClickListener {
            val desc = etDesc?.text.toString().trim()
            if (desc.isEmpty()) {
                Toast.makeText(activity, "Escribe los detalles del reporte ⚠️", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val category = when {
                chipBug?.isChecked == true -> "🐛 Error / Fallo"
                chipSuggestion?.isChecked == true -> "💡 Sugerencia"
                else -> "❓ Consulta"
            }

            val imageUri = reportImageUri
            bottomSheet.dismiss()
            reportDialogView = null
            sendReportViaEmail(activity, category, desc, imageUri)
        }

        bottomSheet.setOnDismissListener { reportDialogView = null }
        bottomSheet.show()
    }

    fun sendReportViaEmail(activity: androidx.fragment.app.FragmentActivity, category: String, description: String, imageUri: android.net.Uri? = null) {
        val prefs = activity.getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = prefs.getString("user_full_name", FirebaseAuth.getInstance().currentUser?.displayName ?: "Usuario")
        val userEmail = prefs.getString("user_email", FirebaseAuth.getInstance().currentUser?.email ?: "No especificado")
        val userPhone = prefs.getString("user_phone", "No especificado")

        val targetEmail = "manuelgomez612430@gmail.com"
        val subject = "[Reporte GoMezMart] $category - $userName"

        val bodyText = "--- REPORTE ENVIADO DESDE GOMEZMART ---\n" +
                "• Tipo: $category\n" +
                "• Usuario: $userName\n" +
                "• Correo: $userEmail\n" +
                "• Teléfono: $userPhone\n" +
                "• Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n\n" +
                "---------------------------------------\n" +
                "DETALLES DEL REPORTE:\n" +
                "$description\n" +
                "---------------------------------------"

        val reportMap = mapOf(
            "category" to category,
            "userName" to (userName ?: ""),
            "userEmail" to (userEmail ?: ""),
            "userPhone" to (userPhone ?: ""),
            "description" to description,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "timestamp" to System.currentTimeMillis()
        )
        FirebaseFirestore.getInstance().collection("error_reports").add(reportMap)

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (imageUri != null) "image/*" else "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, bodyText)
            if (imageUri != null) {
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        try {
            activity.startActivity(Intent.createChooser(emailIntent, "Enviar reporte por correo..."))
            Toast.makeText(activity, "Elige tu aplicación de correo para enviar 📧", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "No se encontró aplicación de correo", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Comprueba si el usuario actual es un invitado
     */
    fun isGuestUser(): Boolean {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (e: Exception) { null }
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        
        // Si hay una cuenta real iniciada, definitivamente NO es invitado
        if (user != null && !user.isAnonymous) {
            if (prefs.getBoolean("is_guest", false)) {
                prefs.edit().putBoolean("is_guest", false).apply()
            }
            return false
        }
        
        // Es invitado si activó la opción "is_guest" previamente
        return prefs.getBoolean("is_guest", false)
    }

    /**
     * Muestra un diálogo bloqueando la acción e invitando a iniciar sesión
     * Retorna true si es invitado (la acción debe detenerse), false si es usuario real.
     */
    fun requireLoginForGuest(actionDescription: String = "realizar esta acción"): Boolean {
        if (!isGuestUser()) return false

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Inicia sesión")
            .setMessage("Debes tener una cuenta registrada para $actionDescription.")
            .setPositiveButton("Crear cuenta / Ingresar") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cerrar", null)
            .show()
        return true
    }

    /**
     * Verifica que el módulo dinámico correspondiente a [businessType] esté instalado antes de entrar al sistema.
     * Si no está descargado, bloquea el ingreso y muestra un diálogo con barra de progreso para instalarlo primero.
     */
    fun ensureModuleInstalled(businessType: String, onInstalled: () -> Unit) {
        val dynamicManager = com.gomezmart.app.utils.DynamicModuleManager(this)
        val module = when (businessType) {
            "HOTEL" -> com.gomezmart.app.utils.DynamicFeatureModule.HOTEL
            "LOANS" -> com.gomezmart.app.utils.DynamicFeatureModule.LOANS
            else -> com.gomezmart.app.utils.DynamicFeatureModule.PRODUCTS
        }

        if (dynamicManager.isModuleInstalled(module)) {
            onInstalled()
            return
        }

        val systemName = when (businessType) {
            "HOTEL" -> "Sistema de Hotel"
            "LOANS" -> "Sistema de Préstamos"
            else -> "Sistema de Productos"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📥 $systemName no instalado")
            .setMessage("Tu $systemName aún no está descargado en este dispositivo.\n\nPresiona 'Instalar Sistema' para descargarlo e ingresar.")
            .setPositiveButton("Instalar Sistema") { _, _ ->
                val dialogLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(60, 40, 60, 40)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = false
                    max = 100
                    progress = 0
                }
                val statusText = android.widget.TextView(this).apply {
                    text = "Descargando e instalando $systemName: 0%"
                    textSize = 14f
                    textAlignment = android.widget.TextView.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 24, 0, 0)
                }
                dialogLayout.addView(progressBar)
                dialogLayout.addView(statusText)

                val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Descargando $systemName 🚀")
                    .setView(dialogLayout)
                    .setCancelable(false)
                    .show()

                dynamicManager.launchOrDownloadModule(
                    module = module,
                    autoLaunchOnSuccess = false,
                    onProgress = { progress ->
                        progressBar.isIndeterminate = false
                        progressBar.progress = progress
                        statusText.text = "Descargando e instalando $systemName: $progress%"
                    },
                    onSuccess = {
                        if (!isFinishing && !isDestroyed) {
                            progressDialog.dismiss()
                            Toast.makeText(this, "$systemName instalado con éxito ✅", Toast.LENGTH_SHORT).show()
                            onInstalled()
                        }
                    },
                    onError = { err ->
                        if (!isFinishing && !isDestroyed) {
                            progressDialog.dismiss()
                            Toast.makeText(this, "Error al instalar: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .setNegativeButton("Cancelar") { _, _ ->
                if (this is MainActivity) {
                    startActivity(Intent(this, BuyerHomeActivity::class.java))
                    finish()
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.fade_out)
    }
}
