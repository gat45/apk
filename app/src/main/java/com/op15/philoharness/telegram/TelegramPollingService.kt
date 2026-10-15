package com.op15.philoharness.telegram

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.control.DefaultPhiloReasoningWorker
import com.op15.philoharness.control.PhiloReasoningWorker
import com.op15.philoharness.control.ReasoningPhase
import com.op15.philoharness.llm.JavaHttpTransport
import com.op15.philoharness.ui.PhiloHarnessApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Relie TelegramClient a la meme boucle reelle que MainActivity.sendMessage()
 * (insert Room -> enqueue WorkManager -> attend SUCCEEDED -> repond) --
 * capacite inspiree du concept Telegram de orailnoor/private-agent, aucune
 * UI de configuration cablee (le token doit etre fourni explicitement via
 * `start(context, token)`, jamais lu automatiquement d'une source cachee).
 *
 * PAS demarre automatiquement par l'application -- un appelant explicite
 * (future UI de parametres, cf. TODO.md) doit fournir le token du bot avant
 * que ce service ne fasse quoi que ce soit. Non teste sur device (ecrit en
 * fin de session, cf. DEVICE_TEST_CHECKLIST.md).
 */
class TelegramPollingService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_BOT_TOKEN)
        if (token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling(token)
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun startPolling(token: String) {
        job?.cancel()
        val client = TelegramClient(JavaHttpTransport(), token)
        val dao = (application as PhiloHarnessApplication).database.argumentDao()
        val workManager = WorkManager.getInstance(this)
        job = scope.launch {
            var offset: Long? = null
            while (true) {
                when (val result = client.getUpdates(offset, timeoutSeconds = 30)) {
                    is TelegramResult.Success<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val messages = result.value as List<TelegramMessage>
                        for (msg in messages) {
                            offset = msg.updateId
                            handleIncoming(client, dao, workManager, msg.chatId, msg.text)
                        }
                    }
                    is TelegramResult.Error -> {
                        android.util.Log.w("TelegramPolling", "getUpdates: ${result.message}")
                        kotlinx.coroutines.delay(5_000)
                    }
                }
            }
        }
    }

    /** Meme sequence que MainActivity.sendMessage() : insert -> enqueue -> attendre -> repondre. */
    private suspend fun handleIncoming(
        client: TelegramClient,
        dao: com.op15.philoharness.arguments.ArgumentDao,
        workManager: WorkManager,
        chatId: Long,
        text: String,
    ) {
        val argumentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertArgument(
            ArgumentEntity(
                id = argumentId,
                questionOrigine = text,
                texteOriginal = text,
                definitionsJson = "{}",
                premissesExplicitesJson = "[]",
                premissesImplicitesJson = "[]",
                conclusion = "",
                typeRaisonnement = ReasoningType.DEDUCTIF,
                hypothesesJson = "[]",
                limitesFormalisation = null,
                statut = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
                niveauConfianceJustification = "",
                createdAtEpochMs = now,
                validFrom = now,
                reasoningPhase = ReasoningPhase.NEEDS_REASONING.name,
            ),
        )
        val request = OneTimeWorkRequestBuilder<DefaultPhiloReasoningWorker>()
            .setInputData(workDataOf(PhiloReasoningWorker.KEY_ARGUMENT_ID to argumentId))
            .build()
        workManager.enqueueUniqueWork(argumentId, ExistingWorkPolicy.KEEP, request)

        // Attend la fin du Worker (succes/echec/annulation) avant de repondre sur Telegram --
        // pas de polling actif cote UI ici, juste la premiere valeur terminale du Flow.
        val finalState = workManager.getWorkInfoByIdFlow(request.id)
            .first { it == null || it.state.isFinished }
        if (finalState?.state == WorkInfo.State.SUCCEEDED) {
            val conclusion = dao.getArgument(argumentId)?.conclusion
            client.sendMessage(chatId, conclusion?.ifBlank { null } ?: "Je ne peux pas encore repondre a cette question.")
        } else {
            client.sendMessage(chatId, "Echec de l'analyse.")
        }
    }

    companion object {
        private const val EXTRA_BOT_TOKEN = "bot_token"

        fun start(context: android.content.Context, botToken: String) {
            val intent = Intent(context, TelegramPollingService::class.java)
                .putExtra(EXTRA_BOT_TOKEN, botToken)
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TelegramPollingService::class.java))
        }
    }
}
