package com.openclaw.assistant.node

import com.openclaw.assistant.broker.AndroidContactMatchV1
import com.openclaw.assistant.broker.AndroidContactCallLaunchV1
import com.openclaw.assistant.broker.AndroidContactCallResolutionV1
import com.openclaw.assistant.broker.AndroidContactCallResolverV1
import com.openclaw.assistant.broker.AndroidContactCallTargetV1
import com.openclaw.assistant.broker.AndroidContactCallLauncherV1
import com.openclaw.assistant.broker.AndroidContactsSearchReadV1
import com.openclaw.assistant.broker.AndroidContactsSearchReaderV1
import com.openclaw.assistant.broker.AndroidDeviceStatusReaderV1
import com.openclaw.assistant.broker.AndroidDeviceStatusSummaryV1
import com.openclaw.assistant.broker.AssistantCapabilityExecutorV1
import com.openclaw.assistant.broker.AssistantCapabilityV1
import com.openclaw.assistant.broker.AssistantPrivateReadGrantManagerV1
import com.openclaw.assistant.broker.AssistantProposalV1
import com.openclaw.assistant.broker.AssistantProposalValidatorV1
import com.openclaw.assistant.broker.AssistantRiskV1
import com.openclaw.assistant.broker.AssistantWireCodecV1
import com.openclaw.assistant.broker.CanonicalJsonV1
import com.openclaw.assistant.broker.PresenceLeaseManager
import com.openclaw.assistant.broker.ProposalSignatureVerifierV1
import com.openclaw.assistant.broker.SignedAssistantProposalV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantNodeCommandHandlerV1Test {
    @Test
    fun `presence exposes only contract and active lease id`() {
        val fixture = Fixture()

        val result = fixture.handler.handlePresence()

        assertTrue(result.ok)
        assertEquals(
            setOf("contractVersion", "presenceLeaseId"),
            Json.parseToJsonElement(result.payloadJson!!).jsonObject.keys,
        )
        assertFalse(result.payloadJson!!.contains(SESSION))
        assertFalse(result.payloadJson!!.contains(DEVICE))
    }

    @Test
    fun `locked device rejects presence and execution before native access`() = runTest {
        val fixture = Fixture()
        fixture.unlocked = false

        val presence = fixture.handler.handlePresence()
        val execution = fixture.handler.handleExecute(fixture.signedJson(AssistantCapabilityV1.ANDROID_DEVICE_STATUS))

        assertFalse(presence.ok)
        assertEquals("UNLOCKED_PRESENCE_REQUIRED", presence.error?.code)
        assertFalse(execution.ok)
        assertEquals(0, fixture.statusReads)
    }

    @Test
    fun `strict decoder rejects unknown envelope fields`() = runTest {
        val fixture = Fixture()
        val malformed = fixture.signedJson(AssistantCapabilityV1.ANDROID_DEVICE_STATUS)
            .dropLast(1) + ",\"extra\":true}"

        val result = fixture.handler.handleExecute(malformed)

        assertFalse(result.ok)
        assertEquals("SIGNED_PROPOSAL_SCHEMA", result.error?.code)
        assertEquals(0, fixture.statusReads)
    }

    @Test
    fun `status execution returns only a privacy minimized receipt`() = runTest {
        val fixture = Fixture()

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_DEVICE_STATUS),
        )

        assertTrue(result.ok)
        val payload = Json.parseToJsonElement(result.payloadJson!!).jsonObject
        assertEquals("COMPLETED", payload["status"]?.jsonPrimitive?.content)
        assertEquals(1, fixture.statusReads)
        assertNull(payload["privateResult"])
    }

    @Test
    fun `contact private result is delivered locally and never serialized`() = runTest {
        val fixture = Fixture(grantContacts = true, sinkAccepts = true)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.contactsReads)
        assertEquals(1, fixture.privateDeliveries)
        assertEquals(SESSION, fixture.deliveredSessionKey)
        assertEquals(DEVICE, fixture.deliveredDeviceId)
        assertFalse(result.payloadJson!!.contains("Jen Thorndale"))
        assertFalse(result.payloadJson!!.contains("+1 250 555 0100"))
        assertEquals(
            "COMPLETED",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `unavailable private sink fails without serializing private data`() = runTest {
        val fixture = Fixture(grantContacts = true, sinkAccepts = false)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH),
        )

        assertTrue(result.ok)
        assertFalse(result.payloadJson!!.contains("Jen Thorndale"))
        val payload = Json.parseToJsonElement(result.payloadJson!!).jsonObject
        assertEquals("FAILED", payload["status"]?.jsonPrimitive?.content)
        assertEquals(
            "PRIVATE_RESULT_DELIVERY_UNAVAILABLE",
            payload["errorCode"]?.jsonPrimitive?.content,
        )
        assertNull(payload["resultSummary"])
    }

    @Test
    fun `approved private read is granted once and reusable in the same session`() = runTest {
        val fixture = Fixture(approvalAllowed = true, sinkAccepts = true)
        val request = fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH)

        val first = fixture.handler.handleExecute(request)
        val second = fixture.handler.handleExecute(request)

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(1, fixture.approvalRequests)
        assertEquals(2, fixture.contactsReads)
        assertEquals(2, fixture.privateDeliveries)
    }

    @Test
    fun `denied private read never reaches contacts provider`() = runTest {
        val fixture = Fixture(approvalAllowed = false)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.approvalRequests)
        assertEquals(0, fixture.contactsReads)
        assertEquals(
            "PRIVATE_READ_APPROVAL_DENIED",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `lock during approval fails closed before grant or provider read`() = runTest {
        val fixture = Fixture(approvalAllowed = true, lockDuringApproval = true)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH),
        )

        assertTrue(result.ok)
        assertEquals(
            "UNLOCKED_PRESENCE_REQUIRED",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
        assertEquals(0, fixture.contactsReads)
        assertFalse(fixture.hasContactsGrant())
    }

    @Test
    fun `lock after provider read drops private result before local delivery`() = runTest {
        val fixture = Fixture(
            grantContacts = true,
            sinkAccepts = true,
            lockOnContactsRead = true,
        )

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.contactsReads)
        assertEquals(0, fixture.privateDeliveries)
        assertEquals(
            "PRIVATE_RESULT_DELIVERY_UNAVAILABLE",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
        assertFalse(result.payloadJson!!.contains("Jen Thorndale"))
    }

    @Test
    fun `approved contact call resolves and launches locally without serializing recipient`() = runTest {
        val fixture = Fixture(callApprovalAllowed = true)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.callResolutions)
        assertEquals(1, fixture.callApprovalRequests)
        assertEquals(1, fixture.callLaunches)
        assertFalse(result.payloadJson!!.contains("Jen Thorndale"))
        assertFalse(result.payloadJson!!.contains("+1 250 555 0100"))
        val payload = Json.parseToJsonElement(result.payloadJson!!).jsonObject
        assertEquals("COMPLETED", payload["status"]?.jsonPrimitive?.content)
        assertEquals("true", payload["resultSummary"]?.jsonObject?.get("placedCall")?.toString())
        assertEquals("false", payload["resultSummary"]?.jsonObject?.get("requiresTap")?.toString())
    }

    @Test
    fun `denied contact call never launches and never serializes recipient`() = runTest {
        val fixture = Fixture(callApprovalAllowed = false)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.callResolutions)
        assertEquals(1, fixture.callApprovalRequests)
        assertEquals(0, fixture.callLaunches)
        assertFalse(result.payloadJson!!.contains("Jen Thorndale"))
        assertEquals(
            "CALL_APPROVAL_DENIED",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `ambiguous contact call fails before approval or launch`() = runTest {
        val fixture = Fixture(ambiguousCall = true, callApprovalAllowed = true)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT),
        )

        assertTrue(result.ok)
        assertEquals(1, fixture.callResolutions)
        assertEquals(0, fixture.callApprovalRequests)
        assertEquals(0, fixture.callLaunches)
        assertEquals(
            "CONTACT_AMBIGUOUS",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `lock during call approval fails closed before launch`() = runTest {
        val fixture = Fixture(callApprovalAllowed = true, lockDuringCallApproval = true)

        val result = fixture.handler.handleExecute(
            fixture.signedJson(AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT),
        )

        assertTrue(result.ok)
        assertEquals(0, fixture.callLaunches)
        assertEquals(
            "UNLOCKED_PRESENCE_REQUIRED",
            Json.parseToJsonElement(result.payloadJson!!).jsonObject["errorCode"]?.jsonPrimitive?.content,
        )
    }

    private class Fixture(
        grantContacts: Boolean = false,
        private val sinkAccepts: Boolean = false,
        private val approvalAllowed: Boolean = false,
        private val lockDuringApproval: Boolean = false,
        private val lockOnContactsRead: Boolean = false,
        private val callApprovalAllowed: Boolean = false,
        private val lockDuringCallApproval: Boolean = false,
        private val ambiguousCall: Boolean = false,
    ) {
        var unlocked = true
        var statusReads = 0
        var contactsReads = 0
        var privateDeliveries = 0
        var deliveredSessionKey: String? = null
        var deliveredDeviceId: String? = null
        var approvalRequests = 0
        var callResolutions = 0
        var callApprovalRequests = 0
        var callLaunches = 0
        private var epochNow = NOW
        private var elapsedNow = 1_000L
        val leases = PresenceLeaseManager(
            nowElapsedMs = { elapsedNow },
            newLeaseId = { LEASE },
            ttlMs = 5_000L,
            renewWithinMs = 1_000L,
        )
        private val grantManager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { elapsedNow })
        private val lease = leases.issue(SESSION, DEVICE)
        private val executor = AssistantCapabilityExecutorV1(
            validator = AssistantProposalValidatorV1(
                presenceLeases = leases,
                signatureVerifier = ProposalSignatureVerifierV1 { _, key, signature ->
                    key == "gateway-key-1" && signature == "signature"
                },
                nowEpochMs = { epochNow },
            ),
            deviceStatusReader = AndroidDeviceStatusReaderV1 {
                statusReads += 1
                AndroidDeviceStatusSummaryV1(82, true, true)
            },
            contactsSearchReader = AndroidContactsSearchReaderV1 { _, _ ->
                contactsReads += 1
                if (lockOnContactsRead) unlocked = false
                AndroidContactsSearchReadV1.Success(
                    listOf(AndroidContactMatchV1("101", "Jen Thorndale", "+1 250 555 0100")),
                    truncated = false,
                )
            },
            privateReadAuthorizer = grantManager,
            contactCallResolver = AndroidContactCallResolverV1 {
                callResolutions += 1
                if (ambiguousCall) {
                    AndroidContactCallResolutionV1.Ambiguous
                } else {
                    AndroidContactCallResolutionV1.Ready(
                        AndroidContactCallTargetV1("Jen Thorndale", "+1 250 555 0100"),
                    )
                }
            },
            contactCallLauncher = AndroidContactCallLauncherV1 {
                callLaunches += 1
                AndroidContactCallLaunchV1.Launched(placedCall = true, requiresTap = false)
            },
            nowEpochMs = { epochNow },
            newReceiptId = { RECEIPT },
        )
        val handler = AssistantNodeCommandHandlerV1(
            presenceLeases = leases,
            executor = executor,
            privateReadGrants = grantManager,
            privateReadApprovalGate = AssistantPrivateReadApprovalGateV1 {
                approvalRequests += 1
                if (lockDuringApproval) unlocked = false
                approvalAllowed
            },
            contactCallApprovalGate = AssistantContactCallApprovalGateV1 {
                callApprovalRequests += 1
                if (lockDuringCallApproval) unlocked = false
                callApprovalAllowed
            },
            securityGate = { unlocked },
            privateResultSink = AssistantPrivateResultSinkV1 { delivery ->
                privateDeliveries += 1
                deliveredSessionKey = delivery.voiceSessionKey
                deliveredDeviceId = delivery.targetDeviceId
                sinkAccepts
            },
        )

        init {
            if (grantContacts) {
                grantManager.grant(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH, SESSION, DEVICE)
            }
        }

        fun signedJson(capability: AssistantCapabilityV1): String {
            val arguments = when (capability) {
                AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> buildJsonObject {
                    put("query", "Jen")
                    put("limit", 1)
                }
                AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT -> buildJsonObject {
                    put("query", "Jen")
                }
                else -> buildJsonObject {}
            }
            val proposal = AssistantProposalV1(
                proposalId = PROPOSAL,
                planId = PLAN,
                stepId = STEP,
                capability = capability,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = DEVICE,
                voiceSessionKey = SESSION,
                presenceLeaseId = lease.leaseId,
                issuedAtMs = epochNow,
                expiresAtMs = epochNow + 30_000L,
                idempotencyKey = IDEMPOTENCY,
                risk = capability.risk,
            )
            return AssistantWireCodecV1.encodeSignedProposal(
                SignedAssistantProposalV1(proposal, "gateway-key-1", "signature"),
            )
        }

        fun hasContactsGrant(): Boolean = grantManager.isAuthorized(
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
            SESSION,
            DEVICE,
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DEVICE = "paired-device"
        const val SESSION = "agent:voice-main:voice-android-device"
        const val LEASE = "lease-1"
        const val PROPOSAL = "11111111-1111-4111-8111-111111111111"
        const val PLAN = "22222222-2222-4222-8222-222222222222"
        const val STEP = "33333333-3333-4333-8333-333333333333"
        const val IDEMPOTENCY = "44444444-4444-4444-8444-444444444444"
        const val RECEIPT = "55555555-5555-4555-8555-555555555555"
    }
}
