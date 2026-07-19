package com.openclaw.assistant.service

import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientVoiceRecognitionPolicyTest {
    @Test
    fun `no match and speech timeout retry inside bounded window`() {
        assertTrue(AmbientVoiceRecognitionPolicy.shouldRetry(SpeechRecognizer.ERROR_NO_MATCH, 0L))
        assertTrue(
            AmbientVoiceRecognitionPolicy.shouldRetry(
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS - 1L,
            ),
        )
    }

    @Test
    fun `soft recognition errors stop retrying at window boundary`() {
        assertFalse(
            AmbientVoiceRecognitionPolicy.shouldRetry(
                SpeechRecognizer.ERROR_NO_MATCH,
                AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `fatal recognition errors never retry`() {
        assertFalse(AmbientVoiceRecognitionPolicy.shouldRetry(SpeechRecognizer.ERROR_AUDIO, 0L))
        assertFalse(SpeechRecognizer.ERROR_CLIENT.let { AmbientVoiceRecognitionPolicy.shouldRetry(it, 0L) })
        assertFalse(AmbientVoiceRecognitionPolicy.shouldRetry(null, 0L))
    }

    @Test
    fun `late successful transcript is cancelled and never reaches chat send`() = runTest {
        var recognizerCancelled = false
        var chatSendCount = 0

        val result = runAmbientVoiceListenWindow(
            listenWindowStartedAtMs = 0L,
            nowMs = { testScheduler.currentTime },
            listenForTurn = {
                try {
                    delay(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS)
                    "too late"
                } finally {
                    recognizerCancelled = true
                }
            },
            onSoftRetry = {},
            onTranscriptAccepted = { _, _ -> chatSendCount += 1 },
        )

        assertEquals(AmbientVoiceListenWindowResult.IDLE_TIMEOUT, result)
        assertEquals(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS, testScheduler.currentTime)
        assertTrue(recognizerCancelled)
        assertEquals(0, chatSendCount)
    }

    @Test
    fun `soft error at final millisecond cannot start an unbounded retry`() = runTest {
        var attempts = 0

        val result = runAmbientVoiceListenWindow(
            listenWindowStartedAtMs = 0L,
            nowMs = { testScheduler.currentTime },
            listenForTurn = {
                attempts += 1
                delay(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS - 1L)
                throw RetryableSpeechRecognitionException("no match", SpeechRecognizer.ERROR_NO_MATCH)
            },
            onSoftRetry = { delay(250L) },
            onTranscriptAccepted = { _, _ -> fail("A retry error must not produce a transcript") },
        )

        assertEquals(AmbientVoiceListenWindowResult.IDLE_TIMEOUT, result)
        assertEquals(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS, testScheduler.currentTime)
        assertEquals(1, attempts)
    }

    @Test
    fun `deadline is rechecked at gateway send boundary`() = runTest {
        var chatSendCount = 0
        var deadlineExpired = false

        try {
            runAmbientVoiceListenWindow(
                listenWindowStartedAtMs = 0L,
                nowMs = { testScheduler.currentTime },
                listenForTurn = {
                    delay(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS - 1L)
                    "edge transcript"
                },
                onSoftRetry = {},
                onTranscriptAccepted = { _, deadlineMs ->
                    delay(1L)
                    requireAmbientVoiceBeforeDeadline(deadlineMs, testScheduler.currentTime) {
                        deadlineExpired = true
                    }
                    chatSendCount += 1
                },
            )
            fail("Expected the Gateway send-boundary deadline check to cancel")
        } catch (error: CancellationException) {
            assertEquals("Ambient listening deadline expired", error.message)
        }

        assertEquals(AmbientVoiceRecognitionPolicy.RETRY_WINDOW_MS, testScheduler.currentTime)
        assertTrue(deadlineExpired)
        assertEquals(0, chatSendCount)
    }

    @Test
    fun `external cancellation is not converted to idle timeout`() = runTest {
        try {
            runAmbientVoiceListenWindow(
                listenWindowStartedAtMs = 0L,
                nowMs = { testScheduler.currentTime },
                listenForTurn = { throw CancellationException("secure lock") },
                onSoftRetry = {},
                onTranscriptAccepted = { _, _ -> fail("Cancelled recognition must not be accepted") },
            )
            fail("Expected secure-lock cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("secure lock", error.message)
        }
    }

    @Test
    fun `recognizer timeout is not mistaken for ambient deadline expiry`() = runTest {
        try {
            runAmbientVoiceListenWindow(
                listenWindowStartedAtMs = 0L,
                nowMs = { testScheduler.currentTime },
                listenForTurn = {
                    withTimeout(1_000L) {
                        delay(2_000L)
                        "unreachable"
                    }
                },
                onSoftRetry = {},
                onTranscriptAccepted = { _, _ -> fail("Timed-out recognition must not be accepted") },
            )
            fail("Expected the recognizer's own timeout to propagate")
        } catch (error: CancellationException) {
            assertEquals(1_000L, testScheduler.currentTime)
        }
    }
}
