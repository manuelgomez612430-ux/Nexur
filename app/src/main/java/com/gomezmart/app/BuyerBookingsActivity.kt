package com.gomezmart.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.HotelBookingRequest
import com.gomezmart.app.databinding.ActivityBuyerBookingsBinding
import com.gomezmart.app.databinding.ItemBuyerBookingBinding
import java.text.SimpleDateFormat
import java.util.*

class BuyerBookingsActivity : BaseActivity() {

    private lateinit var binding: ActivityBuyerBookingsBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: BookingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerBookingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets
        applyWindowInsets(binding.appBarBuyerBookings, applyTop = true, applyBottom = false)

        binding.btnBackBuyerBookings.setOnClickListener { finish() }
        
        setupRecyclerView()
        loadMyBookings()
    }

    private fun setupRecyclerView() {
        adapter = BookingsAdapter()
        binding.rvBuyerBookings.layoutManager = LinearLayoutManager(this)
        binding.rvBuyerBookings.adapter = adapter
    }

    private fun loadMyBookings() {
        val userId = auth.currentUser?.uid ?: return
        
        db.collectionGroup("booking_requests")
            .whereEqualTo("buyerId", userId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                val list = snapshot.documents.mapNotNull { it.toObject(HotelBookingRequest::class.java) }
                adapter.submitList(list.sortedByDescending { it.bookingDate })
                
                binding.layoutEmptyBookings.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    inner class BookingsAdapter : RecyclerView.Adapter<BookingsAdapter.ViewHolder>() {
        private var items = emptyList<HotelBookingRequest>()

        fun submitList(newItems: List<HotelBookingRequest>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemBuyerBookingBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemBuyerBookingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvBookingRoomName.text = item.roomName
                tvBookingStay.text = "Estadía: ${item.stayNights} noches"
                
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                tvBookingDate.text = "Fecha: ${sdf.format(Date(item.bookingDate))}"

                when (item.status) {
                    "PENDING" -> {
                        chipBookingStatus.text = "PENDIENTE"
                        chipBookingStatus.setChipBackgroundColorResource(R.color.orange_50)
                        chipBookingStatus.setTextColor(root.context.getColor(R.color.orange_600))
                    }
                    "ACCEPTED" -> {
                        chipBookingStatus.text = "ACEPTADA ✅"
                        chipBookingStatus.setChipBackgroundColorResource(R.color.emerald_50)
                        chipBookingStatus.setTextColor(root.context.getColor(R.color.emerald_600))
                    }
                    "REJECTED" -> {
                        chipBookingStatus.text = "RECHAZADA ❌"
                        chipBookingStatus.setChipBackgroundColorResource(R.color.red_50)
                        chipBookingStatus.setTextColor(root.context.getColor(R.color.red_600))
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
