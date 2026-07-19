package com.openclaw.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionPolicyTest {
    @Test
    fun `forced key wins regardless of resume preference`() {
        for (resume in listOf(false, true)) {
            assertEquals(
                "agent:voice-main:voice-android-device",
                VoiceSessionPolicy.selectGatewaySessionKey(
                    forcedSessionKey = "agent:voice-main:voice-android-device",
                    resumeLatestSession = resume,
                    currentSessionKey = "current",
                    newSessionKey = "new",
                ),
            )
        }
    }

    @Test
    fun `ordinary sessions retain random or resume behavior`() {
        assertEquals(
            "new",
            VoiceSessionPolicy.selectGatewaySessionKey(null, false, "current", "new"),
        )
        assertEquals(
            "current",
            VoiceSessionPolicy.selectGatewaySessionKey(null, true, "current", "new"),
        )
    }

    @Test
    fun `force continuous is local to the active profile`() {
        assertTrue(VoiceSessionPolicy.continuousMode(true, false))
        assertTrue(VoiceSessionPolicy.continuousMode(false, true))
        assertFalse(VoiceSessionPolicy.continuousMode(false, false))
    }
}
