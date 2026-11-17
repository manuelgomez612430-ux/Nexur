package com.gomezmart.app.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.bumptech.glide.Glide
import android.util.Base64
import java.io.File
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.gomezmart.app.R

object GlideHelper {

    fun getLoadingPlaceholder(context: Context): CircularProgressDrawable {
        return CircularProgressDrawable(context).apply {
            strokeWidth = 6f
            centerRadius = 40f
            setColorSchemeColors(context.getColor(R.color.purple_600))
            start()
        }
    }

    fun getOptions(context: Context, showPlaceholder: Boolean = true): RequestOptions {
        val options = RequestOptions()
            .error(R.drawable.gomezmart_icon)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Guardar tanto la versión comprimida como la original en disco
            .skipMemoryCache(false) // Mantener en memoria RAM para navegación e imágenes instantáneas a 0ms
            .centerCrop()
            .timeout(4000)
        
        return if (showPlaceholder) {
            options.placeholder(getLoadingPlaceholder(context))
        } else {
            options
        }
    }

    /**
     * Carga una imagen con Glide optimizada con soporte completo para:
     * - URLs remotas (HTTP / HTTPS)
     * - Data URIs Base64 ("data:image/jpeg;base64,...")
     * - Cadenas Base64 directas
     * - Archivos locales ("/storage/..." o "file://...")
     */
    fun loadImageWithProgressBar(
        context: Context,
        url: String?,
        imageView: ImageView,
        progressBar: View?,
        errorPlaceholder: Int = R.drawable.gomezmart_icon,
        skipCache: Boolean = false,
        overrideWidth: Int = 1080,
        overrideHeight: Int = 1080,
        onSuccess: (() -> Unit)? = null
    ) {
        if (url.isNullOrEmpty()) {
            progressBar?.visibility = View.GONE
            imageView.setImageResource(errorPlaceholder)
            return
        }

        progressBar?.visibility = View.VISIBLE

        // 1. Manejo de Data URI Base64 o Base64 puro
        val rawBase64 = when {
            url.startsWith("data:image/") && url.contains(",") -> url.substringAfter(",")
            url.length > 500 && !url.startsWith("http") && !url.startsWith("/") -> url
            else -> null
        }

        if (rawBase64 != null) {
            try {
                val imageBytes = Base64.decode(rawBase64, Base64.DEFAULT)
                Glide.with(context)
                    .load(imageBytes)
                    .override(overrideWidth, overrideHeight)
                    .apply(getOptions(context, showPlaceholder = false))
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            progressBar?.visibility = View.GONE
                            imageView.setImageResource(errorPlaceholder)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            progressBar?.visibility = View.GONE
                            onSuccess?.invoke()
                            return false
                        }
                    })
                    .into(imageView)
                return
            } catch (e: Exception) {
                // Continuar con carga estándar si falla decodificación
            }
        }

        // 2. Manejo de archivo local
        val localFile = if (url.startsWith("/") || url.startsWith("file://")) {
            val cleanPath = url.removePrefix("file://")
            val f = File(cleanPath)
            if (f.exists()) f else null
        } else null

        val loadTarget: Any = localFile ?: url

        var request = Glide.with(context)
            .load(loadTarget)
            .thumbnail(
                Glide.with(context)
                    .load(loadTarget)
                    .override(120, 120)
            )
            .override(overrideWidth, overrideHeight)
            .apply(getOptions(context, showPlaceholder = false))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar?.visibility = View.GONE
                    imageView.setImageResource(errorPlaceholder)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar?.visibility = View.GONE
                    onSuccess?.invoke()
                    return false
                }
            })

        if (skipCache) {
            request = request.diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
        }

        request.into(imageView)
    }
}
