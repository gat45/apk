package com.op15.philoharness.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceManagerTest {

    private fun profile(
        hexagon: Boolean = true,
        vulkan: Boolean = true,
        thermal: ThermalStatus = ThermalStatus.NOMINAL,
        availableRam: Long = 3_500_000_000L,
    ) = DeviceProfile(
        totalRamBytes = 8_000_000_000L,
        availableRamBytes = availableRam,
        cpuCoreCount = 8,
        vulkanSupported = vulkan,
        hexagonNpuPresent = hexagon,
        thermalStatus = thermal,
    )

    @Test
    fun `never selects a backend that was never validated, even if it is the only accelerator present`() {
        val manager = ResourceManager(AcceleratorValidator())
        // Aucune validation enregistree pour HEXAGON_NPU ni VULKAN_GPU -- doit retomber sur CPU.
        val plan = manager.planFor(profile())
        assertEquals(Backend.CPU, plan.chosenBackend)
        assertTrue(plan.degraded)
    }

    @Test
    fun `never selects a backend explicitly rejected, falls back to CPU degraded`() {
        val manager = ResourceManager(AcceleratorValidator())
        manager.recordValidation(Backend.VULKAN_GPU, BackendValidation.Rejected("divergence", 0.4))
        manager.recordValidation(Backend.HEXAGON_NPU, BackendValidation.Rejected("divergence", 0.5))
        val plan = manager.planFor(profile())
        assertEquals(Backend.CPU, plan.chosenBackend)
        assertTrue(plan.degraded)
    }

    @Test
    fun `selects the first validated backend in preference order`() {
        val manager = ResourceManager(AcceleratorValidator())
        manager.recordValidation(Backend.HEXAGON_NPU, BackendValidation.Validated(agreementRatio = 0.958, speedupVsCpu = 1.7))
        val plan = manager.planFor(profile())
        assertEquals(Backend.HEXAGON_NPU, plan.chosenBackend)
        assertTrue(!plan.degraded)
    }

    @Test
    fun `severe thermal status forces CPU fallback with reduced context regardless of validated backends`() {
        val manager = ResourceManager(AcceleratorValidator())
        manager.recordValidation(Backend.HEXAGON_NPU, BackendValidation.Validated(agreementRatio = 0.958, speedupVsCpu = 1.7))
        val plan = manager.planFor(profile(thermal = ThermalStatus.SEVERE))
        assertEquals(Backend.CPU, plan.chosenBackend)
        assertTrue(plan.degraded)
    }

    @Test
    fun `low available RAM reduces the context window`() {
        val manager = ResourceManager(AcceleratorValidator())
        val plan = manager.planFor(profile(availableRam = 1_000_000_000L))
        assertEquals(1024, plan.contextTokens)
    }
}
