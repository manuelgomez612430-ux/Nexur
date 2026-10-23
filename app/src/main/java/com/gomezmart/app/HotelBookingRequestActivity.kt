package com.gomezmart.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.HotelBookingRequest
import com.gomezmart.app.databinding.ActivityHotelBookingRequestBinding
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

        binding.btnChatHotelOwner.setOnClickListener {
            openChatWithStoreOwner(storeId, "Hospedaje")
        }
    }

    private fun openChatWithStoreOwner(sId: String, sName: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Inicia sesión para chatear con el hospedaje 🔐", Toast.LENGTH_SHORT).show()
            return
        }
        val myUid = user.uid
        if (sId.isEmpty() || sId == myUid) {
            Toast.makeText(this, "Este es tu propio hospedaje", Toast.LENGTH_SHORT).show()
            return
        }

        val chatId = if (myUid < sId) "${myUid}_${sId}" else "${sId}_${myUid}"
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", chatId)
            putExtra("OTHER_USER_ID", sId)
            putExtra("OTHER_USER_NAME", sName)
            putExtra("OTHER_USER_TITLE", "Dueño de $sName")
            putExtra("IS_DIRECT", true)
        }
        startActivityWithFade(intent)
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
