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
import kotlinx.coroutines.launch

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
            val room = allRooms.find { it.id == roomId }
            room?.let { showRoomActionDialog(it) }
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

    private fun setupRecyclerView() {
        adapter = HotelRoomAdapter { room -> showRoomActionDialog(room) }
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
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
            database.hotelDao().getAllRooms().collectLatest { rooms ->
                _binding?.let { b ->
                    allRooms = rooms
                    
                    // Lógica de Medianoche: Auto-marcar como Mantenimiento si es nuevo día
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

                    // Agrupar habitaciones por piso para la lista
                    val groupedList = mutableListOf<com.naxor.app.adapter.RoomListItem>()
                    rooms.groupBy { it.floor }.toSortedMap().forEach { (floor, roomsInFloor) ->
                        groupedList.add(com.naxor.app.adapter.RoomListItem.Header(floor))
                        roomsInFloor.forEach { groupedList.add(com.naxor.app.adapter.RoomListItem.Room(it)) }
                    }
                    adapter.submitList(groupedList)
                    
                    val statusMap = rooms.associate { it.id to it.status }
                    val nameMap = rooms.associate { it.id to it.number }
                    b.mapView.roomStatuses = statusMap
                    b.mapView.roomNames = nameMap
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

    private fun showRoomActionDialog(room: HotelRoomEntity) {
        if (room.status == "OCCUPIED") {
            val intent = Intent(requireContext(), com.naxor.app.HotelManageRoomActivity::class.java)
            intent.putExtra("ROOM_ID", room.id)
            intent.putExtra("ROOM_NUMBER", room.number)
            startActivity(intent)
            return
        }

        val options = when(room.status) {
            "FREE" -> arrayOf("Check-in", "Solicitar Limpieza (Manual)", "Eliminar")
            "DIRTY" -> arrayOf("Limpieza Realizada", "Eliminar")
            "MAINTENANCE" -> arrayOf("Check-in (Habilitar)", "Limpieza Realizada", "Eliminar")
            else -> arrayOf("Habilitar", "Eliminar")
        }
        AlertDialog.Builder(requireContext()).setTitle("Habitación ${room.number}").setItems(options) { _, w ->
            lifecycleScope.launch {
                when (options[w]) {
                    "Check-in", "Check-in (Habilitar)" -> {
                        val i = Intent(requireContext(), com.naxor.app.HotelCheckInActivity::class.java)
                        i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                        startActivity(i)
                    }
                    "Solicitar Limpieza (Manual)" -> {
                        database.hotelDao().updateRoom(room.copy(status = "MAINTENANCE", lastCleaned = 0L))
                    }
                    "Limpieza Realizada", "Habilitar" -> {
                        database.hotelDao().updateRoom(room.copy(status = "FREE", lastCleaned = System.currentTimeMillis()))
                    }
                    "Mantenimiento" -> database.hotelDao().updateRoomStatus(room.id, "MAINTENANCE")
                    "Eliminar" -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("¿Eliminar Habitación?")
                            .setMessage("Esta acción ocultará la habitación de la lista y el mapa.")
                            .setPositiveButton("Eliminar") { _, _ ->
                                lifecycleScope.launch {
                                    database.hotelDao().updateRoom(room.copy(isDeleted = true))
                                }
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
        }.show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
