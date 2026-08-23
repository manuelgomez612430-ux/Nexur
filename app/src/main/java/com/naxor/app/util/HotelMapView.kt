package com.naxor.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
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
    
    private val gridMinorPaint = Paint().apply { color = Color.parseColor("#F1F5F9"); strokeWidth = 1f }
    private val gridMajorPaint = Paint().apply { color = Color.parseColor("#E2E8F0"); strokeWidth = 2f }
    private val canvasBgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    
    val CANVAS_WIDTH = 3000f
    val CANVAS_HEIGHT = 4000f
    private val GRID_MINOR = 50f
    private val GRID_MAJOR = 250f

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX; val focusY = detector.focusY
            val lastScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.1f, 5.0f)
            val ratio = scaleFactor / lastScale
            translateX = focusX - (focusX - translateX) * ratio
            translateY = focusY - (focusY - translateY) * ratio
            invalidate(); return true
        }
    })

    fun setZoom(scale: Float) { scaleFactor = scale.coerceIn(0.1f, 5.0f); translateX = 0f; translateY = 0f; invalidate() }
    fun zoomIn() { scaleFactor = (scaleFactor * 1.25f).coerceAtMost(5.0f); invalidate() }
    fun zoomOut() { scaleFactor = (scaleFactor / 1.25f).coerceAtLeast(0.1f); invalidate() }

    var layoutElements = mutableListOf<HotelRoomLayoutEntity>()
    var onRoomClicked: ((String) -> Unit)? = null
    var onElementSelected: ((HotelRoomLayoutEntity?) -> Unit)? = null
    var isEditMode = false
    var isMoveAssistActive = false
    
    var selectedElement: HotelRoomLayoutEntity? = null
        private set(value) { field = value; onElementSelected?.invoke(value) }

    fun selectElement(element: HotelRoomLayoutEntity?) { selectedElement = element; invalidate() }
    
    private var isResizing = false
    private var isRotating = false
    private var isMovingElement = false
    private var isPanning = false
    private var resizeHandleType = 0 
    
    private var potentialSelection: HotelRoomLayoutEntity? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    
    private var startX = 0f; private var startY = 0f; private var startW = 0f; private var startH = 0f
    private var startTouchX = 0f; private var startTouchY = 0f
    private var anchorWorldX = 0f; private var anchorWorldY = 0f
    
    private val handleSize = 40f
    private val moveHandleSize = 65f
    private val resizeHandlePaint = Paint().apply { color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL } 
    private val rotateHandlePaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
    private val moveHandlePaint = Paint().apply { color = Color.parseColor("#6366F1"); style = Paint.Style.FILL; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 36f; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var snapX: Float? = null; private var snapY: Float? = null
    private val SNAP_THRESHOLD = 25f

    private var longPressedElement: HotelRoomLayoutEntity? = null
    private val longPressAction = Runnable { longPressedElement?.let { showElementOptions(it) } }

    var roomStatuses = mapOf<String, String>()
    var roomNames = mapOf<String, String>()
    var roomsWithFailures = setOf<String>() // IDs de habitaciones con fallas pendientes
    var roomsReserved = setOf<String>() // IDs de habitaciones con reserva confirmada
    var onDuplicateRequested: ((HotelRoomLayoutEntity) -> Unit)? = null
    var onHistorySaveRequested: (() -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        
        if (isEditMode) {
            canvas.translate(translateX, translateY)
            canvas.scale(scaleFactor, scaleFactor)
        } else {
            val viewW = width.toFloat(); val viewH = height.toFloat()
            if (viewW > 0 && viewH > 0 && layoutElements.isNotEmpty()) {
                var miX = Float.MAX_VALUE; var miY = Float.MAX_VALUE; var maX = -Float.MAX_VALUE; var maY = -Float.MAX_VALUE
                layoutElements.forEach { e -> val a = getAABB(e); miX = minOf(miX, a.left); miY = minOf(miY, a.top); maX = maxOf(maX, a.right); maY = maxOf(maY, a.bottom) }
                val cW = maX - miX; val cH = maY - miY
                if (cW > 0 && cH > 0) {
                    val s = minOf(viewW / cW, viewH / cH) * 0.85f 
                    canvas.translate((viewW - cW * s) / 2f - miX * s, (viewH - cH * s) / 2f - miY * s); canvas.scale(s, s)
                }
            } else if (viewW > 0 && viewH > 0) {
                val s = minOf(viewW / CANVAS_WIDTH, viewH / CANVAS_HEIGHT) * 0.9f
                canvas.translate((viewW - CANVAS_WIDTH * s) / 2f, (viewH - CANVAS_HEIGHT * s) / 2f); canvas.scale(s, s)
            }
        }
        
        canvas.drawRect(0f, 0f, CANVAS_WIDTH, CANVAS_HEIGHT, canvasBgPaint)
        drawGrid(canvas)
        canvas.drawRect(0f, 0f, CANVAS_WIDTH, CANVAS_HEIGHT, limitPaint)
        
        drawLayer(canvas, "ROOM", onlyStructure = true)
        drawLayer(canvas, "WALL", onlyStructure = true)
        drawLayer(canvas, "DOOR", onlyStructure = true)
        
        // Dibujar iconos de falla sobre habitaciones
        if (!isEditMode) {
            drawMaintenanceBadges(canvas)
        }

        drawLayer(canvas, "ROOM", onlyText = true)

        if (isEditMode) {
            selectedElement?.let { e ->
                val cX = e.x + e.width / 2; val cY = e.y + e.height / 2
                canvas.save(); canvas.rotate(e.rotation, cX, cY)
                drawHandlesForElement(canvas, e, cX, cY)
                canvas.restore()
            }
            snapX?.let { canvas.drawLine(it, -5000f, it, 5000f, snapPaint) }
            snapY?.let { canvas.drawLine(-5000f, it, 5000f, it, snapPaint) }
        }
        canvas.restore()
    }

    private fun drawMaintenanceBadges(canvas: Canvas) {
        val badgePaint = Paint().apply { color = Color.parseColor("#F59E0B"); style = Paint.Style.FILL } // Ambar
        val iconPaint = Paint().apply { color = Color.WHITE; textSize = 40f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        
        layoutElements.filter { it.type == "ROOM" && roomsWithFailures.contains(it.roomId) }.forEach { element ->
            val cX = element.x + element.width / 2
            val badgeY = element.y + 35f
            canvas.drawCircle(cX + element.width/2 - 30f, badgeY, 25f, badgePaint)
            canvas.drawText("!", cX + element.width/2 - 30f, badgeY + 14f, iconPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#F8FAFC"))
        var x = 0f
        while (x <= CANVAS_WIDTH) { canvas.drawLine(x, 0f, x, CANVAS_HEIGHT, gridMinorPaint); x += GRID_MINOR }
        var y = 0f
        while (y <= CANVAS_HEIGHT) { canvas.drawLine(0f, y, CANVAS_WIDTH, y, gridMinorPaint); y += GRID_MINOR }
        x = 0f
        while (x <= CANVAS_WIDTH) { canvas.drawLine(x, 0f, x, CANVAS_HEIGHT, gridMajorPaint); x += GRID_MAJOR }
        y = 0f
        while (y <= CANVAS_HEIGHT) { canvas.drawLine(0f, y, CANVAS_WIDTH, y, gridMajorPaint); y += GRID_MAJOR }
        val axisPaint = Paint().apply { color = Color.parseColor("#CBD5E1"); strokeWidth = 3f }
        canvas.drawLine(CANVAS_WIDTH / 2, 0f, CANVAS_WIDTH / 2, CANVAS_HEIGHT, axisPaint)
        canvas.drawLine(0f, CANVAS_HEIGHT / 2, CANVAS_WIDTH, CANVAS_HEIGHT / 2, axisPaint)
    }

    private fun drawLayer(canvas: Canvas, type: String, onlyStructure: Boolean = false, onlyText: Boolean = false) {
        layoutElements.filter { it.type == type }.forEach { element ->
            val centerX = element.x + element.width / 2; val centerY = element.y + element.height / 2
            canvas.save()
            canvas.rotate(element.rotation, centerX, centerY)
            when (element.type) {
                "ROOM" -> {
                    if (onlyStructure) {
                        val status = roomStatuses[element.roomId] ?: "FREE"
                        val isReserved = roomsReserved.contains(element.roomId)
                        
                        roomPaint.color = Color.parseColor(when {
                            status == "OCCUPIED" -> "#DC2626"    // Rojo
                            status == "DIRTY" -> "#EA580C"       // Naranja
                            status == "MAINTENANCE" -> "#9333EA" // Morado
                            isReserved -> "#0284C7"              // Azul (Reserva)
                            else -> "#16A34A"                   // Verde (Libre)
                        })
                        canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, roomPaint)
                    }
                    if (onlyText) {
                        val name = roomNames[element.roomId] ?: ""
                        if (name.isNotEmpty()) {
                            val displayText = if (isEditMode) name else "HAB. $name"
                            val rMin = minOf(element.width, element.height)
                            val tSize = (rMin * 0.32f); textPaint.textSize = tSize
                            val maxWidth = element.width * 0.80f; val tWidth = textPaint.measureText(displayText)
                            if (tWidth > maxWidth) textPaint.textSize = tSize * (maxWidth / tWidth)
                            val tBounds = android.graphics.Rect(); textPaint.getTextBounds(displayText, 0, displayText.length, tBounds)
                            textPaint.setShadowLayer(6f, 0f, 0f, Color.BLACK)
                            canvas.drawText(displayText, centerX, centerY + tBounds.height() / 2f, textPaint)
                        }
                    }
                }
                "DOOR" -> if (onlyStructure) {
                    canvas.save(); canvas.scale(if (element.isHollow) -1f else 1f, if (element.strokeWidth > 5f) -1f else 1f, centerX, centerY)
                    canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, doorPaint)
                    canvas.drawArc(element.x, element.y - element.width, element.x + element.width * 2, element.y + element.width, 180f, 90f, false, doorArcPaint)
                    canvas.restore()
                }
                "WALL" -> if (onlyStructure) {
                    if (element.isHollow) { wallPaint.style = Paint.Style.STROKE; wallPaint.strokeWidth = element.strokeWidth } else wallPaint.style = Paint.Style.FILL
                    canvas.drawRect(element.x, element.y, element.x + element.width, element.y + element.height, wallPaint)
                }
            }
            canvas.restore()
        }
    }

    private fun drawHandlesForElement(canvas: Canvas, e: HotelRoomLayoutEntity, cX: Float, cY: Float) {
        val sH = handleSize / scaleFactor; val sM = moveHandleSize / scaleFactor
        val mOff = 80f / scaleFactor; val rOff = 40f / scaleFactor
        when {
            isResizing -> drawHandleByType(canvas, e, resizeHandleType, sH, cX, cY)
            isRotating -> { canvas.drawCircle(cX, e.y - rOff, sH / 2, rotateHandlePaint); canvas.drawLine(cX, e.y, cX, e.y - rOff, borderPaint) }
            isMovingElement -> { val mY = e.y + e.height + mOff; canvas.drawCircle(cX, mY, sM / 2, moveHandlePaint); canvas.drawLine(cX, e.y + e.height, cX, mY, borderPaint) }
            else -> {
                canvas.drawRect(e.x, e.y, e.x + e.width, e.y + e.height, borderPaint)
                canvas.drawCircle(e.x, e.y, sH/2, resizeHandlePaint); canvas.drawCircle(e.x + e.width, e.y, sH/2, resizeHandlePaint)
                canvas.drawCircle(e.x, e.y + e.height, sH/2, resizeHandlePaint); canvas.drawCircle(e.x + e.width, e.y + e.height, sH/2, resizeHandlePaint)
                canvas.drawCircle(cX, e.y, sH/2, resizeHandlePaint); canvas.drawCircle(cX, e.y + e.height, sH/2, resizeHandlePaint)
                canvas.drawCircle(e.x, cY, sH/2, resizeHandlePaint); canvas.drawCircle(e.x + e.width, cY, sH/2, resizeHandlePaint)
                canvas.drawCircle(cX, e.y - rOff, sH / 2, rotateHandlePaint); canvas.drawLine(cX, e.y, cX, e.y - rOff, borderPaint)
                val mY = e.y + e.height + mOff; canvas.drawCircle(cX, mY, sM / 2, moveHandlePaint); canvas.drawLine(cX, e.y + e.height, cX, mY, borderPaint)
            }
        }
    }

    private fun drawHandleByType(canvas: Canvas, e: HotelRoomLayoutEntity, t: Int, sH: Float, cX: Float, cY: Float) {
        when (t) {
            1 -> canvas.drawCircle(e.x, e.y, sH/2, resizeHandlePaint); 2 -> canvas.drawCircle(e.x + e.width, e.y, sH/2, resizeHandlePaint)
            3 -> canvas.drawCircle(e.x, e.y + e.height, sH/2, resizeHandlePaint); 4 -> canvas.drawCircle(e.x + e.width, e.y + e.height, sH/2, resizeHandlePaint)
            5 -> canvas.drawCircle(cX, e.y, sH/2, resizeHandlePaint); 6 -> canvas.drawCircle(cX, e.y + e.height, sH/2, resizeHandlePaint)
            7 -> canvas.drawCircle(e.x, cY, sH/2, resizeHandlePaint); 8 -> canvas.drawCircle(e.x + e.width, cY, sH/2, resizeHandlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val wX = (event.x - translateX) / scaleFactor; val wY = (event.y - translateY) / scaleFactor
        if (!isEditMode) {
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val clk = layoutElements.reversed().find { e ->
                    val local = getLocalCoordinates(wX, wY, e.x + e.width/2, e.y + e.height/2, e.rotation)
                    e.type == "ROOM" && local[0] in e.x..(e.x+e.width) && local[1] in e.y..(e.y+e.height)
                }
                clk?.roomId?.let { onRoomClicked?.invoke(it) }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectedElement?.let { it ->
                    val cX = it.x + it.width / 2; val cY = it.y + it.height / 2
                    val local = getLocalCoordinates(wX, wY, cX, cY, it.rotation)
                    val lx = local[0]; val ly = local[1]; val sH = handleSize / scaleFactor
                    val sM = moveHandleSize / scaleFactor
                    val mYLoc = it.y + it.height + 80f / scaleFactor
                    if (Math.hypot((lx - cX).toDouble(), (ly - mYLoc).toDouble()) < sM / 2) { isMovingElement = true; startX = it.x; startY = it.y; startTouchX = wX; startTouchY = wY; return true }
                    resizeHandleType = when {
                        Math.hypot((lx - it.x).toDouble(), (ly - it.y).toDouble()) < sH -> 1
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - it.y).toDouble()) < sH -> 2
                        Math.hypot((lx - it.x).toDouble(), (ly - (it.y + it.height)).toDouble()) < sH -> 3
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - (it.y + it.height)).toDouble()) < sH -> 4
                        Math.hypot((lx - cX).toDouble(), (ly - it.y).toDouble()) < sH -> 5
                        Math.hypot((lx - cX).toDouble(), (ly - (it.y + it.height)).toDouble()) < sH -> 6
                        Math.hypot((lx - it.x).toDouble(), (ly - cY).toDouble()) < sH -> 7
                        Math.hypot((lx - (it.x + it.width)).toDouble(), (ly - cY).toDouble()) < sH -> 8
                        else -> 0
                    }
                    if (resizeHandleType != 0) { isResizing = true; startX = it.x; startY = it.y; startW = it.width; startH = it.height; startTouchX = wX; startTouchY = wY; val anchor = getAnchorPointWorld(it, resizeHandleType); anchorWorldX = anchor[0]; anchorWorldY = anchor[1]; return true }
                    if (Math.hypot((lx - cX).toDouble(), (ly - (it.y - 40f / scaleFactor)).toDouble()) < sH) { isRotating = true; startTouchX = wX; startTouchY = wY; return true }
                }
                val clk = layoutElements.reversed().find { e ->
                    val local = getLocalCoordinates(wX, wY, e.x + e.width/2, e.y + e.height/2, e.rotation)
                    val lx = local[0]; val ly = local[1]
                    if (lx !in e.x..(e.x + e.width) || ly !in e.y..(e.y + e.height)) return@find false
                    if (e.isHollow) { val b = maxOf(e.strokeWidth, 40f / scaleFactor); if (lx > e.x+b && lx < e.x+e.width-b && ly > e.y+b && ly < e.y+e.height-b) return@find false }
                    true
                }
                if (clk != null) { potentialSelection = clk; startX = clk.x; startY = clk.y; startW = clk.width; startH = clk.height; startTouchX = wX; startTouchY = wY; longPressedElement = clk; handler.postDelayed(longPressAction, 600) }
                else { selectedElement = null; isPanning = true; startTouchX = event.x; startTouchY = event.y }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { isResizing = false; isRotating = false; isMovingElement = false; isPanning = false; potentialSelection = null; handler.removeCallbacks(longPressAction); invalidate() }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) { isPanning = false; potentialSelection = null; return true }
                if (Math.abs(wX - startTouchX) > 10 || Math.abs(wY - startTouchY) > 10) handler.removeCallbacks(longPressAction)
                if (isPanning) { translateX += (event.x - startTouchX); translateY += (event.y - startTouchY); startTouchX = event.x; startTouchY = event.y; invalidate(); return true }
                if (potentialSelection != null && Math.hypot((wX - startTouchX).toDouble(), (wY - startTouchY).toDouble()) > touchSlop / scaleFactor) confirmSelection()
                selectedElement?.let { element ->
                    if (isMoveAssistActive || isMovingElement) { element.x = startX + (wX - startTouchX); element.y = startY + (wY - startTouchY); applySnapping(element, false); constrainToCanvas(element); invalidate(); return true }
                    if (isResizing) {
                        snapX = null; snapY = null; var tX = wX; var tY = wY; var bDx = SNAP_THRESHOLD / scaleFactor; var bDy = SNAP_THRESHOLD / scaleFactor
                        layoutElements.forEach { other -> if (other == element) return@forEach; val o = getAABB(other); val oX = listOf(o.left, o.right, o.centerX()); val oY = listOf(o.top, o.bottom, o.centerY()); for (tx in oX) { val d = Math.abs(tX - tx); if (d < bDx) { bDx = d; tX = tx; snapX = tx } }; for (ty in oY) { val d = Math.abs(tY - ty); if (d < bDy) { bDy = d; tY = ty; snapY = ty } } }
                        val r = Math.toRadians(element.rotation.toDouble()); val co = Math.cos(r).toFloat(); val si = Math.sin(r).toFloat()
                        val dxW = tX - anchorWorldX; val dyW = tY - anchorWorldY; val dLx = dxW * co + dyW * si; val dLy = -dxW * si + dyW * co
                        var fW = startW; var fH = startH
                        when (resizeHandleType) {
                            1, 2, 3, 4 -> { fW = Math.abs(dLx).coerceAtLeast(20f); fH = Math.abs(dLy).coerceAtLeast(20f) }
                            5, 6 -> { fH = Math.abs(dLy).coerceAtLeast(20f) }; 7, 8 -> { fW = Math.abs(dLx).coerceAtLeast(20f) }
                        }
                        val lOff = when (resizeHandleType) {
                            1 -> floatArrayOf(fW/2, fH/2); 2 -> floatArrayOf(-fW/2, fH/2); 3 -> floatArrayOf(fW/2, -fH/2); 4 -> floatArrayOf(-fW/2, -fH/2)
                            5 -> floatArrayOf(0f, fH/2); 6 -> floatArrayOf(0f, -fH/2); 7 -> floatArrayOf(fW/2, 0f); 8 -> floatArrayOf(-fW/2, 0f); else -> floatArrayOf(0f, 0f)
                        }
                        val wOffX = lOff[0] * co - lOff[1] * si; val wOffY = lOff[0] * si + lOff[1] * co
                        element.width = fW; element.height = fH; element.x = (anchorWorldX - wOffX) - fW / 2; element.y = (anchorWorldY - wOffY) - fH / 2; constrainToCanvas(element)
                    } else if (isRotating) { val a = Math.toDegrees(Math.atan2((wY - (element.y + element.height/2)).toDouble(), (wX - (element.x + element.width/2)).toDouble())).toFloat(); element.rotation = Math.round((a + 90f) / 15f) * 15f; constrainToCanvas(element) }
                    else { element.x = startX + (wX - startTouchX); element.y = startY + (wY - startTouchY); applySnapping(element, false); constrainToCanvas(element) }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (potentialSelection != null && event.pointerCount == 1) confirmSelection()
                if (isResizing || isRotating || isMovingElement || (selectedElement != null && !isPanning)) onHistorySaveRequested?.invoke()
                isResizing = false; isRotating = false; isPanning = false; isMovingElement = false; resizeHandleType = 0; potentialSelection = null; snapX = null; snapY = null; handler.removeCallbacks(longPressAction); longPressedElement = null; invalidate()
            }
        }
        return true
    }

    private fun confirmSelection() { potentialSelection?.let { if (it != selectedElement) { onHistorySaveRequested?.invoke(); selectedElement = it } }; potentialSelection = null; invalidate() }

    fun constrainToCanvas(e: HotelRoomLayoutEntity) { val a = getAABB(e); if (a.left < 0) e.x -= a.left; if (a.right > CANVAS_WIDTH) e.x -= (a.right - CANVAS_WIDTH); if (a.top < 0) e.y -= a.top; if (a.bottom > CANVAS_HEIGHT) e.y -= (a.bottom - CANVAS_HEIGHT) }

    private fun getAnchorPointWorld(e: HotelRoomLayoutEntity, hT: Int): FloatArray {
        val cX = e.x + e.width / 2; val cY = e.y + e.height / 2
        val lA = when (hT) {
            1 -> floatArrayOf(e.x + e.width, e.y + e.height); 2 -> floatArrayOf(e.x, e.y + e.height); 3 -> floatArrayOf(e.x + e.width, e.y); 4 -> floatArrayOf(e.x, e.y)
            5 -> floatArrayOf(cX, e.y + e.height); 6 -> floatArrayOf(cX, e.y); 7 -> floatArrayOf(e.x + e.width, cY); 8 -> floatArrayOf(e.x, cY); else -> floatArrayOf(cX, cY)
        }
        val m = android.graphics.Matrix(); m.postRotate(e.rotation, cX, cY); val pts = floatArrayOf(lA[0], lA[1]); m.mapPoints(pts); return pts
    }

    private fun getLocalCoordinates(x: Float, y: Float, cX: Float, cY: Float, r: Float): FloatArray {
        val aR = Math.toRadians((-r).toDouble()); val co = Math.cos(aR).toFloat(); val si = Math.sin(aR).toFloat()
        val dx = x - cX; val dy = y - cY; return floatArrayOf((dx * co - dy * si) + cX, (dx * si + dy * co) + cY)
    }

    private fun applySnapping(e: HotelRoomLayoutEntity, isRes: Boolean) {
        if (isRes) return
        snapX = null; snapY = null; val ea = getAABB(e); var bDx = SNAP_THRESHOLD / scaleFactor; var bDy = SNAP_THRESHOLD / scaleFactor
        layoutElements.forEach { other -> if (other == e) return@forEach; val oa = getAABB(other); val sX = listOf(ea.left, ea.right, ea.centerX()); val tX = listOf(oa.left, oa.right, oa.centerX()); for (s in sX) for (t in tX) { val d = Math.abs(s - t); if (d < bDx) { bDx = d; e.x += (t - s); snapX = t } }; val sY = listOf(ea.top, ea.bottom, ea.centerY()); val tY = listOf(oa.top, oa.bottom, oa.centerY()); for (s in sY) for (t in tY) { val d = Math.abs(s - t); if (d < bDy) { bDy = d; e.y += (t - s); snapY = t } } }
        if (snapX == null) { val gX = Math.round(e.x / 20f) * 20f; if (Math.abs(e.x - gX) < 10f) e.x = gX }
        if (snapY == null) { val gY = Math.round(e.y / 20f) * 20f; if (Math.abs(e.y - gY) < 10f) e.y = gY }
    }

    private fun getAABB(e: HotelRoomLayoutEntity): RectF {
        val m = android.graphics.Matrix(); m.postRotate(e.rotation, e.x + e.width / 2, e.y + e.height / 2)
        val ex = if (e.type == "WALL" && e.isHollow) e.strokeWidth / 2 else 0f
        val p = floatArrayOf(e.x - ex, e.y - ex, e.x + e.width + ex, e.y - ex, e.x + e.width + ex, e.y + e.height + ex, e.x - ex, e.y + e.height + ex)
        m.mapPoints(p); var miX = p[0]; var maX = p[0]; var miY = p[1]; var maY = p[1]
        for (i in 0 until p.size step 2) { miX = minOf(miX, p[i]); maX = maxOf(maX, p[i]); miY = minOf(miY, p[i+1]); maY = maxOf(maY, p[i+1]) }
        return RectF(miX, miY, maX, maY)
    }

    private fun showElementOptions(e: HotelRoomLayoutEntity) {
        val o = mutableListOf<String>(); o.add("Duplicar")
        if (e.type == "WALL") { o.add(if (e.isHollow) "Poner Relleno" else "Quitar Relleno"); if (e.isHollow) { o.add("Aumentar Grosor"); o.add("Disminuir Grosor") } }
        else if (e.type == "DOOR") { o.add("Voltear Lado (L/R)"); o.add("Voltear Apertura (In/Out)") }
        androidx.appcompat.app.AlertDialog.Builder(context).setTitle("Opciones de Elemento").setItems(o.toTypedArray()) { _, w ->
            onHistorySaveRequested?.invoke()
            when (o[w]) {
                "Duplicar" -> onDuplicateRequested?.invoke(e)
                "Poner Relleno", "Quitar Relleno" -> e.isHollow = !e.isHollow
                "Aumentar Grosor" -> e.strokeWidth = (e.strokeWidth + 4f).coerceAtMost(40f)
                "Disminuir Grosor" -> e.strokeWidth = (e.strokeWidth - 4f).coerceAtLeast(2f)
                "Voltear Lado (L/R)" -> e.isHollow = !e.isHollow
                "Voltear Apertura (In/Out)" -> e.strokeWidth = if (e.strokeWidth > 5f) 2f else 8f
            }
            invalidate()
        }.show()
    }

    fun deleteSelected() { selectedElement?.let { onHistorySaveRequested?.invoke(); layoutElements.remove(it); selectedElement = null; invalidate() } }
}
