package com.naxor.app.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import android.util.Log
import java.io.File

object SupabaseManager {

    // --- CONFIGURACIÓN DE SUPABASE ---
    private const val SUPABASE_URL = "https://gohjlkcwzduxqntrhlgv.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdvaGpsa2N3emR1eHFudHJobGd2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg3NDUyMzcsImV4cCI6MjEwNDMyMTIzN30.dblTJHjk1uNtaHLMfN_hwNjgrb_zR8vRM6nTpinnWzo"

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(Storage)
    }

    /**
     * Sube una imagen a Supabase Storage y devuelve la URL pública.
     */
    suspend fun uploadImage(bucket: String, path: String, file: File): String? {
        return try {
            Log.d("SupabaseManager", "Iniciando subida a bucket: $bucket, path: $path")
            val bucketRef = client.storage.from(bucket)
            
            // Subir el archivo
            bucketRef.upload(path, file.readBytes()) {
                upsert = true
            }
            
            val url = client.storage.from(bucket).publicUrl(path)
            Log.d("SupabaseManager", "Subida exitosa. URL generada: $url")
            url
        } catch (e: Exception) {
            Log.e("SupabaseManager", "Error crítico en Supabase: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
