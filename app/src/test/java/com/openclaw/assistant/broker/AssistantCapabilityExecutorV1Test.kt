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
                capability = AssistantCapabilityV1.WINDOWS_FILES_SEARCH,
                arguments = buildJsonObject { put("query", JsonPrimitive("report")) },
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

    @Test
    fun `authorized calendar read keeps event details out of durable receipt`() {
        val fixture = Fixture(calendarReadGranted = true)
        val outcome = fixture.executor.execute(
            fixture.signedCalendarNext(limit = 1),
            DEVICE_ID,
            SESSION_KEY,
        )

        assertEquals(AssistantReceiptStatusV1.COMPLETED, outcome.receipt.status)
        assertEquals("1", outcome.receipt.resultSummary?.get("eventCount")?.toString())
        assertEquals("true", outcome.receipt.resultSummary?.get("truncated")?.toString())
        assertEquals(1, fixture.calendarReadCount)
        assertTrue(outcome.privateResult.toString().contains("Dentist"))
        assertFalse(outcome.receipt.toString().contains("Dentist"))
    }

    @Test
    fun `calendar read requires grant and permission before returning private data`() {
        val withoutGrant = Fixture()
        val denied = withoutGrant.executor.execute(
            withoutGrant.signedCalendarNext(),
            DEVICE_ID,
            SESSION_KEY,
        )
        assertEquals("PRIVATE_READ_GRANT_REQUIRED", denied.receipt.errorCode)
        assertEquals(0, withoutGrant.calendarReadCount)

        val withoutPermission = Fixture(calendarReadGranted = true, calendarReadPermission = false)
        val permission = withoutPermission.executor.execute(
            withoutPermission.signedCalendarNext(),
            DEVICE_ID,
            SESSION_KEY,
        )
        assertEquals("CALENDAR_READ_PERMISSION_REQUIRED", permission.receipt.errorCode)
        assertEquals(1, withoutPermission.calendarReadCount)
        assertNull(permission.privateResult)
    }

    @Test
    fun `authorized Messenger read keeps previews out of durable receipt`() {
        val fixture = Fixture(messengerReadGranted = true)
        val outcome = fixture.executor.execute(
            fixture.signedMessenger(sender = "Jen", limit = 1),
            DEVICE_ID,
            SESSION_KEY,
        )

        assertEquals(AssistantReceiptStatusV1.COMPLETED, outcome.receipt.status)
        assertEquals("1", outcome.receipt.resultSummary?.get("notificationCount")?.toString())
        assertEquals(1, fixture.messengerReadCount)
        assertTrue(outcome.privateResult.toString().contains("See you at seven"))
        assertFalse(outcome.receipt.toString().contains("Jen Thorndale"))
        assertFalse(outcome.receipt.toString().contains("See you at seven"))
    }

    @Test
    fun `Messenger read requires grant before notification history access`() {
        val fixture = Fixture(messengerReadGranted = false)
        val outcome = fixture.executor.execute(
            fixture.signedMessenger(),
            DEVICE_ID,
            SESSION_KEY,
        )

        assertEquals("PRIVATE_READ_GRANT_REQUIRED", outcome.receipt.errorCode)
        assertEquals(0, fixture.messengerReadCount)
        assertNull(outcome.privateResult)
    }

    @Test
    fun `prepared calendar creation writes only after approval path and returns no title`() {
        val fixture = Fixture()
        val signed = fixture.signedCalendarCreate()
        val prepared = fixture.executor.prepareCalendarCreate(signed, DEVICE_ID, SESSION_KEY)
            as AssistantCalendarCreatePreparationV1.Ready

        assertEquals(42L, prepared.target.calendarId)
        assertEquals("Personal", prepared.target.displayName)
        assertEquals("Dentist", prepared.title)
        assertEquals(1, fixture.calendarResolutionCount)
        assertEquals(0, fixture.calendarWriteCount)

        val outcome = fixture.executor.executePreparedCalendarCreate(
            prepared,
            signed,
            DEVICE_ID,
            SESSION_KEY,
        )
        assertEquals(AssistantReceiptStatusV1.COMPLETED, outcome.receipt.status)
        assertEquals("true", outcome.receipt.resultSummary?.get("created")?.toString())
        assertFalse(outcome.receipt.toString().contains("Dentist"))
        assertEquals(1, fixture.calendarWriteCount)
    }

    @Test
    fun `invalid all-day range fails before calendar resolution or approval`() {
        val fixture = Fixture()
        val prepared = fixture.executor.prepareCalendarCreate(
            fixture.signedCalendarCreate(allDay = true, endEpochMs = fixture.epochNow + 3_600_000L),
            DEVICE_ID,
            SESSION_KEY,
        ) as AssistantCalendarCreatePreparationV1.Terminal

        assertEquals("CALENDAR_ARGUMENTS_INVALID", prepared.outcome.receipt.errorCode)
        assertEquals(0, fixture.calendarResolutionCount)
        assertEquals(0, fixture.calendarWriteCount)
    }

    private class Fixture(
        private val failRead: Boolean = false,
        private val privateReadGranted: Boolean = false,
        private val contactsPermission: Boolean = true,
        private val calendarReadGranted: Boolean = false,
        private val calendarReadPermission: Boolean = true,
        private val messengerReadGranted: Boolean = false,
        private val messengerReadPermission: Boolean = true,
        private val calendarCreateResolution: AndroidCalendarCreateResolutionV1 =
            AndroidCalendarCreateResolutionV1.Ready(AndroidCalendarCreateTargetV1(42L, "Personal")),
        private val calendarWriteResult: AndroidCalendarCreateWriteV1 = AndroidCalendarCreateWriteV1.Created,
    ) {
        var elapsedNow = 1_000L
        var epochNow = 1_700_000_000_000L
        var readCount = 0
        var contactsReadCount = 0
        var calendarReadCount = 0
        var messengerReadCount = 0
        var calendarResolutionCount = 0
        var calendarWriteCount = 0
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
            calendarNextReader = AndroidCalendarNextReaderV1 { _, _ ->
                calendarReadCount += 1
                if (!calendarReadPermission) {
                    AndroidCalendarNextReadV1.PermissionRequired
                } else {
                    AndroidCalendarNextReadV1.Success(
                        events = listOf(
                            AndroidCalendarEventV1("Dentist", epochNow + 60_000L, epochNow + 3_660_000L, false),
                            AndroidCalendarEventV1("Lunch", epochNow + 7_200_000L, epochNow + 10_800_000L, false),
                        ),
                        truncated = false,
                    )
                }
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
            messengerNotificationsReader = AndroidMessengerNotificationsReaderV1 { sender, limit ->
                messengerReadCount += 1
                if (!messengerReadPermission) {
                    AndroidMessengerNotificationsReadV1.PermissionRequired
                } else {
                    assertTrue(sender == null || sender == "jen")
                    assertTrue(limit in 1..10)
                    AndroidMessengerNotificationsReadV1.Success(
                        notifications = listOf(
                            AndroidMessengerNotificationV1(
                                sender = "Jen Thorndale",
                                textPreview = "See you at seven",
                                timestamp = epochNow,
                            ),
                        ),
                        truncated = false,
                    )
                }
            },
            privateReadAuthorizer = AssistantPrivateReadAuthorizerV1 { capability, sessionKey, deviceId, _ ->
                ((privateReadGranted && capability == AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH) ||
                    (calendarReadGranted && capability == AssistantCapabilityV1.ANDROID_CALENDAR_NEXT) ||
                    (messengerReadGranted &&
                        capability == AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ)) &&
                    sessionKey == SESSION_KEY &&
                    deviceId == DEVICE_ID
            },
            calendarCreateResolver = AndroidCalendarCreateResolverV1 {
                calendarResolutionCount += 1
                calendarCreateResolution
            },
            calendarCreateWriter = AndroidCalendarCreateWriterV1 { calendarId, title, start, end, allDay ->
                calendarWriteCount += 1
                assertEquals(42L, calendarId)
                assertEquals("Dentist", title)
                assertTrue(end > start)
                assertFalse(allDay)
                calendarWriteResult
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

        fun signedCalendarNext(limit: Int? = null): SignedAssistantProposalV1 = signed(
            capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT,
            arguments = buildJsonObject {
                put("afterEpochMs", JsonPrimitive(epochNow))
                limit?.let { put("limit", JsonPrimitive(it)) }
            },
        )

        fun signedCalendarCreate(
            allDay: Boolean = false,
            endEpochMs: Long = epochNow + 3_660_000L,
        ): SignedAssistantProposalV1 = signed(
            capability = AssistantCapabilityV1.ANDROID_CALENDAR_CREATE,
            arguments = buildJsonObject {
                put("title", JsonPrimitive("Dentist"))
                put("startEpochMs", JsonPrimitive(epochNow + 60_000L))
                put("endEpochMs", JsonPrimitive(endEpochMs))
                put("allDay", JsonPrimitive(allDay))
            },
        )

        fun signedMessenger(
            sender: String? = null,
            limit: Int? = null,
        ): SignedAssistantProposalV1 = signed(
            capability = AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ,
            arguments = buildJsonObject {
                sender?.let { put("sender", JsonPrimitive(it)) }
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
