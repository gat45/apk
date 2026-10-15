package com.op15.philoharness

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test de sanite pure (aucune lib native chargee) -- sert a isoler si un crash
 * instrumente observe ailleurs (Z3JniSolverDeviceTest) est specifique a
 * libz3_jni_bridge.so ou s'il s'agit d'une instabilite plus generale du
 * device/ROM pour TOUT test instrumente.
 */
class SanityDeviceTest {
    @Test
    fun trivialAssertionPassesWithoutLoadingAnyNativeLibrary() {
        assertEquals(2, 1 + 1)
    }

    /**
     * Experience de controle : aucune lib native chargee, mais un delai
     * artificiel laisse le temps au thread JIT en arriere-plan de compiler du
     * bytecode avant la fin du test -- teste si le crash observe avec Z3 est en
     * realite un bug JIT/ART declenche par le TEMPS ecoule au demarrage, pas
     * par Z3 lui-meme.
     */
    @Test
    fun delayedAssertionWithoutAnyNativeLibraryStillGivesJitTimeToRun() {
        Thread.sleep(800)
        assertEquals(2, 1 + 1)
    }
}
