package com.openclaw.assistant.chatgpt

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        var requestId = ""
        val controller = LockedConversationController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.send" -> {
                        requestId = Regex("\\\"idempotencyKey\\\":\\\"([^\\\"]+)\\\"")
                            .find(params)!!.groupValues[1]
                        """{"runId":"run-1","status":"started"}"""
                    }
                    else -> {
                        historyReads++
                        if (historyReads == 1) {
                            """{"messages":[
                              {"role":"assistant","content":"Old","__openclaw":{"mirrorIdentity":"old:assistant"}}
                            ]}"""
                        } else {
                            """{"messages":[
                              {"role":"user","content":"How are you?","idempotencyKey":"${requestId}:user","__openclaw":{"mirrorIdentity":"turn-1:prompt"}},
                              {"role":"assistant","content":[{"type":"text","text":"New reply"}],"__openclaw":{"mirrorIdentity":"turn-1:assistant"}}
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

    @Test
    fun `detects a new reply when bounded history stays the same size`() = runTest {
        var historyReads = 0
        var requestId = ""
        val controller = LockedConversationController(
            requestGateway = { method, params, _ ->
                when (method) {
                    "chat.send" -> {
                        requestId = Regex("\\\"idempotencyKey\\\":\\\"([^\\\"]+)\\\"")
                            .find(params)!!.groupValues[1]
                        """{"runId":"run-2","status":"started"}"""
                    }
                    else -> {
                        historyReads++
                        if (historyReads == 1) {
                            """{"messages":[
                              {"id":"user-old","role":"user","content":"Question"},
                              {"id":"assistant-old","role":"assistant","content":"Old reply","__openclaw":{"mirrorIdentity":"old:assistant"}}
                            ]}"""
                        } else {
                            """{"messages":[
                              {"id":"user-new","role":"user","content":"New question","idempotencyKey":"${requestId}:user","__openclaw":{"mirrorIdentity":"turn-2:prompt"}},
                              {"id":"assistant-new","role":"assistant","content":"New reply","__openclaw":{"mirrorIdentity":"turn-2:assistant"}}
                            ]}"""
                        }
                    }
                }
            },
            pollDelay = {},
        )

        assertEquals("New reply", controller.ask("device-2", "New question"))
    }

    @Test
    fun `fails closed when baseline history is unavailable`() = runTest {
        var sendCalled = false
        val controller = LockedConversationController(
            requestGateway = { method, _, _ ->
                if (method == "chat.history") error("gateway unavailable")
                sendCalled = true
                """{"runId":"should-not-start"}"""
            },
            pollDelay = {},
        )

        val failure = runCatching { controller.ask("device-3", "Question") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(sendCalled)
    }

    @Test
    fun `times out and aborts the exact server run`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val controller = LockedConversationController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.send" -> """{"runId":"server-run-timeout","status":"started"}"""
                    "chat.abort" -> """{"ok":true}"""
                    else -> """{"messages":[]}"""
                }
            },
            pollDelay = { delay(it) },
        )

        assertNull(controller.ask("device-4", "Question"))
        val abort = calls.single { it.first == "chat.abort" }.second
        assertTrue(abort.contains("server-run-timeout"))
        assertTrue(abort.contains("agent:locked-voice:voice-locked-device-4"))
    }
}
