package com.naxor.app

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.naxor.app.adapter.HotelMovementAdapter
import com.naxor.app.adapter.HotelMovementItem
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.HotelBookingEntity
import com.naxor.app.data.HotelChargeEntity
import com.naxor.app.data.HotelPaymentEntity
import com.naxor.app.databinding.ActivityHotelManageRoomBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class HotelManageRoomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHotelManageRoomBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: HotelMovementAdapter
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private var roomId: String? = null
    private var activeBooking: HotelBookingEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelManageRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getStringExtra("ROOM_ID")
        val roomNumber = intent.getStringExtra("ROOM_NUMBER")
        binding.toolbarManage.title = "Habitación $roomNumber"
        binding.toolbarManage.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        loadData()
        setupListeners()
        setupPhoneInteractivity()
    }

    private fun setupPhoneInteractivity() {
        binding.tvGuestDetails.setOnClickListener {
            val phone = activeBooking?.guestPhone?.trim() ?: ""
            if (phone.isNotEmpty() && phone != "-") {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                        data = android.net.Uri.parse("tel:$phone")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo abrir el marcador", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = HotelMovementAdapter()
        binding.rvMovements.layoutManager = LinearLayoutManager(this)
        binding.rvMovements.adapter = adapter
    }

    private fun loadData() {
        if (roomId == null) return

        lifecycleScope.launch {
            val booking = database.hotelDao().getActiveBookingForRoom(roomId!!)
            if (booking == null) {
                Toast.makeText(this@HotelManageRoomActivity, "No hay estancia activa", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            activeBooking = booking
            
            binding.tvGuestName.text = booking.guestName
            binding.tvGuestDoc.text = "Doc: ${booking.guestDoc}"
            
            val phone = if (booking.guestPhone.isNotEmpty()) booking.guestPhone else "-"
            val origin = if (!booking.guestOrigin.isNullOrEmpty()) booking.guestOrigin else "-"
            val nationality = if (booking.guestNationality == "FOREIGNER") "Extranjero" else "Nacional"
            binding.tvGuestDetails.text = "Cel: $phone | Orig: $origin | $nationality"

            // Observar cargos y pagos en tiempo real
            combine(
                database.hotelDao().getChargesForBooking(booking.id),
                database.hotelDao().getPaymentsForBooking(booking.id)
            ) { charges, payments ->
                Pair(charges, payments)
            }.collect { (charges, payments) ->
                updateUI(booking, charges, payments)
            }
        }
    }

    private fun updateUI(booking: HotelBookingEntity, charges: List<HotelChargeEntity>, payments: List<HotelPaymentEntity>) {
        val totalStay = booking.totalAmount
        val totalExtraCharges = charges.sumOf { it.amount }
        val totalPayments = payments.sumOf { it.amount } + booking.deposit
        
        val totalAccount = totalStay + totalExtraCharges
        val pendingBalance = totalAccount - totalPayments

        binding.tvTotalAccount.text = "S/ ${String.format(Locale.US, "%.2f", totalAccount)}"
        binding.tvPendingBalance.text = "S/ ${String.format(Locale.US, "%.2f", pendingBalance)}"

        // Calcular Noches Pagadas
        val diff = booking.checkOutDate - booking.checkInDate
        val totalNights = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff).toInt().coerceAtLeast(1)
        val nightsFromPayments = payments.sumOf { it.nightsPaid }
        val paidNights = booking.initialNightsPaid + nightsFromPayments
        
        binding.tvPaidNights.text = "$paidNights de $totalNights"

        // Mapear a items de la lista
        val items = mutableListOf<HotelMovementItem>()
        val initialConcept = if (booking.initialNightsPaid > 0) "Pago Inicial (${booking.initialNightsPaid} noches)" else "Anticipo Check-in"
        items.add(HotelMovementItem("initial", "PAYMENT", initialConcept, booking.deposit, booking.timestamp))
        
        charges.forEach { items.add(HotelMovementItem(it.id, "CHARGE", it.concept, it.amount, it.timestamp)) }
        payments.forEach { 
            val pConcept = if (it.nightsPaid > 0) "Pago (${it.nightsPaid} noches)" else "Abono / Pago"
            items.add(HotelMovementItem(it.id, "PAYMENT", pConcept, it.amount, it.timestamp)) 
        }
        
        adapter.submitList(items.sortedByDescending { it.timestamp })
    }

    private fun setupListeners() {
        binding.btnEditGuest.setOnClickListener { showEditGuestDialog() }
        binding.btnAddCharge.setOnClickListener { showAddChargeDialog() }
        binding.btnRegisterPayment.setOnClickListener { showAddPaymentDialog() }
        binding.btnExtendStay.setOnClickListener { showExtendStayDialog() }
        binding.btnRequestCleaning.setOnClickListener { markRoomForCleaning() }
        binding.btnCheckOut.setOnClickListener { performCheckOut() }
    }

    private fun markRoomForCleaning() {
        val booking = activeBooking ?: return
        lifecycleScope.launch {
            val room = database.hotelDao().getRoomById(booking.roomId) ?: return@launch
            database.hotelDao().updateRoom(room.copy(lastCleaned = 0L))
            Toast.makeText(this@HotelManageRoomActivity, "Solicitud de limpieza registrada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditGuestDialog() {
        val booking = activeBooking ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 30)
        }

        val etName = EditText(this).apply {
            hint = "Nombre Completo"
            setText(booking.guestName)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val etDoc = EditText(this).apply {
            hint = "DNI / Pasaporte"
            setText(booking.guestDoc)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etPhone = EditText(this).apply {
            hint = "Teléfono"
            setText(booking.guestPhone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val etOrigin = EditText(this).apply {
            hint = "Procedencia"
            setText(booking.guestOrigin)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val checkForeigner = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Extranjero"
            isChecked = booking.guestNationality == "FOREIGNER"
        }

        layout.addView(etName); layout.addView(etDoc); layout.addView(etPhone); layout.addView(etOrigin); layout.addView(checkForeigner)

        AlertDialog.Builder(this)
            .setTitle("Editar datos")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = etName.text.toString().trim()
                val newDoc = etDoc.text.toString().trim()
                val newPhone = etPhone.text.toString().trim()
                val newOrigin = etOrigin.text.toString().trim()
                val newNat = if (checkForeigner.isChecked) "FOREIGNER" else "NATIONAL"

                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        val updatedBooking = booking.copy(
                            guestName = newName,
                            guestDoc = newDoc,
                            guestPhone = newPhone,
                            guestOrigin = newOrigin,
                            guestNationality = newNat
                        )
                        database.hotelDao().updateBooking(updatedBooking)
                        activeBooking = updatedBooking
                        binding.tvGuestName.text = newName
                        binding.tvGuestDoc.text = "Doc: $newDoc"
                        val natLabel = if (newNat == "FOREIGNER") "Extranjero" else "Nacional"
                        binding.tvGuestDetails.text = "Cel: ${if(newPhone.isEmpty()) "-" else newPhone} | Orig: ${if(newOrigin.isEmpty()) "-" else newOrigin} | $natLabel"
                        Toast.makeText(this@HotelManageRoomActivity, "Datos actualizados", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showExtendStayDialog() {
        val booking = activeBooking ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }
        val etDays = EditText(this).apply { 
            hint = "Cantidad de noches extra"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(etDays)

        AlertDialog.Builder(this)
            .setTitle("Extender Estancia")
            .setMessage("Se sumará el costo proporcional según la tarifa de la habitación.")
            .setView(layout)
            .setPositiveButton("Extender") { _, _ ->
                val extraDays = etDays.text.toString().toIntOrNull() ?: 0
                if (extraDays > 0) { extendStay(booking, extraDays) }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun extendStay(booking: HotelBookingEntity, extraDays: Int) {
        lifecycleScope.launch {
            val room = database.hotelDao().getRoomById(booking.roomId) ?: return@launch
            val extraChargeAmount = room.baseRate * extraDays
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = booking.checkOutDate
            cal.add(java.util.Calendar.DAY_OF_YEAR, extraDays)
            database.hotelDao().insertCharge(HotelChargeEntity(bookingId = booking.id, concept = "Extensión de estancia ($extraDays noches)", amount = extraChargeAmount))
            val updatedBooking = booking.copy(checkOutDate = cal.timeInMillis, totalAmount = booking.totalAmount + extraChargeAmount)
            database.hotelDao().updateBooking(updatedBooking)
            activeBooking = updatedBooking
            Toast.makeText(this@HotelManageRoomActivity, "Estancia extendida correctamente", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddChargeDialog() {
        val booking = activeBooking ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 20, 60, 20) }
        val etConcept = EditText(this).apply { hint = "Concepto (Ej: Gaseosa, Desayuno)" }
        val etAmount = EditText(this).apply { hint = "Monto S/"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        layout.addView(etConcept); layout.addView(etAmount)
        AlertDialog.Builder(this).setTitle("Añadir Consumo").setView(layout).setPositiveButton("Añadir") { _, _ ->
            val concept = etConcept.text.toString(); val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (concept.isNotEmpty() && amount > 0) { lifecycleScope.launch { database.hotelDao().insertCharge(HotelChargeEntity(bookingId = booking.id, concept = concept, amount = amount)) } }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun showAddPaymentDialog() {
        val booking = activeBooking ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 20, 60, 20) }
        val etAmount = EditText(this).apply { hint = "Monto del Pago S/"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val etNights = EditText(this).apply { hint = "Noches que cubre (Opcional)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(etAmount); layout.addView(etNights)
        AlertDialog.Builder(this).setTitle("Registrar Pago").setView(layout).setPositiveButton("Pagar") { _, _ ->
            val amount = etAmount.text.toString().toDoubleOrNull() ?: 0.0; val nights = etNights.text.toString().toIntOrNull() ?: 0
            if (amount > 0) { lifecycleScope.launch { database.hotelDao().insertPayment(HotelPaymentEntity(bookingId = booking.id, amount = amount, nightsPaid = nights)) } }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun performCheckOut() {
        val booking = activeBooking ?: return
        lifecycleScope.launch {
            val charges = database.hotelDao().getChargesForBooking(booking.id).first()
            val payments = database.hotelDao().getPaymentsForBooking(booking.id).first()
            val totalAccount = booking.totalAmount + charges.sumOf { it.amount }
            val totalPaid = booking.deposit + payments.sumOf { it.amount }
            val balance = totalAccount - totalPaid
            if (balance > 0.01) {
                AlertDialog.Builder(this@HotelManageRoomActivity).setTitle("Saldo Pendiente").setMessage("Aún falta pagar S/ ${String.format(Locale.US, "%.2f", balance)}.\n\n¿Qué desea hacer?")
                    .setPositiveButton("Confirmar Salida") { _, _ -> showReceiptOptions(booking, charges, payments) }
                    .setNeutralButton("Llamar Huésped") { _, _ -> val phone = booking.guestPhone.trim(); if (phone.isNotEmpty() && phone != "-") { startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))) } else { Toast.makeText(this@HotelManageRoomActivity, "Sin número de teléfono", Toast.LENGTH_SHORT).show() } }
                    .setNegativeButton("Cancelar", null).show()
            } else { showReceiptOptions(booking, charges, payments) }
        }
    }

    private fun showReceiptOptions(booking: HotelBookingEntity, charges: List<HotelChargeEntity>, payments: List<HotelPaymentEntity>) {
        val options = arrayOf("Emitir Boleta", "Emitir Factura", "Solo Check-out (Sin comprobante)")
        AlertDialog.Builder(this).setTitle("Comprobante de Pago").setItems(options) { _, which ->
            when (which) { 0 -> startReceiptFlow("BOLETA", booking, charges, payments); 1 -> startReceiptFlow("FACTURA", booking, charges, payments); 2 -> finishCheckOut(booking) }
        }.show()
    }

    private fun startReceiptFlow(type: String, booking: HotelBookingEntity, charges: List<HotelChargeEntity>, payments: List<HotelPaymentEntity>) {
        val loading = AlertDialog.Builder(this).setView(android.widget.ProgressBar(this).apply { setPadding(50, 50, 50, 50) }).setMessage("Generando $type...").setCancelable(false).show()
        handler.postDelayed({ loading.dismiss(); showReceiptPreview(type, booking, charges, payments) }, 1500)
    }

    private fun showReceiptPreview(type: String, booking: HotelBookingEntity, charges: List<HotelChargeEntity>, payments: List<HotelPaymentEntity>) {
        val receiptText = generateReceiptText(type, booking, charges, payments)
        AlertDialog.Builder(this).setTitle("Vista Previa de $type").setMessage(receiptText).setPositiveButton("Enviar por WhatsApp") { _, _ -> sendToWhatsApp(booking.guestPhone, receiptText); finishCheckOut(booking) }.setNegativeButton("Cerrar") { _, _ -> finishCheckOut(booking) }.show()
    }

    private fun generateReceiptText(type: String, booking: HotelBookingEntity, charges: List<HotelChargeEntity>, payments: List<HotelPaymentEntity>): String {
        val sb = StringBuilder(); sb.append("🏨 *NEXUR HOTEL - $type*\n----------------------------\n*Huésped:* ${booking.guestName}\n*Doc:* ${booking.guestDoc}\n----------------------------\n*Detalle:*\n- Estancia: S/ ${String.format(Locale.US, "%.2f", booking.totalAmount)}\n")
        charges.forEach { sb.append("- ${it.concept}: S/ ${String.format(Locale.US, "%.2f", it.amount)}\n") }
        sb.append("----------------------------\n")
        val total = booking.totalAmount + charges.sumOf { it.amount }; sb.append("*TOTAL:* S/ ${String.format(Locale.US, "%.2f", total)}\n")
        val totalPaid = booking.deposit + payments.sumOf { it.amount }; sb.append("*PAGADO:* S/ ${String.format(Locale.US, "%.2f", totalPaid)}\n")
        val balance = total - totalPaid
        if (balance > 0.01) sb.append("*PENDIENTE:* S/ ${String.format(Locale.US, "%.2f", balance)}\n")
        sb.append("----------------------------\n¡Gracias por su estancia!"); return sb.toString()
    }

    private fun sendToWhatsApp(phone: String, message: String) {
        val cleanPhone = phone.replace(" ", "").replace("-", ""); val formattedPhone = if (cleanPhone.startsWith("+")) cleanPhone else "+51$cleanPhone"
        try { val intent = android.content.Intent(android.content.Intent.ACTION_VIEW); val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=" + java.net.URLEncoder.encode(message, "UTF-8"); intent.data = android.net.Uri.parse(url); startActivity(intent) }
        catch (e: Exception) { Toast.makeText(this, "Error al abrir WhatsApp", Toast.LENGTH_SHORT).show() }
    }

    private fun finishCheckOut(booking: HotelBookingEntity) {
        lifecycleScope.launch { database.hotelDao().updateRoomStatus(booking.roomId, "DIRTY"); database.hotelDao().updateBooking(booking.copy(status = "CHECKED_OUT")); Toast.makeText(this@HotelManageRoomActivity, "Check-out completado", Toast.LENGTH_SHORT).show(); finish() }
    }
}
