package com.naxor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naxor.app.databinding.ActivityPdfViewerBinding
import com.naxor.app.databinding.ItemPdfPageBinding
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfFile: File? = null
    private var guestPhone: String? = null
    private var guestName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    }

    private fun sendPdfByWhatsApp() {
        if (pdfFile == null || !pdfFile!!.exists()) return

        val phone = guestPhone ?: ""
        val name = guestName ?: "Cliente"
        val message = "Hola *$name*, te envío tu estado de cuenta actualizado de parte de NEXUR. Adjunto el documento PDF para tu revisión. 😊"

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile!!)
            
            var cleanNumber = phone.replace(" ", "").replace("-", "").replace("+", "")
            if (cleanNumber.length == 9 && !cleanNumber.startsWith("51")) cleanNumber = "51$cleanNumber"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("jid", "$cleanNumber@s.whatsapp.net")
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
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
