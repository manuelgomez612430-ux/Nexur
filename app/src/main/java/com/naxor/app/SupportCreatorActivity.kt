package com.naxor.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.naxor.app.databinding.ActivitySupportCreatorBinding

class SupportCreatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportCreatorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSupport.setNavigationOnClickListener { finish() }

        // Cargar el QR real si el usuario ya puso el archivo en drawable
        val resId = resources.getIdentifier("qr_yape_manuel", "drawable", packageName)
        if (resId != 0) {
            binding.ivQrYape.setImageResource(resId)
        } else {
            android.widget.Toast.makeText(this, "Por favor, agregue la imagen 'qr_yape_manuel' a la carpeta drawable", android.widget.Toast.LENGTH_LONG).show()
        }

        binding.btnFutureInfo.setOnClickListener {
            startActivity(android.content.Intent(this, FutureVisionActivity::class.java))
        }
    }

    // Eliminamos el método showFutureVisionDialog ya que ahora usamos una nueva Activity
}
