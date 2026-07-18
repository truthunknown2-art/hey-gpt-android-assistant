package com.openclaw.assistant.chatgpt

/**
 * Tracks the microphone portion of a handoff to the official ChatGPT app.
 *
 * Android's audio-recording callback reports active recordings without requiring
 * us to inspect or automate ChatGPT. Once recording has started, an empty list
 * means the Live conversation released the microphone and wake-word detection
 * can safely resume.
 */
class ChatGptHandoffTracker {
    var recordingObserved: Boolean = false
        private set

    fun reset() {
        recordingObserved = false
    }

    fun onRecordingStateChanged(hasActiveRecording: Boolean): Boolean {
        if (hasActiveRecording) {
            recordingObserved = true
            return false
        }
        return recordingObserved
    }
}
