package com.naxor.app.fragment

import android.content.Context
import com.naxor.app.data.HotelRoomLayoutEntity
import com.naxor.app.util.HotelMapView
import com.naxor.app.R
import com.naxor.app.MainActivity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerSize
import android.content.res.ColorStateList
import android.net.Uri
import android.text.Html
import android.text.InputType
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.shape.CornerFamily
import com.google.android.material.textfield.TextInputLayout
import com.naxor.app.HotelCheckInActivity
import com.naxor.app.HotelMaintenanceActivity
import com.naxor.app.HotelManageRoomActivity
import com.naxor.app.HotelMapEditorActivity
import com.naxor.app.SyncManager
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.adapter.HotelRoomAdapter
import com.naxor.app.adapter.RoomListItem
import com.naxor.app.adapter.RoomListType
import com.naxor.app.databinding.DialogAddRoomBinding
import com.naxor.app.databinding.FragmentHotelRoomsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

class HotelRoomsFragment : Fragment() {

    private var _binding: FragmentHotelRoomsBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private lateinit var adapter: HotelRoomAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()
    
    private var allLayouts: List<HotelRoomLayoutEntity> = emptyList()
    private var currentFloor = 1
    
    private var tempRoomPhotoPath: String? = null
    private var currentDialogBinding: DialogAddRoomBinding? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            tempRoomPhotoPath = saveImageToInternalStorage(it)
            currentDialogBinding?.let { b ->
                b.ivRoomPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                b.ivRoomPhoto.setPadding(0, 0, 0, 0)
                b.ivRoomPhoto.imageTintList = null
                Glide.with(this).load(it).into(b.ivRoomPhoto)
            }
        }
    }

    private fun saveImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val file = File(requireContext().getExternalFilesDir(null), "room_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelRoomsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Soporte Edge-to-edge (Fondo extendido hasta arriba)
        ViewCompat.setOnApplyWindowInsetsListener(binding.roomsContentRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // 1. Pad horizontal del root
            binding.roomsContentRoot.setPadding(systemBars.left, 0, systemBars.right, 0)
            
            // 2. Pad top del Toolbar para no quedar bajo la hora/batería
            binding.layoutRoomsToolbar.setPadding(0, systemBars.top, 0, 0)
            
            insets
        }

        setupToolbar()
        setupRecyclerView()
        setupViewToggle()
        setupListeners()
        observeRooms()
        observeLayouts()
        checkInitialViewMode()
    }

    private fun checkInitialViewMode() {
        val prefs = requireContext().getSharedPreferences("HotelPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("FORCE_MAP_VIEW", false)) {
            binding.toggleViewMode.check(R.id.btnGraphView)
            binding.rvRooms.visibility = View.GONE
            binding.layoutGraphView.visibility = View.VISIBLE
            binding.fabAddRoom.hide()
            
            prefs.edit().remove("FORCE_MAP_VIEW").apply()
        }
    }

    private fun setupViewToggle() {
        binding.toggleViewMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnGraphView) {
                    binding.rvRooms.visibility = View.GONE
                    binding.layoutGraphView.visibility = View.VISIBLE
                    binding.fabAddRoom.hide()
                } else {
                    binding.rvRooms.visibility = View.VISIBLE
                    binding.layoutGraphView.visibility = View.GONE
                    binding.fabAddRoom.show()
                }
            }
        }
    }

    private fun setupListeners() {
        binding.fabAddRoom.setOnClickListener { showRoomDialog() }
        
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val canEditMap = !isCollab || collabPrefs.getBoolean("can_edit_map", false)

        binding.btnOpenEditor.visibility = if (canEditMap) View.VISIBLE else View.GONE
        binding.btnCreateMapEmpty.visibility = if (canEditMap) View.VISIBLE else View.GONE

        binding.btnOpenEditor.setOnClickListener { showFloorSelectionDialog() }
        binding.btnCreateMapEmpty.setOnClickListener { showFloorSelectionDialog() }
        
        binding.mapView.isEditMode = false 
        binding.mapView.onRoomClicked = { roomId ->
            viewLifecycleOwner.lifecycleScope.launch {
                val room = database.hotelDao().getRoomById(roomId)
                val reports = database.hotelDao().getPendingMaintenanceForRoom(roomId).first()
                val bookings = database.hotelDao().getAllBookings().first()
                val hasRes = bookings.any { it.roomId == roomId && it.status == "CONFIRMED" }
                room?.let { showRoomActionDialog(it, reports.isNotEmpty(), hasRes) }
            }
        }
    }

    private fun showFloorSelectionDialog() {
        // Combinar pisos de habitaciones y de layouts existentes
        val floorsFromRooms = allRooms.map { it.floor }
        val floorsFromLayouts = allLayouts.map { it.floorId }
        val availableFloors = (floorsFromRooms + floorsFromLayouts).distinct().sorted()
        
        if (availableFloors.isEmpty()) {
            // Si no hay nada, mostrar al menos el piso 1 para poder empezar
            val intent = Intent(requireContext(), HotelMapEditorActivity::class.java)
            intent.putExtra("FLOOR_ID", 1)
            startActivity(intent)
            return
        }

        val floorNames = availableFloors.map { "Piso $it" }.toTypedArray()
        
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Piso para Editar")
            .setItems(floorNames) { _, which ->
                val selectedFloor = availableFloors[which]
                val intent = Intent(requireContext(), HotelMapEditorActivity::class.java)
                intent.putExtra("FLOOR_ID", selectedFloor)
                startActivity(intent)
            }
            .show()
    }

    private fun showBulkDeleteDialog() {
        val options = arrayOf("🗑️ Por Piso", "🔢 Por Rango de Números")
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminación Masiva")
            .setItems(options) { _, which ->
                if (which == 0) showDeleteByFloorDialog()
                else showDeleteByRangeDialog()
            }
            .show()
    }

    private fun showDeleteByFloorDialog() {
        val floors = allRooms.map { it.floor }.distinct().sorted()
        if (floors.isEmpty()) {
            Toast.makeText(requireContext(), "No hay habitaciones para eliminar", Toast.LENGTH_SHORT).show()
            return
        }
        
        val floorNames = floors.map { "Piso $it" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Piso a vaciar")
            .setItems(floorNames) { _, which ->
                val floor = floors[which]
                confirmBulkDelete("¿Eliminar TODAS las habitaciones del Piso $floor?") {
                    lifecycleScope.launch {
                        val roomsToDelete = allRooms.filter { it.floor == floor }
                        if (roomsToDelete.isNotEmpty()) {
                            val updatedRooms = roomsToDelete.map { it.copy(isDeleted = true) }
                            database.hotelDao().updateRooms(updatedRooms)
                            
                            // Sincronizar con la nube en segundo plano
                            updatedRooms.forEach {
                                SyncManager(requireContext()).syncHotelRoomToCloud(it)
                            }
                            Toast.makeText(requireContext(), "Piso $floor vaciado", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.show()
    }

    private fun showDeleteByRangeDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        val etStart = EditText(requireContext()).apply { hint = "Desde N° (Ej: 101)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val etEnd = EditText(requireContext()).apply { hint = "Hasta N° (Ej: 110)"; inputType = InputType.TYPE_CLASS_NUMBER }
        
        layout.addView(etStart)
        layout.addView(etEnd)
        
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar por Rango")
            .setView(layout)
            .setPositiveButton("Eliminar") { _, _ ->
                val start = etStart.text.toString().toIntOrNull()
                val end = etEnd.text.toString().toIntOrNull()
                
                if (start != null && end != null && start <= end) {
                    confirmBulkDelete("¿Eliminar habitaciones desde la $start hasta la $end?") {
                        lifecycleScope.launch {
                            val roomsToDelete = allRooms.filter { 
                                val num = it.number.toIntOrNull()
                                num != null && num in start..end
                            }
                            if (roomsToDelete.isNotEmpty()) {
                                val updatedRooms = roomsToDelete.map { it.copy(isDeleted = true) }
                                database.hotelDao().updateRooms(updatedRooms)
                                
                                // Sincronizar
                                updatedRooms.forEach {
                                    SyncManager(requireContext()).syncHotelRoomToCloud(it)
                                }
                                Toast.makeText(requireContext(), "Rango eliminado correctamente", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Rango inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmBulkDelete(msg: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Confirmar Eliminación")
            .setMessage(msg)
            .setPositiveButton("Sí, Eliminar", { _, _ -> onConfirm() })
            .setNegativeButton("No", null)
            .show()
    }

    private fun observeLayouts() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.hotelDao().getAllLayouts().collectLatest { layouts ->
                allLayouts = layouts
                updateMapForCurrentFloor()
                val floors = layouts.map { it.floorId }.distinct().sorted()
                updateFloorSelector(if (floors.isEmpty()) listOf(1) else floors)
            }
        }
    }

    private fun updateMapForCurrentFloor() {
        _binding?.let { b ->
            val floorLayouts = allLayouts.filter { it.floorId == currentFloor }
            b.mapView.layoutElements = floorLayouts.toMutableList()
            b.mapView.invalidate()
            
            if (floorLayouts.isEmpty() && allLayouts.isEmpty()) {
                b.layoutEmptyMap.visibility = View.VISIBLE
                b.mapView.visibility = View.GONE
                b.scrollFloors.visibility = View.GONE
            } else {
                b.layoutEmptyMap.visibility = View.GONE
                b.mapView.visibility = View.VISIBLE
                b.scrollFloors.visibility = View.VISIBLE
            }
        }
    }

    private fun updateFloorSelector(floors: List<Int>) {
        binding.layoutFloors.removeAllViews()
        val sortedFloors = floors.sorted() // Orden normal 1, 2, 3...
        val density = resources.displayMetrics.density
        
        for (floor in sortedFloors) {
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                val heightSize = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, heightSize).apply { 
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0) 
                }
                text = "PISO $floor"
                textSize = 11f
                insetTop = 0; insetBottom = 0
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                minWidth = 0; minHeight = 0
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, heightSize / 2f)
                    .build()
                
                if (floor == currentFloor) {
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sky_600))
                    setTextColor(Color.WHITE)
                    strokeWidth = 0
                } else {
                    setBackgroundColor(Color.WHITE)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_600))
                    strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.slate_200)
                }
                
                setOnClickListener {
                    currentFloor = floor
                    updateMapForCurrentFloor()
                    updateFloorSelector(floors)
                }
            }
            binding.layoutFloors.addView(btn)
        }
    }

    private fun observeRooms() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                database.hotelDao().getAllRooms(),
                database.hotelDao().getAllMaintenanceReports(),
                database.hotelDao().getAllBookings()
            ) { rooms, maintenance, bookings ->
                Triple(rooms, maintenance, bookings)
            }.collectLatest { (rooms, maintenance, bookings) ->
                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Procesamiento de datos para UI
                val processedData = withContext(Dispatchers.Default) {
                    val pendingMaintMap = maintenance.filter { it.status == "PENDING" }.groupBy { it.roomId }
                    val reservationsMap = bookings.filter { it.status == "CONFIRMED" }.groupBy { it.roomId }

                    val groupedList = mutableListOf<RoomListType>()
                    rooms.groupBy { it.floor }.toSortedMap().forEach { (floor, roomsInFloor) ->
                        groupedList.add(RoomListType.Header(floor))
                        roomsInFloor.forEach { room ->
                            val hasFailure = pendingMaintMap.containsKey(room.id)
                            val hasRes = reservationsMap.containsKey(room.id)
                            groupedList.add(
                                RoomListType.Room(
                                    RoomListItem(room, hasFailure, hasRes)
                            ))
                        }
                    }

                    val statusMap = rooms.associate { it.id to it.status }
                    val nameMap = rooms.associate { it.id to it.number }
                    
                    Quadruple(groupedList, statusMap, nameMap, Pair(pendingMaintMap.keys, reservationsMap.keys))
                }

                _binding?.let { b ->
                    allRooms = rooms
                    adapter.submitList(processedData.first)
                    
                    b.mapView.roomStatuses = processedData.second
                    b.mapView.roomNames = processedData.third
                    b.mapView.roomsWithFailures = processedData.fourth.first
                    b.mapView.roomsReserved = processedData.fourth.second
                    
                    b.mapView.invalidate()
                }

                // Ejecutar actualización de estados de limpieza una sola vez fuera del flujo reactivo principal
                withContext(Dispatchers.IO) {
                    val roomsToUpdate = rooms.filter { it.status == "FREE" && it.lastCleaned < startOfToday }
                    if (roomsToUpdate.isNotEmpty()) {
                        roomsToUpdate.forEach { room ->
                            val updated = room.copy(status = "MAINTENANCE")
                            database.hotelDao().updateRoom(updated)
                            SyncManager(requireContext()).syncHotelRoomToCloud(updated)
                        }
                    }
                }
            }
        }
    }

    private fun showRoomDialog(roomToEdit: HotelRoomEntity? = null) {
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)

        val dialogBinding = DialogAddRoomBinding.inflate(layoutInflater)
        currentDialogBinding = dialogBinding
        tempRoomPhotoPath = roomToEdit?.photoPath

        if (isCollab) {
            dialogBinding.etRoomNumber.isEnabled = false
            dialogBinding.etRoomFloor.isEnabled = false
            dialogBinding.etRoomPrice.isEnabled = false
            dialogBinding.checkBulk.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_GoMezMart_Dialog)
            .setView(dialogBinding.root)
            .create()

        if (roomToEdit != null) {
            dialogBinding.tvTitleDialog.text = "Editar Habitación"
            dialogBinding.checkBulk.visibility = View.GONE
            dialogBinding.etRoomNumber.setText(roomToEdit.number)
            dialogBinding.etRoomFloor.setText(roomToEdit.floor.toString())
            dialogBinding.etRoomPrice.setText(roomToEdit.baseRate.toString())
            dialogBinding.etGlobalDescription.setText(roomToEdit.description)
            dialogBinding.etSpecificDescription.setText(roomToEdit.specificDescription)
            dialogBinding.btnSaveRoom.text = "Actualizar"
            
            if (!roomToEdit.photoPath.isNullOrEmpty()) {
                val file = File(roomToEdit.photoPath)
                if (file.exists()) {
                    dialogBinding.ivRoomPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    dialogBinding.ivRoomPhoto.setPadding(0, 0, 0, 0)
                    dialogBinding.ivRoomPhoto.imageTintList = null
                    Glide.with(this).load(file).into(dialogBinding.ivRoomPhoto)
                }
            }
        }

        dialogBinding.cardRoomPhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // --- CARGAR DESCRIPCIONES GLOBALES (Sugerencias dinámicas) ---
        val globalDescs = allRooms.mapNotNull { it.description }.filter { it.isNotEmpty() }.distinct()
        val adapterDescs = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, globalDescs)
        dialogBinding.etGlobalDescription.setAdapter(adapterDescs)

        // Configurar Tipos de Habitación (Exposed Dropdown)
        val types = arrayOf("Simple", "Doble", "Matrimonial", "Suite")
        val adapterTypes = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, types)
        dialogBinding.spinnerRoomType.setAdapter(adapterTypes)
        
        if (roomToEdit != null) {
            dialogBinding.spinnerRoomType.setText(roomToEdit.type, false)
        } else {
            dialogBinding.spinnerRoomType.setText(types[0], false)
        }

        dialogBinding.checkBulk.setOnCheckedChangeListener { _, isChecked: Boolean ->
            dialogBinding.layoutRoomNormal.visibility = if (isChecked) View.GONE else View.VISIBLE
            dialogBinding.layoutRoomBulk.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // --- LÓGICA DE VALIDACIÓN EN TIEMPO REAL ---
        val validateRoom = { input: String, layout: TextInputLayout ->
            if (input.isEmpty()) {
                layout.error = null
                layout.helperText = null
                layout.setBoxStrokeColorStateList(ColorStateList.valueOf(requireContext().getColor(R.color.slate_200)))
            } else {
                lifecycleScope.launch {
                    val exists = withContext(Dispatchers.IO) {
                        val r = database.hotelDao().getRoomByNumber(input)
                        r != null && r.id != roomToEdit?.id
                    }
                    if (exists) {
                        layout.error = "Esta habitación ya existe"
                        layout.helperText = null
                        layout.setBoxStrokeColorStateList(ColorStateList.valueOf(requireContext().getColor(R.color.red_600)))
                    } else {
                        layout.error = null
                        layout.helperText = "Número disponible ✓"
                        layout.setHelperTextColor(ColorStateList.valueOf(requireContext().getColor(R.color.emerald_600)))
                        layout.setBoxStrokeColorStateList(ColorStateList.valueOf(requireContext().getColor(R.color.emerald_600)))
                    }
                }
            }
        }

        dialogBinding.etRoomNumber.addTextChangedListener { validateRoom(it.toString(), dialogBinding.layoutRoomNumber) }
        dialogBinding.etRoomStart.addTextChangedListener { validateRoom(it.toString(), dialogBinding.layoutRoomStart) }
        dialogBinding.etRoomEnd.addTextChangedListener { validateRoom(it.toString(), dialogBinding.layoutRoomEnd) }

        dialogBinding.btnCancelRoom.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnSaveRoom.setOnClickListener {
            val isBulk = dialogBinding.checkBulk.isChecked
            val type = dialogBinding.spinnerRoomType.text.toString()
            val price = dialogBinding.etRoomPrice.text.toString().toDoubleOrNull() ?: 0.0
            val globalDesc = dialogBinding.etGlobalDescription.text.toString().trim()
            val specificDesc = dialogBinding.etSpecificDescription.text.toString().trim()

            if (isBulk && roomToEdit == null) {
                val startNum = dialogBinding.etRoomStart.text.toString().toIntOrNull()
                val endNum = dialogBinding.etRoomEnd.text.toString().toIntOrNull()
                val floor = dialogBinding.etRoomFloorBulk.text.toString().toIntOrNull() ?: 1

                if (startNum != null && endNum != null && startNum <= endNum) {
                    val count = endNum - startNum + 1
                    if (count > 50) {
                        Toast.makeText(requireContext(), "Máximo 50 habitaciones a la vez", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        val conflictingRooms = withContext(Dispatchers.IO) {
                            (startNum..endNum).filter { database.hotelDao().getRoomByNumber(it.toString()) != null }
                        }

                        if (conflictingRooms.isNotEmpty()) {
                            Toast.makeText(requireContext(), "Error: Las habitaciones ${conflictingRooms.joinToString()} ya existen", Toast.LENGTH_LONG).show()
                            return@launch
                        }

                        for (num in startNum..endNum) {
                            val room = HotelRoomEntity(
                                number = num.toString(),
                                floor = floor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = tempRoomPhotoPath
                            )
                            database.hotelDao().insertRoom(room)
                            SyncManager(requireContext()).syncHotelRoomToCloud(room)
                        }
                        Toast.makeText(requireContext(), "Creadas $count habitaciones", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                } else {
                    Toast.makeText(requireContext(), "Ingresa un rango válido (Inicio <= Final)", Toast.LENGTH_SHORT).show()
                }
            } else {
                val numberStr = dialogBinding.etRoomNumber.text.toString()
                val floor = dialogBinding.etRoomFloor.text.toString().toIntOrNull() ?: 1

                if (numberStr.isNotEmpty()) {
                    lifecycleScope.launch {
                        val exists = withContext(Dispatchers.IO) {
                            val r = database.hotelDao().getRoomByNumber(numberStr)
                            r != null && r.id != roomToEdit?.id
                        }
                        if (exists) {
                            dialogBinding.layoutRoomNumber.error = "Esta habitación ya existe"
                            return@launch
                        }

                        if (roomToEdit != null) {
                            val updatedRoom = roomToEdit.copy(
                                number = numberStr,
                                floor = floor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = tempRoomPhotoPath,
                                photoUrl = if (tempRoomPhotoPath != roomToEdit.photoPath) null else roomToEdit.photoUrl
                            )
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(requireContext()).syncHotelRoomToCloud(updatedRoom)
                            Toast.makeText(requireContext(), "Habitación actualizada", Toast.LENGTH_SHORT).show()
                        } else {
                            val room = HotelRoomEntity(
                                number = numberStr,
                                floor = floor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = tempRoomPhotoPath
                            )
                            database.hotelDao().insertRoom(room)
                            SyncManager(requireContext()).syncHotelRoomToCloud(room)
                        }
                        dialog.dismiss()
                    }
                } else {
                    Toast.makeText(requireContext(), "Ingresa el número de habitación", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()

        // Ajustar ancho del diálogo
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setupToolbar() {
        binding.btnMenuRooms.setOnClickListener { (activity as? MainActivity)?.openSideMenu() }
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        binding.btnBulkDelete.visibility = if (isCollab) View.GONE else View.VISIBLE
        binding.btnBulkDelete.setOnClickListener { showBulkDeleteDialog() }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun setupRecyclerView() {
        adapter = HotelRoomAdapter(
            onAction = { room ->
                lifecycleScope.launch {
                    when (room.status) {
                        "OCCUPIED" -> {
                            val intent = Intent(requireContext(), HotelManageRoomActivity::class.java)
                            intent.putExtra("ROOM_ID", room.id); intent.putExtra("ROOM_NUMBER", room.number)
                            startActivity(intent)
                        }
                        "FREE", "DIRTY", "MAINTENANCE", "RESERVED" -> {
                            val i = Intent(requireContext(), HotelCheckInActivity::class.java)
                            i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                            startActivity(i)
                        }
                    }
                }
            },
            onSecondAction = { room ->
                lifecycleScope.launch {
                    val newStatus = if (room.status == "OCCUPIED") "OCCUPIED" else "FREE"
                    val updatedRoom = room.copy(status = newStatus, lastCleaned = System.currentTimeMillis())
                    database.hotelDao().updateRoom(updatedRoom)
                    SyncManager(requireContext()).syncHotelRoomToCloud(updatedRoom)
                    Toast.makeText(requireContext(), "Aseo registrado correctamente ✨", Toast.LENGTH_SHORT).show()
                }
            },
            onThirdAction = { room ->
                lifecycleScope.launch {
                    val reports = database.hotelDao().getPendingMaintenanceForRoom(room.id).first()
                    reports.forEach { report -> 
                        database.hotelDao().updateMaintenanceReport(report.copy(status = "FIXED")) 
                    }
                    Toast.makeText(requireContext(), "Reparación completada ✅", Toast.LENGTH_SHORT).show()
                }
            },
            onCardClick = { room, hasFailure, hasRes -> showRoomActionDialog(room, hasFailure, hasRes) }
        )
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun showRoomActionDialog(room: HotelRoomEntity, hasPendingFailure: Boolean, hasReservation: Boolean = false) {
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        
        val options = mutableListOf<String>()
        
        if (room.status == "FREE" && !hasReservation) {
            options.add("Check-in Rápido")
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(
            Calendar.MILLISECOND, 0)
        val needsCleaning = room.lastCleaned < cal.timeInMillis

        if (needsCleaning || room.status == "DIRTY" || room.status == "MAINTENANCE") {
            options.add("Limpieza Realizada")
        } else {
            options.add("Solicitar Aseo (Manual)")
        }

        if (hasPendingFailure) {
            options.add("Marcar como Reparada ✅")
        } else {
            options.add("Reportar Falla 🛠️")
        }

        if (isCollab) {
            if (collabPrefs.getBoolean("can_edit_room_info", false)) options.add("Editar Información")
        } else {
            options.add("Editar Información")
            options.add("Eliminar Habitación")
        }

        val descriptionText = StringBuilder()
        if (!room.description.isNullOrEmpty()) descriptionText.append("<br><small>📝 ${room.description}</small>")
        if (!room.specificDescription.isNullOrEmpty()) descriptionText.append("<br><small>✨ ${room.specificDescription}</small>")

        val titleHtml = "<b>Opciones: Habitación ${room.number}</b>$descriptionText"

        AlertDialog.Builder(requireContext())
            .setTitle(Html.fromHtml(titleHtml, Html.FROM_HTML_MODE_LEGACY))
            .setItems(options.toTypedArray()) { _, w ->
                lifecycleScope.launch {
                    when (options[w]) {
                        "Check-in Rápido" -> {
                            val i = Intent(requireContext(), HotelCheckInActivity::class.java)
                            i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                            startActivity(i)
                        }
                        "Limpieza Realizada" -> {
                            val updatedRoom = room.copy(status = if(room.status == "OCCUPIED") "OCCUPIED" else "FREE", lastCleaned = System.currentTimeMillis())
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(requireContext()).syncHotelRoomToCloud(updatedRoom)
                        }
                        "Solicitar Aseo (Manual)" -> {
                            val updatedRoom = room.copy(lastCleaned = 0L)
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(requireContext()).syncHotelRoomToCloud(updatedRoom)
                        }
                    "Marcar como Reparada ✅" -> {
                        val reports = database.hotelDao().getPendingMaintenanceForRoom(room.id).first()
                        reports.forEach { report -> 
                            database.hotelDao().updateMaintenanceReport(report.copy(status = "FIXED")) 
                        }
                        Toast.makeText(requireContext(), "Falla reparada", Toast.LENGTH_SHORT).show()
                    }
                    "Reportar Falla 🛠️" -> {
                        val intent = Intent(requireContext(), HotelMaintenanceActivity::class.java)
                        intent.putExtra("REPORT_ROOM_ID", room.id)
                        startActivity(intent)
                    }
                        "Editar Información" -> {
                            showRoomDialog(room)
                        }
                        "Eliminar Habitación" -> {
                            AlertDialog.Builder(requireContext()).setTitle("¿Eliminar?").setPositiveButton("Sí") { _,_ -> 
                                lifecycleScope.launch { 
                                    val updatedRoom = room.copy(isDeleted = true)
                                    database.hotelDao().updateRoom(updatedRoom) 
                                    SyncManager(requireContext()).syncHotelRoomToCloud(updatedRoom)
                                } 
                            }.setNegativeButton("No", null).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
