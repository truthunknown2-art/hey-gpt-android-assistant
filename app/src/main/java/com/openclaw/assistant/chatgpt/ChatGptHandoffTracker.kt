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
    var isArmed: Boolean = false
        private set

    fun reset() {
        recordingObserved = false
        isArmed = false
    }

    /** Call only after the app-owned recorder has fully released. */
    fun arm() {
        recordingObserved = false
        isArmed = true
    }

    fun onRecordingStateChanged(hasActiveRecording: Boolean): Boolean {
        if (!isArmed) return false
        if (hasActiveRecording) {
            recordingObserved = true
            return false
        }
        return recordingObserved
    }
}
