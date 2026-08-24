package com.naxor.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.HotelToolAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelToolEntity
import com.naxor.app.databinding.ActivityHotelToolsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HotelToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelToolsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: HotelToolAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTools.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        loadData()
        
        binding.fabAddTool.setOnClickListener { showAddToolDialog() }

        checkIntroTutorial()
    }

    private fun checkIntroTutorial() {
        val prefs = getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("intro_shown", false)) {
            showIntroTutorial()
        }
    }

    private fun showIntroTutorial() {
        AlertDialog.Builder(this)
            .setTitle("🚀 Guía de Inventario")
            .setMessage("¡Bienvenido al Control de Activos! 🛠️\n\n" +
                    "IMPORTANCIA:\nEsta herramienta te permite asegurar que tus sábanas, toallas y herramientas estén siempre en óptimo estado para tus clientes. Evita usar insumos desgastados que dañen la reputación de tu hotel.\n\n" +
                    "¿CÓMO EMPEZAR?\n1. Presiona el botón '+' abajo a la derecha.\n" +
                    "2. Asigna un código físico a cada objeto (ej: una etiqueta en la sábana con 'S-101').\n" +
                    "3. Ingresa el nombre y cuánto tiempo deseas que dure antes de cambiarlo.\n\n" +
                    "¡Naxor te avisará cuando sea momento de renovarlos!")
            .setPositiveButton("Entendido") { _, _ ->
                getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE).edit { putBoolean("intro_shown", true) }
            }
            .setCancelable(false)
            .show()
    }

    private fun checkCardTutorial() {
        val prefs = getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("card_tutorial_shown", false)) {
            showCardTutorial()
        }
    }

    private fun showCardTutorial() {
        AlertDialog.Builder(this)
            .setTitle("💡 ¿Cómo gestionar tus herramientas?")
            .setMessage("Ahora que tienes tu primer registro, así es como funciona:\n\n" +
                    "⏱️ TIEMPO DE USO: Cuenta los días desde que registraste el objeto.\n\n" +
                    "⏳ VIDA ÚTIL: Te indica cuántos meses le quedan según tu configuración. Si sale 'VENCIDO' en rojo, ¡es hora de un cambio!\n\n" +
                    "🔄 RENOVAR: Úsalo cuando compres un repuesto nuevo (ej: sábana nueva) para el mismo código. El tiempo volverá a cero.\n\n" +
                    "🚫 DAR DE BAJA: Úsalo si la herramienta se perdió o ya no se usará más.")
            .setPositiveButton("¡Excelente!") { _, _ ->
                getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE).edit { putBoolean("card_tutorial_shown", true) }
            }
            .show()
    }

    private fun setupRecyclerView() {
        adapter = HotelToolAdapter(
            onRetire = { tool -> showRetireConfirmation(tool) },
            onRenew = { tool -> showRenewConfirmation(tool) }
        )
        binding.rvTools.layoutManager = LinearLayoutManager(this)
        binding.rvTools.adapter = adapter
    }

    private fun showRenewConfirmation(tool: HotelToolEntity) {
        val title = if (tool.status == "RETIRED") "Reactivar herramienta" else "Renovar herramienta"
        val message = if (tool.status == "RETIRED") 
            "¿Deseas reactivar la herramienta #${tool.code}? Su tiempo de uso comenzará a contar desde hoy."
            else "¿Has reemplazado esta herramienta por una nueva? El contador de tiempo de uso se reiniciará a cero."

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirmar") { _, _ ->
                lifecycleScope.launch {
                    val updatedTool = tool.copy(
                        registrationDate = System.currentTimeMillis(),
                        status = "ACTIVE"
                    )
                    database.hotelToolDao().updateTool(updatedTool)
                    Toast.makeText(this@HotelToolsActivity, "Herramienta renovada correctamente", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadData() {
        lifecycleScope.launch {
            database.hotelToolDao().getAllTools().collectLatest { tools ->
                adapter.submitList(tools)
            }
        }
    }

    private fun showAddToolDialog() {
        val builder = AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        // Guía informativa
        val tvGuide = TextView(this).apply {
            text = "ℹ️ IMPORTANTE: Cada herramienta debe tener un código único para su control individual."
            textSize = 12f
            setTextColor(getColor(R.color.purple_600))
            setPadding(0, 0, 0, 20)
        }

        val etCode = EditText(this).apply { hint = "Código único (Eje: SAB-001)" }
        val etName = EditText(this).apply { hint = "Nombre (Eje: Sábana Matrimonial)" }
        val etMonths = EditText(this).apply { 
            hint = "Vida útil en meses (0 = ilimitada)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(tvGuide)
        layout.addView(etCode)
        layout.addView(etName)
        layout.addView(etMonths)

        builder.setTitle("Registrar Herramienta")
            .setView(layout)
            .setPositiveButton("Registrar") { _, _ ->
                val code = etCode.text.toString().trim()
                val name = etName.text.toString().trim()
                val months = etMonths.text.toString().toIntOrNull() ?: 0

                if (code.isEmpty() || name.isEmpty()) {
                    Toast.makeText(this, "El código y nombre son obligatorios", Toast.LENGTH_SHORT).show()
                } else {
                    saveTool(code, name, months)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveTool(code: String, name: String, months: Int) {
        lifecycleScope.launch {
            // Verificar si el código ya existe
            val existing = database.hotelToolDao().getToolByCode(code)
            if (existing != null) {
                Toast.makeText(this@HotelToolsActivity, "El código #$code ya está en uso", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val tool = HotelToolEntity(
                code = code,
                name = name,
                maxUsageMonths = months
            )
            database.hotelToolDao().insertTool(tool)
            Toast.makeText(this@HotelToolsActivity, "Herramienta registrada con éxito", Toast.LENGTH_SHORT).show()
            
            // Mostrar tutorial de tarjeta después del primer registro exitoso
            checkCardTutorial()
        }
    }

    private fun showRetireConfirmation(tool: HotelToolEntity) {
        AlertDialog.Builder(this)
            .setTitle("¿Dar de baja?")
            .setMessage("¿Confirmas que la herramienta #${tool.code} será retirada o cambiada?")
            .setPositiveButton("Sí, Retirar") { _, _ ->
                lifecycleScope.launch {
                    database.hotelToolDao().updateTool(tool.copy(status = "RETIRED"))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
