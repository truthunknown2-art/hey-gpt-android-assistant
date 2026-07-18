package com.openclaw.assistant.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.backend.VoiceBackendSelector
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.gateway.GatewayVoiceTurnController
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/**
 * Voice Interaction Session
 * Handles actual voice interaction
 */
class OpenClawSession(
    context: Context,
    initialSessionArgs: Bundle? = null
) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
        private const val INITIAL_FILLER_DELAY_MS = 750L
        private const val INTERRUPT_LISTEN_DELAY_MS = 350L
        private const val SECURE_LOCK_SETTLE_MS = 500L
        private const val SECURE_LOCK_MONITOR_MS = 250L
    }

    private val settings = SettingsRepository.getInstance(context)
    private var sessionArgs: Bundle? = initialSessionArgs
    private val apiClient = OpenClawClient()
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    
    // Repository
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(context)
    private var currentSessionId: String? = null
    
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    @Volatile private var ignoreNextTtsStop = false
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val endedForSecureLock = AtomicBoolean(false)
    private val gatewayVoiceTurns by lazy {
        val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        GatewayVoiceTurnController(runtime::requestGateway)
    }
    private var activeGatewaySessionKey: String? = null
    private val secureLockCheck = Runnable {
        if (requiresUnlockedSession() && keyguardManager.isDeviceLocked) {
            endMainVoiceForSecureLock("screen_off")
        }
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !requiresUnlockedSession()) return
            mainHandler.removeCallbacks(secureLockCheck)
            mainHandler.postDelayed(secureLockCheck, SECURE_LOCK_SETTLE_MS)
        }
    }
    private val interruptReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            if (currentState.value != AssistantState.SPEAKING &&
                currentState.value != AssistantState.PREPARING_SPEECH) return
            Log.d(TAG, "Barge-in interrupt received in OpenClawSession")
            interruptAndListen()
        }
    }

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization

    private fun effectiveVoiceTarget(): String {
        return sessionArgs?.getString(OpenClawAssistantService.EXTRA_VOICE_TARGET)
            ?.takeIf { it == SettingsRepository.VOICE_TARGET_OPENCLAW || it == SettingsRepository.VOICE_TARGET_HERMES }
            ?: if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                SettingsRepository.VOICE_TARGET_OPENCLAW
            } else {
                SettingsRepository.VOICE_TARGET_HERMES
            }
    }

    private fun isOpenClawVoiceTarget(): Boolean {
        return effectiveVoiceTarget() == SettingsRepository.VOICE_TARGET_OPENCLAW
    }

    private fun isHeyGptMainProfile(): Boolean =
        sessionArgs?.getString(OpenClawAssistantService.EXTRA_VOICE_PROFILE) ==
            OpenClawAssistantService.VOICE_PROFILE_HEY_GPT_MAIN

    private fun forcedSessionKey(): String? =
        sessionArgs?.getString(OpenClawAssistantService.EXTRA_SESSION_KEY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun continuousModeForSession(): Boolean =
        VoiceSessionPolicy.continuousMode(
            forceContinuous =
                sessionArgs?.getBoolean(OpenClawAssistantService.EXTRA_FORCE_CONTINUOUS, false) == true,
            configuredContinuous = settings.continuousMode,
        )

    private fun requiresUnlockedSession(): Boolean =
        sessionArgs?.getBoolean(OpenClawAssistantService.EXTRA_REQUIRE_UNLOCKED, false) == true

    private fun ensureSessionUnlocked(reason: String): Boolean {
        if (!requiresUnlockedSession() || !keyguardManager.isDeviceLocked) return true
        endMainVoiceForSecureLock(reason)
        return false
    }

    private fun startSecureLockMonitor() {
        if (!requiresUnlockedSession()) return
        scope.launch {
            while (isActive && !endedForSecureLock.get()) {
                delay(SECURE_LOCK_MONITOR_MS)
                if (keyguardManager.isDeviceLocked) {
                    endMainVoiceForSecureLock("monitor")
                    break
                }
            }
        }
    }

    private fun endMainVoiceForSecureLock(reason: String) {
        if (!requiresUnlockedSession() || !endedForSecureLock.compareAndSet(false, true)) return
        Log.w(TAG, "Ending tool-capable voice session after secure lock: $reason")
        mainHandler.removeCallbacks(secureLockCheck)
        listeningJob?.cancel()
        speakingJob?.cancel()
        scope.coroutineContext.cancelChildren()
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        runCatching { speechManager.destroy() }
        runCatching { ttsManager.stopAll() }
        abandonAudioFocus()
        currentState.value = AssistantState.IDLE
        SessionForegroundService.stop(context)
        releaseWakeLock()
        sendResumeBroadcast()
        finish()
    }

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()
        
        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
             Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        speechManager = SpeechRecognizerManager(context)
        ttsManager = TTSManager(context)
        val initialized = ttsManager.initializeCurrentProvider()
        Log.e(TAG, "Session TTS: initialized=$initialized ready=${ttsManager.isReady()} error=${ttsManager.getErrorMessage()}")
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            interruptReceiver,
            android.content.IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator already released", e)
        }
    }

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Session foreground service keeps process alive during conversation (prevents MIUI/HyperOS freeze)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            Log.e(TAG, "Initializing ComposeView with owners")
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }
            
            setContent {
                AssistantUI(
                    state = currentState.value,
                    displayText = displayText.value,
                    userQuery = userQuery.value,
                    partialText = partialText.value,
                    errorMessage = errorMessage.value,
                    audioLevel = audioLevel.value,
                    onClose = {
                        isUserDismissed = true
                        finish()
                    },
                    onRetry = { startListening() },
                    onInterrupt = { interruptAndListen() }
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionArgs = args
        endedForSecureLock.set(false)
        activeGatewaySessionKey = null
        mainHandler.removeCallbacks(secureLockCheck)

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // Ensure any existing SpeechRecognizerManager is cleaned up before creating a new one
        if (this::speechManager.isInitialized) {
            try {
                speechManager.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy existing SpeechRecognizerManager before recreation", e)
            }
        }
        speechManager = SpeechRecognizerManager(context)

        if (!ensureSessionUnlocked("on_show")) return

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()
        
        // SESSION MANAGEMENT
        if (isOpenClawVoiceTarget()) {
            // Gateway mode: manage session on the gateway side, not in local DB
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            val forcedSessionKey = forcedSessionKey()
            val newSessionKey = java.util.UUID.randomUUID().toString()
            val selectedSessionKey = VoiceSessionPolicy.selectGatewaySessionKey(
                forcedSessionKey = forcedSessionKey,
                resumeLatestSession = settings.resumeLatestSession,
                currentSessionKey = nodeRuntime.chatSessionKey.value,
                newSessionKey = newSessionKey,
            )
            activeGatewaySessionKey = selectedSessionKey
            if (forcedSessionKey != null) {
                // Voice turns address this session explicitly. Do not move the
                // app's normal chat UI into the ambient voice conversation.
            } else if (!settings.resumeLatestSession) {
                // Start a fresh gateway session with a human-readable label
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val label = String.format(context.getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(selectedSessionKey)
                scope.launch { nodeRuntime.patchChatSession(selectedSessionKey, label) }
            }
            // resumeLatestSession ON → keep the current active gateway session as-is
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                        Log.d(TAG, "Resuming latest session: $currentSessionId")
                    } else {
                        currentSessionId = chatRepository.createSession(title = String.format(context.getString(R.string.default_session_title_format), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                        Log.d(TAG, "Created new session: $currentSessionId")
                    }

                    // Store this ID in settings so ChatActivity and API calls use it
                    currentSessionId?.let { settings.sessionId = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }
        }
        
        // Check settings
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_config_required)
            displayText.value = context.getString(R.string.config_required)
            return
        }

        // For Gateway mode, fail fast if the gateway is not healthy
        if (isOpenClawVoiceTarget()) {
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!nodeRuntime.chatHealthOk.value) {
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_gateway_not_connected)
                displayText.value = context.getString(R.string.config_required)
                return
            }
        }

        startSecureLockMonitor()

        // Start speech recognition
        startListening()
    }
    
    override fun onHide() {
        super.onHide()

        // ユーザーが明示的に Close ボタンを押した場合は、音声状態に関わらず必ずクリーンアップ
        if (isUserDismissed) {
            isUserDismissed = false
            cleanupSession()
            return
        }

        // If a voice session is active (listening, thinking, or speaking),
        // keep resources alive so conversation continues with screen off.
        val state = currentState.value
        val isVoiceActive = state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING ||
                state == AssistantState.PREPARING_SPEECH ||
                state == AssistantState.PROCESSING
        
        if (isVoiceActive) {
            Log.d(TAG, "onHide: voice session active ($state), keeping resources alive")
            // Keep scope, speechManager, ttsManager alive
            // SessionForegroundService is already running
            return
        }

        cleanupSession()
    }

    private fun cleanupSession() {
        mainHandler.removeCallbacks(secureLockCheck)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        abandonAudioFocus()
        SessionForegroundService.stop(context)
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
        releaseWakeLock()

        // Resume Hotword
        sendResumeBroadcast()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        
        // Resume Hotword (safety)
        sendResumeBroadcast()
        try {
            context.unregisterReceiver(interruptReceiver)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(secureLockCheck)

        SessionForegroundService.stop(context)
        ttsManager.shutdown()
        toneGeneratorReleased.set(true)
        toneGenerator.release()
        releaseWakeLock()
    }

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var speakingJob: Job? = null
    private var isUserDismissed = false

    private fun startListening(initialDelayMs: Long = 50L) {
        Log.d(TAG, "startListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        if (!ensureSessionUnlocked("before_listening")) return
        listeningJob?.cancel()
        acquireWakeLock()
        sendPauseBroadcast()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        // Fallback: if SpeechResult.Ready doesn't arrive within 2s, force LISTENING
        // so users don't see "Processing" indefinitely while the recognizer warms up
        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                Log.w(TAG, "SpeechResult.Ready timeout — forcing LISTENING state")
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for resources to be released before reopening the mic
            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val listenResult = withTimeoutOrNull(30_000L) {
                    speechManager.startListening(settings.speechLanguage.ifEmpty { null }, settings.speechSilenceTimeout).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Processing -> {
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> {
                                audioLevel.value = result.rmsdB
                            }
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                // Ensure state is listening if we get partial results
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received: text='${result.text}'")
                                if (!ensureSessionUnlocked("recognition_result")) {
                                    hasActuallySpoken = true
                                    return@collectLatest
                                }
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (isTimeout && continuousModeForSession() && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                ) {
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout) {
                                    // Timeout - close session without error screen
                                    // But only if AI is not currently thinking or speaking
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true // break the loop but don't finish
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        finish() // Close the session
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    currentState.value = AssistantState.ERROR
                                    errorMessage.value = result.message
                                    hasActuallySpoken = true
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (30s). Device may be locked.")
                    // Manual timeout - only close session if not thinking/speaking
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true // break the loop but don't finish
                    } else {
                        finish() // Close the session
                        hasActuallySpoken = true
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private var thinkingSoundJob: Job? = null

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private fun sendToOpenClaw(message: String) {
        Log.d(TAG, "sendToOpenClaw() called, transitioning to THINKING")
        if (!ensureSessionUnlocked("before_processing")) return
        currentState.value = AssistantState.THINKING
        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        // 相槌フレーズの再生（LLMへのリクエストと並行して実行）
        if (settings.fillerPhrasesEnabled) {
            scheduleInitialFillerPhrase()
        }

        scope.launch {
            if (VoiceDispatchPolicy.selectPath(isHeyGptMainProfile()) ==
                VoiceDispatchPath.DEDICATED_GATEWAY
            ) {
                if (!isOpenClawVoiceTarget()) {
                    cancelInitialFillerPhrase()
                    cancelWaitPhraseTimer()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_gateway_not_connected)
                    return@launch
                }
                sendViaGateway(message)
                return@launch
            }

            val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
            val voiceBackendId = resolveVoiceSessionBackendId()
            if (!isOpenClawVoiceTarget() && voiceBackendId == null) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.av_settings_no_hermes)
                return@launch
            }
            val primaryReply = try {
                if (voiceBackendId != null) {
                    com.openclaw.assistant.backend.PrimaryBackendDispatcher.send(
                        context = context,
                        userText = message,
                        backendId = voiceBackendId,
                        sessionId = settings.sessionId,
                        agentId = agentId,
                    )
                } else {
                    null
                }
            } catch (e: Throwable) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = e.message ?: context.getString(R.string.error_network)
                return@launch
            }
            if (primaryReply != null) {
                val text = primaryReply.text
                if (text.isNotBlank()) {
                    displayText.value = text
                    handleResponseReceived(text)
                } else {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                }
                return@launch
            }

            if (!isOpenClawVoiceTarget()) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.av_settings_no_hermes)
                return@launch
            }

            // Save user message to local DB only for HTTP mode
            if (!isOpenClawVoiceTarget()) {
                currentSessionId?.let { sessionId ->
                    chatRepository.addMessage(sessionId, message, isUser = true)
                }
            }

            if (isOpenClawVoiceTarget()) {
                sendViaGateway(message)
            } else {
                sendViaHttp(message)
            }
        }
    }

    private suspend fun resolveVoiceSessionBackendId(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
        return VoiceBackendSelector.selectBackendId(
            voiceTarget = effectiveVoiceTarget(),
            backends = backends,
            gatewayHealthy = (context.applicationContext as OpenClawApplication).nodeRuntime.chatHealthOk.value,
        )
    }

    private suspend fun resolveOpenClawGatewayModel(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
            .filter { it.enabled }
        return backends.firstOrNull {
            it.isPrimary && it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
        }?.modelName?.takeIf { it.isNotBlank() }
            ?: backends.firstOrNull {
                it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
            }?.modelName?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveLegacyOpenClawModel(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
            .filter { it.enabled }
        return backends.firstOrNull {
            it.isPrimary && it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_HTTP
        }?.modelName?.takeIf { it.isNotBlank() }
            ?: backends.firstOrNull {
                it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_HTTP
            }?.modelName?.takeIf { it.isNotBlank() }
    }

    private var waitPhraseJob: Job? = null

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = scope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (currentState.value == AssistantState.THINKING && isActive) {
                playFillerPhrase()
            }
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = scope.launch {
            delay(5000) // 5秒待機
            if (currentState.value == AssistantState.THINKING && isActive) {
                Log.d(TAG, "Wait phrase timer triggered (> 5s). Playing wait phrase.")
                playWaitPhrase()
            }
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val phrase = context.getString(R.string.filler_ok)
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                // 相槌は progress 監視せずに即座に発話だけさせる
                ttsManager.speakWithProgress(phrase).collect {} 
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play filler phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private fun playWaitPhrase() {
        val waitPhrases = listOf(
            context.getString(R.string.wait_phrase_let_me_think),
            context.getString(R.string.wait_phrase_one_moment),
            context.getString(R.string.wait_phrase_checking)
        )
        val phrase = waitPhrases.random()
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                ttsManager.speakWithProgress(phrase).collect {}
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wait phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            cancelInitialFillerPhrase()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_gateway_not_connected)
            return
        }

        try {
            startWaitPhraseTimer() // 待ちフレーズのタイマー開始
            val responseText = if (isHeyGptMainProfile()) {
                val sessionKey = activeGatewaySessionKey
                    ?: forcedSessionKey()
                    ?: error("Hey GPT main session key is unavailable")
                gatewayVoiceTurns.ask(
                    sessionKey = sessionKey,
                    agentId = VoiceSessionKeys.VOICE_MAIN_AGENT_ID,
                    message = message,
                    beforeSend = {
                        if (!ensureSessionUnlocked("before_chat_send")) {
                            throw CancellationException("Device became securely locked")
                        }
                    },
                )
            } else {
                val gatewayModel = resolveOpenClawGatewayModel()
                val assistantCountBefore =
                    nodeRuntime.chatMessages.value.count { it.role == "assistant" }
                nodeRuntime.sendChat(
                    message = message,
                    thinking = "low",
                    attachments = emptyList(),
                    modelName = gatewayModel,
                )
                withTimeoutOrNull(60_000L) {
                    nodeRuntime.chatMessages
                        .first { messages ->
                            messages.count { it.role == "assistant" } > assistantCountBefore
                        }
                        .lastOrNull { it.role == "assistant" }
                        ?.content?.firstOrNull { it.type == "text" }?.text
                }
            }

            cancelWaitPhraseTimer()

            if (responseText != null) {
                displayText.value = responseText
                handleResponseReceived(responseText)
            } else {
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_no_response)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "Gateway error", e)
            cancelInitialFillerPhrase()
            cancelWaitPhraseTimer()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = e.message ?: context.getString(R.string.error_network)
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }

        startWaitPhraseTimer()

        // Route through the configured Primary backend first. Older installs
        // without migrated backend records fall through to the legacy HTTP path.
        val primaryReply = try {
            com.openclaw.assistant.backend.PrimaryBackendDispatcher.sendPrimary(
                context = context,
                userText = message,
                sessionId = settings.sessionId,
                agentId = agentId,
            )
        } catch (e: Throwable) {
            cancelWaitPhraseTimer(); cancelInitialFillerPhrase(); stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = e.message ?: context.getString(R.string.error_network)
            return
        }
        if (primaryReply != null) {
            cancelWaitPhraseTimer()
            val text = primaryReply.text
            if (text.isNotBlank()) {
                displayText.value = text
                handleResponseReceived(text)
            } else {
                cancelInitialFillerPhrase(); stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_no_response)
            }
            return
        }

        val result = apiClient.sendMessage(
            httpUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId,
            modelName = resolveLegacyOpenClawModel(),
        )

        cancelWaitPhraseTimer()

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText()
                if (responseText != null) {
                    displayText.value = responseText
                    handleResponseReceived(responseText)
                } else if (response.error != null) {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = response.error
                } else {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "API error", error)
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = error.message ?: context.getString(R.string.error_network)
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()
        if (!ensureSessionUnlocked("before_response")) return

        // Save AI response to local DB only for HTTP mode
        if (!isOpenClawVoiceTarget()) {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
        }

        if (settings.ttsEnabled) {
            // Thinking sound continues until actual audio playback starts
            speakResponse(responseText)
        } else if (continuousModeForSession()) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            stopThinkingSound()
            // TTS disabled & continuous conversation OFF: Return to IDLE
            currentState.value = AssistantState.IDLE
            SessionForegroundService.stop(context)
        }
    }

    private fun interruptAndListen() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        sendPauseBroadcast()
        ignoreNextTtsStop = true
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        speechManager.destroy()
        abandonAudioFocus()
        currentState.value = AssistantState.PROCESSING
        partialText.value = ""
        errorMessage.value = null
        scope.launch {
            delay(INTERRUPT_LISTEN_DELAY_MS)
            startListening()
        }
    }

    private fun speakResponse(text: String) {
        Log.d(TAG, "speakResponse() called, text length=${text.length}")
        // Thinking sound continues until TTSState.Speaking is received
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            ignoreNextTtsStop = false
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    if (!ensureSessionUnlocked("before_speech_chunk")) return@launch
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Preparing -> {
                                Log.d(TAG, "TTS Preparing")
                                // Keep PREPARING_SPEECH state
                            }
                            is TTSState.Speaking -> {
                                Log.d(TAG, "TTS Speaking")
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                                // Barge-inが有効な場合、読み上げ開始時にHotwordServiceを再開する
                                if (settings.ttsBargeInEnabled) {
                                    sendResumeBroadcast()
                                }
                            }
                            is TTSState.Done -> {
                                Log.d(TAG, "TTS Done")
                                chunkSuccess = true
                            }
                            is TTSState.Error -> {
                                if (ignoreNextTtsStop) {
                                    Log.d(TAG, "Ignoring TTS stop during controlled interruption")
                                    return@collect
                                }
                                Log.e(TAG, "TTS Error: ${state.message}")
                                chunkSuccess = false
                            }
                        }
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }

                // abandonAudioFocus() : 連続対話やBarge-in対応のため、ここでは即座にfocusを捨てない運用にするか、
                // 次のアクション(マイクの起動等)まで保持しておく選択もある。
                // 既存の挙動を尊重し一旦残す
                abandonAudioFocus()

                // 読み上げ終了時にBarge-inのために再開していたHotwordServiceを再度一時停止させる
                // (この後すぐにlisteningに入る場合はそちらでpauseされるが念のため)
                if (settings.ttsBargeInEnabled && !continuousModeForSession()) {
                    sendPauseBroadcast()
                }

                if (ignoreNextTtsStop) {
                    return@launch
                }

                if (success) {
                    // After speech completion, if continuous conversation mode is enabled, start listening again
                    if (continuousModeForSession()) {
                        Log.d(TAG, "TTS complete, continuous mode ON. Starting 2nd rally startListening() in 500ms")
                        delay(500)
                        startListening()
                    } else {
                        // If continuous conversation is OFF, end the session
                        currentState.value = AssistantState.IDLE
                        releaseWakeLock()
                        SessionForegroundService.stop(context)
                    }
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_speech_general)
                }
            } catch (e: CancellationException) {
                if (!ignoreNextTtsStop) {
                    throw e
                }
            } catch (e: Exception) {
                if (ignoreNextTtsStop) {
                    return@launch
                }
                Log.e(TAG, "TTS speak error", e)
                abandonAudioFocus()
                releaseWakeLock()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_speech_general)
            }
        }
    }

    private fun stopAuxiliarySpeech() {
        val hadActiveAuxSpeech = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (hadActiveAuxSpeech) {
            ttsManager.stop()
        }
    }



    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

/**
 * Assistant state
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

/**
 * Assistant UI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone icon
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            // Audio level animation
            // RMS dB usually ranges from roughly -2 (silence) to 10+ (loud speech).
            // We map this to a scale factor.
            // Shift -2 to 0: (level + 2)
            // Divide by expected max roughly 12: ((level + 2) / 12)
            // Clamp to 0..1 range just in case.
            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            
            // Scale increases up to 1.5x for loud sounds
            val targetLevelScale = 1f + (normalizedLevel * 0.5f) 
            
            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow), // Slightly faster response
                label = "audio_level_scale"
            )

            // Combine breathing (baseScale) with voice reaction. 
            // When speaking loudly, the voice reaction should dominate.
            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f

            // Morphing sphere — audio-reactive blob that
            // breathes / ripples / pulses by state. The mic icon floats above
            // the sphere so the existing tap-to-interrupt affordance still
            // works while the assistant is speaking.
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .then(
                        if (state == AssistantState.SPEAKING || state == AssistantState.PREPARING_SPEECH) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.interrupt_description),
                                role = Role.Button
                            ) { onInterrupt() }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                com.openclaw.assistant.ui.voice.MorphingSphere(
                    state = state,
                    audioLevel = normalizedLevel,
                )
                Icon(
                    imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status text
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.PREPARING_SPEECH -> stringResource(R.string.preparing_speech)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                },
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Recognized text (partial results)
            if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                Text(
                    text = partialText,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }

            // User Query (Final Result)
            if (userQuery.isNotBlank()) {
                Text(
                    text = "$userQuery",
                    fontSize = 16.sp,
                    color = Color.DarkGray, // Slightly different color
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Main text
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Text(
                    text = displayText,
                    fontSize = 18.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
