package com.gomezmart.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelRoomEntity
import com.gomezmart.app.data.HotelRoomLayoutEntity
import com.gomezmart.app.databinding.ActivityHotelMapEditorBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class HotelMapEditorActivity : BaseActivity() {

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
        binding.editorMapView.onElementModified = { modified ->
            if (modified.type == "WALL" && modified.baseId != null) {
                // Sincronizar cambios a otros pisos en el snapshot
                val baseId = modified.baseId
                allLayoutsSnapshot.filter { it.baseId == baseId && it.id != modified.id }.forEach { other ->
                    other.x = modified.x
                    other.y = modified.y
                    other.width = modified.width
                    other.height = modified.height
                    other.rotation = modified.rotation
                    other.strokeWidth = modified.strokeWidth
                    other.isHollow = modified.isHollow
                }
            }
        }

        binding.editorMapView.onDuplicateRequested = { element ->
            handleDuplicate(element)
        }

        binding.btnDuplicate.setOnClickListener {
            binding.editorMapView.selectedElement?.let { handleDuplicate(it) }
        }

        binding.btnDuplicateSelection.setOnClickListener {
            binding.editorMapView.selectedElement?.let { handleDuplicate(it) }
        }

        binding.btnToggleBaseWall.setOnClickListener {
            val element = binding.editorMapView.selectedElement ?: return@setOnClickListener
            if (element.type == "WALL") {
                saveStateToHistory()
                if (element.baseId == null) {
                    // Convertir en Pared Base
                    val newBaseId = UUID.randomUUID().toString()
                    element.baseId = newBaseId
                    binding.btnToggleBaseWall.text = "Pared Base: ACTIVADO"
                    Toast.makeText(this, "Esta pared ahora se sincronizará en todos los pisos", Toast.LENGTH_SHORT).show()
                } else {
                    // Quitar Pared Base
                    element.baseId = null
                    binding.btnToggleBaseWall.text = "Sincronizar entre pisos"
                    Toast.makeText(this, "Esta pared ahora es independiente", Toast.LENGTH_SHORT).show()
                }
                binding.editorMapView.invalidate()
            }
        }

        binding.editorMapView.onElementSelected = { element ->
            val titleParams = binding.txtToolsTitle.layoutParams as RelativeLayout.LayoutParams
            
            if (element != null) {
                // MODO SELECCIÓN: Ocultar herramientas generales, mostrar controles específicos
                binding.gridMainTools.visibility = View.GONE
                binding.layoutElementControls.visibility = View.VISIBLE
                binding.btnHeaderDelete.visibility = View.VISIBLE
                
                // Mover título a la izquierda
                titleParams.removeRule(RelativeLayout.CENTER_IN_PARENT)
                titleParams.addRule(RelativeLayout.ALIGN_PARENT_START)
                titleParams.addRule(RelativeLayout.CENTER_VERTICAL)
                binding.txtToolsTitle.layoutParams = titleParams

                val isWall = element.type == "WALL"; val isDoor = element.type == "DOOR"; val isRoom = element.type == "ROOM"
                
                binding.btnFlipSide.visibility = if (isDoor) View.VISIBLE else View.GONE
                binding.btnFlipOpening.visibility = if (isDoor) View.VISIBLE else View.GONE
                
                binding.layoutThickness.visibility = if (isWall) View.VISIBLE else View.GONE
                binding.btnToggleBaseWall.visibility = if (isWall) View.VISIBLE else View.GONE
                binding.btnDuplicateSelection.visibility = if (isRoom) View.VISIBLE else View.GONE
                
                if (isWall) {
                    binding.sliderThickness.value = element.strokeWidth.coerceIn(2f, 40f)
                    binding.btnToggleHollow.text = if (element.isHollow) "Sin Relleno" else "Con Relleno"
                    binding.btnToggleBaseWall.text = if (element.baseId != null) "Pared Base: ACTIVADO" else "Sincronizar entre pisos"
                }
                
                if (binding.expandableContent.visibility == View.GONE) {
                    binding.expandableContent.visibility = View.VISIBLE
                    binding.imgExpandIcon.rotation = 180f
                }
            } else {
                // MODO NORMAL: Mostrar herramientas generales, ocultar controles específicos
                binding.gridMainTools.visibility = View.VISIBLE
                binding.layoutElementControls.visibility = View.GONE
                binding.btnHeaderDelete.visibility = View.GONE
                
                // Centrar título
                titleParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
                titleParams.addRule(RelativeLayout.CENTER_IN_PARENT)
                binding.txtToolsTitle.layoutParams = titleParams

                // Mantener panel abierto al deseleccionar para fluidez
                binding.expandableContent.visibility = View.VISIBLE
                binding.imgExpandIcon.rotation = 180f
            }
        }

        binding.btnFlipSide.setOnClickListener {
            binding.editorMapView.selectedElement?.let { if (it.type == "DOOR") { saveStateToHistory(); it.isHollow = !it.isHollow; binding.editorMapView.invalidate() } }
        }
        binding.btnFlipOpening.setOnClickListener {
            binding.editorMapView.selectedElement?.let { if (it.type == "DOOR") { saveStateToHistory(); it.strokeWidth = if (it.strokeWidth > 5f) 2f else 8f; binding.editorMapView.invalidate() } }
        }
        /* binding.btnMoveAssist.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { binding.editorMapView.isMoveAssistActive = true; v.isPressed = true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { binding.editorMapView.isMoveAssistActive = false; v.isPressed = false }
            }
            true
        } */
        binding.sliderThickness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.editorMapView.selectedElement?.let { it.strokeWidth = value; binding.editorMapView.invalidate() }
        }
        binding.btnToggleHollow.setOnClickListener {
            binding.editorMapView.selectedElement?.let { saveStateToHistory(); it.isHollow = !it.isHollow; binding.btnToggleHollow.text = if (it.isHollow) "Poner Relleno" else "Quitar Relleno"; binding.editorMapView.invalidate() }
        }
        binding.btnAddWall.setOnClickListener {
            saveStateToHistory()
            val newWall = HotelRoomLayoutEntity(type = "WALL", floorId = currentFloorId, x = 100f, y = 100f, width = 600f, height = 50f, isHollow = false, strokeWidth = 8f)
            binding.editorMapView.layoutElements.add(newWall); binding.editorMapView.constrainToCanvas(newWall); binding.editorMapView.invalidate()
        }
        binding.btnAddDoor.setOnClickListener {
            saveStateToHistory()
            val newDoor = HotelRoomLayoutEntity(type = "DOOR", floorId = currentFloorId, x = 150f, y = 150f, width = 100f, height = 12f, isHollow = false, strokeWidth = 2f)
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
        binding.btnHeaderDelete.setOnClickListener { binding.editorMapView.deleteSelected() }
        binding.btnRotate.setOnClickListener {
            binding.editorMapView.selectedElement?.let { saveStateToHistory(); it.rotation = (it.rotation + 90f) % 360f; binding.editorMapView.constrainToCanvas(it); binding.editorMapView.invalidate() }
        }
        binding.btnSave.setOnClickListener {
            syncCurrentFloorToSnapshot()
            lifecycleScope.launch {
                try {
                    val currentLayouts = database.hotelDao().getAllLayouts().first()
                    for (layout in currentLayouts) database.hotelDao().deleteLayout(layout)
                    
                    val syncManager = SyncManager(this@HotelMapEditorActivity)
                    for (element in allLayoutsSnapshot) {
                        database.hotelDao().insertLayout(element)
                        syncManager.syncHotelLayoutToCloud(element)
                    }
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
