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
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.R
import com.naxor.app.MainActivity
import com.naxor.app.adapter.HotelBookingAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class HotelHomeFragment : Fragment() {

    private var _binding: FragmentHotelHomeBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private val arrivalsAdapter = HotelBookingAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupListeners()
        observeRoomStats()
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
        
        binding.toolMaintenance.setOnClickListener { 
            startToolActivity(Intent(requireContext(), com.naxor.app.HotelMaintenanceActivity::class.java))
        }
        binding.toolInventory.setOnClickListener { 
            Toast.makeText(requireContext(), "Inventario de Herramientas", Toast.LENGTH_SHORT).show()
        }
        binding.toolCityGuide.setOnClickListener { 
            Toast.makeText(requireContext(), "Guía Digital de la Ciudad", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
    }

    private fun observeRoomStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.hotelDao().getAllRooms().collectLatest { rooms ->
                _binding?.let { b ->
                    val free = rooms.count { it.status == "FREE" }
                    val total = rooms.size
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
