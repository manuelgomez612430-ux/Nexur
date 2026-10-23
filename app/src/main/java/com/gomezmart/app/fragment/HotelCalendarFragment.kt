package com.gomezmart.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.databinding.FragmentHotelCalendarBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.gomezmart.app.MainActivity
import com.gomezmart.app.R
import com.gomezmart.app.adapter.HotelBookingAdapter
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

    private fun observeBookings() {
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            dbInstance.hotelDao().getAllBookings().collectLatest { bookings ->
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
