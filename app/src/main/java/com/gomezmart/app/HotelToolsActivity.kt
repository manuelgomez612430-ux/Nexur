package com.gomezmart.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputLayout
import com.gomezmart.app.adapter.HotelToolAdapter
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelToolEntity
import com.gomezmart.app.databinding.ActivityHotelToolsBinding
import com.gomezmart.app.databinding.DialogAddHotelToolBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelToolsActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelToolsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: HotelToolAdapter
    private var deleteMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadData()
        
        binding.fabAddTool.setOnClickListener { showAddToolDialog() }

        checkIntroTutorial()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarTools)
        binding.toolbarTools.setNavigationOnClickListener { 
            if (deleteMenuItem?.isVisible == true) {
                adapter.setSelectionMode(false)
                deleteMenuItem?.isVisible = false
            } else {
                finish() 
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_hotel_tools, menu)
        deleteMenuItem = menu?.findItem(R.id.action_delete_tools)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_delete_tools) {
            showBulkDeleteConfirmation()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showBulkDeleteConfirmation() {
        val selected = adapter.getSelectedTools()
        if (selected.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("¿Eliminar seleccionados?")
            .setMessage("¿Estás seguro de que deseas eliminar ${selected.size} activos del inventario?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    selected.forEach { tool ->
                        val updated = tool.copy(isDeleted = true)
                        database.hotelToolDao().updateTool(updated)
                        SyncManager(this@HotelToolsActivity).syncHotelToolToCloud(updated)
                    }
                    adapter.setSelectionMode(false)
                    deleteMenuItem?.isVisible = false
                    Toast.makeText(this@HotelToolsActivity, "Activos eliminados", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
                    "IMPORTANCIA:\nEsta herramienta te permite asegurar que tus sábanas, toallas y herramientas estén siempre en óptimo estado.\n\n" +
                    "¿CÓMO EMPEZAR?\n1. Presiona el botón '+' abajo a la derecha.\n" +
                    "2. Asigna un código físico (ej: 'S-101') y su vida útil.\n" +
                    "GoMezMart te avisará 7, 5, 2 y 1 día antes de que venzan.")
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
            .setTitle("💡 Gestión de Activos")
            .setMessage("⏱️ TIEMPO DE USO: Tiempo transcurrido.\n\n" +
                    "⏳ VIDA ÚTIL: Tiempo restante exacto.\n\n" +
                    "🔄 RENOVAR: Reinicia el contador al comprar uno nuevo.")
            .setPositiveButton("¡Excelente!") { _, _ ->
                getSharedPreferences("HotelToolsPrefs", MODE_PRIVATE).edit { putBoolean("card_tutorial_shown", true) }
            }
            .show()
    }

    private fun setupRecyclerView() {
        adapter = HotelToolAdapter(
            onRetire = { tool -> showRetireConfirmation(tool) },
            onRenew = { tool -> showRenewConfirmation(tool) },
            onSelectionChanged = { count ->
                deleteMenuItem?.isVisible = count > 0
            }
        )
        binding.rvTools.layoutManager = LinearLayoutManager(this)
        binding.rvTools.adapter = adapter
    }

    private fun showRenewConfirmation(tool: HotelToolEntity) {
        val title = if (tool.status == "RETIRED") "Reactivar" else "Renovar"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("¿Confirmas la renovación del activo #${tool.code}? El contador de vida útil se reiniciará.")
            .setPositiveButton("Confirmar") { _, _ ->
                lifecycleScope.launch {
                    val cal = Calendar.getInstance()
                    val now = cal.timeInMillis
                    
                    if (tool.maxUsageDays > 0) {
                        cal.add(Calendar.DAY_OF_YEAR, tool.maxUsageDays)
                    }
                    
                    val updatedTool = tool.copy(
                        registrationDate = now,
                        expiryDate = if (tool.maxUsageDays > 0) cal.timeInMillis else 0L,
                        status = "ACTIVE"
                    )
                    database.hotelToolDao().updateTool(updatedTool)
                    SyncManager(this@HotelToolsActivity).syncHotelToolToCloud(updatedTool)
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
        val db = DialogAddHotelToolBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog).setView(db.root).create()

        var isUpdating = false
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        val updateExpiryText = {
            val totalDays = db.etToolDays.text.toString().toIntOrNull() ?: 0
            
            if (totalDays == 0) {
                db.tvExpiryCalc.text = "Vence el: --/--/---- (Vida útil: Ilimitada)"
            } else {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, totalDays)
                
                val months = totalDays / 30
                val remDays = totalDays % 30
                val durationStr = when {
                    months > 0 && remDays > 0 -> "$months meses y $remDays días"
                    months > 0 -> "$months meses"
                    else -> "$totalDays días"
                }
                
                db.tvExpiryCalc.text = "Vence el: ${sdf.format(cal.time)} (Vida útil: $durationStr)"
            }
        }

        db.etToolMonths.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                val months = s.toString().toIntOrNull() ?: 0
                if (months > 0) {
                    val cal = Calendar.getInstance()
                    val now = cal.timeInMillis
                    cal.add(Calendar.MONTH, months)
                    val diffDays = ((cal.timeInMillis - now) / (1000 * 60 * 60 * 24)).toInt()
                    db.etToolDays.setText(diffDays.toString())
                } else {
                    db.etToolDays.setText("0")
                }
                updateExpiryText()
                isUpdating = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        db.etToolDays.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                val days = s.toString().toIntOrNull() ?: 0
                if (days >= 28) {
                    val months = days / 30
                    db.etToolMonths.setText(months.toString())
                } else {
                    db.etToolMonths.setText("0")
                }
                updateExpiryText()
                isUpdating = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        db.btnCancelTool.setOnClickListener { dialog.dismiss() }
        db.btnSaveTool.setOnClickListener {
            val code = db.etToolCode.text.toString().trim()
            val name = db.etToolName.text.toString().trim()
            val totalDays = db.etToolDays.text.toString().toIntOrNull() ?: 0

            if (code.isEmpty() || name.isEmpty()) {
                Toast.makeText(this, "Completa los campos obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val existing = database.hotelToolDao().getToolByCode(code)
                if (existing != null) {
                    db.layoutToolCode.error = "Código en uso"
                } else {
                    val cal = Calendar.getInstance()
                    if (totalDays > 0) cal.add(Calendar.DAY_OF_YEAR, totalDays)

                    val tool = HotelToolEntity(
                        code = code,
                        name = name,
                        maxUsageDays = totalDays,
                        expiryDate = if (totalDays > 0) cal.timeInMillis else 0L
                    )
                    database.hotelToolDao().insertTool(tool)
                    SyncManager(this@HotelToolsActivity).syncHotelToolToCloud(tool)
                    dialog.dismiss()
                    checkCardTutorial()
                }
            }
        }
        
        dialog.show()
        
        // --- HACER LA VENTANA MÁS ANCHA ---
        val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showRetireConfirmation(tool: HotelToolEntity) {
        AlertDialog.Builder(this)
            .setTitle("Dar de baja")
            .setMessage("¿Confirmas que el activo #${tool.code} será retirado?")
            .setPositiveButton("Sí, Retirar") { _, _ ->
                lifecycleScope.launch {
                    val updatedTool = tool.copy(status = "RETIRED")
                    database.hotelToolDao().updateTool(updatedTool)
                    SyncManager(this@HotelToolsActivity).syncHotelToolToCloud(updatedTool)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
