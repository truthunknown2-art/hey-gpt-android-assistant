package com.openclaw.assistant

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.openclaw.assistant.service.AmbientVoiceSessionRegistry
import com.openclaw.assistant.service.AssistantUI
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/** Optional UI attached to the service-owned ambient voice session. */
class AmbientVoiceActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SESSION_TOKEN = "com.openclaw.assistant.AMBIENT_SESSION_TOKEN"
    }

    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val sessionToken: String
        get() = intent.getStringExtra(EXTRA_SESSION_TOKEN).orEmpty()

    private val sessionEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(EXTRA_SESSION_TOKEN) == sessionToken) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (sessionToken.isBlank() || keyguardManager.isDeviceLocked) {
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this,
            sessionEndedReceiver,
            IntentFilter(HotwordService.ACTION_AMBIENT_SESSION_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            OpenClawAssistantTheme {
                val state by AmbientVoiceSessionRegistry.state.collectAsState()
                LaunchedEffect(state.token, state.active) {
                    if (state.token == sessionToken && !state.active) finish()
                }
                AssistantUI(
                    state = state.state,
                    displayText = state.assistantText,
                    userQuery = state.userText,
                    partialText = state.partialText,
                    errorMessage = state.error,
                    audioLevel = state.audioLevel,
                    onClose = { requestStop("ui_close") },
                    onRetry = { requestStop("ui_retry") },
                    onInterrupt = {},
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (keyguardManager.isDeviceLocked) {
            finish()
            return
        }
        sendBroadcast(
            Intent(HotwordService.ACTION_AMBIENT_UI_ATTACHED)
                .setPackage(packageName)
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken),
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionEndedReceiver) }
        super.onDestroy()
    }

    private fun requestStop(reason: String) {
        sendBroadcast(
            Intent(HotwordService.ACTION_STOP_AMBIENT_SESSION)
                .setPackage(packageName)
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                .putExtra(HotwordService.EXTRA_AMBIENT_STOP_REASON, reason),
        )
        finish()
    }
}
