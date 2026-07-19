package com.openclaw.assistant.broker

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPrivateReadApprovalRegistryV1Test {
    @Test
    fun `exact pending proposal can be approved once`() = runTest {
        val registry = AssistantPrivateReadApprovalRegistryV1()
        val ticket = registry.register(signed())!!
        val decision = async { registry.await(ticket) }
        yield()

        val snapshot = registry.snapshot(PROPOSAL)!!
        assertEquals("Jen", snapshot.arguments["query"]?.toString()?.trim('"'))
        assertEquals(CanonicalJsonV1.sha256(snapshot.arguments), snapshot.argumentsHash)
        assertTrue(registry.respond(PROPOSAL, true))
        assertTrue(decision.await())
        assertNull(registry.snapshot(PROPOSAL))
        assertFalse(registry.respond(PROPOSAL, true))
    }

    @Test
    fun `duplicate proposal id fails closed without replacing original`() = runTest {
        val registry = AssistantPrivateReadApprovalRegistryV1()
        val original = registry.register(signed())!!

        assertNull(registry.register(signed()))
        assertTrue(registry.respond(PROPOSAL, false))
        assertFalse(registry.await(original))
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `timeout and revoke all deny and remove pending state`() = runTest {
        val registry = AssistantPrivateReadApprovalRegistryV1()
        val timedOut = registry.register(signed())!!
        assertFalse(registry.await(timedOut, timeoutMs = 1L))
        assertEquals(0, registry.pendingCount())

        val revoked = registry.register(signed())!!
        val decision = async { registry.await(revoked) }
        yield()
        registry.revokeAll()
        assertFalse(decision.await())
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `low risk proposal cannot create private read approval`() {
        val registry = AssistantPrivateReadApprovalRegistryV1()
        assertNull(registry.register(signed(AssistantCapabilityV1.ANDROID_DEVICE_STATUS)))
    }

    @Test
    fun `session revoke denies only matching pending approval`() = runTest {
        val registry = AssistantPrivateReadApprovalRegistryV1()
        val ticket = registry.register(signed())!!
        val decision = async { registry.await(ticket) }
        yield()

        registry.revokeSession("agent:voice-main:voice-android-device", "paired-device")

        assertFalse(decision.await())
        assertEquals(0, registry.pendingCount())
    }

    private fun signed(
        capability: AssistantCapabilityV1 = AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
    ): SignedAssistantProposalV1 {
        val arguments = if (capability == AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH) {
            buildJsonObject { put("query", "Jen") }
        } else {
            buildJsonObject {}
        }
        return SignedAssistantProposalV1(
            AssistantProposalV1(
                proposalId = PROPOSAL,
                planId = "22222222-2222-4222-8222-222222222222",
                stepId = "33333333-3333-4333-8333-333333333333",
                capability = capability,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = "paired-device",
                voiceSessionKey = "agent:voice-main:voice-android-device",
                presenceLeaseId = "lease-1",
                issuedAtMs = 1_700_000_000_000L,
                expiresAtMs = 1_700_000_030_000L,
                idempotencyKey = "44444444-4444-4444-8444-444444444444",
                risk = capability.risk,
            ),
            signatureKeyId = "gateway-key-1",
            signatureBase64Url = "signature",
        )
    }

    private companion object {
        const val PROPOSAL = "11111111-1111-4111-8111-111111111111"
    }
}
