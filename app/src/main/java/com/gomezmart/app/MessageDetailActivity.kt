package com.gomezmart.app

import android.os.Bundle
import com.gomezmart.app.databinding.ActivityMessageDetailBinding
import java.text.SimpleDateFormat
import java.util.*

class MessageDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityMessageDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets
        applyWindowInsetsMargins(binding.btnBackDetail, applyTop = true, applyBottom = false)
        applyWindowInsetsHeight(binding.headerBackground, applyTop = true, applyBottom = false)

        val title = intent.getStringExtra("EXTRA_TITLE") ?: "Sin Título"
        val content = intent.getStringExtra("EXTRA_CONTENT")
        val timestamp = intent.getLongExtra("EXTRA_TIME", 0L)

        binding.tvDetailTitle.text = title
        
        // Si el contenido llega nulo o vacÃ­o, mostrar un mensaje de aviso
        if (content.isNullOrBlank()) {
            binding.tvDetailContent.text = "(Este mensaje no contiene un cuerpo de texto adicional)"
            binding.tvDetailContent.alpha = 0.5f
            binding.tvDetailContent.setTypeface(null, android.graphics.Typeface.ITALIC)
        } else {
            binding.tvDetailContent.text = content
            binding.tvDetailContent.alpha = 1.0f
            binding.tvDetailContent.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        
        if (timestamp > 0) {
            val sdf = SimpleDateFormat("dd 'de' MMMM, yyyy - hh:mm a", Locale("es", "PE"))
            binding.tvDetailTime.text = sdf.format(Date(timestamp))
        }

        binding.btnBackDetail.setOnClickListener { finish() }
        binding.btnCloseDetail.setOnClickListener { finish() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_out_down)
    }
}
