package com.gomezmart.app.util

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

object GeminiHelper {

    /**
     * Modelos de Gemini probados en cascada. Si el primero está saturado, retirado o
     * no disponible, se pasa automáticamente al siguiente hasta conseguir respuesta.
     */
    private val MODEL_CANDIDATES = listOf(
        "gemini-3.6-flash",
        "gemini-3.8-flash",
        "gemini-3.5-flash",
        "gemini-2.5-flash"
    )

    /**
     * Envía un prompt a Gemini probando varios modelos en cascada y con reintentos.
     * Devuelve la respuesta o null si todos fallan.
     */
    private suspend fun askGemini(prompt: String): String? {
        var lastError: String? = null
        for (modelName in MODEL_CANDIDATES) {
            for (attempt in 1..2) {
                try {
                    val genModel = Firebase.ai(backend = GenerativeBackend.googleAI())
                        .generativeModel(modelName)
                    val res = withTimeoutOrNull(20000L) {
                        genModel.generateContent(prompt)
                    }
                    if (res != null) {
                        val text = res.text
                        if (!text.isNullOrBlank()) {
                            Log.i("GeminiHelper", "✅ Respuesta OK con $modelName (intento $attempt)")
                            return text
                        }
                        val finish = res.candidates?.firstOrNull()?.finishReason
                        val block = res.promptFeedback?.blockReason
                        Log.e("GeminiHelper", "⚠️ Respuesta vacía en $modelName | finishReason=$finish | blockReason=$block")
                        // Si fue bloqueado por seguridad, no insistir con el mismo modelo
                        if (finish != null && finish.name.contains("SAFETY", true)) break
                    } else {
                        Log.w("GeminiHelper", "⏳ Timeout en $modelName (intento $attempt)")
                    }
                    delay(1200L)
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e("GeminiHelper", "❌ Error en $modelName (intento $attempt): ${e.message}")
                    delay(1000L)
                }
            }
        }
        Log.e("GeminiHelper", "❌ Todos los modelos fallaron. Último error: $lastError")
        return null
    }

    suspend fun getBusinessAnalysis(
        ventas: Double,
        utilidad: Double,
        gastos: Double,
        deudores: Double,
        deudas: Double,
        capital: Double,
        compromisos: Double,
        periodo: String
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            Actúa como un asesor financiero experto para un pequeño comerciante. 
            Analiza los siguientes datos de su negocio para el periodo: $periodo.
            
            DATOS:
            - Capital Total de Inversión: S/ $capital
            - Ventas Totales: S/ $ventas
            - Utilidad (Ganancia bruta): S/ $utilidad
            - Gastos Operativos: S/ $gastos
            - Dinero por cobrar (Deudores): S/ $deudores
            - Dinero por pagar (Deudas del negocio): S/ $deudas
            - Compromisos de pago futuros: S/ $compromisos
            
            INSTRUCCIONES:
            Proporciona un análisis MUY breve (máximo 4 párrafos cortos) que incluya:
            1. Una evaluación de la rentabilidad basándote en la Utilidad vs el Capital de inversión.
            2. Una felicitación o advertencia según la utilidad neta (utilidad - gastos).
            3. Un dato o idea específica para mejorar las ventas o reducir gastos basado en estos números.
            4. Una recomendación sobre el balance de deudas vs deudores, considerando los compromisos futuros.
            
            Usa emojis sutiles. Sé directo y útil.
        """.trimIndent()

        return@withContext try {
            askGemini(prompt) ?: "No se pudo generar el análisis en este momento."
        } catch (e: Exception) {
            "Error al conectar con la IA de GoMezMart: ${e.message}"
        }
    }

    suspend fun getLoanPortfolioAnalysis(
        totalLent: Double,
        pendingBalance: Double,
        netProfit: Double,
        expenses: Double,
        clientsCount: Int,
        overdueCount: Int,
        futureCommitments: Double
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            Actúa como un experto en gestión de riesgos de cartera de préstamos para un pequeño prestamista independiente.
            Analiza los siguientes datos de su cartera actual:
            
            DATOS DE CARTERA:
            - Capital total prestado (activo): S/ ${String.format("%.2f", totalLent)}
            - Saldo total por cobrar (incluye intereses): S/ ${String.format("%.2f", pendingBalance)}
            - Ganancia neta acumulada (intereses - gastos): S/ ${String.format("%.2f", netProfit)}
            - Gastos operativos registrados: S/ ${String.format("%.2f", expenses)}
            - Cantidad total de clientes: $clientsCount
            - Cantidad de clientes con cuotas morosas: $overdueCount
            - Compromisos de pago futuros (Tus deudas): S/ ${String.format("%.2f", futureCommitments)}
            
            INSTRUCCIONES:
            Da un diagnóstico ultra-breve (máximo 3 viñetas cortas, muy directo y al grano, usando emojis como 📈, ⚖️, ⚠️):
            1. Estado de riesgo y morosidad de la cartera.
            2. Salud de rentabilidad (ganancia neta vs gastos).
            3. Una acción clave recomendada para hoy.
            
            Sé extremadamente conciso y profesional. No escribas introducciones largas.
        """.trimIndent()

        return@withContext try {
            askGemini(prompt) ?: "No se pudo generar el análisis de cartera en este momento."
        } catch (e: Exception) {
            "Error al conectar con la IA de GoMezMart: ${e.message}"
        }
    }

    suspend fun askBusinessAssistant(
        context: android.content.Context,
        totalLent: Double,
        pendingBalance: Double,
        netProfit: Double,
        expenses: Double,
        clientsCount: Int,
        overdueCount: Int,
        futureCommitments: Double,
        clientDetailsContext: String,
        conversationHistory: String,
        userQuestion: String
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            Actúa como el Asesor Financiero IA experto y agente operativo multifuncional de la aplicación GoMezMart.
            Tienes acceso total a la información financiera y de todos los clientes del negocio.
            
            DATOS GENERALES DE LA CARTERA:
            - Capital total prestado: S/ ${String.format(Locale.US, "%.2f", totalLent)}
            - Saldo por cobrar: S/ ${String.format(Locale.US, "%.2f", pendingBalance)}
            - Ganancia neta: S/ ${String.format(Locale.US, "%.2f", netProfit)}
            - Gastos operativos: S/ ${String.format(Locale.US, "%.2f", expenses)}
            - Clientes totales: $clientsCount | Morosos: $overdueCount
            - Compromisos futuros (deudas): S/ ${String.format(Locale.US, "%.2f", futureCommitments)}
            
            DETALLE DE CLIENTES Y DEUDAS ACTUALES:
            $clientDetailsContext
            
            HISTORIAL DE CONVERSACIÓN RECIENTE:
            $conversationHistory
            
            NUEVA PREGUNTA O SOLICITUD DEL USUARIO:
            "$userQuestion"
            
            INSTRUCCIONES:
            1. Usa los datos reales de los clientes (nombres, teléfonos y montos exactos de deuda) cuando te pidan redactar mensajes o consultar información. NUNCA uses corchetes o placeholders como "[Monto]" si tienes el dato exacto arriba.
            2. Si el usuario pide un mensaje para cobrar a un cliente específico, redacta el mensaje de WhatsApp listo con su nombre y monto exacto.
            3. Responde de forma concisa, profesional y directa en español, usando emojis sutiles.
        """.trimIndent()

        return@withContext try {
            // 1) SDK de Firebase AI con cascada de modelos (es el canal que la
            //    API key del proyecto tiene permitido).
            var lastError: String? = null
            for (modelName in MODEL_CANDIDATES) {
                try {
                    val genModel = Firebase.ai(backend = GenerativeBackend.googleAI())
                        .generativeModel(modelName)
                    val res = withTimeoutOrNull(15000L) {
                        genModel.generateContent(prompt).text
                    }
                    if (!res.isNullOrBlank()) {
                        Log.i("GeminiHelper", "✅ Respuesta obtenida con el modelo $modelName")
                        return@withContext res
                    }
                    lastError = "El modelo $modelName no devolvió contenido (posible saturación)."
                    Log.w("GeminiHelper", "⚠️ $lastError")
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e("GeminiHelper", "❌ Error con el modelo $modelName: ${e.message}")
                }
            }

            // 2) Último recurso: llamada HTTP directa autenticada como app Android
            for (modelName in MODEL_CANDIDATES) {
                val direct = GeminiDirectClient.generate(context, modelName, prompt, timeoutMs = 12000)
                if (!direct.isNullOrBlank()) {
                    return@withContext direct
                }
            }

            Log.e("GeminiHelper", "❌ Todos los modelos fallaron. Último error: $lastError")
            fallbackLocalResponse(totalLent, pendingBalance, clientDetailsContext, userQuestion)
        } catch (e: Exception) {
            Log.e("GeminiHelper", "❌ Error general de la IA: ${e.message}")
            fallbackLocalResponse(totalLent, pendingBalance, clientDetailsContext, userQuestion)
        }
    }

    /**
     * Envía un prompt libre a Gemini (cascada de modelos + reintentos + llamada
     * HTTP directa como último recurso). Devuelve null si todos los intentos fallan.
     *
     * Se usa para consultas puntuales que no encajan en los análisis específicos
     * (por ejemplo, el consejo financiero del panel de Hotel).
     */
    suspend fun askRaw(context: android.content.Context, prompt: String): String? {
        val fromSdk = askGemini(prompt)
        if (!fromSdk.isNullOrBlank()) return fromSdk

        for (modelName in MODEL_CANDIDATES) {
            val direct = GeminiDirectClient.generate(context, modelName, prompt, timeoutMs = 12000)
            if (!direct.isNullOrBlank()) return direct
        }
        return null
    }

    private fun fallbackLocalResponse(totalLent: Double, pendingBalance: Double, clientDetails: String, question: String): String {
        return "🤖 **Asesor Financiero (Modo Inteligente Local):**\n\n" +
                "He procesado tu consulta (*\"$question\"*). Debido a la alta demanda temporal en los servidores de la nube, te respondo al instante con la información exacta de tu base de datos:\n\n" +
                "• **Capital prestado total:** S/ ${String.format(Locale.US, "%.2f", totalLent)}\n" +
                "• **Saldo total por cobrar:** S/ ${String.format(Locale.US, "%.2f", pendingBalance)}\n\n" +
                "**📋 Tus clientes y deudas exactas:**\n$clientDetails\n\n" +
                "💡 *Consejo:* Puedes usar el botón **'Enviar WhatsApp de Cobranza'** aquí abajo para contactar de inmediato a cualquiera de tus deudores con su monto exacto."
    }

    /**
     * Genera una propuesta de diseño COMPLETA para la tienda virtual, incluyendo temas, colores y secciones.
     * Ahora también propone mejoras para los campos principales del negocio.
     */
    suspend fun generateStoreDesignProposal(
        name: String,
        category: String,
        description: String,
        owner: String
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            Actúa como un Director Creativo de una agencia de diseño de interfaces (UI/UX) y Copywriter Senior.
            Tu misión es RE-DISEÑAR totalmente la identidad digital de este negocio en la app GoMezMart:
            
            DATOS ACTUALES:
            - Marca: $name
            - Rubro: $category
            - Descripción: $description
            - Fundador: $owner
            
            TAREA:
            Genera una propuesta de diseño integral en formato JSON. Debes mejorar los textos actuales y proponer un estilo visual.
            
            REGLAS DE ESTILO (Theming):
            1. primaryColor: Color hex que represente el rubro (ej: Verde esmeralda para naturaleza, Dorado/Azul para lujo).
            2. textColor: Color para los textos (debe ser muy legible sobre el fondo).
            3. backgroundColor: Color de fondo de la página.
            4. fontStyle: "MODERN", "CLASSIC", o "BOLD".
            5. decorations: "CIRCLES", "LINES", o "MIXED".
            
            MEJORA DE INFORMACIÓN PRINCIPAL:
            - improvedName: Optimiza el nombre comercial si es necesario para que suene más profesional.
            - improvedDescription: Reescribe la descripción para que sea mucho más vendedora, profesional y elegante.
            
            REGLAS DE CONTENIDO (Secciones Extra):
            Genera 3 secciones estratégicas nuevas con títulos potentes y descripciones persuasivas.
            
            FORMATO DE RESPUESTA (JSON PURO):
            {
              "businessInfo": {
                "name": "...",
                "description": "..."
              },
              "theme": {
                "primaryColor": "#HEX",
                "textColor": "#HEX",
                "backgroundColor": "#HEX",
                "fontStyle": "MODERN|CLASSIC|BOLD",
                "decorations": "CIRCLES|LINES|MIXED"
              },
              "sections": [
                {"title": "...", "description": "..."},
                {"title": "...", "description": "..."},
                {"title": "...", "description": "..."}
              ]
            }
            
            CRÍTICO: Responde SOLO con el JSON. Sin markdown, sin explicaciones.
        """.trimIndent()

        return@withContext try {
            askGemini(prompt)?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * Convierte el formato Markdown simple (**texto**) a texto enriquecido para Android
     * También limpia otros símbolos comunes de la IA
     */
    fun formatMarkdown(text: String): android.text.SpannableStringBuilder {
        // Limpiar símbolos extraños que a veces envía la IA
        val cleanText = text.replace("^^", "")
        
        val spannable = android.text.SpannableStringBuilder(cleanText)
        
        // 1. Manejar Negritas **texto**
        val pattern = java.util.regex.Pattern.compile("\\*\\*(.*?)\\*\\*")
        var matcher = pattern.matcher(spannable)
        
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val content = matcher.group(1) ?: ""
            
            spannable.replace(start, end, content)
            spannable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                start + content.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // Reiniciar el matcher porque la longitud del texto cambió
            matcher = pattern.matcher(spannable)
        }
        
        // 2. Manejar Viñetas (Líneas que empiezan con *)
        val bulletPattern = java.util.regex.Pattern.compile("(?m)^\\* (.*)")
        var bulletMatcher = bulletPattern.matcher(spannable)
        while (bulletMatcher.find()) {
            val start = bulletMatcher.start()
            val content = bulletMatcher.group(1) ?: ""

            // Reemplazar "* " por "• "
            spannable.replace(start, start + 2, "• ")
            spannable.setSpan(
                android.text.style.BulletSpan(20),
                start,
                start + content.length + 2,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            bulletMatcher = bulletPattern.matcher(spannable)
        }

        // 3. Encabezados Markdown (### Título  /  ## Título  /  # Título) -> negrita
        val headingPattern = java.util.regex.Pattern.compile("(?m)^#{1,6}\\s*(.*)$")
        var headingMatcher = headingPattern.matcher(spannable)
        while (headingMatcher.find()) {
            val start = headingMatcher.start()
            val end = headingMatcher.end()
            val content = headingMatcher.group(1).orEmpty().trim()

            spannable.replace(start, end, content)
            spannable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                start + content.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(1.06f),
                start,
                start + content.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            headingMatcher = headingPattern.matcher(spannable)
        }

        // 4. Cursivas *texto* (que no sean viñetas ni negritas)
        val italicPattern = java.util.regex.Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
        var italicMatcher = italicPattern.matcher(spannable)
        while (italicMatcher.find()) {
            val start = italicMatcher.start()
            val end = italicMatcher.end()
            val content = italicMatcher.group(1) ?: ""

            spannable.replace(start, end, content)
            spannable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                start,
                start + content.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            italicMatcher = italicPattern.matcher(spannable)
        }

        // 5. Reglas horizontales "---" -> separador visual
        val rulePattern = java.util.regex.Pattern.compile("(?m)^-{3,}\\s*$")
        var ruleMatcher = rulePattern.matcher(spannable)
        while (ruleMatcher.find()) {
            val start = ruleMatcher.start()
            val end = ruleMatcher.end()
            spannable.replace(start, end, "\n")
            ruleMatcher = rulePattern.matcher(spannable)
        }

        return spannable
    }
}
