package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptHandoffTrackerTest {
    @Test
    fun `does not resume before ChatGPT starts recording`() {
        val tracker = ChatGptHandoffTracker()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = false))
        assertFalse(tracker.recordingObserved)
    }

    @Test
    fun `resumes after an observed recording stops`() {
        val tracker = ChatGptHandoffTracker()

        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = true))
        assertTrue(tracker.recordingObserved)
        assertTrue(tracker.onRecordingStateChanged(hasActiveRecording = false))
    }

    @Test
    fun `reset prepares tracker for another handoff`() {
        val tracker = ChatGptHandoffTracker()
        tracker.onRecordingStateChanged(hasActiveRecording = true)

        tracker.reset()

        assertFalse(tracker.recordingObserved)
        assertFalse(tracker.onRecordingStateChanged(hasActiveRecording = false))
    }
}
