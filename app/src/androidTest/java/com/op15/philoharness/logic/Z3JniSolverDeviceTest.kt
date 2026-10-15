package com.op15.philoharness.logic

import android.os.Debug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test INSTRUMENTE (tourne sur device reel, pas en JVM pure) -- etape 2 du
 * spike technique (plan tache 1). EXECUTE ET VALIDE sur OnePlus 15 (CPH2747,
 * arm64-v8a, Android SDK 36, JIT dans son etat normal) : 5/5 tests passent,
 * y compris le test de charge ci-dessous. Voir le README du depot pour le
 * post-mortem complet (bug de ref-counting cote appelant, corrige dans
 * z3_jni_bridge.cpp via Z3_ast_vector_inc_ref/dec_ref).
 *
 * Trois cas couvrant le minimum demande par le plan ("tester sat/unsat sur 3
 * formules SMT-LIB2 simples") + un test de charge (robustesse, demande
 * explicitement apres la resolution du bug initial) :
 */
class Z3JniSolverDeviceTest {

    // Noms de methode sans espaces : D8 (dexer androidTest, contrairement au JVM unit test
    // runner) refuse les SimpleName avec espaces avant DEX version 040.
    @Test
    fun satisfiableFormulaReturnsSatisfiable() {
        val solver = Z3JniSolver()
        val result = solver.solve(
            """
            (declare-const x Int)
            (assert (> x 0))
            (assert (< x 10))
            """.trimIndent(),
        )
        assertEquals(SolverVerdict.SATISFIABLE, result.verdict)
    }

    @Test
    fun unsatisfiableFormulaReturnsInsatisfiable() {
        val solver = Z3JniSolver()
        val result = solver.solve(
            """
            (declare-const x Int)
            (assert (> x 10))
            (assert (< x 5))
            """.trimIndent(),
        )
        assertEquals(SolverVerdict.INSATISFIABLE, result.verdict)
    }

    @Test
    fun malformedFormulaReturnsInconnuNeverAFabricatedVerdict() {
        val solver = Z3JniSolver()
        val result = solver.solve("(this is not valid smt-lib2 at all")
        assertEquals(SolverVerdict.INCONNU, result.verdict)
    }

    /**
     * Test de charge (robustesse, point explicitement laisse ouvert apres la
     * resolution du bug initial) : appels repetes, nouveau contexte/solver a
     * chaque fois (meme pattern que nativeSolve, qui cree/detruit son contexte
     * par appel -- pas un contexte partage entre appels). Verifie :
     * (1) aucun crash sur 200 iterations, melange sat/unsat/malformee ;
     * (2) le tas natif ne croit pas sans borne (signe de fuite -- Z3_del_context
     * absent d'un chemin, ou ast_vector/solver jamais dec_ref).
     *
     * Marge large (x3) sur le seuil de croissance : le but est de detecter une
     * fuite nette, pas de regler un seuil de performance precis.
     */
    @Test
    fun repeatedCallsDoNotCrashAndDoNotLeakNativeMemoryUnbounded() {
        val solver = Z3JniSolver()
        val formulas = listOf(
            "(declare-const x Int)\n(assert (> x 0))\n(assert (< x 10))", // sat
            "(declare-const x Int)\n(assert (> x 10))\n(assert (< x 5))", // unsat
            "(this is not valid smt-lib2 at all", // malformee
        )

        // Laisse le premier appel de chaque "forme" stabiliser les structures
        // internes de Z3 (caches, pools) avant de mesurer la croissance.
        repeat(5) { i -> solver.solve(formulas[i % formulas.size]) }
        System.gc()
        val nativeHeapBefore = Debug.getNativeHeapAllocatedSize()

        val iterations = 200
        for (i in 0 until iterations) {
            val result = solver.solve(formulas[i % formulas.size])
            assertTrue(
                "iteration $i: verdict inattendu ${result.verdict}",
                result.verdict == SolverVerdict.SATISFIABLE ||
                    result.verdict == SolverVerdict.INSATISFIABLE ||
                    result.verdict == SolverVerdict.INCONNU,
            )
        }

        System.gc()
        val nativeHeapAfter = Debug.getNativeHeapAllocatedSize()
        val growth = nativeHeapAfter - nativeHeapBefore
        // Seuil large et documente : 5 Mo pour 200 iterations (~25 Ko/appel) --
        // tolere la fragmentation/les caches internes de Z3, mais une vraie
        // fuite de contexte/solver (quelques Mo par appel) le depasserait vite.
        val maxAcceptableGrowthBytes = 5L * 1024 * 1024
        assertTrue(
            "tas natif : +$growth octets sur $iterations appels (seuil ${maxAcceptableGrowthBytes}) -- fuite possible",
            growth < maxAcceptableGrowthBytes,
        )
    }
}
