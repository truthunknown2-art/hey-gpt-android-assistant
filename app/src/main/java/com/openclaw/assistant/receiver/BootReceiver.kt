package com.openclaw.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService

/** Restores hotword listening after a reboot or an in-place app update. */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isRecoveryAction(intent.action)) return

        Log.d(TAG, "Restoring hotword service after ${intent.action}")

        val settings = SettingsRepository.getInstance(context)

        if (settings.hotwordEnabled && settings.hasUsableWakeTarget()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Starting HotwordService")
                HotwordService.start(context)
            } else {
                Log.w(TAG, "RECORD_AUDIO not granted, skipping HotwordService recovery")
            }
        }
    }

    internal fun isRecoveryAction(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
}
