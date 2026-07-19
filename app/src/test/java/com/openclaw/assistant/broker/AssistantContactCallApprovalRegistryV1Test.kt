package com.openclaw.assistant.broker

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContactCallApprovalRegistryV1Test {
    @Test
    fun `approval is one shot and keeps resolved recipient process local`() = runTest {
        val registry = AssistantContactCallApprovalRegistryV1()
        val prepared = prepared()
        val ticket = registry.register(prepared)!!

        assertNull(registry.register(prepared))
        assertEquals("Jen Thorndale", registry.snapshot(PROPOSAL)?.displayName)
        assertEquals("+1 250 555 0100", registry.snapshot(PROPOSAL)?.phoneNumber)
        val decision = async { registry.await(ticket) }
        assertTrue(registry.respond(PROPOSAL, true))
        assertTrue(decision.await())
        assertFalse(registry.respond(PROPOSAL, true))
        assertNull(registry.snapshot(PROPOSAL))
    }

    @Test
    fun `revocation denies pending call approval`() = runTest {
        val registry = AssistantContactCallApprovalRegistryV1()
        val ticket = registry.register(prepared())!!

        val decision = async { registry.await(ticket) }
        registry.revokeAll()

        assertFalse(decision.await())
        assertEquals(0, registry.pendingCount())
    }

    private fun prepared(): AssistantContactCallPreparationV1.Ready {
        val arguments = buildJsonObject { put("query", "Jen") }
        return AssistantContactCallPreparationV1.Ready(
            proposal = AssistantProposalV1(
                proposalId = PROPOSAL,
                planId = "22222222-2222-4222-8222-222222222222",
                stepId = "33333333-3333-4333-8333-333333333333",
                capability = AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = "paired-device",
                voiceSessionKey = "agent:voice-main:voice-android-device",
                presenceLeaseId = "lease-1",
                issuedAtMs = 1_700_000_000_000L,
                expiresAtMs = 1_700_000_030_000L,
                idempotencyKey = "44444444-4444-4444-8444-444444444444",
                risk = AssistantRiskV1.HIGH,
            ),
            target = AndroidContactCallTargetV1("Jen Thorndale", "+1 250 555 0100"),
        )
    }

    private companion object {
        const val PROPOSAL = "11111111-1111-4111-8111-111111111111"
    }
}
