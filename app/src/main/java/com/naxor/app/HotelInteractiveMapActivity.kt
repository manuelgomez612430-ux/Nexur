package com.naxor.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelBookingEntity
import com.naxor.app.data.HotelMaintenanceEntity
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.data.HotelRoomLayoutEntity
import com.naxor.app.databinding.ActivityHotelInteractiveMapBinding
import com.naxor.app.databinding.DialogAddRoomBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class HotelInteractiveMapActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelInteractiveMapBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    private var allRooms: List<HotelRoomEntity> = emptyList()
    private var allLayouts: List<HotelRoomLayoutEntity> = emptyList()
    private var currentFloor = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelInteractiveMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupListeners()
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbarMap.setNavigationOnClickListener { finish() }
    }

    private fun setupListeners() {
        binding.btnEditThisFloor.setOnClickListener {
            val intent = Intent(this, HotelMapEditorActivity::class.java)
            intent.putExtra("FLOOR_ID", currentFloor)
            startActivity(intent)
        }

        binding.mapViewInteractive.isEditMode = false
        binding.mapViewInteractive.onRoomClicked = { roomId ->
            lifecycleScope.launch {
                val room = database.hotelDao().getRoomById(roomId)
                val reports = database.hotelDao().getPendingMaintenanceForRoom(roomId).first()
                val bookings = database.hotelDao().getAllBookings().first()
                val hasRes = bookings.any { it.roomId == roomId && it.status == "CONFIRMED" }
                room?.let { showRoomActionDialog(it, reports.isNotEmpty(), hasRes) }
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            combine(
                database.hotelDao().getAllRooms(),
                database.hotelDao().getAllLayouts(),
                database.hotelDao().getAllBookings(),
                database.hotelDao().getAllMaintenanceReports()
            ) { rooms, layouts, bookings, maintenance ->
                MapDataContainer(rooms, layouts, bookings, maintenance)
            }.collectLatest { data ->
                allRooms = data.rooms
                allLayouts = data.layouts
                
                val floorsFromRooms = allRooms.map { it.floor }
                val floorsFromLayouts = allLayouts.map { it.floorId }
                val floors = (floorsFromRooms + floorsFromLayouts).distinct().sorted()
                
                updateFloorSelector(if (floors.isEmpty()) listOf(1) else floors)
                updateMap(data.bookings, data.maintenance)
            }
        }
    }

    private data class MapDataContainer(
        val rooms: List<HotelRoomEntity>,
        val layouts: List<HotelRoomLayoutEntity>,
        val bookings: List<HotelBookingEntity>,
        val maintenance: List<HotelMaintenanceEntity>
    )

    private fun updateFloorSelector(floors: List<Int>) {
        binding.layoutFloorsMap.removeAllViews()
        val density = resources.displayMetrics.density
        
        for (floor in floors) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                val heightSize = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, heightSize).apply { 
                    setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0) 
                }
                text = "PISO $floor"
                textSize = 12f
                insetTop = 0; insetBottom = 0
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                
                if (floor == currentFloor) {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.purple_600))
                    setTextColor(Color.WHITE)
                    strokeWidth = 0
                } else {
                    setBackgroundColor(Color.WHITE)
                    setTextColor(ContextCompat.getColor(context, R.color.slate_600))
                    strokeColor = ContextCompat.getColorStateList(context, R.color.slate_200)
                }
                
                setOnClickListener {
                    currentFloor = floor
                    updateFloorSelector(floors)
                    lifecycleScope.launch { 
                        val bookings = database.hotelDao().getAllBookings().first()
                        val maint = database.hotelDao().getAllMaintenanceReports().first()
                        updateMap(bookings, maint) 
                    }
                }
            }
            binding.layoutFloorsMap.addView(btn)
        }
    }

    private fun updateMap(bookings: List<HotelBookingEntity>, maintenance: List<HotelMaintenanceEntity>) {
        val floorLayouts = allLayouts.filter { it.floorId == currentFloor }
        binding.mapViewInteractive.layoutElements = floorLayouts.toMutableList()
        
        binding.mapViewInteractive.roomStatuses = allRooms.associate { it.id to it.status }
        binding.mapViewInteractive.roomNames = allRooms.associate { it.id to it.number }
        binding.mapViewInteractive.roomsWithFailures = maintenance.filter { it.status == "PENDING" }.map { it.roomId }.toSet()
        binding.mapViewInteractive.roomsReserved = bookings.filter { it.status == "CONFIRMED" }.map { it.roomId }.toSet()
        
        binding.mapViewInteractive.invalidate()
        
        if (floorLayouts.isEmpty()) {
            binding.layoutEmptyMap.visibility = View.VISIBLE
            binding.mapViewInteractive.visibility = View.GONE
        } else {
            binding.layoutEmptyMap.visibility = View.GONE
            binding.mapViewInteractive.visibility = View.VISIBLE
        }
    }

    private fun showRoomActionDialog(room: HotelRoomEntity, hasFailure: Boolean, hasRes: Boolean) {
        val options = mutableListOf<String>()
        if (room.status == "FREE" && !hasRes) options.add("Check-in Rápido")
        options.add(if (room.status == "FREE") "Solicitar Aseo (Manual)" else "Limpieza Realizada")
        options.add(if (hasFailure) "Marcar como Reparada ✅" else "Reportar Falla 🛠️")
        options.add("Editar Información")
        options.add("Gestionar Habitación")

        val descriptionText = StringBuilder()
        if (!room.description.isNullOrEmpty()) descriptionText.append("<br><small>📝 ${room.description}</small>")
        if (!room.specificDescription.isNullOrEmpty()) descriptionText.append("<br><small>✨ ${room.specificDescription}</small>")

        val titleHtml = "<b>Habitación ${room.number}</b>$descriptionText"

        AlertDialog.Builder(this)
            .setTitle(Html.fromHtml(titleHtml, Html.FROM_HTML_MODE_LEGACY))
            .setItems(options.toTypedArray()) { _, w ->
                when (options[w]) {
                    "Check-in Rápido" -> {
                        val i = Intent(this, HotelCheckInActivity::class.java)
                        i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                        startActivity(i)
                    }
                    "Gestionar Habitación" -> {
                        val intent = Intent(this, HotelManageRoomActivity::class.java)
                        intent.putExtra("ROOM_ID", room.id); intent.putExtra("ROOM_NUMBER", room.number)
                        startActivity(intent)
                    }
                    "Limpieza Realizada" -> {
                        lifecycleScope.launch { 
                            val updatedRoom = room.copy(status = "FREE", lastCleaned = System.currentTimeMillis())
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(this@HotelInteractiveMapActivity).syncHotelRoomToCloud(updatedRoom)
                        }
                    }
                    "Solicitar Aseo (Manual)" -> {
                        lifecycleScope.launch {
                            val updatedRoom = room.copy(lastCleaned = 0L)
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(this@HotelInteractiveMapActivity).syncHotelRoomToCloud(updatedRoom)
                        }
                    }
                    "Reportar Falla 🛠️" -> {
                        val intent = Intent(this, HotelMaintenanceActivity::class.java)
                        intent.putExtra("REPORT_ROOM_ID", room.id)
                        startActivity(intent)
                    }
                    "Editar Información" -> {
                        showEditRoomDialog(room)
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showEditRoomDialog(room: HotelRoomEntity) {
        val dialogBinding = DialogAddRoomBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_GoMezMart_Dialog)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvTitleDialog.text = "Editar Habitación"
        dialogBinding.checkBulk.visibility = View.GONE
        dialogBinding.etRoomNumber.setText(room.number)
        dialogBinding.etRoomFloor.setText(room.floor.toString())
        dialogBinding.etRoomPrice.setText(room.baseRate.toString())
        dialogBinding.etGlobalDescription.setText(room.description)
        dialogBinding.etSpecificDescription.setText(room.specificDescription)
        dialogBinding.btnSaveRoom.text = "Actualizar"

        // Sugerencias de descripciones globales dinámicas
        lifecycleScope.launch {
            val rooms = database.hotelDao().getAllRooms().first()
            val globalDescs = rooms.mapNotNull { it.description }.filter { it.isNotEmpty() }.distinct()
            val adapterDescs = ArrayAdapter(this@HotelInteractiveMapActivity, android.R.layout.simple_dropdown_item_1line, globalDescs)
            dialogBinding.etGlobalDescription.setAdapter(adapterDescs)
        }

        val types = arrayOf("Simple", "Doble", "Matrimonial", "Suite")
        val adapterTypes = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, types)
        dialogBinding.spinnerRoomType.setAdapter(adapterTypes)
        dialogBinding.spinnerRoomType.setText(room.type, false)

        dialogBinding.btnCancelRoom.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSaveRoom.setOnClickListener {
            val num = dialogBinding.etRoomNumber.text.toString()
            val floor = dialogBinding.etRoomFloor.text.toString().toIntOrNull() ?: 1
            val price = dialogBinding.etRoomPrice.text.toString().toDoubleOrNull() ?: 0.0
            val type = dialogBinding.spinnerRoomType.text.toString()
            val globalDesc = dialogBinding.etGlobalDescription.text.toString().trim()
            val specificDesc = dialogBinding.etSpecificDescription.text.toString().trim()

            if (num.isNotEmpty()) {
                lifecycleScope.launch {
                    val updated = room.copy(
                        number = num, 
                        floor = floor, 
                        baseRate = price, 
                        type = type,
                        description = globalDesc,
                        specificDescription = specificDesc
                    )
                    database.hotelDao().updateRoom(updated)
                    SyncManager(this@HotelInteractiveMapActivity).syncHotelRoomToCloud(updated)
                    dialog.dismiss()
                    Toast.makeText(this@HotelInteractiveMapActivity, "Habitación actualizada", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }
}
