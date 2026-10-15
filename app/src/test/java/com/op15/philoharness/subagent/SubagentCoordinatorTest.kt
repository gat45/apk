package com.op15.philoharness.subagent

import com.op15.philoharness.tools.PermissionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentCoordinatorTest {

    @Test
    fun `spawnParallel actually runs tasks concurrently, not sequentially`() = runBlocking {
        // Bug reel corrige (TODO.md) : la methode s'appelait "spawnParallel" mais executait les
        // taches dans une boucle sequentielle. Preuve directe : 3 taches de 100ms chacune
        // doivent se terminer en ~100ms au total si elles tournent en parallele, pas ~300ms.
        var concurrentPeak = 0
        var concurrentNow = 0
        val lock = Mutex()
        val coordinator = SubagentCoordinator { spec ->
            lock.withLock { concurrentNow++; concurrentPeak = maxOf(concurrentPeak, concurrentNow) }
            delay(100)
            lock.withLock { concurrentNow-- }
            SubagentOutcome.Completed("done:${spec.id}")
        }

        val start = System.currentTimeMillis()
        val results = coordinator.spawnParallel(
            specs = (1..3).map { SubagentSpec(id = "a$it", task = "t$it", inheritedPermissionMode = PermissionMode.READ_ONLY) },
            parentPermissionMode = PermissionMode.READ_ONLY,
        )
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(3, results.size)
        assertTrue("les 3 taches devraient se chevaucher (pic observe : $concurrentPeak)", concurrentPeak > 1)
        assertTrue("execution sequentielle aurait pris ~300ms, mesure : ${elapsedMs}ms", elapsedMs < 250)
    }

    @Test
    fun `spawnParallel rejects duplicate subagent ids`() = runBlocking {
        val coordinator = SubagentCoordinator { SubagentOutcome.Completed("x") }
        try {
            coordinator.spawnParallel(
                specs = listOf(
                    SubagentSpec(id = "dup", task = "a", inheritedPermissionMode = PermissionMode.READ_ONLY),
                    SubagentSpec(id = "dup", task = "b", inheritedPermissionMode = PermissionMode.READ_ONLY),
                ),
                parentPermissionMode = PermissionMode.READ_ONLY,
            )
            org.junit.Assert.fail("devrait rejeter des ids dupliques")
        } catch (e: IllegalArgumentException) {
            // attendu
        }
    }

    @Test
    fun `spawnParallel clamps a requested permission that exceeds the parent, regardless of what the spec claims`() = runBlocking {
        // Angle mort corrige : avant, clampPermission() existait mais n'etait jamais appelee --
        // spawnParallel faisait confiance au champ inheritedPermissionMode de la spec tel quel.
        var observedMode: PermissionMode? = null
        val coordinator = SubagentCoordinator { spec ->
            observedMode = spec.inheritedPermissionMode
            SubagentOutcome.Completed("ok")
        }
        coordinator.spawnParallel(
            specs = listOf(
                SubagentSpec(id = "a", task = "t", inheritedPermissionMode = PermissionMode.FULL_ACCESS), // demande trop large
            ),
            parentPermissionMode = PermissionMode.READ_ONLY, // parent restrictif
        )
        assertEquals(
            "le mode effectif doit etre clampe au mode du parent, jamais celui demande par la spec",
            PermissionMode.READ_ONLY,
            observedMode,
        )
    }

    @Test
    fun `clampPermission never returns a mode more permissive than the parent`() {
        val coordinator = SubagentCoordinator { SubagentOutcome.Completed("") }
        assertEquals(PermissionMode.READ_ONLY, coordinator.clampPermission(PermissionMode.READ_ONLY, PermissionMode.FULL_ACCESS))
        assertEquals(PermissionMode.ASK_EACH_TIME, coordinator.clampPermission(PermissionMode.ASK_EACH_TIME, PermissionMode.FULL_ACCESS))
        assertEquals(PermissionMode.FULL_ACCESS, coordinator.clampPermission(PermissionMode.FULL_ACCESS, PermissionMode.FULL_ACCESS))
        // Un mode moins permissif que le parent reste inchange (jamais remonte artificiellement).
        assertEquals(PermissionMode.READ_ONLY, coordinator.clampPermission(PermissionMode.FULL_ACCESS, PermissionMode.READ_ONLY))
    }

    @Test
    fun `ASK_FOR_NEW_TOOLS_ONLY is treated as more permissive than ASK_EACH_TIME`() {
        // Bug reel corrige (TODO.md) : ASK_FOR_NEW_TOOLS_ONLY accorde une permission
        // PERSISTANTE des le premier appel (GatedToolLoop.grantedTools), jamais redemandee
        // ensuite -- plus permissif en autonomie cumulee qu'ASK_EACH_TIME, qui ne grante jamais
        // rien durablement. Un parent en ASK_EACH_TIME doit donc clamper un enfant demandant
        // ASK_FOR_NEW_TOOLS_ONLY vers ASK_EACH_TIME, pas le laisser passer tel quel.
        val coordinator = SubagentCoordinator { SubagentOutcome.Completed("") }
        assertEquals(
            PermissionMode.ASK_EACH_TIME,
            coordinator.clampPermission(PermissionMode.ASK_EACH_TIME, PermissionMode.ASK_FOR_NEW_TOOLS_ONLY),
        )
    }
}
