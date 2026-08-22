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
        val bAddress = prefs.getString("business_address", "Dirección no registrada") ?: "Dirección no registrada"
        
        // --- 1. FONDO Y ESTILO ---
        canvas.drawColor(Color.WHITE)
        val accentColor = Color.parseColor("#4C1D95") // Púrpura Premium
        
        // Barra lateral decorativa
        paint.color = accentColor
        canvas.drawRect(0f, 0f, 15f, 842f, paint)

        // --- 2. CABECERA ---
        boldPaint.textSize = 28f; boldPaint.color = accentColor
        canvas.drawText(bName.uppercase(), 50f, 70f, boldPaint)
        
        paint.textSize = 10f; paint.color = Color.GRAY
        canvas.drawText("COMPROBANTE DE ESTADO DE CUENTA", 50f, 90f, paint)
        canvas.drawText("📍 $bAddress", 50f, 105f, paint)
        canvas.drawText("📞 Contacto: $bPhone", 50f, 120f, paint)
        
        // Línea divisoria
        paint.color = Color.parseColor("#E2E8F0")
        canvas.drawLine(50f, 140f, 545f, 140f, paint)

        // --- 3. DATOS DEL CLIENTE ---
        boldPaint.textSize = 14f; boldPaint.color = Color.BLACK
        canvas.drawText("INFORMACIÓN DEL CLIENTE", 50f, 170f, boldPaint)
        
        paint.textSize = 12f; paint.color = Color.DKGRAY
        canvas.drawText("Nombre: ${debtor.nombre.uppercase()}", 50f, 195f, paint)
        canvas.drawText("Documento: ${if (debtor.telefono.isBlank()) "Sin número" else debtor.telefono}", 50f, 215f, paint)
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        if (debtor.fechaCobro > 0) {
            canvas.drawText("Fecha Límite de Pago: ${sdf.format(Date(debtor.fechaCobro))}", 50f, 235f, paint)
        }

        // --- 4. CUADRO DE DEUDA DESTACADO ---
        paint.color = Color.parseColor("#F8FAFC")
        canvas.drawRoundRect(50f, 270f, 545f, 360f, 15f, 15f, paint)
        
        boldPaint.textSize = 16f; boldPaint.color = Color.parseColor("#1E293B")
        canvas.drawText("SALDO PENDIENTE A LA FECHA", 75f, 305f, boldPaint)
        
        boldPaint.textSize = 32f; boldPaint.color = Color.parseColor("#DC2626")
        val currency = prefs.getString("currency_symbol", "S/")
        canvas.drawText("$currency ${String.format("%.2f", debtor.deudaTotal)}", 75f, 340f, boldPaint)

        // --- 5. MENSAJE PROMOCIONAL / COBRO FORMAL ---
        paint.textSize = 11f; paint.color = Color.BLACK
        var yPos = 420f
        val messages = listOf(
            "Estimado(a) ${debtor.nombre},",
            "En *${bName}* valoramos mucho su preferencia y confianza. Trabajamos día a día",
            "para ofrecerle el mejor servicio y calidad que usted se merece.",
            "",
            "Le enviamos este documento como un recordatorio formal de su saldo pendiente.",
            "Mantener sus cuentas al día nos permite seguir brindándole beneficios exclusivos",
            "y una atención de primera clase.",
            "",
            "💳 *MÉTODOS DE PAGO DISPONIBLES:*",
            "- Transferencia bancaria o depósitos.",
            "- Pagos directos en nuestras instalaciones.",
            "- Billeteras digitales (Yape/Plin) al número: $bPhone",
            "",
            "Si ya realizó el pago, por favor ignore este mensaje y envíenos el comprobante",
            "para actualizar nuestro sistema. ¡Muchas gracias por su puntualidad!"
        )

        messages.forEach { line ->
            if (line.startsWith("💳")) boldPaint.textSize = 11f else boldPaint.textSize = 11f
            canvas.drawText(line, 50f, yPos, paint)
            yPos += 18f
        }

        // --- 6. PIE DE PÁGINA ---
        paint.textSize = 9f; paint.color = Color.LTGRAY
        val sdfFull = SimpleDateFormat("dd 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "PE"))
        canvas.drawText("Este documento tiene carácter informativo. Generado automáticamente por NEXUR.", 50f, 780f, paint)
        canvas.drawText("Fecha de emisión: ${sdfFull.format(Date())}", 50f, 795f, paint)
        
        boldPaint.textSize = 12f; boldPaint.color = accentColor
        canvas.drawText("GRACIAS POR SU PREFERENCIA", 50f, 815f, boldPaint)

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
