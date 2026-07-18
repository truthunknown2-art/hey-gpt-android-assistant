package com.openclaw.assistant.chatgpt

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.util.Log

/**
 * Wakes the display and dismisses only a keyguard Android already considers
 * trusted or non-secure. A securely locked device is rejected by the launcher
 * before this activity starts; requestDismissKeyguard never bypasses user auth.
 */
class TrustedKeyguardHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        when {
            keyguardManager.isDeviceLocked -> failHandoff("Device became securely locked")
            !keyguardManager.isKeyguardLocked -> triggerAssistant()
            else -> keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = triggerAssistant()
                    override fun onDismissCancelled() = failHandoff("Trusted keyguard dismissal cancelled")
                    override fun onDismissError() = failHandoff("Trusted keyguard dismissal failed")
                },
            )
        }
    }

    private fun triggerAssistant() {
        window.decorView.postDelayed({
            if (!AssistTriggerAccessibilityService.triggerSystemAssistant()) {
                Log.w(TAG, "Assistant trigger unavailable after trusted-keyguard dismissal")
                ChatGptLiveLauncher.postFallbackNotification(this)
            }
            finishAndRemoveTask()
        }, ASSISTANT_SETTLE_DELAY_MS)
    }

    private fun failHandoff(reason: String) {
        Log.w(TAG, reason)
        ChatGptLiveLauncher.postFallbackNotification(this)
        finishAndRemoveTask()
    }

    companion object {
        private const val TAG = "TrustedKeyguardHandoff"
        private const val ASSISTANT_SETTLE_DELAY_MS = 350L
    }
}
