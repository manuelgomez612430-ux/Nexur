package com.naxor.app.util

import android.content.Context
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

    fun generateLoanContract(client: LoanClientEntity, loan: LoanEntity, installments: List<LoanInstallmentEntity>): File? {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        val titlePaint = Paint().apply { isFakeBoldText = true; textSize = 24f; color = Color.BLACK }
        val boldPaint = Paint().apply { isFakeBoldText = true; textSize = 14f; color = Color.BLACK }
        
        var y = 100f
        canvas.drawText("CONTRATO DE PRÉSTAMO - NAXOR", 100f, y, titlePaint)
        
        y += 60f
        canvas.drawText("Datos del Cliente:", 50f, y, boldPaint)
        y += 25f
        paint.textSize = 12f
        canvas.drawText("Nombre: ${client.name}", 70f, y, paint)
        y += 20f
        canvas.drawText("DNI: ${client.doc}", 70f, y, paint)
        
        y += 50f
        canvas.drawText("Detalles del Crédito:", 50f, y, boldPaint)
        y += 25f
        canvas.drawText("Capital Prestado: S/ ${String.format(Locale.US, "%.2f", loan.amount)}", 70f, y, paint)
        y += 20f
        canvas.drawText("Tasa de Interés: ${loan.interestRate}%", 70f, y, paint)
        y += 20f
        canvas.drawText("Total a Pagar: S/ ${String.format(Locale.US, "%.2f", loan.totalToPay)}", 70f, y, paint)
        y += 20f
        canvas.drawText("Frecuencia: ${loan.frequency}", 70f, y, paint)
        
        y += 60f
        canvas.drawText("Cronograma de Pagos:", 50f, y, boldPaint)
        y += 30f
        
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        installments.forEach { inst ->
            if (y > 780f) { // Simple control de página
                // Podriamos crear mas paginas aqui
            } else {
                canvas.drawText("Cuota #${inst.installmentNumber} | Vence: ${sdf.format(Date(inst.dueDate))} | Monto: S/ ${String.format(Locale.US, "%.2f", inst.amount)}", 70f, y, paint)
                y += 20f
            }
        }
        
        y += 100f
        canvas.drawRect(100f, y, 250f, y + 2f, paint)
        canvas.drawText("Firma del Cliente", 130f, y + 20f, paint)
        
        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Contrato_${client.name.replace(" ", "_")}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }

    fun generatePaymentReceipt(client: LoanClientEntity, loan: LoanEntity, installment: LoanInstallmentEntity, paidAmount: Double): File? {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(300, 500, 1).create() // Tamaño ticket
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        val titlePaint = Paint().apply { isFakeBoldText = true; textSize = 18f; color = Color.BLACK; textAlign = Paint.Align.CENTER }
        
        var y = 50f
        canvas.drawText("RECIBO DE PAGO", 150f, y, titlePaint)
        y += 40f
        paint.textSize = 12f
        canvas.drawText("Cliente: ${client.name}", 20f, y, paint)
        y += 20f
        canvas.drawText("Préstamo ID: #${loan.id.take(8).uppercase()}", 20f, y, paint)
        y += 40f
        paint.isFakeBoldText = true
        canvas.drawText("DETALLE DE ABONO", 20f, y, paint)
        y += 20f
        paint.isFakeBoldText = false
        canvas.drawText("Cuota #: ${installment.installmentNumber}", 20f, y, paint)
        y += 20f
        canvas.drawText("Monto Pagado: S/ ${String.format(Locale.US, "%.2f", paidAmount)}", 20f, y, paint)
        y += 20f
        val remaining = installment.amount - installment.amountPaid
        canvas.drawText("Saldo Cuota: S/ ${String.format(Locale.US, "%.2f", remaining)}", 20f, y, paint)
        
        y += 40f
        canvas.drawText("Fecha: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}", 20f, y, paint)
        
        y += 60f
        canvas.drawText("¡Gracias por su puntualidad!", 150f, y, titlePaint.apply { textSize = 12f })
        
        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Recibo_${installment.id.take(5)}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }

    fun generateDailyCollectionReport(installments: List<com.naxor.app.data.LoanInstallmentEntity>): File? {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        val titlePaint = Paint().apply { isFakeBoldText = true; textSize = 22f; color = Color.BLACK }
        
        var y = 60f
        canvas.drawText("REPORTE DE COBRANZA DIARIA", 50f, y, titlePaint)
        y += 30f
        paint.textSize = 14f
        canvas.drawText("Fecha: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())}", 50f, y, paint)
        
        y += 50f
        canvas.drawRect(50f, y, 545f, y + 2f, paint)
        y += 30f
        
        canvas.drawText("CUOTA", 50f, y, paint)
        canvas.drawText("MONTO", 450f, y, paint)
        y += 25f
        
        var total = 0.0
        val db = com.naxor.app.data.AppDatabase.getDatabase(context)
        
        // Usamos runBlocking para no complicar el generador de PDF síncrono
        kotlinx.coroutines.runBlocking {
            installments.forEach { inst ->
                val loan = db.loanDao().getLoanById(inst.loanId)
                val client = loan?.let { db.loanDao().getClientById(it.clientId) }
                
                canvas.drawText("${client?.name ?: "?"} (Cuota #${inst.installmentNumber})", 50f, y, paint)
                canvas.drawText("S/ ${String.format(Locale.US, "%.2f", inst.amount)}", 450f, y, paint)
                total += inst.amount
                y += 20f
                
                if (y > 780f) {
                    // Control simple de página
                }
            }
        }
        
        y += 40f
        canvas.drawRect(50f, y, 545f, y + 2f, paint)
        y += 30f
        paint.isFakeBoldText = true
        canvas.drawText("TOTAL ESTIMADO DEL DÍA:", 50f, y, paint)
        canvas.drawText("S/ ${String.format(Locale.US, "%.2f", total)}", 450f, y, paint)
        
        document.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "Reporte_Cobros_${System.currentTimeMillis()}.pdf")
        document.writeTo(file.outputStream())
        document.close()
        return file
    }
}
