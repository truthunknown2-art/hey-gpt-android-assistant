package com.openclaw.assistant.service

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.util.Log
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.broker.AssistantPrivateReadGrants
import com.openclaw.assistant.broker.AssistantPrivateReadApprovals
import com.openclaw.assistant.broker.AssistantPresenceLeases
import com.openclaw.assistant.broker.PresenceLease
import com.openclaw.assistant.broker.PresenceLeaseValidation
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.gateway.GatewayVoiceTurnController
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.TTSUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class AmbientVoiceUiState(
    val token: String? = null,
    val active: Boolean = false,
    val state: AssistantState = AssistantState.IDLE,
    val userText: String = "",
    val assistantText: String = "",
    val partialText: String = "",
    val error: String? = null,
    val audioLevel: Float = 0f,
)

object AmbientVoiceSessionRegistry {
    private val mutableState = MutableStateFlow(AmbientVoiceUiState())
    val state: StateFlow<AmbientVoiceUiState> = mutableState.asStateFlow()

    internal fun publish(state: AmbientVoiceUiState) {
        mutableState.value = state
    }
}

/** Owns one unlocked, continuous OpenClaw voice session inside HotwordService. */
internal class AmbientVoiceSession(
    context: Context,
    private val onEnded: suspend (token: String, reason: String) -> Unit,
) {
    companion object {
        private const val TAG = "AmbientVoiceSession"
        private const val STT_READY_TIMEOUT_MS = 8_000L
        private const val STT_TURN_TIMEOUT_MS = 35_000L
        private const val LOCK_MONITOR_MS = 250L
        private const val NEXT_TURN_DELAY_MS = 750L
    }

    private val app = context.applicationContext as OpenClawApplication
    private val settings = SettingsRepository.getInstance(app)
    private val keyguardManager = app.getSystemService(KeyguardManager::class.java)
    private val speechManager = SpeechRecognizerManager(app)
    private val ttsManager = TTSManager(app)
    private val turns = GatewayVoiceTurnController(app.nodeRuntime::requestGateway)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private var sessionJob: Job? = null
    private var lockMonitorJob: Job? = null
    private var token: String = ""
    private var sessionKey: String = ""
    private var targetDeviceId: String = ""
    private var presenceLease: PresenceLease? = null
    private var uiState = AmbientVoiceUiState()
    private var wakeLock: PowerManager.WakeLock? = null

    val isActive: Boolean
        get() = active.get()

    fun start(token: String, sessionKey: String): Boolean {
        if (token.isBlank() || sessionKey.isBlank() || !active.compareAndSet(false, true)) return false
        this.token = token
        this.sessionKey = sessionKey
        targetDeviceId = app.nodeRuntime.deviceId.orEmpty()
        if (keyguardManager.isDeviceLocked || !app.nodeRuntime.chatHealthOk.value || targetDeviceId.isBlank()) {
            finish(if (keyguardManager.isDeviceLocked) "secure_lock" else "gateway_unavailable")
            return false
        }
        AssistantPrivateReadApprovals.registry.revokeSession(sessionKey, targetDeviceId)
        AssistantPrivateReadGrants.manager.revokeSession(sessionKey, targetDeviceId)
        presenceLease = AssistantPresenceLeases.manager.issue(sessionKey, targetDeviceId)

        acquireWakeLock()
        if (settings.ttsEnabled && !ttsManager.initializeCurrentProvider()) {
            finish("tts_unavailable")
            return false
        }
        publish(state = AssistantState.PROCESSING)
        lockMonitorJob = scope.launch {
            while (isActive && active.get()) {
                delay(LOCK_MONITOR_MS)
                if (!ensureUnlocked("lock_monitor")) break
            }
        }
        sessionJob = scope.launch {
            try {
                while (isActive && active.get()) {
                    val transcript = listenForTurn()
                    if (!ensureUnlocked("after_transcript")) return@launch
                    publish(
                        state = AssistantState.THINKING,
                        userText = transcript,
                        assistantText = "",
                        partialText = "",
                    )
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                    val response = turns.ask(
                        sessionKey = sessionKey,
                        agentId = VoiceSessionKeys.VOICE_MAIN_AGENT_ID,
                        message = transcript,
                        beforeSend = {
                            if (!ensureUnlocked("before_chat_send")) {
                                throw CancellationException("Device became securely locked")
                            }
                        },
                    ) ?: error(app.getString(R.string.error_no_response))
                    if (!ensureUnlocked("after_gateway_response")) return@launch
                    publish(
                        state = if (settings.ttsEnabled) {
                            AssistantState.PREPARING_SPEECH
                        } else {
                            AssistantState.IDLE
                        },
                        assistantText = response,
                    )
                    if (settings.ttsEnabled) speak(response)
                    delay(NEXT_TURN_DELAY_MS)
                }
            } catch (error: CancellationException) {
                if (active.get()) finish("cancelled")
            } catch (error: Exception) {
                Log.e(TAG, "Ambient voice session failed", error)
                publish(state = AssistantState.ERROR, error = error.message ?: app.getString(R.string.error_network))
                finish("session_error")
            }
        }
        return true
    }

    fun stop(requestToken: String, reason: String) {
        if (requestToken != token) return
        finish(reason)
    }

    private suspend fun listenForTurn(): String {
        if (!ensureUnlocked("before_listening")) throw CancellationException("Secure lock")
        publish(
            state = AssistantState.PROCESSING,
            userText = "",
            assistantText = "",
            partialText = "",
            error = null,
            audioLevel = 0f,
        )
        speechManager.destroyAndAwait()
        return kotlinx.coroutines.coroutineScope {
            val results = speechManager.startListening(
                settings.speechLanguage.ifBlank { null },
                settings.speechSilenceTimeout,
            ).produceIn(this)
            try {
                awaitReady(results)
                awaitTranscript(results)
            } finally {
                results.cancel()
                speechManager.destroyAndAwait()
            }
        }
    }

    private suspend fun awaitReady(results: ReceiveChannel<SpeechResult>) {
        withTimeout(STT_READY_TIMEOUT_MS) {
            while (true) {
                when (val result = results.receiveCatching().getOrNull()
                    ?: error(app.getString(R.string.error_speech_client))) {
                    SpeechResult.Ready -> {
                        publish(state = AssistantState.LISTENING)
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        return@withTimeout
                    }
                    is SpeechResult.Error -> error(result.message)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun awaitTranscript(results: ReceiveChannel<SpeechResult>): String =
        withTimeout(STT_TURN_TIMEOUT_MS) {
            while (true) {
                when (val result = results.receiveCatching().getOrNull()
                    ?: error(app.getString(R.string.error_speech_client))) {
                    SpeechResult.Listening -> publish(state = AssistantState.LISTENING)
                    SpeechResult.Processing -> publish(state = AssistantState.PROCESSING)
                    is SpeechResult.RmsChanged -> publish(audioLevel = result.rmsdB)
                    is SpeechResult.PartialResult -> publish(
                        state = AssistantState.LISTENING,
                        partialText = result.text,
                    )
                    is SpeechResult.Result -> return@withTimeout result.text
                    is SpeechResult.Error -> error(result.message)
                    SpeechResult.Ready -> Unit
                }
            }
            error(app.getString(R.string.error_no_recognition_result))
        }

    private suspend fun speak(text: String) {
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)
        val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
        val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
        for (chunk in chunks) {
            if (!ensureUnlocked("before_tts_chunk")) throw CancellationException("Secure lock")
            var complete = false
            ttsManager.speakWithProgress(chunk).collect { state ->
                when (state) {
                    is TTSState.Preparing -> publish(state = AssistantState.PREPARING_SPEECH)
                    is TTSState.Speaking -> publish(state = AssistantState.SPEAKING)
                    is TTSState.Done -> complete = true
                    is TTSState.Error -> error(state.message)
                }
            }
            if (!complete) error(app.getString(R.string.tts_error_generic))
        }
    }

    private fun ensureUnlocked(reason: String): Boolean {
        if (keyguardManager.isDeviceLocked) {
            Log.w(TAG, "Ending ambient voice session after secure lock: $reason")
            finish("secure_lock")
            return false
        }
        if (!app.nodeRuntime.chatHealthOk.value) {
            Log.w(TAG, "Ending ambient voice session after Gateway disconnect: $reason")
            finish("gateway_disconnected")
            return false
        }
        val lease = presenceLease
        if (lease == null) {
            finish("presence_lease_missing")
            return false
        }
        return when (val validation = AssistantPresenceLeases.manager.validateAndRenew(
            lease.leaseId,
            sessionKey,
            targetDeviceId,
        )) {
            is PresenceLeaseValidation.Valid -> {
                presenceLease = validation.lease
                true
            }
            is PresenceLeaseValidation.Rejected -> {
                Log.w(TAG, "Ending ambient voice session after presence rejection: ${validation.reason}")
                finish("presence_lease_rejected")
                false
            }
        }
    }

    private fun publish(
        state: AssistantState = uiState.state,
        userText: String = uiState.userText,
        assistantText: String = uiState.assistantText,
        partialText: String = uiState.partialText,
        error: String? = uiState.error,
        audioLevel: Float = uiState.audioLevel,
    ) {
        uiState = AmbientVoiceUiState(
            token = token,
            active = active.get(),
            state = state,
            userText = userText,
            assistantText = assistantText,
            partialText = partialText,
            error = error,
            audioLevel = audioLevel,
        )
        AmbientVoiceSessionRegistry.publish(uiState)
    }

    private fun finish(reason: String) {
        if (!active.get() || !finishing.compareAndSet(false, true)) return
        AssistantPrivateReadApprovals.registry.revokeSession(sessionKey, targetDeviceId)
        AssistantPrivateReadGrants.manager.revokeSession(sessionKey, targetDeviceId)
        presenceLease?.let { AssistantPresenceLeases.manager.revoke(it.leaseId) }
        presenceLease = null
        val capturedSessionJob = sessionJob
        val capturedMonitorJob = lockMonitorJob
        sessionJob = null
        lockMonitorJob = null
        cleanupScope.launch {
            performAmbientVoiceTeardown(
                cancelAndJoinTurns = {
                    capturedSessionJob?.cancel()
                    capturedMonitorJob?.cancel()
                    listOfNotNull(capturedSessionJob, capturedMonitorJob).joinAll()
                    scope.cancel()
                },
                releaseSpeech = { speechManager.destroyAndAwait() },
                stopSpeechOutput = {
                    runCatching { ttsManager.stopAll() }
                    runCatching { ttsManager.shutdown() }
                    runCatching { toneGenerator.stopTone() }
                    runCatching { toneGenerator.release() }
                },
                publishInactive = {
                    active.set(false)
                    uiState = uiState.copy(active = false, state = AssistantState.IDLE, partialText = "")
                    AmbientVoiceSessionRegistry.publish(uiState)
                },
                restartHotword = { onEnded(token, reason) },
                releaseWakeLock = ::releaseWakeLock,
            )
        }
    }

    private fun acquireWakeLock() {
        val powerManager = app.getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::AmbientVoice",
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

internal suspend fun performAmbientVoiceTeardown(
    cancelAndJoinTurns: suspend () -> Unit,
    releaseSpeech: suspend () -> Unit,
    stopSpeechOutput: () -> Unit,
    publishInactive: () -> Unit,
    restartHotword: suspend () -> Unit,
    releaseWakeLock: () -> Unit,
) {
    try {
        cancelAndJoinTurns()
        releaseSpeech()
        stopSpeechOutput()
        publishInactive()
        restartHotword()
    } finally {
        releaseWakeLock()
    }
}
