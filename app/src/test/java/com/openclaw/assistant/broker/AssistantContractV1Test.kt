package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AssistantContractV1Test {
    @Test
    fun `canonical json sorts object keys recursively and preserves array order`() {
        val value = buildJsonObject {
            put("z", JsonPrimitive(2))
            put("a", buildJsonObject {
                put("b", JsonPrimitive(true))
                put("a", JsonArray(listOf(JsonPrimitive("second"), JsonPrimitive("first"))))
            })
        }

        assertEquals(
            "{\"a\":{\"a\":[\"second\",\"first\"],\"b\":true},\"z\":2}",
            CanonicalJsonV1.encode(value),
        )
        assertEquals(
            "sha256:132802f7c6db0d43cd899b7e7f1aed4fba3701a0c678fccbf459670a9a6bc27c",
            CanonicalJsonV1.sha256(value),
        )
    }

    @Test
    fun `valid exact proposal is accepted`() {
        val fixture = Fixture()
        val signed = fixture.signedProposal()

        val result = fixture.validator.validate(signed, DEVICE_ID, SESSION_KEY)

        assertTrue(result is ProposalValidationV1.Accepted)
        assertTrue(fixture.signatureChecked)
    }

    @Test
    fun `argument mutation is rejected before execution`() {
        val fixture = Fixture()
        val original = fixture.signedProposal()
        val mutated = original.copy(
            proposal = original.proposal.copy(
                arguments = buildJsonObject {
                    put("afterEpochMs", 1_700_000_000_000L)
                    put("limit", 10)
                },
            ),
        )

        assertRejected(fixture, mutated, ProposalRejectionV1.ARGUMENT_HASH)
    }

    @Test
    fun `unexpected or incorrectly typed arguments are rejected`() {
        val fixture = Fixture()
        val unknown = fixture.signedProposal(
            arguments = buildJsonObject {
                put("afterEpochMs", 1_700_000_000_000L)
                put("unexpected", true)
            },
        )
        val stringLimit = fixture.signedProposal(
            arguments = buildJsonObject { put("limit", "2") },
        )

        assertRejected(fixture, unknown, ProposalRejectionV1.ARGUMENT_SCHEMA)
        assertRejected(fixture, stringLimit, ProposalRejectionV1.ARGUMENT_SCHEMA)
    }

    @Test
    fun `risk device session time signature and lease bindings fail closed`() {
        val fixture = Fixture()
        val valid = fixture.signedProposal()

        assertRejected(
            fixture,
            valid.copy(proposal = valid.proposal.copy(risk = AssistantRiskV1.HIGH)),
            ProposalRejectionV1.CAPABILITY_RISK,
        )
        assertRejected(fixture, valid, ProposalRejectionV1.TARGET_DEVICE, expectedDeviceId = "wrong")
        assertRejected(fixture, valid, ProposalRejectionV1.VOICE_SESSION, expectedSessionKey = "wrong")
        assertRejected(
            fixture,
            valid.copy(proposal = valid.proposal.copy(expiresAtMs = fixture.epochNow)),
            ProposalRejectionV1.PROPOSAL_TIME,
        )

        fixture.signatureValid = false
        assertRejected(fixture, valid, ProposalRejectionV1.SIGNATURE)
        fixture.signatureValid = true
        fixture.elapsedNow = 6_000L
        assertRejected(fixture, valid, ProposalRejectionV1.PRESENCE_LEASE)
    }

    @Test
    fun `calendar epoch rejects values outside javascript safe integer range`() {
        val fixture = Fixture()
        val signed = fixture.signedProposal(
            arguments = buildJsonObject {
                put("afterEpochMs", AssistantContractV1.MAX_SAFE_INTEGER + 1)
            },
        )

        assertRejected(fixture, signed, ProposalRejectionV1.ARGUMENT_SCHEMA)
    }

    @Test
    fun `calendar create is high risk with a bounded exact schedule`() {
        val fixture = Fixture()
        val arguments = buildJsonObject {
            put("title", "Dentist")
            put("startEpochMs", fixture.epochNow + 60_000L)
            put("endEpochMs", fixture.epochNow + 3_660_000L)
            put("allDay", false)
        }
        val base = fixture.signedProposal()
        val valid = base.copy(
            proposal = base.proposal.copy(
                capability = AssistantCapabilityV1.ANDROID_CALENDAR_CREATE,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                risk = AssistantRiskV1.HIGH,
            ),
        )

        assertTrue(fixture.validator.validate(valid, DEVICE_ID, SESSION_KEY) is ProposalValidationV1.Accepted)

        val oversized = buildJsonObject {
            put("title", "Trip")
            put("startEpochMs", fixture.epochNow + 60_000L)
            put("endEpochMs", fixture.epochNow + 32L * 24L * 60L * 60L * 1_000L)
        }
        assertRejected(
            fixture,
            valid.copy(
                proposal = valid.proposal.copy(
                    arguments = oversized,
                    argumentsHash = CanonicalJsonV1.sha256(oversized),
                ),
            ),
            ProposalRejectionV1.ARGUMENT_SCHEMA,
        )
    }

    @Test
    fun `pinned Ed25519 verifier accepts only exact canonical signature and key`() {
        val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        val publicKey = privateKey.generatePublicKey().encoded
        val verifier = PinnedEd25519ProposalSignatureVerifierV1(mapOf("gateway-key-1" to publicKey))
        val payload = "proposal".toByteArray()
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(payload, 0, payload.size)
        }
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())

        assertTrue(verifier.verify(payload, "gateway-key-1", signature))
        assertFalse(verifier.verify("mutated".toByteArray(), "gateway-key-1", signature))
        assertFalse(verifier.verify(payload, "unknown-key", signature))
        assertFalse(verifier.verify(payload, "gateway-key-1", "$signature="))
        assertFalse(verifier.verify(payload, "gateway-key-1", "not-base64!"))
    }

    private fun assertRejected(
        fixture: Fixture,
        signed: SignedAssistantProposalV1,
        expected: ProposalRejectionV1,
        expectedDeviceId: String = DEVICE_ID,
        expectedSessionKey: String = SESSION_KEY,
    ) {
        val result = fixture.validator.validate(signed, expectedDeviceId, expectedSessionKey)
        assertEquals(expected, (result as ProposalValidationV1.Rejected).reason)
    }

    private class Fixture {
        var elapsedNow = 1_000L
        var epochNow = 1_700_000_000_000L
        var signatureValid = true
        var signatureChecked = false
        private val leases = PresenceLeaseManager(
            nowElapsedMs = { elapsedNow },
            newLeaseId = { LEASE_ID },
            ttlMs = 5_000L,
            renewWithinMs = 1_000L,
        )
        private val lease = leases.issue(SESSION_KEY, DEVICE_ID)
        val validator = AssistantProposalValidatorV1(
            presenceLeases = leases,
            signatureVerifier = ProposalSignatureVerifierV1 { payload, keyId, signature ->
                signatureChecked = true
                payload.isNotEmpty() && keyId == "gateway-key-1" && signature == "signature" && signatureValid
            },
            nowEpochMs = { epochNow },
        )

        fun signedProposal(
            arguments: kotlinx.serialization.json.JsonObject = buildJsonObject {
                put("afterEpochMs", 1_700_000_000_000L)
                put("limit", 2)
            },
        ): SignedAssistantProposalV1 {
            val proposal = AssistantProposalV1(
                proposalId = "11111111-1111-4111-8111-111111111111",
                planId = "22222222-2222-4222-8222-222222222222",
                stepId = "33333333-3333-4333-8333-333333333333",
                capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = DEVICE_ID,
                voiceSessionKey = SESSION_KEY,
                presenceLeaseId = lease.leaseId,
                issuedAtMs = epochNow - 1_000L,
                expiresAtMs = epochNow + 30_000L,
                idempotencyKey = "44444444-4444-4444-8444-444444444444",
                risk = AssistantRiskV1.MEDIUM,
            )
            return SignedAssistantProposalV1(
                proposal = proposal,
                signatureKeyId = "gateway-key-1",
                signatureBase64Url = "signature",
            )
        }
    }

    companion object {
        private const val SESSION_KEY = "agent:voice-main:voice-android-device"
        private const val DEVICE_ID = "paired-device"
        private const val LEASE_ID = "lease-1"
    }
}
