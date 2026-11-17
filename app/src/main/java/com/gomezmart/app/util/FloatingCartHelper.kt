package com.gomezmart.app.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.CartCheckoutActivity
import com.gomezmart.app.R
import com.gomezmart.app.data.CartManager

object FloatingCartHelper {

    private var floatingView: View? = null
    
    // Persistir posición entre pantallas
    private var lastX = -1f
    private var lastY = -1f
    
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            hide()
            return
        }

        // Ocultamos si el carrito está vacío
        if (CartManager.getItems().isEmpty()) {
            hide()
            return
        }
        
        // Solo respetamos si la actividad prohíbe explícitamente el carrito
        if (activity is BaseActivity && !activity.shouldShowFloatingCart()) {
            hide()
            return
        }

        try {
            // Cargar posición persistida desde disco si es la primera vez
            if (lastX == -1f) {
                val prefs = activity.getSharedPreferences("FloatingCartPrefs", Context.MODE_PRIVATE)
                lastX = prefs.getFloat("last_x", -1f)
                lastY = prefs.getFloat("last_y", -1f)
            }

            val window = activity.window ?: return
            val decorView = window.decorView
            val rootLayout = decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
            
            // [OPTIMIZACIÓN] Si ya existe, lo quitamos de la actividad anterior antes de re-crear
            hide()

            floatingView = LayoutInflater.from(activity).inflate(R.layout.layout_floating_cart, rootLayout, false)
            setupDraggableLogic(floatingView!!)

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            
            // [CORRECCIÓN] Establecer posición INICIAL antes de añadir al layout para evitar el "salto"
            if (lastX != -1f && lastY != -1f) {
                floatingView?.x = lastX
                floatingView?.y = lastY
            }

            rootLayout.addView(floatingView, params)
            
            // Ajuste fino tras el inflado por si las dimensiones del botón cambiaron
            floatingView?.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                try {
                    if (lastX != -1f && lastY != -1f) {
                        val dm = activity.resources.displayMetrics
                        val safeX = lastX.coerceIn(0f, (dm.widthPixels - (floatingView?.width ?: 0)).toFloat())
                        val safeY = lastY.coerceIn(100f, (dm.heightPixels - (floatingView?.height ?: 0) - 100).toFloat())
                        floatingView?.x = safeX
                        floatingView?.y = safeY
                    } else {
                        val dm = activity.resources.displayMetrics
                        val defaultX = (dm.widthPixels - (200 * dm.density).toInt()).toFloat()
                        val defaultY = (dm.heightPixels - (200 * dm.density).toInt()).toFloat()
                        floatingView?.x = defaultX
                        floatingView?.y = defaultY
                        lastX = defaultX
                        lastY = defaultY
                    }
                    updateBadge()
                } catch (e: Exception) {
                    Log.e("FloatingCartHelper", "Post layout error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingCartHelper", "Show floating cart error: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableLogic(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = v.x
                    initialY = v.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = v.context.resources.displayMetrics
                    val newX = (initialX + (event.rawX - initialTouchX)).coerceIn(0f, (dm.widthPixels - v.width).toFloat())
                    val newY = (initialY + (event.rawY - initialTouchY)).coerceIn(100f, (dm.heightPixels - v.height - 100).toFloat())
                    
                    v.x = newX
                    v.y = newY
                    lastX = newX
                    lastY = newY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    try {
                        val prefs = v.context.getSharedPreferences("FloatingCartPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putFloat("last_x", lastX).putFloat("last_y", lastY).apply()
                    } catch (e: Exception) {}
                    if (event.action == MotionEvent.ACTION_UP) {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            openCart(v.context)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openCart(context: Context) {
        try {
            val intent = Intent(context, CartCheckoutActivity::class.java).apply {
                putExtra("STORE_ID", CartManager.currentStoreId)
                putExtra("STORE_NAME", CartManager.currentStoreName) 
                putExtra("STORE_LOGO", CartManager.currentStoreLogo) 
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("FloatingCartHelper", "Open cart error: ${e.message}")
        }
    }

    fun updateBadge() {
        floatingView?.let { view ->
            try {
                val count = CartManager.getTotalCount()
                val badge = view.findViewById<View>(R.id.cardCartBadge)
                val badgeText = view.findViewById<TextView>(R.id.tvCartBadgeCount)
                
                if (count > 0) {
                    view.visibility = View.VISIBLE
                    badge.visibility = View.VISIBLE
                    if (badgeText.text != count.toString()) {
                        badgeText.text = count.toString()
                        badge.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).withEndAction {
                            badge.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                        }.start()
                    }
                } else {
                    view.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("FloatingCartHelper", "Update badge error: ${e.message}")
            }
        }
    }

    fun hide() {
        try {
            (floatingView?.parent as? ViewGroup)?.removeView(floatingView)
        } catch (e: Exception) {}
        floatingView = null
    }
}
