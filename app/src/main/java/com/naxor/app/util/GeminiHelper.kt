package com.naxor.app.util

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiHelper {

    // Usando el backend de Google AI (Gemini Developer API) con el modelo recomendado para 2026
    private val model = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-3.6-flash")

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
            val response = model.generateContent(prompt)
            response.text ?: "No se pudo generar el análisis en este momento."
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
            Proporciona un análisis MUY breve (máximo 4 párrafos directos):
            1. Evalúa el riesgo de la cartera basándote en la relación de clientes morosos vs total.
            2. Analiza si los intereses cobrados están cubriendo bien los gastos operativos y tus propios compromisos de pago.
            3. Da una recomendación específica para mejorar la puntualidad de los cobros.
            4. Sugiere una estrategia para reinvertir el capital recuperado considerando lo que debes pagar próximamente.
            
            Usa emojis sutiles (como 📈, ⚖️, ⚠️). Sé directo, profesional y motivador.
        """.trimIndent()

        return@withContext try {
            val response = model.generateContent(prompt)
            response.text ?: "No se pudo generar el análisis de cartera en este momento."
        } catch (e: Exception) {
            "Error al conectar con la IA de GoMezMart: ${e.message}"
        }
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
            val response = model.generateContent(prompt)
            response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
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
        
        return spannable
    }
}
