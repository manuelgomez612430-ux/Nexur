package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelMaintenanceEntity
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.databinding.ActivityHotelMaintenanceBinding
import com.naxor.app.databinding.ItemHotelMaintenanceBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

class HotelMaintenanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelMaintenanceBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: MaintenanceAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelMaintenanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarMaintenance.setNavigationOnClickListener { finish() }
        setupToolbarMenu()
        
        setupRecyclerView()
        loadData()
        
        binding.fabAddReport.setOnClickListener { showAddReportDialog() }

        // Manejar reporte automático si viene de la lista de habitaciones
        val autoRoomId = intent.getStringExtra("REPORT_ROOM_ID")
        if (autoRoomId != null) {
            lifecycleScope.launch {
                // Esperar a que se carguen las habitaciones
                while (allRooms.isEmpty()) { kotlinx.coroutines.delay(100) }
                val targetRoom = allRooms.find { it.id == autoRoomId }
                targetRoom?.let { showAddReportDialog(it) }
            }
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbarMaintenance.inflateMenu(R.menu.menu_hotel_maintenance)
        binding.toolbarMaintenance.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_all -> {
                    showDeleteAllConfirmation()
                    true
                }
                else -> false
            }
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar todo el historial?")
            .setMessage("Esta acción eliminará todos los reportes registrados. No se puede deshacer.")
            .setPositiveButton("Borrar Todo") { _, _ ->
                lifecycleScope.launch {
                    database.hotelDao().deleteAllMaintenanceReports()
                    Toast.makeText(this@HotelMaintenanceActivity, "Historial eliminado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = MaintenanceAdapter(
            onFixed = { report -> markAsFixed(report) }
        )
        binding.rvMaintenance.layoutManager = LinearLayoutManager(this)
        binding.rvMaintenance.adapter = adapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            allRooms = database.hotelDao().getAllRooms().first()
            database.hotelDao().getAllMaintenanceReports().collectLatest { reports ->
                adapter.submitList(reports.sortedByDescending { it.timestamp })
            }
        }
    }

    private fun markAsFixed(report: HotelMaintenanceEntity) {
        lifecycleScope.launch {
            database.hotelDao().updateMaintenanceReport(report.copy(status = "FIXED"))
            Toast.makeText(this@HotelMaintenanceActivity, "Falla marcada como solucionada", Toast.LENGTH_SHORT).show()
        }
    }

    private var currentPhotoPath: String? = null
    private var ivPreview: ImageView? = null

    private val takePhotoLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            currentPhotoPath?.let { path ->
                ivPreview?.let { view ->
                    view.visibility = View.VISIBLE
                    Glide.with(this).load(File(path)).into(view)
                }
            }
        }
    }

    private fun showAddReportDialog(preSelectedRoom: HotelRoomEntity? = null) {
        val builder = AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val spinnerRoom = Spinner(this)
        val roomNumbers = allRooms.map { "Hab. ${it.number}" }.toTypedArray()
        spinnerRoom.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roomNumbers)
        
        preSelectedRoom?.let { room ->
            val index = allRooms.indexOfFirst { it.id == room.id }
            if (index >= 0) spinnerRoom.setSelection(index)
        }

        val etDesc = EditText(this).apply { hint = "Descripción de la falla o defecto" }
        
        val btnPhoto = Button(this).apply { 
            text = "📷 Tomar Foto del Problema"
            setOnClickListener { launchCamera() }
        }
        
        ivPreview = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400).apply { setMargins(0, 20, 0, 20) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        layout.addView(TextView(this).apply { text = "Habitación:" })
        layout.addView(spinnerRoom)
        layout.addView(etDesc)
        layout.addView(btnPhoto)
        layout.addView(ivPreview)

        builder.setTitle(if (preSelectedRoom != null) "Reportar Falla: Hab. ${preSelectedRoom.number}" else "Reportar Falla")
            .setView(layout)
            .setPositiveButton("Enviar Reporte") { _, _ ->
                val selectedRoomIndex = spinnerRoom.selectedItemPosition
                if (selectedRoomIndex >= 0) {
                    val room = allRooms[selectedRoomIndex]
                    val desc = etDesc.text.toString()
                    if (desc.isNotEmpty()) {
                        saveReport(room.id, desc)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun launchCamera() {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = File(getExternalFilesDir(null), "maint_${System.currentTimeMillis()}.jpg")
        currentPhotoPath = photoFile.absolutePath
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
    }

    private fun saveReport(roomId: String, description: String) {
        lifecycleScope.launch {
            val report = HotelMaintenanceEntity(
                roomId = roomId,
                description = description,
                photoPath = currentPhotoPath,
                status = "PENDING"
            )
            database.hotelDao().insertMaintenanceReport(report)
            Toast.makeText(this@HotelMaintenanceActivity, "Reporte enviado con éxito", Toast.LENGTH_SHORT).show()
            currentPhotoPath = null
        }
    }

    inner class MaintenanceAdapter(
        private val onFixed: (HotelMaintenanceEntity) -> Unit
    ) : RecyclerView.Adapter<MaintenanceAdapter.ViewHolder>() {

        private var items = emptyList<HotelMaintenanceEntity>()

        fun submitList(newItems: List<HotelMaintenanceEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemHotelMaintenanceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemHotelMaintenanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val roomsMap = allRooms.associateBy { it.id }
            val room = roomsMap[item.roomId]
            
            with(holder.binding) {
                tvRoomLabel.text = "Habitación ${room?.number ?: "?"}"
                tvDescription.text = item.description
                
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                tvTimestamp.text = "Reportado: ${sdf.format(Date(item.timestamp))}"

                if (item.photoPath != null) {
                    cardPhoto.visibility = View.VISIBLE
                    Glide.with(root.context).load(File(item.photoPath)).into(ivReportPhoto)
                } else {
                    cardPhoto.visibility = View.GONE
                }

                if (item.status == "FIXED") {
                    chipStatus.text = "SOLUCIONADO"
                    chipStatus.setChipBackgroundColorResource(R.color.green_600)
                    btnMarkFixed.visibility = View.GONE
                } else {
                    chipStatus.text = "PENDIENTE"
                    chipStatus.setChipBackgroundColorResource(R.color.orange_600)
                    btnMarkFixed.visibility = View.VISIBLE
                }

                btnMarkFixed.setOnClickListener { onFixed(item) }
            }
        }

        override fun getItemCount() = items.size
    }
}
