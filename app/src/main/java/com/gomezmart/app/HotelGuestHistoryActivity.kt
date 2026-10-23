package com.gomezmart.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.gomezmart.app.adapter.GuestHistoryItem
import com.gomezmart.app.adapter.HotelGuestHistoryAdapter
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.ActivityHotelGuestHistoryBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class HotelGuestHistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelGuestHistoryBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: HotelGuestHistoryAdapter

    private var startDate: Long = 0
    private var endDate: Long = Long.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelGuestHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarHistory.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        setupFilters()
        setFilterToToday()
    }

    private fun setupRecyclerView() {
        adapter = HotelGuestHistoryAdapter(
            onLongClick = { item -> showDeleteConfirmation(item) }
        )
        binding.rvGuestHistory.layoutManager = LinearLayoutManager(this)
        binding.rvGuestHistory.adapter = adapter
    }

    private fun showDeleteConfirmation(item: GuestHistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar registro?")
            .setMessage("¿Estás seguro de que deseas eliminar el historial de ${item.name}? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    val booking = database.hotelDao().getAllBookings().first().find { it.id == item.bookingId }
                    booking?.let {
                        val updated = it.copy(isDeleted = true)
                        database.hotelDao().updateBooking(updated)
                        SyncManager(this@HotelGuestHistoryActivity).syncHotelBookingToCloud(updated)
                        Toast.makeText(this@HotelGuestHistoryActivity, "Registro eliminado", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupFilters() {
        binding.chipGroupHistory.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                binding.tvSelectedDateLabel.visibility = View.GONE
            }
            when (checkedIds.firstOrNull()) {
                R.id.chipHistoryToday -> setFilterToToday()
                R.id.chipHistoryYesterday -> setFilterToYesterday()
                R.id.chipHistoryMonth -> setFilterToMonth()
            }
        }

        binding.btnOpenCalendar.setOnClickListener {
            showDatePicker()
        }
    }

    private fun setFilterToToday() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        endDate = System.currentTimeMillis()
        loadHistory()
    }

    private fun setFilterToYesterday() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        endDate = cal.timeInMillis
        loadHistory()
    }

    private fun setFilterToMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        startDate = cal.timeInMillis
        endDate = System.currentTimeMillis()
        loadHistory()
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Seleccionar Día")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
            
        picker.addOnPositiveButtonClickListener { selection ->
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = selection
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            startDate = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
            endDate = cal.timeInMillis
            
            // Al elegir fecha, deseleccionamos los chips rápidos
            binding.chipGroupHistory.clearCheck()
            
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            binding.tvSelectedDateLabel.text = "Viendo fecha: ${sdf.format(Date(selection))}"
            binding.tvSelectedDateLabel.visibility = View.VISIBLE
            
            loadHistory()
        }
        picker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val rooms = database.hotelDao().getAllRooms().first()
            val roomsMap = rooms.associateBy { it.id }
            
            database.hotelDao().getAllBookings().collect { bookings ->
                // Filtrar por rango de fechas
                val filteredBookings = bookings.filter { it.timestamp in startDate..endDate }
                
                val historyItems = mutableListOf<GuestHistoryItem>()
                var totalRevenue = 0.0
                
                for (booking in filteredBookings) {
                    val room = roomsMap[booking.roomId]
                    val charges = database.hotelDao().getChargesForBooking(booking.id).first()
                    val totalExtra = charges.sumOf { it.amount }
                    val totalAccount = booking.totalAmount + totalExtra
                    
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
                        totalAmount = totalAccount,
                        timestamp = booking.timestamp
                    ))
                    
                    totalRevenue += totalAccount
                }
                
                updateSummary(totalRevenue, historyItems.size)
                adapter.submitList(historyItems.sortedByDescending { it.timestamp })
            }
        }
    }

    private fun updateSummary(revenue: Double, count: Int) {
        binding.tvTotalRevenue.text = String.format(Locale.US, "S/ %.2f", revenue)
        binding.tvGuestCount.text = "$count Huéspedes"
    }
}
