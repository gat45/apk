package com.op15.philoharness.control

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.op15.philoharness.arguments.ArgumentDao
import com.op15.philoharness.llm.JavaHttpTransport
import com.op15.philoharness.llm.LlmClient
import com.op15.philoharness.logic.LogicSolver
import com.op15.philoharness.memory.MarkdownMemoryStore
import com.op15.philoharness.tools.AuditLog
import com.op15.philoharness.tools.GitToolExecutor
import java.io.File

/**
 * WorkManager instancie normalement un Worker via un constructeur
 * (Context, WorkerParameters) seul -- insuffisant pour injecter argumentDao/
 * logicSolver/llmClient. Cette factory (enregistree dans
 * PhiloHarnessApplication via Configuration.Provider) fournit ces
 * dependances explicitement, sans conteneur DI (Hilt/Koin) : la complexite
 * du projet ne le justifie pas encore.
 */
class PhiloWorkerFactory(
    private val argumentDao: ArgumentDao,
    private val logicSolver: LogicSolver,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DefaultPhiloReasoningWorker::class.java.name -> DefaultPhiloReasoningWorker(
            context = appContext,
            params = workerParameters,
            argumentDao = argumentDao,
            logicSolver = logicSolver,
            // Budget volontairement conservateur pour un premier branchement reel :
            // 20 etapes ou 2 minutes, le premier seuil atteint arrete la boucle.
            budget = ReasoningBudget(maxSteps = 20, maxWallMs = 120_000),
            llmClient = LlmClient(JavaHttpTransport()),
            // Repertoire prive de l'app par defaut -- JAMAIS un chemin large (sdcard,
            // depot utilisateur) sans configuration explicite future. `git status` y
            // repondra "not a git repository", ce qui est le comportement attendu et
            // sur tant qu'aucun vrai depot n'est configure.
            gitToolExecutor = GitToolExecutor(repoDir = appContext.filesDir),
            // Contrairement aux executeurs d'outils (null par defaut, discipline
            // "pas d'acces large sans config explicite"), le journal d'audit est lui-meme
            // un mecanisme de securite/tracabilite -- active par defaut, ne cree aucun
            // risque : il ne fait qu'enregistrer ce que GatedToolLoop a deja decide.
            auditLog = AuditLog(File(appContext.filesDir, "audit.jsonl")),
            // Meme discipline que auditLog : la memoire durable elle-meme ne cree aucun
            // risque (ecriture bornee au dossier prive de l'app, slug assaini) -- active
            // par defaut, contrairement aux outils qui touchent un perimetre externe.
            memoryStore = MarkdownMemoryStore(appContext),
        )
        else -> null // laisse WorkManager utiliser la DefaultWorkerFactory pour les autres classes
    }
}
