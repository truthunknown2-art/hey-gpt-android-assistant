package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConversationRouteTest {
    @Test
    fun `unlocked devices always use official ChatGPT Live`() {
        assertEquals(
            VoiceConversationRoute.CHATGPT_LIVE,
            chooseVoiceConversationRoute(false, hasTranscript = true, openClawReady = true),
        )
    }

    @Test
    fun `securely locked device uses OpenClaw for a captured question`() {
        assertEquals(
            VoiceConversationRoute.LOCKED_OPENCLAW,
            chooseVoiceConversationRoute(true, hasTranscript = true, openClawReady = true),
        )
    }

    @Test
    fun `securely locked device requires unlock when fallback is unavailable`() {
        assertEquals(
            VoiceConversationRoute.UNLOCK_REQUIRED,
            chooseVoiceConversationRoute(true, hasTranscript = true, openClawReady = false),
        )
        assertEquals(
            VoiceConversationRoute.UNLOCK_REQUIRED,
            chooseVoiceConversationRoute(true, hasTranscript = false, openClawReady = true),
        )
    }
}
