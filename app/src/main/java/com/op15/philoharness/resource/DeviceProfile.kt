package com.op15.philoharness.resource

/**
 * Profil mesure de l'appareil, utilise pour generer un plan d'execution.
 * Patron directement inspire de KuRue/bram (README : "Hardware-aware planning:
 * candidate plans account for model weights, KV cache, compute buffers, backend
 * repacking, current memory pressure, and a safety reserve") -- verifie reel,
 * licence encore non choisie sur ce depot a la date de l'audit : s'inspirer du
 * DESIGN est libre, copier le CODE ne l'est pas sans clarification.
 */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCoreCount: Int,
    val vulkanSupported: Boolean,
    val hexagonNpuPresent: Boolean,
    val thermalStatus: ThermalStatus,
)

enum class ThermalStatus { NOMINAL, LIGHT, MODERATE, SEVERE, CRITICAL }

enum class Backend { CPU, VULKAN_GPU, HEXAGON_NPU }

/**
 * Statut de validation d'un backend. "Working" n'est jamais suppose a partir de
 * la seule initialisation reussie -- il faut reproduire la reference CPU
 * (meme principe que ggml-hexagon dans ce projet : AGENTS.md, "preuve backend
 * exigee"). Bram a lui-meme attrape un backend GPU qui "reportait un succes en
 * renvoyant du n'importe quoi" (README) -- l'init seule ne prouve rien.
 */
sealed interface BackendValidation {
    data class Validated(val agreementRatio: Double, val speedupVsCpu: Double) : BackendValidation
    data class Rejected(val reason: String, val agreementRatio: Double?) : BackendValidation
    object NotYetTested : BackendValidation
}

data class ExecutionPlan(
    val chosenBackend: Backend,
    val contextTokens: Int,
    val reason: String,
    /** true si le plan degrade deliberement (stockage-assiste, backend plus lent mais sur) plutot que d'echouer. */
    val degraded: Boolean,
)
