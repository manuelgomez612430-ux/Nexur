package com.naxor.app

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    
    private val historyStack = mutableListOf<List<HotelRoomLayoutEntity>>()
    private val MAX_HISTORY = 30

    private var currentFloorId = 1
    private var allLayoutsSnapshot = mutableListOf<HotelRoomLayoutEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelMapEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentFloorId = intent.getIntExtra("FLOOR_ID", 1)
        binding.txtToolbarTitle.text = "Editor - Piso $currentFloorId"
        binding.toolbarEditor.setNavigationOnClickListener { finish() }
        
        setupCollapsiblePanel()
        loadInitialData()
        setupListeners()
        setupZoomButton()
        setupUndoButton()
        setupFloorNavigationButtons()
    }

    private fun setupFloorNavigationButtons() {
        binding.btnPrevFloor.setOnClickListener {
            if (currentFloorId > 1) {
                syncCurrentFloorToSnapshot()
                currentFloorId--
                binding.txtToolbarTitle.text = "Editor - Piso $currentFloorId"
                loadFloor(currentFloorId)
            } else {
                Toast.makeText(this, "Ya estás en el primer piso", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNextFloor.setOnClickListener {
            // 1. Sincronizar cambios actuales antes de subir
            syncCurrentFloorToSnapshot()
            
            val nextFloorId = currentFloorId + 1
            
            // 2. Verificar si el piso siguiente está vacío
            val nextFloorElements = allLayoutsSnapshot.filter { it.floorId == nextFloorId }
            
            if (nextFloorElements.isEmpty()) {
                // Si el piso está vacío, copiamos la estructura (paredes y puertas) del piso actual
                val structuralElements = binding.editorMapView.layoutElements
                    .filter { it.type == "WALL" || it.type == "DOOR" }
                    .map { it.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        floorId = nextFloorId
                    ) }
                
                allLayoutsSnapshot.addAll(structuralElements)
                Toast.makeText(this, "Estructura copiada al Piso $nextFloorId", Toast.LENGTH_SHORT).show()
            }
            
            // 3. Cargar el siguiente piso
            currentFloorId = nextFloorId
            binding.txtToolbarTitle.text = "Editor - Piso $currentFloorId"
            loadFloor(currentFloorId)
        }
    }

    private fun setupCollapsiblePanel() {
        binding.headerTools.setOnClickListener {
            val isExpanded = binding.expandableContent.visibility == android.view.View.VISIBLE
            binding.expandableContent.visibility = if (isExpanded) android.view.View.GONE else android.view.View.VISIBLE
            binding.imgExpandIcon.rotation = if (isExpanded) 0f else 180f
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            allRooms = database.hotelDao().getAllRooms().first()
            val layouts = database.hotelDao().getAllLayouts().first()
            allLayoutsSnapshot = layouts.toMutableList()
            
            binding.editorMapView.roomNames = allRooms.associate { it.id to it.number }
            loadFloor(currentFloorId)
            binding.editorMapView.isEditMode = true
        }
    }

    private fun loadFloor(floorId: Int) {
        currentFloorId = floorId
        binding.editorMapView.layoutElements = allLayoutsSnapshot.filter { it.floorId == floorId }.toMutableList()
        binding.editorMapView.invalidate()
        historyStack.clear() 
    }

    private fun syncCurrentFloorToSnapshot() {
        allLayoutsSnapshot.removeAll { it.floorId == currentFloorId }
        val currentElements = binding.editorMapView.layoutElements.map { it.copy(floorId = currentFloorId) }
        allLayoutsSnapshot.addAll(currentElements)
    }

    private fun setupUndoButton() {
        binding.editorMapView.onHistorySaveRequested = { saveStateToHistory() }
        binding.btnUndo.setOnClickListener {
            if (historyStack.isNotEmpty()) {
                val lastState = historyStack.removeAt(historyStack.size - 1)
                binding.editorMapView.layoutElements = lastState.map { it.copy() }.toMutableList()
                binding.editorMapView.invalidate()
            }
        }
    }

    private fun saveStateToHistory() {
        val snapshot = binding.editorMapView.layoutElements.map { it.copy() }
        historyStack.add(snapshot)
        if (historyStack.size > MAX_HISTORY) historyStack.removeAt(0)
    }

    private fun setupZoomButton() {
        binding.btnResetZoom.setOnClickListener {
            val isExpanded = binding.layoutZoomActions.visibility == android.view.View.VISIBLE
            if (isExpanded) {
                binding.editorMapView.setZoom(1.0f)
                binding.layoutZoomActions.visibility = android.view.View.GONE
            } else {
                binding.layoutZoomActions.visibility = android.view.View.VISIBLE
            }
        }
        binding.btnZoomIn.setOnClickListener { binding.editorMapView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.editorMapView.zoomOut() }
    }

    private fun setupListeners() {
        binding.editorMapView.onDuplicateRequested = { element ->
            handleDuplicate(element)
        }

        binding.btnDuplicate.setOnClickListener {
            binding.editorMapView.selectedElement?.let { handleDuplicate(it) }
        }

        binding.editorMapView.onElementSelected = { element ->
            if (element != null) {
                binding.layoutElementControls.visibility = android.view.View.VISIBLE
                binding.divider.visibility = android.view.View.VISIBLE
                val isWall = element.type == "WALL"; val isDoor = element.type == "DOOR"
                
                binding.btnAddWall.visibility = if (isDoor) android.view.View.GONE else android.view.View.VISIBLE
                binding.btnAddRoom.visibility = if (isDoor) android.view.View.GONE else android.view.View.VISIBLE
                binding.btnFlipSide.visibility = if (isDoor) android.view.View.VISIBLE else android.view.View.GONE
                binding.btnFlipOpening.visibility = if (isDoor) android.view.View.VISIBLE else android.view.View.GONE
                
                binding.sliderThickness.visibility = if (isWall) android.view.View.VISIBLE else android.view.View.GONE
                binding.btnToggleHollow.visibility = if (isWall) android.view.View.VISIBLE else android.view.View.GONE
                
                if (isWall) {
                    binding.sliderThickness.value = element.strokeWidth.coerceIn(2f, 40f)
                    binding.btnToggleHollow.text = if (element.isHollow) "Poner Relleno" else "Quitar Relleno"
                }
                val isSmall = element.width < 80f || element.height < 80f
                binding.btnMoveAssist.visibility = if (isSmall) android.view.View.VISIBLE else android.view.View.GONE
                
                // Expandir panel automáticamente al seleccionar si está cerrado
                if (binding.expandableContent.visibility == android.view.View.GONE) {
                    binding.expandableContent.visibility = android.view.View.VISIBLE
                    binding.imgExpandIcon.rotation = 180f
                }
            } else {
                binding.layoutElementControls.visibility = android.view.View.GONE
                binding.divider.visibility = android.view.View.GONE
                binding.btnAddWall.visibility = android.view.View.VISIBLE
                binding.btnAddRoom.visibility = android.view.View.VISIBLE
                binding.btnFlipSide.visibility = android.view.View.GONE
                binding.btnFlipOpening.visibility = android.view.View.GONE
                
                // Minimizar panel automáticamente al deseleccionar
                binding.expandableContent.visibility = android.view.View.GONE
                binding.imgExpandIcon.rotation = 0f
            }
        }

        binding.btnFlipSide.setOnClickListener {
            binding.editorMapView.selectedElement?.let { if (it.type == "DOOR") { saveStateToHistory(); it.isHollow = !it.isHollow; binding.editorMapView.invalidate() } }
        }
        binding.btnFlipOpening.setOnClickListener {
            binding.editorMapView.selectedElement?.let { if (it.type == "DOOR") { saveStateToHistory(); it.strokeWidth = if (it.strokeWidth > 5f) 2f else 8f; binding.editorMapView.invalidate() } }
        }
        binding.btnMoveAssist.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { binding.editorMapView.isMoveAssistActive = true; v.isPressed = true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { binding.editorMapView.isMoveAssistActive = false; v.isPressed = false }
            }
            true
        }
        binding.sliderThickness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.editorMapView.selectedElement?.let { it.strokeWidth = value; binding.editorMapView.invalidate() }
        }
        binding.btnToggleHollow.setOnClickListener {
            binding.editorMapView.selectedElement?.let { saveStateToHistory(); it.isHollow = !it.isHollow; binding.btnToggleHollow.text = if (it.isHollow) "Poner Relleno" else "Quitar Relleno"; binding.editorMapView.invalidate() }
        }
        binding.btnAddWall.setOnClickListener {
            saveStateToHistory()
            val newWall = HotelRoomLayoutEntity(type = "WALL", floorId = currentFloorId, x = 100f, y = 100f, width = 400f, height = 20f, isHollow = false, strokeWidth = 8f)
            binding.editorMapView.layoutElements.add(newWall); binding.editorMapView.constrainToCanvas(newWall); binding.editorMapView.invalidate()
        }
        binding.btnAddDoor.setOnClickListener {
            saveStateToHistory()
            val newDoor = HotelRoomLayoutEntity(type = "DOOR", floorId = currentFloorId, x = 150f, y = 150f, width = 60f, height = 10f, isHollow = false, strokeWidth = 2f)
            binding.editorMapView.layoutElements.add(newDoor); binding.editorMapView.constrainToCanvas(newDoor); binding.editorMapView.invalidate()
        }
        binding.btnAddRoom.setOnClickListener {
            if (allRooms.isEmpty()) return@setOnClickListener
            val usedRoomIds = allLayoutsSnapshot.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
            val currentUsed = binding.editorMapView.layoutElements.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
            val totalUsed = (usedRoomIds + currentUsed).distinct()
            val availableRooms = allRooms.filter { it.id !in totalUsed }
            
            if (availableRooms.isEmpty()) return@setOnClickListener
            val roomNames = availableRooms.map { it.number }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Vincular Habitación").setItems(roomNames) { _, which ->
                saveStateToHistory()
                val selectedRoom = availableRooms[which]
                val currentSelected = binding.editorMapView.selectedElement
                val baseWidth = if (currentSelected?.type == "ROOM") currentSelected.width else 250f
                val baseHeight = if (currentSelected?.type == "ROOM") currentSelected.height else 250f
                val newElement = HotelRoomLayoutEntity(roomId = selectedRoom.id, type = "ROOM", floorId = currentFloorId, x = 200f, y = 200f, width = baseWidth, height = baseHeight)
                binding.editorMapView.layoutElements.add(newElement); binding.editorMapView.constrainToCanvas(newElement); binding.editorMapView.roomNames = allRooms.associate { it.id to it.number }; binding.editorMapView.selectElement(newElement); binding.editorMapView.invalidate()
            }.show()
        }
        binding.btnDelete.setOnClickListener { binding.editorMapView.deleteSelected() }
        binding.btnRotate.setOnClickListener {
            binding.editorMapView.selectedElement?.let { saveStateToHistory(); it.rotation = (it.rotation + 90f) % 360f; binding.editorMapView.constrainToCanvas(it); binding.editorMapView.invalidate() }
        }
        binding.btnSave.setOnClickListener {
            syncCurrentFloorToSnapshot()
            lifecycleScope.launch {
                try {
                    val currentLayouts = database.hotelDao().getAllLayouts().first()
                    for (layout in currentLayouts) database.hotelDao().deleteLayout(layout)
                    for (element in allLayoutsSnapshot) database.hotelDao().insertLayout(element)
                    Toast.makeText(this@HotelMapEditorActivity, "Piso guardado con éxito", Toast.LENGTH_SHORT).show(); finish()
                } catch (e: Exception) { Toast.makeText(this@HotelMapEditorActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handleDuplicate(element: HotelRoomLayoutEntity) {
        if (element.type == "ROOM") {
            val usedRoomIds = allLayoutsSnapshot.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
            val currentUsed = binding.editorMapView.layoutElements.filter { it.type == "ROOM" }.mapNotNull { it.roomId }
            val totalUsed = (usedRoomIds + currentUsed).distinct()
            val availableRooms = allRooms.filter { it.id !in totalUsed }

            if (availableRooms.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Sin habitaciones disponibles")
                    .setMessage("Todas las habitaciones ya están en el mapa.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val roomNames = availableRooms.map { it.number }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Duplicar como Habitación...")
                    .setItems(roomNames) { _, which ->
                        saveStateToHistory()
                        val selectedRoom = availableRooms[which]
                        val copy = element.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            roomId = selectedRoom.id,
                            x = (element.x + 40f).coerceIn(0f, binding.editorMapView.CANVAS_WIDTH - element.width),
                            y = (element.y + 40f).coerceIn(0f, binding.editorMapView.CANVAS_HEIGHT - element.height)
                        )
                        binding.editorMapView.layoutElements.add(copy)
                        binding.editorMapView.selectElement(copy)
                        binding.editorMapView.invalidate()
                    }.show()
            }
        } else {
            saveStateToHistory()
            val copy = element.copy(
                id = java.util.UUID.randomUUID().toString(),
                x = (element.x + 30f).coerceIn(0f, binding.editorMapView.CANVAS_WIDTH - element.width),
                y = (element.y + 30f).coerceIn(0f, binding.editorMapView.CANVAS_HEIGHT - element.height)
            )
            binding.editorMapView.layoutElements.add(copy)
            binding.editorMapView.selectElement(copy)
            binding.editorMapView.invalidate()
        }
    }
}
