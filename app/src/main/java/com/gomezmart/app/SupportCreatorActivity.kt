package com.gomezmart.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.gomezmart.app.databinding.ActivitySupportCreatorBinding

class SupportCreatorActivity : BaseActivity() {

    private lateinit var binding: ActivitySupportCreatorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets con margen para mover completamente la cabecera por debajo de la barra de estado
        applyWindowInsetsMargins(binding.appBarSupport, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)

        binding.toolbarSupport.setNavigationOnClickListener { finish() }

        // Cargar el QR real. Se referencia de forma DIRECTA (R.drawable) en lugar de
        // usar resources.getIdentifier() por reflexión: así R8 puede eliminar el
        // recurso si no se usa y se evita el coste de reflexión en cada carga.
        binding.ivQrYape.setImageResource(R.drawable.qr_yape_manuel)

        binding.btnFutureInfo.setOnClickListener {
            startActivity(android.content.Intent(this, FutureVisionActivity::class.java))
        }

        binding.btnToggleYape.setOnClickListener {
            val isVisible = binding.cardYapeContainer.visibility == android.view.View.VISIBLE
            if (isVisible) {
                binding.cardYapeContainer.visibility = android.view.View.GONE
                binding.btnToggleYape.text = "💖 DONAR CON YAPE"
            } else {
                binding.cardYapeContainer.visibility = android.view.View.VISIBLE
                binding.btnToggleYape.text = "🔼 OCULTAR OPCIONES"
            }
        }

        binding.btnYapeDirect.setOnClickListener {
            yapearDirecto()
        }

        binding.btnDownloadQr.setOnClickListener {
            downloadQr()
        }

        binding.btnPaypal.setOnClickListener {
            abrirPayPal()
        }
    }

    private fun abrirPayPal() {
        // Enlace de Donación basado en tu correo de PayPal
        val email = "manuelgomez612430@gmail.com"
        val paypalUrl = "https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=$email&item_name=Apoyo+al+desarrollo+de+GoMezMart"
        
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(paypalUrl))
            startActivity(intent)
            android.widget.Toast.makeText(this, "Abriendo PayPal... 💳", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No se pudo abrir el navegador", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadQr() {
        val resId = R.drawable.qr_yape_manuel

        try {
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, resId)
            val fileName = "QR_Yape_GoMezMart_${System.currentTimeMillis()}.jpg"
            
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it).use { outputStream ->
                    if (outputStream != null) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }

                android.widget.Toast.makeText(this, "✅ QR descargado en tu galería", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Error al descargar el QR", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun yapearDirecto() {
        val phone = "974834440" // Manuel Gomez Flores
        
        // 1. Copiar número al portapapeles
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Número Yape", phone)
        clipboard.setPrimaryClip(clip)

        // 2. Intentar abrir Yape
        val intent = packageManager.getLaunchIntentForPackage("com.bcp.innovatcx.yapeapp")
        if (intent != null) {
            startActivity(intent)
            android.widget.Toast.makeText(this, "Número copiado. Pégalo en Yape ✅", android.widget.Toast.LENGTH_LONG).show()
        } else {
            // Si no tiene Yape, abrir Play Store
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.bcp.innovatcx.yapeapp")))
            } catch (e: Exception) {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.bcp.innovatcx.yapeapp")))
            }
            android.widget.Toast.makeText(this, "Instale Yape para continuar", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Eliminamos el método showFutureVisionDialog ya que ahora usamos una nueva Activity
}
