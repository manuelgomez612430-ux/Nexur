package com.gomezmart.app.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.*
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelTool
import com.gomezmart.app.databinding.FragmentHotelHomeBinding
import com.gomezmart.app.databinding.ItemHotelToolCardBinding
import com.gomezmart.app.util.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelHomeFragment : Fragment() {

    private var _binding: FragmentHotelHomeBinding? = null
    private val binding get() = _binding!!
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private var hotelRegistrations: List<ListenerRegistration> = emptyList()
    
    private lateinit var toolsAdapter: HotelToolsGridAdapter
    private var hasNewBooking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupRecyclerView()
        updateDashboard()
        updateWelcomeMessage()
        checkBookingRequests()

        val ctx = context ?: return
        hotelRegistrations = SyncManager(ctx).startRealtimeHotelSync { }
    }

    private fun setupRecyclerView() {
        toolsAdapter = HotelToolsGridAdapter()
        binding.rvHotelToolsGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvHotelToolsGrid.adapter = toolsAdapter
        renderTools()
    }

    private fun renderTools() {
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)

        val allTools = mutableListOf<HotelTool>()

        // 1. Historial
        if (!isCollab || collabPrefs.getBoolean("can_view_history", true)) {
            allTools.add(HotelTool("guests", "Historial", "📋", { startToolActivity(Intent(requireContext(), HotelGuestHistoryActivity::class.java)) }))
        }

        // 2. Reservas
        if (!isCollab || collabPrefs.getBoolean("can_manage_bookings", true)) {
            allTools.add(HotelTool("bookings", "Reservas", "🛎️", { startToolActivity(Intent(requireContext(), HotelBookingsActivity::class.java)) }, hasNewBooking))
        }

        // 3. Averías
        if (!isCollab || collabPrefs.getBoolean("can_manage_failures", true)) {
            allTools.add(HotelTool("maintenance", "Averías", "🛠️", { startToolActivity(Intent(requireContext(), HotelMaintenanceActivity::class.java)) }))
        }

        // 4. Pagos
        if (!isCollab || collabPrefs.getBoolean("can_manage_debts", false)) {
            allTools.add(HotelTool("payments", "Cuentas por pagar", "💰", { startToolActivity(Intent(requireContext(), BusinessDebtsActivity::class.java)) }))
        }

        // 5. Herramientas
        if (!isCollab || collabPrefs.getBoolean("can_manage_tools", true)) {
            allTools.add(HotelTool("inventory", "Herramientas", "🧰", { startToolActivity(Intent(requireContext(), HotelToolsActivity::class.java)) }))
        }

        toolsAdapter.submitList(allTools)
    }

    private fun checkBookingRequests() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(userId)
            .collection("booking_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                hasNewBooking = !snapshot.isEmpty
                renderTools() // Refrescar grid para mostrar badge
                
                if (hasNewBooking && !snapshot.metadata.hasPendingWrites()) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.ADDED) {
                            val name = change.document.getString("guestName") ?: "Alguien"
                            NotificationHelper.showMessageNotification(requireContext(), "Nueva Solicitud de Reserva", "$name desea reservar una habitación.")
                        }
                    }
                }
            }
    }

    private fun setupHeader() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.layoutHotelHeader, applyTop = true, applyBottom = false)
        base?.applyWindowInsetsHeight(binding.hotelHeaderBackground, applyTop = true, applyBottom = false)

        binding.btnMenuHotel.setOnClickListener { (activity as? MainActivity)?.openSideMenu() }
        binding.cardRoomsMap.setOnClickListener { startActivity(Intent(requireContext(), HotelInteractiveMapActivity::class.java)) }
        binding.btnSupportCreatorHotel.setOnClickListener { startActivity(Intent(requireContext(), SupportCreatorActivity::class.java)) }
    }

    private fun startToolActivity(intent: Intent) {
        startActivity(intent)
    }

    private fun updateWelcomeMessage() {
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val bType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
        val bName = bizPrefs.getString("${bType}_name", "Mi Negocio") ?: "Mi Negocio"

        if (isCollab) {
            val collabName = collabPrefs.getString("collab_name", "Colaborador")
            val collabRole = collabPrefs.getString("collab_role", "EQUIPO")
            binding.tvWelcome.text = "¡Hola, $collabName! 👋"
            binding.tvHotelDate.text = "Rol: $collabRole"
        } else {
            val owner = bizPrefs.getString("${bType}_owner_name", "") ?: ""
            binding.tvWelcome.text = if (owner.isNotEmpty()) "¡Hola, $owner! 👋" else "¡Hola, $bName! 👋"
            val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
            binding.tvHotelDate.text = sdf.format(Date()).replaceFirstChar { it.uppercase() }
        }
    }

    private fun updateDashboard() {
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            combine(dbInstance.hotelDao().getAllRooms(), dbInstance.hotelDao().getAllBookings()) { rooms, bookings -> Pair(rooms, bookings) }
                .collectLatest { (rooms, bookings) ->
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

    fun refreshQuickActions() { renderTools() }

    inner class HotelToolsGridAdapter : RecyclerView.Adapter<HotelToolsGridAdapter.ViewHolder>() {
        private var items = listOf<HotelTool>()
        fun submitList(list: List<HotelTool>) { items = list; notifyDataSetChanged() }

        inner class ViewHolder(val b: ItemHotelToolCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemHotelToolCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tool = items[position]
            with(holder.b) {
                tvToolName.text = tool.name
                tvToolEmoji.text = tool.emoji
                viewToolBadge.visibility = if (tool.hasBadge) View.VISIBLE else View.GONE
                root.setOnClickListener { tool.action() }
            }
        }
        override fun getItemCount() = items.size
    }

    override fun onStop() {
        super.onStop()
        hotelRegistrations.forEach { it.remove() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
