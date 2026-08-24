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
            
            INSTRUCCIONES:
            Proporciona un análisis MUY breve (máximo 4 párrafos cortos) que incluya:
            1. Una evaluación de la rentabilidad basándote en la Utilidad vs el Capital de inversión.
            2. Una felicitación o advertencia según la utilidad neta (utilidad - gastos).
            3. Un dato o idea específica para mejorar las ventas o reducir gastos basado en estos números.
            4. Una recomendación sobre el balance de deudas vs deudores.
            5. Considera los gastos futuros programados si los hubiera.
            
            Usa emojis sutiles. Sé directo y útil.
        """.trimIndent()

        return@withContext try {
            val response = model.generateContent(prompt)
            response.text ?: "No se pudo generar el análisis en este momento."
        } catch (e: Exception) {
            "Error al conectar con la IA: ${e.message}"
        }
    }
}
