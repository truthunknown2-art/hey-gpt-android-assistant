package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCapabilityExecutorV1Test {
    @Test
    fun `valid device status proposal returns privacy minimized completed receipt`() {
        val fixture = Fixture()
        val outcome = fixture.executor.execute(fixture.signedStatus(), DEVICE_ID, SESSION_KEY)
        val receipt = outcome.receipt

        assertEquals(AssistantReceiptStatusV1.COMPLETED, receipt.status)
        assertEquals(AssistantCapabilityV1.ANDROID_DEVICE_STATUS, receipt.capability)
        assertEquals(1, fixture.readCount)
        assertEquals(82, receipt.resultSummary?.get("batteryLevelPercent")?.toString()?.toInt())
        assertEquals("true", receipt.resultSummary?.get("charging")?.toString())
        assertEquals("true", receipt.resultSummary?.get("screenInteractive")?.toString())
        assertNull(receipt.errorCode)
        assertNull(outcome.privateResult)
        assertTrue(receipt.finishedAtMs >= receipt.startedAtMs)
    }

    @Test
    fun `invalid lease is denied before native read`() {
        val fixture = Fixture()
        val signed = fixture.signedStatus()
        fixture.leases.revoke(LEASE_ID)

        val receipt = fixture.executor.execute(signed, DEVICE_ID, SESSION_KEY).receipt

        assertEquals(AssistantReceiptStatusV1.DENIED, receipt.status)
        assertEquals("PROPOSAL_PRESENCE_LEASE", receipt.errorCode)
        assertEquals(0, fixture.readCount)
        assertNull(receipt.resultSummary)
    }

    @Test
    fun `recognized but unavailable capability fails closed`() {
        val fixture = Fixture()
        val receipt = fixture.executor.execute(
            fixture.signed(
                capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT,
                arguments = buildJsonObject {},
            ),
            DEVICE_ID,
            SESSION_KEY,
        ).receipt

        assertEquals(AssistantReceiptStatusV1.DENIED, receipt.status)
        assertEquals("CAPABILITY_NOT_IMPLEMENTED", receipt.errorCode)
        assertEquals(0, fixture.readCount)
    }

    @Test
    fun `native status failure returns failed receipt without exception details`() {
        val fixture = Fixture(failRead = true)
        val receipt = fixture.executor.execute(fixture.signedStatus(), DEVICE_ID, SESSION_KEY).receipt

        assertEquals(AssistantReceiptStatusV1.FAILED, receipt.status)
        assertEquals("DEVICE_STATUS_READ_FAILED", receipt.errorCode)
        assertNull(receipt.resultSummary)
    }

    @Test
    fun `authorized contacts search keeps private fields out of durable receipt`() {
        val fixture = Fixture(privateReadGranted = true)
        val outcome = fixture.executor.execute(
            fixture.signedContacts(query = " Jen ", limit = 1),
            DEVICE_ID,
            SESSION_KEY,
        )
        val receipt = outcome.receipt

        assertEquals(AssistantReceiptStatusV1.COMPLETED, receipt.status)
        assertEquals(AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH, receipt.capability)
        assertEquals("1", receipt.resultSummary?.get("matchCount")?.toString())
        assertEquals("true", receipt.resultSummary?.get("truncated")?.toString())
        assertEquals(1, fixture.contactsReadCount)
        assertTrue(outcome.privateResult.toString().contains("Jen Thorndale"))
        assertTrue(outcome.privateResult.toString().contains("+1 250 555 0100"))
        assertFalse(receipt.toString().contains("Jen Thorndale"))
        assertFalse(receipt.toString().contains("+1 250 555 0100"))
    }

    @Test
    fun `contacts search without private read grant is denied before provider access`() {
        val fixture = Fixture(privateReadGranted = false)
        val outcome = fixture.executor.execute(
            fixture.signedContacts(query = "Jen"),
            DEVICE_ID,
            SESSION_KEY,
        )

        assertEquals(AssistantReceiptStatusV1.DENIED, outcome.receipt.status)
        assertEquals("PRIVATE_READ_GRANT_REQUIRED", outcome.receipt.errorCode)
        assertEquals(0, fixture.contactsReadCount)
        assertNull(outcome.privateResult)
    }

    @Test
    fun `contacts permission denial returns a bounded error without private result`() {
        val fixture = Fixture(privateReadGranted = true, contactsPermission = false)
        val outcome = fixture.executor.execute(
            fixture.signedContacts(query = "Jen"),
            DEVICE_ID,
            SESSION_KEY,
        )

        assertEquals(AssistantReceiptStatusV1.DENIED, outcome.receipt.status)
        assertEquals("CONTACTS_READ_PERMISSION_REQUIRED", outcome.receipt.errorCode)
        assertEquals(1, fixture.contactsReadCount)
        assertNull(outcome.privateResult)
    }

    private class Fixture(
        private val failRead: Boolean = false,
        private val privateReadGranted: Boolean = false,
        private val contactsPermission: Boolean = true,
    ) {
        var elapsedNow = 1_000L
        var epochNow = 1_700_000_000_000L
        var readCount = 0
        var contactsReadCount = 0
        val leases = PresenceLeaseManager(
            nowElapsedMs = { elapsedNow },
            newLeaseId = { LEASE_ID },
            ttlMs = 5_000L,
            renewWithinMs = 1_000L,
        )
        private val lease = leases.issue(SESSION_KEY, DEVICE_ID)
        private val validator = AssistantProposalValidatorV1(
            presenceLeases = leases,
            signatureVerifier = ProposalSignatureVerifierV1 { _, keyId, signature ->
                keyId == "gateway-key-1" && signature == "signature"
            },
            nowEpochMs = { epochNow },
        )
        val executor = AssistantCapabilityExecutorV1(
            validator = validator,
            deviceStatusReader = AndroidDeviceStatusReaderV1 {
                readCount += 1
                if (failRead) error("sensitive implementation detail")
                AndroidDeviceStatusSummaryV1(
                    batteryLevelPercent = 82,
                    charging = true,
                    screenInteractive = true,
                )
            },
            contactsSearchReader = AndroidContactsSearchReaderV1 { query, limit ->
                contactsReadCount += 1
                if (!contactsPermission) {
                    AndroidContactsSearchReadV1.PermissionRequired
                } else {
                    assertEquals("Jen", query)
                    assertTrue(limit in 1..10)
                    AndroidContactsSearchReadV1.Success(
                        matches = listOf(
                            AndroidContactMatchV1("101", "Jen Thorndale", "+1 250 555 0100"),
                            AndroidContactMatchV1("102", "Jennifer Test", "+1 250 555 0101"),
                        ),
                        truncated = false,
                    )
                }
            },
            privateReadAuthorizer = AssistantPrivateReadAuthorizerV1 { capability, sessionKey, deviceId ->
                privateReadGranted &&
                    capability == AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH &&
                    sessionKey == SESSION_KEY &&
                    deviceId == DEVICE_ID
            },
            nowEpochMs = { epochNow++ },
            newReceiptId = { RECEIPT_ID },
        )

        fun signedStatus(): SignedAssistantProposalV1 = signed(
            capability = AssistantCapabilityV1.ANDROID_DEVICE_STATUS,
            arguments = buildJsonObject {},
        )

        fun signedContacts(query: String, limit: Int? = null): SignedAssistantProposalV1 = signed(
            capability = AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
            arguments = buildJsonObject {
                put("query", JsonPrimitive(query))
                limit?.let { put("limit", JsonPrimitive(it)) }
            },
        )

        fun signed(
            capability: AssistantCapabilityV1,
            arguments: JsonObject,
        ): SignedAssistantProposalV1 {
            val proposal = AssistantProposalV1(
                proposalId = PROPOSAL_ID,
                planId = PLAN_ID,
                stepId = STEP_ID,
                capability = capability,
                arguments = arguments,
                argumentsHash = CanonicalJsonV1.sha256(arguments),
                targetDeviceId = DEVICE_ID,
                voiceSessionKey = SESSION_KEY,
                presenceLeaseId = lease.leaseId,
                issuedAtMs = epochNow - 1_000L,
                expiresAtMs = epochNow + 30_000L,
                idempotencyKey = IDEMPOTENCY_KEY,
                risk = capability.risk,
            )
            return SignedAssistantProposalV1(
                proposal = proposal,
                signatureKeyId = "gateway-key-1",
                signatureBase64Url = "signature",
            )
        }
    }

    private companion object {
        const val SESSION_KEY = "agent:voice-main:voice-android-device"
        const val DEVICE_ID = "paired-device"
        const val LEASE_ID = "lease-1"
        const val PROPOSAL_ID = "11111111-1111-4111-8111-111111111111"
        const val PLAN_ID = "22222222-2222-4222-8222-222222222222"
        const val STEP_ID = "33333333-3333-4333-8333-333333333333"
        const val IDEMPOTENCY_KEY = "44444444-4444-4444-8444-444444444444"
        const val RECEIPT_ID = "55555555-5555-4555-8555-555555555555"
    }
}
