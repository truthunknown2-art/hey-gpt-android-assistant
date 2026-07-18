package com.openclaw.assistant.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordServiceTest {

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
