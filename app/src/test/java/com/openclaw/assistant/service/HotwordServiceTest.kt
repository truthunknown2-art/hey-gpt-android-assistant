package com.openclaw.assistant.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordServiceTest {

    @Test
    fun ambientFinish_revokesAuthorityBeforeOrderedVoskRecovery() = runTest {
        val events = mutableListOf<String>()

        val cleanup = beginAmbientVoiceFinish(
            cleanupScope = this,
            closePrivateResult = { events += "private_result_closed" },
            revokeApprovals = { events += "approvals_revoked" },
            revokeGrants = { events += "grants_revoked" },
            revokePresence = { events += "presence_revoked" },
            cancelAndJoinTurns = { events += "chat_abort_complete" },
            releaseSpeech = { events += "stt_released" },
            stopSpeechOutput = { events += "tts_released" },
            publishInactive = { events += "inactive" },
            restartHotword = { events += "vosk_restart_attempted" },
            releaseWakeLock = { events += "wake_lock_released" },
        )

        assertEquals(
            listOf(
                "private_result_closed",
                "approvals_revoked",
                "grants_revoked",
                "presence_revoked",
            ),
            events,
        )
        cleanup.join()
        assertEquals(
            listOf(
                "private_result_closed",
                "approvals_revoked",
                "grants_revoked",
                "presence_revoked",
                "chat_abort_complete",
                "stt_released",
                "tts_released",
                "inactive",
                "vosk_restart_attempted",
                "wake_lock_released",
            ),
            events,
        )
    }

    @Test
    fun ambientTeardown_ordersAbortAudioRestartAndWakeLockRelease() = runTest {
        val events = mutableListOf<String>()

        performAmbientVoiceTeardown(
            cancelAndJoinTurns = { events += "chat_abort_complete" },
            releaseSpeech = { events += "stt_released" },
            stopSpeechOutput = { events += "tts_released" },
            publishInactive = { events += "inactive" },
            restartHotword = { events += "vosk_restart_attempted" },
            releaseWakeLock = { events += "wake_lock_released" },
        )

        assertEquals(
            listOf(
                "chat_abort_complete",
                "stt_released",
                "tts_released",
                "inactive",
                "vosk_restart_attempted",
                "wake_lock_released",
            ),
            events,
        )
    }

    @Test
    fun secureResume_attemptsRecorderRestartBeforeCallerCanReleaseWakeLock() = runTest {
        val events = mutableListOf<String>()

        HotwordService.restartHotwordBeforeWakeLockRelease(
            settleDelayMs = 500,
            shouldRestart = { true },
            restart = { events += "restart" },
        )
        events += "release"

        assertEquals(listOf("restart", "release"), events)
    }

    @Test
    fun secureResume_skipsRestartWhenAnotherSessionBecameActive() = runTest {
        var restarted = false

        HotwordService.restartHotwordBeforeWakeLockRelease(
            settleDelayMs = 500,
            shouldRestart = { false },
            restart = { restarted = true },
        )

        assertFalse(restarted)
    }

    @Test
    fun heyGptBargeIn_claimedByExistingSession_doesNotLaunchSecondSession() = runTest {
        val events = mutableListOf<String>()
        var launchPending = true

        val shouldLaunch = HotwordService.waitForExistingSessionClaim(
            isBargeInCandidate = true,
            waitForClaim = {
                events += "existing_session_interrupted"
                launchPending = false
            },
            isLaunchPending = { launchPending },
        )
        if (shouldLaunch) events += "show_second_session"

        assertFalse(shouldLaunch)
        assertEquals(listOf("existing_session_interrupted"), events)
    }

    @Test
    fun ordinaryWake_withoutActiveSession_launchesImmediately() = runTest {
        var waited = false

        val shouldLaunch = HotwordService.waitForExistingSessionClaim(
            isBargeInCandidate = false,
            waitForClaim = { waited = true },
            isLaunchPending = { false },
        )

        assertTrue(shouldLaunch)
        assertFalse(waited)
    }

    @Test
    fun chatGptHandoffStartAction_doesNotInitializeVoskRecorder() {
        assertFalse(
            HotwordService.shouldInitializeVosk(HotwordService.ACTION_REQUEST_CHATGPT_HANDOFF),
        )
        assertTrue(HotwordService.shouldInitializeVosk(startAction = null))
    }

    @Test
    fun coldChatGptHandoffFinish_initializesVoskBeforeListeningCanResume() {
        val events = mutableListOf<String>()

        HotwordService.resumeAfterChatGptHandoff(
            modelReady = false,
            initialize = { events += "initialize" },
            resume = { events += "resume" },
        )

        assertEquals(listOf("initialize"), events)
    }

    @Test
    fun warmChatGptHandoffFinish_resumesExistingVoskModel() {
        val events = mutableListOf<String>()

        HotwordService.resumeAfterChatGptHandoff(
            modelReady = true,
            initialize = { events += "initialize" },
            resume = { events += "resume" },
        )

        assertEquals(listOf("resume"), events)
    }

    @Test
    fun duplicateVoskInitialization_isRejectedWhileFirstLoadIsActive() {
        assertFalse(
            HotwordService.shouldStartVoskInitialization(
                modelReady = false,
                initializationActive = true,
            ),
        )
        assertTrue(
            HotwordService.shouldStartVoskInitialization(
                modelReady = false,
                initializationActive = false,
            ),
        )
        assertFalse(
            HotwordService.shouldStartVoskInitialization(
                modelReady = true,
                initializationActive = false,
            ),
        )
    }

    @Test
    fun ttsResumeMarker_isRecognizedAsBargeInCandidate() {
        assertTrue(
            HotwordService.isBargeInCandidate(
                isSessionActive = false,
                existingSessionCanClaimInterrupt = true,
                ttsBargeInEnabled = true,
            ),
        )
        assertFalse(
            HotwordService.isBargeInCandidate(
                isSessionActive = false,
                existingSessionCanClaimInterrupt = true,
                ttsBargeInEnabled = false,
            ),
        )
    }

    @Test
    fun shouldCopyModel_returnsFalse_whenVersionsMatchAndDirValid() {
        val currentVersion = 10
        val savedVersion = 10
        val targetDirExists = true
        val targetDirNotEmpty = true

        val result = HotwordService.shouldCopyModel(
            currentVersion,
            savedVersion,
            targetDirExists,
            targetDirNotEmpty
        )

        assertFalse("Should NOT copy when versions match and dir is valid", result)
    }

    @Test
    fun shouldCopyModel_returnsTrue_whenVersionsMismatch() {
        val currentVersion = 11
        val savedVersion = 10
        val targetDirExists = true
        val targetDirNotEmpty = true

        val result = HotwordService.shouldCopyModel(
            currentVersion,
            savedVersion,
            targetDirExists,
            targetDirNotEmpty
        )

        assertTrue("Should copy when versions mismatch", result)
    }

    @Test
    fun shouldCopyModel_returnsTrue_whenDirMissing() {
        val currentVersion = 10
        val savedVersion = 10
        val targetDirExists = false
        val targetDirNotEmpty = false // irrelevant if exists is false, but usually empty

        val result = HotwordService.shouldCopyModel(
            currentVersion,
            savedVersion,
            targetDirExists,
            targetDirNotEmpty
        )

        assertTrue("Should copy when target dir is missing", result)
    }

    @Test
    fun shouldCopyModel_returnsTrue_whenDirEmpty() {
        val currentVersion = 10
        val savedVersion = 10
        val targetDirExists = true
        val targetDirNotEmpty = false

        val result = HotwordService.shouldCopyModel(
            currentVersion,
            savedVersion,
            targetDirExists,
            targetDirNotEmpty
        )

        assertTrue("Should copy when target dir is empty", result)
    }
}
