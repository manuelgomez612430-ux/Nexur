package com.gomezmart.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.gomezmart.app.data.AppDatabase
import com.gomezmart.app.data.LoanClientEntity
import com.gomezmart.app.data.LoanEntity
import com.gomezmart.app.data.LoanInstallmentEntity
import kotlinx.coroutines.runBlocking
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
        val oAddress = prefs.getString("${bType}_address", "")

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val center = 595f / 2f
        val margin = 45f
        
        canvas.drawColor(Color.WHITE)
        
        // --- CABECERA FORMAL Y SOBRIA (CONTRATO DE MUTUO CON GARANTÍA EJECUTIVA) ---
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f }
        canvas.drawLine(margin, 35f, 550f, 35f, linePaint)
        canvas.drawLine(margin, 39f, 550f, 39f, linePaint)
        
        val titlePaint = Paint().apply { 
            isFakeBoldText = true; 
            textSize = 17f; 
            color = Color.BLACK; 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
            letterSpacing = 0.04f
        }
        canvas.drawText("CONTRATO DE MUTUO DE DINERO CON GARANTÍA EJECUTIVA", center, 65f, titlePaint)
        
        val subTitlePaint = Paint().apply { 
            textSize = 9.5f; 
            color = Color.GRAY; 
            textAlign = Paint.Align.CENTER; 
            isAntiAlias = true 
        }
        canvas.drawText("(Artículo 1648° y ss. del Código Civil y D.L. 1529 de Bancarización)", center, 80f, subTitlePaint)
        canvas.drawLine(margin, 90f, 550f, 90f, Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
        
        var y = 115f
        val bodyPaint = Paint().apply { textSize = 10.5f; color = Color.BLACK; isAntiAlias = true }
        val boldPaint = Paint().apply { isFakeBoldText = true; textSize = 10.5f; color = Color.BLACK; isAntiAlias = true }
        
        // --- INTRODUCCIÓN Y PARTES ---
        val mutuanteName = if (!oName.isNullOrEmpty()) oName else if (!bName.isNullOrEmpty()) bName else "EL PRESTAMISTA"
        val mutuanteDni = if (!oDni.isNullOrEmpty()) "N° $oDni" else "registrado en el sistema"
        val mutuanteAddr = if (!oAddress.isNullOrEmpty()) oAddress else "domicilio registrado"

        val introText = "Conste por el presente documento el CONTRATO DE MUTUO DE DINERO CON GARANTÍA EJECUTIVA que celebran de una parte:\n\n" +
                "• EL MUTUANTE (Prestamista): $mutuanteName, identificado con DNI/CE $mutuanteDni, con domicilio en $mutuanteAddr.\n\n" +
                "• EL MUTUATARIO (Deudor): ${client.name}, identificado con DNI/CE N° ${client.doc}, con domicilio en ${client.address ?: "registrado en el sistema"}.\n\n" +
                "Ambas partes declaran hallarse en pleno uso de sus facultades civiles y convienen las siguientes cláusulas:"

        fun drawMultilineText(text: String, x: Float, startY: Float, paint: Paint, width: Float): Float {
            var currentY = startY
            val lines = text.split("\n")
            for (paragraph in lines) {
                if (paragraph.isEmpty()) {
                    currentY += 5f
                    continue
                }
                val words = paragraph.split(" ")
                var line = ""
                for (word in words) {
                    val testLine = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(testLine) > width) {
                        canvas.drawText(line, x, currentY, paint)
                        line = word
                        currentY += paint.textSize + 3.5f
                    } else {
                        line = testLine
                    }
                }
                canvas.drawText(line, x, currentY, paint)
                currentY += paint.textSize + 4f
            }
            return currentY
        }

        y = drawMultilineText(introText, margin, y, bodyPaint, 505f)
        y += 6f

        // --- CLÁUSULAS ---
        val freqLabel = when(loan.frequency) {
            "DAILY" -> "diaria"
            "WEEKLY" -> "semanal"
            "MONTHLY" -> "mensual"
            else -> "de pago único"
        }

        val clauses = mutableListOf(
            "PRIMERA (OBJETO): EL MUTUANTE entrega en calidad de préstamo a EL MUTUATARIO la suma de S/ ${String.format(Locale.US, "%.2f", loan.amount)}.",
            "SEGUNDA (FORMA Y MEDIO DE ENTREGA): La entrega del dinero se hace efectiva mediante transferencia/depósito/efectivo conforme al D.L. 1529, sirviendo la firma y constancia adjunta como comprobante probatorio suficiente.",
            "TERCERA (PLAZO Y DEVOLUCIÓN): EL MUTUATARIO se obliga a devolver la totalidad del capital e intereses acumulados por un total de S/ ${String.format(Locale.US, "%.2f", loan.totalToPay)}. El reembolso se realizará bajo la modalidad $freqLabel en ${loan.installmentsCount} cuota(s) según el cronograma adjunto.",
            "CUARTA (INTERESES Y MORA AUTOMÁTICA): Se acuerda una tasa de interés compensatorio del ${loan.interestRate}%. En caso de incumplimiento o retraso, la mora operará de forma automática sin necesidad de aviso previo, aplicándose una penalidad moratoria diaria de S/ ${String.format(Locale.US, "%.2f", loan.lateFeeAmount)} por día de retraso.",
            "QUINTA (INCUMPLIMIENTO, VENCIMIENTO ANTICIPADO Y TÍTULO EJECUTIVO): El incumplimiento de una sola cuota facultará a EL MUTUANTE a dar por vencidos todos los plazos y exigir el pago inmediato del total adeudado. En respaldo de esta obligación, EL MUTUATARIO suscribe en la fecha un Pagaré a la orden de EL MUTUANTE, el cual posee mérito ejecutivo para embargo y acciones judiciales según ley.",
            "SEXTA (JURISDICCIÓN): Para cualquier controversia, las partes renuncian al fuero de sus domicilios y se someten a la competencia de los Jueces y Tribunales del Distrito Judicial correspondiente."
        )

        if (!loan.collateralDescription.isNullOrEmpty()) {
            clauses.add(5, "CLÁUSULA ADICIONAL DE GARANTÍA: EL MUTUATARIO suscribe e individualiza en prenda/garantía el siguiente bien de respaldo: ${loan.collateralDescription}.")
        }

        for (clause in clauses) {
            y = drawMultilineText(clause, margin, y, bodyPaint, 505f)
            y += 3.5f
        }

        y += 8f
        canvas.drawText("CRONOGRAMA DE PAGOS ADJUNTO:", margin, y, boldPaint)
        y += 14f

        // Table Header
        canvas.drawRect(margin, y, 550f, y + 18f, Paint().apply { color = Color.parseColor("#F1F5F9"); style = Paint.Style.FILL })
        canvas.drawLine(margin, y, 550f, y, linePaint)
        canvas.drawLine(margin, y + 18f, 550f, y + 18f, linePaint)
        canvas.drawText("CUOTA", margin + 10f, y + 13f, boldPaint.apply { textSize = 9.5f })
        canvas.drawText("FECHA DE VENCIMIENTO", 160f, y + 13f, boldPaint)
        canvas.drawText("MONTO A PAGAR (S/)", 430f, y + 13f, boldPaint)
        y += 28f

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        installments.take(8).forEach { inst ->
            val dueText = if (inst.dueDate > 0L) sdf.format(Date(inst.dueDate)) else "Sin fecha límite"
            canvas.drawText("#${inst.installmentNumber}", margin + 10f, y, bodyPaint.apply { textSize = 9.5f })
            canvas.drawText(dueText, 160f, y, bodyPaint)
            canvas.drawText("S/ " + String.format(Locale.US, "%.2f", inst.amount), 430f, y, bodyPaint)
            y += 15f
        }

        // --- FIRMAS Y HUELLAS DACTILARES ---
        y = 745f
        canvas.drawLine(60f, y, 240f, y, linePaint)
        canvas.drawText(mutuanteName, 150f, y + 14f, bodyPaint.apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true })
        canvas.drawText("EL MUTUANTE (Prestamista)", 150f, y + 26f, bodyPaint.apply { isFakeBoldText = false; textSize = 8.5f; color = Color.GRAY })
        canvas.drawText("Huella Digital ☝️", 150f, y + 38f, bodyPaint)

        canvas.drawLine(330f, y, 510f, y, linePaint)
        canvas.drawText(client.name, 420f, y + 14f, bodyPaint.apply { isFakeBoldText = true; textSize = 9.5f; color = Color.BLACK })
        canvas.drawText("EL MUTUATARIO (Deudor)", 420f, y + 26f, bodyPaint.apply { isFakeBoldText = false; textSize = 8.5f; color = Color.GRAY })
        canvas.drawText("Huella Digital ☝️", 420f, y + 38f, bodyPaint)

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
        val boxStrokePaint = Paint().apply { 
            color = Color.parseColor("#E2E8F0"); 
            style = Paint.Style.STROKE; 
            strokeWidth = 2f 
        }
        canvas.drawRoundRect(80f, y, 515f, y + 160f, 25f, 25f, boxStrokePaint)
        
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
            color = Color.parseColor("#EF4444")
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
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
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
        val isCollabMode = prefs.getBoolean("is_collaborator_mode", false)

        val document = PdfDocument()
        val w = 480; val h = 720
        val pageInfo = PdfDocument.PageInfo.Builder(w, h, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint().apply { isAntiAlias = true }
        val center = w / 2f
        
        // --- BACKGROUND ---
        canvas.drawColor(Color.parseColor("#F8FAFC")) // Slate 50

        // --- CABECERA CURVA Y MODERNA ---
        val headerPaint = Paint().apply { color = Color.parseColor("#059669"); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w.toFloat(), 180f, headerPaint)
        
        // Circulo blanco con check
        paint.color = Color.WHITE
        canvas.drawCircle(center, 85f, 42f, paint)
        
        paint.color = Color.parseColor("#059669")
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        val path = Path()
        path.moveTo(center - 14f, 85f)
        path.lineTo(center - 4f, 98f)
        path.lineTo(center + 16f, 74f)
        canvas.drawPath(path, paint)
        
        paint.style = Paint.Style.FILL
        val titlePaint = Paint().apply { 
            isFakeBoldText = true; textSize = 20f; color = Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true; letterSpacing = 0.05f 
        }
        canvas.drawText("PAGO RECIBIDO EXITOSAMENTE", center, 155f, titlePaint)

        // --- TARJETA PRINCIPAL (RECIBO REDISEÑADO) ---
        val cardPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRoundRect(25f, 185f, w - 25f, h - 60f, 28f, 28f, cardPaint)
        
        val shadowPaint = Paint().apply { color = Color.parseColor("#0F000000"); style = Paint.Style.FILL }
        canvas.drawRoundRect(28f, 189f, w - 22f, h - 56f, 28f, 28f, shadowPaint)

        var y = 225f
        val labelPaint = Paint().apply { textSize = 12f; color = Color.parseColor("#64748B"); isAntiAlias = true; isFakeBoldText = true }
        val valPaint = Paint().apply { textSize = 13f; color = Color.parseColor("#0F172A"); isAntiAlias = true; isFakeBoldText = true }
        
        // --- DATOS DEL NEGOCIO / EMISOR ---
        if (showBusiness) {
            val bizTitlePaint = Paint().apply { textSize = 17f; color = Color.parseColor("#059669"); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
            canvas.drawText(bName.uppercase(), center, y, bizTitlePaint)
            y += 35f
        }

        // --- LÍNEA DIVISORIA DE PUNTOS ---
        val dashPaint = Paint().apply { 
            color = Color.parseColor("#E2E8F0"); strokeWidth = 2f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        canvas.drawLine(45f, y, w - 45f, y, dashPaint)
        y += 35f
        
        // --- FUNCIÓN PARA DIBUJAR CLAVE/VALOR CON AJUSTE AUTOMÁTICO DE ANCHO ---
        fun drawReceiptRow(label: String, value: String) {
            canvas.drawText(label, 45f, y, labelPaint)
            val maxWidth = w - 195f
            if (valPaint.measureText(value) > maxWidth) {
                val words = value.split(" ")
                var line1 = ""
                var line2 = ""
                for (word in words) {
                    if (valPaint.measureText("$line1 $word".trim()) <= maxWidth) {
                        line1 = "$line1 $word".trim()
                    } else {
                        line2 = "$line2 $word".trim()
                    }
                }
                canvas.drawText(line1, 180f, y, valPaint)
                if (line2.isNotEmpty()) {
                    y += 20f
                    canvas.drawText(line2, 180f, y, valPaint)
                }
            } else {
                canvas.drawText(value, 180f, y, valPaint)
            }
            y += 32f
        }

        drawReceiptRow("PAGADO POR:", client.name.uppercase())
        drawReceiptRow("N° RECIBO:", UUID.randomUUID().toString().take(8).uppercase())
        drawReceiptRow("FECHA:", SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale.getDefault()).format(Date()))
        
        // CONCEPTO ADAPTATIVO (SI ES PAGO ÚNICO / SIN CUOTAS O CON CUOTAS)
        val isSinglePayment = loan.installmentsCount <= 1 || loan.frequency == "NONE" || installment.installmentNumber <= 0
        val conceptoText = if (isSinglePayment) "ABONO A DEUDA TOTAL" else "CUOTA #${installment.installmentNumber}"
        drawReceiptRow("CONCEPTO:", conceptoText)
        
        y += 20f
        
        // --- CAJA DE MONTO (CREATIVA) ---
        val amountBgPaint = Paint().apply { color = Color.parseColor("#ECFDF5"); style = Paint.Style.FILL }
        canvas.drawRoundRect(55f, y, w - 55f, y + 85f, 20f, 20f, amountBgPaint)
        
        val amountLabelPaint = Paint().apply { textSize = 12f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER; isFakeBoldText = true; letterSpacing = 0.08f }
        canvas.drawText("IMPORTE TOTAL ABONADO", center, y + 28f, amountLabelPaint)
        
        val amountValPaint = Paint().apply { textSize = 38f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        canvas.drawText("S/ ${String.format(Locale.US, "%.2f", paidAmount)}", center, y + 70f, amountValPaint)
        
        y += 125f
        
        // --- SALDO PENDIENTE ADAPTATIVO ---
        val remaining = (installment.amount - installment.amountPaid).coerceAtLeast(0.0)
        if (remaining > 0.01) {
            val remainPaint = Paint().apply { textSize = 13f; color = Color.parseColor("#64748B"); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            val balanceLabel = if (isSinglePayment) "Saldo pendiente del préstamo: S/ ${String.format(Locale.US, "%.2f", remaining)}" else "Saldo pendiente de esta cuota: S/ ${String.format(Locale.US, "%.2f", remaining)}"
            canvas.drawText(balanceLabel, center, y, remainPaint)
        } else {
            val successTextPaint = Paint().apply { isFakeBoldText = true; textSize = 15f; color = Color.parseColor("#059669"); textAlign = Paint.Align.CENTER }
            val cancelLabel = if (isSinglePayment) "¡PRÉSTAMO CANCELADO TOTALMENTE! 🎯" else "¡CUOTA CANCELADA TOTALMENTE! 🎯"
            canvas.drawText(cancelLabel, center, y, successTextPaint)
        }
        
        // --- PIE DE PÁGINA (GRAMÁTICA SINGULAR VS PLURAL) ---
        y = h - 35f
        val thankYouNote = if (isCollabMode) "Gracias por confiar en nuestra gestión profesional." else "Gracias por tu puntualidad y preferencia."
        val footerNotePaint = Paint().apply { 
            textSize = 11f; color = Color.parseColor("#94A3B8"); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        canvas.drawText(thankYouNote, center, y, footerNotePaint)

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
        
        canvas.drawRect(0f, 0f, 595f, 90f, Paint().apply { color = Color.parseColor("#1E293B") })
        
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
        val db = AppDatabase.getDatabase(context)
        val rowPaint = Paint().apply { textSize = 12f; color = Color.DKGRAY }

        runBlocking {
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