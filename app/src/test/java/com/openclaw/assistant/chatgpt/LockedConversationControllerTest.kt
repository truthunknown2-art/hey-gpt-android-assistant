package com.openclaw.assistant.chatgpt

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedConversationControllerTest {
    @Test
    fun `uses a stable agent-scoped session per device`() {
        assertEquals(
            "agent:locked-voice:voice-locked-abc123",
            LockedConversationController.lockedVoiceSessionKey("ABC123"),
        )
        assertEquals(
            "agent:locked-voice:voice-locked-android",
            LockedConversationController.lockedVoiceSessionKey(null),
        )
    }

    @Test
    fun `parses array and string assistant history`() {
        val replies = LockedConversationController.parseAssistantReplies(
            """{"messages":[
              {"role":"user","content":[{"type":"text","text":"hello"}]},
              {"role":"assistant","content":[{"type":"text","text":"First answer"}]},
              {"role":"assistant","content":"Second answer"}
            ]}""",
        )
        assertEquals(listOf("First answer", "Second answer"), replies)
    }

    @Test
    fun `waits for the next assistant reply in the locked session`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        var historyReads = 0
        val controller = LockedConversationController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.send" -> """{"runId":"run-1","status":"started"}"""
                    else -> {
                        historyReads++
                        if (historyReads == 1) {
                            """{"messages":[{"role":"assistant","content":"Old"}]}"""
                        } else {
                            """{"messages":[
                              {"role":"assistant","content":"Old"},
                              {"role":"assistant","content":[{"type":"text","text":"New reply"}]}
                            ]}"""
                        }
                    }
                }
            },
            pollDelay = {},
        )

        assertEquals("New reply", controller.ask("device-1", "How are you?"))
        val send = calls.single { it.first == "chat.send" }.second
        assertTrue(send.contains("agent:locked-voice:voice-locked-device-1"))
        assertTrue(send.contains("\"agentId\":\"locked-voice\""))
        assertTrue(send.contains("Locked voice mode"))
    }
}
