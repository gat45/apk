package com.op15.philoharness.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.op15.philoharness.R
import com.op15.philoharness.arguments.ArgumentDao
import com.op15.philoharness.formalization.ArgumentStructurer
import com.op15.philoharness.llm.LlmClient
import com.op15.philoharness.logic.LogicSolver
import com.op15.philoharness.memory.MarkdownMemoryStore
import com.op15.philoharness.rag.RagRetriever
import com.op15.philoharness.rag.RagStore
import com.op15.philoharness.tools.AuditLog
import com.op15.philoharness.tools.BuildTestExecutor
import com.op15.philoharness.tools.FileEditExecutor
import com.op15.philoharness.tools.GitToolExecutor
import com.op15.philoharness.tools.ShellToolExecutor

/**
 * Implementation concrete de PhiloReasoningWorker : ferme la boucle reelle
 * LLM -> Controller -> Z3 (voir README, section Spike et sections suivantes).
 * Delegue toute la logique de decision a ReasoningController (pure, teste en
 * JVM) -- cette classe ne fait que l'orchestration Android.
 *
 * La phase est persistee dans ArgumentEntity.reasoningPhase (pas un champ
 * d'instance) : un worker tue et relance par WorkManager reprend a la bonne
 * phase au lieu de repartir de NEEDS_REASONING -- limite notee dans une
 * version anterieure du README, desormais corrigee.
 */
class DefaultPhiloReasoningWorker(
    context: Context,
    params: WorkerParameters,
    private val argumentDao: ArgumentDao,
    logicSolver: LogicSolver,
    budget: ReasoningBudget,
    llmClient: LlmClient,
    gitToolExecutor: GitToolExecutor? = null,
    buildTestExecutor: BuildTestExecutor? = null,
    fileEditExecutor: FileEditExecutor? = null,
    shellToolExecutor: ShellToolExecutor? = null,
    auditLog: AuditLog? = null,
    memoryStore: MarkdownMemoryStore? = null,
) : PhiloReasoningWorker(
    context, params, argumentDao, logicSolver, budget,
    gitToolExecutor = gitToolExecutor, buildTestExecutor = buildTestExecutor,
    fileEditExecutor = fileEditExecutor, shellToolExecutor = shellToolExecutor,
    auditLog = auditLog, memoryStore = memoryStore,
) {

    /**
     * `RagStore.search` capture deja toute exception et renvoie une liste vide (best-effort,
     * cf. son propre code) -- aucune gestion d'erreur supplementaire necessaire ici.
     */
    private val controller = ReasoningController(
        llmClient,
        ArgumentStructurer(),
        RagRetriever { query, topK -> RagStore.search(applicationContext, query, topK).map { it.content } },
    )

    override suspend fun observeNextStep(argumentId: String): ReasoningDecision {
        val argument = argumentDao.getArgument(argumentId)
            ?: return ReasoningDecision.Abstain(AbstentionReason.SOURCES_INSUFFISANTES)

        val currentPhase = ReasoningPhase.entries.find { it.name == argument.reasoningPhase }
            ?: ReasoningPhase.NEEDS_REASONING // valeur inconnue/corrompue -- repart du debut plutot que planter

        val outcome = controller.decide(argument, currentPhase)

        val nextPhase = when (currentPhase) {
            ReasoningPhase.NEEDS_REASONING -> when (outcome.decision) {
                is ReasoningDecision.Reason -> ReasoningPhase.NEEDS_VERIFICATION
                // Demande d'outil : reste en NEEDS_REASONING -- le prochain passage de la
                // boucle (meme budget, PhiloReasoningWorker.doWork) relira lastToolOutput
                // (deja persiste par l'execution de l'outil dans PhiloReasoningWorker) et
                // rappellera le LLM avec le resultat disponible. Borne par budget.maxSteps,
                // pas une boucle infinie.
                is ReasoningDecision.UseTool -> ReasoningPhase.NEEDS_REASONING
                else -> ReasoningPhase.DONE
            }
            ReasoningPhase.NEEDS_VERIFICATION -> ReasoningPhase.NEEDS_ANSWER
            ReasoningPhase.NEEDS_ANSWER -> ReasoningPhase.DONE
            ReasoningPhase.DONE -> ReasoningPhase.DONE
        }

        // Une seule ecriture pour la conclusion, les premisses ET la phase -- evite une
        // incoherence si le process meurt entre deux updateArgument separes. Premisses :
        // angle mort reel trouve en auditant cette session -- ArgumentStructurer les
        // calculait deja mais rien ne les persistait jamais (cf. TODO.md).
        argumentDao.updateArgument(
            argument.copy(
                conclusion = outcome.structuredConclusion ?: argument.conclusion,
                premissesExplicitesJson = outcome.structuredPremises
                    ?.let { org.json.JSONArray(it).toString() }
                    ?: argument.premissesExplicitesJson,
                reasoningPhase = nextPhase.name,
            ),
        )

        return outcome.decision
    }

    override fun buildForegroundInfo(): ForegroundInfo {
        val channelId = "philo_reasoning"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Raisonnement philosophique", NotificationManager.IMPORTANCE_LOW),
            )
        }
        // Icone dediee (TODO.md "Notification foreground" : generique avant,
        // android.R.drawable.ic_popup_reminder) + bouton Annuler relie a
        // WorkManager.createCancelPendingIntent (annule reellement ce travail,
        // pas un bouton decoratif) -- meme TODO notait l'absence des deux.
        val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notification.Builder(applicationContext, channelId)
            .setContentTitle("Analyse d'argument en cours")
            .setContentText("Raisonnement + verification Z3 en cours -- peut prendre plusieurs secondes sur ce device.")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(applicationContext, R.drawable.ic_notification),
                    "Annuler",
                    cancelIntent,
                ).build(),
            )
            .build()
        // Type explicite requis a partir de l'API 29 pour lever toute ambiguite
        // avec le foregroundServiceType declare dans le manifeste (dataSync) --
        // sans l'un des deux, Android 14+ refuse le service (voir manifeste).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
