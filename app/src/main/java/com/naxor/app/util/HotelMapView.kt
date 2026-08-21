package com.naxor.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.naxor.app.data.HotelRoomLayoutEntity

class HotelMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val roomPaint = Paint().apply { style = Paint.Style.FILL }
    private val wallPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 8f }
    private val doorPaint = Paint().apply { color = Color.parseColor("#B45309"); style = Paint.Style.FILL }
    private val doorArcPaint = Paint().apply { color = Color.parseColor("#F59E0B"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val borderPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val snapPaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) }
    private val limitPaint = Paint().apply { color = Color.parseColor("#CBD5E1"); style = Paint.Style.STROKE; strokeWidth = 8f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f) }
    private val gridPaint = Paint().apply { color = Color.parseColor("#E2E8F0"); strokeWidth = 1f }
    private val canvasBgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    
    val CANVAS_WIDTH = 3000f
    val CANVAS_HEIGHT = 4000f
    private val GRID_SIZE = 100f

    var layoutElements = mutableListOf<HotelRoomLayoutEntity>()
    var onRoomClicked: ((String) -> Unit)? = null
    var onElementSelected: ((HotelRoomLayoutEntity?) -> Unit)? = null
    var isEditMode = false
    var isMoveAssistActive = false
    
    var selectedElement: HotelRoomLayoutEntity? = null
        private set(value) {
            field = value
            onElementSelected?.invoke(value)
        }

    fun selectElement(element: HotelRoomLayoutEntity?) {
        selectedElement = element
        invalidate()
    }
    
    private var isResizing = false
    private var isRotating = false
    private var resizeHandleType = 0 
    
    // Estados iniciales para gestos estables
    private var startX = 0f
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var anchorWorldX = 0f
    private var anchorWorldY = 0f
    
    private val handleSize = 40f
    private val resizeHandlePaint = Paint().apply { color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL } 
    private val rotateHandlePaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 36f; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var snapX: Float? = null
    private var snapY: Float? = null
    private val SNAP_THRESHOLD = 20f

    private var longPressedElement: HotelRoomLayoutEntity? = null
    private val longPressAction = Runnable {
        longPressedElement?.let { showElementOptions(it) }
    }

    var roomStatuses = mapOf<String, String>()
    var roomNames = mapOf<String, String>()
    var onDuplicateRequested: ((HotelRoomLayoutEntity) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, CANVAS_WIDTH, CANVAS_HEIGHT, canvasBgPaint)
        canvas.drawRect(0f, 0f, CANVAS_WIDTH, CANVAS_HEIGHT, limitPaint)
        drawGrid(canvas)
        drawLayer(canvas, "ROOM")
        drawLayer(canvas, "DOOR")
        drawLayer(canvas, "WALL")
        if (isEditMode) {
            snapX?.let { canvas.drawLine(it, 0f, it, height.toFloat(), snapPaint) }
            snapY?.let { canvas.drawLine(0f, it, width.toFloat(), it, snapPaint) }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        var x = 0f
        while (x <= CANVAS_WIDTH) { canvas.drawLine(x, 0f, x, CANVAS_HEIGHT, gridPaint); x += GRID_SIZE }
        var y = 0f
        while (y <= CANVAS_HEIGHT) { canvas.drawLine(0f, y, CANVAS_WIDTH, y, gridPaint); y += GRID_SIZE }
    }

    private fun drawLayer(canvas: Canvas, type: String) {
        layoutElements.filter { it.type == type }.forEach { element ->
            val centerX = element.x + element.width / 2
            val centerY = element.y + element.height / 2
            canvas.save()
            canvas.rotate(element.rotation, centerX, centerY)
            when (element.type) {
                "ROOM" -> {
                    val status = roomStatuses[element.roomId] ?: "FREE"
                    roomPaint.color = Color.parseColor(when(status) { "OCCUPIED" -> "#DC2626"; "DIRTY" -> "#EA580C"; "MAINTENANCE" -> "#94A3B8"; else -> "#16A34A" })
                    canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, roomPaint)
                    val name = roomNames[element.roomId] ?: ""
                    if (name.isNotEmpty()) canvas.drawText(name, centerX, centerY + 12f, textPaint)
                }
                "DOOR" -> {
                    canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, doorPaint)
                    canvas.drawArc(element.x, element.y - element.width, element.x + element.width * 2, element.y + element.width, 180f, 90f, false, doorArcPaint)
                }
                else -> {
                    if (element.isHollow) { wallPaint.style = Paint.Style.STROKE; wallPaint.strokeWidth = element.strokeWidth } else wallPaint.style = Paint.Style.FILL
                    canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, wallPaint)
                }
            }
            if (isEditMode && element == selectedElement) {
                canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, borderPaint)
                canvas.drawCircle(element.x, element.y, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(element.x + element.width, element.y, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(element.x, element.y + element.height, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(element.x + element.width, element.y + element.height, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(centerX, element.y, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(centerX, element.y + element.height, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(element.x, centerY, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(element.x + element.width, centerY, handleSize / 2, resizeHandlePaint)
                canvas.drawCircle(centerX, element.y - 40f, handleSize / 2, rotateHandlePaint)
                canvas.drawLine(centerX, element.y, centerX, element.y - 40f, borderPaint)
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) {
            if (event.action == MotionEvent.ACTION_UP) {
                val clicked = layoutElements.reversed().find { element ->
                    val local = getLocalCoordinates(event.x, event.y, element.x + element.width/2, element.y + element.height/2, element.rotation)
                    if (element.type != "ROOM" || local[0] !in element.x..(element.x+element.width) || local[1] !in element.y..(element.y+element.height)) return@find false
                    true
                }
                clicked?.roomId?.let { onRoomClicked?.invoke(it) }
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedElement?.let { it ->
                    val centerX = it.x + it.width / 2
                    val centerY = it.y + it.height / 2
                    val local = getLocalCoordinates(event.x, event.y, centerX, centerY, it.rotation)
                    val lx = local[0]; val ly = local[1]

                    resizeHandleType = when {
                        Math.hypot((lx - it.x).toDouble(), (ly - it.y).toDouble()) < handleSize -> 1
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - it.y).toDouble()) < handleSize -> 2
                        Math.hypot((lx - it.x).toDouble(), (ly - (it.y + it.height)).toDouble()) < handleSize -> 3
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - (it.y + it.height)).toDouble()) < handleSize -> 4
                        Math.hypot((lx - centerX).toDouble(), (ly - it.y).toDouble()) < handleSize -> 5
                        Math.hypot((lx - centerX).toDouble(), (ly - (it.y + it.height)).toDouble()) < handleSize -> 6
                        Math.hypot((lx - it.x).toDouble(), (ly - centerY).toDouble()) < handleSize -> 7
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - centerY).toDouble()) < handleSize -> 8
                        else -> 0
                    }

                    if (resizeHandleType != 0) {
                        isResizing = true
                        startX = it.x; startY = it.y; startW = it.width; startH = it.height
                        startTouchX = event.x; startTouchY = event.y
                        val anchor = getAnchorPointWorld(it, resizeHandleType)
                        anchorWorldX = anchor[0]; anchorWorldY = anchor[1]
                        return true
                    }

                    if (Math.hypot((lx - centerX).toDouble(), (ly - (it.y - 40f)).toDouble()) < handleSize) {
                        isRotating = true; startTouchX = event.x; startTouchY = event.y
                        return true
                    }
                }

                val clickedElement = layoutElements.reversed().find { element ->
                    val centerX = element.x + element.width / 2
                    val centerY = element.y + element.height / 2
                    val local = getLocalCoordinates(event.x, event.y, centerX, centerY, element.rotation)
                    val lx = local[0]; val ly = local[1]
                    if (lx !in element.x..(element.x + element.width) || ly !in element.y..(element.y + element.height)) return@find false
                    if (element.isHollow) {
                        val buffer = maxOf(element.strokeWidth, 40f)
                        val nearEdge = lx < element.x + buffer || lx > (element.x + element.width) - buffer || ly < element.y + buffer || ly > (element.y + element.height) - borderPaint.strokeWidth
                        if (!nearEdge) return@find false
                    }
                    return@find true
                }
                
                selectedElement = clickedElement
                isResizing = false; isRotating = false
                if (clickedElement != null) {
                    startX = clickedElement.x; startY = clickedElement.y
                    startW = clickedElement.width; startH = clickedElement.height
                    startTouchX = event.x; startTouchY = event.y
                    longPressedElement = clickedElement
                    handler.postDelayed(longPressAction, 600)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.x - startTouchX) > 10 || Math.abs(event.y - startTouchY) > 10) handler.removeCallbacks(longPressAction)
                selectedElement?.let { element ->
                    if (isMoveAssistActive) {
                        val tDx = event.x - startTouchX; val tDy = event.y - startTouchY
                        element.x = (startX + tDx).coerceIn(0f, CANVAS_WIDTH - element.width)
                        element.y = (startY + tDy).coerceIn(0f, CANVAS_HEIGHT - element.height)
                        applySnapping(element, false); invalidate(); return true
                    }

                    if (isResizing) {
                        snapX = null; snapY = null
                        var targetX = event.x; var targetY = event.y
                        
                        // Snapping del punto de toque contra otros elementos
                        layoutElements.forEach { other ->
                            if (other == element) return@forEach
                            val oAABB = getAABB(other)
                            val othersX = listOf(oAABB.left, oAABB.right, oAABB.centerX())
                            val othersY = listOf(oAABB.top, oAABB.bottom, oAABB.centerY())
                            for (t in othersX) if (Math.abs(targetX - t) < SNAP_THRESHOLD) { targetX = t; snapX = t; break }
                            for (t in othersY) if (Math.abs(targetY - t) < SNAP_THRESHOLD) { targetY = t; snapY = t; break }
                        }

                        // Lógica de "Anclaje Fijo" Totalmente Estable
                        val rotRad = Math.toRadians(element.rotation.toDouble())
                        val cos = Math.cos(rotRad).toFloat(); val sin = Math.sin(rotRad).toFloat()
                        
                        val dxW = targetX - anchorWorldX; val dyW = targetY - anchorWorldY
                        val dL_x = dxW * cos + dyW * sin
                        val dL_y = -dxW * sin + dyW * cos
                        
                        var finalW = startW; var finalH = startH
                        when (resizeHandleType) {
                            1, 2, 3, 4 -> { finalW = Math.abs(dL_x).coerceAtLeast(20f); finalH = Math.abs(dL_y).coerceAtLeast(20f) }
                            5, 6 -> { finalH = Math.abs(dL_y).coerceAtLeast(20f) }
                            7, 8 -> { finalW = Math.abs(dL_x).coerceAtLeast(20f) }
                        }
                        
                        val localAnchorOffset = when (resizeHandleType) {
                            1 -> floatArrayOf(finalW/2, finalH/2); 2 -> floatArrayOf(-finalW/2, finalH/2)
                            3 -> floatArrayOf(finalW/2, -finalH/2); 4 -> floatArrayOf(-finalW/2, -finalH/2)
                            5 -> floatArrayOf(0f, finalH/2); 6 -> floatArrayOf(0f, -finalH/2)
                            7 -> floatArrayOf(finalW/2, 0f); 8 -> floatArrayOf(-finalW/2, 0f)
                            else -> floatArrayOf(0f, 0f)
                        }
                        
                        val worldAnchorOffset_x = localAnchorOffset[0] * cos - localAnchorOffset[1] * sin
                        val worldAnchorOffset_y = localAnchorOffset[0] * sin + localAnchorOffset[1] * cos
                        val newCenterX = anchorWorldX - worldAnchorOffset_x
                        val newCenterY = anchorWorldY - worldAnchorOffset_y
                        
                        element.width = finalW; element.height = finalH
                        element.x = newCenterX - finalW / 2
                        element.y = newCenterY - finalH / 2
                        
                        // Límite del lienzo estricto: Si se sale, revertimos/ajustamos
                        val aabb = getAABB(element)
                        if (aabb.left < 0) element.x -= aabb.left
                        if (aabb.right > CANVAS_WIDTH) element.x -= (aabb.right - CANVAS_WIDTH)
                        if (aabb.top < 0) element.y -= aabb.top
                        if (aabb.bottom > CANVAS_HEIGHT) element.y -= (aabb.bottom - CANVAS_HEIGHT)

                    } else if (isRotating) {
                        val angle = Math.toDegrees(Math.atan2((event.y - (element.y + element.height/2)).toDouble(), (event.x - (element.x + element.width/2)).toDouble())).toFloat()
                        element.rotation = Math.round((angle + 90f) / 15f) * 15f
                    } else {
                        val tDx = event.x - startTouchX; val tDy = event.y - startTouchY
                        element.x = (startX + tDx).coerceIn(0f, CANVAS_WIDTH - element.width)
                        element.y = (startY + tDy).coerceIn(0f, CANVAS_HEIGHT - element.height)
                        applySnapping(element, false)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isResizing = false; isRotating = false; resizeHandleType = 0
                snapX = null; snapY = null; handler.removeCallbacks(longPressAction); longPressedElement = null; invalidate()
            }
        }
        return true
    }

    private fun getAnchorPointWorld(element: HotelRoomLayoutEntity, handleType: Int): FloatArray {
        val centerX = element.x + element.width / 2
        val centerY = element.y + element.height / 2
        val localAnchor = when (handleType) {
            1 -> floatArrayOf(element.x + element.width, element.y + element.height)
            2 -> floatArrayOf(element.x, element.y + element.height)
            3 -> floatArrayOf(element.x + element.width, element.y)
            4 -> floatArrayOf(element.x, element.y)
            5 -> floatArrayOf(centerX, element.y + element.height)
            6 -> floatArrayOf(centerX, element.y)
            7 -> floatArrayOf(element.x + element.width, centerY)
            8 -> floatArrayOf(element.x, centerY)
            else -> floatArrayOf(centerX, centerY)
        }
        val matrix = android.graphics.Matrix()
        matrix.postRotate(element.rotation, centerX, centerY)
        val pts = floatArrayOf(localAnchor[0], localAnchor[1])
        matrix.mapPoints(pts)
        return pts
    }

    private fun getLocalCoordinates(x: Float, y: Float, centerX: Float, centerY: Float, rotation: Float): FloatArray {
        val angleRad = Math.toRadians((-rotation).toDouble())
        val cos = Math.cos(angleRad).toFloat(); val sin = Math.sin(angleRad).toFloat()
        val dx = x - centerX; val dy = y - centerY
        return floatArrayOf((dx * cos - dy * sin) + centerX, (dx * sin + dy * cos) + centerY)
    }

    private fun applySnapping(element: HotelRoomLayoutEntity, isResizing: Boolean) {
        if (isResizing) return 
        snapX = null; snapY = null
        val eAABB = getAABB(element)
        layoutElements.forEach { other ->
            if (other == element) return@forEach
            val oAABB = getAABB(other)
            val sources = listOf(eAABB.left, eAABB.right, eAABB.centerX())
            val targets = listOf(oAABB.left, oAABB.right, oAABB.centerX())
            for (s in sources) for (t in targets) if (Math.abs(s - t) < SNAP_THRESHOLD) { element.x += (t - s); snapX = t; break }
            val sY = listOf(eAABB.top, eAABB.bottom, eAABB.centerY()); val tY = listOf(oAABB.top, oAABB.bottom, oAABB.centerY())
            for (s in sY) for (t in tY) if (Math.abs(s - t) < SNAP_THRESHOLD) { element.y += (t - s); snapY = t; break }
        }
        if (snapX == null) { val gX = Math.round(element.x / 20f) * 20f; if (Math.abs(element.x - gX) < 10f) element.x = gX }
        if (snapY == null) { val gY = Math.round(element.y / 20f) * 20f; if (Math.abs(element.y - gY) < 10f) element.y = gY }
    }

    private fun getAABB(element: HotelRoomLayoutEntity): RectF {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(element.rotation, element.x + element.width / 2, element.y + element.height / 2)
        val exp = if (element.type == "WALL" && element.isHollow) element.strokeWidth / 2 else 0f
        val pts = floatArrayOf(element.x - exp, element.y - exp, element.x + element.width + exp, element.y - exp, element.x + element.width + exp, element.y + element.height + exp, element.x - exp, element.y + element.height + exp)
        matrix.mapPoints(pts)
        var minX = pts[0]; var maxX = pts[0]; var minY = pts[1]; var maxY = pts[1]
        for (i in 0 until pts.size step 2) { minX = minOf(minX, pts[i]); maxX = maxOf(maxX, pts[i]); minY = minOf(minY, pts[i+1]); maxY = maxOf(maxY, pts[i+1]) }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun showElementOptions(element: HotelRoomLayoutEntity) {
        val options = mutableListOf<String>(); options.add("Duplicar")
        if (element.type == "WALL") {
            options.add(if (element.isHollow) "Poner Relleno" else "Quitar Relleno")
            if (element.isHollow) { options.add("Aumentar Grosor"); options.add("Disminuir Grosor") }
        }
        androidx.appcompat.app.AlertDialog.Builder(context).setTitle("Opciones de Elemento").setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Duplicar" -> onDuplicateRequested?.invoke(element)
                "Poner Relleno", "Quitar Relleno" -> element.isHollow = !element.isHollow
                "Aumentar Grosor" -> element.strokeWidth = (element.strokeWidth + 4f).coerceAtMost(40f)
                "Disminuir Grosor" -> element.strokeWidth = (element.strokeWidth - 4f).coerceAtLeast(2f)
            }
            invalidate()
        }.show()
    }

    fun deleteSelected() { selectedElement?.let { layoutElements.remove(it); selectedElement = null; invalidate() } }
}
