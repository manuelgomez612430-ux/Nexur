package com.naxor.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.naxor.app.data.DebtorEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DebtorPdfGenerator(private val context: Context) {

    fun generateDebtorReport(debtor: DebtorEntity): File? {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint()
        val boldPaint = Paint().apply { isFakeBoldText = true }
        
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bName = prefs.getString("business_name", "MI NEGOCIO") ?: "MI NEGOCIO"
        val bPhone = prefs.getString("business_phone", "") ?: ""
        
        // 1. Cabecera (Empresa)
        boldPaint.textSize = 22f; boldPaint.color = Color.parseColor("#6B21A8")
        canvas.drawText(bName.uppercase(), 50f, 60f, boldPaint)
        
        paint.textSize = 10f; paint.color = Color.GRAY
        canvas.drawText("Estado de Cuenta de Cliente", 50f, 80f, paint)
        canvas.drawText("Teléfono: $bPhone", 50f, 95f, paint)
        
        canvas.drawLine(50f, 110f, 545f, 110f, paint)

        // 2. Datos del Deudor
        boldPaint.textSize = 16f; boldPaint.color = Color.BLACK
        canvas.drawText("CLIENTE: ${debtor.nombre.uppercase()}", 50f, 150f, boldPaint)
        
        paint.textSize = 12f; paint.color = Color.DKGRAY
        canvas.drawText("Teléfono registrado: ${if (debtor.telefono.isBlank()) "No registrado" else debtor.telefono}", 50f, 175f, paint)
        
        if (debtor.fechaCobro > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            canvas.drawText("Fecha programada de pago: ${sdf.format(Date(debtor.fechaCobro))}", 50f, 195f, paint)
        }

        // 3. Resumen de Deuda
        paint.color = Color.parseColor("#F1F5F9")
        canvas.drawRect(50f, 230f, 545f, 310f, paint)
        
        boldPaint.textSize = 14f; boldPaint.color = Color.BLACK
        canvas.drawText("SALDO PENDIENTE TOTAL", 70f, 260f, boldPaint)
        
        boldPaint.textSize = 24f; boldPaint.color = Color.parseColor("#DC2626")
        val currency = prefs.getString("currency_symbol", "S/")
        canvas.drawText("$currency ${String.format("%.2f", debtor.deudaTotal)}", 70f, 290f, boldPaint)

        // 4. Pie de página
        paint.textSize = 10f; paint.color = Color.GRAY
        val sdfFull = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        canvas.drawText("Documento generado el: ${sdfFull.format(Date())}", 50f, 780f, paint)
        canvas.drawText("Naxor - Tu Gestión Inteligente", 50f, 800f, paint)

        doc.finishPage(page)
        
        val fileName = "Estado_Cuenta_${debtor.nombre.replace(" ", "_")}.pdf"
        val file = File(context.getExternalFilesDir(null), fileName)
        return try {
            doc.writeTo(file.outputStream())
            doc.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
