package com.openclaw.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDispatchPolicyTest {
    @Test
    fun `Hey GPT always selects the dedicated gateway path`() {
        assertEquals(
            VoiceDispatchPath.DEDICATED_GATEWAY,
            VoiceDispatchPolicy.selectPath(isHeyGptMainProfile = true),
        )
    }

    @Test
    fun `ordinary voice sessions retain configured backend routing`() {
        assertEquals(
            VoiceDispatchPath.CONFIGURED_BACKEND,
            VoiceDispatchPolicy.selectPath(isHeyGptMainProfile = false),
        )
    }
}
