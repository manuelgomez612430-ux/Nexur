package com.naxor.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.naxor.app.data.BusinessDebtEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BusinessDebtPdfGenerator(private val context: Context) {

    fun generatePaymentCommitment(debt: BusinessDebtEntity): File? {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint()
        val boldPaint = Paint().apply { isFakeBoldText = true }
        
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bName = prefs.getString("business_name", "MI NEGOCIO") ?: "MI NEGOCIO"
        
        // 1. Cabecera
        boldPaint.textSize = 22f; boldPaint.color = Color.parseColor("#1E293B")
        canvas.drawText("COMPROMISO DE PAGO", 50f, 60f, boldPaint)
        
        paint.textSize = 10f; paint.color = Color.GRAY
        canvas.drawText("Emitido por: $bName", 50f, 80f, paint)
        canvas.drawLine(50f, 100f, 545f, 100f, paint)

        // 2. Información del Acreedor
        boldPaint.textSize = 16f; boldPaint.color = Color.BLACK
        canvas.drawText("ACREEDOR: ${debt.acreedor.uppercase()}", 50f, 140f, boldPaint)
        canvas.drawText("CONCEPTO: ${debt.concepto}", 50f, 165f, paint)

        if (debt.fechaVencimiento > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            canvas.drawText("FECHA COMPROMISO: ${sdf.format(Date(debt.fechaVencimiento))}", 50f, 190f, boldPaint)
        }

        // 3. Monto
        paint.color = Color.parseColor("#F1F5F9")
        canvas.drawRect(50f, 220f, 545f, 300f, paint)
        
        boldPaint.textSize = 14f; boldPaint.color = Color.BLACK
        canvas.drawText("MONTO PENDIENTE", 70f, 250f, boldPaint)
        
        boldPaint.textSize = 24f; boldPaint.color = Color.parseColor("#475569")
        val currency = prefs.getString("currency_symbol", "S/")
        canvas.drawText("$currency ${String.format(Locale.getDefault(), "%.2f", debt.montoTotal - debt.montoPagado)}", 70f, 280f, boldPaint)

        // 4. Footer
        paint.textSize = 10f; paint.color = Color.GRAY
        canvas.drawText("Documento de control interno generado por Naxor", 50f, 800f, paint)

        doc.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Compromiso_Pago_${debt.acreedor.replace(" ", "_")}.pdf")
        return try {
            doc.writeTo(file.outputStream())
            doc.close()
            file
        } catch (e: Exception) {
            null
        }
    }
}
