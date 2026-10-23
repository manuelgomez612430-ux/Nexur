package com.gomezmart.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.data.OrderModel
import com.gomezmart.app.databinding.ActivityOrderTrackingFullscreenBinding
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class OrderTrackingFullscreenActivity : BaseActivity() {

    private lateinit var binding: ActivityOrderTrackingFullscreenBinding
    private val db = FirebaseFirestore.getInstance()
    private var orderRegistration: ListenerRegistration? = null
    private var deliveryPersonRegistration: ListenerRegistration? = null
    
    private var deliveryMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var orderId: String = ""
    
    private var isFollowModeActive = true
    private val handler = Handler(Looper.getMainLooper())
    private var lastInteractionTime = 0L
    private val followReactivationDelay = 4000L // 4 segundos para volver a seguir automáticamente

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderTrackingFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        orderId = intent.getStringExtra("ORDER_ID") ?: ""
        if (orderId.isEmpty()) {
            finish()
            return
        }

        binding.btnBackFullscreen.setOnClickListener { finish() }
        setupMap()
        startOrderListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        binding.mapFullscreen.apply {
            setMultiTouchControls(true)
            setFlingEnabled(true)
            isTilesScaledToDpi = true
            
            // Detectar interacción manual
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    isFollowModeActive = false
                    lastInteractionTime = System.currentTimeMillis()
                    resetFollowModeTimer()
                }
                false
            }
        }
        binding.mapFullscreen.controller.setZoom(17.5)
    }

    private fun resetFollowModeTimer() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            isFollowModeActive = true
            // Si ya tenemos una posición del repartidor, volver a él suavemente
            deliveryMarker?.position?.let {
                binding.mapFullscreen.controller.animateTo(it)
            }
        }, followReactivationDelay)
    }

    private fun startOrderListener() {
        orderRegistration = db.collection("orders").document(orderId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val order = snapshot.toObject(OrderModel::class.java)
                    if (order != null) {
                        updateUI(order)
                    }
                }
            }
    }

    private fun updateUI(order: OrderModel) {
        binding.tvFullscreenAddress.text = "Destino: ${order.deliveryAddress}"
        
        val isCancelled = order.status == "CANCELLED"
        if (isCancelled) {
            binding.tvFullscreenStatus.text = "PEDIDO CANCELADO 🚫"
            binding.tvFullscreenStatus.setTextColor(Color.parseColor("#DC2626")) // red_600
        } else {
            val statusText = when (order.status) {
                "PENDING" -> "Pedido Recibido ⏳"
                "ACCEPTED" -> "Preparando Pedido 👨‍🍳"
                "EN_ROUTE" -> "¡Pedido en camino! 🛵"
                "DELIVERED" -> "Pedido Entregado ✅"
                else -> order.status
            }
            binding.tvFullscreenStatus.text = statusText
            binding.tvFullscreenStatus.setTextColor(Color.parseColor("#7C3AED")) // purple_600
        }

        if (destinationMarker == null && order.deliveryLat != 0.0) {
            destinationMarker = Marker(binding.mapFullscreen)
            destinationMarker?.title = "Punto de entrega"
            destinationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            val destPoint = GeoPoint(order.deliveryLat, order.deliveryLng)
            destinationMarker?.position = destPoint
            binding.mapFullscreen.overlays.add(destinationMarker)
            
            if (isFollowModeActive) {
                binding.mapFullscreen.controller.setCenter(destPoint)
            }
        }

        if (order.status == "EN_ROUTE") {
            startDeliveryPersonTracking(order.deliveryPersonId)
        } else {
            stopDeliveryPersonTracking()
        }
    }

    private fun startDeliveryPersonTracking(deliveryPersonId: String) {
        if (deliveryPersonId.isEmpty()) return
        if (deliveryPersonRegistration != null) return
        
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
            deliveryMarker = Marker(binding.mapFullscreen)
            deliveryMarker?.title = "Tu repartidor 🛵"
            deliveryMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            
            try {
                val size = 110
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                
                paint.color = Color.WHITE
                paint.style = Paint.Style.FILL
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                
                paint.color = Color.parseColor("#7C3AED")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 7f
                canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 4, paint)
                
                paint.style = Paint.Style.FILL
                paint.textSize = 55f
                paint.textAlign = Paint.Align.CENTER
                val fontMetrics = paint.fontMetrics
                val y = (size / 2f) - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.drawText("🛵", size / 2f, y, paint)
                
                deliveryMarker?.icon = BitmapDrawable(resources, output)
            } catch (e: Exception) {}
            binding.mapFullscreen.overlays.add(deliveryMarker)
        }
        
        deliveryMarker?.position = point
        binding.mapFullscreen.invalidate()

        // SEGUIMIENTO FLUIDO: Si el modo seguimiento está activo, mover la cámara
        if (isFollowModeActive) {
            binding.mapFullscreen.controller.animateTo(point)
        }
    }

    private fun stopDeliveryPersonTracking() {
        deliveryPersonRegistration?.remove()
        deliveryPersonRegistration = null
        deliveryMarker?.let { 
            binding.mapFullscreen.overlays.remove(it)
            deliveryMarker = null
        }
        binding.mapFullscreen.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapFullscreen.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapFullscreen.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        orderRegistration?.remove()
        deliveryPersonRegistration?.remove()
    }
}
