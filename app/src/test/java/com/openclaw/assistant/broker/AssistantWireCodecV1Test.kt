package com.openclaw.assistant.broker

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWireCodecV1Test {
    @Test
    fun `signed proposal round trips without changing typed fields`() {
        val signed = signed()

        val decoded = AssistantWireCodecV1.decodeSignedProposal(
            AssistantWireCodecV1.encodeSignedProposal(signed),
        )

        assertTrue(decoded is AssistantSignedProposalDecodeV1.Success)
        assertEquals(signed, (decoded as AssistantSignedProposalDecodeV1.Success).signed)
    }

    @Test
    fun `null malformed and oversized payloads fail closed`() {
        assertRejected(null, "SIGNED_PROPOSAL_MALFORMED")
        assertRejected("[]", "SIGNED_PROPOSAL_MALFORMED")
        assertRejected("x".repeat(16 * 1_024 + 1), "SIGNED_PROPOSAL_MALFORMED")
    }

    @Test
    fun `unknown fields and wrong primitive types are rejected`() {
        val valid = AssistantWireCodecV1.encodeSignedProposal(signed())
        assertRejected(valid.dropLast(1) + ",\"privateKey\":\"no\"}", "SIGNED_PROPOSAL_SCHEMA")
        assertRejected(
            valid.replace("\"contractVersion\":1", "\"contractVersion\":\"1\""),
            "SIGNED_PROPOSAL_SCHEMA",
        )
        assertRejected(
            valid.replace("\"proposalId\"", "\"unknown\":true,\"proposalId\""),
            "SIGNED_PROPOSAL_SCHEMA",
        )
    }

    @Test
    fun `unknown capability and risk values are rejected`() {
        val valid = AssistantWireCodecV1.encodeSignedProposal(signed())
        assertRejected(
            valid.replace("android.device.status", "android.shell.execute"),
            "SIGNED_PROPOSAL_SCHEMA",
        )
        assertRejected(valid.replace("\"LOW\"", "\"CRITICAL\""), "SIGNED_PROPOSAL_SCHEMA")
    }

    @Test
    fun `receipt encoder rejects private or capability mismatched summary fields`() {
        val contactsPrivate = receipt(
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
            buildJsonObject { put("displayName", "Jen Thorndale") },
        )
        val statusPrivate = receipt(
            AssistantCapabilityV1.ANDROID_DEVICE_STATUS,
            buildJsonObject { put("phoneNumber", "+1 250 555 0100") },
        )
        val unsupportedSummary = receipt(
            AssistantCapabilityV1.WINDOWS_FILES_READ,
            buildJsonObject { put("path", "C:/private.txt") },
        )

        assertFails { AssistantWireCodecV1.encodeReceipt(contactsPrivate) }
        assertFails { AssistantWireCodecV1.encodeReceipt(statusPrivate) }
        assertFails { AssistantWireCodecV1.encodeReceipt(unsupportedSummary) }
    }

    @Test
    fun `receipt encoder accepts only allowlisted contact summary`() {
        val encoded = AssistantWireCodecV1.encodeReceipt(
            receipt(
                AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
                buildJsonObject {
                    put("matchCount", 1)
                    put("truncated", false)
                },
            ),
        )

        assertTrue(encoded.contains("\"matchCount\":1"))
        assertTrue(!encoded.contains("Jen"))
    }

    private fun signed(): SignedAssistantProposalV1 {
        val arguments = buildJsonObject {}
        return SignedAssistantProposalV1(
            proposal = AssistantProposalV1(
                proposalId = PROPOSAL,
                planId = PLAN,
                stepId = STEP,
                capability = AssistantCapabilityV1.ANDROID_DEVICE_STATUS,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = "paired-device",
                voiceSessionKey = "agent:voice-main:voice-android-device",
                presenceLeaseId = "lease-1",
                issuedAtMs = 1_700_000_000_000L,
                expiresAtMs = 1_700_000_030_000L,
                idempotencyKey = IDEMPOTENCY,
                risk = AssistantRiskV1.LOW,
            ),
            signatureKeyId = "assistant-v1-0123456789abcdef01234567",
            signatureBase64Url = "signature",
        )
    }

    private fun receipt(
        capability: AssistantCapabilityV1,
        summary: kotlinx.serialization.json.JsonObject,
    ): AssistantReceiptV1 = AssistantReceiptV1(
        receiptId = RECEIPT,
        proposalId = PROPOSAL,
        planId = PLAN,
        stepId = STEP,
        capability = capability,
        argumentsHash = "sha256:${"0".repeat(64)}",
        targetDeviceId = "paired-device",
        idempotencyKey = IDEMPOTENCY,
        status = AssistantReceiptStatusV1.COMPLETED,
        startedAtMs = 1_700_000_000_000L,
        finishedAtMs = 1_700_000_000_100L,
        resultSummary = summary,
    )

    private fun assertRejected(value: String?, code: String) {
        val decoded = AssistantWireCodecV1.decodeSignedProposal(value)
        assertTrue(decoded is AssistantSignedProposalDecodeV1.Rejected)
        assertEquals(code, (decoded as AssistantSignedProposalDecodeV1.Rejected).code)
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching { block() }.isFailure)
    }

    private companion object {
        const val RECEIPT = "00000000-0000-4000-8000-000000000000"
        const val PROPOSAL = "11111111-1111-4111-8111-111111111111"
        const val PLAN = "22222222-2222-4222-8222-222222222222"
        const val STEP = "33333333-3333-4333-8333-333333333333"
        const val IDEMPOTENCY = "44444444-4444-4444-8444-444444444444"
    }
}
