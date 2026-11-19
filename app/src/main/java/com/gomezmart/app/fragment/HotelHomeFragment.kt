package com.gomezmart.app.fragment

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.*
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.HotelRoomEntity
import com.gomezmart.app.data.HotelTool
import com.gomezmart.app.databinding.BottomSheetRoomSelectionBinding
import com.gomezmart.app.databinding.FragmentHotelHomeBinding
import com.gomezmart.app.databinding.ItemHotelToolCardBinding
import com.gomezmart.app.databinding.ItemSheetRoomChoiceBinding
import com.gomezmart.app.util.BillingManager
import com.gomezmart.app.util.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HotelHomeFragment : Fragment() {

    private var _binding: FragmentHotelHomeBinding? = null
    private val binding get() = _binding!!
    private val database: AppDatabase?
        get() = context?.applicationContext?.let { AppDatabase.getDatabase(it) }
    private val hotelRegistrations = mutableListOf<ListenerRegistration>()
    
    private var hasNewBooking = false
    private var hasNewCollabRequest = false
    private var hasNewMessage = false
    private var billingManager: BillingManager? = null
    private var isStoreVerified = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotelHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupPlanProButton()
        setupBentoActions()
        updateDashboard()
        updateWelcomeMessage()
    }

    override fun onStart() {
        super.onStart()
        if (hotelRegistrations.isEmpty()) {
            val ctx = context ?: return
            val sm = SyncManager(ctx)
            hotelRegistrations.addAll(sm.startRealtimeHotelSync { })
            sm.startRealtimeUnreadCountSync { count ->
                val unread = count > 0
                if (hasNewMessage != unread) {
                    hasNewMessage = unread
                    if (_binding != null && isAdded) renderTools()
                }
            }?.let { hotelRegistrations.add(it) }
            checkBookingRequests()
            checkCollaboratorRequests()
            attachPlanProListener()
        }
    }

    private fun setupBentoActions() {
        val b = _binding ?: return

        // Acciones Rápidas del Día
        b.cardQuickHospedar.setOnClickListener { handleQuickHospedar() }
        b.cardQuickSalida.setOnClickListener { handleQuickSalida() }
        b.cardQuickLimpieza.setOnClickListener { handleQuickLimpieza() }

        // Hero: Subir negocio al mercado / Mi página web
        b.cardMarketHero.setOnClickListener { handleMarketClick() }

        // Bento Asymmetric: Reservas, Averías, Historial
        b.cardToolBookings.setOnClickListener {
            startToolActivity(Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelBookingsActivity"))
        }
        b.cardToolMaintenance.setOnClickListener {
            startToolActivity(Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelMaintenanceActivity"))
        }
        b.cardToolGuests.setOnClickListener {
            startToolActivity(Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelGuestHistoryActivity"))
        }

        // Acciones Adicionales: Cuentas, Herramientas, Colaboradores, Mensajes
        b.cardToolPayments.setOnClickListener {
            startToolActivity(Intent(requireContext(), BusinessDebtsActivity::class.java))
        }
        b.cardToolInventory.setOnClickListener {
            startToolActivity(Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelToolsActivity"))
        }
        b.cardToolTeam.setOnClickListener {
            startToolActivity(Intent().setClassName(requireContext().packageName, "com.gomezmart.feature_hotel.HotelManageTeamActivity"))
        }
        b.cardToolMessages.setOnClickListener {
            startToolActivity(Intent(requireContext(), MailboxActivity::class.java))
        }

        renderTools()
    }

    private fun handleMarketClick() {
        val baseActivity = activity as? BaseActivity
        if (baseActivity?.requireLoginForGuest("publicar o editar tu negocio en el Mercado en Línea") != true) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            val openLivePage = {
                startToolActivity(Intent(requireContext(), StoreProfileActivity::class.java).apply {
                    putExtra("STORE_ID", uid)
                    putExtra("IS_OWNER", true)
                    putExtra("FORCE_RUBRO", "HOTEL")
                })
            }
            val ctx = context ?: return
            val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
            val isHotelOnline = bizPrefs.getBoolean("HOTEL_is_online", bizPrefs.getBoolean("is_online_store", false))
            val savedHotelName = bizPrefs.getString("HOTEL_name", "") ?: ""
            val hasStartedWorkingOnPage = savedHotelName.isNotBlank() && !savedHotelName.equals("Mi Negocio", ignoreCase = true)
            val hideIntroDialog = bizPrefs.getBoolean("HOTEL_hide_create_page_intro", false) || hasStartedWorkingOnPage

            if (isHotelOnline || hideIntroDialog) {
                openLivePage()
            } else {
                val density = resources.displayMetrics.density
                val dialogContainer = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val hPad = (24 * density).toInt()
                    val topPad = (12 * density).toInt()
                    setPadding(hPad, topPad, hPad, 0)
                }
                val cbDontShowAgain = android.widget.CheckBox(requireContext()).apply {
                    text = "No volver a mostrar otra vez"
                    textSize = 13.5f
                }
                dialogContainer.addView(cbDontShowAgain)

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Crear página de negocio")
                    .setMessage("Antes de subir al mercado tu negocio, vamos a crear tu página.")
                    .setView(dialogContainer)
                    .setNegativeButton("Salir") { _, _ ->
                        if (cbDontShowAgain.isChecked) {
                            bizPrefs.edit().putBoolean("HOTEL_hide_create_page_intro", true).apply()
                            renderTools()
                        }
                    }
                    .setPositiveButton("Crear mi página") { _, _ ->
                        if (cbDontShowAgain.isChecked) {
                            bizPrefs.edit().putBoolean("HOTEL_hide_create_page_intro", true).apply()
                        }
                        openLivePage()
                    }
                    .show()
            }
        }
    }

    private fun renderTools() {
        val ctx = context ?: return
        val b = _binding ?: return
        if (!isAdded) return
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val collabPrefs = ctx.getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)

        // Hero: Mi página / Subir al mercado
        if (isCollab) {
            b.cardMarketHero.visibility = View.GONE
        } else {
            b.cardMarketHero.visibility = View.VISIBLE
            val isHotelOnline = bizPrefs.getBoolean("HOTEL_is_online", bizPrefs.getBoolean("is_online_store", false))
            if (isHotelOnline) {
                b.tvMarketHeroTag.text = "🌐 MI PÁGINA VIRTUAL"
                b.tvMarketHeroTitle.text = "Mi Página & Catálogo Online"
                b.tvMarketHeroSubtitle.text = "Gestiona tu vitrina virtual y reservas"
                b.tvMarketHeroAction.text = "Ver en vivo ➔"
            } else {
                b.tvMarketHeroTag.text = "🏪 MERCADO EN LÍNEA"
                b.tvMarketHeroTitle.text = "Subir negocio al mercado"
                b.tvMarketHeroSubtitle.text = "Crea tu página web gratis y recibe reservas"
                b.tvMarketHeroAction.text = "Crear mi página ➔"
            }
        }

        // Permisos del Bento principal
        val canBookings = !isCollab || collabPrefs.getBoolean("can_manage_bookings", true)
        val canFailures = !isCollab || collabPrefs.getBoolean("can_manage_failures", true)
        val canHistory = !isCollab || collabPrefs.getBoolean("can_view_history", true)

        b.cardToolBookings.visibility = if (canBookings) View.VISIBLE else View.GONE
        b.cardToolMaintenance.visibility = if (canFailures) View.VISIBLE else View.GONE
        b.cardToolGuests.visibility = if (canHistory) View.VISIBLE else View.GONE
        b.layoutBentoRightCol.visibility = if (canFailures || canHistory) View.VISIBLE else View.GONE
        b.layoutBentoMain.visibility = if (canBookings || canFailures || canHistory) View.VISIBLE else View.GONE

        // Permisos de herramientas adicionales
        val canPayments = !isCollab || collabPrefs.getBoolean("can_manage_debts", false)
        val canInventory = !isCollab || collabPrefs.getBoolean("can_manage_tools", true)
        val canTeam = !isCollab || collabPrefs.getBoolean("can_manage_team", false)

        b.cardToolPayments.visibility = if (canPayments) View.VISIBLE else View.GONE
        b.cardToolInventory.visibility = if (canInventory) View.VISIBLE else View.GONE
        b.layoutRowTools1.visibility = if (canPayments || canInventory) View.VISIBLE else View.GONE

        b.cardToolTeam.visibility = if (canTeam) View.VISIBLE else View.GONE
        b.cardToolMessages.visibility = View.VISIBLE
        b.layoutRowTools2.visibility = View.VISIBLE

        b.tvExtraToolsTitle.visibility = if (canPayments || canInventory || canTeam) View.VISIBLE else View.GONE

        // Badges en vivo
        b.badgeBookings.visibility = if (hasNewBooking) View.VISIBLE else View.GONE
        b.badgeTeam.visibility = if (hasNewCollabRequest) View.VISIBLE else View.GONE
        b.badgeMessages.visibility = if (hasNewMessage) View.VISIBLE else View.GONE
    }

    private fun checkCollaboratorRequests() {
        val ctx = context ?: return
        if (com.gomezmart.app.SyncManager(ctx).isGuestUser()) return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val reg = FirebaseFirestore.getInstance().collection("users").document(userId)
            .collection("collaborator_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !isAdded || _binding == null) return@addSnapshotListener

                hasNewCollabRequest = !snapshot.isEmpty
                renderTools()
            }
        hotelRegistrations.add(reg)
    }

    private fun checkBookingRequests() {
        val ctx = context ?: return
        if (com.gomezmart.app.SyncManager(ctx).isGuestUser()) return
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val reg = FirebaseFirestore.getInstance().collection("users").document(userId)
            .collection("booking_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !isAdded || _binding == null) return@addSnapshotListener

                hasNewBooking = !snapshot.isEmpty
                renderTools()

                val safeCtx = context ?: return@addSnapshotListener
                if (hasNewBooking && !snapshot.metadata.hasPendingWrites()) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.ADDED) {
                            val name = change.document.getString("guestName") ?: "Alguien"
                            NotificationHelper.showMessageNotification(safeCtx, "Nueva Solicitud de Reserva", "$name desea reservar una habitación.")
                        }
                    }
                }
            }
        hotelRegistrations.add(reg)
    }

    private fun setupHeader() {
        val base = activity as? BaseActivity
        base?.applyWindowInsets(binding.layoutHotelHeader, applyTop = true, applyBottom = false)
        base?.applyWindowInsetsHeight(binding.hotelHeaderBackground, applyTop = true, applyBottom = false)

        binding.btnMenuHotel.setOnClickListener { (activity as? MainActivity)?.openSideMenu() }
        binding.cardRoomsMap.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startToolActivity(Intent().setClassName(ctx.packageName, "com.gomezmart.feature_hotel.HotelInteractiveMapActivity"))
        }
    }

    private fun setupPlanProButton() {
        val ctx = context ?: return
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        if (isCollab) {
            binding.btnHotelPlanPro.visibility = View.GONE
            return
        }

        binding.btnHotelPlanPro.visibility = View.VISIBLE
        binding.btnHotelPlanPro.setOnClickListener {
            val baseActivity = activity as? BaseActivity
            if (baseActivity?.requireLoginForGuest("obtener el Plan Profesional y la insignia oficial") == true) return@setOnClickListener
            if (isStoreVerified) {
                showVerifiedStoreInfoDialog()
            } else {
                showSubscriptionBottomSheet()
            }
        }

        billingManager = BillingManager(ctx) { verified, _ ->
            isStoreVerified = verified
            if (_binding != null && isAdded) updatePlanProButtonUI()
        }
        billingManager?.startConnection()
        updatePlanProButtonUI()
    }

    private fun attachPlanProListener() {
        val ctx = context ?: return
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        if (bizPrefs.getBoolean("is_collaborator_mode", false)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (!uid.isNullOrEmpty() && !SyncManager(ctx).isGuestUser()) {
            val reg = FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("settings").document("HOTEL")
                .addSnapshotListener { doc, _ ->
                    val safeCtx = context ?: return@addSnapshotListener
                    if (doc != null && doc.exists()) {
                        isStoreVerified = doc.getBoolean("is_verified") ?: false
                        val tier = doc.getString("subscription_tier")
                        if (!tier.isNullOrEmpty()) {
                            safeCtx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                                .putString("subscription_tier", tier)
                                .apply()
                        }
                        if (_binding != null && isAdded) updatePlanProButtonUI()
                    }
                }
            hotelRegistrations.add(reg)
        }
    }

    private fun updatePlanProButtonUI() {
        val ctx = context ?: return
        if (isStoreVerified) {
            val tier = BillingManager.getActiveTier(ctx)
            binding.btnHotelPlanPro.text = if (tier == BillingManager.TIER_VIP_30) {
                "👑 Plan Empresarial Activo ✔"
            } else {
                "🛡️ Plan Profesional Activo ✔"
            }
            binding.btnHotelPlanPro.setTextColor(ContextCompat.getColor(ctx, R.color.emerald_700))
            binding.btnHotelPlanPro.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.emerald_200))
            binding.btnHotelPlanPro.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.emerald_50))
        } else {
            binding.btnHotelPlanPro.text = "⭐ Comprar Plan Profesional"
            binding.btnHotelPlanPro.setTextColor(ContextCompat.getColor(ctx, R.color.purple_700))
            binding.btnHotelPlanPro.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.purple_200))
            binding.btnHotelPlanPro.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.purple_50))
        }
    }

    private fun showSubscriptionBottomSheet() {
        val act = activity ?: return
        BillingManager.showSubscriptionBottomSheet(act, billingManager)
    }

    private fun showVerifiedStoreInfoDialog() {
        val ctx = context ?: return
        val act = activity ?: return
        val tier = BillingManager.getActiveTier(ctx)
        val isVip = tier == BillingManager.TIER_VIP_30
        val title = if (isVip) "¡Plan Empresarial VIP Activo! 👑" else "¡Plan Profesional Activo! 🛡️"
        val msg = if (isVip) {
            "Tu negocio cuenta con el Plan Empresarial VIP (1.5 GB en la Nube, Posicionamiento Top en el Mercado e IA ilimitada)."
        } else {
            "Tu negocio cuenta con el Plan Profesional (250 MB en la Nube e Insignia Oficial de Verificación). ¿Deseas ver los beneficios del Plan Empresarial VIP (S/ 30/mes)?"
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setIcon(R.drawable.ic_verified_badge)
            .setPositiveButton("Excelente", null)
        if (!isVip) {
            builder.setNeutralButton("Ver Plan VIP (S/ 30)") { _, _ ->
                BillingManager.showSubscriptionBottomSheet(act, billingManager, BillingManager.TIER_VIP_30)
            }
        }
        builder.show()
    }

    private fun startToolActivity(intent: Intent) {
        (activity as? BaseActivity)?.startActivityWithFade(intent) ?: startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updateWelcomeMessage()
        renderTools()
    }

    private fun updateWelcomeMessage() {
        val ctx = context ?: return
        val b = _binding ?: return
        val appPrefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val bizPrefs = ctx.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val collabPrefs = ctx.getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        val bType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
        val bName = bizPrefs.getString("${bType}_name", "Mi Negocio") ?: "Mi Negocio"

        if (isCollab) {
            val collabName = collabPrefs.getString("collab_name", "Colaborador")
            val collabRole = collabPrefs.getString("collab_role", "EQUIPO")
            b.tvWelcome.text = "¡Hola, $collabName! 👋"
            b.tvHotelDate.text = "Rol: $collabRole"
        } else {
            val userFullName = appPrefs.getString("user_full_name", null)?.takeIf { it.isNotBlank() }
                ?: bizPrefs.getString("${bType}_owner_name", null)?.takeIf { it.isNotBlank() }
                ?: FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: bName
            b.tvWelcome.text = "¡Hola, $userFullName! 👋"
            val sdf = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "PE"))
            b.tvHotelDate.text = sdf.format(Date()).replaceFirstChar { it.uppercase() }
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
                        val free = rooms.count { it.status != "OCCUPIED" && it.status != "MAINTENANCE" }
                        val reserved = bookings.count { it.status == "CONFIRMED" }

                        val startOfToday = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        val dirtyCount = rooms.count { it.status == "DIRTY" || it.status == "MAINTENANCE" || it.lastCleaned < startOfToday }

                        b.tvStatOccupied.text = occupied.toString()
                        b.tvStatFree.text = free.toString()
                        b.tvStatReserved.text = reserved.toString()
                        b.tvStatDirty.text = dirtyCount.toString()

                        val occupancyPercent = if (total > 0) ((occupied.toDouble() / total) * 100).toInt() else 0
                        b.tvOccupancyBadge.text = "$occupancyPercent% Ocupado"
                        b.progressOccupancy.progress = occupancyPercent

                        b.tvQuickLimpiezaLabel.text = if (dirtyCount > 0) "Aseo ($dirtyCount)" else "Aseo al día"
                        b.tvRoomsSummary.text = "$free de $total habitaciones disponibles"
                    }
                }
        }
    }

    private fun handleQuickHospedar() {
        val safeCtx = context ?: return
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = dbInstance.hotelDao().getAllRooms().first()
            val freeRooms = rooms.filter { it.status == "FREE" }
            if (freeRooms.isEmpty()) {
                Toast.makeText(safeCtx, "No hay habitaciones libres en este momento", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (freeRooms.size == 1) {
                val selected = freeRooms[0]
                val i = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelCheckInActivity")
                i.putExtra("ROOM_ID", selected.id)
                i.putExtra("ROOM_NUMBER", selected.number)
                i.putExtra("BASE_RATE", selected.baseRate)
                startActivity(i)
            } else {
                showRoomSelectionBottomSheet(
                    title = "Hospedar en Habitación",
                    subtitle = "Elige una habitación para registrar al cliente",
                    countText = "${freeRooms.size} Libres",
                    rooms = freeRooms,
                    badgeFormatter = { String.format(Locale.US, "S/ %.2f", it.baseRate) },
                    badgeColorRes = R.color.emerald_700
                ) { selected, dialog ->
                    dialog.dismiss()
                    val i = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelCheckInActivity")
                    i.putExtra("ROOM_ID", selected.id)
                    i.putExtra("ROOM_NUMBER", selected.number)
                    i.putExtra("BASE_RATE", selected.baseRate)
                    startActivity(i)
                }
            }
        }
    }

    private fun handleQuickSalida() {
        val safeCtx = context ?: return
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = dbInstance.hotelDao().getAllRooms().first()
            val occupiedRooms = rooms.filter { it.status == "OCCUPIED" }
            if (occupiedRooms.isEmpty()) {
                Toast.makeText(safeCtx, "No hay habitaciones ocupadas en este momento", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showRoomSelectionBottomSheet(
                title = "Dar Salida / Cobro",
                subtitle = "Selecciona la habitación ocupada para gestionar",
                countText = "${occupiedRooms.size} Ocupadas",
                rooms = occupiedRooms,
                badgeFormatter = { "Gestionar ➔" },
                badgeColorRes = R.color.red_700
            ) { selected, dialog ->
                dialog.dismiss()
                val intent = Intent().setClassName(safeCtx.packageName, "com.gomezmart.feature_hotel.HotelManageRoomActivity")
                intent.putExtra("ROOM_ID", selected.id)
                intent.putExtra("ROOM_NUMBER", selected.number)
                startActivity(intent)
            }
        }
    }

    private fun handleQuickLimpieza() {
        val safeCtx = context ?: return
        val dbInstance = database ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val rooms = dbInstance.hotelDao().getAllRooms().first()
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dirtyRooms = rooms.filter { it.status == "DIRTY" || it.status == "MAINTENANCE" || it.lastCleaned < startOfToday }
            if (dirtyRooms.isEmpty()) {
                Toast.makeText(safeCtx, "¡Todas las habitaciones están aseadas! ✨", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showRoomSelectionBottomSheet(
                title = "Habitaciones por Asear",
                subtitle = "Toca para marcar como limpia y disponible",
                countText = "${dirtyRooms.size} Por Asear",
                rooms = dirtyRooms,
                badgeFormatter = { "Marcar limpia ✨" },
                badgeColorRes = R.color.orange_700
            ) { selected, dialog ->
                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    val newStatus = if (selected.status == "OCCUPIED") "OCCUPIED" else "FREE"
                    val updated = selected.copy(status = newStatus, lastCleaned = System.currentTimeMillis())
                    dbInstance.hotelDao().updateRoom(updated)
                    SyncManager(safeCtx.applicationContext).syncHotelRoomToCloud(updated)
                    Toast.makeText(safeCtx, "Habitación ${selected.number} marcada como limpia ✨", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRoomSelectionBottomSheet(
        title: String,
        subtitle: String,
        countText: String,
        rooms: List<HotelRoomEntity>,
        badgeFormatter: (HotelRoomEntity) -> String,
        badgeColorRes: Int,
        onRoomSelected: (HotelRoomEntity, BottomSheetDialog) -> Unit
    ) {
        val safeCtx = context ?: return
        val dialog = BottomSheetDialog(safeCtx)
        val sheetBinding = BottomSheetRoomSelectionBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetTitle.text = title
        sheetBinding.tvSheetSubtitle.text = subtitle
        sheetBinding.tvSheetCountBadge.text = countText

        if (rooms.isEmpty()) {
            sheetBinding.rvSheetRooms.visibility = View.GONE
            sheetBinding.tvSheetEmpty.visibility = View.VISIBLE
        } else {
            sheetBinding.rvSheetRooms.visibility = View.VISIBLE
            sheetBinding.tvSheetEmpty.visibility = View.GONE
            sheetBinding.rvSheetRooms.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(safeCtx)
            sheetBinding.rvSheetRooms.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val itemBinding = ItemSheetRoomChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    return object : RecyclerView.ViewHolder(itemBinding.root) {}
                }

                override fun getItemCount() = rooms.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val room = rooms[position]
                    val itemBinding = ItemSheetRoomChoiceBinding.bind(holder.itemView)

                    val emoji = when (room.type.uppercase()) {
                        "SIMPLE" -> "🛏️"
                        "DOBLE" -> "👥"
                        "MATRIMONIAL" -> "❤️"
                        "SUITE" -> "🌟"
                        else -> "🏢"
                    }
                    itemBinding.tvSheetRoomEmoji.text = emoji
                    itemBinding.tvSheetRoomName.text = "Habitación ${room.number}"
                    val floorText = if (room.floor > 0) " • Piso ${room.floor}" else ""
                    itemBinding.tvSheetRoomDetail.text = "${room.type}$floorText"

                    itemBinding.tvSheetRoomBadge.text = badgeFormatter(room)
                    itemBinding.tvSheetRoomBadge.setTextColor(ContextCompat.getColor(safeCtx, badgeColorRes))

                    itemBinding.root.setOnClickListener {
                        onRoomSelected(room, dialog)
                    }
                }
            }
        }
        dialog.show()
    }

    fun refreshQuickActions() { renderTools() }

    override fun onStop() {
        super.onStop()
        hotelRegistrations.forEach { it.remove() }
        hotelRegistrations.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        billingManager?.destroy()
        billingManager = null
        _binding = null
    }
}
