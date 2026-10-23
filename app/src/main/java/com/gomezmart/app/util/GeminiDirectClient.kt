package com.gomezmart.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cliente directo de la API de Gemini (Google AI / generativelanguage).
 *
 * El SDK de Firebase AI exige un token de "App Check" que, si falla, deja la
 * petición colgada hasta expirar. Este cliente realiza la llamada HTTP directamente
 * autenticándose como aplicación Android (API key + paquete + certificado SHA-1),
 * que es el mecanismo que acepta la clave de Firebase del proyecto.
 */
object GeminiDirectClient {

    private const val TAG = "GeminiDirect"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    private var cachedApiKey: String? = null
    private var cachedCert: String? = null

    private fun apiKey(): String? {
        if (cachedApiKey != null) return cachedApiKey
        return try {
            FirebaseApp.getInstance().options.apiKey?.also { cachedApiKey = it }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo obtener la API key: ${e.message}")
            null
        }
    }

    /** SHA-1 del certificado de firma de la app, en formato con dos puntos. */
    private fun signingCert(context: Context): String? {
        if (cachedCert != null) return cachedCert
        return try {
            val pm = context.packageManager
            val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: SigningInfo es la vía moderna de obtener el firmante.
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                // API 24-27: se usa la vía heredada (deprecada en API 28).
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()
            }
            if (signer == null) return null
            val digest = MessageDigest.getInstance("SHA-1").apply { update(signer.toByteArray()) }
            digest.digest().joinToString(":") { String.format("%02X", it) }.also { cachedCert = it }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo obtener el certificado: ${e.message}")
            null
        }
    }

    /**
     * Envía un prompt a Gemini y devuelve el texto de la respuesta, o null si falla.
     */
    suspend fun generate(
        context: Context,
        model: String,
        prompt: String,
        timeoutMs: Int = 25000
    ): String? = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext null
        val cert = signingCert(context) ?: return@withContext null
        val pkg = context.packageName

        var connection: HttpURLConnection? = null
        try {
            val url = URL(BASE_URL + model + ":generateContent")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
                setRequestProperty("X-Android-Package", pkg)
                setRequestProperty("X-Android-Cert", cert)
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.7)
                        .put("maxOutputTokens", 2048)
                )
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.e(TAG, "❌ HTTP $code en $model: ${err.take(400)}")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                Log.e(TAG, "⚠️ Respuesta sin candidatos en $model: ${response.take(300)}")
                return@withContext null
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val text = if (parts != null && parts.length() > 0) {
                parts.getJSONObject(0).optString("text", "")
            } else ""

            if (text.isBlank()) {
                Log.e(TAG, "⚠️ Texto vacío en $model")
                null
            } else {
                Log.i(TAG, "✅ Respuesta OK con $model")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error de red con $model: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
