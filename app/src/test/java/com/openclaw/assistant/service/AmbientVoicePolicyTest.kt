package com.openclaw.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientVoicePolicyTest {
    @Test
    fun `only current active generation can acknowledge ui`() {
        assertTrue(AmbientVoicePolicy.acceptsUiAttachment("new", "new", sessionActive = true))
        assertFalse(AmbientVoicePolicy.acceptsUiAttachment("new", "old", sessionActive = true))
        assertFalse(AmbientVoicePolicy.acceptsUiAttachment("new", "new", sessionActive = false))
    }

    @Test
    fun `blocked ui does not terminate healthy headless session`() {
        assertEquals(
            AmbientUiTimeoutDecision.CONTINUE_HEADLESS,
            AmbientVoicePolicy.uiTimeoutDecision("token", "token", true, sessionActive = true),
        )
    }

    @Test
    fun `stale timeout is ignored and failed session ends`() {
        assertEquals(
            AmbientUiTimeoutDecision.IGNORE,
            AmbientVoicePolicy.uiTimeoutDecision("new", "old", true, sessionActive = true),
        )
        assertEquals(
            AmbientUiTimeoutDecision.END_SESSION,
            AmbientVoicePolicy.uiTimeoutDecision("new", "new", true, sessionActive = false),
        )
    }
}
