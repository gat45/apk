package com.op15.philoharness.ui

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import com.op15.philoharness.arguments.PhiloDatabase
import com.op15.philoharness.control.PhiloWorkerFactory
import com.op15.philoharness.logic.SwitchingLogicSolver

/**
 * Point d'entree de l'app. Cree la base Room en singleton applicatif --
 * suffisant pour ce squelette, une vraie app ajouterait un conteneur DI
 * (Hilt/Koin) si la complexite le justifie, ce qui n'est pas le cas ici.
 *
 * Implemente Configuration.Provider pour enregistrer PhiloWorkerFactory --
 * necessaire pour que WorkManager sache injecter argumentDao/logicSolver
 * dans DefaultPhiloReasoningWorker (sinon il tente le constructeur
 * (Context, WorkerParameters) seul et echoue).
 */
class PhiloHarnessApplication : Application(), Configuration.Provider {
    lateinit var database: PhiloDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, PhiloDatabase::class.java, "philo_harness.db")
            // Acceptable en pre-release (aucun utilisateur reel, pas de donnees a
            // preserver) -- a remplacer par une vraie Migration avant toute release.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PhiloWorkerFactory(database.argumentDao(), SwitchingLogicSolver()))
            .build()
}
