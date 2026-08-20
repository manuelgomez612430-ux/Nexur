package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.naxor.app.databinding.ActivityTutorialBinding
import com.naxor.app.databinding.ItemTutorialSlideBinding

data class TutorialSlide(
    val title: String,
    val description: String,
    val imageRes: Int
)

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private val slides = listOf(
        TutorialSlide(
            "¡Bienvenido a Naxor! 🚀",
            "Tu sistema inteligente de gestión comercial. Aquí podrás controlar tu inventario, ventas y rendimiento en tiempo real.",
            R.drawable.logo_naxor_full
        ),
        TutorialSlide(
            "📦 Gestión de Inventario",
            "Registra tus productos, controla el stock y visualiza tu inversión total con solo un vistazo.",
            android.R.drawable.ic_menu_save
        ),
        TutorialSlide(
            "💸 Ventas Rápidas",
            "Realiza ventas usando el escáner de códigos de barras o búsqueda inteligente. ¡Emite comprobantes al instante!",
            android.R.drawable.ic_menu_add
        ),
        TutorialSlide(
            "📈 Análisis de Rendimiento",
            "Mide el crecimiento de tu negocio con gráficos detallados de utilidad, gastos y ticket promedio.",
            android.R.drawable.ic_menu_compass
        ),
        TutorialSlide(
            "🔒 Sincronización Segura",
            "Tus datos están protegidos en la nube. Accede desde cualquier dispositivo con tu cuenta de Google.",
            android.R.drawable.ic_lock_lock
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupListeners()
        setupDots(0)
    }

    private fun setupViewPager() {
        binding.viewPagerTutorial.adapter = TutorialAdapter(slides)
        binding.viewPagerTutorial.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                setupDots(position)
                if (position == slides.size - 1) {
                    binding.btnNextTutorial.text = "Comenzar"
                } else {
                    binding.btnNextTutorial.text = "Siguiente"
                }
            }
        })
    }

    private fun setupListeners() {
        binding.btnSkipTutorial.setOnClickListener { finishTutorial() }
        binding.btnNextTutorial.setOnClickListener {
            if (binding.viewPagerTutorial.currentItem < slides.size - 1) {
                binding.viewPagerTutorial.currentItem += 1
            } else {
                finishTutorial()
            }
        }
    }

    private fun setupDots(position: Int) {
        binding.layoutDots.removeAllViews()
        for (i in slides.indices) {
            val dot = View(this)
            val params = LinearLayout.LayoutParams(24, 24)
            params.setMargins(8, 0, 8, 0)
            dot.layoutParams = params
            dot.background = ContextCompat.getDrawable(this, if (i == position) R.drawable.badge_dot else android.R.drawable.btn_radio)
            binding.layoutDots.addView(dot)
        }
    }

    private fun finishTutorial() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tutorial_shown", true).apply()
        finish()
    }

    inner class TutorialAdapter(private val items: List<TutorialSlide>) : RecyclerView.Adapter<TutorialAdapter.ViewHolder>() {
        inner class ViewHolder(val b: ItemTutorialSlideBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemTutorialSlideBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.b.tvTutorialTitle.text = item.title
            holder.b.tvTutorialDesc.text = item.description
            holder.b.ivTutorialImage.setImageResource(item.imageRes)
            if (item.imageRes == R.drawable.logo_naxor_full) {
                holder.b.ivTutorialImage.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                holder.b.ivTutorialImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.b.ivTutorialImage.alpha = 0.5f
            }
        }
        override fun getItemCount() = items.size
    }
}
