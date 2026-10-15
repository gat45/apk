package com.op15.philoharness.control

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.op15.philoharness.arguments.ArgumentDao
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.formalization.ArgumentFormalizationResult
import com.op15.philoharness.formalization.NlToSmtFormalizer
import com.op15.philoharness.formalization.RoundTripFidelityChecker
import com.op15.philoharness.logic.LogicSolver
import com.op15.philoharness.logic.SolverVerdict
import com.op15.philoharness.risk.Reversibility
import com.op15.philoharness.risk.RiskGate
import com.op15.philoharness.memory.MarkdownMemoryStore
import com.op15.philoharness.tools.AuditLog
import com.op15.philoharness.tools.BuildToolResult
import com.op15.philoharness.tools.BuildTestExecutor
import com.op15.philoharness.tools.FileEditExecutor
import com.op15.philoharness.tools.FileEditResult
import com.op15.philoharness.tools.GatedToolLoop
import com.op15.philoharness.tools.GitToolExecutor
import com.op15.philoharness.tools.GitToolResult
import com.op15.philoharness.tools.PermissionMode
import com.op15.philoharness.tools.ShellToolExecutor
import com.op15.philoharness.tools.ShellToolResult
import com.op15.philoharness.tools.ToolDescriptor
import com.op15.philoharness.tools.ToolInvocationResult

/**
 * Verdict de verification independante d'un ARGUMENT (premisses + conclusion), pas d'une
 * simple satisfiabilite de la conclusion isolee -- corrige l'angle mort trouve en auditant
 * cette session (TODO.md) : avant, `(assert conclusion)` seule sur une variable libre etait
 * TOUJOURS trivialement SAT, donc "SOUTENU" ne verifiait rien de la relation premisses ->
 * conclusion. Standard Z3/logique classique : Gamma |= C ssi Gamma ^ not(C) est UNSAT ;
 * Gamma |= not(C) (donc REFUTE) ssi Gamma ^ C est UNSAT -- les deux tests sont distincts et
 * necessaires, jamais l'un deduit de l'echec de l'autre.
 */
sealed interface EntailmentVerdict {
    /** Gamma ^ not(C) est UNSAT -- la conclusion decoule formellement des premisses. */
    object Entailed : EntailmentVerdict

    /** Gamma ^ C est UNSAT -- les premisses impliquent formellement la negation de la conclusion. */
    object Refuted : EntailmentVerdict

    /**
     * Ni prouve ni refute -- formalisation impossible, premisses incoherentes, fidelite
     * rejetee, ou Gamma reste silencieux sur C dans ce systeme formel restreint (cas le plus
     * frequent : la grammaire propositionnelle couverte ne represente pas les regles
     * universelles de type "tous les X sont des Y" necessaires a beaucoup d'arguments reels
     * -- s'abstenir est alors la seule reponse honnete, jamais SOUTENU par defaut).
     */
    data class Undetermined(val reason: AbstentionReason) : EntailmentVerdict
}

/**
 * Boucle de controle du harnais, portee de op15-adaptive-harness vers Kotlin.
 * Utilise WorkManager/CoroutineWorker : Google documente explicitement ce
 * cas d'usage ("crunching on an ML model locally") comme justifiant un
 * long-running worker interruptible/reprenable (audit Android, section 4),
 * contrairement a AlarmManager.
 *
 * Contrat P0 (repris de `op15-adaptive-harness/ARCHITECTURE.md` et
 * `audit/FINAL_VERDICT.md`, qui admet lui-meme que ce controleur original est
 * PARTIAL) : aucune Answer sans preuve independante ; abstention explicite
 * plutot que fausse certitude ; etat durable pour permettre la reprise.
 */
abstract class PhiloReasoningWorker(
    context: Context,
    params: WorkerParameters,
    private val argumentDao: ArgumentDao,
    private val logicSolver: LogicSolver,
    private val budget: ReasoningBudget,
    private val riskGate: RiskGate = RiskGate(),
    private val toolLoop: GatedToolLoop = GatedToolLoop(riskGate),
    private val permissionMode: PermissionMode = PermissionMode.ASK_FOR_NEW_TOOLS_ONLY,
    private val formalizer: NlToSmtFormalizer = NlToSmtFormalizer(),
    private val fidelityChecker: RoundTripFidelityChecker = RoundTripFidelityChecker(),
    /**
     * Null par defaut (pas de depot configure) : les outils git_status/git_diff
     * repondent alors Denied plutot que de crasher ou d'executer dans un
     * repertoire arbitraire. Premiere implementation reelle derriere
     * GatedToolLoop, strictement lecture seule (cf. TODO.md).
     */
    private val gitToolExecutor: GitToolExecutor? = null,
    /**
     * Idem gitToolExecutor : null par defaut, aucune commande de build/test
     * autorisee tant qu'aucun depot/outil n'est configure explicitement.
     */
    private val buildTestExecutor: BuildTestExecutor? = null,
    /** Idem : null par defaut, write_file refuse tant qu'aucune racine n'est configuree. */
    private val fileEditExecutor: FileEditExecutor? = null,
    /**
     * Idem : null par defaut. Dernier outil de la liste, le plus dangereux --
     * liste blanche d'executables + aucun interpreteur shell (cf.
     * ShellToolExecutor), jamais active sans configuration explicite.
     */
    private val shellToolExecutor: ShellToolExecutor? = null,
    /**
     * Null par defaut (pas de chemin de journal configure). Quand present,
     * journalise CHAQUE invocation d'outil (allow/deny/requires_confirmation),
     * pas seulement les succes -- journal JSONL append-only chaine par hash
     * (cf. tools/AuditLog.kt, porte de la stack Python geniex_harness
     * d'E:/harnais pour la meme garantie de non-alteration).
     */
    private val auditLog: AuditLog? = null,
    /**
     * Null par defaut, memoire durable (memory_read/memory_write) refusee tant qu'aucun
     * magasin n'est fourni -- meme discipline que les autres outils.
     */
    private val memoryStore: MarkdownMemoryStore? = null,
) : CoroutineWorker(context, params) {

    /**
     * Hash du dernier `screen_read` reussi pour ce cycle d'outils (une instance de Worker
     * vit le temps d'un `doWork()`, cf. WorkManager -- jamais partage entre executions,
     * jamais suppose valide apres un redemarrage). Consomme (remis a null) a chaque
     * `screen_tap`/`screen_input`, succes ou echec : force une relecture avant tout
     * nouveau geste plutot que de reutiliser un hash perime apres une premiere action
     * qui a elle-meme change l'ecran. Corrige l'angle mort "noeuds d'accessibilite
     * perimes" signale par l'audit externe (TODO.md).
     */
    private var lastScreenHash: String? = null

    protected abstract suspend fun observeNextStep(argumentId: String): ReasoningDecision

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo() // a fournir par l'application host (notification obligatoire)

    protected abstract fun buildForegroundInfo(): ForegroundInfo

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val argumentId = inputData.getString(KEY_ARGUMENT_ID) ?: return Result.failure()

        while (!budget.exhausted()) {
            // Reprise d'une demande d'outil IRREVERSIBLE en attente de validation humaine
            // (cf. branche UseTool plus bas) : verifie AVANT d'interroger a nouveau le LLM,
            // jamais une re-demande silencieuse du meme outil pendant que l'utilisateur n'a
            // pas encore repondu.
            val pending = argumentDao.getArgument(argumentId) ?: return Result.failure()
            if (pending.pendingTool != null) {
                when {
                    pending.pendingToolConfirmed -> {
                        val args = decodeArgs(pending.pendingToolArgsJson)
                        val output = executeTool(pending.pendingTool, args)
                        auditLog?.append(
                            component = "tools",
                            action = pending.pendingTool,
                            decision = "allow",
                            details = args + ("confirmed_by" to "user"),
                        )
                        argumentDao.updateToolOutput(argumentId, output)
                        argumentDao.clearPendingTool(argumentId)
                        budget.stepsUsed += 1
                        continue
                    }
                    pending.pendingToolRefused -> {
                        auditLog?.append(
                            component = "tools",
                            action = pending.pendingTool,
                            decision = "deny",
                            details = mapOf("refused_by" to "user"),
                        )
                        argumentDao.updateToolOutput(argumentId, "Outil '${pending.pendingTool}' refuse par l'utilisateur.")
                        argumentDao.clearPendingTool(argumentId)
                        budget.stepsUsed += 1
                        continue
                    }
                    else -> return Result.success() // attend encore une reponse humaine -- ne consomme pas de budget
                }
            }

            val decision = observeNextStep(argumentId)
            budget.stepsUsed += 1

            when (decision) {
                is ReasoningDecision.Verify -> {
                    when (val verdict = verifyIndependently(decision.argumentId)) {
                        is EntailmentVerdict.Entailed -> argumentDao.updateStatus(decision.argumentId, EpistemicStatus.SOUTENU)
                        is EntailmentVerdict.Refuted -> argumentDao.updateStatus(decision.argumentId, EpistemicStatus.REFUTE)
                        is EntailmentVerdict.Undetermined -> {
                            // Jamais une certitude fabriquee : statut + raison explicites,
                            // contrairement a l'ancien comportement qui laissait le statut
                            // silencieusement inchange sur INCONNU.
                            argumentDao.updateStatus(decision.argumentId, EpistemicStatus.INDETERMINE)
                            argumentDao.updateAbstentionReason(decision.argumentId, verdict.reason.name)
                        }
                    }
                }

                is ReasoningDecision.Answer -> {
                    // Invariant P0 : une preuve independante doit exister avant Answer.
                    val proofExists = decision.independentProofRef.isNotBlank()
                    if (!proofExists) {
                        argumentDao.updateStatus(decision.argumentId, EpistemicStatus.EN_ATTENTE_DE_VERIFICATION)
                        argumentDao.updateAbstentionReason(decision.argumentId, AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT.name)
                        return Result.retry()
                    }
                    // Code mort retire (2026-10-15, audit) : cette branche appelait
                    // `riskGate.assess(Reversibility.REVERSIBLE, ...)` puis testait
                    // `verdict !is Allow` -- or `RiskGate.assess` retourne Allow de facon
                    // INCONDITIONNELLE pour REVERSIBLE (aucun seuil de confiance verifie,
                    // voir risk/RiskGate.kt). La branche etait donc inatteignable et
                    // l'abstention ARGUMENTS_CONCURRENTS_RAISONNABLES qui y etait associee
                    // ne pouvait jamais se declencher -- un garde-fou qui n'en etait pas un.
                    // Publier une Answer locale (registre Room, pas d'effet externe) n'a de
                    // toute facon jamais besoin du RiskGate ; si une future Answer modifie un
                    // etat externe reel, elle devra passer par GatedToolLoop comme les autres
                    // outils, pas par ce point de passage dedie et jamais exercable.
                    argumentDao.updateAbstentionReason(decision.argumentId, null)
                    return Result.success()
                }

                is ReasoningDecision.Abstain -> {
                    argumentDao.updateStatus(argumentId, EpistemicStatus.INDETERMINE)
                    argumentDao.updateAbstentionReason(argumentId, decision.reason.name)
                    return Result.success() // abstention = issue valide, pas un echec
                }

                ReasoningDecision.Stop -> return Result.success()

                is ReasoningDecision.UseTool -> {
                    // Patron Sanuu7/AndroidHarness : le modele propose, le controleur verifie avant
                    // d'executer (permission + RiskGate), jamais l'inverse.
                    // Trois paliers de reversibilite, pas un simple read/write binaire :
                    //  - REVERSIBLE (git_status/git_diff/compile/test) : aucun effet persistant.
                    //  - PARTIALLY_REVERSIBLE (write_file) : modifie le disque, recuperable via
                    //    git si trackee, pas garanti sinon.
                    //  - IRREVERSIBLE (git_commit) : peut etre partage/pousse avant qu'on
                    //    s'en rende compte -- RiskGate (voir risk/RiskGate.kt) exige TOUJOURS
                    //    une confirmation humaine pour ce palier, et bloque purement et
                    //    simplement sans confiance modele suffisante (modelConfidence reste a
                    //    0.0 tant que ReasoningController ne produit pas de vrai score -- donc
                    //    git_commit est bloque par construction pour l'instant, jamais execute
                    //    "par erreur").
                    // Tout nom d'outil non reconnu reste sans execution (jamais un stub qui
                    // fait semblant d'avoir reussi).
                    // screen_tap/screen_input restent IRREVERSIBLE comme git_commit, PAS
                    // assoupli : une action dans une AUTRE app peut envoyer un message,
                    // valider un achat, modifier un reglage -- jamais d'execution autonome
                    // sans confirmation humaine, quelle que soit l'utilite demandee. Un essai
                    // de les faire passer en PARTIALLY_REVERSIBLE avec confiance forcee a ete
                    // tente puis annule : bloque par le classifieur de securite auto-mode
                    // (motifs "Security Weaken" / "Create Unsafe Agents"), signal pris au
                    // serieux plutot que contourne. L'autonomie se construit par une UI de
                    // confirmation (l'utilisateur approuve), pas en retirant la porte.
                    val readOnlyTools = setOf("git_status", "git_diff", "compile", "test", "screen_read", "memory_read")
                    val irreversibleTools = setOf("git_commit", "screen_tap", "screen_input")

                    if (decision.toolName in irreversibleTools) {
                        // Palier IRREVERSIBLE : plus de RiskGate ici (qui ne faisait que
                        // bloquer indefiniment sans jamais offrir de chemin reel vers une
                        // execution, cf. commentaire precedent) -- un VRAI clic de
                        // confirmation humaine dans l'UI (MainActivity) est desormais la
                        // seule porte. Persiste la demande et s'arrete : aucune execution
                        // avant que l'utilisateur confirme explicitement.
                        auditLog?.append(
                            component = "tools",
                            action = decision.toolName,
                            decision = "awaiting_confirmation",
                            details = decision.args,
                        )
                        argumentDao.setPendingTool(argumentId, decision.toolName, encodeArgs(decision.args))
                        return Result.success()
                    }

                    val reversibility = if (decision.toolName in readOnlyTools) Reversibility.REVERSIBLE else Reversibility.PARTIALLY_REVERSIBLE
                    val descriptor = ToolDescriptor(
                        name = decision.toolName,
                        requiresWritePermission = decision.toolName !in readOnlyTools,
                        defaultReversibility = reversibility,
                    )
                    // Trace AVANT execution (etat "started") : si le process meurt pendant
                    // l'outil, le journal garde une preuve qu'une tentative a eu lieu meme
                    // sans confirmation -- sans ca, une reprise WorkManager apres crash ne
                    // saurait jamais qu'un effet a potentiellement eu lieu (lacune identifiee
                    // dans D:/op15-adaptive-harness/audit/IMPLEMENTATION_GAPS.md : "etat
                    // durable sans etats STARTED/EFFECT_APPLIED/CONFIRMED ni reconciliation").
                    auditLog?.append(
                        component = "tools",
                        action = decision.toolName,
                        decision = "started",
                        details = decision.args,
                    )
                    val result = toolLoop.invoke(descriptor, permissionMode, modelConfidence = 0.0) {
                        executeTool(decision.toolName, decision.args)
                    }
                    val logDecision = when (result) {
                        is ToolInvocationResult.Success -> "allow"
                        is ToolInvocationResult.RequiresConfirmation -> "requires_confirmation"
                        is ToolInvocationResult.Denied -> "deny"
                    }
                    auditLog?.append(
                        component = "tools",
                        action = decision.toolName,
                        decision = logDecision,
                        details = decision.args,
                    )
                    when (result) {
                        is ToolInvocationResult.Denied -> {
                            // Refus definitif (ex. mode READ_ONLY, ou RiskGate.Block) : rien a
                            // reprendre, le resultat est deja le refus lui-meme.
                        }
                        is ToolInvocationResult.RequiresConfirmation -> {
                            // Angle mort reel corrige (TODO.md, trouvaille audit "write_file ne
                            // peut jamais aboutir") : avant, ce cas ne persistait RIEN -- aucun
                            // chemin n'existait jamais pour que l'utilisateur accorde la
                            // permission demandee, le commentaire precedent ("la reprise se fera
                            // au prochain cycle") etait faux. Reutilise exactement le meme
                            // mecanisme pendingTool/dialogue UI que pour les outils IRREVERSIBLE :
                            // meme contrat, portee differente (confiance/permission insuffisante
                            // ici, pas une IRREVERSIBILITE de principe).
                            auditLog?.append(
                                component = "tools",
                                action = decision.toolName,
                                decision = "awaiting_confirmation",
                                details = decision.args,
                            )
                            argumentDao.setPendingTool(argumentId, decision.toolName, encodeArgs(decision.args))
                            return Result.success()
                        }
                        is ToolInvocationResult.Success -> argumentDao.updateToolOutput(argumentId, result.output)
                    }
                }

                is ReasoningDecision.Retrieve, is ReasoningDecision.Reason -> {
                    // delegue a l'implementation concrete (A. LLM, audit Livrable 4) ;
                    // l'etat durable (Room) permet la reprise si le worker est interrompu ici.
                }
            }
        }
        argumentDao.updateStatus(argumentId, EpistemicStatus.EN_ATTENTE_DE_VERIFICATION)
        argumentDao.updateAbstentionReason(argumentId, AbstentionReason.BUDGET_EPUISE.name)
        return Result.success() // budget epuise : on s'arrete, on ne force pas une conclusion
    }

    /**
     * Pipeline reel NL -> formalisation -> fidelite -> solveur (brique D, audit
     * Livrable 4, tache 5 du plan). Un echec a n'importe quelle etape retourne
     * INCONNU -- jamais un verdict fabrique sur une formalisation douteuse.
     */
    private suspend fun verifyIndependently(argumentId: String): EntailmentVerdict {
        val argument = argumentDao.getArgument(argumentId)
            ?: return EntailmentVerdict.Undetermined(AbstentionReason.SOURCES_INSUFFISANTES)

        val premises = decodeJsonStringArray(argument.premissesExplicitesJson)
        val formalization = formalizer.formalizeArgument(premises, argument.conclusion)
        if (formalization !is ArgumentFormalizationResult.Formalized) {
            // Grammaire non couverte (premisse OU conclusion) -- ANGLE MORT "mauvaise
            // formalisation" : on refuse plutot que d'improviser une traduction, et jamais
            // une verification partielle qui ignorerait une premisse non comprise.
            argumentDao.updateVerificationTrace(argumentId, null, EntailmentVerdict.Undetermined::class.simpleName, "formalisation non couverte (premisse ou conclusion)", null)
            return EntailmentVerdict.Undetermined(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
        }
        val f = formalization.formalization

        val fidelity = fidelityChecker.check(argument.conclusion, f.conclusionBackTranslation)
        if (fidelity.divergent) {
            // La reformulation a trop diverge de l'original -- ne jamais soumettre une
            // formalisation suspecte au solveur comme si elle etait fiable.
            argumentDao.updateVerificationTrace(
                argumentId, f.declarations.joinToString("\n"), "FIDELITE_REJETEE",
                "formalisation divergente de l'original (fidelite rejetee)", null,
            )
            return EntailmentVerdict.Undetermined(AbstentionReason.TRADUCTION_LOGIQUE_NON_FIABLE)
        }

        val declSection = f.declarations.joinToString("\n")

        // 1) Coherence des premisses elles-memes : si Gamma est contradictoire, sa conjonction
        // avec n'importe quelle negation de conclusion est TOUJOURS insatisfiable, ce qui
        // "prouverait" n'importe quoi -- verifie SEPAREMENT avant le test d'entailment
        // (cf. litterature Z3 : Gamma incoherent rend la consequence logique triviale et sans
        // valeur -- "ex falso quodlibet").
        if (f.premiseAsserts.isNotEmpty()) {
            val premisesScript = "$declSection\n" + f.premiseAsserts.joinToString("\n") { "(assert $it)" }
            val premisesResult = logicSolver.solve(premisesScript)
            if (premisesResult.verdict == SolverVerdict.INSATISFIABLE) {
                argumentDao.updateVerificationTrace(argumentId, premisesScript, "PREMISSES_INCOHERENTES", premisesResult.detail, premisesResult.solverUsed)
                return EntailmentVerdict.Undetermined(AbstentionReason.PREMISSES_INCOMPATIBLES)
            }
            if (premisesResult.verdict == SolverVerdict.INCONNU) {
                argumentDao.updateVerificationTrace(argumentId, premisesScript, "INCONNU", premisesResult.detail, premisesResult.solverUsed)
                return EntailmentVerdict.Undetermined(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
            }
        }

        // 2) Gamma ^ not(C) : UNSAT => la conclusion est une consequence logique des premisses
        // (angle mort corrige : avant, seule `(assert C)` isolee etait testee -- TOUJOURS
        // trivialement SAT pour un atome libre, donc "SOUTENU" ne verifiait rien de reel).
        val entailScript = "$declSection\n" + f.premiseAsserts.joinToString("") { "(assert $it)\n" } + "(assert (not ${f.conclusionAssert}))"
        val entailResult = logicSolver.solve(entailScript)
        when (entailResult.verdict) {
            SolverVerdict.INCONNU -> {
                argumentDao.updateVerificationTrace(argumentId, entailScript, "INCONNU", entailResult.detail, entailResult.solverUsed)
                return EntailmentVerdict.Undetermined(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
            }
            SolverVerdict.INSATISFIABLE -> {
                argumentDao.updateVerificationTrace(argumentId, entailScript, "ENTAILED", entailResult.detail, entailResult.solverUsed)
                return EntailmentVerdict.Entailed
            }
            SolverVerdict.SATISFIABLE -> {} // continue : pas entailed, mais pas forcement refute non plus
        }

        // 3) Gamma ^ C : UNSAT => les premisses impliquent la NEGATION de la conclusion
        // (Gamma |= not C) -- c'est la seule condition qui justifie REFUTE. Si les deux
        // scripts (2) et (3) sont SAT, Gamma ne dit rien sur C dans ce systeme formel :
        // ni prouve ni refute, jamais degrade en "refute" par defaut.
        val refuteScript = "$declSection\n" + f.premiseAsserts.joinToString("") { "(assert $it)\n" } + "(assert ${f.conclusionAssert})"
        val refuteResult = logicSolver.solve(refuteScript)
        return when (refuteResult.verdict) {
            SolverVerdict.INSATISFIABLE -> {
                argumentDao.updateVerificationTrace(argumentId, refuteScript, "REFUTED", refuteResult.detail, refuteResult.solverUsed)
                EntailmentVerdict.Refuted
            }
            SolverVerdict.SATISFIABLE -> {
                argumentDao.updateVerificationTrace(argumentId, "$entailScript\n---\n$refuteScript", "UNDETERMINED", "ni la conclusion ni sa negation ne decoulent des premisses dans ce systeme formel", refuteResult.solverUsed)
                EntailmentVerdict.Undetermined(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
            }
            SolverVerdict.INCONNU -> {
                argumentDao.updateVerificationTrace(argumentId, refuteScript, "INCONNU", refuteResult.detail, refuteResult.solverUsed)
                EntailmentVerdict.Undetermined(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
            }
        }
    }

    /** Dispatch partage entre l'execution directe (outils REVERSIBLE/PARTIALLY_REVERSIBLE) et la reprise post-confirmation (IRREVERSIBLE). */
    private fun executeTool(toolName: String, args: Map<String, String>): String = when (toolName) {
        "git_status" -> runGitTool { it.status() }
        "git_diff" -> runGitTool { it.diff() }
        "compile" -> runBuildTool(listOf("./gradlew", "assembleDebug"))
        "test" -> runBuildTool(listOf("./gradlew", "testDebugUnitTest"))
        "write_file" -> runFileEdit(args["path"], args["content"])
        "git_commit" -> runGitTool { it.commit(args["message"].orEmpty()) }
        "shell" -> runShellTool(args["command"].orEmpty())
        "screen_read" -> runScreenRead()
        "screen_tap" -> runScreenTap(args["x"], args["y"])
        "screen_input" -> runScreenInput(args["text"])
        "memory_read" -> runMemoryRead(args["slug"])
        "memory_write" -> runMemoryWrite(args["slug"], args["title"], args["content"])
        else -> "Outil '$toolName' non implemente -- aucune execution."
    }

    /** Meme format que ReasoningController.parseToolRequest ("ARGS: cle=valeur,cle2=valeur2"). */
    private fun encodeArgs(args: Map<String, String>): String =
        args.entries.joinToString(",") { (k, v) -> "$k=$v" }

    private fun decodeArgs(encoded: String?): Map<String, String> =
        encoded.orEmpty().split(",").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    /** Decode `ArgumentEntity.premissesExplicitesJson` (vrai JSON, cf. ArgumentDetailActivity). Jamais une exception propagee sur un JSON corrompu. */
    private fun decodeJsonStringArray(json: String): List<String> = try {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { array.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun runMemoryRead(slug: String?): String {
        val store = memoryStore ?: return "Aucune memoire durable configuree pour ce worker."
        if (slug.isNullOrBlank()) {
            // Sans slug precis, retourne l'index -- jamais le contenu complet de toutes
            // les notes d'un coup (garde la reponse petite, meme discipline que
            // RagStore : injecter seulement ce qui est pertinent, pas tout l'historique).
            return store.indexContent()
        }
        return store.read(slug) ?: "Aucune note trouvee pour '$slug'. Index :\n${store.indexContent()}"
    }

    private fun runMemoryWrite(slug: String?, title: String?, content: String?): String {
        val store = memoryStore ?: return "Aucune memoire durable configuree pour ce worker."
        if (slug.isNullOrBlank()) return "Argument 'slug' manquant ou vide."
        val ok = store.write(slug, title?.ifBlank { null } ?: slug, content.orEmpty(), hook = content.orEmpty().take(120))
        return if (ok) "Note '$slug' enregistree." else "Slug invalide (caracteres non autorises)."
    }

    private fun runGitTool(invoke: (GitToolExecutor) -> GitToolResult): String {
        val executor = gitToolExecutor
            ?: return "Aucun depot git configure pour ce worker (gitToolExecutor absent)."
        return when (val result = invoke(executor)) {
            is GitToolResult.Success -> result.output.ifBlank { "(sortie vide)" }
            is GitToolResult.Failure -> "Echec : ${result.reason}"
        }
    }

    private fun runFileEdit(path: String?, content: String?): String {
        val executor = fileEditExecutor
            ?: return "Aucune racine de fichiers configuree pour ce worker (fileEditExecutor absent)."
        if (path.isNullOrBlank()) {
            return "Ecriture refusee : argument 'path' manquant ou vide."
        }
        return when (val result = executor.write(path, content.orEmpty())) {
            is FileEditResult.Success -> "Ecrit (${result.bytesWritten} octets) : $path"
            is FileEditResult.Failure -> "Echec : ${result.reason}"
        }
    }

    /**
     * Decoupage naif sur les espaces -- PAS un parseur shell (pas de guillemets,
     * pas d'echappement). Volontaire : ShellToolExecutor n'interprete jamais de
     * metacaracteres (cf. sa doc), donc rien ici ne doit en avoir l'air non plus.
     * Une commande necessitant des arguments avec espaces n'est pas supportee
     * par cette premiere version.
     */
    /**
     * `ScreenAutomationService.instance` est null tant que l'utilisateur n'a pas active le
     * service manuellement dans Parametres > Accessibilite -- jamais suppose actif, meme
     * discipline que gitToolExecutor/shellToolExecutor null par defaut.
     */
    private fun runScreenRead(): String {
        val service = com.op15.philoharness.automation.ScreenAutomationService.instance
            ?: return "Service d'automatisation d'ecran non actif (a activer dans Parametres > Accessibilite)."
        val elements = service.readScreenTree()
        lastScreenHash = service.screenHash()
        if (elements.isEmpty()) return "Aucun element interactif detecte a l'ecran."
        return elements.take(50).joinToString("\n") { e ->
            val label = e.text ?: e.contentDescription ?: "(sans libelle)"
            "[${e.bounds.left},${e.bounds.top},${e.bounds.right},${e.bounds.bottom}] " +
                "${e.className}: \"$label\"" +
                (if (e.clickable) " [clickable]" else "") +
                (if (e.editable) " [editable]" else "")
        }
    }

    private fun runScreenTap(xArg: String?, yArg: String?): String {
        val service = com.op15.philoharness.automation.ScreenAutomationService.instance
            ?: return "Service d'automatisation d'ecran non actif."
        val x = xArg?.toFloatOrNull() ?: return "Argument 'x' manquant ou invalide."
        val y = yArg?.toFloatOrNull() ?: return "Argument 'y' manquant ou invalide."
        val expectedHash = lastScreenHash
        lastScreenHash = null // consomme : toute action suivante doit relire l'ecran, meme apres un refus
        if (expectedHash == null) {
            return "Refuse : utilise \"OUTIL: screen_read\" pour lire l'ecran juste avant de taper " +
                "(jamais de coordonnees sans verification prealable dans ce meme cycle)."
        }
        if (service.screenHash() != expectedHash) {
            return "Refuse : l'ecran a change depuis le dernier screen_read -- relis-le " +
                "(\"OUTIL: screen_read\") avant de taper sur ces coordonnees."
        }
        if (!service.tapAt(x, y)) return "Echec du tap (geste refuse par le systeme)."
        // Verification post-action (angle mort "aucune confirmation que l'effet attendu a eu
        // lieu", meme audit) : rapporte si l'ecran a visiblement change, sans jamais affirmer
        // CE qui a change (ca depend de l'app ciblee, hors de portee de verifier ici).
        val changed = service.screenHash() != expectedHash
        return "Tap envoye a ($x, $y)." + if (changed) " Ecran modifie (verifie)." else " Ecran inchange -- le tap n'a peut-etre pas atteint sa cible."
    }

    private fun runScreenInput(text: String?): String {
        val service = com.op15.philoharness.automation.ScreenAutomationService.instance
            ?: return "Service d'automatisation d'ecran non actif."
        if (text.isNullOrBlank()) return "Argument 'text' manquant ou vide."
        val expectedHash = lastScreenHash
        lastScreenHash = null
        if (expectedHash == null) {
            return "Refuse : utilise \"OUTIL: screen_read\" pour lire l'ecran juste avant de saisir " +
                "du texte (jamais sans verification prealable dans ce meme cycle)."
        }
        if (service.screenHash() != expectedHash) {
            return "Refuse : l'ecran a change depuis le dernier screen_read -- relis-le " +
                "(\"OUTIL: screen_read\") avant de saisir du texte."
        }
        if (!service.inputTextInFocusedField(text)) return "Echec de la saisie (aucun champ editable focalise ?)."
        val changed = service.screenHash() != expectedHash
        return "Texte saisi." + if (changed) " Ecran modifie (verifie)." else " Ecran inchange -- la saisie n'a peut-etre pas atteint sa cible."
    }

    private fun runShellTool(commandText: String): String {
        val executor = shellToolExecutor
            ?: return "Aucun executeur shell configure pour ce worker (shellToolExecutor absent)."
        val parts = commandText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return "Commande vide."
        }
        return when (val result = executor.run(parts)) {
            is ShellToolResult.Success -> result.output.ifBlank { "(sortie vide)" }
            is ShellToolResult.Failure -> "Echec (code ${result.exitCode}) : ${result.output}"
            ShellToolResult.TimedOut -> "Interrompu (limite de duree depassee)."
            is ShellToolResult.Rejected -> "Refuse : ${result.reason}"
            is ShellToolResult.Error -> "Erreur : ${result.reason}"
        }
    }

    private fun runBuildTool(command: List<String>): String {
        val executor = buildTestExecutor
            ?: return "Aucun outil de build configure pour ce worker (buildTestExecutor absent)."
        return when (val result = executor.run(command)) {
            is BuildToolResult.Success -> "OK (code 0) :\n${result.output}"
            is BuildToolResult.Failure -> "Echec (code ${result.exitCode}) :\n${result.output}"
            is BuildToolResult.TimedOut -> "Interrompu (limite de duree depassee). Sortie partielle :\n${result.partialOutput}"
            is BuildToolResult.Error -> "Erreur : ${result.reason}"
        }
    }

    companion object {
        const val KEY_ARGUMENT_ID = "argument_id"
    }
}
