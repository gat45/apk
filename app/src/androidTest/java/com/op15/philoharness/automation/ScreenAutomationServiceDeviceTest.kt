package com.op15.philoharness.automation

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Teste ScreenAutomationService EN CONDITIONS REELLES (TODO.md : "Aucun des trois [service
 * d'accessibilite/Telegram/memoire] jamais teste sur device reel -- pas d'AccessibilityService
 * active manuellement dans les parametres Android").
 *
 * **Activation programmatique confirmee IMPOSSIBLE sur ce device/ROM** (teste reellement,
 * pas suppose) : `settings put secure enabled_accessibility_services <composant>`, meme
 * execute en root (`su -c`), n'est jamais persiste -- `settings get` renvoie `null`
 * immediatement apres l'ecriture, dans la MEME session shell. OxygenOS bloque donc
 * l'activation d'un service d'accessibilite hors de l'UI Parametres elle-meme (protection
 * OEM, pas une limite d'Android standard ni un bug de ce code). Consequence directe :
 * ce test ne peut PAS s'auto-activer, et saute (`assumeTrue`, pas un echec rouge permanent
 * en CI) tant que l'utilisateur n'a pas active manuellement "OP15 Assistant" dans
 * Parametres > Accessibilite avant de lancer `connectedDebugAndroidTest`. Une fois active
 * manuellement, ce test verifie reellement le service (pas juste sa compilation).
 */
class ScreenAutomationServiceDeviceTest {

    private fun waitForConnectedInstance(): ScreenAutomationService? {
        repeat(25) {
            ScreenAutomationService.instance?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    @Test
    fun whenManuallyEnabledTheServiceConnectsAndScreenHashIsStableAcrossTwoReadsWithNoActionBetween() {
        val service = waitForConnectedInstance()
        assumeTrue(
            "Service d'accessibilite non active manuellement (Parametres > Accessibilite) -- " +
                "activation programmatique confirmee bloquee par OxygenOS sur ce device, voir commentaire de classe.",
            service != null,
        )
        val hash1 = service!!.screenHash()
        val hash2 = service.screenHash()
        assertEquals("le hash doit etre stable entre deux lectures sans action entre les deux", hash1, hash2)
    }
}
