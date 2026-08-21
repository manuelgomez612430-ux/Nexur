package com.naxor.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.data.HotelRoomLayoutEntity
import com.naxor.app.databinding.ActivityHotelMapEditorBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HotelMapEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelMapEditorBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var allRooms: List<HotelRoomEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelMapEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarEditor.setNavigationOnClickListener { finish() }
        
        loadInitialData()
        setupListeners()
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            allRooms = database.hotelDao().getAllRooms().first()
            val layouts = database.hotelDao().getAllLayouts().first()
            
            binding.editorMapView.roomNames = allRooms.associate { it.id to it.number }
            binding.editorMapView.layoutElements = layouts.toMutableList()
            binding.editorMapView.isEditMode = true
            binding.editorMapView.invalidate()
        }
    }

    private fun setupListeners() {
        binding.editorMapView.onDuplicateRequested = { element: HotelRoomLayoutEntity ->
            if (element.type == "WALL") {
                val copy = element.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    x = element.x + 30f,
                    y = element.y + 30f,
                    width = element.width,
                    height = element.height
                )
                binding.editorMapView.layoutElements.add(copy)
                binding.editorMapView.selectElement(copy)
                binding.editorMapView.invalidate()
            } else if (element.type == "ROOM") {
                // Duplicar Habitación: Buscar habitaciones no usadas
                val usedRoomIds = binding.editorMapView.layoutElements.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
                val availableRooms = allRooms.filter { it.id !in usedRoomIds }
                
                if (availableRooms.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Sin habitaciones disponibles")
                        .setMessage("Todas las habitaciones ya están en el Mapa. Debes agregar más habitaciones en la lista.")
                        .setPositiveButton("Entendido", null)
                        .show()
                } else {
                    val roomNames = availableRooms.map { it.number }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Duplicar como Habitación...")
                        .setItems(roomNames) { _, which ->
                            val selectedRoom = availableRooms[which]
                            val copy = element.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                roomId = selectedRoom.id,
                                x = element.x + 40f,
                                y = element.y + 40f,
                                width = element.width,
                                height = element.height,
                                rotation = element.rotation
                            )
                            binding.editorMapView.layoutElements.add(copy)
                            binding.editorMapView.roomNames = allRooms.associate { it.id to it.number }
                            binding.editorMapView.selectElement(copy)
                            binding.editorMapView.invalidate()
                        }.show()
                }
            } else if (element.type == "DOOR") {
                val copy = element.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    x = element.x + 20f,
                    y = element.y + 20f,
                    width = element.width,
                    height = element.height
                )
                binding.editorMapView.layoutElements.add(copy)
                binding.editorMapView.selectElement(copy)
                binding.editorMapView.invalidate()
            }
        }

        binding.editorMapView.onElementSelected = { element: HotelRoomLayoutEntity? ->
            if (element != null) {
                binding.layoutElementControls.visibility = android.view.View.VISIBLE
                binding.divider.visibility = android.view.View.VISIBLE
                
                // Mostrar controles de pared solo si es pared
                val isWall = element.type == "WALL"
                binding.txtThicknessLabel.visibility = if (isWall) android.view.View.VISIBLE else android.view.View.GONE
                binding.sliderThickness.visibility = if (isWall) android.view.View.VISIBLE else android.view.View.GONE
                binding.btnToggleHollow.visibility = if (isWall) android.view.View.VISIBLE else android.view.View.GONE
                
                if (isWall) {
                    val currentStroke = element.strokeWidth
                    val alignedValue = (Math.round(currentStroke / 2.0) * 2.0).toFloat()
                    binding.sliderThickness.value = alignedValue.coerceIn(2f, 40f)
                    binding.btnToggleHollow.text = if (element.isHollow) "Poner Relleno" else "Quitar Relleno"
                }

                // Mostrar botón de asistencia si es muy pequeño o delgado
                val isSmall = element.width < 80f || element.height < 80f
                binding.btnMoveAssist.visibility = if (isSmall) android.view.View.VISIBLE else android.view.View.GONE
            } else {
                binding.layoutElementControls.visibility = android.view.View.GONE
                binding.divider.visibility = android.view.View.GONE
            }
        }

        binding.btnMoveAssist.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    binding.editorMapView.isMoveAssistActive = true
                    v.isPressed = true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.editorMapView.isMoveAssistActive = false
                    v.isPressed = false
                }
            }
            true
        }

        binding.sliderThickness.addOnChangeListener { slider: com.google.android.material.slider.Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                binding.editorMapView.selectedElement?.let { element ->
                    element.strokeWidth = value
                    binding.editorMapView.invalidate()
                }
            }
        }

        binding.btnToggleHollow.setOnClickListener {
            binding.editorMapView.selectedElement?.let { element ->
                element.isHollow = !element.isHollow
                binding.btnToggleHollow.text = if (element.isHollow) "Poner Relleno" else "Quitar Relleno"
                binding.editorMapView.invalidate()
            }
        }

        binding.btnAddWall.setOnClickListener {
            val newWall = HotelRoomLayoutEntity(
                type = "WALL",
                x = 100f, y = 100f, width = 400f, height = 20f,
                isHollow = false, strokeWidth = 8f
            )
            binding.editorMapView.layoutElements.add(newWall)
            binding.editorMapView.invalidate()
        }

        binding.btnAddDoor.setOnClickListener {
            val newDoor = HotelRoomLayoutEntity(
                type = "DOOR",
                x = 150f, y = 150f, width = 60f, height = 10f,
                isHollow = false, strokeWidth = 2f
            )
            binding.editorMapView.layoutElements.add(newDoor)
            binding.editorMapView.invalidate()
        }

        binding.btnAddRoom.setOnClickListener {
            if (allRooms.isEmpty()) {
                Toast.makeText(this, "Primero crea habitaciones en la lista", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Buscar habitaciones no usadas
            val usedRoomIds = binding.editorMapView.layoutElements.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
            val availableRooms = allRooms.filter { it.id !in usedRoomIds }
            
            if (availableRooms.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Sin habitaciones libres")
                    .setMessage("Todas tus habitaciones ya están colocadas en el mapa.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            val roomNames = availableRooms.map { it.number }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Vincular Habitación")
                .setItems(roomNames) { _, which ->
                    val selectedRoom = availableRooms[which]
                    
                    // Si hay una habitación seleccionada, usamos su tamaño como base
                    val currentSelected = binding.editorMapView.selectedElement
                    val baseWidth = if (currentSelected?.type == "ROOM") currentSelected.width else 250f
                    val baseHeight = if (currentSelected?.type == "ROOM") currentSelected.height else 250f
                    
                    val newElement = HotelRoomLayoutEntity(
                        roomId = selectedRoom.id,
                        type = "ROOM",
                        x = 200f, y = 200f, 
                        width = baseWidth, 
                        height = baseHeight
                    )
                    binding.editorMapView.layoutElements.add(newElement)
                    binding.editorMapView.roomNames = allRooms.associate { it.id to it.number }
                    binding.editorMapView.selectElement(newElement)
                    binding.editorMapView.invalidate()
                }.show()
        }

        binding.btnDelete.setOnClickListener {
            binding.editorMapView.deleteSelected()
        }

        binding.btnRotate.setOnClickListener {
            binding.editorMapView.selectedElement?.let { element ->
                element.rotation = (element.rotation + 90f) % 360f
                // Al rotar 90 grados, el elemento podría quedar fuera del límite si está muy cerca de los bordes.
                // Re-aplicamos la restricción.
                element.x = element.x.coerceIn(0f, binding.editorMapView.CANVAS_WIDTH - element.width)
                element.y = element.y.coerceIn(0f, binding.editorMapView.CANVAS_HEIGHT - element.height)
                binding.editorMapView.invalidate()
            }
        }

        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                try {
                    // Borrar los antiguos
                    val currentLayouts = database.hotelDao().getAllLayouts().first()
                    for (layout in currentLayouts) {
                        database.hotelDao().deleteLayout(layout)
                    }
                    
                    // Insertar los nuevos (hacer copia de la lista para evitar concurrencia)
                    val newElements = binding.editorMapView.layoutElements.toList()
                    for (element in newElements) {
                        database.hotelDao().insertLayout(element)
                    }
                    
                    Toast.makeText(this@HotelMapEditorActivity, "Plano guardado con éxito", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@HotelMapEditorActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
