package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentHotelHomeBinding
import com.naxor.app.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelHomeFragment : Fragment() {

    private var _binding: FragmentHotelHomeBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        updateDashboard()
    }

    private fun setupToolbar() {
        binding.toolbarHotel.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupListeners() {
        binding.cardRoomsMap.setOnClickListener { (activity as? MainActivity)?.navigateToStock() }
        binding.toolGuests.setOnClickListener { startToolActivity(Intent(requireContext(), com.naxor.app.HotelGuestHistoryActivity::class.java)) }
        binding.toolBookings.setOnClickListener { startToolActivity(Intent(requireContext(), com.naxor.app.HotelBookingsActivity::class.java)) }
        binding.toolPayments.setOnClickListener { startToolActivity(Intent(requireContext(), com.naxor.app.BusinessDebtsActivity::class.java)) }
        binding.toolMaintenance.setOnClickListener { startToolActivity(Intent(requireContext(), com.naxor.app.HotelMaintenanceActivity::class.java)) }
        binding.toolInventory.setOnClickListener { startToolActivity(Intent(requireContext(), com.naxor.app.HotelToolsActivity::class.java)) }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
    }

    private fun updateDashboard() {
        val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
        binding.tvHotelDate.text = sdf.format(Date()).replaceFirstChar { it.uppercase() }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                database.hotelDao().getAllRooms(),
                database.hotelDao().getAllBookings()
            ) { rooms, bookings ->
                Pair(rooms, bookings)
            }.collectLatest { (rooms, bookings) ->
                _binding?.let { b ->
                    val total = rooms.size
                    val occupied = rooms.count { it.status == "OCCUPIED" }
                    val free = rooms.count { it.status == "FREE" }
                    val reserved = bookings.count { it.status == "CONFIRMED" }

                    b.tvStatOccupied.text = occupied.toString()
                    b.tvStatFree.text = free.toString()
                    b.tvStatReserved.text = reserved.toString()
                    
                    b.tvRoomsSummary.text = "$free de $total habitaciones disponibles"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
