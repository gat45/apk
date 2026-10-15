package com.op15.philoharness.risk

import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGateTest {

    private val gate = RiskGate()

    @Test
    fun `reversible actions are always allowed regardless of confidence`() {
        val verdict = gate.assess(ProposedAction("edit scratch file", Reversibility.REVERSIBLE, modelConfidence = 0.0))
        assertTrue(verdict is RiskVerdict.Allow)
    }

    @Test
    fun `irreversible action always requires human confirmation even at high confidence`() {
        // Regle explicite (SeerGuard-inspired, simplifiee) : jamais d'execution automatique
        // d'une action irreversible, quelle que soit la confiance du modele.
        val verdict = gate.assess(ProposedAction("delete remote branch", Reversibility.IRREVERSIBLE, modelConfidence = 0.99))
        assertTrue(verdict is RiskVerdict.RequireConfirmation)
    }

    @Test
    fun `irreversible action with low confidence is blocked outright, not just flagged`() {
        val verdict = gate.assess(ProposedAction("delete remote branch", Reversibility.IRREVERSIBLE, modelConfidence = 0.3))
        assertTrue(verdict is RiskVerdict.Block)
    }

    @Test
    fun `partially reversible action above threshold is allowed`() {
        val verdict = gate.assess(ProposedAction("overwrite local file", Reversibility.PARTIALLY_REVERSIBLE, modelConfidence = 0.8))
        assertTrue(verdict is RiskVerdict.Allow)
    }

    @Test
    fun `partially reversible action below threshold requires confirmation, not an outright block`() {
        val verdict = gate.assess(ProposedAction("overwrite local file", Reversibility.PARTIALLY_REVERSIBLE, modelConfidence = 0.3))
        assertTrue(verdict is RiskVerdict.RequireConfirmation)
    }
}
