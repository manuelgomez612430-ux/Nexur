package com.naxor.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.CalculationEntity
import com.naxor.app.data.ProductEntity
import com.naxor.app.databinding.ActivityAsignadorDePreciosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.round

class AsignadorDePreciosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAsignadorDePreciosBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    private var costoUnitarioActual = 0.0
    private var unidadesActuales = 1
    private var margenElegidoActual = 0.0
    private var precioRedondeadoActual = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAsignadorDePreciosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCategoriaDropdown()
        setupListeners()
        setupAutoCategoryRecognition()
    }

    private fun setupCategoriaDropdown() {
        val categorias = arrayOf(
            "Abarrotes", "Alimentos", "Belleza y Cosméticos", "Calzado",
            "Electrónica", "Farmacia y Salud", "Ferretería", "Hogar",
            "Juguetería", "Librería y Útiles", "Mascotas", "Regalos y Novedades",
            "Repuestos Automotriz", "Ropa", "Otros"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categorias)
        binding.autoCompleteCategoria.setAdapter(adapter)

        binding.autoCompleteCategoria.setOnItemClickListener { _, _, position, _ ->
            val seleccion = categorias[position]
            aplicarMargenPorCategoria(seleccion)
        }
    }

    private fun aplicarMargenPorCategoria(categoria: String) {
        val margenSugerido = when (categoria) {
            "Abarrotes", "Alimentos" -> "15"
            "Farmacia y Salud" -> "25"
            "Electrónica", "Ferretería", "Mascotas" -> "30"
            "Hogar", "Librería y Útiles", "Repuestos Automotriz" -> "35"
            "Juguetería", "Ropa" -> "40"
            "Belleza y Cosméticos" -> "45"
            "Calzado", "Regalos y Novedades" -> "50"
            else -> "30"
        }
        binding.etMargenDeseado.setText(margenSugerido)
        
        binding.tvHelperMargen.text = "Se aplicó un $margenSugerido% de margen para $categoria. Puedes modificarlo."
        binding.tvHelperMargen.setTextColor(resources.getColor(R.color.emerald_600, theme))
    }

    private fun setupAutoCategoryRecognition() {
        val mapping = mapOf(
            "Abarrotes" to listOf("arroz", "azucar", "leche", "aceit", "fideo", "huevo", "agua", "botell", "gaseos", "gallet", "pan", "cafe", "yogur", "papa", "ceboll", "tomate", "fruta", "carne", "pollo", "pescado", "atun", "conserva"),
            "Calzado" to listOf("zapato", "sandali", "zapatill", "bota", "taco", "ojota", "chancl"),
            "Ropa" to listOf("camis", "polo", "pantalon", "casaca", "vestid", "falda", "blus", "media", "ropa", "jean", "short", "poler", "chompa", "abrigo"),
            "Electrónica" to listOf("celular", "laptop", "tv", "televisor", "tablet", "audifono", "cargador", "cable", "mouse", "teclado", "parlante", "monitor"),
            "Ferretería" to listOf("martillo", "clavo", "cemento", "pintur", "taladro", "perno", "fierro", "herramient", "wincha", "lija", "tubo"),
            "Mascotas" to listOf("perro", "gato", "comida", "croquet", "collar", "mascot"),
            "Belleza y Cosméticos" to listOf("perfume", "crema", "labial", "maquillaje", "shampoo", "jabon", "bellez", "desodorante"),
            "Librería y Útiles" to listOf("cuaderno", "lapicero", "lapiz", "regla", "mochila", "utiles", "plumon", "borrador"),
            "Juguetería" to listOf("juguete", "muñec", "carro", "peluche", "lego", "pelota"),
            "Farmacia y Salud" to listOf("medicin", "pastill", "jarabe", "alcohol", "gasa", "venda", "farmaci", "vitamina"),
            "Hogar" to listOf("mesa", "silla", "cama", "saban", "toalla", "olla", "plato", "hogar", "vaso", "taza")
        )

        binding.etNombreProducto.addTextChangedListener { text ->
            val input = text.toString().lowercase()
            if (input.length >= 3) {
                for ((categoria, keywords) in mapping) {
                    if (keywords.any { input.contains(it) }) {
                        binding.autoCompleteCategoria.setText(categoria, false)
                        aplicarMargenPorCategoria(categoria)
                        break
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.cbIncluirTransporte.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutCostoTransporte.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnCalcularPrecio.setOnClickListener {
            if (validarCampos()) {
                hideKeyboard()
                ejecutarCalculos()
            }
        }

        binding.btnNuevoCalculo.setOnClickListener {
            resetFields()
        }

        binding.btnRecalcular.setOnClickListener {
            if (validarCampos()) {
                hideKeyboard()
                ejecutarCalculos()
                Toast.makeText(this, "Resultados actualizados", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGuardarInventario.setOnClickListener {
            saveToInventory()
        }

        binding.toggleGroupEscenario.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                actualizarEscenario(checkedId)
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun validarCampos(): Boolean {
        if (binding.etNombreProducto.text.isNullOrBlank() ||
            binding.etCostoLote.text.isNullOrBlank() ||
            binding.etCantidadUnidades.text.isNullOrBlank() ||
            binding.etMargenDeseado.text.isNullOrBlank()) {
            Toast.makeText(this, "Por favor, completa todos los campos", Toast.LENGTH_SHORT).show()
            return false
        }
        
        val unidades = binding.etCantidadUnidades.text.toString().toIntOrNull() ?: 0
        if (unidades <= 0) {
            Toast.makeText(this, "La cantidad de unidades debe ser mayor a 0", Toast.LENGTH_SHORT).show()
            return false
        }

        val margen = binding.etMargenDeseado.text.toString().toDoubleOrNull() ?: 0.0
        if (margen <= 0 || margen >= 100) {
            Toast.makeText(this, "El margen debe estar entre 1% y 99%", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun ejecutarCalculos() {
        val costoLote = binding.etCostoLote.text.toString().toDoubleOrNull() ?: 0.0
        unidadesActuales = binding.etCantidadUnidades.text.toString().toIntOrNull() ?: 1
        margenElegidoActual = binding.etMargenDeseado.text.toString().toDoubleOrNull() ?: 0.0
        val costoTransporte = if (binding.cbIncluirTransporte.isChecked) {
            binding.etCostoTransporte.text.toString().toDoubleOrNull() ?: 0.0
        } else 0.0

        val inversionTotal = costoLote + costoTransporte
        costoUnitarioActual = inversionTotal / unidadesActuales
        
        val precioExacto = costoUnitarioActual / (1 - (margenElegidoActual / 100))
        precioRedondeadoActual = round(precioExacto * 10) / 10.0

        // Guardar automáticamente en historial
        saveToHistory(
            nombre = binding.etNombreProducto.text.toString(),
            categoria = binding.autoCompleteCategoria.text.toString(),
            costoLote = costoLote,
            unidades = unidadesActuales,
            margen = margenElegidoActual,
            transporte = costoTransporte,
            sugerido = precioRedondeadoActual
        )

        // Mostrar datos en resumen
        binding.rowProducto.tvLabel.text = "Producto"
        binding.rowProducto.tvValue.text = binding.etNombreProducto.text.toString()
        binding.rowCostoBase.tvLabel.text = "Costo lote"
        binding.rowCostoBase.tvValue.text = String.format(Locale.getDefault(), "S/ %.2f", costoLote)
        binding.rowTransporte.tvLabel.text = "Transporte"
        binding.rowTransporte.tvValue.text = String.format(Locale.getDefault(), "S/ %.2f", costoTransporte)
        binding.rowTotalInversion.tvLabel.text = "Inversión Total"
        binding.rowTotalInversion.tvValue.text = String.format(Locale.getDefault(), "S/ %.2f", inversionTotal)

        // Por defecto, mostrar el escenario sugerido
        binding.toggleGroupEscenario.check(R.id.btnEscenarioSugerido)
        actualizarEscenario(R.id.btnEscenarioSugerido)

        binding.cardResultados.visibility = View.VISIBLE
        binding.btnCalcularPrecio.visibility = View.GONE
        binding.layoutBotonesPostCalculo.visibility = View.VISIBLE
    }

    private fun saveToHistory(
        nombre: String, categoria: String, costoLote: Double,
        unidades: Int, margen: Double, transporte: Double, sugerido: Double
    ) {
        try {
            val entity = CalculationEntity(
                nombre, if (categoria.isBlank()) "Otros" else categoria,
                costoLote, unidades, margen, transporte, sugerido
            )
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    database.calculationDao().insert(entity)
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveToInventory() {
        val nombre = binding.etNombreProducto.text.toString().trim()
        val categoria = binding.autoCompleteCategoria.text.toString()
        val costoLote = binding.etCostoLote.text.toString().toDoubleOrNull() ?: 0.0
        val transporte = if (binding.cbIncluirTransporte.isChecked) {
            binding.etCostoTransporte.text.toString().toDoubleOrNull() ?: 0.0
        } else 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verificar si ya existe un producto con el mismo nombre
                val existing = database.productDao().getProductByName(nombre)
                
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AsignadorDePreciosActivity, "El producto '$nombre' ya existe en el inventario", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val product = ProductEntity(
                        null, // Código opcional
                        nombre,
                        if (categoria.isBlank()) "Otros" else categoria,
                        unidadesActuales,
                        costoLote + transporte,
                        precioRedondeadoActual
                    )
                    database.productDao().insert(product)
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AsignadorDePreciosActivity, "¡Producto añadido al Inventario!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AsignadorDePreciosActivity, "Error al guardar en inventario", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun actualizarEscenario(checkedId: Int) {
        val margenFinal = when (checkedId) {
            R.id.btnEscenarioMinimo -> (margenElegidoActual - 5.0).coerceAtLeast(1.0)
            R.id.btnEscenarioMaximo -> (margenElegidoActual + 5.0).coerceAtMost(99.0)
            else -> margenElegidoActual
        }

        val precioExacto = costoUnitarioActual / (1 - (margenFinal / 100))
        precioRedondeadoActual = round(precioExacto * 10) / 10.0
        
        val gananciaU = precioRedondeadoActual - costoUnitarioActual
        val gananciaT = gananciaU * unidadesActuales

        binding.tvResultadoPrecioSugerido.text = String.format(Locale.getDefault(), "S/ %.2f", precioRedondeadoActual)
        binding.tvPrecioExacto.text = String.format(Locale.getDefault(), "Matemáticamente exacto: S/ %.2f", precioExacto)
        binding.tvMargenGanancia.text = String.format(Locale.getDefault(), "Margen de este escenario: %.0f%%", margenFinal)
        
        binding.rowGananciaUnidad.tvLabel.text = "Ganancia x unidad"
        binding.rowGananciaUnidad.tvValue.text = String.format(Locale.getDefault(), "S/ %.2f", gananciaU)
        binding.rowGananciaUnidad.tvValue.setTextColor(resources.getColor(R.color.emerald_600, theme))

        binding.rowGananciaTotal.tvLabel.text = "Ganancia total lote"
        binding.rowGananciaTotal.tvValue.text = String.format(Locale.getDefault(), "S/ %.2f", gananciaT)
        binding.rowGananciaTotal.tvValue.setTextColor(resources.getColor(R.color.emerald_600, theme))
    }

    private fun ocultarResultados() {
        binding.cardResultados.visibility = View.GONE
        binding.btnCalcularPrecio.visibility = View.VISIBLE
        binding.layoutBotonesPostCalculo.visibility = View.GONE
    }

    private fun resetFields() {
        binding.etNombreProducto.text?.clear()
        binding.etCostoLote.text?.clear()
        binding.etCantidadUnidades.text?.clear()
        binding.etMargenDeseado.text?.clear()
        binding.etCostoTransporte.text?.clear()
        binding.cbIncluirTransporte.isChecked = false
        binding.autoCompleteCategoria.setText("", false)
        binding.tvHelperMargen.text = "El margen de ganancia se asigna según la categoría."
        binding.tvHelperMargen.setTextColor(resources.getColor(R.color.slate_500, theme))
        ocultarResultados()
    }
}