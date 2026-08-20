package com.naxor.app.util

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class TutorialSpotlightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var targetRect = RectF()
    private var isCircle = true

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setTarget(view: View, circle: Boolean = true) {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        
        // Ajuste relativo a este view (que es pantalla completa)
        val myLoc = IntArray(2)
        getLocationInWindow(myLoc)
        
        val x = (location[0] - myLoc[0]).toFloat()
        val y = (location[1] - myLoc[1]).toFloat()
        
        targetRect.set(x - 10f, y - 10f, x + view.width + 10f, y + view.height + 10f)
        isCircle = circle
        visibility = VISIBLE
        invalidate()
    }

    fun clearTarget() {
        targetRect.set(0f, 0f, 0f, 0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Fondo un poco menos oscuro para mejor visibilidad
        paint.color = Color.parseColor("#99000000") 
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        if (targetRect.width() > 0) {
            if (isCircle) {
                // Radio más amplio para que el botón no se vea apretado
                val radius = (Math.max(targetRect.width(), targetRect.height()) / 2f) + 30f
                canvas.drawCircle(targetRect.centerX(), targetRect.centerY(), radius, eraser)
            } else {
                canvas.drawRoundRect(targetRect, 30f, 30f, eraser)
            }
        }
    }
}
