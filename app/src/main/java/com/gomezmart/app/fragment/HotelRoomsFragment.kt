package com.gomezmart.app.fragment

import android.content.Context
import com.gomezmart.app.data.HotelRoomLayoutEntity
import com.gomezmart.app.util.HotelMapView
import com.gomezmart.app.R
import com.gomezmart.app.MainActivity
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
import com.google.android.material.chip.Chip
import com.google.android.material.shape.CornerFamily
import com.google.android.material.textfield.TextInputLayout
import com.gomezmart.app.SyncManager
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelRoomEntity
import com.gomezmart.app.adapter.HotelRoomAdapter
import com.gomezmart.app.adapter.RoomListItem
import com.gomezmart.app.adapter.RoomListType
import com.gomezmart.app.databinding.DialogAddRoomBinding
import com.gomezmart.app.databinding.DialogQuickHotelSetupBinding
import com.gomezmart.app.databinding.FragmentHotelRoomsBinding
import com.gomezmart.app.databinding.ItemFloorSetupRowBinding
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
    private val database by lazy { AppDatabase.getDatabase(requireActivity().applicationContext) }
    private lateinit var adapter: HotelRoomAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()
    
    private var allLayouts: List<HotelRoomLayoutEntity> = emptyList()
    private var currentFloor = 1
    
    private var tempRoomPhotoPath: String? = null
    private var pendingPhotoPickerCallback: ((String) -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val savedPath = saveImageToInternalStorage(it) ?: return@let
            pendingPhotoPickerCallback?.invoke(savedPath)
            pendingPhotoPickerCallback = null
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

    private fun hasRoomPhoto(room: HotelRoomEntity): Boolean {
        val hasLocal = !room.photoPath.isNullOrBlank() && File(room.photoPath).exists()
        val hasRemote = !room.photoUrl.isNullOrBlank()
        return hasLocal || hasRemote
    }

    private fun updatePhotoBannerVisibility() {
        val b = _binding ?: return
        val ctx = context ?: return
        val isCollab = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            .getBoolean("is_collaborator_mode", false)
        val isListView = b.toggleViewMode.checkedButtonId != R.id.btnGraphView
        val missingCount = allRooms.count { !hasRoomPhoto(it) }

        if (!isCollab && isListView && allRooms.isNotEmpty() && missingCount > 0) {
            b.cardPhotoReadinessBanner.visibility = View.VISIBLE
            b.tvPhotoBannerTitle.text = if (missingCount == 1) {
                "📸 1 habitación sin foto"
            } else {
                "📸 $missingCount habitaciones sin foto"
            }
            b.tvPhotoBannerSubtitle.text = "Agrégales foto para publicar y destacar en el Mercado"
        } else {
            b.cardPhotoReadinessBanner.visibility = View.GONE
        }
    }

    private fun launchBatchPhotoFill() {
        context ?: return
        val missingRooms = allRooms.filter { !hasRoomPhoto(it) }
        if (missingRooms.isEmpty()) return

        pendingPhotoPickerCallback = picker@{ savedPath ->
            val safeCtx = context ?: return@picker
            val latestMissing = allRooms.filter { !hasRoomPhoto(it) }
            if (latestMissing.isEmpty()) return@picker

            val applyPhotoToRooms: (List<HotelRoomEntity>) -> Unit = { targets ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val updatedList = targets.map {
                        it.copy(photoPath = savedPath, photoUrl = null, isSynced = false)
                    }
                    withContext(Dispatchers.IO) {
                        database.hotelDao().updateRooms(updatedList)
                    }
                    SyncManager(safeCtx.applicationContext).syncHotelRoomsBatchToCloud(updatedList)
                    context?.let { c ->
                        val msg = if (updatedList.size == 1) {
                            "✓ Foto guardada para Habitación ${updatedList.first().number}"
                        } else {
                            "✓ Foto aplicada a ${updatedList.size} habitaciones"
                        }
                        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (latestMissing.size == 1) {
                applyPhotoToRooms(latestMissing)
            } else {
                val first = latestMissing.first()
                val sameType = latestMissing.filter { it.type.equals(first.type, ignoreCase = true) }
                val options = mutableListOf<Pair<String, List<HotelRoomEntity>>>()
                options.add("Solo en Habitación ${first.number}" to listOf(first))
                if (sameType.size > 1 && sameType.size < latestMissing.size) {
                    options.add("En todas las ${first.type} sin foto (${sameType.size})" to sameType)
                }
                options.add("En todas las sin foto (${latestMissing.size})" to latestMissing)

                AlertDialog.Builder(safeCtx)
                    .setTitle("¿Dónde aplicar esta foto?")
                    .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                        applyPhotoToRooms(options[which].second)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        pickImageLauncher.launch("image/*")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelRoomsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Soporte Edge-to-edge (Fondo extendido hasta arriba)
        ViewCompat.setOnApplyWindowInsetsListener(binding.roomsContentRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.layoutRoomsToolbar.setPadding(0, systemBars.top, 0, 0)

            insets
        }
        ViewCompat.requestApplyInsets(binding.roomsContentRoot)

        setupToolbar()
        setupRecyclerView()
        setupViewToggle()
        setupListeners()
        observeRooms()
        observeLayouts()
        checkInitialViewMode()
    }

    private fun checkInitialViewMode() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("HotelPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("FORCE_MAP_VIEW", false)) {
            binding.toggleViewMode.check(R.id.btnGraphView)
            binding.rvRooms.visibility = View.GONE
            binding.layoutEmptyRooms.visibility = View.GONE
            binding.cardPhotoReadinessBanner.visibility = View.GONE
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
                    binding.layoutEmptyRooms.visibility = View.GONE
                    binding.cardPhotoReadinessBanner.visibility = View.GONE
                    binding.layoutGraphView.visibility = View.VISIBLE
                    binding.fabAddRoom.hide()
                } else {
                    val isEmpty = adapter.currentList.isEmpty()
                    binding.rvRooms.visibility = if (isEmpty) View.GONE else View.VISIBLE
                    binding.layoutEmptyRooms.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.layoutGraphView.visibility = View.GONE
                    if (isEmpty) binding.fabAddRoom.hide() else binding.fabAddRoom.show()
                    updatePhotoBannerVisibility()
                }
            }
        }
    }

    private fun setupListeners() {
        binding.fabAddRoom.setOnClickListener { showRoomDialog() }
        binding.btnCreateFirstRoom.setOnClickListener { showRoomDialog() }
        binding.btnQuickSetupHotel.setOnClickListener { showQuickFloorsSetupDialog() }
        binding.btnCompleteMissingPhotos.setOnClickListener { launchBatchPhotoFill() }
        
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val canEditMap = !isCollab || collabPrefs.getBoolean("can_edit_map", false)

        if (isCollab) {
            binding.btnQuickSetupHotel.visibility = View.GONE
        }

        binding.btnOpenEditor.visibility = if (canEditMap) View.VISIBLE else View.GONE
        binding.btnCreateMapEmpty.visibility = if (canEditMap) View.VISIBLE else View.GONE

        binding.btnOpenEditor.setOnClickListener { showFloorSelectionDialog() }
        binding.btnCreateMapEmpty.setOnClickListener { showFloorSelectionDialog() }
        
        binding.mapView.isEditMode = false 
        binding.mapView.onRoomClicked = { roomId ->
            val dbInstance = database
            if (dbInstance != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val room = dbInstance.hotelDao().getRoomById(roomId)
                    val reports = dbInstance.hotelDao().getPendingMaintenanceForRoom(roomId).first()
                    val bookings = dbInstance.hotelDao().getAllBookings().first()
                    val hasRes = bookings.any { it.roomId == roomId && it.status == "CONFIRMED" }
                    room?.let { showRoomActionDialog(it, reports.isNotEmpty(), hasRes) }
                }
            }
        }
    }

    private fun showFloorSelectionDialog() {
        // Combinar pisos de habitaciones y de layouts existentes
        val floorsFromRooms = allRooms.map { it.floor }
        val floorsFromLayouts = allLayouts.map { it.floorId }
        val availableFloors = (floorsFromRooms + floorsFromLayouts).distinct().sorted()
        
        if (availableFloors.isEmpty()) {
            val intent = Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelMapEditorActivity")
            intent.putExtra("FLOOR_ID", 1)
            startActivity(intent)
            return
        }

        val floorNames = availableFloors.map { "Piso $it" }.toTypedArray()
        
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Piso para Editar")
            .setItems(floorNames) { _, which ->
                val selectedFloor = availableFloors[which]
                val intent = Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelMapEditorActivity")
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
        val ctx = context ?: return
        val floors = allRooms.map { it.floor }.distinct().sorted()
        if (floors.isEmpty()) {
            Toast.makeText(ctx, "No hay habitaciones para eliminar", Toast.LENGTH_SHORT).show()
            return
        }

        val floorNames = floors.map { "Piso $it" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Seleccionar Piso a vaciar")
            .setItems(floorNames) { _, which ->
                val floor = floors[which]
                confirmBulkDelete("¿Eliminar TODAS las habitaciones del Piso $floor?") {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val safeCtx = context ?: return@launch
                        val roomsToDelete = allRooms.filter { it.floor == floor }
                        if (roomsToDelete.isNotEmpty()) {
                            val updatedRooms = roomsToDelete.map { it.copy(isDeleted = true) }
                            database.hotelDao().updateRooms(updatedRooms)

                            val sm = SyncManager(safeCtx.applicationContext)
                            updatedRooms.forEach { sm.syncHotelRoomToCloud(it) }
                            Toast.makeText(safeCtx, "Piso $floor vaciado", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.show()
    }

    private fun showDeleteByRangeDialog() {
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val etStart = EditText(ctx).apply { hint = "Desde N° (Ej: 101)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val etEnd = EditText(ctx).apply { hint = "Hasta N° (Ej: 110)"; inputType = InputType.TYPE_CLASS_NUMBER }

        layout.addView(etStart)
        layout.addView(etEnd)

        AlertDialog.Builder(ctx)
            .setTitle("Eliminar por Rango")
            .setView(layout)
            .setPositiveButton("Eliminar") { _, _ ->
                val start = etStart.text.toString().toIntOrNull()
                val end = etEnd.text.toString().toIntOrNull()

                if (start != null && end != null && start <= end) {
                    confirmBulkDelete("¿Eliminar habitaciones desde la $start hasta la $end?") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val safeCtx = context ?: return@launch
                            val roomsToDelete = allRooms.filter {
                                val num = it.number.toIntOrNull()
                                num != null && num in start..end
                            }
                            if (roomsToDelete.isNotEmpty()) {
                                val updatedRooms = roomsToDelete.map { it.copy(isDeleted = true) }
                                database.hotelDao().updateRooms(updatedRooms)

                                val sm = SyncManager(safeCtx.applicationContext)
                                updatedRooms.forEach { sm.syncHotelRoomToCloud(it) }
                                Toast.makeText(safeCtx, "Rango eliminado correctamente", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(ctx, "Rango inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmBulkDelete(msg: String, onConfirm: () -> Unit) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("⚠️ Confirmar Eliminación")
            .setMessage(msg)
            .setPositiveButton("Sí, Eliminar") { _, _ -> onConfirm() }
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
        val ctx = context ?: return
        val b = _binding ?: return
        b.layoutFloors.removeAllViews()
        val sortedFloors = floors.sorted()
        val density = resources.displayMetrics.density

        for (floor in sortedFloors) {
            val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.sky_600))
                    setTextColor(Color.WHITE)
                    strokeWidth = 0
                } else {
                    setBackgroundColor(Color.WHITE)
                    setTextColor(ContextCompat.getColor(ctx, R.color.slate_600))
                    strokeColor = ContextCompat.getColorStateList(ctx, R.color.slate_200)
                }

                setOnClickListener {
                    currentFloor = floor
                    updateMapForCurrentFloor()
                    updateFloorSelector(floors)
                }
            }
            b.layoutFloors.addView(btn)
        }
    }

    private fun observeRooms() {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                database.hotelDao().getAllRooms(),
                database.hotelDao().getAllMaintenanceReports(),
                database.hotelDao().getAllBookings()
            ) { rooms, maintenance, bookings ->
                Triple(rooms, maintenance, bookings)
            }.collectLatest { (rooms, maintenance, bookings) ->

                // Procesamiento de datos para UI
                val processedData = withContext(Dispatchers.Default) {
                    val pendingMaintMap = maintenance.filter { it.status == "PENDING" }.groupBy { it.roomId }
                    val reservationsMap = bookings.filter { it.status == "CONFIRMED" }.groupBy { it.roomId }
                    val activeBookingsMap = bookings.filter { it.status == "CHECKED_IN" }.associateBy { it.roomId }

                    val groupedList = mutableListOf<RoomListType>()
                    rooms.groupBy { it.floor }.toSortedMap().forEach { (floor, roomsInFloor) ->
                        groupedList.add(RoomListType.Header(floor))
                        roomsInFloor.forEach { room ->
                            val hasFailure = pendingMaintMap.containsKey(room.id)
                            val hasRes = reservationsMap.containsKey(room.id)
                            val activeBooking = activeBookingsMap[room.id]
                            val pendingDebt = if (activeBooking != null) (activeBooking.totalAmount - activeBooking.deposit).coerceAtLeast(0.0) else 0.0
                            groupedList.add(
                                RoomListType.Room(
                                    RoomListItem(room, hasFailure, hasRes, pendingDebt = pendingDebt)
                                )
                            )
                        }
                    }

                    val statusMap = rooms.associate { it.id to it.status }
                    val nameMap = rooms.associate { it.id to it.number }

                    Quadruple(groupedList, statusMap, nameMap, Pair(pendingMaintMap.keys, reservationsMap.keys))
                }

                _binding?.let { b ->
                    allRooms = rooms
                    adapter.submitList(processedData.first) {
                        if (_binding != null) {
                            b.rvRooms.post {
                                if (_binding != null && b.rvRooms.childCount == 0 && adapter.itemCount > 0) {
                                    b.rvRooms.scrollBy(0, 1)
                                    b.rvRooms.scrollBy(0, -1)
                                }
                            }
                        }
                    }

                    b.rvRooms.post {
                        if (_binding != null && b.rvRooms.childCount == 0 && adapter.itemCount > 0) {
                            b.rvRooms.scrollBy(0, 1)
                            b.rvRooms.scrollBy(0, -1)
                        }
                    }

                    val isEmpty = processedData.first.isEmpty()
                    val isListView = b.toggleViewMode.checkedButtonId != R.id.btnGraphView
                    b.layoutEmptyRooms.visibility = if (isEmpty && isListView) View.VISIBLE else View.GONE
                    b.rvRooms.visibility = if (isEmpty || !isListView) View.GONE else View.VISIBLE
                    if (isListView) {
                        if (isEmpty) b.fabAddRoom.hide() else b.fabAddRoom.show()
                    }
                    updatePhotoBannerVisibility()

                    b.mapView.roomStatuses = processedData.second
                    b.mapView.roomNames = processedData.third
                    b.mapView.roomsWithFailures = processedData.fourth.first
                    b.mapView.roomsReserved = processedData.fourth.second

                    b.mapView.invalidate()
                }

                // No se marcan habitaciones libres como "DIRTY" automáticamente cada día.
                // Si alguna habitación libre quedó con status DIRTY sin huésped activo por la lógica anterior,
                // la restauramos a FREE para que figure como disponible de inmediato.
                withContext(Dispatchers.IO) {
                    val occupiedIds = bookings.filter { it.status == "CONFIRMED" }.map { it.roomId }.toSet()
                    val autoMarkedDirty = rooms.filter { it.status == "DIRTY" && !occupiedIds.contains(it.id) && it.lastCleaned > 0L }
                    if (autoMarkedDirty.isNotEmpty()) {
                        val restoredList = autoMarkedDirty.map { it.copy(status = "FREE") }
                        database.hotelDao().updateRooms(restoredList)
                        val sm = SyncManager(appContext)
                        restoredList.forEach { sm.syncHotelRoomToCloud(it) }
                    }
                }
            }
        }
    }

    private fun formatRateText(rate: Double): String {
        return if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()
    }

    private fun suggestNextRoomNumber(floor: Int, extraTaken: Set<String> = emptySet()): String {
        val taken = (allRooms.map { it.number } + extraTaken).toSet()
        val floorNumericMax = allRooms
            .filter { it.floor == floor }
            .mapNotNull { it.number.toIntOrNull() }
            .maxOrNull()

        var candidate = if (floorNumericMax != null) floorNumericMax + 1 else floor * 100 + 1
        while (taken.contains(candidate.toString())) {
            candidate++
        }
        return candidate.toString()
    }

    private fun suggestRateForType(type: String): Double? {
        return allRooms
            .lastOrNull { it.type.equals(type, ignoreCase = true) && it.baseRate > 0.0 }
            ?.baseRate
    }

    private fun showRoomDialog(roomToEdit: HotelRoomEntity? = null) {
        val ctx = context ?: return
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)

        val dialogBinding = DialogAddRoomBinding.inflate(layoutInflater)
        tempRoomPhotoPath = roomToEdit?.photoPath
        var photoCleared = false

        val sessionAddedNumbers = mutableSetOf<String>()
        var selectedFloor = (roomToEdit?.floor ?: currentFloor).coerceAtLeast(1)
        var userEditedNumber = roomToEdit != null
        var userEditedPrice = roomToEdit != null
        var isProgrammaticNumberChange = false
        var isProgrammaticPriceChange = false

        val setRoomNumberProgrammatic = { text: String ->
            isProgrammaticNumberChange = true
            dialogBinding.etRoomNumber.setText(text)
            dialogBinding.etRoomNumber.setSelection(text.length)
            isProgrammaticNumberChange = false
        }

        val setPriceProgrammatic = { text: String, helper: String? ->
            isProgrammaticPriceChange = true
            dialogBinding.etRoomPrice.setText(text)
            dialogBinding.layoutRoomPrice.helperText = helper
            isProgrammaticPriceChange = false
        }

        val getSelectedType = {
            when (dialogBinding.chipGroupRoomType.checkedChipId) {
                R.id.chipTypeDouble -> "Doble"
                R.id.chipTypeMatrimonial -> "Matrimonial"
                R.id.chipTypeSuite -> "Suite"
                else -> "Simple"
            }
        }

        val updateDialogPhotoUi = {
            val localFile = tempRoomPhotoPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            val remoteUrl = if (!photoCleared) roomToEdit?.photoUrl?.takeIf { it.isNotBlank() } else null
            val photoSource: Any? = localFile ?: remoteUrl

            if (photoSource != null) {
                dialogBinding.ivRoomPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                dialogBinding.ivRoomPhoto.setPadding(0, 0, 0, 0)
                dialogBinding.ivRoomPhoto.imageTintList = null
                Glide.with(this).load(photoSource).centerCrop().into(dialogBinding.ivRoomPhoto)
                dialogBinding.tvPhotoTitle.text = "Foto lista ✓"
                dialogBinding.tvPhotoHint.text = "Visible para tus clientes en el Mercado"
                dialogBinding.tvPhotoActionBtn.text = "Cambiar"
                dialogBinding.btnClearRoomPhoto.visibility = View.VISIBLE
                dialogBinding.cardRoomPhoto.strokeColor = ContextCompat.getColor(ctx, R.color.emerald_200)
            } else {
                Glide.with(this).clear(dialogBinding.ivRoomPhoto)
                dialogBinding.ivRoomPhoto.setImageResource(android.R.drawable.ic_menu_camera)
                dialogBinding.ivRoomPhoto.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (13 * resources.displayMetrics.density).toInt()
                dialogBinding.ivRoomPhoto.setPadding(pad, pad, pad, pad)
                dialogBinding.ivRoomPhoto.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.purple_600))
                val isRangeMode = dialogBinding.toggleAddMode.checkedButtonId == R.id.btnModeRange && roomToEdit == null
                dialogBinding.tvPhotoTitle.text = if (isRangeMode) "Foto para este lote" else "Foto de la habitación"
                dialogBinding.tvPhotoHint.text = "Recomendada para mostrarla en el Mercado"
                dialogBinding.tvPhotoActionBtn.text = "Subir"
                dialogBinding.btnClearRoomPhoto.visibility = View.GONE
                dialogBinding.cardRoomPhoto.strokeColor = ContextCompat.getColor(ctx, R.color.slate_200)
            }
        }

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_Gomezmart_Dialog)
            .setView(dialogBinding.root)
            .create()

        // Configurar estado inicial (Crear vs Editar vs Colaborador)
        if (roomToEdit != null) {
            dialogBinding.tvTitleDialog.text = "Editar Habitación"
            dialogBinding.tvSubtitleDialog.text = "Actualiza los detalles y foto de esta habitación"
            dialogBinding.cardToggleAddMode.visibility = View.GONE
            dialogBinding.btnSaveAndNext.visibility = View.GONE
            dialogBinding.btnSaveRoom.text = "Actualizar"
            setRoomNumberProgrammatic(roomToEdit.number)
            setPriceProgrammatic(formatRateText(roomToEdit.baseRate), null)
            dialogBinding.etGlobalDescription.setText(roomToEdit.description ?: "")
            dialogBinding.etSpecificDescription.setText(roomToEdit.specificDescription ?: "")

            val chipId = when {
                roomToEdit.type.equals("Doble", ignoreCase = true) || roomToEdit.type.equals("DOUBLE", ignoreCase = true) -> R.id.chipTypeDouble
                roomToEdit.type.equals("Matrimonial", ignoreCase = true) -> R.id.chipTypeMatrimonial
                roomToEdit.type.equals("Suite", ignoreCase = true) -> R.id.chipTypeSuite
                else -> R.id.chipTypeSimple
            }
            dialogBinding.chipGroupRoomType.check(chipId)
        } else {
            dialogBinding.btnSaveRoom.text = "Guardar"
            setRoomNumberProgrammatic(suggestNextRoomNumber(selectedFloor))
            suggestRateForType(getSelectedType())?.let { suggested ->
                setPriceProgrammatic(formatRateText(suggested), "Tarifa sugerida según tus habitaciones ${getSelectedType()}")
            }
        }
        updateDialogPhotoUi()

        if (isCollab) {
            dialogBinding.etRoomNumber.isEnabled = false
            dialogBinding.btnFloorMinus.isEnabled = false
            dialogBinding.btnFloorPlus.isEnabled = false
            dialogBinding.etRoomPrice.isEnabled = false
            dialogBinding.scrollQuickPrices.visibility = View.GONE
            dialogBinding.cardToggleAddMode.visibility = View.GONE
            dialogBinding.btnSaveAndNext.visibility = View.GONE
            dialogBinding.cardRoomPhoto.visibility = View.GONE
        }

        // Stepper de Piso
        val updateFloorUi = {
            dialogBinding.tvFloorValue.text = selectedFloor.toString()
            dialogBinding.tvFloorRangeExample.text = "Ej: ${selectedFloor}01 al ${selectedFloor}09"
            if (roomToEdit == null) {
                val isRangeMode = dialogBinding.toggleAddMode.checkedButtonId == R.id.btnModeRange
                if (isRangeMode) {
                    val nextStart = suggestNextRoomNumber(selectedFloor, sessionAddedNumbers)
                    dialogBinding.etRoomStart.setText(nextStart)
                    val endDefault = (nextStart.toIntOrNull()?.plus(4))?.toString() ?: ""
                    if (dialogBinding.etRoomEnd.text.isNullOrBlank()) {
                        dialogBinding.etRoomEnd.setText(endDefault)
                    }
                } else if (!userEditedNumber) {
                    setRoomNumberProgrammatic(suggestNextRoomNumber(selectedFloor, sessionAddedNumbers))
                }
            }
        }
        dialogBinding.tvFloorValue.text = selectedFloor.toString()
        dialogBinding.tvFloorRangeExample.text = "Ej: ${selectedFloor}01 al ${selectedFloor}09"

        dialogBinding.btnFloorMinus.setOnClickListener {
            if (selectedFloor > 1) {
                selectedFloor--
                updateFloorUi()
            }
        }
        dialogBinding.btnFloorPlus.setOnClickListener {
            if (selectedFloor < 50) {
                selectedFloor++
                updateFloorUi()
            }
        }

        // Preview en tiempo real para Modo Rango
        val updateRangePreview = {
            val startNum = dialogBinding.etRoomStart.text.toString().toIntOrNull()
            val endNum = dialogBinding.etRoomEnd.text.toString().toIntOrNull()
            if (startNum == null || endNum == null) {
                dialogBinding.tvRangePreview.text = "Ingresa el rango inicial y final"
                dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.purple_50))
                dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.purple_700))
            } else if (startNum > endNum) {
                dialogBinding.tvRangePreview.text = "El número inicial debe ser menor o igual al final"
                dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.red_50))
                dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.red_600))
            } else {
                val total = endNum - startNum + 1
                if (total > 60) {
                    dialogBinding.tvRangePreview.text = "Máximo 60 habitaciones por lote (o usa Configurar por Pisos)"
                    dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.red_50))
                    dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.red_600))
                } else {
                    val existingSet = (allRooms.map { it.number } + sessionAddedNumbers).toSet()
                    val existingCount = (startNum..endNum).count { existingSet.contains(it.toString()) }
                    val newCount = total - existingCount
                    if (newCount == 0) {
                        dialogBinding.tvRangePreview.text = "Todas las habitaciones del $startNum al $endNum ya existen"
                        dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.amber_50))
                        dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.amber_700))
                    } else if (existingCount > 0) {
                        dialogBinding.tvRangePreview.text = "Se crearán $newCount nuevas en Piso $selectedFloor ($existingCount ya existen y se omitirán)"
                        dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.amber_50))
                        dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.amber_700))
                    } else {
                        dialogBinding.tvRangePreview.text = "✓ Se crearán $newCount habitaciones ($startNum – $endNum) en Piso $selectedFloor"
                        dialogBinding.tvRangePreview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.emerald_50))
                        dialogBinding.tvRangePreview.setTextColor(ContextCompat.getColor(ctx, R.color.emerald_700))
                    }
                }
            }
        }

        // Toggle 1 Habitación vs Varias (Rango)
        dialogBinding.toggleAddMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isRange = checkedId == R.id.btnModeRange
            dialogBinding.layoutRoomNumber.visibility = if (isRange) View.GONE else View.VISIBLE
            dialogBinding.layoutFloorRangeHint.visibility = if (isRange) View.VISIBLE else View.GONE
            dialogBinding.layoutRoomBulk.visibility = if (isRange) View.VISIBLE else View.GONE
            dialogBinding.btnSaveAndNext.visibility = if (isRange || roomToEdit != null || isCollab) View.GONE else View.VISIBLE
            dialogBinding.btnSaveRoom.text = if (isRange) "Crear Lote" else if (roomToEdit != null) "Actualizar" else "Guardar"

            if (isRange) {
                if (dialogBinding.etRoomStart.text.isNullOrBlank()) {
                    val startSuggestion = suggestNextRoomNumber(selectedFloor, sessionAddedNumbers)
                    dialogBinding.etRoomStart.setText(startSuggestion)
                    if (dialogBinding.etRoomEnd.text.isNullOrBlank()) {
                        startSuggestion.toIntOrNull()?.let { s ->
                            dialogBinding.etRoomEnd.setText((s + 4).toString())
                        }
                    }
                }
                updateRangePreview()
            }
            updateDialogPhotoUi()
        }

        dialogBinding.btnOpenFloorWizardFromDialog.setOnClickListener {
            dialog.dismiss()
            showQuickFloorsSetupDialog()
        }

        // ChipGroup Tipo de Habitación -> auto-sugerir precio si no fue editado manualmente
        dialogBinding.chipGroupRoomType.setOnCheckedStateChangeListener { _, _ ->
            if (!userEditedPrice && roomToEdit == null) {
                val chosenType = getSelectedType()
                val suggested = suggestRateForType(chosenType)
                if (suggested != null) {
                    setPriceProgrammatic(formatRateText(suggested), "Tarifa sugerida según tus habitaciones $chosenType")
                } else {
                    setPriceProgrammatic("", null)
                }
            }
        }

        // Chips de precios rápidos
        if (!isCollab) {
            val existingRates = allRooms.map { it.baseRate }.filter { it > 0.0 }.distinct().sorted()
            val defaultRates = listOf(40.0, 50.0, 70.0, 100.0, 120.0)
            val quickRates = (existingRates + defaultRates).distinct().take(5)
            val bgColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.slate_100))
            val strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.slate_200))
            val textColor = ContextCompat.getColor(ctx, R.color.slate_700)
            quickRates.forEach { rate ->
                val chip = Chip(ctx).apply {
                    text = "S/ ${formatRateText(rate)}"
                    isCheckable = false
                    chipBackgroundColor = bgColor
                    chipStrokeColor = strokeColor
                    chipStrokeWidth = resources.displayMetrics.density
                    setTextColor(textColor)
                    textSize = 12f
                    setOnClickListener {
                        userEditedPrice = true
                        setPriceProgrammatic(formatRateText(rate), null)
                    }
                }
                dialogBinding.chipGroupQuickPrices.addView(chip)
            }
        }

        // Sección Opcional Colapsable (Comodidades y Detalles)
        val hasOptionalData = roomToEdit != null && (
            !roomToEdit.description.isNullOrBlank() ||
            !roomToEdit.specificDescription.isNullOrBlank()
        )
        dialogBinding.layoutOptionalContent.visibility = if (hasOptionalData) View.VISIBLE else View.GONE
        dialogBinding.tvOptionalToggleAction.text = if (hasOptionalData) "Ocultar" else "Agregar +"
        dialogBinding.cardOptionalHeader.setOnClickListener {
            val showing = dialogBinding.layoutOptionalContent.visibility == View.VISIBLE
            dialogBinding.layoutOptionalContent.visibility = if (showing) View.GONE else View.VISIBLE
            dialogBinding.tvOptionalToggleAction.text = if (showing) "Agregar +" else "Ocultar"
        }

        dialogBinding.cardRoomPhoto.setOnClickListener {
            pendingPhotoPickerCallback = { savedPath ->
                tempRoomPhotoPath = savedPath
                photoCleared = false
                updateDialogPhotoUi()
            }
            pickImageLauncher.launch("image/*")
        }
        dialogBinding.btnClearRoomPhoto.setOnClickListener {
            tempRoomPhotoPath = null
            photoCleared = true
            updateDialogPhotoUi()
        }

        // Comodidades rápidas sincronizadas con Descripción Global
        val amenityChips = listOf(
            dialogBinding.chipAmenityWifi,
            dialogBinding.chipAmenityTv,
            dialogBinding.chipAmenityBath,
            dialogBinding.chipAmenityHotWater,
            dialogBinding.chipAmenityAc,
            dialogBinding.chipAmenityBalcony
        )
        val initialGlobal = roomToEdit?.description ?: ""
        amenityChips.forEach { chip ->
            val label = chip.text.toString()
            chip.isChecked = initialGlobal.contains(label, ignoreCase = true)
            chip.setOnCheckedChangeListener { _, isChecked ->
                val currentItems = dialogBinding.etGlobalDescription.text.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableList()
                if (isChecked) {
                    if (currentItems.none { it.equals(label, ignoreCase = true) }) {
                        currentItems.add(label)
                    }
                } else {
                    currentItems.removeAll { it.equals(label, ignoreCase = true) }
                }
                dialogBinding.etGlobalDescription.setText(currentItems.joinToString(", "), false)
            }
        }

        val globalDescs = allRooms.mapNotNull { it.description }.filter { it.isNotEmpty() }.distinct()
        val adapterDescs = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, globalDescs)
        dialogBinding.etGlobalDescription.setAdapter(adapterDescs)

        // Validación en tiempo real para N° Habitación
        val validateRoom = { input: String, layout: TextInputLayout ->
            val safeCtx = context
            if (safeCtx != null) {
                val trimmed = input.trim()
                if (trimmed.isEmpty()) {
                    layout.error = null
                    layout.helperText = null
                    layout.setBoxStrokeColorStateList(ColorStateList.valueOf(ContextCompat.getColor(safeCtx, R.color.slate_200)))
                } else if (sessionAddedNumbers.contains(trimmed)) {
                    layout.error = "Ya registrada en esta sesión"
                    layout.helperText = null
                    layout.setBoxStrokeColorStateList(ColorStateList.valueOf(ContextCompat.getColor(safeCtx, R.color.red_600)))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val exists = withContext(Dispatchers.IO) {
                            val r = database.hotelDao().getRoomByNumber(trimmed)
                            r != null && r.id != roomToEdit?.id
                        }
                        val c = context ?: return@launch
                        if (exists) {
                            layout.error = "Esta habitación ya existe"
                            layout.helperText = null
                            layout.setBoxStrokeColorStateList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.red_600)))
                        } else {
                            layout.error = null
                            layout.helperText = "Disponible ✓"
                            layout.setHelperTextColor(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.emerald_600)))
                            layout.setBoxStrokeColorStateList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.emerald_600)))
                        }
                    }
                }
            }
        }

        dialogBinding.etRoomNumber.addTextChangedListener {
            if (!isProgrammaticNumberChange) userEditedNumber = true
            validateRoom(it.toString(), dialogBinding.layoutRoomNumber)
        }
        dialogBinding.etRoomPrice.addTextChangedListener {
            if (!isProgrammaticPriceChange) {
                userEditedPrice = true
                dialogBinding.layoutRoomPrice.helperText = null
            }
        }
        dialogBinding.etRoomStart.addTextChangedListener { updateRangePreview() }
        dialogBinding.etRoomEnd.addTextChangedListener { updateRangePreview() }

        // Validación inicial del número sugerido
        validateRoom(dialogBinding.etRoomNumber.text.toString(), dialogBinding.layoutRoomNumber)

        dialogBinding.btnCancelRoom.setOnClickListener { dialog.dismiss() }

        val saveSingleRoom = { keepOpenForNext: Boolean ->
            val safeCtx = context
            if (safeCtx != null) {
                val numberStr = dialogBinding.etRoomNumber.text.toString().trim()
                val type = getSelectedType()
                val price = dialogBinding.etRoomPrice.text.toString().toDoubleOrNull() ?: 0.0
                val globalDesc = dialogBinding.etGlobalDescription.text.toString().trim()
                val specificDesc = dialogBinding.etSpecificDescription.text.toString().trim()

                if (numberStr.isEmpty()) {
                    dialogBinding.layoutRoomNumber.error = "Ingresa el número de habitación"
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val exists = sessionAddedNumbers.contains(numberStr) || withContext(Dispatchers.IO) {
                            val r = database.hotelDao().getRoomByNumber(numberStr)
                            r != null && r.id != roomToEdit?.id
                        }
                        if (exists) {
                            dialogBinding.layoutRoomNumber.error = "Esta habitación ya existe"
                            return@launch
                        }

                        val sm = SyncManager(safeCtx.applicationContext)
                        if (roomToEdit != null) {
                            val updatedRoom = roomToEdit.copy(
                                number = numberStr,
                                floor = selectedFloor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = if (photoCleared) null else tempRoomPhotoPath,
                                photoUrl = if (photoCleared || tempRoomPhotoPath != roomToEdit.photoPath) null else roomToEdit.photoUrl,
                                isSynced = false
                            )
                            database.hotelDao().updateRoom(updatedRoom)
                            sm.syncHotelRoomToCloud(updatedRoom)
                            context?.let { c -> Toast.makeText(c, "Habitación actualizada", Toast.LENGTH_SHORT).show() }
                            dialog.dismiss()
                        } else {
                            val room = HotelRoomEntity(
                                number = numberStr,
                                floor = selectedFloor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = tempRoomPhotoPath
                            )
                            database.hotelDao().insertRoom(room)
                            sm.syncHotelRoomToCloud(room)
                            sessionAddedNumbers.add(numberStr)

                            if (keepOpenForNext) {
                                val nextNum = suggestNextRoomNumber(selectedFloor, sessionAddedNumbers)
                                userEditedNumber = false
                                setRoomNumberProgrammatic(nextNum)
                                dialogBinding.etSpecificDescription.setText("")
                                updateDialogPhotoUi()
                                if (tempRoomPhotoPath != null) {
                                    dialogBinding.tvPhotoHint.text = "Misma foto lista · Toca para cambiar o × quitar"
                                }
                                dialogBinding.tvSaveFeedbackBanner.text = "✓ Habitación $numberStr guardada · Siguiente: $nextNum"
                                dialogBinding.tvSaveFeedbackBanner.visibility = View.VISIBLE
                                validateRoom(nextNum, dialogBinding.layoutRoomNumber)
                            } else {
                                context?.let { c -> Toast.makeText(c, "Habitación $numberStr guardada ✓", Toast.LENGTH_SHORT).show() }
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }

        dialogBinding.btnSaveAndNext.setOnClickListener { saveSingleRoom(true) }

        dialogBinding.btnSaveRoom.setOnClickListener {
            val safeCtx = context ?: return@setOnClickListener
            val isRange = dialogBinding.toggleAddMode.checkedButtonId == R.id.btnModeRange && roomToEdit == null

            if (!isRange) {
                saveSingleRoom(false)
            } else {
                val startNum = dialogBinding.etRoomStart.text.toString().toIntOrNull()
                val endNum = dialogBinding.etRoomEnd.text.toString().toIntOrNull()
                val type = getSelectedType()
                val price = dialogBinding.etRoomPrice.text.toString().toDoubleOrNull() ?: 0.0
                val globalDesc = dialogBinding.etGlobalDescription.text.toString().trim()
                val specificDesc = dialogBinding.etSpecificDescription.text.toString().trim()

                if (startNum == null || endNum == null || startNum > endNum) {
                    Toast.makeText(safeCtx, "Ingresa un rango válido (Inicio ≤ Final)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val total = endNum - startNum + 1
                if (total > 60) {
                    Toast.makeText(safeCtx, "Máximo 60 habitaciones por lote", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val numbersToCreate = withContext(Dispatchers.IO) {
                        (startNum..endNum)
                            .map { it.toString() }
                            .filter { numStr ->
                                !sessionAddedNumbers.contains(numStr) &&
                                database.hotelDao().getRoomByNumber(numStr) == null
                            }
                    }

                    if (numbersToCreate.isEmpty()) {
                        context?.let { c -> Toast.makeText(c, "Todas las habitaciones del rango ya existen", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }

                    val createdBatch = withContext(Dispatchers.IO) {
                        val list = mutableListOf<HotelRoomEntity>()
                        numbersToCreate.forEach { numStr ->
                            val room = HotelRoomEntity(
                                number = numStr,
                                floor = selectedFloor,
                                type = type,
                                baseRate = price,
                                description = globalDesc,
                                specificDescription = specificDesc,
                                photoPath = tempRoomPhotoPath
                            )
                            database.hotelDao().insertRoom(room)
                            list.add(room)
                        }
                        list
                    }
                    SyncManager(safeCtx.applicationContext).syncHotelRoomsBatchToCloud(createdBatch)
                    context?.let { c ->
                        Toast.makeText(c, "✓ Creadas ${createdBatch.size} habitaciones en Piso $selectedFloor", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private data class FloorSetupSpec(
        var floor: Int,
        var count: Int,
        var startNumber: Int,
        var type: String,
        var price: String,
        var customStartEdited: Boolean = false,
        var customPriceEdited: Boolean = false,
        var photoPath: String? = null
    )

    private fun showQuickFloorsSetupDialog() {
        val ctx = context ?: return
        val wizardBinding = DialogQuickHotelSetupBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(ctx, R.style.Theme_Gomezmart_Dialog)
            .setView(wizardBinding.root)
            .create()

        val defaultPrice = allRooms.lastOrNull { it.baseRate > 0.0 }?.baseRate?.let { formatRateText(it) } ?: "50"
        val specs = mutableListOf<FloorSetupSpec>()
        if (allRooms.isEmpty()) {
            specs.add(FloorSetupSpec(floor = 1, count = 5, startNumber = 101, type = "Simple", price = defaultPrice))
            specs.add(FloorSetupSpec(floor = 2, count = 9, startNumber = 201, type = "Simple", price = defaultPrice))
        } else {
            val nextFloor = (allRooms.maxOfOrNull { it.floor } ?: 0) + 1
            specs.add(FloorSetupSpec(floor = nextFloor, count = 5, startNumber = nextFloor * 100 + 1, type = "Simple", price = defaultPrice))
        }

        val existingNumbers = allRooms.map { it.number }.toSet()

        val refreshWizardSummary = {
            val plannedNumbers = mutableSetOf<String>()
            var newRoomsTotal = 0
            var skippedExisting = 0
            val parts = mutableListOf<String>()

            specs.forEach { spec ->
                val count = spec.count.coerceIn(0, 60)
                var createdInFloor = 0
                for (i in 0 until count) {
                    val numStr = (spec.startNumber + i).toString()
                    if (existingNumbers.contains(numStr) || plannedNumbers.contains(numStr)) {
                        skippedExisting++
                    } else {
                        plannedNumbers.add(numStr)
                        newRoomsTotal++
                        createdInFloor++
                    }
                }
                if (count > 0) {
                    parts.add("P${spec.floor}: $createdInFloor")
                }
            }

            if (newRoomsTotal == 0) {
                wizardBinding.tvWizardSummary.text = if (skippedExisting > 0) {
                    "⚠️ Todas las habitaciones indicadas ya existen"
                } else {
                    "Define al menos 1 habitación en algún piso"
                }
                wizardBinding.tvWizardSummary.setBackgroundColor(ContextCompat.getColor(ctx, R.color.amber_50))
                wizardBinding.tvWizardSummary.setTextColor(ContextCompat.getColor(ctx, R.color.amber_700))
                wizardBinding.btnCreateAllFloors.isEnabled = false
                wizardBinding.btnCreateAllFloors.text = "Crear"
            } else {
                val skipNote = if (skippedExisting > 0) " · $skippedExisting omitidas" else ""
                wizardBinding.tvWizardSummary.text = "Total: $newRoomsTotal habitaciones (${parts.joinToString(" · ")})$skipNote"
                wizardBinding.tvWizardSummary.setBackgroundColor(ContextCompat.getColor(ctx, R.color.purple_50))
                wizardBinding.tvWizardSummary.setTextColor(ContextCompat.getColor(ctx, R.color.purple_700))
                wizardBinding.btnCreateAllFloors.isEnabled = true
                wizardBinding.btnCreateAllFloors.text = "Crear $newRoomsTotal Habitaciones"
            }
        }

        lateinit var renderFloorRows: () -> Unit
        renderFloorRows = {
            wizardBinding.layoutFloorRows.removeAllViews()
            specs.forEachIndexed { index, spec ->
                val rowBinding = ItemFloorSetupRowBinding.inflate(layoutInflater, wizardBinding.layoutFloorRows, false)
                var updatingRow = false

                val updateRowBadge = {
                    val endNum = if (spec.count > 0) spec.startNumber + spec.count - 1 else spec.startNumber
                    rowBinding.tvRowRangeBadge.text = if (spec.count > 0) "${spec.startNumber} – $endNum" else "0 hab."
                }

                val updateRowPhotoUi = {
                    val localFile = spec.photoPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    if (localFile != null) {
                        rowBinding.ivRowFloorPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                        rowBinding.ivRowFloorPhoto.setPadding(0, 0, 0, 0)
                        rowBinding.ivRowFloorPhoto.imageTintList = null
                        Glide.with(this).load(localFile).centerCrop().into(rowBinding.ivRowFloorPhoto)
                        rowBinding.tvRowPhotoTitle.text = "✓ Foto asignada al Piso ${spec.floor}"
                        rowBinding.tvRowPhotoHint.text = "Se aplicará a estas ${spec.count} habitaciones"
                        rowBinding.tvRowPhotoAction.text = "Cambiar"
                        rowBinding.btnRowClearPhoto.visibility = View.VISIBLE
                        rowBinding.cardRowPhoto.strokeColor = ContextCompat.getColor(ctx, R.color.emerald_200)
                    } else {
                        Glide.with(this).clear(rowBinding.ivRowFloorPhoto)
                        rowBinding.ivRowFloorPhoto.setImageResource(android.R.drawable.ic_menu_camera)
                        rowBinding.ivRowFloorPhoto.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        val pad = (8 * resources.displayMetrics.density).toInt()
                        rowBinding.ivRowFloorPhoto.setPadding(pad, pad, pad, pad)
                        rowBinding.ivRowFloorPhoto.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.purple_600))
                        rowBinding.tvRowPhotoTitle.text = "Foto del Piso ${spec.floor} (para el Mercado)"
                        rowBinding.tvRowPhotoHint.text = "Recomendada · Se aplicará a estas ${spec.count} hab."
                        rowBinding.tvRowPhotoAction.text = "Subir"
                        rowBinding.btnRowClearPhoto.visibility = View.GONE
                        rowBinding.cardRowPhoto.strokeColor = ContextCompat.getColor(ctx, R.color.slate_200)
                    }
                }

                updatingRow = true
                rowBinding.tvRowFloorNumber.text = spec.floor.toString()
                rowBinding.etRowRoomCount.setText(spec.count.toString())
                rowBinding.etRowStartNumber.setText(spec.startNumber.toString())
                rowBinding.etRowPrice.setText(spec.price)
                val typeChipId = when (spec.type) {
                    "Doble" -> R.id.chipRowDouble
                    "Matrimonial" -> R.id.chipRowMatrimonial
                    "Suite" -> R.id.chipRowSuite
                    else -> R.id.chipRowSimple
                }
                rowBinding.chipGroupRowType.check(typeChipId)
                updateRowBadge()
                updateRowPhotoUi()
                updatingRow = false

                rowBinding.cardRowPhoto.setOnClickListener {
                    pendingPhotoPickerCallback = { savedPath ->
                        spec.photoPath = savedPath
                        updateRowPhotoUi()
                    }
                    pickImageLauncher.launch("image/*")
                }

                rowBinding.btnRowClearPhoto.setOnClickListener {
                    spec.photoPath = null
                    updateRowPhotoUi()
                }

                rowBinding.btnRemoveFloorRow.visibility = if (specs.size > 1) View.VISIBLE else View.GONE
                rowBinding.btnRemoveFloorRow.setOnClickListener {
                    if (specs.size > 1 && index in specs.indices) {
                        specs.removeAt(index)
                        renderFloorRows()
                    }
                }

                rowBinding.btnRowFloorMinus.setOnClickListener {
                    if (spec.floor > 1) {
                        spec.floor--
                        updatingRow = true
                        rowBinding.tvRowFloorNumber.text = spec.floor.toString()
                        if (!spec.customStartEdited) {
                            spec.startNumber = spec.floor * 100 + 1
                            rowBinding.etRowStartNumber.setText(spec.startNumber.toString())
                        }
                        updatingRow = false
                        updateRowBadge()
                        updateRowPhotoUi()
                        refreshWizardSummary()
                    }
                }

                rowBinding.btnRowFloorPlus.setOnClickListener {
                    if (spec.floor < 50) {
                        spec.floor++
                        updatingRow = true
                        rowBinding.tvRowFloorNumber.text = spec.floor.toString()
                        if (!spec.customStartEdited) {
                            spec.startNumber = spec.floor * 100 + 1
                            rowBinding.etRowStartNumber.setText(spec.startNumber.toString())
                        }
                        updatingRow = false
                        updateRowBadge()
                        updateRowPhotoUi()
                        refreshWizardSummary()
                    }
                }

                rowBinding.btnRowCountMinus.setOnClickListener {
                    if (spec.count > 1) {
                        spec.count--
                        updatingRow = true
                        rowBinding.etRowRoomCount.setText(spec.count.toString())
                        updatingRow = false
                        updateRowBadge()
                        updateRowPhotoUi()
                        refreshWizardSummary()
                    }
                }

                rowBinding.btnRowCountPlus.setOnClickListener {
                    if (spec.count < 60) {
                        spec.count++
                        updatingRow = true
                        rowBinding.etRowRoomCount.setText(spec.count.toString())
                        updatingRow = false
                        updateRowBadge()
                        updateRowPhotoUi()
                        refreshWizardSummary()
                    }
                }

                rowBinding.etRowRoomCount.addTextChangedListener {
                    if (updatingRow) return@addTextChangedListener
                    spec.count = (it.toString().toIntOrNull() ?: 0).coerceIn(0, 60)
                    updateRowBadge()
                    updateRowPhotoUi()
                    refreshWizardSummary()
                }

                rowBinding.etRowStartNumber.addTextChangedListener {
                    if (updatingRow) return@addTextChangedListener
                    spec.customStartEdited = true
                    spec.startNumber = it.toString().toIntOrNull() ?: (spec.floor * 100 + 1)
                    updateRowBadge()
                    refreshWizardSummary()
                }

                rowBinding.etRowPrice.addTextChangedListener {
                    if (updatingRow) return@addTextChangedListener
                    spec.customPriceEdited = true
                    spec.price = it.toString()
                }

                rowBinding.chipGroupRowType.setOnCheckedStateChangeListener { _, checkedIds ->
                    if (updatingRow) return@setOnCheckedStateChangeListener
                    spec.type = when (checkedIds.firstOrNull()) {
                        R.id.chipRowDouble -> "Doble"
                        R.id.chipRowMatrimonial -> "Matrimonial"
                        R.id.chipRowSuite -> "Suite"
                        else -> "Simple"
                    }
                    if (!spec.customPriceEdited) {
                        val suggested = suggestRateForType(spec.type)
                            ?: when (spec.type) {
                                "Doble" -> 80.0
                                "Matrimonial" -> 100.0
                                "Suite" -> 150.0
                                else -> 50.0
                            }
                        spec.price = formatRateText(suggested)
                        updatingRow = true
                        rowBinding.etRowPrice.setText(spec.price)
                        updatingRow = false
                    }
                }

                wizardBinding.layoutFloorRows.addView(rowBinding.root)
            }
            refreshWizardSummary()
        }

        renderFloorRows()

        wizardBinding.btnAddFloorRow.setOnClickListener {
            val lastSpec = specs.lastOrNull()
            val nextFloor = ((specs.maxOfOrNull { it.floor } ?: 0) + 1).coerceAtMost(50)
            specs.add(
                FloorSetupSpec(
                    floor = nextFloor,
                    count = lastSpec?.count ?: 5,
                    startNumber = nextFloor * 100 + 1,
                    type = lastSpec?.type ?: "Simple",
                    price = lastSpec?.price ?: defaultPrice
                )
            )
            renderFloorRows()
        }

        wizardBinding.btnCancelWizard.setOnClickListener { dialog.dismiss() }

        wizardBinding.btnCreateAllFloors.setOnClickListener {
            val safeCtx = context ?: return@setOnClickListener
            wizardBinding.btnCreateAllFloors.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val createdRooms = withContext(Dispatchers.IO) {
                    val toInsert = mutableListOf<HotelRoomEntity>()
                    val seenNumbers = mutableSetOf<String>()

                    specs.forEach { spec ->
                        val count = spec.count.coerceIn(0, 60)
                        val price = spec.price.toDoubleOrNull() ?: 0.0
                        for (i in 0 until count) {
                            val numStr = (spec.startNumber + i).toString()
                            if (!seenNumbers.contains(numStr) && database.hotelDao().getRoomByNumber(numStr) == null) {
                                seenNumbers.add(numStr)
                                val room = HotelRoomEntity(
                                    number = numStr,
                                    floor = spec.floor,
                                    type = spec.type,
                                    baseRate = price,
                                    photoPath = spec.photoPath
                                )
                                database.hotelDao().insertRoom(room)
                                toInsert.add(room)
                            }
                        }
                    }
                    toInsert
                }

                SyncManager(safeCtx.applicationContext).syncHotelRoomsBatchToCloud(createdRooms)

                context?.let { c ->
                    Toast.makeText(
                        c,
                        "✓ Creadas ${createdRooms.size} habitaciones en ${createdRooms.map { it.floor }.distinct().size} pisos",
                        Toast.LENGTH_LONG
                    ).show()
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setupToolbar() {
        binding.btnMenuRooms.setOnClickListener { (activity as? MainActivity)?.openSideMenu() }
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        binding.btnQuickFloorSetupToolbar.visibility = if (isCollab) View.GONE else View.VISIBLE
        binding.btnQuickFloorSetupToolbar.setOnClickListener { showQuickFloorsSetupDialog() }
        binding.btnBulkDelete.visibility = if (isCollab) View.GONE else View.VISIBLE
        binding.btnBulkDelete.setOnClickListener { showBulkDeleteDialog() }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun setupRecyclerView() {
        val ctx = context ?: return
        val isCollab = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            .getBoolean("is_collaborator_mode", false)
        adapter = HotelRoomAdapter(
            onAction = { room ->
                val safeCtx = context ?: return@HotelRoomAdapter
                when (room.status) {
                    "OCCUPIED" -> {
                        val intent = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelManageRoomActivity")
                        intent.putExtra("ROOM_ID", room.id); intent.putExtra("ROOM_NUMBER", room.number)
                        startActivity(intent)
                    }
                    "FREE", "DIRTY", "MAINTENANCE", "RESERVED" -> {
                        val i = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelCheckInActivity")
                        i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                        startActivity(i)
                    }
                }
            },
            onSecondAction = { room ->
                val safeCtx = context ?: return@HotelRoomAdapter
                viewLifecycleOwner.lifecycleScope.launch {
                    val newStatus = if (room.status == "OCCUPIED") "OCCUPIED" else "FREE"
                    val updatedRoom = room.copy(status = newStatus, lastCleaned = System.currentTimeMillis())
                    database.hotelDao().updateRoom(updatedRoom)
                    SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updatedRoom)
                    context?.let { c -> Toast.makeText(c, "Aseo registrado correctamente ✨", Toast.LENGTH_SHORT).show() }
                }
            },
            onThirdAction = { room ->
                val safeCtx = context ?: return@HotelRoomAdapter
                viewLifecycleOwner.lifecycleScope.launch {
                    val reports = database.hotelDao().getPendingMaintenanceForRoom(room.id).first()
                    val sm = SyncManager(safeCtx.applicationContext)
                    reports.forEach { report ->
                        val fixed = report.copy(status = "FIXED")
                        database.hotelDao().updateMaintenanceReport(fixed)
                        sm.syncHotelMaintenanceToCloud(fixed)
                    }
                    context?.let { c -> Toast.makeText(c, "Reparación completada ✅", Toast.LENGTH_SHORT).show() }
                }
            },
            onPhotoClick = { room ->
                pendingPhotoPickerCallback = picker@{ savedPath ->
                    val safeCtx = context ?: return@picker
                    viewLifecycleOwner.lifecycleScope.launch {
                        val updatedRoom = room.copy(
                            photoPath = savedPath,
                            photoUrl = null,
                            isSynced = false
                        )
                        database.hotelDao().updateRoom(updatedRoom)
                        SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updatedRoom)
                        context?.let { c ->
                            Toast.makeText(c, "✓ Foto guardada para Habitación ${room.number}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                pickImageLauncher.launch("image/*")
            },
            isCollaborator = isCollab,
            onCardClick = { room, hasFailure, hasRes -> showRoomActionDialog(room, hasFailure, hasRes) }
        )
        binding.rvRooms.layoutManager = LinearLayoutManager(ctx)
        binding.rvRooms.adapter = adapter
    }

    private fun showRoomActionDialog(room: HotelRoomEntity, hasPendingFailure: Boolean, hasReservation: Boolean = false) {
        val ctx = context ?: return
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val collabPrefs = ctx.getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)

        val options = mutableListOf<String>()

        if (room.status == "FREE" && !hasReservation) {
            options.add("Hospedar Huésped")
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

        AlertDialog.Builder(ctx)
            .setTitle(Html.fromHtml(titleHtml, Html.FROM_HTML_MODE_LEGACY))
            .setItems(options.toTypedArray()) { _, w ->
                val safeCtx = context ?: return@setItems
                viewLifecycleOwner.lifecycleScope.launch {
                    when (options[w]) {
                        "Hospedar Huésped" -> {
                            val i = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelCheckInActivity")
                            i.putExtra("ROOM_ID", room.id); i.putExtra("ROOM_NUMBER", room.number); i.putExtra("BASE_RATE", room.baseRate)
                            startActivity(i)
                        }
                        "Limpieza Realizada" -> {
                            val preservedStatus = when (room.status) {
                                "OCCUPIED" -> "OCCUPIED"
                                "RESERVED" -> "RESERVED"
                                else -> "FREE"
                            }
                            val updatedRoom = room.copy(status = preservedStatus, lastCleaned = System.currentTimeMillis())
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updatedRoom)
                        }
                        "Solicitar Aseo (Manual)" -> {
                            val updatedRoom = room.copy(lastCleaned = 0L)
                            database.hotelDao().updateRoom(updatedRoom)
                            SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updatedRoom)
                        }
                        "Marcar como Reparada ✅" -> {
                            val reports = database.hotelDao().getPendingMaintenanceForRoom(room.id).first()
                            val sm = SyncManager(safeCtx.applicationContext)
                            reports.forEach { report ->
                                val fixed = report.copy(status = "FIXED")
                                database.hotelDao().updateMaintenanceReport(fixed)
                                sm.syncHotelMaintenanceToCloud(fixed)
                            }
                            context?.let { c -> Toast.makeText(c, "Falla reparada", Toast.LENGTH_SHORT).show() }
                        }
                        "Reportar Falla 🛠️" -> {
                            val intent = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelMaintenanceActivity")
                            intent.putExtra("REPORT_ROOM_ID", room.id)
                            startActivity(intent)
                        }
                        "Editar Información" -> {
                            showRoomDialog(room)
                        }
                        "Eliminar Habitación" -> {
                            AlertDialog.Builder(safeCtx).setTitle("¿Eliminar?").setPositiveButton("Sí") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val updatedRoom = room.copy(isDeleted = true)
                                    database.hotelDao().updateRoom(updatedRoom)
                                    SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updatedRoom)
                                }
                            }.setNegativeButton("No", null).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingPhotoPickerCallback = null
        _binding = null
    }
}
