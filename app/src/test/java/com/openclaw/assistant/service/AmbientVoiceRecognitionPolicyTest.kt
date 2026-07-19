package com.openclaw.assistant.service

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
