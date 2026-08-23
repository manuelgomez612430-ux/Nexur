package com.naxor.app.fragment

import com.naxor.app.data.HotelRoomLayoutEntity
import com.naxor.app.util.HotelMapView
import com.naxor.app.R
import com.naxor.app.MainActivity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerSize
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.adapter.HotelRoomAdapter
import com.naxor.app.databinding.FragmentHotelRoomsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class HotelRoomsFragment : Fragment() {

    private var _binding: FragmentHotelRoomsBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private lateinit var adapter: HotelRoomAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()
    
    private var allLayouts: List<HotelRoomLayoutEntity> = emptyList()
    private var currentFloor = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelRoomsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupViewToggle()
        setupListeners()
        observeRooms()
        observeLayouts()
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
        binding.fabAddRoom.setOnClickListener { showAddRoomDialog() }
        
        binding.btnOpenEditor.setOnClickListener { showFloorSelectionDialog() }
        binding.btnCreateMapEmpty.setOnClickListener { showFloorSelectionDialog() }
        
        binding.mapView.isEditMode = false 
        binding.mapView.onRoomClicked = { roomId ->
            viewLifecycleOwner.lifecycleScope.launch {
                val room = database.hotelDao().getRoomById(roomId)
                val reports = database.hotelDao().getPendingMaintenanceForRoom(roomId).first()
                room?.let { showRoomActionDialog(it, reports.isNotEmpty()) }
            }
        }
    }

    private fun showFloorSelectionDialog() {
        val floors = (1..10).toList()
        val floorNames = floors.map { "Piso $it" }.toTypedArray()
        
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Piso para Editar")
            .setItems(floorNames) { _, which ->
                val selectedFloor = floors[which]
                val intent = Intent(requireContext(), com.naxor.app.HotelMapEditorActivity::class.java)
                intent.putExtra("FLOOR_ID", selectedFloor)
                startActivity(intent)
            }
            .show()
    }

    private fun setupToolbar() {
        binding.toolbarRooms.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarRooms.setNavigationOnClickListener { (activity as? MainActivity)?.openSideMenu() }
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
                    .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, heightSize / 2f)
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
                _binding?.let { b ->
                    allRooms = rooms
                    
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    val startOfToday = cal.timeInMillis
                    
                    rooms.filter { it.status == "FREE" && it.lastCleaned < startOfToday }.forEach { room ->
                        lifecycleScope.launch {
                            database.hotelDao().updateRoomStatus(room.id, "MAINTENANCE")
                        }
                    }

                    // Preparar mapas de estado
                    val pendingMaintMap = maintenance.filter { it.status == "PENDING" }.groupBy { it.roomId }
                    val reservationsMap = bookings.filter { it.status == "CONFIRMED" }.groupBy { it.roomId }

                    val groupedList = mutableListOf<com.naxor.app.adapter.RoomListType>()
                    rooms.groupBy { it.floor }.toSortedMap().forEach { (floor, roomsInFloor) ->
                        groupedList.add(com.naxor.app.adapter.RoomListType.Header(floor))
                        roomsInFloor.forEach { room ->
                            val hasFailure = pendingMaintMap.containsKey(room.id)
                            val hasRes = reservationsMap.containsKey(room.id)
                            groupedList.add(com.naxor.app.adapter.RoomListType.Room(
                                com.naxor.app.adapter.RoomListItem(room, hasFailure, hasRes)
                            ))
                        }
                    }
                    adapter.submitList(groupedList)
                    
                    val statusMap = rooms.associate { it.id to it.status }
                    val nameMap = rooms.associate { it.id to it.number }
                    b.mapView.roomStatuses = statusMap
                    b.mapView.roomNames = nameMap
                    
                    // Pasar datos extra al mapa
                    b.mapView.roomsWithFailures = pendingMaintMap.keys
                    b.mapView.roomsReserved = reservationsMap.keys
                    
                    b.mapView.invalidate()
                }
            }
        }
    }

    private fun showAddRoomDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 30)
        }

        val checkBulk = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            text = "Creación Masiva (Múltiples habitaciones)"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.slate_700))
        }

        val etNumber = EditText(requireContext()).apply { 
            hint = "Número de Habitación"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etFloor = EditText(requireContext()).apply {
            hint = "Piso"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        val etCount = EditText(requireContext()).apply {
            hint = "Cantidad a crear"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
        }

        val etPrice = EditText(requireContext()).apply { 
            hint = "Precio por Noche"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        
        val spinnerType = Spinner(requireContext())
        val types = arrayOf("Simple", "Doble", "Matrimonial", "Suite")
        spinnerType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)

        checkBulk.setOnCheckedChangeListener { _, isChecked ->
            etNumber.hint = if (isChecked) "Número Inicial (Ej: 101)" else "Número de Habitación"
            etCount.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        layout.addView(checkBulk)
        layout.addView(etNumber)
        layout.addView(etFloor)
        layout.addView(etCount)
        layout.addView(spinnerType)
        layout.addView(etPrice)

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Habitación")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val numberStr = etNumber.text.toString()
                val floor = etFloor.text.toString().toIntOrNull() ?: 1
                val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
                val type = spinnerType.selectedItem.toString()
                val isBulk = checkBulk.isChecked
                val count = etCount.text.toString().toIntOrNull() ?: 1

                if (numberStr.isNotEmpty()) {
                    lifecycleScope.launch {
                        if (isBulk) {
                            val startNum = numberStr.toIntOrNull()
                            if (startNum != null) {
                                for (i in 0 until count) {
                                    val currentNum = (startNum + i).toString()
                                    database.hotelDao().insertRoom(HotelRoomEntity(
                                        number = currentNum,
                                        floor = floor,
                                        type = type,
                                        baseRate = price
                                    ))
                                }
                                Toast.makeText(requireContext(), "$count habitaciones creadas", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "El número inicial debe ser numérico", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            database.hotelDao().insertRoom(HotelRoomEntity(
                                number = numberStr,
                                floor = floor,
                                type = type,
                                baseRate = price
                            ))
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = HotelRoomAdapter(
            onAction = { room ->
                lifecycleScope.launch {
                    when (room.status) {
                        "OCCUPIED" -> {
                            val intent = Intent(requireContext(), com.naxor.app.HotelManageRoomActivity::class.java)
                            intent.putExtra("ROOM_ID", room.id); intent.putExtra("ROOM_NUMBER", room.number)
                            startActivity(intent)
                        }
                        "FREE", "DIRTY", "MAINTENANCE" -> {
                            val i = Intent(requireContext(), com.naxor.app.HotelCheckInActivity::class.java)
                            i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                            startActivity(i)
                        }
                    }
                }
            },
            onSecondAction = { room ->
                lifecycleScope.launch {
                    val newStatus = if (room.status == "OCCUPIED") "OCCUPIED" else "FREE"
                    database.hotelDao().updateRoom(room.copy(status = newStatus, lastCleaned = System.currentTimeMillis()))
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
            onCardClick = { room, hasFailure -> showRoomActionDialog(room, hasFailure) }
        )
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun showRoomActionDialog(room: HotelRoomEntity, hasPendingFailure: Boolean) {
        val options = mutableListOf<String>()
        
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
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

        options.add("Eliminar Habitación")

        AlertDialog.Builder(requireContext())
            .setTitle("Opciones: Habitación ${room.number}")
            .setItems(options.toTypedArray()) { _, w ->
                lifecycleScope.launch {
                    when (options[w]) {
                        "Limpieza Realizada" -> {
                            database.hotelDao().updateRoom(room.copy(status = if(room.status == "OCCUPIED") "OCCUPIED" else "FREE", lastCleaned = System.currentTimeMillis()))
                        }
                        "Solicitar Aseo (Manual)" -> {
                            database.hotelDao().updateRoom(room.copy(lastCleaned = 0L))
                        }
                    "Marcar como Reparada ✅" -> {
                        val reports = database.hotelDao().getPendingMaintenanceForRoom(room.id).first()
                        reports.forEach { report -> 
                            database.hotelDao().updateMaintenanceReport(report.copy(status = "FIXED")) 
                        }
                        Toast.makeText(requireContext(), "Falla reparada", Toast.LENGTH_SHORT).show()
                    }
                    "Reportar Falla 🛠️" -> {
                        val intent = Intent(requireContext(), com.naxor.app.HotelMaintenanceActivity::class.java)
                        intent.putExtra("REPORT_ROOM_ID", room.id)
                        startActivity(intent)
                    }
                        "Eliminar Habitación" -> {
                            AlertDialog.Builder(requireContext()).setTitle("¿Eliminar?").setPositiveButton("Sí") { _,_ -> lifecycleScope.launch { database.hotelDao().updateRoom(room.copy(isDeleted = true)) } }.setNegativeButton("No", null).show()
                        }
                    }
                }
            }.show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
