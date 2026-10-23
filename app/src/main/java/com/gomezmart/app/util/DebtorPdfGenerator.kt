package com.gomezmart.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.gomezmart.app.data.DebtorEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DebtorPdfGenerator(private val context: Context) {

    fun generateDebtorReport(debtor: DebtorEntity, isProfessional: Boolean): File? {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint()
        val boldPaint = Paint().apply { isFakeBoldText = true }
        
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = prefs.getString("business_type", "PRODUCTS") ?: "PRODUCTS"
        
        val bName = prefs.getString("${bType}_name", prefs.getString("business_name", "MI NEGOCIO"))
        val bPhone = prefs.getString("${bType}_phone", prefs.getString("business_phone", ""))
        val bAddress = prefs.getString("${bType}_address", prefs.getString("business_address", ""))
        val oName = prefs.getString("${bType}_owner_name", "")

        val showOwner = prefs.getBoolean("${bType}_show_owner", false)
        val showBusiness = prefs.getBoolean("${bType}_show_business", true)
        val showAddress = prefs.getBoolean("${bType}_show_address", true)
        val showPhone = prefs.getBoolean("${bType}_show_phone", true)
        
        // --- 1. FONDO Y ESTILO ---
        canvas.drawColor(Color.WHITE)
        val accentColor = if (isProfessional) Color.parseColor("#4C1D95") else Color.parseColor("#0F172A") // Púrpura vs Slate oscuro
        
        if (isProfessional) {
            // Barra lateral decorativa solo para profesional
            paint.color = accentColor
            canvas.drawRect(0f, 0f, 15f, 842f, paint)
        }

        // --- 2. CABECERA ---
        boldPaint.textSize = 24f; boldPaint.color = accentColor
        
        var headerY = 70f
        if (showBusiness && !bName.isNullOrEmpty()) {
            canvas.drawText(bName.uppercase(), 50f, headerY, boldPaint)
            headerY += 20f
        }
        
        if (showOwner && !oName.isNullOrEmpty()) {
            paint.textSize = 12f; paint.color = Color.BLACK; paint.isFakeBoldText = true
            canvas.drawText("Titular: ${oName.uppercase()}", 50f, headerY, paint)
            headerY += 15f
        }
        
        paint.textSize = 10f; paint.color = Color.GRAY; paint.isFakeBoldText = false
        val headerSubtitle = if (isProfessional) "COMPROBANTE DE ESTADO DE CUENTA" else "RECORDATORIO DE PAGO"
        canvas.drawText(headerSubtitle, 50f, headerY, paint)
        headerY += 15f
        
        if (isProfessional) {
            if (showAddress && !bAddress.isNullOrEmpty()) {
                canvas.drawText("📍 $bAddress", 50f, headerY, paint)
                headerY += 15f
            }
            if (showPhone && !bPhone.isNullOrEmpty()) {
                canvas.drawText("📞 Contacto: $bPhone", 50f, headerY, paint)
            }
        } else {
            canvas.drawText("Generado el: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())}", 50f, headerY, paint)
        }
        
        // Línea divisoria
        paint.color = Color.parseColor("#E2E8F0")
        canvas.drawLine(50f, 140f, 545f, 140f, paint)

        // --- 3. DATOS DEL CLIENTE ---
        boldPaint.textSize = 14f; boldPaint.color = Color.BLACK
        canvas.drawText("INFORMACIÓN DEL CLIENTE", 50f, 170f, boldPaint)
        
        paint.textSize = 12f; paint.color = Color.DKGRAY
        canvas.drawText("Nombre: ${debtor.nombre.uppercase()}", 50f, 195f, paint)
        if (isProfessional) {
            canvas.drawText("Contacto: ${if (debtor.telefono.isBlank()) "Sin número" else debtor.telefono}", 50f, 215f, paint)
        }
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        if (debtor.fechaCobro > 0) {
            canvas.drawText("Fecha Límite de Pago: ${sdf.format(Date(debtor.fechaCobro))}", 50f, 235f, paint)
        }

        // --- 4. CUADRO DE DEUDA DESTACADO ---
        paint.color = if (isProfessional) Color.parseColor("#F8FAFC") else Color.parseColor("#F1F5F9")
        canvas.drawRoundRect(50f, 270f, 545f, 360f, 15f, 15f, paint)
        
        boldPaint.textSize = 16f; boldPaint.color = Color.parseColor("#1E293B")
        val totalLabel = if (isProfessional) "SALDO PENDIENTE A LA FECHA" else "MONTO A PAGAR"
        canvas.drawText(totalLabel, 75f, 305f, boldPaint)
        
        boldPaint.textSize = 32f; boldPaint.color = if (isProfessional) Color.parseColor("#DC2626") else Color.BLACK
        val currency = prefs.getString("currency_symbol", "S/")
        canvas.drawText("$currency ${String.format("%.2f", debtor.deudaTotal)}", 75f, 340f, boldPaint)

        // --- 5. MENSAJE ---
        paint.textSize = 11f; paint.color = Color.BLACK
        var yPos = 420f
        
        val senderLabel = if (showBusiness && !bName.isNullOrEmpty()) bName else if (!oName.isNullOrEmpty()) oName else "mi sistema"

        val messages = if (isProfessional) {
            listOf(
                "Estimado(a) ${debtor.nombre},",
                "En *$senderLabel* valoro mucho su preferencia y confianza. Trabajo día a día",
                "para ofrecerle el mejor servicio y calidad que usted se merece.",
                "",
                "Le envío este documento como un recordatorio formal de su saldo pendiente.",
                "Mantener sus cuentas al día me permite seguir brindándole beneficios exclusivos",
                "y una atención de primera clase.",
                "",
                "💳 MÉTODOS DE PAGO DISPONIBLES:",
                "- Transferencia bancaria o depósitos.",
                "- Pagos directos en mi local.",
                "- Billeteras digitales (Yape/Plin) al número: $bPhone",
                "",
                "Si ya realizó el pago, por favor ignore este mensaje y envíeme el comprobante",
                "para actualizar mi sistema. ¡Muchas gracias por su puntualidad!"
            )
        } else {
            listOf(
                "Hola ${debtor.nombre},",
                "Espero que te encuentres muy bien.",
                "",
                "Te envío este pequeño recordatorio sobre el saldo pendiente que tengo",
                "registrado con $senderLabel por el valor mencionado arriba.",
                "",
                "Agradecería mucho que pudieras ponerte al día en cuanto te sea posible.",
                "Esto me ayuda a seguir trabajando contigo de la mejor manera.",
                "",
                "💰 MEDIOS DE PAGO:",
                "- En efectivo o transferencia.",
                "- Billeteras digitales: $bPhone",
                "",
                "Cualquier duda o si ya realizaste el pago, solo avísame.",
                "¡Que tengas un excelente día!"
            )
        }

        messages.forEach { line ->
            canvas.drawText(line, 50f, yPos, paint)
            yPos += 18f
        }

        // --- 6. PIE DE PÁGINA ---
        paint.textSize = 9f; paint.color = Color.LTGRAY
        val sdfFull = SimpleDateFormat("dd 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "PE"))
        canvas.drawText("Documento informativo generado por GoMezMart.", 50f, 780f, paint)
        canvas.drawText("Emisión: ${sdfFull.format(Date())}", 50f, 795f, paint)
        
        if (isProfessional) {
            boldPaint.textSize = 12f; boldPaint.color = accentColor
            canvas.drawText("GRACIAS POR SU PREFERENCIA", 50f, 815f, boldPaint)
        }

        doc.finishPage(page)
        
        val suffix = if (isProfessional) "Empresarial" else "Personal"
        val fileName = "Estado_Cuenta_${debtor.nombre.replace(" ", "_")}_$suffix.pdf"
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
