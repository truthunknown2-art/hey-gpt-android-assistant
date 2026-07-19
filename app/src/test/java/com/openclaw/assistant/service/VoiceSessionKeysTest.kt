package com.openclaw.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionKeysTest {
    @Test
    fun `same device produces the same main voice key`() {
        assertEquals(
            VoiceSessionKeys.mainVoice("Device-123"),
            VoiceSessionKeys.mainVoice("Device-123"),
        )
    }

    @Test
    fun `different devices produce different main voice keys`() {
        assertNotEquals(
            VoiceSessionKeys.mainVoice("device-one"),
            VoiceSessionKeys.mainVoice("device-two"),
        )
    }

    @Test
    fun `session keys are agent scoped sanitized and bounded`() {
        val main = VoiceSessionKeys.mainVoice("ABC !@# 123_${"x".repeat(80)}")
        val locked = VoiceSessionKeys.lockedVoice("ABC !@# 123_${"x".repeat(80)}")

        assertTrue(main.startsWith("agent:voice-main:voice-android-abc123_"))
        assertTrue(locked.startsWith("agent:locked-voice:voice-locked-abc123_"))
        assertTrue(main.removePrefix("agent:voice-main:voice-android-").length <= 32)
        assertTrue(locked.removePrefix("agent:locked-voice:voice-locked-").length <= 32)
    }
}
