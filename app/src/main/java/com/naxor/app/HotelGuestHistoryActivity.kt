package com.naxor.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.GuestHistoryItem
import com.naxor.app.adapter.HotelGuestHistoryAdapter
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.ActivityHotelGuestHistoryBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class HotelGuestHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelGuestHistoryBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: HotelGuestHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelGuestHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarHistory.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        loadHistory()
    }

    private fun setupRecyclerView() {
        adapter = HotelGuestHistoryAdapter()
        binding.rvGuestHistory.layoutManager = LinearLayoutManager(this)
        binding.rvGuestHistory.adapter = adapter
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            // Obtener datos base
            val rooms = database.hotelDao().getAllRooms().first()
            val roomsMap = rooms.associateBy { it.id }
            
            database.hotelDao().getAllBookings().collect { bookings ->
                val historyItems = mutableListOf<GuestHistoryItem>()
                
                for (booking in bookings) {
                    val room = roomsMap[booking.roomId]
                    val charges = database.hotelDao().getChargesForBooking(booking.id).first()
                    val totalExtra = charges.sumOf { it.amount }
                    val totalAccount = booking.totalAmount + totalExtra
                    
                    // Calcular noches
                    val diff = booking.checkOutDate - booking.checkInDate
                    val nights = TimeUnit.MILLISECONDS.toDays(diff).toInt().coerceAtLeast(1)

                    historyItems.add(GuestHistoryItem(
                        bookingId = booking.id,
                        name = booking.guestName,
                        doc = booking.guestDoc,
                        origin = booking.guestOrigin ?: "-",
                        nationality = booking.guestNationality,
                        roomNumber = room?.number ?: "?",
                        floor = room?.floor ?: 1,
                        stayNights = nights,
                        extraCharges = totalExtra,
                        totalAmount = totalAccount
                    ))
                }
                
                adapter.submitList(historyItems.sortedByDescending { it.bookingId })
            }
        }
    }
}
