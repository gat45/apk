package com.op15.philoharness.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

sealed interface TtsState {
    object Initializing : TtsState
    object Ready : TtsState
    data class Unavailable(val reason: String) : TtsState
}

/**
 * Synthese vocale native (android.speech.tts.TextToSpeech) : aucune dependance
 * externe, fonctionne hors ligne une fois la voix francaise installee par
 * l'utilisateur (Parametres systeme > Langues > Synthese vocale), zero risque
 * de resolution Gradle offline. Lit UNIQUEMENT la conclusion finale -- jamais
 * les phases internes ni les traces d'outils (cf. plan d'integration voix).
 */
class TtsController(context: Context, private val onStateChanged: (TtsState) -> Unit) {

    private var state: TtsState = TtsState.Initializing
    private var engine: TextToSpeech? = null
    private var onSpeakingChanged: ((Boolean) -> Unit)? = null

    init {
        onStateChanged(state)
        engine = TextToSpeech(context.applicationContext) { status ->
            state = if (status != TextToSpeech.SUCCESS) {
                TtsState.Unavailable("Moteur TTS indisponible (status=$status)")
            } else {
                val frenchResult = engine?.setLanguage(Locale.FRENCH)
                when (frenchResult) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                        TtsState.Unavailable("Francais indisponible sur ce moteur TTS (resultat=$frenchResult)")
                    else -> TtsState.Ready
                }
            }
            onStateChanged(state)
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakingChanged?.invoke(true) ?: Unit
            override fun onDone(utteranceId: String?) = onSpeakingChanged?.invoke(false) ?: Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeakingChanged?.invoke(false) ?: Unit
        })
    }

    fun setOnSpeakingChanged(listener: (Boolean) -> Unit) {
        onSpeakingChanged = listener
    }

    /** Ne prononce que du texte destine a l'utilisateur final (conclusion), jamais l'etat interne. */
    fun speak(text: String) {
        if (state != TtsState.Ready || text.isBlank()) return
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "philo_conclusion")
    }

    /** Arret immediat demande par l'utilisateur (bouton Stop). */
    fun stopSpeaking() {
        engine?.stop()
        onSpeakingChanged?.invoke(false)
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
