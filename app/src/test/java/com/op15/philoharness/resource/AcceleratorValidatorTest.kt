package com.op15.philoharness.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratorValidatorTest {

    private val validator = AcceleratorValidator(minAgreementRatio = 0.90)

    private fun agreeingTask(): ReferenceTask = object : ReferenceTask {
        override fun run(backend: Backend): FloatArray = floatArrayOf(1.0f, 2.0f, 3.0f)
    }

    private fun divergingTask(): ReferenceTask = object : ReferenceTask {
        override fun run(backend: Backend): FloatArray =
            if (backend == Backend.CPU) floatArrayOf(1.0f, 2.0f, 3.0f) else floatArrayOf(9.0f, 9.0f, 9.0f)
    }

    @Test
    fun `CPU is always considered the reference, trivially validated`() {
        val result = validator.validate(Backend.CPU, listOf(agreeingTask()), 0L, 10L)
        assertTrue(result is BackendValidation.Validated)
    }

    @Test
    fun `backend agreeing with CPU on all reference tasks is validated`() {
        val tasks = List(5) { agreeingTask() }
        val result = validator.validate(Backend.HEXAGON_NPU, tasks, 0L, 10L)
        assertTrue("backend should be validated when it agrees with CPU", result is BackendValidation.Validated)
    }

    @Test
    fun `backend diverging from CPU is rejected even though it initializes and runs`() {
        // Reproduit le scenario documente par Bram : un backend GPU qui "rapportait un succes
        // en renvoyant du n'importe quoi" -- l'execution ne leve aucune exception, le resultat diverge simplement.
        val tasks = List(5) { divergingTask() }
        val result = validator.validate(Backend.VULKAN_GPU, tasks, 0L, 10L)
        assertTrue("backend diverging from CPU reference must be rejected, not validated", result is BackendValidation.Rejected)
    }

    @Test
    fun `empty reference task list is rejected rather than silently validated`() {
        val result = validator.validate(Backend.HEXAGON_NPU, emptyList(), 0L, 10L)
        assertTrue(result is BackendValidation.Rejected)
    }

    @Test
    fun `partial agreement below threshold is rejected with the exact agreement ratio reported`() {
        // 2 taches qui s'accordent, 3 qui divergent -> 40% d'accord, sous le seuil de 90%.
        val tasks = List(2) { agreeingTask() } + List(3) { divergingTask() }
        val result = validator.validate(Backend.VULKAN_GPU, tasks, 0L, 10L)
        require(result is BackendValidation.Rejected)
        assertEquals(0.4, result.agreementRatio!!, 0.001)
    }
}
