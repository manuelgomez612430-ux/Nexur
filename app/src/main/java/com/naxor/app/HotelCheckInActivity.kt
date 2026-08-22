package com.naxor.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelBookingEntity
import com.naxor.app.databinding.ActivityHotelCheckinBinding
import kotlinx.coroutines.launch
import java.util.Calendar

class HotelCheckInActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelCheckinBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var roomId: String? = null
    private var roomNumber: String? = null
    private var baseRate: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelCheckinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getStringExtra("ROOM_ID")
        roomNumber = intent.getStringExtra("ROOM_NUMBER")
        baseRate = intent.getDoubleExtra("BASE_RATE", 0.0)

        binding.tvRoomInfo.text = "Habitación: $roomNumber"
        binding.toolbarCheckIn.setNavigationOnClickListener { finish() }

        binding.btnConfirmCheckIn.setOnClickListener {
            performCheckIn()
        }
    }

    private fun performCheckIn() {
        val name = binding.etGuestName.text.toString().trim()
        val doc = binding.etGuestDoc.text.toString().trim()
        val phone = binding.etGuestPhone.text.toString().trim()
        val origin = binding.etGuestOrigin.text.toString().trim()
        val days = binding.etStayDays.text.toString().toIntOrNull() ?: 1
        val deposit = binding.etDeposit.text.toString().toDoubleOrNull() ?: 0.0

        if (name.isEmpty() || roomId == null) {
            Toast.makeText(this, "Completa los datos del huésped", Toast.LENGTH_SHORT).show()
            return
        }

        val checkIn = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, days)
        val checkOut = cal.timeInMillis

        val booking = HotelBookingEntity(
            roomId = roomId!!,
            guestName = name,
            guestDoc = doc,
            guestPhone = phone,
            guestOrigin = origin,
            checkInDate = checkIn,
            checkOutDate = checkOut,
            totalAmount = baseRate * days,
            deposit = deposit,
            status = "CHECKED_IN"
        )

        lifecycleScope.launch {
            database.hotelDao().insertBooking(booking)
            database.hotelDao().updateRoomStatus(roomId!!, "OCCUPIED")
            Toast.makeText(this@HotelCheckInActivity, "Check-in exitoso", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
