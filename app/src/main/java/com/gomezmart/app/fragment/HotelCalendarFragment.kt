package com.gomezmart.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.adapter.HotelBookingAdapter
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.FragmentHotelCalendarBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HotelCalendarFragment : Fragment() {

    private var _binding: FragmentHotelCalendarBinding? = null
    private val binding get() = _binding!!
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private val adapter = HotelBookingAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeBookings()
    }

    private fun setupToolbar() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.toolbarCalendar, applyTop = true, applyBottom = false)

        binding.toolbarCalendar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
        binding.toolbarCalendar.setNavigationOnClickListener {
            (activity as? MainActivity)?.openSideMenu()
        }
    }

    private fun setupRecyclerView() {
        binding.rvCalendarBookings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCalendarBookings.adapter = adapter
    }

    private fun setupListeners() {
        val openBookings = {
            val intent = Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelBookingsActivity")
            startActivity(intent)
        }
        binding.btnOpenBookingsActivity.setOnClickListener { openBookings() }
        binding.btnEmptyAddBooking.setOnClickListener { openBookings() }
        binding.fabAddCalendarBooking.setOnClickListener { openBookings() }
    }

    private fun observeBookings() {
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            dbInstance.hotelDao().getAllBookings().collectLatest { bookings ->
                _binding?.let { b ->
                    val sorted = bookings.sortedBy { it.checkInDate }
                    adapter.submitList(sorted)

                    val isEmpty = sorted.isEmpty()
                    b.layoutEmptyCalendar.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    b.rvCalendarBookings.visibility = if (isEmpty) View.GONE else View.VISIBLE

                    if (isEmpty) {
                        b.tvCalendarSummaryCount.text = "0 reservas registradas"
                    } else {
                        val confirmed = sorted.count { it.status == "CONFIRMED" }
                        b.tvCalendarSummaryCount.text = "$confirmed confirmada(s) • ${sorted.size} en total"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
