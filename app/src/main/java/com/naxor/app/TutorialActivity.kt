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
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.naxor.app.databinding.ActivityTutorialBinding
import com.naxor.app.databinding.ItemTutorialSlideBinding

data class TutorialSlide(
    val title: String,
    val description: String,
    val imageRes: Int
)

class TutorialActivity : BaseActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private var slides = listOf<TutorialSlide>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Soporte para Edge-to-Edge (Estabilizado)
        enableEdgeToEdge()
        
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Aplicar padding para evitar que los controles se oculten tras las barras del sistema
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeSlides()
        setupViewPager()
        setupListeners()
        setupDots(0)
    }

    private fun initializeSlides() {
        val prefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val businessType = prefs.getString("business_type", "PRODUCTS")

        slides = if (businessType == "LOANS") {
            listOf(
                TutorialSlide(
                    "¡Bienvenido a GoMezMart Préstamos! 💰",
                    "Tu aliado inteligente para la gestión de créditos y cobranzas. Mantén tus cuentas claras y seguras.",
                    R.drawable.gomezmart_logo
                ),
                TutorialSlide(
                    "👤 Cartera de Clientes",
                    "Organiza a tus clientes, guarda sus datos de contacto y fotos de garantía para mayor seguridad.",
                    android.R.drawable.ic_menu_my_calendar
                ),
                TutorialSlide(
                    "💵 Préstamos Rápidos",
                    "Registra nuevos créditos en segundos. Calcula cuotas e intereses automáticamente con nuestra calculadora integrada.",
                    android.R.drawable.ic_menu_add
                ),
                TutorialSlide(
                    "📊 Control de Cobros",
                    "Visualiza cuánto has prestado, cuánto has recuperado y tu utilidad neta en tiempo real.",
                    android.R.drawable.ic_menu_compass
                ),
                TutorialSlide(
                    "🔒 Respaldo en la Nube",
                    "Nunca pierdas tus cuentas. Sincronizamos toda tu información con tu cuenta de Google automáticamente.",
                    android.R.drawable.ic_lock_lock
                )
            )
        } else {
            listOf(
                TutorialSlide(
                    "¡Bienvenido a GoMezMart! 🚀",
                    "Tu sistema inteligente de gestión comercial. Aquí podrás controlar tu inventario, ventas y rendimiento en tiempo real.",
                    R.drawable.gomezmart_logo
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
        }
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
            if (item.imageRes == R.drawable.gomezmart_logo) {
                holder.b.ivTutorialImage.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                holder.b.ivTutorialImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.b.ivTutorialImage.alpha = 0.5f
            }
        }
        override fun getItemCount() = items.size
    }
}
