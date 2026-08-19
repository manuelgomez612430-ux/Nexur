package com.naxor.app.util

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.naxor.app.R
import java.util.Locale

class VoiceRecognitionHelper(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningDialog: AlertDialog? = null
    private var onResult: ((String) -> Unit)? = null

    fun startListening(callback: (String) -> Unit) {
        this.onResult = callback

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "El dictado por voz no está disponible en este dispositivo", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        showListeningDialog()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                updatePulseAnimation(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listeningDialog?.dismiss()
            }
            override fun onError(error: Int) {
                Log.e("VoiceHelper", "Error code: $error")
                listeningDialog?.dismiss()
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(context, "No se escuchó nada, intenta de nuevo", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult?.invoke(matches[0])
                }
                listeningDialog?.dismiss()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun showListeningDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_voice_listening, null)
        listeningDialog = AlertDialog.Builder(context, R.style.Theme_Naxor_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { speechRecognizer?.stopListening() }
            .create()
        
        listeningDialog?.show()
        
        // Animación base
        val pulseView = dialogView.findViewById<View>(R.id.viewPulse)
        pulseView.alpha = 0.3f
    }

    private fun updatePulseAnimation(rmsdB: Float) {
        listeningDialog?.findViewById<View>(R.id.viewPulse)?.let { pulse ->
            val scale = 1.0f + (rmsdB / 10f).coerceAtLeast(0f)
            pulse.scaleX = scale
            pulse.scaleY = scale
            pulse.alpha = (0.3f + (rmsdB / 30f)).coerceIn(0.3f, 0.8f)
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        listeningDialog?.dismiss()
    }
}
