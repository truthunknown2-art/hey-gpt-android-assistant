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
    fun `voice session revocation also removes grants delegated to another device`() {
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
        manager.grant(CONTACTS, SESSION, DEVICE)
        manager.grant(AssistantCapabilityV1.WINDOWS_FILES_READ, SESSION, "windows-node")
        manager.grant(CONTACTS, "other-session", DEVICE)

        manager.revokeVoiceSession(SESSION)

        assertFalse(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
        assertFalse(manager.isAuthorized(AssistantCapabilityV1.WINDOWS_FILES_READ, SESSION, "windows-node"))
        assertTrue(manager.isAuthorized(CONTACTS, "other-session", DEVICE))
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

    @Test
    fun `calendar read receives its own independent grant`() {
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })

        manager.grant(AssistantCapabilityV1.ANDROID_CALENDAR_NEXT, SESSION, DEVICE)

        assertTrue(manager.isAuthorized(AssistantCapabilityV1.ANDROID_CALENDAR_NEXT, SESSION, DEVICE))
        assertFalse(manager.isAuthorized(CONTACTS, SESSION, DEVICE))
    }

    @Test
    fun `Messenger grant requires the same normalized sender`() {
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
        manager.grant(MESSENGER, SESSION, DEVICE, messengerScope("  Jen   Thorndale ", 3))

        assertTrue(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope("jen thorndale", 1)))
        assertFalse(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope("Alex", 1)))
        assertFalse(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope(null, 1)))
    }

    @Test
    fun `Messenger grant cannot authorize a larger result count`() {
        val manager = AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
        manager.grant(MESSENGER, SESSION, DEVICE, messengerScope("Jen", 3))

        assertTrue(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope("Jen", 3)))
        assertTrue(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope("Jen", 2)))
        assertFalse(manager.isAuthorized(MESSENGER, SESSION, DEVICE, messengerScope("Jen", 4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Messenger grant cannot be capability wide`() {
        AssistantPrivateReadGrantManagerV1(nowElapsedMs = { 1_000L })
            .grant(MESSENGER, SESSION, DEVICE)
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
        val MESSENGER = AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ
        const val SESSION = "agent:voice-main:voice-android-device"
        const val DEVICE = "paired-device"

        fun messengerScope(sender: String?, limit: Int) = AssistantPrivateReadScopeV1.Messenger(
            normalizedSender = sender?.let(::normalizePrivateReadSenderV1),
            maxLimit = limit,
        )
    }
}
