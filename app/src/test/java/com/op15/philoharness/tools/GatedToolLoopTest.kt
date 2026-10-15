package com.op15.philoharness.tools

import com.op15.philoharness.risk.Reversibility
import com.op15.philoharness.risk.RiskGate
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedToolLoopTest {

    private fun writeTool(reversibility: Reversibility = Reversibility.PARTIALLY_REVERSIBLE) =
        ToolDescriptor(name = "edit_file", requiresWritePermission = true, defaultReversibility = reversibility)

    @Test
    fun `read-only mode denies a write tool outright`() {
        val loop = GatedToolLoop(RiskGate())
        val result = loop.invoke(writeTool(), PermissionMode.READ_ONLY, modelConfidence = 0.99) { "executed" }
        assertTrue(result is ToolInvocationResult.Denied)
    }

    @Test
    fun `ask-each-time mode always requires confirmation even for a previously granted tool`() {
        val loop = GatedToolLoop(RiskGate(), grantedTools = mutableSetOf("edit_file"))
        val result = loop.invoke(writeTool(), PermissionMode.ASK_EACH_TIME, modelConfidence = 0.99) { "executed" }
        assertTrue(result is ToolInvocationResult.RequiresConfirmation)
    }

    @Test
    fun `ask-for-new-tools-only mode executes directly once the tool was already granted`() {
        val loop = GatedToolLoop(RiskGate(), grantedTools = mutableSetOf("edit_file"))
        val result = loop.invoke(
            writeTool(reversibility = Reversibility.REVERSIBLE),
            PermissionMode.ASK_FOR_NEW_TOOLS_ONLY,
            modelConfidence = 0.99,
        ) { "executed" }
        assertTrue(result is ToolInvocationResult.Success)
    }

    @Test
    fun `full access mode still blocks an irreversible tool at low confidence -- permission never bypasses the risk gate`() {
        val loop = GatedToolLoop(RiskGate())
        val result = loop.invoke(
            writeTool(reversibility = Reversibility.IRREVERSIBLE),
            PermissionMode.FULL_ACCESS,
            modelConfidence = 0.1,
        ) { "executed" }
        assertTrue("FULL_ACCESS must not bypass the risk gate for irreversible low-confidence actions", result is ToolInvocationResult.Denied)
    }
}
