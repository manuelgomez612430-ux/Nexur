package com.naxor.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.HotelBookingRequest
import com.naxor.app.databinding.ActivityHotelBookingRequestBinding
import java.text.SimpleDateFormat
import java.util.*

class HotelBookingRequestActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelBookingRequestBinding
    private val db = FirebaseFirestore.getInstance()
    private var selectedDate: Long = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelBookingRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomId = intent.getStringExtra("ROOM_ID") ?: ""
        val roomName = intent.getStringExtra("ROOM_NAME") ?: "Habitación"
        val roomPrice = intent.getDoubleExtra("ROOM_PRICE", 0.0)
        val storeId = intent.getStringExtra("STORE_ID") ?: ""

        setupUI(roomName, roomPrice)
        setupListeners(roomId, roomName, storeId)
    }

    private fun setupUI(name: String, price: Double) {
        binding.toolbarBookingRequest.setNavigationOnClickListener { finish() }
        binding.tvBookingRoomName.text = name
        binding.tvBookingRoomPrice.text = "S/ ${String.format(Locale.US, "%.2f", price)} la noche"
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        binding.etBookingDate.setText(sdf.format(Date(selectedDate)))
    }

    private fun setupListeners(roomId: String, roomName: String, storeId: String) {
        binding.etBookingDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                val selCal = Calendar.getInstance()
                selCal.set(y, m, d)
                selectedDate = selCal.timeInMillis
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                binding.etBookingDate.setText(sdf.format(Date(selectedDate)))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnSubmitBooking.setOnClickListener {
            submitRequest(roomId, roomName, storeId)
        }
    }

    private fun submitRequest(rId: String, rName: String, sId: String) {
        val name = binding.etBookingName.text.toString().trim()
        val doc = binding.etBookingDoc.text.toString().trim()
        val phone = binding.etBookingPhone.text.toString().trim()
        val origin = binding.etBookingOrigin.text.toString().trim()
        val stay = binding.etBookingStay.text.toString().toIntOrNull() ?: 0
        val nationality = if (binding.toggleBookingNationality.checkedButtonId == R.id.btnBookingForeigner) "FOREIGNER" else "NATIONAL"

        if (name.isEmpty() || doc.isEmpty() || phone.isEmpty() || origin.isEmpty() || stay <= 0) {
            Toast.makeText(this, "Por favor, completa todos los campos requeridos", Toast.LENGTH_LONG).show()
            return
        }

        val buyerId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
        val requestId = UUID.randomUUID().toString()

        val request = HotelBookingRequest(
            id = requestId,
            roomId = rId,
            roomName = rName,
            guestName = name,
            guestDoc = doc,
            guestPhone = phone,
            guestOrigin = origin,
            guestNationality = nationality,
            stayNights = stay,
            bookingDate = selectedDate,
            status = "PENDING",
            buyerId = buyerId,
            timestamp = FieldValue.serverTimestamp()
        )

        binding.btnSubmitBooking.isEnabled = false
        binding.btnSubmitBooking.text = "Enviando..."

        db.collection("users").document(sId)
            .collection("booking_requests").document(requestId)
            .set(request)
            .addOnSuccessListener {
                Toast.makeText(this, "Solicitud enviada con éxito ✅", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener {
                binding.btnSubmitBooking.isEnabled = true
                binding.btnSubmitBooking.text = "ENVIAR SOLICITUD DE RESERVA"
                Toast.makeText(this, "Error al enviar solicitud: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
