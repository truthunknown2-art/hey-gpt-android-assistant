package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedVoiceSpeechPolicyTest {
    @Test
    fun `locked reply is capped in code at eighty words`() {
        val result = limitLockedVoiceReply((1..100).joinToString(" ") { "word$it" })
        assertEquals(80, result.split(" ").size)
        assertTrue(result.endsWith("word80"))
    }

    @Test
    fun `tts deadline scales beyond the old fixed fifteen seconds`() {
        val eightyWords = (1..80).joinToString(" ") { "word" }
        assertTrue(localTtsTimeoutMs(eightyWords) > 15_000L)
        assertTrue(localTtsTimeoutMs(eightyWords) <= 90_000L)
    }
}
