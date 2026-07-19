package com.openclaw.assistant.receiver

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    private val receiver = BootReceiver()

    @Test
    fun `recovery actions include boot and in-place app replacement`() {
        assertTrue(receiver.isRecoveryAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(receiver.isRecoveryAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `unrelated and missing actions do not restore hotword service`() {
        assertFalse(receiver.isRecoveryAction(Intent.ACTION_PACKAGE_REPLACED))
        assertFalse(receiver.isRecoveryAction(null))
    }

    @Test
    fun `assistant services restore only for an enabled authorized wake target`() {
        assertTrue(receiver.shouldRestoreAssistantServices(true, true, true))
        assertFalse(receiver.shouldRestoreAssistantServices(false, true, true))
        assertFalse(receiver.shouldRestoreAssistantServices(true, false, true))
        assertFalse(receiver.shouldRestoreAssistantServices(true, true, false))
    }
}
