package com.gomezmart.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gomezmart.app.databinding.ActivityPdfViewerBinding
import com.gomezmart.app.databinding.ItemPdfPageBinding
import java.io.File

class PdfViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfFile: File? = null
    private var guestPhone: String? = null
    private var guestName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets de pantalla (Top para barra de estado/cámara y Bottom para navegación)
        applyWindowInsets(binding.appBarPdf, applyTop = true, applyBottom = false)
        applyWindowInsetsMargins(binding.cardSendActions, applyTop = false, applyBottom = true)

        val filePath = intent.getStringExtra("PDF_PATH") ?: return finish()
        pdfFile = File(filePath)
        guestPhone = intent.getStringExtra("GUEST_PHONE")
        guestName = intent.getStringExtra("GUEST_NAME")

        setupToolbar()
        setupRecyclerView()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbarPdf.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        if (pdfFile == null || !pdfFile!!.exists()) return

        val bitmaps = mutableListOf<Bitmap>()
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.add(bitmap)
                page.close()
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al renderizar PDF", Toast.LENGTH_SHORT).show()
        }

        binding.rvPdfPages.layoutManager = LinearLayoutManager(this)
        binding.rvPdfPages.adapter = PdfPagesAdapter(bitmaps)
    }

    private fun setupListeners() {
        binding.btnSendWhatsApp.setOnClickListener {
            sendPdfByWhatsApp()
        }
        
        val isContract = pdfFile?.name?.contains("Contrato") == true
        if (isContract) {
            binding.btnPrintPdf.visibility = android.view.View.VISIBLE
            binding.btnPrintPdf.setOnClickListener { printPdf() }
        }
    }

    private fun printPdf() {
        pdfFile?.let { file ->
            val printManager = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val jobName = "${getString(R.string.app_name)} Document"
            
            printManager.print(jobName, object : android.print.PrintDocumentAdapter() {
                override fun onLayout(oldAttributes: android.print.PrintAttributes?, newAttributes: android.print.PrintAttributes, cancellationSignal: android.os.CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val builder = android.print.PrintDocumentInfo.Builder(file.name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    callback.onLayoutFinished(builder.build(), true)
                }

                override fun onWrite(pages: Array<out android.print.PageRange>?, destination: android.os.ParcelFileDescriptor, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback) {
                    try {
                        val input = java.io.FileInputStream(file)
                        val output = java.io.FileOutputStream(destination.fileDescriptor)
                        input.copyTo(output)
                        callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                        input.close()
                        output.close()
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        }
    }

    private fun sendPdfByWhatsApp() {
        if (pdfFile == null || !pdfFile!!.exists()) return

        val phone = guestPhone ?: ""
        val name = guestName ?: "Cliente"
        val message = "Hola *$name*, te envío el documento PDF de parte de GOMEZMART. 😊"

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile!!)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (phone.isNotEmpty()) {
                var cleanNumber = phone.replace(" ", "").replace("-", "").replace("+", "")
                if (cleanNumber.length == 9 && !cleanNumber.startsWith("51")) cleanNumber = "51$cleanNumber"
                intent.setPackage("com.whatsapp")
                intent.putExtra("jid", "$cleanNumber@s.whatsapp.net")
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, "Enviar por..."))
            }
        } catch (e: Exception) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile!!)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar por..."))
        }
    }

    class PdfPagesAdapter(private val bitmaps: List<Bitmap>) : RecyclerView.Adapter<PdfPagesAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.binding.ivPdfPage.setImageBitmap(bitmaps[position])
        }
        override fun getItemCount() = bitmaps.size
    }
}
