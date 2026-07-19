package com.openclaw.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.service.NodeForegroundService

/** Restores hotword listening and its device-action node after reboot or app update. */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isRecoveryAction(intent.action)) return

        Log.d(TAG, "Restoring hotword service after ${intent.action}")

        val settings = SettingsRepository.getInstance(context)

        val shouldRestore = shouldRestoreAssistantServices(
            hotwordEnabled = settings.hotwordEnabled,
            hasUsableWakeTarget = settings.hasUsableWakeTarget(),
            hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
        if (!shouldRestore) {
            Log.w(TAG, "Assistant recovery requirements are not met; skipping services")
            return
        }

        Log.d(TAG, "Starting HotwordService")
        HotwordService.start(context)

        // The helper node owns the narrow device-action commands. Initialize its
        // paired runtime before starting the service so onCreate cannot race it.
        runCatching {
            (context.applicationContext as OpenClawApplication).ensureRuntime()
            NodeForegroundService.start(context)
        }.onFailure { error ->
            Log.e(TAG, "Failed to restore assistant node service", error)
        }
    }

    internal fun isRecoveryAction(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

    internal fun shouldRestoreAssistantServices(
        hotwordEnabled: Boolean,
        hasUsableWakeTarget: Boolean,
        hasRecordAudioPermission: Boolean,
    ): Boolean = hotwordEnabled && hasUsableWakeTarget && hasRecordAudioPermission
}
