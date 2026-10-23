package com.gomezmart.app

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelBookingEntity
import com.gomezmart.app.data.HotelBookingRequest
import com.gomezmart.app.data.HotelRoomEntity
import com.gomezmart.app.databinding.ActivityHotelBookingsBinding
import com.gomezmart.app.databinding.ItemBookingRequestBinding
import com.gomezmart.app.databinding.ItemHotelGuestHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelBookingsActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelBookingsBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val db = FirebaseFirestore.getInstance()
    private lateinit var adapter: BookingsAdapter
    private var allRooms: List<HotelRoomEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelBookingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarBookings.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        setupListeners()
        loadData()
        
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean("has_new_booking_request", false).apply()
    }

    private fun setupRecyclerView() {
        adapter = BookingsAdapter(
            onCheckIn = { booking -> performCheckInFromBooking(booking) },
            onCancel = { booking -> cancelBooking(booking) },
            onAcceptRequest = { request -> acceptBookingRequest(request) },
            onRejectRequest = { request -> rejectBookingRequest(request) }
        )
        binding.rvBookings.layoutManager = LinearLayoutManager(this)
        binding.rvBookings.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAddBooking.setOnClickListener { showAddBookingDialog() }
        
        binding.toggleBookingTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnShowRequests) {
                    loadBookingRequests()
                } else {
                    loadConfirmedBookings()
                }
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allRooms = database.hotelDao().getAllRooms().first()
            loadConfirmedBookings()
        }
    }

    private fun loadConfirmedBookings() {
        lifecycleScope.launch {
            database.hotelDao().getAllBookings().collectLatest { bookings ->
                val confirmed = bookings.filter { it.status == "CONFIRMED" }.sortedBy { it.checkInDate }
                if (binding.toggleBookingTabs.checkedButtonId == R.id.btnShowBookings) {
                    adapter.submitList(confirmed.map { BookableItem.Booking(it) })
                }
            }
        }
    }

    private fun loadBookingRequests() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("users").document(userId)
            .collection("booking_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                val requests = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(HotelBookingRequest::class.java)?.copy(id = doc.id)
                }
                
                if (binding.toggleBookingTabs.checkedButtonId == R.id.btnShowRequests) {
                    adapter.submitList(requests.map { BookableItem.Request(it) })
                }
            }
    }

    private fun acceptBookingRequest(request: HotelBookingRequest) {
        lifecycleScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            
            val booking = HotelBookingEntity(
                id = request.id,
                roomId = request.roomId,
                guestName = request.guestName,
                guestDoc = request.guestDoc,
                guestPhone = request.guestPhone,
                guestOrigin = request.guestOrigin,
                guestNationality = request.guestNationality,
                checkInDate = request.bookingDate,
                checkOutDate = request.bookingDate + (request.stayNights * 24L * 60L * 60L * 1000L),
                totalAmount = 0.0,
                status = "CONFIRMED"
            )
            
            database.hotelDao().insertBooking(booking)
            database.hotelDao().updateRoomStatus(request.roomId, "RESERVED") // Marcar como reservado
            
            val syncManager = SyncManager(this@HotelBookingsActivity)
            syncManager.syncHotelBookingToCloud(booking)
            database.hotelDao().getRoomById(request.roomId)?.let { syncManager.syncHotelRoomToCloud(it) }
            
            db.collection("users").document(userId).collection("booking_requests").document(request.id)
                .update("status", "ACCEPTED")
            
            Toast.makeText(this@HotelBookingsActivity, "Reserva Aceptada ✅", Toast.LENGTH_SHORT).show()
            loadBookingRequests()
        }
    }

    private fun rejectBookingRequest(request: HotelBookingRequest) {
        lifecycleScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            
            db.collection("users").document(userId).collection("booking_requests").document(request.id)
                .update("status", "REJECTED")
            
            val msg = mapOf(
                "title" to "Reserva Rechazada",
                "content" to "Lo sentimos, tu reserva para la habitación ${request.roomName} ha sido rechazada.",
                "timestamp" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(request.buyerId).collection("messages").add(msg)
            
            Toast.makeText(this@HotelBookingsActivity, "Reserva Rechazada ❌", Toast.LENGTH_SHORT).show()
            loadBookingRequests()
        }
    }

    private fun cancelBooking(booking: HotelBookingEntity) {
        lifecycleScope.launch {
            database.hotelDao().updateBooking(booking.copy(status = "CANCELLED", isDeleted = true))
            Toast.makeText(this@HotelBookingsActivity, "Reserva cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performCheckInFromBooking(booking: HotelBookingEntity) {
        val room = allRooms.find { it.id == booking.roomId }
        val intent = Intent(this, HotelCheckInActivity::class.java).apply {
            putExtra("BOOKING_ID", booking.id)
            putExtra("ROOM_ID", booking.roomId)
            putExtra("ROOM_NUMBER", room?.number ?: "")
            putExtra("BASE_RATE", room?.baseRate ?: 0.0)
            putExtra("GUEST_NAME", booking.guestName)
            putExtra("GUEST_DOC", booking.guestDoc)
            putExtra("GUEST_PHONE", booking.guestPhone)
            putExtra("GUEST_ORIGIN", booking.guestOrigin)
        }
        startActivity(intent)
        finish()
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
        val etPhone = EditText(this).apply { 
            hint = "Celular (Opcional)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val etOrigin = EditText(this).apply { hint = "Procedencia (Opcional)" }
        
        var selectedDate = System.currentTimeMillis()
        val btnDate = Button(this).apply {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            text = "📅 Fecha: ${sdf.format(Date(selectedDate))}"
            setOnClickListener {
                val cal = Calendar.getInstance()
                DatePickerDialog(this@HotelBookingsActivity, { _, y, m, d ->
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
        layout.addView(etPhone)
        layout.addView(etOrigin)
        layout.addView(btnDate)

        builder.setTitle("Nueva Reserva")
            .setView(layout)
            .setPositiveButton("Crear Reserva") { _, _ ->
                val room = allRooms[spinnerRoom.selectedItemPosition]
                val guest = etGuest.text.toString()
                val doc = etDoc.text.toString()
                val phone = etPhone.text.toString()
                val origin = etOrigin.text.toString()
                if (guest.isNotEmpty()) {
                    createBooking(room.id, guest, doc, phone, origin, selectedDate)
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun createBooking(roomId: String, name: String, doc: String, phone: String, origin: String, date: Long) {
        lifecycleScope.launch {
            val booking = HotelBookingEntity(
                roomId = roomId,
                guestName = name,
                guestDoc = doc,
                guestPhone = phone,
                guestOrigin = origin,
                checkInDate = date,
                checkOutDate = date + (24 * 60 * 60 * 1000),
                totalAmount = 0.0,
                status = "CONFIRMED"
            )
            database.hotelDao().insertBooking(booking)
            Toast.makeText(this@HotelBookingsActivity, "Reserva registrada", Toast.LENGTH_SHORT).show()
        }
    }

    sealed class BookableItem {
        data class Booking(val booking: HotelBookingEntity) : BookableItem()
        data class Request(val request: HotelBookingRequest) : BookableItem()
    }

    inner class BookingsAdapter(
        private val onCheckIn: (HotelBookingEntity) -> Unit,
        private val onCancel: (HotelBookingEntity) -> Unit,
        private val onAcceptRequest: (HotelBookingRequest) -> Unit,
        private val onRejectRequest: (HotelBookingRequest) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items = emptyList<BookableItem>()
        private val roomsMap by lazy { allRooms.associateBy { it.id } }

        fun submitList(newItems: List<BookableItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = if (items[position] is BookableItem.Booking) 1 else 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                BookingViewHolder(ItemHotelGuestHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                RequestViewHolder(ItemBookingRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is BookingViewHolder && item is BookableItem.Booking) {
                val b = item.booking
                val room = roomsMap[b.roomId]
                with(holder.binding) {
                    tvGuestName.text = b.guestName
                    tvGuestDoc.text = "Doc: ${b.guestDoc}"
                    tvRoomNumber.text = "Hab. ${room?.number ?: "?"}"
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvStayDuration.text = "Reserva: ${sdf.format(Date(b.checkInDate))}"
                    chipNationality.text = "CONFIRMADA"
                    chipNationality.setChipBackgroundColorResource(R.color.sky_600)
                    tvTotalAmount.text = "Realizar Check-in >"
                    tvTotalAmount.setOnClickListener { onCheckIn(b) }
                    root.setOnLongClickListener {
                        AlertDialog.Builder(root.context).setTitle("¿Cancelar reserva?").setPositiveButton("Sí") { _,_ -> onCancel(b) }.setNegativeButton("No", null).show()
                        true
                    }
                }
            } else if (holder is RequestViewHolder && item is BookableItem.Request) {
                val r = item.request
                with(holder.binding) {
                    tvReqGuestName.text = r.guestName
                    tvReqDetails.text = "Hab. ${r.roomName} | ${r.stayNights} noches"
                    tvReqPhone.text = r.guestPhone
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvReqDate.text = "Fecha: ${sdf.format(Date(r.bookingDate))}"
                    btnAcceptRequest.setOnClickListener { onAcceptRequest(r) }
                    btnRejectRequest.setOnClickListener { onRejectRequest(r) }
                    btnCallGuest.setOnClickListener {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${r.guestPhone}"))
                        root.context.startActivity(intent)
                    }
                }
            }
        }
        override fun getItemCount() = items.size

        inner class BookingViewHolder(val binding: ItemHotelGuestHistoryBinding) : RecyclerView.ViewHolder(binding.root)
        inner class RequestViewHolder(val binding: ItemBookingRequestBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
