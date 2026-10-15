package com.op15.philoharness.hardware

import com.op15.philoharness.resource.AcceleratorValidator
import com.op15.philoharness.resource.Backend
import com.op15.philoharness.resource.BackendValidation
import com.op15.philoharness.resource.DeviceProfile
import com.op15.philoharness.resource.ResourceManager
import com.op15.philoharness.resource.ThermalStatus
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Demarre/arrete `llama-server` sur le device -- comble le dernier manque identifie
 * ("Gestion du serveur LLM depuis l'app", TODO.md). Capacite d'ECRITURE delibrement
 * ISOLEE de RootHelper (qui reste 100% lecture seule, allowlist stricte) : deux commandes
 * FIXES seulement (demarrer avec un modele precis, arreter), jamais une commande arbitraire
 * -- pas un executeur shell generique. Appele UNIQUEMENT depuis une action UI explicite de
 * l'utilisateur (bouton + boite de confirmation dans DiagnosticsActivity), jamais depuis le
 * LLM/l'agent -- a l'oppose de GatedToolLoop/RiskGate (qui gatent des actions PROPOSEES par
 * le modele), ici l'utilisateur est deja la validation humaine en tapant le bouton.
 *
 * Params de lancement repris de la session de test reelle du 2026-10-14 (-dev HTP0, skel
 * dans /data/local/tmp/npu, GGML_HEXAGON_MBUF/NDEV) -- deja valides par un vrai pipeline
 * saisie->LLM->Z3 ce soir.
 */
object LlmServerController {

    private const val NPU_DIR = "/data/local/tmp/npu"
    private const val LOG_FILE = "$NPU_DIR/server_philoharness.log"
    private const val PORT = 8080

    private val lock = ReentrantLock()

    /**
     * `ResourceManager` cable au vrai lancement (2026-10-15, angle mort corrige -- TODO.md,
     * zero reference avant ce correctif, `-dev HTP0` etait fige en dur sans jamais consulter
     * le systeme concu precisement pour eviter "un backend qui repond silencieusement faux"
     * -- cas KuRue/bram cite dans AcceleratorValidator.kt).
     *
     * PAS de pipeline de validation EN DIRECT ici : `AcceleratorValidator.validate()` a besoin
     * de `ReferenceTask` qui comparent des logits CPU-vs-backend sur de vrais prompts -- cette
     * app n'a pas cette capacite (ferait tourner llama-server deux fois par requete, hors de
     * portee pour une simple correction de cablage). A la place, le resultat HEXAGON_NPU est
     * PRE-ENREGISTRE UNE SEULE FOIS depuis les mesures reelles de la campagne de bench de cette
     * session (`bench_results/RAPPORT_AB_FROID_CAPSMAX_20260906.md` -- voie mempool, bugs 3+4
     * corriges, coherence byte-identique vs CPU confirmee sur 3 prompts, pp16 250 tok/s) --
     * jamais fabrique. **Limite honnete** (signalee aussi dans TODO.md, cf. critique externe
     * point 12 : "un backend valide pour un modele/format n'est pas automatiquement valide
     * pour tous les autres") : cette validation est liee a CETTE config precise (mempool,
     * commit `1b5384d7`) -- pas une preuve generale pour un futur modele/quantization/build.
     * A invalider manuellement (retirer `recordValidation` ci-dessous) si la config change.
     */
    private val resourceManager = ResourceManager(AcceleratorValidator()).apply {
        recordValidation(Backend.HEXAGON_NPU, BackendValidation.Validated(agreementRatio = 1.0, speedupVsCpu = 1.0))
    }

    /**
     * Profil mesure, pas suppose. Thermique : signal REEL plutot qu'un seuil de temperature
     * invente (piege deja documente dans la memoire du projet, "pieges thermal trip-point
     * constants") -- `HardwareTelemetry.cpuThermalLimits()` ne liste une entree QUE quand le
     * systeme LMh (Limits Management Hardware) limite DEJA activement la frequence d'un coeur :
     * un signal binaire mais reellement observe par l'OS, jamais devine. Simplification
     * assumee : NOMINAL/SEVERE seulement (pas les 5 niveaux de ThermalStatus), faute de seuils
     * verifies pour les paliers intermediaires sur ce device precis.
     */
    private fun currentDeviceProfile(): DeviceProfile? {
        val ram = HardwareTelemetry.ramInfo() ?: return null
        val throttled = HardwareTelemetry.cpuThermalLimits().isNotEmpty()
        return DeviceProfile(
            totalRamBytes = ram.totalMb * 1_000_000L,
            availableRamBytes = ram.availableMb * 1_000_000L,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            vulkanSupported = false, // ce build llama-server/ggml-hexagon n'expose aucun backend Vulkan -- jamais une option reelle ici
            hexagonNpuPresent = true, // confirme par la campagne de bench reelle de cette session, pas suppose
            thermalStatus = if (throttled) ThermalStatus.SEVERE else ThermalStatus.NOMINAL,
        )
    }

    // SANS -f (bug reel trouve en testant sur device, 2026-10-14) : `pgrep -f llama-server`
    // matche sur la ligne de commande COMPLETE de chaque process -- y compris le process
    // `su -c "...setsid ./llama-server ..."` que start() vient lui-meme de lancer, dont le
    // cmdline CONTIENT litteralement "llama-server". Resultat observe : isRunning() repondait
    // "deja en cours d'execution" alors qu'aucun llama-server ne tournait reellement (le
    // process su -c encore vivant quelques secondes apres le lancement se faisait passer pour
    // le serveur lui-meme). Meme piege deja documente et evite pour pkill() plus bas -- oublie
    // ici a la premiere version. `pgrep` SANS `-f` matche seulement le nom du process
    // (~15 car.), jamais l'invocateur.
    fun isRunning(): Boolean =
        RootHelper.suCmdReadOnly("pgrep llama-server").trim().isNotBlank()

    /** Lance `llama-server` avec le modele donne. Retourne un message d'etat, jamais d'exception. */
    fun start(modelPath: String): String = lock.withLock {
        if (!RootHelper.isRooted()) return@withLock "Root indisponible."
        if (isRunning()) return@withLock "Deja en cours d'execution."

        val profile = currentDeviceProfile()
        val plan = profile?.let { resourceManager.planFor(it) }
        // Repli de securite explicite si le profil n'a pas pu etre mesure (ex. ramInfo()
        // renvoie null) : NE JAMAIS bloquer le lancement pour cette raison seule, mais ne pas
        // pretendre non plus avoir consulte un plan -- comportement identique a avant ce
        // correctif (-dev HTP0 fixe), pas une regression silencieuse.
        val devFlag = when (plan?.chosenBackend) {
            null, Backend.HEXAGON_NPU -> "-dev HTP0"
            Backend.VULKAN_GPU -> "-dev HTP0" // jamais une vraie option sur ce build (vulkanSupported=false dans le profil) -- filet de securite seul
            Backend.CPU -> null // pas de -dev : CPU pur, llama-server utilise son chemin par defaut
        }
        if (plan?.degraded == true) {
            android.util.Log.w("LlmServerController", "Plan d'execution degrade : ${plan.reason}")
        }
        // "-ngl" (nombre de couches a deporter) n'a de sens qu'avec un backend accelere --
        // jamais envoye en repli CPU, incertain (et non verifiable sans device la) que ce
        // build de llama-server le gere proprement sans "-dev" associe.
        val args = listOfNotNull(devFlag?.let { "-ngl 99" }, devFlag, "-t 8").joinToString(" ")
        val cmd = "cd $NPU_DIR && " +
            "ADSP_LIBRARY_PATH=$NPU_DIR LD_LIBRARY_PATH=$NPU_DIR " +
            "GGML_HEXAGON_MBUF=3500 GGML_HEXAGON_NDEV=1 " +
            "setsid ./llama-server -m ${shQuote(modelPath)} --host 127.0.0.1 --port $PORT " +
            "$args < /dev/null > $LOG_FILE 2>&1 &"
        suWrite(cmd)
        buildString {
            append("Lancement demande (modele : ${modelPath.substringAfterLast('/')}")
            if (plan?.degraded == true) append(", mode degrade : ${plan.reason}")
            append("). Verifie l'etat du serveur ci-dessus apres quelques secondes (chargement du modele).")
        }
    }

    /** Arrete `llama-server`. `pkill` SANS `-f` : matche sur le nom du process (~15 car.), jamais
     * sur la ligne de commande complete de l'invocateur (bug documente dans la source d'inspiration
     * -- un `su -c 'pkill -f llama-server'` peut se matcher lui-meme avec `-f`). */
    fun stop(): String = lock.withLock {
        if (!RootHelper.isRooted()) return@withLock "Root indisponible."
        if (!isRunning()) return@withLock "Deja arrete."
        suWrite("pkill llama-server")
        "Arret demande."
    }

    private fun shQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * Seule porte d'ecriture de ce fichier : DEUX commandes fixes ci-dessus seulement
     * (jamais une chaine arbitraire passee par un appelant externe -- cette fonction est
     * privee, pas exposee). Reimplementation minimale de l'exec `su` (RootHelper.suCmd est
     * prive, a dessein -- pas de capacite d'ecriture exposee depuis ce fichier lecture seule).
     */
    // Meme liste que RootHelper.SU_BIN_PATHS (prive, non reutilisable directement) -- duplique
    // ici volontairement plutot que d'exposer une methode d'ecriture depuis RootHelper, qui
    // doit rester 100% lecture seule.
    private val SU_BIN_PATHS = arrayOf(
        "/product/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
    )

    /**
     * Bug reel trouve en testant sur device (2026-10-14) : la premiere version ne drainait
     * JAMAIS stdout/stderr du process `su -c` -- si `su` (ou le shell qu'il lance) ecrit ne
     * serait-ce qu'un octet sur l'une de ces pipes (banniere, message de job control du
     * backgrounding `&`...) et que rien ne le lit, le pipe se remplit et le process reste
     * BLOQUE indefiniment sans jamais atteindre `setsid ./llama-server ...` -- `waitFor`
     * expirait silencieusement (code de retour ignore), aucune exception, aucun log,
     * et le fichier de log du serveur n'etait jamais touche (confirme par son mtime inchange
     * apres l'appel). Meme pattern de correctif que RootHelper.exec() : deux threads qui
     * drainent stdout/stderr en parallele du waitFor.
     */
    private fun suWrite(cmd: String) {
        try {
            val su = SU_BIN_PATHS.firstOrNull { java.io.File(it).exists() } ?: "su"
            android.util.Log.i("LlmServerController", "suWrite su=$su cmd=$cmd")
            val process = ProcessBuilder(su, "-c", cmd).start()
            process.outputStream.close()
            var out = ""
            var err = ""
            val outThread = Thread { out = try { process.inputStream.readBytes().toString(Charsets.UTF_8) } catch (e: Exception) { "" } }
            val errThread = Thread { err = try { process.errorStream.readBytes().toString(Charsets.UTF_8) } catch (e: Exception) { "" } }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            outThread.join(1000)
            errThread.join(1000)
            android.util.Log.i("LlmServerController", "suWrite fini=$finished exit=${if (finished) process.exitValue() else "?"} out=[$out] err=[$err]")
        } catch (e: Exception) {
            android.util.Log.e("LlmServerController", "suWrite erreur : ${e.message}", e)
        }
    }
}
