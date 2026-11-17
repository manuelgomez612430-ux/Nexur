package com.gomezmart.app.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import java.io.ByteArrayOutputStream
import java.io.File

object SupabaseManager {

    // --- CONFIGURACIÓN DE SUPABASE ---
    private const val SUPABASE_URL = "https://gohjlkcwzduxqntrhlgv.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdvaGpsa2N3emR1eHFudHJobGd2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg3NDUyMzcsImV4cCI6MjEwNDMyMTIzN30.dblTJHjk1uNtaHLMfN_hwNjgrb_zR8vRM6nTpinnWzo"

    // Peso comercial promedio por foto en la nube (~0.80 MB) para que 18-19 fotos llenen los 15 MB gratuitos,
    // mientras que en el servidor de Supabase cada foto comprimida solo ocupa ~45-65 KB reales.
    private const val MIN_COMMERCIAL_PHOTO_BYTES = 820_000L

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(Storage)
    }

    /**
     * Sube una imagen a Supabase Storage comprimiéndola automáticamente para ahorrar hasta 90% de espacio
     * real en el servidor y devuelve la URL pública.
     */
    suspend fun uploadImage(bucket: String, path: String, file: File): String? {
        return try {
            Log.d("SupabaseManager", "Iniciando subida optimizada a bucket: $bucket, path: $path")
            val bucketRef = client.storage.from(bucket)

            val originalBytes = file.length().coerceAtLeast(MIN_COMMERCIAL_PHOTO_BYTES)
            val optimizedBytes = compressImageBytesForServer(file) ?: file.readBytes()

            Log.d(
                "SupabaseManager",
                "Compresión inteligente: Original=${file.length() / 1024}KB -> Servidor=${optimizedBytes.size / 1024}KB"
            )

            bucketRef.upload(path, optimizedBytes) {
                upsert = true
            }

            // Registrar uso comercial de nube si no es simple reemplazo de logo/portada
            val isReplacement = path.startsWith("logos/") || path.startsWith("covers/")
            recordCommercialCloudUsage(
                addedCommercialBytes = if (isReplacement) (originalBytes / 3) else originalBytes,
                addedRealServerBytes = optimizedBytes.size.toLong()
            )

            val url = client.storage.from(bucket).publicUrl(path)
            Log.d("SupabaseManager", "Subida exitosa. URL generada: $url")
            url
        } catch (e: Exception) {
            Log.w("SupabaseManager", "Fallo subida a Supabase (${e.message}), generando Data URI comprimido como respaldo...")
            encodeFileAsCompressedDataUri(file)
        }
    }

    /**
     * Comprime cualquier imagen antes de subirla a Supabase (máx 1080px, ~45-65 KB reales)
     * para que el 1 GB gratuito de Supabase soporte ~20,000 fotos sin perder nitidez en móvil.
     */
    private fun compressImageBytesForServer(file: File): ByteArray? {
        return try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val maxDim = 1080
            val w = original.width
            val h = original.height
            val scaled = if (w > maxDim || h > maxDim) {
                val ratio = w.toFloat() / h.toFloat()
                val (newW, newH) = if (ratio > 1f) {
                    maxDim to (maxDim / ratio).toInt().coerceAtLeast(1)
                } else {
                    (maxDim * ratio).toInt().coerceAtLeast(1) to maxDim
                }
                Bitmap.createScaledBitmap(original, newW, newH, true)
            } else {
                original
            }

            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            var quality = 78
            scaled.compress(format, quality, baos)
            while (baos.size() > 68_000 && quality > 42) {
                baos.reset()
                quality -= 10
                scaled.compress(format, quality, baos)
            }
            baos.toByteArray()
        } catch (e: Exception) {
            Log.w("SupabaseManager", "No se pudo comprimir para servidor, usando bytes originales: ${e.message}")
            null
        }
    }

    private fun recordCommercialCloudUsage(addedCommercialBytes: Long, addedRealServerBytes: Long) {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val db = FirebaseFirestore.getInstance()
            val userRef = db.collection("users").document(uid)
            db.runTransaction { transaction ->
                val snap = transaction.get(userRef)
                val currentCommercial = snap.getLong("cloud_storage_commercial_bytes") ?: 0L
                val currentReal = snap.getLong("cloud_storage_real_bytes") ?: 0L
                val currentPhotos = snap.getLong("cloud_photos_count") ?: 0L
                transaction.set(
                    userRef,
                    mapOf(
                        "cloud_storage_commercial_bytes" to (currentCommercial + addedCommercialBytes),
                        "cloud_storage_real_bytes" to (currentReal + addedRealServerBytes),
                        "cloud_photos_count" to (currentPhotos + 1L),
                        "cloud_storage_updated_at" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            }
        } catch (e: Exception) {
            Log.w("SupabaseManager", "No se pudo actualizar métrica de nube: ${e.message}")
        }
    }

    private fun encodeFileAsCompressedDataUri(file: File): String? {
        return try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val maxDim = 900
            val w = original.width
            val h = original.height
            val scaled = if (w > maxDim || h > maxDim) {
                val ratio = w.toFloat() / h.toFloat()
                val (newW, newH) = if (ratio > 1f) {
                    maxDim to (maxDim / ratio).toInt().coerceAtLeast(1)
                } else {
                    (maxDim * ratio).toInt().coerceAtLeast(1) to maxDim
                }
                Bitmap.createScaledBitmap(original, newW, newH, true)
            } else {
                original
            }
            val baos = ByteArrayOutputStream()
            var quality = 78
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            while (baos.size() > 140_000 && quality > 35) {
                baos.reset()
                quality -= 12
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            }
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (ex: Exception) {
            Log.e("SupabaseManager", "Error al comprimir respaldo Base64: ${ex.message}")
            null
        }
    }

    /**
     * Elimina una lista de archivos en Supabase Storage.
     */
    suspend fun deleteFiles(bucket: String, paths: List<String>): Boolean {
        return try {
            if (paths.isNotEmpty()) {
                client.storage.from(bucket).delete(paths)
                Log.d("SupabaseManager", "Eliminados ${paths.size} archivos en Supabase")
            }
            true
        } catch (e: Exception) {
            Log.e("SupabaseManager", "Error al eliminar archivos en Supabase: ${e.message}")
            false
        }
    }

    /**
     * Elimina un archivo individual en Supabase Storage.
     */
    suspend fun deleteImage(bucket: String, path: String): Boolean {
        return deleteFiles(bucket, listOf(path))
    }
}
