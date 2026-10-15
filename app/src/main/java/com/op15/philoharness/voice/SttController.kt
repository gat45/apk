package com.op15.philoharness.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

sealed interface SttResult {
    data class Recognized(val text: String) : SttResult
    data class Error(val message: String) : SttResult
    object Cancelled : SttResult
    object ListeningStarted : SttResult
    object ListeningEnded : SttResult
}

/**
 * Reconnaissance vocale native (android.speech.SpeechRecognizer) : aucune
 * dependance externe. Prefere explicitement la reconnaissance embarquee
 * (createOnDeviceSpeechRecognizer, API 31+) quand disponible plutot que de
 * supposer un mode hors-ligne -- le SpeechRecognizer par defaut peut exiger
 * un reseau selon le moteur installe (cf. plan d'integration voix, point de
 * vigilance explicite sur isOnDeviceRecognitionAvailable()).
 */
class SttController(private val context: Context, private val onResult: (SttResult) -> Unit) {

    private var recognizer: SpeechRecognizer? = null

    // Code 12 (ERROR_LANGUAGE_NOT_SUPPORTED) observe en conditions reelles : le moteur
    // embarque (createOnDeviceSpeechRecognizer) ne supporte pas forcement le francais meme
    // quand isOnDeviceRecognitionAvailable() repond true pour l'anglais -- la disponibilite
    // on-device est par langue, pas globale. On retente une seule fois avec le moteur
    // standard (reseau) avant d'abandonner, au lieu de remonter une erreur opaque.
    private var alreadyFellBackFromOnDevice = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isAvailable()) {
            onResult(SttResult.Error("Reconnaissance vocale indisponible sur cet appareil"))
            return
        }
        alreadyFellBackFromOnDevice = false
        startListeningInternal(preferOnDevice = true)
    }

    private fun startListeningInternal(preferOnDevice: Boolean) {
        val useOnDevice = preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        recognizer?.destroy()
        recognizer = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onResult(SttResult.ListeningStarted)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onResult(SttResult.ListeningEnded)

            override fun onError(error: Int) {
                val languageUnsupported = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (languageUnsupported && useOnDevice && !alreadyFellBackFromOnDevice) {
                    alreadyFellBackFromOnDevice = true
                    startListeningInternal(preferOnDevice = false)
                    return
                }
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Rien de compris, reessaie"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune parole detectee"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission micro refusee"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Reseau indisponible (moteur en ligne requis)"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance deja en cours"
                    SpeechRecognizer.ERROR_CLIENT -> "Annule"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Francais non supporte par le moteur de reconnaissance"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Francais temporairement indisponible sur ce moteur"
                    else -> "Erreur de reconnaissance (code=$error)"
                }
                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    onResult(SttResult.Cancelled)
                } else {
                    onResult(SttResult.Error(message))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    onResult(SttResult.Error("Rien de compris, reessaie"))
                } else {
                    onResult(SttResult.Recognized(text))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }


    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
