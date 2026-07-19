package com.openclaw.assistant.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLeaseManagerTest {
    @Test
    fun `lease is bound to the exact session and device`() {
        val fixture = Fixture()
        val lease = fixture.manager.issue(SESSION_KEY, DEVICE_ID)

        assertTrue(fixture.manager.validate(lease.leaseId, SESSION_KEY, DEVICE_ID) is PresenceLeaseValidation.Valid)
        assertEquals(
            PresenceLeaseRejection.WRONG_SESSION,
            (fixture.manager.validate(lease.leaseId, "agent:voice-main:other", DEVICE_ID) as PresenceLeaseValidation.Rejected).reason,
        )
        assertEquals(
            PresenceLeaseRejection.WRONG_DEVICE,
            (fixture.manager.validate(lease.leaseId, SESSION_KEY, "other-device") as PresenceLeaseValidation.Rejected).reason,
        )
    }

    @Test
    fun `renewal extends only a still-valid exact lease`() {
        val fixture = Fixture()
        val lease = fixture.manager.issue(SESSION_KEY, DEVICE_ID)
        fixture.now = 5_000L

        val renewed = fixture.manager.validateAndRenew(
            lease.leaseId,
            SESSION_KEY,
            DEVICE_ID,
        ) as PresenceLeaseValidation.Valid

        assertEquals(10_000L, renewed.lease.expiresAtElapsedMs)
        assertEquals(1, fixture.manager.activeCount())
    }

    @Test
    fun `expired and revoked leases fail closed`() {
        val fixture = Fixture()
        val expired = fixture.manager.issue(SESSION_KEY, DEVICE_ID)
        fixture.now = expired.expiresAtElapsedMs

        assertEquals(
            PresenceLeaseRejection.EXPIRED,
            (fixture.manager.validate(expired.leaseId, SESSION_KEY, DEVICE_ID) as PresenceLeaseValidation.Rejected).reason,
        )

        fixture.now += 1
        val revoked = fixture.manager.issue(SESSION_KEY, DEVICE_ID)
        fixture.manager.revoke(revoked.leaseId)
        assertEquals(
            PresenceLeaseRejection.MISSING,
            (fixture.manager.validate(revoked.leaseId, SESSION_KEY, DEVICE_ID) as PresenceLeaseValidation.Rejected).reason,
        )
    }

    @Test
    fun `issuing a replacement invalidates the prior device lease`() {
        val fixture = Fixture()
        val first = fixture.manager.issue(SESSION_KEY, DEVICE_ID)
        val second = fixture.manager.issue("agent:voice-main:new", DEVICE_ID)

        assertNotEquals(first.leaseId, second.leaseId)
        assertEquals(
            PresenceLeaseRejection.MISSING,
            (fixture.manager.validate(first.leaseId, SESSION_KEY, DEVICE_ID) as PresenceLeaseValidation.Rejected).reason,
        )
        assertEquals(1, fixture.manager.activeCount())
    }

    private class Fixture {
        var now = 1_000L
        private var nextId = 0
        val manager = PresenceLeaseManager(
            nowElapsedMs = { now },
            newLeaseId = { "lease-${++nextId}" },
            ttlMs = 5_000L,
            renewWithinMs = 1_000L,
        )
    }

    companion object {
        private const val SESSION_KEY = "agent:voice-main:voice-android-device"
        private const val DEVICE_ID = "paired-device"
    }
}
