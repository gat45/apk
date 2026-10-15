package com.op15.philoharness.control

import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.formalization.ArgumentStructurer
import com.op15.philoharness.formalization.StructuringResult
import com.op15.philoharness.llm.ChatMessage
import com.op15.philoharness.llm.LlmCallResult
import com.op15.philoharness.llm.LlmClient
import com.op15.philoharness.rag.RagRetriever

/**
 * Phase explicite de la boucle, passee par l'appelant (le Worker) plutot que
 * stockee en champ mutable ici -- garde cette classe pure et testable en JVM
 * sans Android/Room/WorkManager (meme principe que ResourceManager/RiskGate).
 */
enum class ReasoningPhase { NEEDS_REASONING, NEEDS_VERIFICATION, NEEDS_ANSWER, DONE }

/**
 * Resultat d'une etape de raisonnement : la decision + la conclusion structuree a persister,
 * si trouvee. [structuredPremises] : angle mort reel trouve en auditant cette session --
 * `ArgumentStructurer` calculait deja de vraies premisses mais rien ne les persistait jamais
 * (`ArgumentEntity.premissesExplicitesJson` restait a "[]" pour toujours). Necessaires
 * desormais pour que `verifyIndependently()` formalise l'ARGUMENT complet, pas la conclusion
 * seule (cf. TODO.md, trouvaille audit "la verification Z3 ne verifie pas l'argument").
 */
data class ReasonOutcome(
    val decision: ReasoningDecision,
    val structuredConclusion: String?,
    val structuredPremises: List<String>? = null,
)

/**
 * Boucle de decision reelle (brique A+B, audit Livrable 4) : appelle le LLM,
 * structure sa reponse via ArgumentStructurer (tache 4), route vers la
 * verification Z3 (tache 1), et ne produit jamais Answer sans statut
 * epistemique favorable deja etabli par un verdict de solveur independant.
 */
class ReasoningController(
    private val llmClient: LlmClient,
    private val argumentStructurer: ArgumentStructurer,
    private val ragRetriever: RagRetriever = RagRetriever.NONE,
) {
    fun decide(argument: ArgumentEntity, phase: ReasoningPhase): ReasonOutcome = when (phase) {
        ReasoningPhase.NEEDS_REASONING -> reasonStep(argument)
        ReasoningPhase.NEEDS_VERIFICATION -> ReasonOutcome(ReasoningDecision.Verify(argument.id), null)
        ReasoningPhase.NEEDS_ANSWER -> ReasonOutcome(answerStep(argument), null)
        ReasoningPhase.DONE -> ReasonOutcome(ReasoningDecision.Stop, null)
    }

    private fun reasonStep(argument: ArgumentEntity): ReasonOutcome {
        val prompt = buildPrompt(argument)
        val toolsOffered = argument.lastToolOutput == null
        return when (
            val result = llmClient.chat(
                listOf(ChatMessage("user", prompt)),
                maxTokens = 256,
                grammar = if (toolsOffered) toolRequestGrammar() else null,
            )
        ) {
            is LlmCallResult.Success -> {
                val toolRequest = parseToolRequest(result.response.content)
                if (toolRequest != null) {
                    // Chainon agentique : avant de structurer une conclusion, verifier si le
                    // modele a demande un outil. Le Worker executera l'outil et persistera
                    // le resultat dans ArgumentEntity.lastToolOutput -- comme la phase reste
                    // NEEDS_REASONING pour une demande d'outil (cf. DefaultPhiloReasoningWorker),
                    // le prochain appel LLM verra ce resultat via buildPrompt() et pourra
                    // conclure sans redemander le meme outil. Borne par budget.maxSteps,
                    // jamais une boucle infinie.
                    return ReasonOutcome(toolRequest, structuredConclusion = null)
                }
                when (val structuring = argumentStructurer.structure(result.response.content)) {
                    is StructuringResult.Structured -> ReasonOutcome(
                        decision = ReasoningDecision.Reason("conclusion identifiee via ${structuring.argument.conclusionMarker}"),
                        structuredConclusion = structuring.argument.conclusion,
                        structuredPremises = structuring.argument.premises,
                    )
                    is StructuringResult.NoMarkerFound -> ReasonOutcome(
                        // Angle mort "mauvaise formalisation" (audit) : le LLM n'a produit aucun
                        // marqueur de structuration reconnu -- abstention, pas une conclusion devinee.
                        decision = ReasoningDecision.Abstain(AbstentionReason.DEFINITIONS_INSUFFISANTES),
                        structuredConclusion = null,
                    )
                }
            }
            is LlmCallResult.NetworkError, is LlmCallResult.ServerError ->
                // Le modele local (llama.cpp) est injoignable -- ce n'est pas une absence de
                // sources documentaires, mais la cause la plus proche du vocabulaire existant.
                ReasonOutcome(ReasoningDecision.Abstain(AbstentionReason.SOURCES_INSUFFISANTES), null)
        }
    }

    /**
     * Format volontairement minimal et explicite (pas de JSON/function-calling --
     * le modele local n'est pas garanti le supporter proprement) : une ligne
     * "OUTIL: nom_outil" optionnellement suivie de "ARGS: cle=valeur,cle2=valeur2".
     * Seuls git_status/git_diff sont annonces au modele dans le prompt (lecture
     * seule, Reversibility.REVERSIBLE) -- write_file/git_commit/shell existent
     * dans le dispatch du Worker mais ne sont jamais suggeres au LLM pour
     * l'instant, meme discipline conservatrice que le reste de la session.
     */
    /**
     * Grammaire GBNF (angle mort note dans TODO.md : "ecrire une grammaire GBNF reelle
     * pour ReasoningController ... rien ne l'utilise encore") -- transmise a
     * `LlmClient.chat(grammar=...)`, qui contraint llama-server AU NIVEAU DU SAMPLING :
     * le modele ne peut litteralement pas emettre un nom d'outil hors de cette liste
     * ("OUTIL: shell_rm_rf" devient impossible a generer, pas juste deconseille dans
     * le prompt). Volontairement PAS de JSON/function-calling (decision documentee
     * ailleurs dans ce fichier : non garanti fiable sur un petit modele local) --
     * la grammaire contraint seulement le format texte "OUTIL: .../ARGS: ..." deja
     * utilise, elle ne le remplace pas. [parseToolRequest] reste volontairement
     * permissif (accepte tout nom qu'un texte lui presente) : la grammaire est une
     * aide a la fiabilite cote generation COOPERATIVE, pas une barriere de securite
     * -- le Worker (executeTool) reste la seule autorite sur ce qui est reellement
     * execute, exactement comme avant cette grammaire.
     *
     * LIMITE REELLE trouvee en testant contre un vrai llama-server (pas supposee) :
     * la branche `conclusion` est un sur-ensemble textuel de `toolcall` ("OUTIL: x"
     * est aussi un texte libre valide), donc llama.cpp -- qui maintient en parallele
     * TOUS les etats de parsing encore valides, pas "un branchement choisi" -- continue
     * d'autoriser n'importe quel caractere apres "OUTIL: " tant que la branche
     * `conclusion` reste satisfaite. Verifie en forcant le modele (prompt adversarial
     * explicite "ecris OUTIL: shell") : il a produit "OUTIL: shell" sans erreur, alors
     * que "shell" n'est PAS dans `toolname`. La grammaire contraint donc la forme
     * quand le modele s'engage realistement dans le protocole outil (demontre separement
     * avec "OUTIL: git_status", jamais un nom hors liste dans ce cas), mais ne garantit
     * PAS qu'un texte adversarial ne puisse pas imiter la ligne -- aucune instruction
     * du prompt reel n'incite jamais a ca, mais l'honnetete sur la limite compte plus
     * que l'affichage d'une garantie qui n'existe pas.
     *
     * La branche `conclusion` reste du texte libre non contraint : ArgumentStructurer
     * depend de marqueurs linguistiques naturels ("donc", "car"...), pas d'un format
     * rigide, et une grammaire ne peut pas exprimer "contient un marqueur" sans
     * réduire la liberté de formulation du modèle.
     */
    private fun toolRequestGrammar(): String = """
        root ::= toolcall | conclusion
        toolcall ::= "OUTIL: " toolname "\n" argsline?
        toolname ::= "git_status" | "git_diff" | "memory_read" | "screen_read" | "screen_tap" | "screen_input"
        argsline ::= "ARGS: " argtext "\n"?
        argtext ::= [^\n]+
        conclusion ::= [^\n]+ ("\n" [^\n]*)*
    """.trimIndent()

    private fun parseToolRequest(response: String): ReasoningDecision.UseTool? {
        val toolLine = response.lineSequence().firstOrNull { it.trim().startsWith("OUTIL:", ignoreCase = true) }
            ?: return null
        val toolName = toolLine.substringAfter(":").trim().takeIf { it.isNotBlank() } ?: return null
        val argsLine = response.lineSequence().firstOrNull { it.trim().startsWith("ARGS:", ignoreCase = true) }
        val args = argsLine
            ?.substringAfter(":")
            ?.split(",")
            ?.mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            ?.toMap()
            ?: emptyMap()
        return ReasoningDecision.UseTool(toolName, args)
    }

    private fun answerStep(argument: ArgumentEntity): ReasoningDecision = when (argument.statut) {
        EpistemicStatus.SOUTENU -> ReasoningDecision.Answer(argument.id, "z3:sat:${argument.id}")
        EpistemicStatus.REFUTE -> ReasoningDecision.Answer(argument.id, "z3:unsat:${argument.id}")
        else ->
            // INDETERMINE/EN_ATTENTE_DE_VERIFICATION/CONTESTE/OBSOLETE : le solveur n'a pas
            // tranche -- jamais de certitude fabriquee, abstention explicite.
            ReasoningDecision.Abstain(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT)
    }

    private fun buildPrompt(argument: ArgumentEntity): String {
        val ragSection = buildRagSection(argument)
        val toolResultSection = argument.lastToolOutput?.let { output ->
            "\n\nResultat du dernier outil demande :\n$output\n"
        } ?: ""
        val toolMenuSection = if (argument.lastToolOutput == null) {
            // N'annonce les outils que si aucun n'a encore ete utilise pour cet argument --
            // evite une boucle de redemandes repetees une fois une reponse deja obtenue.
            "\n\nSi tu as besoin du statut ou du diff git pour repondre, ecris exactement une " +
                "ligne \"OUTIL: git_status\" ou \"OUTIL: git_diff\" a la place de ta reponse. " +
                "Pour consulter ta memoire durable (notes passees), ecris \"OUTIL: memory_read\" " +
                "(avec \"ARGS: slug=xxx\" pour une note precise, sinon l'index complet est retourne). " +
                "Si la tache demande d'agir sur l'ecran du telephone (une autre application) : " +
                "\"OUTIL: screen_read\" pour lire les elements visibles (coordonnees+libelles), " +
                "\"OUTIL: screen_tap\" avec \"ARGS: x=123,y=456\" pour taper a ces coordonnees, " +
                "\"OUTIL: screen_input\" avec \"ARGS: text=...\" pour saisir du texte dans le champ " +
                "deja focalise. Toujours lire l'ecran avant de taper (jamais de coordonnees devinees). " +
                "Sinon, conclus directement."
        } else ""
        return "Analyse cet argument et conclus avec un marqueur explicite (\"donc\", \"car\"...).\n\n" +
            "Question : ${argument.questionOrigine}\n" +
            "Texte : ${argument.texteOriginal}" +
            ragSection +
            toolResultSection +
            toolMenuSection
    }

    /**
     * Contexte RAG best-effort (angle mort comble : RagStore existait et fonctionnait depuis
     * une session precedente mais rien ne l'appelait -- TODO.md). Echec silencieux cote
     * [RagRetriever] (`RagStore.search` capture deja toute exception et retourne une liste
     * vide) -- jamais de section vide/mensongere injectee si rien de pertinent n'est trouve,
     * et jamais presente comme une source fiable : c'est un rappel de passages anterieurs,
     * pas une preuve (le solveur Z3 reste la seule preuve independante, cf. verifyIndependently).
     */
    private fun buildRagSection(argument: ArgumentEntity): String {
        val query = argument.questionOrigine.ifBlank { argument.texteOriginal }
        if (query.isBlank()) return ""
        val passages = ragRetriever.retrieve(query, topK = 3)
        if (passages.isEmpty()) return ""
        return "\n\nContexte retrouve dans la memoire (echanges/documents anterieurs, " +
            "a titre indicatif -- pas une preuve) :\n" +
            passages.joinToString("\n---\n")
    }
}
