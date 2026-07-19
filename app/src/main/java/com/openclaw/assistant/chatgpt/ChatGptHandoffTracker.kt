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
    private var ignoredBaselineSessions: Set<Int> = emptySet()

    fun reset() {
        recordingObserved = false
        isArmed = false
        ignoredBaselineSessions = emptySet()
    }

    /**
     * Call after the app-owned recorder has released. Any sessions that are
     * already active are treated as a baseline rather than as ChatGPT. If a
     * baseline session disappears and its id is later reused, it is no longer
     * ignored.
     */
    fun arm(baselineSessions: Set<Int> = emptySet()) {
        recordingObserved = false
        isArmed = true
        ignoredBaselineSessions = baselineSessions
    }

    fun hasExternalRecording(currentSessions: Set<Int>): Boolean {
        if (!isArmed) return false
        ignoredBaselineSessions = ignoredBaselineSessions.intersect(currentSessions)
        return currentSessions.any { it !in ignoredBaselineSessions }
    }

    fun onRecordingSessionsChanged(currentSessions: Set<Int>): Boolean =
        onRecordingStateChanged(hasExternalRecording(currentSessions))

    fun onRecordingStateChanged(hasActiveRecording: Boolean): Boolean {
        if (!isArmed) return false
        if (hasActiveRecording) {
            recordingObserved = true
            return false
        }
        return recordingObserved
    }
}
