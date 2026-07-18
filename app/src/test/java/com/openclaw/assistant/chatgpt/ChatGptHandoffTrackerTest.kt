package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptHandoffTrackerTest {
    @Test
    fun `does not resume before ChatGPT starts recording`() {
        val tracker = ChatGptHandoffTracker()
        tracker.arm()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = false))
        assertFalse(tracker.recordingObserved)
    }

    @Test
    fun `resumes after an observed recording stops`() {
        val tracker = ChatGptHandoffTracker()
        tracker.arm()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = true))
        assertTrue(tracker.recordingObserved)
        assertTrue(tracker.onRecordingStateChanged(hasActiveRecording = false))
    }

    @Test
    fun `reset prepares tracker for another handoff`() {
        val tracker = ChatGptHandoffTracker()
        tracker.arm()
        tracker.onRecordingStateChanged(hasActiveRecording = true)

        tracker.reset()

        assertFalse(tracker.recordingObserved)
        assertFalse(tracker.isArmed)
        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = false))
    }

    @Test
    fun `ignores the app recorder until handoff is armed`() {
        val tracker = ChatGptHandoffTracker()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = true))
        assertFalse(tracker.recordingObserved)

        tracker.arm()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = true))
        assertTrue(tracker.recordingObserved)
    }

    @Test
    fun `baseline recorder is not mistaken for ChatGPT`() {
        val tracker = ChatGptHandoffTracker()
        tracker.arm(baselineSessions = setOf(41))

        assertFalse(tracker.onRecordingSessionsChanged(setOf(41)))
        assertFalse(tracker.recordingObserved)
        assertFalse(tracker.onRecordingSessionsChanged(setOf(41, 52)))
        assertTrue(tracker.recordingObserved)
        assertFalse(tracker.onRecordingSessionsChanged(setOf(41, 52)))
        assertTrue(tracker.onRecordingSessionsChanged(setOf(41)))
    }

    @Test
    fun `session id reused after baseline ends is treated as new`() {
        val tracker = ChatGptHandoffTracker()
        tracker.arm(baselineSessions = setOf(41))

        assertFalse(tracker.onRecordingSessionsChanged(emptySet()))
        assertFalse(tracker.onRecordingSessionsChanged(setOf(41)))
        assertTrue(tracker.recordingObserved)
    }
}
