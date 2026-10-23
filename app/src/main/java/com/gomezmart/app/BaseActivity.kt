package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            // Ignorar si el motor MIUI/HyperOS o la versión del sistema no soporta Edge-to-Edge
        }
        super.onCreate(savedInstanceState)
    }

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(newBase)
            return
        }
        
        try {
            val res = newBase.resources
            if (res != null) {
                val config = Configuration(res.configuration)
                config.fontScale = 1.0f
                val context = newBase.createConfigurationContext(config)
                super.attachBaseContext(context)
            } else {
                super.attachBaseContext(newBase)
            }
        } catch (e: Exception) {
            super.attachBaseContext(newBase)
        }
    }

    // Asegurar que si la configuración cambia dinámicamente, se mantenga el bloqueo
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            overrideConfiguration.fontScale = 1.0f
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
            
            val initialTop = v.getTag(R.id.tag_initial_padding_top) as? Int ?: v.paddingTop.also { v.setTag(R.id.tag_initial_padding_top, it) }
            val initialBottom = v.getTag(R.id.tag_initial_padding_bottom) as? Int ?: v.paddingBottom.also { v.setTag(R.id.tag_initial_padding_bottom, it) }

            val safeTop = if (applyTop) initialTop + statusBars.top else initialTop
            val safeBottom = if (applyBottom) initialBottom + navBars.bottom else initialBottom

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
            val params = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@setOnApplyWindowInsetsListener insets
            
            val initialTop = v.getTag(R.id.tag_initial_margin_top) as? Int ?: params.topMargin.also { v.setTag(R.id.tag_initial_margin_top, it) }
            val initialBottom = v.getTag(R.id.tag_initial_margin_bottom) as? Int ?: params.bottomMargin.also { v.setTag(R.id.tag_initial_margin_bottom, it) }

            val targetTop = if (applyTop) initialTop + statusBars.top else initialTop
            val targetBottom = if (applyBottom) initialBottom + navBars.bottom else initialBottom

            if (params.topMargin != targetTop || params.bottomMargin != targetBottom) {
                params.topMargin = targetTop
                params.bottomMargin = targetBottom
                v.layoutParams = params
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
                v.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Inicia una actividad con un efecto de desvanecimiento (fade).
     */
    fun startActivityWithFade(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
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

    override fun finish() {
        super.finish()
        // Opcional: Aplicar fade también al cerrar si se desea consistencia
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
