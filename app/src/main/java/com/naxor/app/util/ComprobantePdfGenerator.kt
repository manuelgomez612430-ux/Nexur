package com.naxor.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.naxor.app.data.SaleEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ComprobantePdfGenerator(private val context: Context) {

    fun generateComprobantePdf(transactions: List<SaleEntity>): File? {
        if (transactions.isEmpty()) return null
        
        val firstSale = transactions[0]
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        
        val paint = Paint()
        val boldPaint = Paint().apply { isFakeBoldText = true }
        
        val prefs = context.getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val bName = prefs.getString("business_name", "MI NEGOCIO") ?: "MI NEGOCIO"
        val bRuc = prefs.getString("business_ruc", "RUC NO REGISTRADO") ?: "RUC NO REGISTRADO"
        val bAddr = prefs.getString("business_address", "DIRECCION NO REGISTRADA") ?: "DIRECCION NO REGISTRADA"
        val currency = prefs.getString("currency_symbol", "S/") ?: "S/"

        // 1. CABECERA (EMISOR)
        boldPaint.textSize = 20f; boldPaint.color = Color.BLACK
        canvas.drawText(bName.uppercase(), 50f, 60f, boldPaint)
        
        paint.textSize = 10f; paint.color = Color.DKGRAY
        canvas.drawText(bAddr, 50f, 80f, paint)
        canvas.drawText("Teléfono: ${prefs.getString("business_phone", "")}", 50f, 95f, paint)

        // 2. RECUADRO DERECHO (NUMERACION)
        val rectPaint = Paint().apply { 
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
        }
        canvas.drawRect(350f, 30f, 550f, 110f, rectPaint)
        
        boldPaint.textSize = 14f
        val docTitle = when(firstSale.documentType) {
            "BOLETA" -> "BOLETA DE VENTA\nELECTRÓNICA"
            "FACTURA" -> "FACTURA\nELECTRÓNICA"
            else -> "NOTA DE VENTA"
        }
        
        val titleY = if (docTitle.contains("\n")) 65f else 75f
        canvas.drawText("RUC: $bRuc", 370f, 50f, boldPaint)
        
        boldPaint.textSize = 12f
        val lines = docTitle.split("\n")
        var currentY = titleY
        lines.forEach { 
            canvas.drawText(it, 370f, currentY, boldPaint)
            currentY += 15f
        }
        
        boldPaint.textSize = 14f
        val numbering = "${firstSale.series}-${String.format("%06d", firstSale.correlative)}"
        canvas.drawText(numbering, 370f, currentY + 5f, boldPaint)

        // 3. DATOS DEL CLIENTE
        paint.color = Color.BLACK; paint.textSize = 10f
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        canvas.drawText("Fecha de Emisión: ${sdf.format(Date(firstSale.timestamp))}", 50f, 150f, paint)
        canvas.drawText("Cliente: ${firstSale.customerName ?: "ANÓNIMO"}", 50f, 165f, paint)
        canvas.drawText("Documento: ${firstSale.customerDoc ?: "---"}", 50f, 180f, paint)
        canvas.drawText("Dirección: ${firstSale.customerAddress ?: "---"}", 50f, 195f, paint)
        canvas.drawText("Moneda: SOLES", 50f, 210f, paint)

        // 4. TABLA DE PRODUCTOS
        paint.isFakeBoldText = true
        canvas.drawText("Cant.", 50f, 245f, paint)
        canvas.drawText("Descripción", 100f, 245f, paint)
        canvas.drawText("P. Unit", 420f, 245f, paint)
        canvas.drawText("Importe", 500f, 245f, paint)
        canvas.drawLine(50f, 250f, 550f, 250f, paint)
        paint.isFakeBoldText = false
        
        var y = 270f
        for (item in transactions) {
            canvas.drawText(item.cantidad.toString(), 50f, y, paint)
            
            // Texto con salto de linea si es muy largo
            val name = item.nombreProducto
            if (name.length > 45) {
                canvas.drawText(name.take(45), 100f, y, paint)
                y += 15f
                canvas.drawText(name.substring(45), 100f, y, paint)
            } else {
                canvas.drawText(name, 100f, y, paint)
            }
            
            canvas.drawText(String.format("%.2f", item.precioVenta), 420f, y, paint)
            canvas.drawText(String.format("%.2f", item.total), 500f, y, paint)
            y += 20f
            if (y > 700) break // Evitar desborde
        }

        // 5. TOTALES Y MONTO EN LETRAS
        val total = transactions.sumOf { it.total }
        y += 20f
        canvas.drawLine(350f, y, 550f, y, paint)
        y += 20f
        
        if (firstSale.documentType == "FACTURA") {
            val subtotal = total / 1.18
            val igv = total - subtotal
            canvas.drawText("Gravada: $currency", 350f, y, paint); canvas.drawText(String.format("%.2f", subtotal), 500f, y, paint); y += 15f
            canvas.drawText("IGV (18%): $currency", 350f, y, paint); canvas.drawText(String.format("%.2f", igv), 500f, y, paint); y += 15f
        }
        
        boldPaint.textSize = 12f
        canvas.drawText("TOTAL: $currency", 350f, y, boldPaint)
        canvas.drawText(String.format("%.2f", total), 500f, y, boldPaint)
        
        y += 40f
        paint.textSize = 10f; paint.isFakeBoldText = true
        val montoLetras = "SON: ${NumberToLetterConverter.convert(total)} SOLES"
        canvas.drawText(montoLetras, 50f, y, paint)
        
        paint.isFakeBoldText = false
        y += 30f
        if (firstSale.documentType != "NOTA_VENTA") {
            canvas.drawText("Representación impresa de la ${firstSale.documentType} ELECTRÓNICA", 50f, y, paint)
        }

        doc.finishPage(page)
        
        val fileName = "${firstSale.documentType}_${firstSale.series}_${firstSale.correlative}.pdf"
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

object NumberToLetterConverter {
    private val UNIDADES = arrayOf("", "UN ", "DOS ", "TRES ", "CUATRO ", "CINCO ", "SEIS ", "SIETE ", "OCHO ", "NUEVE ")
    private val DECENAS = arrayOf("DIEZ ", "ONCE ", "DOCE ", "TRECE ", "CATORCE ", "QUINCE ", "DIECISEIS ", "DIECISIETE ", "DIECIOCHO ", "DIECINUEVE ")
    private val DECENAS_COMP = arrayOf("", "", "VEINTE ", "TREINTA ", "CUARENTA ", "CINCUENTA ", "SESENTA ", "SETENTA ", "OCHENTA ", "NOVENTA ")
    private val CENTENAS = arrayOf("", "CIENTO ", "DOSCIENTOS ", "TRESCIENTOS ", "CUATROCIENTOS ", "QUINIENTOS ", "SEISCIENTOS ", "SETECIENTOS ", "OCHOCIENTOS ", "NOVECIENTOS ")

    fun convert(amount: Double): String {
        val total = amount.toLong()
        val cents = ((amount - total) * 100).toInt()
        
        var result = ""
        if (total == 0L) result = "CERO "
        else if (total < 10) result = UNIDADES[total.toInt()]
        else if (total < 20) result = DECENAS[total.toInt() - 10]
        else if (total < 100) {
            val d = (total / 10).toInt()
            val u = (total % 10).toInt()
            result = DECENAS_COMP[d] + (if (u > 0) "Y " + UNIDADES[u] else "")
        } else if (total < 1000) {
            val c = (total / 100).toInt()
            val resto = (total % 100).toInt()
            result = if (c == 1 && resto == 0) "CIEN " else CENTENAS[c] + convertBase(resto)
        } else {
            result = total.toString() + " " // Simplificado para montos grandes por ahora
        }
        
        return "${result.trim()} CON ${String.format("%02d", cents)}/100"
    }

    private fun convertBase(n: Int): String {
        if (n == 0) return ""
        if (n < 10) return UNIDADES[n]
        if (n < 20) return DECENAS[n - 10]
        val d = n / 10
        val u = n % 10
        return DECENAS_COMP[d] + (if (u > 0) "Y " + UNIDADES[u] else "")
    }
}
