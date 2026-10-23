package com.gomezmart.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.data.OrderModel
import com.gomezmart.app.databinding.ActivityOrderTrackingBinding
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class OrderTrackingActivity : BaseActivity() {

    private lateinit var binding: ActivityOrderTrackingBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var orderRegistration: ListenerRegistration? = null
    private var deliveryPersonRegistration: ListenerRegistration? = null
    private var orderId: String = ""
    
    private var deliveryMarker: Marker? = null
    private var destinationMarker: Marker? = null
    
    private var isFollowModeActive = true
    private val handler = Handler(Looper.getMainLooper())
    private val followReactivationDelay = 4000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets
        applyWindowInsets(binding.toolbarTracking, applyTop = true, applyBottom = false)

        orderId = intent.getStringExtra("ORDER_ID") ?: ""
        if (orderId.isEmpty()) {
            finish()
            return
        }

        setupToolbar()
        startOrderListener()
    }

    private fun setupToolbar() {
        binding.toolbarTracking.setNavigationOnClickListener { finish() }
    }

    private fun startOrderListener() {
        orderRegistration = db.collection("orders").document(orderId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val order = snapshot.toObject(OrderModel::class.java)?.copy(id = snapshot.id)
                    if (order != null) {
                        updateUI(order)
                    }
                }
            }
    }

    private fun updateUI(order: OrderModel) {
        binding.tvTrackingStore.text = "Tienda: ${order.storeName}"
        
        val isCancelled = order.status == "CANCELLED"
        binding.cardTrackingCancelled.visibility = if (isCancelled) View.VISIBLE else View.GONE
        binding.cardTrackingStatus.visibility = if (isCancelled) View.GONE else View.VISIBLE

        val statusText = when (order.status) {
            "PENDING" -> "PEDIDO RECIBIDO ⏳"
            "ACCEPTED" -> "PREPARANDO TU PEDIDO 👨‍🍳"
            "EN_ROUTE" -> "¡PEDIDO EN CAMINO! 🛵"
            "DELIVERED" -> "PEDIDO ENTREGADO ✅"
            "CANCELLED" -> "PEDIDO CANCELADO 🚫"
            else -> order.status
        }
        binding.tvTrackingStatus.text = statusText

        // Update Timeline
        if (isCancelled) {
            binding.layoutTrackingTimeline.visibility = View.GONE
        } else {
            binding.layoutTrackingTimeline.visibility = View.VISIBLE
            resetTimeline()
            when (order.status) {
                "PENDING" -> markStep(1)
                "ACCEPTED" -> { markStep(1); markStep(2) }
                "EN_ROUTE" -> { markStep(1); markStep(2); markStep(3) }
                "DELIVERED" -> { markStep(1); markStep(2); markStep(3); markStep(4) }
            }
        }

        // Setup Map if En Route
        if (order.status == "EN_ROUTE") {
            binding.cardTrackingMap.visibility = View.VISIBLE
            setupMap(order)
            startDeliveryPersonTracking(order.deliveryPersonId)
            
            binding.btnFullscreenMap.setOnClickListener {
                val intent = Intent(this, OrderTrackingFullscreenActivity::class.java).apply {
                    putExtra("ORDER_ID", orderId)
                }
                startActivity(intent)
            }
        } else {
            binding.cardTrackingMap.visibility = View.GONE
            stopDeliveryPersonTracking()
        }

        binding.btnTrackingContact.setOnClickListener {
            openChat(order)
        }
    }

    private fun resetTimeline() {
        binding.dotPending.setBackgroundResource(R.drawable.bg_circle_slate)
        binding.dotAccepted.setBackgroundResource(R.drawable.bg_circle_slate)
        binding.dotEnRoute.setBackgroundResource(R.drawable.bg_circle_slate)
        binding.dotDelivered.setBackgroundResource(R.drawable.bg_circle_slate)
        
        binding.textPending.setTextColor(Color.parseColor("#94A3B8"))
        binding.textAccepted.setTextColor(Color.parseColor("#94A3B8"))
        binding.textEnRoute.setTextColor(Color.parseColor("#94A3B8"))
        binding.textDelivered.setTextColor(Color.parseColor("#94A3B8"))
        
        binding.line1.setBackgroundColor(Color.parseColor("#E2E8F0"))
        binding.line2.setBackgroundColor(Color.parseColor("#E2E8F0"))
        binding.line3.setBackgroundColor(Color.parseColor("#E2E8F0"))
    }

    private fun markStep(step: Int) {
        val activeColor = Color.parseColor("#7C3AED") // Purple 600
        when (step) {
            1 -> {
                binding.dotPending.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.textPending.setTextColor(activeColor)
            }
            2 -> {
                binding.dotPending.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotAccepted.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.textAccepted.setTextColor(activeColor)
                binding.line1.setBackgroundColor(activeColor)
            }
            3 -> {
                binding.dotPending.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotAccepted.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotEnRoute.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.textEnRoute.setTextColor(activeColor)
                binding.line1.setBackgroundColor(activeColor)
                binding.line2.setBackgroundColor(activeColor)
            }
            4 -> {
                binding.dotPending.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotAccepted.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotEnRoute.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.dotDelivered.setBackgroundResource(R.drawable.bg_circle_purple)
                binding.textDelivered.setTextColor(activeColor)
                binding.line1.setBackgroundColor(activeColor)
                binding.line2.setBackgroundColor(activeColor)
                binding.line3.setBackgroundColor(activeColor)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap(order: OrderModel) {
        binding.mapTracking.apply {
            setMultiTouchControls(true)
            setFlingEnabled(true)
            isTilesScaledToDpi = true
            
            // SOLUCIÓN PARA MAPA "RÍGIDO" EN SCROLLVIEW
            setOnTouchListener { view, event ->
                view.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    isFollowModeActive = false
                    resetFollowModeTimer()
                }
                false
            }
        }
        
        val controller = binding.mapTracking.controller
        controller.setZoom(17.0)

        if (destinationMarker == null) {
            destinationMarker = Marker(binding.mapTracking)
            destinationMarker?.title = "Punto de entrega"
            destinationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            binding.mapTracking.overlays.add(destinationMarker)
        }
        
        if (order.deliveryLat != 0.0) {
            val destPoint = GeoPoint(order.deliveryLat, order.deliveryLng)
            destinationMarker?.position = destPoint
            if (isFollowModeActive && deliveryMarker == null) {
                controller.setCenter(destPoint)
            }
        }
    }

    private fun resetFollowModeTimer() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            isFollowModeActive = true
            deliveryMarker?.position?.let {
                binding.mapTracking.controller.animateTo(it)
            }
        }, followReactivationDelay)
    }

    private fun startDeliveryPersonTracking(deliveryPersonId: String) {
        if (deliveryPersonId.isEmpty()) return
        if (deliveryPersonRegistration != null) return // Ya estamos rastreando
        
        deliveryPersonRegistration = db.collection("users").document(deliveryPersonId)
            .collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val lat = snapshot.getDouble("liveLat") ?: 0.0
                    val lng = snapshot.getDouble("liveLng") ?: 0.0
                    if (lat != 0.0) {
                        updateDeliveryMarker(lat, lng)
                    }
                }
            }
    }
    
    private fun updateDeliveryMarker(lat: Double, lng: Double) {
        val point = GeoPoint(lat, lng)
        
        if (deliveryMarker == null) {
            deliveryMarker = Marker(binding.mapTracking)
            deliveryMarker?.title = "Tu repartidor está aquí 🛵"
            deliveryMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            
            try {
                val size = 100
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.WHITE
                paint.style = Paint.Style.FILL
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                paint.color = Color.parseColor("#7C3AED")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6f
                canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 50f
                paint.textAlign = Paint.Align.CENTER
                val fontMetrics = paint.fontMetrics
                val y = (size / 2f) - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.drawText("🛵", size / 2f, y, paint)
                deliveryMarker?.icon = BitmapDrawable(resources, output)
            } catch (e: Exception) {}
            binding.mapTracking.overlays.add(deliveryMarker)
        }
        
        deliveryMarker?.position = point
        binding.mapTracking.invalidate()

        if (isFollowModeActive) {
            binding.mapTracking.controller.animateTo(point)
        }
    }

    private fun stopDeliveryPersonTracking() {
        deliveryPersonRegistration?.remove()
        deliveryPersonRegistration = null
        deliveryMarker?.let { 
            binding.mapTracking.overlays.remove(it)
            deliveryMarker = null
        }
        binding.mapTracking.invalidate()
    }

    private fun openChat(order: OrderModel) {
        val myUid = auth.currentUser?.uid ?: return
        val storeId = order.storeId
        val directChatId = if (myUid < storeId) "${myUid}_$storeId" else "${storeId}_$myUid"

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("ORDER_ID", directChatId)
            putExtra("OTHER_USER_NAME", order.storeName)
            putExtra("OTHER_USER_ID", storeId)
            putExtra("IS_DIRECT", true)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.mapTracking.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapTracking.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        orderRegistration?.remove()
        deliveryPersonRegistration?.remove()
    }
}
