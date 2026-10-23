package com.gomezmart.app.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.bumptech.glide.Glide
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
            .timeout(8000)
        
        return if (showPlaceholder) {
            options.placeholder(getLoadingPlaceholder(context))
        } else {
            options
        }
    }

    /**
     * Carga una imagen con Glide y maneja automáticamente la visibilidad de un ProgressBar.
     */
    fun loadImageWithProgressBar(
        context: Context,
        url: String?,
        imageView: ImageView,
        progressBar: View?,
        errorPlaceholder: Int = R.drawable.gomezmart_icon,
        skipCache: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        if (url.isNullOrEmpty()) {
            progressBar?.visibility = View.GONE
            imageView.setImageResource(errorPlaceholder)
            return
        }

        progressBar?.visibility = View.VISIBLE
        
        var request = Glide.with(context)
            .load(url)
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
