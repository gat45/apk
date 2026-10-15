package com.op15.philoharness.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Capacite d'automatisation d'ecran (controler d'autres apps via le framework
 * officiel AccessibilityService). Implementation ORIGINALE, ecrite pour ce
 * projet a partir du CONCEPT decrit publiquement par orailnoor/private-agent
 * (README : boucle lecture-ecran -> decision -> action -> observation), sans
 * copier son code source (depot sans fichier LICENSE formel -- le README
 * declare une intention d'ouverture mais ce n'est pas une licence OSI
 * verifiable ; cette classe est donc une reimplementation propre, pas un
 * portage).
 *
 * C'est le outil le plus puissant et le plus risque de tout le harnais :
 * capable d'agir dans N'IMPORTE QUELLE application installee, pas seulement
 * Philo. Volontairement SANS integration automatique au LLM pour l'instant --
 * doit passer par le meme RiskGate/permission que les autres outils avant
 * d'etre jamais appelable depuis un ReasoningDecision.UseTool (non fait ici,
 * cf. TODO.md).
 *
 * Non teste sur device (ecrit en fin de session, budget restant insuffisant
 * pour validation reelle -- cf. DEVICE_TEST_CHECKLIST.md). Un
 * AccessibilityService ne peut PAS etre instancie/teste en JVM pur ni meme
 * facilement en test instrumente standard (necessite que l'utilisateur
 * active manuellement le service dans Parametres > Accessibilite).
 */
class ScreenAutomationService : AccessibilityService() {

    companion object {
        // Reference statique mise a jour par le cycle de vie du service (onServiceConnected/
        // onDestroy) : c'est le pattern standard pour qu'un AccessibilityService -- que le
        // systeme instancie et detruit lui-meme, jamais l'app -- soit atteignable depuis
        // ailleurs (ici, le dispatch d'outils de PhiloReasoningWorker). Null tant que
        // l'utilisateur n'a pas active le service manuellement dans Parametres > Accessibilite
        // -- jamais suppose actif.
        @Volatile var instance: ScreenAutomationService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    data class ScreenElement(
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Pas de reaction automatique aux evenements pour l'instant -- ce service
        // n'agit que sur demande explicite via readScreenTree()/tapAt()/inputText(),
        // jamais en reaction passive a ce que l'utilisateur fait dans une autre app.
    }

    override fun onInterrupt() {}

    /**
     * Aplati l'arbre d'accessibilite de l'ecran actif en une liste d'elements
     * interactifs avec leurs coordonnees -- base pour qu'un agent decide quoi
     * taper/cliquer, sans jamais executer d'action lui-meme a cette etape.
     */
    fun readScreenTree(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        collectElements(root, elements)
        return elements
    }

    private fun collectElements(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>) {
        if (node.isClickable || node.isEditable || node.isScrollable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out.add(
                ScreenElement(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    bounds = bounds,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                ),
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectElements(child, out)
            child.recycle()
        }
    }

    /**
     * Hash stable de l'arbre d'accessibilite actuel -- base de la correction de l'angle
     * mort "noeuds d'accessibilite perimes" (audit externe, TODO.md) : un `screen_read`
     * suivi d'un `screen_tap` sur les memes coordonnees peut agir sur un ecran qui a
     * change entre les deux appels (AccessibilityNodeInfo devient stale si la vue source
     * a change). L'appelant (PhiloReasoningWorker) compare ce hash entre un `screen_read`
     * et le `screen_tap`/`screen_input` suivant, et refuse d'agir s'ils divergent --
     * jamais un tap sur un ecran que le modele n'a plus sous les yeux.
     */
    fun screenHash(): String {
        val elements = readScreenTree()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        elements.forEach { e ->
            digest.update(
                "${e.bounds};${e.className};${e.text};${e.contentDescription};${e.clickable};${e.editable};${e.scrollable}"
                    .toByteArray(),
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Simule un tap physique a des coordonnees ecran via le framework de geste
     * officiel (API 24+, jamais d'injection d'evenement bas niveau). Retourne
     * false si le geste n'a pas pu etre lance (pas d'exception avalee en
     * silence pour un appelant qui doit savoir si l'action a eu lieu).
     */
    fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Saisit du texte dans le noeud editable actuellement focalise (pas de
     * recherche par coordonnees ici -- l'appelant doit d'abord tapAt() sur le
     * champ vise pour lui donner le focus, meme sequence qu'un utilisateur
     * humain).
     */
    fun inputTextInFocusedField(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        node.recycle()
        return result
    }
}
