package com.openclaw.assistant.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPrivateReadGrantManagerV1Test {
    @Test
    fun `grant is exact capability session and device scoped`() {
        var now = 1_000L
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { now })

        val grant = manager.grant(CONTACTS, SESSION, DEVICE)

        assertEquals(now, grant.issuedAtElapsedMs)
        assertEquals(now + AssistantPrivateReadGrantManagerV1.DEFAULT_TTL_MS, grant.expiresAtElapsedMs)
        assertTrue(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
        assertFalse(manager.isAuthorized(CONTACTS, "other-session", DEVICE))
        assertFalse(manager.isAuthorized(CONTACTS, SESSION, "other-device"))
        assertFalse(manager.isAuthorized(AssistantCapabilityV1.WINDOWS_FILES_READ, SESSION, DEVICE))
    }

    @Test
    fun `grant expires at the ten minute boundary`() {
        var now = 5_000L
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { now })
        val grant = manager.grant(CONTACTS, SESSION, DEVICE)

        now = grant.expiresAtElapsedMs - 1
        assertTrue(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
        now = grant.expiresAtElapsedMs
        assertFalse(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `session revocation removes every private read for only that binding`() {
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
        manager.grant(CONTACTS, SESSION, DEVICE)
        manager.grant(AssistantCapabilityV1.WINDOWS_FILES_READ, SESSION, DEVICE)
        manager.grant(CONTACTS, "other-session", DEVICE)

        manager.revokeSession(SESSION, DEVICE)

        assertFalse(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
        assertFalse(manager.isAuthorized(AssistantCapabilityV1.WINDOWS_FILES_READ, SESSION, DEVICE))
        assertTrue(manager.isAuthorized(CONTACTS, "other-session", DEVICE))
        assertEquals(1, manager.activeCount())
    }

    @Test
    fun `reissue cannot extend beyond the fixed ten minute ttl`() {
        var now = 1_000L
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { now })
        manager.grant(CONTACTS, SESSION, DEVICE)
        now += 1_000L

        val reissued = manager.grant(CONTACTS, SESSION, DEVICE)

        assertEquals(now + AssistantPrivateReadGrantManagerV1.DEFAULT_TTL_MS, reissued.expiresAtElapsedMs)
        assertEquals(1, manager.activeCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non private capability cannot receive a grant`() {
        AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
            .grant(AssistantCapabilityV1.ANDROID_DEVICE_STATUS, SESSION, DEVICE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `custom ttl cannot exceed ten minutes`() {
        AssistantPrivateReadGrantManagerV1(
            nowElapsedMs = { 1_000L },
            ttlMs = AssistantPrivateReadGrantManagerV1.DEFAULT_TTL_MS + 1L,
        )
    }

    private companion object {
        val CONTACTS = AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH
        const val SESSION = "agent:voice-main:voice-android-device"
        const val DEVICE = "paired-device"
    }
}
