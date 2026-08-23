package com.naxor.app

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
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelBookingEntity
import com.naxor.app.data.HotelRoomEntity
import com.naxor.app.databinding.ActivityHotelBookingsBinding
import com.naxor.app.databinding.ItemHotelGuestHistoryBinding // Reutilizamos el item de historial para empezar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelBookingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelBookingsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: BookingsAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelBookingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarBookings.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        loadData()
        
        binding.fabAddBooking.setOnClickListener { showAddBookingDialog() }
    }

    private fun setupRecyclerView() {
        adapter = BookingsAdapter(
            onCheckIn = { booking -> performCheckInFromBooking(booking) },
            onCancel = { booking -> cancelBooking(booking) }
        )
        binding.rvBookings.layoutManager = LinearLayoutManager(this)
        binding.rvBookings.adapter = adapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            allRooms = database.hotelDao().getAllRooms().first()
            database.hotelDao().getAllBookings().collectLatest { bookings ->
                // Filtrar solo las confirmadas (reservas futuras)
                adapter.submitList(bookings.filter { it.status == "CONFIRMED" }.sortedBy { it.checkInDate })
            }
        }
    }

    private fun showAddBookingDialog() {
        val builder = AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val spinnerRoom = Spinner(this)
        val roomNumbers = allRooms.map { "Hab. ${it.number} (${it.type})" }.toTypedArray()
        spinnerRoom.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roomNumbers)

        val etGuest = EditText(this).apply { hint = "Nombre del Huésped" }
        val etDoc = EditText(this).apply { hint = "DNI / Documento" }
        
        var selectedDate = System.currentTimeMillis()
        val btnDate = Button(this).apply {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            text = "📅 Fecha: ${sdf.format(Date(selectedDate))}"
            setOnClickListener {
                val cal = Calendar.getInstance()
                android.app.DatePickerDialog(this@HotelBookingsActivity, { _, y, m, d ->
                    val selCal = Calendar.getInstance()
                    selCal.set(y, m, d)
                    selectedDate = selCal.timeInMillis
                    text = "📅 Fecha: ${sdf.format(Date(selectedDate))}"
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }

        layout.addView(TextView(this).apply { text = "Habitación:" })
        layout.addView(spinnerRoom)
        layout.addView(etGuest)
        layout.addView(etDoc)
        layout.addView(btnDate)

        builder.setTitle("Nueva Reserva")
            .setView(layout)
            .setPositiveButton("Crear Reserva") { _, _ ->
                val room = allRooms[spinnerRoom.selectedItemPosition]
                val guest = etGuest.text.toString()
                val doc = etDoc.text.toString()
                if (guest.isNotEmpty()) {
                    createBooking(room.id, guest, doc, selectedDate)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun createBooking(roomId: String, name: String, doc: String, date: Long) {
        lifecycleScope.launch {
            val booking = HotelBookingEntity(
                roomId = roomId,
                guestName = name,
                guestDoc = doc,
                guestPhone = "",
                checkInDate = date,
                checkOutDate = date + (24 * 60 * 60 * 1000), // 1 noche por defecto
                totalAmount = 0.0, // Se ajusta al check-in
                status = "CONFIRMED"
            )
            database.hotelDao().insertBooking(booking)
            Toast.makeText(this@HotelBookingsActivity, "Reserva registrada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelBooking(booking: HotelBookingEntity) {
        lifecycleScope.launch {
            database.hotelDao().updateBooking(booking.copy(status = "CANCELLED", isDeleted = true))
            Toast.makeText(this@HotelBookingsActivity, "Reserva cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performCheckInFromBooking(booking: HotelBookingEntity) {
        // Enviar a la actividad de Check-in con los datos de la reserva
        val intent = android.content.Intent(this, HotelCheckInActivity::class.java).apply {
            putExtra("ROOM_ID", booking.roomId)
            putExtra("GUEST_NAME", booking.guestName)
            putExtra("GUEST_DOC", booking.guestDoc)
        }
        startActivity(intent)
        finish()
    }

    inner class BookingsAdapter(
        private val onCheckIn: (HotelBookingEntity) -> Unit,
        private val onCancel: (HotelBookingEntity) -> Unit
    ) : RecyclerView.Adapter<BookingsAdapter.ViewHolder>() {

        private var items = emptyList<HotelBookingEntity>()
        private val roomsMap = allRooms.associateBy { it.id }

        fun submitList(newItems: List<HotelBookingEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemHotelGuestHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemHotelGuestHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val room = roomsMap[item.roomId]
            with(holder.binding) {
                tvGuestName.text = item.guestName
                tvGuestDoc.text = "Doc: ${item.guestDoc}"
                tvRoomNumber.text = "Hab. ${room?.number ?: "?"}"
                
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                tvStayDuration.text = "Reserva: ${sdf.format(Date(item.checkInDate))}"
                
                chipNationality.text = "RESERVA"
                chipNationality.setChipBackgroundColorResource(R.color.sky_600)
                
                // Reutilizamos el footer para acciones
                tvTotalAmount.text = "Realizar Check-in >"
                tvTotalAmount.setTextColor(root.context.getColor(R.color.emerald_600))
                tvTotalAmount.setOnClickListener { onCheckIn(item) }
                
                root.setOnLongClickListener {
                    AlertDialog.Builder(root.context)
                        .setTitle("¿Cancelar reserva?")
                        .setPositiveButton("Sí") { _,_ -> onCancel(item) }
                        .setNegativeButton("No", null).show()
                    true
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
