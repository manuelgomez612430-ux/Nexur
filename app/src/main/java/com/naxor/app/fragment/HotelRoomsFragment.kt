package com.naxor.app.fragment

import com.naxor.app.data.HotelRoomLayoutEntity
import com.naxor.app.util.HotelMapView
import com.naxor.app.R
import com.naxor.app.MainActivity
import android.content.Intent
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
                    checkMapEmptyState()
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
        
        val openEditor = {
            startActivity(Intent(requireContext(), com.naxor.app.HotelMapEditorActivity::class.java))
        }
        
        binding.btnOpenEditor.setOnClickListener { openEditor() }
        binding.btnCreateMapEmpty.setOnClickListener { openEditor() }
        
        binding.mapView.isEditMode = false 
        binding.mapView.onRoomClicked = { roomId ->
            val room = allRooms.find { it.id == roomId }
            room?.let { showRoomActionDialog(it) }
        }
    }

    private fun setupToolbar() {
        binding.toolbarRooms.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarRooms.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupRecyclerView() {
        adapter = HotelRoomAdapter { room -> showRoomActionDialog(room) }
        binding.rvRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRooms.adapter = adapter
    }

    private fun observeLayouts() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.hotelDao().getAllLayouts().collectLatest { layouts ->
                _binding?.let { b ->
                    b.mapView.layoutElements = layouts.toMutableList()
                    b.mapView.invalidate()
                    checkMapEmptyState()
                }
            }
        }
    }

    private fun checkMapEmptyState() {
        if (binding.mapView.layoutElements.isEmpty()) {
            binding.layoutEmptyMap.visibility = View.VISIBLE
            binding.cardMapDisplay.visibility = View.GONE
            binding.btnOpenEditor.visibility = View.GONE
        } else {
            binding.layoutEmptyMap.visibility = View.GONE
            binding.cardMapDisplay.visibility = View.VISIBLE
            binding.btnOpenEditor.visibility = View.VISIBLE
        }
    }

    private fun observeRooms() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.hotelDao().getAllRooms().collectLatest { rooms ->
                _binding?.let { b ->
                    allRooms = rooms
                    adapter.submitList(rooms)
                    
                    // Actualizar estados y nombres en el mapa
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
            setPadding(50, 20, 50, 20)
        }

        val etNumber = EditText(requireContext()).apply { hint = "Número de Habitación" }
        val etPrice = EditText(requireContext()).apply { 
            hint = "Precio por Noche"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val spinnerType = Spinner(requireContext())
        val types = arrayOf("Simple", "Doble", "Matrimonial", "Suite")
        spinnerType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)

        layout.addView(etNumber)
        layout.addView(spinnerType)
        layout.addView(etPrice)

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Habitación")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val number = etNumber.text.toString()
                val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
                val type = spinnerType.selectedItem.toString()
                
                if (number.isNotEmpty()) {
                    lifecycleScope.launch {
                        database.hotelDao().insertRoom(HotelRoomEntity(
                            number = number,
                            type = type,
                            baseRate = price
                        ))
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRoomActionDialog(room: HotelRoomEntity) {
        val options = when(room.status) {
            "FREE" -> arrayOf("Check-in", "Marcar como Mantenimiento", "Eliminar")
            "OCCUPIED" -> arrayOf("Ver Detalle / Check-out", "Mantenimiento")
            "DIRTY" -> arrayOf("Marcar como Limpia", "Mantenimiento")
            else -> arrayOf("Habilitar (Libre)", "Eliminar")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Acciones - Hab ${room.number}")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val selected = options[which]
                    when {
                        selected == "Check-in" -> {
                            val intent = Intent(requireContext(), com.naxor.app.HotelCheckInActivity::class.java)
                            intent.putExtra("ROOM_ID", room.id)
                            intent.putExtra("ROOM_NUMBER", room.number)
                            intent.putExtra("BASE_RATE", room.baseRate)
                            startActivity(intent)
                        }
                        selected == "Marcar como Limpia" || selected == "Habilitar (Libre)" -> {
                            database.hotelDao().updateRoomStatus(room.id, "FREE")
                        }
                        selected == "Ver Detalle / Check-out" -> {
                            performCheckOut(room)
                        }
                        selected.contains("Mantenimiento") -> {
                            database.hotelDao().updateRoomStatus(room.id, "MAINTENANCE")
                        }
                        selected == "Eliminar" -> {
                            database.hotelDao().updateRoom(room.copy(isDeleted = true))
                        }
                    }
                }
            }
            .show()
    }

    private fun performCheckOut(room: HotelRoomEntity) {
        lifecycleScope.launch {
            val booking = database.hotelDao().getActiveBookingForRoom(room.id)
            if (booking != null) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Check-out - Hab ${room.number}")
                    .setMessage("Huésped: ${booking.guestName}\nSaldo Pendiente: S/ ${booking.totalAmount - booking.deposit}\n\n¿Confirmar salida y marcar habitación para limpieza?")
                    .setPositiveButton("Confirmar Salida") { _, _ ->
                        lifecycleScope.launch {
                            database.hotelDao().updateRoomStatus(room.id, "DIRTY")
                            val updatedBooking = booking.copy(status = "CHECKED_OUT")
                            database.hotelDao().updateBooking(updatedBooking)
                            Toast.makeText(requireContext(), "Check-out completado", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
