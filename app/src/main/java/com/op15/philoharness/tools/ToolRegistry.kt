package com.op15.philoharness.tools

import com.op15.philoharness.risk.ProposedAction
import com.op15.philoharness.risk.RiskGate
import com.op15.philoharness.risk.RiskVerdict

/**
 * Modes de permission inspires de Sanuu7/AndroidHarness (verifie reel, README :
 * "four permission modes up to a full access mode that lifts every sandbox").
 * On garde 4 niveaux mais sans jamais introduire un mode qui desactive le
 * RiskGate -- "full access" chez AndroidHarness leve le bac a sable fichiers,
 * pas la verification de reversibilite, qui reste une discipline separee ici.
 */
enum class PermissionMode {
    READ_ONLY,
    ASK_EACH_TIME,
    ASK_FOR_NEW_TOOLS_ONLY,
    FULL_ACCESS,
}

data class ToolDescriptor(
    val name: String,
    val requiresWritePermission: Boolean,
    val defaultReversibility: com.op15.philoharness.risk.Reversibility,
)

sealed interface ToolInvocationResult {
    data class Success(val output: String) : ToolInvocationResult
    data class Denied(val reason: String) : ToolInvocationResult
    data class RequiresConfirmation(val reason: String) : ToolInvocationResult
}

/**
 * Boucle d'outils a permission controlee (patron AndroidHarness : "the model
 * must be able to propose an action, but the controller must verify its
 * validity before executing it" -- reformule depuis la section "intelligent
 * reactive harness" de l'audit). Le modèle propose, CETTE classe decide.
 */
class GatedToolLoop(
    private val riskGate: RiskGate,
    private var grantedTools: MutableSet<String> = mutableSetOf(),
) {
    fun invoke(
        tool: ToolDescriptor,
        mode: PermissionMode,
        modelConfidence: Double,
        execute: () -> String,
    ): ToolInvocationResult {
        if (tool.requiresWritePermission && mode == PermissionMode.READ_ONLY) {
            return ToolInvocationResult.Denied("Mode lecture seule -- outil '${tool.name}' refuse sans escalade explicite")
        }

        val alreadyGranted = tool.name in grantedTools
        val needsAsk = when (mode) {
            PermissionMode.READ_ONLY -> false // deja traite ci-dessus
            PermissionMode.ASK_EACH_TIME -> true
            PermissionMode.ASK_FOR_NEW_TOOLS_ONLY -> !alreadyGranted
            PermissionMode.FULL_ACCESS -> false
        }
        if (needsAsk) {
            return ToolInvocationResult.RequiresConfirmation("Autorisation demandee pour '${tool.name}' (mode $mode)")
        }

        // Permission accordee au niveau outil : reste a passer le RiskGate (reversibilite + confiance),
        // qui s'applique quel que soit le mode de permission -- "full access" ne le contourne jamais.
        val verdict = riskGate.assess(
            ProposedAction(
                description = "Invocation de ${tool.name}",
                reversibility = tool.defaultReversibility,
                modelConfidence = modelConfidence,
            ),
        )
        return when (verdict) {
            RiskVerdict.Allow -> {
                grantedTools.add(tool.name)
                ToolInvocationResult.Success(execute())
            }
            is RiskVerdict.RequireConfirmation -> ToolInvocationResult.RequiresConfirmation(verdict.reason)
            is RiskVerdict.Block -> ToolInvocationResult.Denied(verdict.reason)
        }
    }

    fun grant(toolName: String) {
        grantedTools.add(toolName)
    }

    fun revoke(toolName: String) {
        grantedTools.remove(toolName)
    }
}
