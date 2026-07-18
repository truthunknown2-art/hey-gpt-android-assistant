package com.openclaw.assistant.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayVoiceTurnControllerTest {
    @Test
    fun `returns only the assistant mirror correlated to this turn`() = runTest {
        var historyReads = 0
        var requestId = ""
        val calls = mutableListOf<Pair<String, String>>()
        val controller = GatewayVoiceTurnController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.send" -> {
                        requestId = Regex("\\\"idempotencyKey\\\":\\\"([^\\\"]+)\\\"")
                            .find(params)!!.groupValues[1]
                        """{"runId":"server-run-main","status":"started"}"""
                    }
                    else -> {
                        historyReads++
                        if (historyReads == 1) {
                            """{"messages":[
                              {"role":"assistant","content":"Old reply","__openclaw":{"mirrorIdentity":"old:assistant"}}
                            ]}"""
                        } else {
                            """{"messages":[
                              {"role":"user","content":"Previous","idempotencyKey":"other:user","__openclaw":{"mirrorIdentity":"other:prompt"}},
                              {"role":"assistant","content":"Delayed previous reply","__openclaw":{"mirrorIdentity":"other:assistant"}},
                              {"role":"user","content":"Remember cobalt","idempotencyKey":"${requestId}:user","__openclaw":{"mirrorIdentity":"current:prompt"}},
                              {"role":"assistant","content":"I will remember cobalt","__openclaw":{"mirrorIdentity":"current:assistant"}}
                            ]}"""
                        }
                    }
                }
            },
            pollDelay = {},
        )

        val reply = controller.ask(
            sessionKey = "agent:voice-main:voice-android-device",
            agentId = "voice-main",
            message = "Remember cobalt",
        )

        assertEquals("I will remember cobalt", reply)
        val send = calls.single { it.first == "chat.send" }.second
        assertTrue(send.contains("\"sessionKey\":\"agent:voice-main:voice-android-device\""))
        assertTrue(send.contains("\"agentId\":\"voice-main\""))
        assertFalse(send.contains("\"model\""))
    }

    @Test
    fun `cancellation aborts the exact server run`() = runTest {
        var historyReads = 0
        val sent = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String>>()
        val controller = GatewayVoiceTurnController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.send" -> {
                        sent.complete(Unit)
                        """{"runId":"server-run-cancel","status":"started"}"""
                    }
                    "chat.abort" -> """{"ok":true}"""
                    "chat.history" -> {
                        historyReads++
                        if (historyReads == 1) """{"messages":[]}""" else awaitCancellation()
                    }
                    else -> error("Unexpected method $method")
                }
            },
            pollDelay = {},
        )

        val job = launch {
            controller.ask(
                sessionKey = "agent:voice-main:voice-android-device",
                agentId = "voice-main",
                message = "Question",
            )
        }
        sent.await()
        job.cancelAndJoin()

        val abort = calls.single { it.first == "chat.abort" }.second
        assertTrue(abort.contains("server-run-cancel"))
        assertTrue(abort.contains("agent:voice-main:voice-android-device"))
        assertTrue(abort.contains("\"agentId\":\"voice-main\""))
    }

    @Test
    fun `cancellation before send acknowledgement aborts the dedicated session`() = runTest {
        val sendAttempted = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String>>()
        val controller = GatewayVoiceTurnController(
            requestGateway = { method, params, _ ->
                calls += method to params
                when (method) {
                    "chat.history" -> """{"messages":[]}"""
                    "chat.send" -> {
                        sendAttempted.complete(Unit)
                        awaitCancellation()
                    }
                    "chat.abort" -> """{"ok":true}"""
                    else -> error("Unexpected method $method")
                }
            },
            pollDelay = {},
        )

        val job = launch {
            controller.ask(
                sessionKey = "agent:voice-main:voice-android-device",
                agentId = "voice-main",
                message = "Question",
            )
        }
        sendAttempted.await()
        job.cancelAndJoin()

        val abort = calls.single { it.first == "chat.abort" }.second
        assertTrue(abort.contains("\"sessionKey\":\"agent:voice-main:voice-android-device\""))
        assertTrue(abort.contains("\"agentId\":\"voice-main\""))
        assertFalse(abort.contains("\"runId\""))
    }

    @Test
    fun `replacement turn waits until cancellation aborts the active run`() = runTest {
        val firstSent = CompletableDeferred<Unit>()
        val firstAborted = CompletableDeferred<Unit>()
        var sendCount = 0
        var secondRequestId = ""
        val controller = GatewayVoiceTurnController(
            requestGateway = { method, params, _ ->
                when (method) {
                    "chat.send" -> {
                        sendCount++
                        if (sendCount == 1) {
                            firstSent.complete(Unit)
                            """{"runId":"first-run","status":"started"}"""
                        } else {
                            secondRequestId = Regex("\\\"idempotencyKey\\\":\\\"([^\\\"]+)\\\"")
                                .find(params)!!.groupValues[1]
                            """{"runId":"second-run","status":"started"}"""
                        }
                    }
                    "chat.abort" -> {
                        firstAborted.complete(Unit)
                        """{"ok":true}"""
                    }
                    "chat.history" -> when {
                        sendCount == 0 -> """{"messages":[]}"""
                        sendCount == 1 && !firstAborted.isCompleted -> awaitCancellation()
                        sendCount == 1 -> """{"messages":[]}"""
                        else -> """{"messages":[
                          {"role":"user","content":"Second","idempotencyKey":"${secondRequestId}:user","__openclaw":{"mirrorIdentity":"second:prompt"}},
                          {"role":"assistant","content":"Second reply","__openclaw":{"mirrorIdentity":"second:assistant"}}
                        ]}"""
                    }
                    else -> error("Unexpected method $method")
                }
            },
            pollDelay = {},
        )

        val first = launch {
            controller.ask(
                sessionKey = "agent:voice-main:voice-android-device",
                agentId = "voice-main",
                message = "First",
            )
        }
        firstSent.await()
        val second = async {
            controller.ask(
                sessionKey = "agent:voice-main:voice-android-device",
                agentId = "voice-main",
                message = "Second",
            )
        }
        runCurrent()
        assertEquals(1, sendCount)

        first.cancelAndJoin()
        firstAborted.await()
        assertEquals("Second reply", second.await())
        assertEquals(2, sendCount)
    }
}
