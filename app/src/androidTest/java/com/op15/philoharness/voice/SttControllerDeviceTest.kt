package com.op15.philoharness.voice

import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.Locale

/**
 * Verifie en conditions reelles ce que TODO.md notait "PAS teste sur device reel" pour la
 * voix : disponibilite reelle de la reconnaissance vocale (systeme et sur l'appareil) sur
 * CE OnePlus 15, pas supposee. N'injecte aucun audio reel (aucun moyen fiable de le faire
 * via adb dans cet environnement) -- se limite a ce qui est verifiable sans parole reelle :
 * la capacite du device elle-meme.
 */
class SttControllerDeviceTest {

    @Test
    fun reportsRealSpeechRecognitionAvailabilityOnThisDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val systemAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceAvailable = if (android.os.Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }
        android.util.Log.i(
            "SttControllerDeviceTest",
            "systemAvailable=$systemAvailable onDeviceAvailable=$onDeviceAvailable " +
                "locale=${Locale.FRENCH.toLanguageTag()} sdkInt=${android.os.Build.VERSION.SDK_INT}",
        )
        // Pas d'assertion sur la valeur elle-meme (ca depend reellement du device/des apps
        // installees, jamais a supposer) -- le but est que l'appel ne plante jamais et que
        // le resultat reel soit journalise pour consultation (logcat), fermant l'angle mort
        // "jamais verifie" sans fabriquer une garantie qui n'existe pas.
    }
}
