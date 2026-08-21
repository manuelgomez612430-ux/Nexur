package com.naxor.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.data.AppDatabase
import com.naxor.app.databinding.FragmentHotelCalendarBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.naxor.app.MainActivity
import com.naxor.app.R
import com.naxor.app.adapter.HotelBookingAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HotelCalendarFragment : Fragment() {

    private var _binding: FragmentHotelCalendarBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private val adapter = HotelBookingAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        observeBookings()
    }

    private fun setupToolbar() {
        binding.toolbarCalendar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarCalendar.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupRecyclerView() {
        binding.rvCalendarBookings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCalendarBookings.adapter = adapter
    }

    private fun observeBookings() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.hotelDao().getAllBookings().collectLatest { bookings ->
                _binding?.let {
                    adapter.submitList(bookings)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
