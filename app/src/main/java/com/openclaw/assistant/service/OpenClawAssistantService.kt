package com.openclaw.assistant.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.openclaw.assistant.chatgpt.ChatGptLiveLauncher

/**
 * Voice Interaction Service
 * Launched as a system assistant with a long press of the home button
 */
class OpenClawAssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OpenClawAssistantSvc"
        private const val PENDING_SESSION_TIMEOUT_MS = 30_000L
        const val ACTION_SHOW_ASSISTANT = "com.openclaw.assistant.ACTION_SHOW_ASSISTANT"
        const val ACTION_HANDOFF_CHATGPT = "com.openclaw.assistant.ACTION_HANDOFF_CHATGPT"
        const val EXTRA_VOICE_TARGET = "com.openclaw.assistant.EXTRA_VOICE_TARGET"
    }

    private var isServiceReady = false
    private var pendingShowSession = false
    private var pendingSessionArgs: Bundle? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSessionTimeoutRunnable = Runnable {
        if (pendingShowSession) {
            Log.w(TAG, "Pending showSession timed out. Clearing.")
            pendingShowSession = false
            pendingSessionArgs = null
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e(TAG, "Assistant trigger receiver triggered: ${intent?.action}")
            when (intent?.action) {
                ACTION_SHOW_ASSISTANT -> triggerShowSession(intent)
                ACTION_HANDOFF_CHATGPT -> launchChatGptHandoff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "VoiceInteractionService onCreate")
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_ASSISTANT)
            addAction(ACTION_HANDOFF_CHATGPT)
        }
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "onStartCommand received: null (system restart)")
            return START_STICKY
        }
        
        val action = intent.action
        Log.e(TAG, "onStartCommand received: $action")
        if (action == ACTION_SHOW_ASSISTANT) {
            triggerShowSession(intent)
        } else if (action == ACTION_HANDOFF_CHATGPT) {
            launchChatGptHandoff()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.e(TAG, "onBind received: ${intent?.action}")
        return super.onBind(intent)
    }

    private fun triggerShowSession(sourceIntent: Intent? = null) {
        val compName = ComponentName(this, OpenClawAssistantService::class.java)
        val isActive = isActiveService(this, compName)
        Log.e(TAG, "triggerShowSession: isServiceReady=$isServiceReady, isActiveService=$isActive")
        val args = buildSessionArgs(sourceIntent)
        
        if (isServiceReady) {
            try {
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called immediately")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call showSession immediately", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        } else {
            Log.e(TAG, "Service not ready. Queuing showSession request.")
            pendingShowSession = true
            pendingSessionArgs = args
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            handler.postDelayed(pendingSessionTimeoutRunnable, PENDING_SESSION_TIMEOUT_MS)
        }
    }

    /**
     * Minimal handoff-only path. It intentionally does not construct an
     * OpenClawSession, recognizer, TTS stack, or require a configured Gateway.
     */
    private fun launchChatGptHandoff() {
        Log.i(TAG, "chatgpt_launch_requested")
        val result = ChatGptLiveLauncher.launch(this)
        Log.i(TAG, "chatgpt_launch_result=$result")
    }

    private fun buildSessionArgs(sourceIntent: Intent?): Bundle {
        return Bundle().apply {
            sourceIntent?.getStringExtra(EXTRA_VOICE_TARGET)?.takeIf { it.isNotBlank() }?.let { target ->
                putString(EXTRA_VOICE_TARGET, target)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        Log.e(TAG, "VoiceInteractionService onReady")
        isServiceReady = true
        if (pendingShowSession) {
            pendingShowSession = false
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            try {
                val args = pendingSessionArgs ?: Bundle()
                pendingSessionArgs = null
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called from onReady (pending)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call pending showSession", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.e(TAG, "VoiceInteractionService onShutdown")
        isServiceReady = false
        pendingShowSession = false
        pendingSessionArgs = null
        handler.removeCallbacks(pendingSessionTimeoutRunnable)
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "debugReceiver already unregistered", e)
        }
    }
}
