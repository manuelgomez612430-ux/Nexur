package com.naxor.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.naxor.app.data.LoanClientEntity
import com.naxor.app.data.LoanEntity
import com.naxor.app.data.LoanInstallmentEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LoanPdfGenerator(private val context: Context) {

    private val successColor = Color.parseColor("#059669") // Emerald 600

    fun generateLoanContract(client: LoanClientEntity, loan: LoanEntity, installments: List<LoanInstallmentEntity>): File? {
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = prefs.getString("business_type", "LOANS") ?: "LOANS"
        
        val bName = prefs.getString("${bType}_name", prefs.getString("business_name", ""))
        val oName = prefs.getString("${bType}_owner_name", "")
        val oDni = prefs.getString("${bType}_owner_dni", "")

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint().apply { isAntiAlias = true }
        val center = 595f / 2f
        
        // --- FONDO LIMPIO ---
        canvas.drawColor(Color.WHITE)
        
        // --- CABECERA SOBRIA Y FORMAL ---
        // Dibujamos una línea doble superior para dar elegancia sin gastar tinta
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        canvas.drawLine(50f, 40f, 545f, 40f, linePaint)
        canvas.drawLine(50f, 45f, 545f, 45f, linePaint)
        
        val titlePaint = Paint().apply { 
            isFakeBoldText = true; 
            textSize = 28f; 
            color = Color.BLACK; 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
            letterSpacing = 0.1f
        }
        canvas.drawText("CONTRATO DE PRÉSTAMO", center, 85f, titlePaint)
        
        // Línea divisoria debajo del título
        canvas.drawLine(150f, 100f, 445f, 100f, Paint().apply { color = Color.GRAY; strokeWidth = 1f })
        
        var y = 150f
        val margin = 50f
        val bodyPaint = Paint().apply { textSize = 14f; color = Color.BLACK; isAntiAlias = true }
        val boldPaint = Paint().apply { isFakeBoldText = true; textSize = 14f; color = Color.BLACK; isAntiAlias = true }
        
        // --- TEXTO INTRODUCTORIO ---
        val introText = if (!oName.isNullOrEmpty()) {
            val bizPart = if (!bName.isNullOrEmpty()) " en representación del negocio $bName," else ""
            "Conste por el presente documento el contrato de préstamo que celebran de una parte el PRESTAMISTA, Sr(a). $oName, identificado con DNI N° $oDni,$bizPart y de la otra parte el PRESTATARIO:"
        } else {
            val headerText = if (!bName.isNullOrEmpty()) "el negocio $bName" else "el PRESTAMISTA"
            "Conste por el presente documento el contrato de préstamo que celebran de una parte $headerText, identificado con los datos registrados en el sistema, y de la otra parte el PRESTATARIO:"
        }
        
        // Función simple para dibujar texto largo con saltos de línea
        fun drawMultilineText(text: String, x: Float, startY: Float, paint: Paint, width: Float): Float {
            var currentY = startY
            val words = text.split(" ")
            var line = ""
            for (word in words) {
                val testLine = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(testLine) > width) {
                    canvas.drawText(line, x, currentY, paint)
                    line = word
                    currentY += paint.textSize + 5f
                } else {
                    line = testLine
                }
            }
            canvas.drawText(line, x, currentY, paint)
            return currentY + paint.textSize + 10f
        }

        y = drawMultilineText(introText, margin, y, bodyPaint, 500f)
        y += 10f
        
        // --- DATOS DEL PRESTATARIO ---
        canvas.drawText("DATOS DEL PRESTATARIO:", margin, y, boldPaint)
        y += 25f
        canvas.drawText("Nombre completo: ${client.name}", margin + 15f, y, bodyPaint)
        y += 22f
        canvas.drawText("Documento de Identidad: ${client.doc}", margin + 15f, y, bodyPaint)
        y += 22f
        canvas.drawText("Domicilio registrado: ${client.address ?: "---"}", margin + 15f, y, bodyPaint)
        
        y += 40f
        
        // --- CLÁUSULAS ---
        canvas.drawText("CLÁUSULAS DEL CONTRATO:", margin, y, boldPaint)
        y += 25f
        
        val clauses = listOf(
            "PRIMERA: El PRESTAMISTA entrega al PRESTATARIO la suma de S/ ${String.format(Locale.US, "%.2f", loan.amount)}.",
            "SEGUNDA: El PRESTATARIO se compromete a devolver el monto total de S/ ${String.format(Locale.US, "%.2f", loan.totalToPay)} (incluye intereses de ${loan.interestRate}%).",
            "TERCERA: El pago se realizará de forma ${loan.frequency} en ${loan.installmentsCount} cuotas.",
            "CUARTA: En caso de mora, se aplicará un recargo diario de S/ ${String.format(Locale.US, "%.2f", loan.lateFeeAmount)}.",
            "QUINTA: En caso de incurrir en un estado de morosidad por un periodo prolongado, el PRESTAMISTA se reserva el derecho de emprender las acciones legales correspondientes ante las autoridades pertinentes como último recurso para la recuperación del capital adeudado."
        )

        for (clause in clauses) {
            y = drawMultilineText(clause, margin + 15f, y, bodyPaint, 480f)
            y += 5f
        }

        y += 15f
        canvas.drawText("CRONOGRAMA DE PAGOS:", margin, y, boldPaint)
        y += 20f
        
        // Table Header Moderno (Tinta amigable)
        val tableHeadPaint = Paint().apply { color = Color.parseColor("#F8FAFC"); style = Paint.Style.FILL }
        canvas.drawRect(margin, y, 545f, y + 25f, tableHeadPaint)
        canvas.drawLine(margin, y, 545f, y, Paint().apply { color = Color.BLACK; strokeWidth = 1f })
        canvas.drawLine(margin, y + 25f, 545f, y + 25f, Paint().apply { color = Color.BLACK; strokeWidth = 1f })
        canvas.drawText("CUOTA", margin + 10f, y + 18f, boldPaint.apply { textSize = 11f })
        canvas.drawText("VENCIMIENTO", 150f, y + 18f, boldPaint)
        canvas.drawText("MONTO (S/)", 450f, y + 18f, boldPaint)
        y += 45f
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        installments.take(15).forEach { inst -> // Limitar a 15 para que quepa en una hoja con el texto legal
            canvas.drawText("#${inst.installmentNumber}", margin + 10f, y, bodyPaint.apply { textSize = 12f })
            canvas.drawText(sdf.format(Date(inst.dueDate)), 150f, y, bodyPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", inst.amount), 450f, y, bodyPaint)
            y += 20f
            canvas.drawLine(margin, y - 15f, 545f, y - 15f, Paint().apply { color = Color.parseColor("#F1F5F9") })
        }
        
        // --- SIGNATURES ---
        y = 780f
        canvas.drawLine(80f, y, 250f, y, Paint().apply { color = Color.BLACK; strokeWidth = 1.2f })
        val signatureLabel = if (!oName.isNullOrEmpty()) oName else if (!bName.isNullOrEmpty()) bName else "EL PRESTAMISTA"
        canvas.drawText(signatureLabel, 165f, y + 18f, bodyPaint.apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true })
        
        canvas.drawLine(330f, y, 500f, y, Paint().apply { color = Color.BLACK; strokeWidth = 1.2f })
        canvas.drawText(client.name, 415f, y + 18f, bodyPaint)
        
        bodyPaint.isFakeBoldText = false; bodyPaint.textSize = 10f; bodyPaint.color = Color.GRAY
        canvas.drawText("PRESTAMISTA", 165f, y + 32f, bodyPaint)
        canvas.drawText("PRESTATARIO", 415f, y + 32f, bodyPaint)

        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Contrato_${client.name.replace(" ", "_")}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }

    fun generateDebtReminder(client: LoanClientEntity, loan: LoanEntity, totalPending: Double): File? {
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = prefs.getString("business_type", "LOANS") ?: "LOANS"
        
        val bName = prefs.getString("${bType}_name", prefs.getString("business_name", ""))
        val bPhone = prefs.getString("${bType}_phone", prefs.getString("business_phone", ""))
        val oName = prefs.getString("${bType}_owner_name", "")

        val showPhone = prefs.getBoolean("${bType}_show_phone", true)

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Vertical
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint().apply { isAntiAlias = true }
        val center = 595f / 2f
        
        // --- FONDO LIMPIO ---
        canvas.drawColor(Color.WHITE)
        
        // --- CABECERA MODERNA (BANNER) ---
        val headerPaint = Paint().apply { color = Color.parseColor("#1E293B"); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, 595f, 120f, headerPaint)
        
        val titlePaint = Paint().apply { 
            isFakeBoldText = true; 
            textSize = 34f; 
            color = Color.WHITE; 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
            letterSpacing = 0.05f
        }
        canvas.drawText("ESTADO DE CUENTA", center, 75f, titlePaint)
        
        val subTitlePaint = Paint().apply { 
            textSize = 14f; 
            color = Color.parseColor("#94A3B8"); 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
        }
        canvas.drawText("Recordatorio de pago pendiente", center, 100f, subTitlePaint)
        
        var y = 180f
        
        // --- SALUDO ---
        val namePaint = Paint().apply { 
            isFakeBoldText = true; 
            textSize = 22f; 
            color = Color.parseColor("#0F172A"); 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
        }
        canvas.drawText("Hola, ${client.name}", center, y, namePaint)
        y += 45f
        
        // --- CUERPO DEL MENSAJE (MÁS GRANDE Y LEGIBLE) ---
        val bodyPaint = Paint().apply { 
            textSize = 18f; 
            color = Color.parseColor("#334155"); 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
        }
        
        val senderLabel = if (!oName.isNullOrEmpty()) oName else if (!bName.isNullOrEmpty()) bName else "mi sistema de gestión"

        canvas.drawText("Espero que estés teniendo un excelente día.", center, y, bodyPaint)
        y += 30f
        canvas.drawText("Te envío este documento para recordarte el saldo", center, y, bodyPaint)
        y += 28f
        canvas.drawText("pendiente que tienes registrado con $senderLabel.", center, y, bodyPaint)
        
        y += 80f
        
        // --- CAJA DE MONTO (ALTO CONTRASTE) ---
        // Borde exterior
        val boxStrokePaint = Paint().apply { 
            color = Color.parseColor("#E2E8F0"); 
            style = Paint.Style.STROKE; 
            strokeWidth = 2f 
        }
        canvas.drawRoundRect(80f, y, 515f, y + 160f, 25f, 25f, boxStrokePaint)
        
        // Fondo interno
        val boxFillPaint = Paint().apply { color = Color.parseColor("#F8FAFC"); style = Paint.Style.FILL }
        canvas.drawRoundRect(82f, y + 2f, 513f, y + 158f, 25f, 25f, boxFillPaint)
        
        val labelPaint = Paint().apply { 
            textSize = 20f
            color = Color.parseColor("#64748B")
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            letterSpacing = 0.1f 
        }
        canvas.drawText("TOTAL A PAGAR", center, y + 50f, labelPaint)
        
        val amountPaint = Paint().apply { 
            isFakeBoldText = true
            textSize = 54f
            color = Color.parseColor("#EF4444") // Rojo intenso (Vibrant Red)
            textAlign = Paint.Align.CENTER 
        }
        canvas.drawText("S/ ${String.format(Locale.US, "%.2f", totalPending)}", center, y + 115f, amountPaint)
        
        y += 230f
        
        // --- INSTRUCCIONES DE PAGO ---
        val instrTitlePaint = Paint().apply { 
            isFakeBoldText = true
            textSize = 16f
            color = Color.parseColor("#1E293B")
            textAlign = Paint.Align.CENTER 
        }
        canvas.drawText("¿CÓMO REALIZAR TU PAGO?", center, y, instrTitlePaint)
        y += 35f
        
        val instrBodyPaint = Paint().apply { 
            textSize = 15f
            color = Color.parseColor("#475569")
            textAlign = Paint.Align.CENTER
            isAntiAlias = true 
        }
        
        val contactAction = if (showPhone && !bPhone.isNullOrEmpty()) "escribiéndome al $bPhone" else "poniéndote en contacto conmigo"
        canvas.drawText("Puedes coordinar tu abono directamente $contactAction.", center, y, instrBodyPaint)
        y += 25f
        canvas.drawText("Mantener tus cuentas al día te ayuda a mejorar tu calificación", center, y, instrBodyPaint)
        y += 25f
        canvas.drawText("en el sistema y acceder a mejores beneficios.", center, y, instrBodyPaint)
        
        y += 70f
        
        // --- NOTA FINAL ---
        val notePaint = Paint().apply { 
            textSize = 13f
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC) 
        }
        canvas.drawText("Si ya realizaste tu pago, por favor ignora este mensaje y envíame tu comprobante.", center, y, notePaint)
        
        // --- PIE DE PÁGINA (BRANDING) ---
        y = 790f
        val footerText = if (!bName.isNullOrEmpty()) bName else "GoMezMart"
        val brandPaint = Paint().apply { 
            textSize = 12f
            color = Color.parseColor("#CBD5E1")
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true 
        }
        canvas.drawText(footerText.uppercase(), center, y, brandPaint)
        
        val datePaint = Paint().apply { 
            textSize = 10f
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER 
        }
        canvas.drawText("Generado el ${SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es")).format(Date())}", center, y + 18f, datePaint)

        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Recordatorio_${client.name.replace(" ", "_")}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }

    fun generatePaymentReceipt(client: LoanClientEntity, loan: LoanEntity, installment: LoanInstallmentEntity, paidAmount: Double): File? {
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bType = prefs.getString("business_type", "LOANS") ?: "LOANS"
        val bName = prefs.getString("${bType}_name", prefs.getString("business_name", "GoMezMart")) ?: "GoMezMart"
        val showBusiness = prefs.getBoolean("${bType}_show_business", true)

        val document = PdfDocument()
        val w = 420; val h = 680
        val pageInfo = PdfDocument.PageInfo.Builder(w, h, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint().apply { isAntiAlias = true }
        val center = w / 2f
        
        // --- BACKGROUND ---
        canvas.drawColor(Color.parseColor("#F8FAFC")) // Slate 50

        // --- CABECERA CURVA MODERNA ---
        val headerPaint = Paint().apply { color = Color.parseColor("#059669"); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w.toFloat(), 180f, headerPaint)
        
        // Dibujar un círculo blanco en el centro de la cabecera para el "Check" visual
        paint.color = Color.WHITE
        canvas.drawCircle(center, 90f, 45f, paint)
        
        // Dibujar un Checkmark (V) en verde
        paint.color = Color.parseColor("#059669")
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        val path = android.graphics.Path()
        path.moveTo(center - 15f, 90f)
        path.lineTo(center - 5f, 105f)
        path.lineTo(center + 18f, 78f)
        canvas.drawPath(path, paint)
        
        paint.style = Paint.Style.FILL
        val titlePaint = Paint().apply { 
            isFakeBoldText = true; textSize = 22f; color = Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true; letterSpacing = 0.05f 
        }
        canvas.drawText("PAGO RECIBIDO EXITOSAMENTE", center, 160f, titlePaint)

        // --- TARJETA PRINCIPAL (RECIBO) ---
        val cardPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRoundRect(25f, 190f, w - 25f, h - 80f, 30f, 30f, cardPaint)
        
        // Sombra suave bajo la tarjeta
        val shadowPaint = Paint().apply { color = Color.parseColor("#10000000"); style = Paint.Style.FILL }
        canvas.drawRoundRect(28f, 195f, w - 22f, h - 75f, 30f, 30f, shadowPaint)

        var y = 240f
        val labelPaint = Paint().apply { textSize = 13f; color = Color.parseColor("#64748B"); isAntiAlias = true; isFakeBoldText = true }
        val valPaint = Paint().apply { textSize = 15f; color = Color.parseColor("#0F172A"); isAntiAlias = true; isFakeBoldText = true }
        
        // --- DATOS DEL NEGOCIO ---
        if (showBusiness) {
            val bizTitlePaint = Paint().apply { textSize = 18f; color = Color.parseColor("#059669"); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
            canvas.drawText(bName.uppercase(), center, y, bizTitlePaint)
            y += 40f
        }

        // --- LÍNEA DIVISORIA PUNTOS ---
        val dashPaint = Paint().apply { 
            color = Color.parseColor("#E2E8F0"); strokeWidth = 2f; style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        canvas.drawLine(50f, y, w - 50f, y, dashPaint)
        y += 40f
        
        // --- DETALLE DEL CLIENTE ---
        canvas.drawText("PAGADO POR:", 50f, y, labelPaint)
        canvas.drawText(client.name.uppercase(), 160f, y, valPaint)
        y += 35f
        canvas.drawText("N° RECIBO:", 50f, y, labelPaint)
        canvas.drawText(UUID.randomUUID().toString().take(8).uppercase(), 160f, y, valPaint)
        y += 35f
        canvas.drawText("FECHA:", 50f, y, labelPaint)
        canvas.drawText(SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale.getDefault()).format(Date()), 160f, y, valPaint)
        y += 35f
        canvas.drawText("CONCEPTO:", 50f, y, labelPaint)
        canvas.drawText("CUOTA #${installment.installmentNumber}", 160f, y, valPaint)
        
        y += 60f
        
        // --- SECCIÓN DE MONTO (CREATIVA) ---
        val amountBgPaint = Paint().apply { color = Color.parseColor("#ECFDF5"); style = Paint.Style.FILL }
        canvas.drawRoundRect(60f, y, w - 60f, y + 90f, 20f, 20f, amountBgPaint)
        
        val amountLabelPaint = Paint().apply { textSize = 12f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER; isFakeBoldText = true; letterSpacing = 0.1f }
        canvas.drawText("IMPORTE TOTAL ABONADO", center, y + 30f, amountLabelPaint)
        
        val amountValPaint = Paint().apply { textSize = 42f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        canvas.drawText("S/ ${String.format(Locale.US, "%.2f", paidAmount)}", center, y + 75f, amountValPaint)
        
        y += 140f
        
        // --- SALDO PENDIENTE ---
        val remaining = (installment.amount - installment.amountPaid).coerceAtLeast(0.0)
        if (remaining > 0.01) {
            val remainPaint = Paint().apply { textSize = 14f; color = Color.parseColor("#94A3B8"); textAlign = Paint.Align.CENTER }
            canvas.drawText("Saldo pendiente de esta cuota: S/ ${String.format(Locale.US, "%.2f", remaining)}", center, y, remainPaint)
        } else {
            val successTextPaint = Paint().apply { isFakeBoldText = true; textSize = 16f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER }
            canvas.drawText("¡CUOTA CANCELADA TOTALMENTE! 🎯", center, y, successTextPaint)
        }
        
        // --- PIE DE PÁGINA ---
        y = h - 40f
        val footerNotePaint = Paint().apply { 
            textSize = 11f; color = Color.parseColor("#94A3B8"); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC) 
        }
        canvas.drawText("Gracias por confiar en nuestra gestión profesional.", center, y, footerNotePaint)

        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Recibo_${installment.id.take(5)}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }

    fun generateDailyCollectionReport(installments: List<LoanInstallmentEntity>): File? {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        
        canvas.drawRect(0f, 0f, 595f, 90f, Paint().apply { color = Color.parseColor("#1E293B") }) // Slate 800
        
        val titlePaint = Paint().apply { isFakeBoldText = true; textSize = 24f; color = Color.WHITE }
        canvas.drawText("REPORTE DE COBRANZA DIARIA", 50f, 55f, titlePaint)
        
        val bodyPaint = Paint().apply { textSize = 12f; color = Color.WHITE }
        canvas.drawText("Fecha: ${SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("es")).format(Date())}", 50f, 80f, bodyPaint)
        
        var y = 140f
        val headPaint = Paint().apply { isFakeBoldText = true; textSize = 13f; color = Color.BLACK }
        canvas.drawText("CLIENTE", 50f, y, headPaint)
        canvas.drawText("CUOTA", 300f, y, headPaint)
        canvas.drawText("MONTO (S/)", 450f, y, headPaint)
        y += 10f
        canvas.drawLine(50f, y, 545f, y, Paint().apply { color = Color.BLACK; strokeWidth = 2f })
        y += 30f
        
        var total = 0.0
        val db = com.naxor.app.data.AppDatabase.getDatabase(context)
        val rowPaint = Paint().apply { textSize = 12f; color = Color.DKGRAY }
        
        kotlinx.coroutines.runBlocking {
            installments.forEach { inst ->
                val loan = db.loanDao().getLoanById(inst.loanId)
                val client = loan?.let { db.loanDao().getClientById(it.clientId) }
                
                canvas.drawText(client?.name ?: "Desconocido", 50f, y, rowPaint)
                canvas.drawText("#${inst.installmentNumber}", 300f, y, rowPaint)
                canvas.drawText(String.format(Locale.US, "%.2f", inst.amount), 450f, y, rowPaint)
                total += inst.amount
                y += 25f
                canvas.drawLine(50f, y - 15f, 545f, y - 15f, Paint().apply { color = Color.parseColor("#F1F5F9") })
            }
        }
        
        y += 30f
        canvas.drawRect(350f, y - 20f, 545f, y + 30f, Paint().apply { color = Color.parseColor("#F0FDF4") })
        canvas.drawText("TOTAL ESTIMADO:", 360f, y + 10f, headPaint.apply { color = successColor })
        canvas.drawText("S/ ${String.format(Locale.US, "%.2f", total)}", 480f, y + 10f, headPaint)
        
        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Reporte_Cobros_Diario.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }
}
