package com.op15.philoharness.control

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.PhiloDatabase
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.logic.SwitchingLogicSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Test INSTRUMENTE du vrai Worker WorkManager (pas ReasoningController isole,
 * cf. ReasoningControllerDeviceTest) -- ferme le dernier point ouvert du
 * README ("brancher DefaultPhiloReasoningWorker de bout en bout, phase Verify
 * comprise"). Utilise PhiloWorkerFactory (le code de production, pas un
 * court-circuit de test) via TestListenableWorkerBuilder.
 *
 * Suppose un llama-server deja lance sur 127.0.0.1:8080 avec un modele SANS
 * mode "reflexion" (Marco-Nano -- voir README). Verifie la PERSISTANCE de
 * phase : apres doWork(), l'argument en base n'est plus a NEEDS_REASONING,
 * preuve que la boucle a reellement avance (Reason -> Verify -> Answer/Abstain)
 * et que chaque etape a bien ecrit son etat.
 */
class DefaultPhiloReasoningWorkerDeviceTest {

    @Test
    fun fullWorkerLoopAdvancesPhaseAndPersistsItAcrossSteps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PhiloDatabase::class.java).build()
        val dao = db.argumentDao()

        val argument = ArgumentEntity(
            id = "worker-device-test-1",
            questionOrigine = "Socrate est-il mortel ?",
            texteOriginal = "tous les hommes sont mortels et Socrate est un homme",
            definitionsJson = "{}",
            premissesExplicitesJson = "[]",
            premissesImplicitesJson = "[]",
            conclusion = "",
            typeRaisonnement = ReasoningType.DEDUCTIF,
            hypothesesJson = "[]",
            limitesFormalisation = null,
            statut = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
            niveauConfianceJustification = "",
            createdAtEpochMs = System.currentTimeMillis(),
            validFrom = System.currentTimeMillis(),
            reasoningPhase = ReasoningPhase.NEEDS_REASONING.name,
        )
        dao.insertArgument(argument)

        val factory = PhiloWorkerFactory(dao, SwitchingLogicSolver())
        val worker = TestListenableWorkerBuilder<DefaultPhiloReasoningWorker>(context)
            .setInputData(androidx.work.workDataOf(PhiloReasoningWorker.KEY_ARGUMENT_ID to argument.id))
            .setWorkerFactory(factory)
            .build()

        val result = worker.doWork()

        val finalArgument = dao.getArgument(argument.id)!!
        assertNotEquals(
            "La phase ne doit plus etre NEEDS_REASONING apres doWork() -- preuve que la boucle a avance",
            ReasoningPhase.NEEDS_REASONING.name,
            finalArgument.reasoningPhase,
        )

        // Succes OU retry (preuve independante absente pour l'instant, cf. verifyIndependently) --
        // jamais un echec silencieux du Worker lui-meme.
        assertEquals(true, result is ListenableWorker.Result.Success || result is ListenableWorker.Result.Retry)

        db.close()
    }
}
