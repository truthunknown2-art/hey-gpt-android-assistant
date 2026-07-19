package com.openclaw.assistant.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "AndroidTTSProvider"

private val SENTENCE_ENDERS = listOf("。", "．", ". ", "! ", "? ", "！", "？")
private val COMMA_ENDERS = listOf("。", "，", ", ")

/**
 * Android native TTS provider (wrapper around TextToSpeech)
 */
class AndroidTTSProvider(private val context: Context) : TTSProvider, PrivateTTSProvider {
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = SettingsRepository.getInstance(context)
    
    init {
        initialize()
    }
    
    private fun initialize() {
        val preferredEngine = selectAndroidTtsEngine(
            configuredEngine = settings.ttsEngine,
            installedEngines = installedTtsEngines(),
        )
        
        if (preferredEngine != null) {
            Log.d(TAG, "Initializing with preferred engine: $preferredEngine")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Initialized with preferred engine")
                    onInitSuccess()
                } else {
                    Log.w(TAG, "Preferred engine failed, falling back to default")
                    tryDefaultEngine()
                }
            }, preferredEngine)
        } else {
            tryDefaultEngine()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedTtsEngines(): Set<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            context.packageManager.queryIntentServices(intent, 0)
        }
        return services.mapNotNullTo(linkedSetOf()) { it.serviceInfo?.packageName }
    }
    
    private fun tryDefaultEngine() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Initialized with default engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Failed to initialize TTS")
            }
        }
    }
    
    private fun onInitSuccess() {
        isInitialized = true
        setupVoice()
        pendingSpeak?.invoke()
        pendingSpeak = null
    }
    
    private fun setupVoice(requireOffline: Boolean = false): Boolean {
        val tts = this.tts ?: return false
        
        val languageTag = settings.speechLanguage
        val locale = if (languageTag.isNotEmpty()) {
            Locale.forLanguageTag(languageTag)
        } else {
            Locale.getDefault()
        }
        
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        
        // Set speed
        tts.setSpeechRate(settings.ttsSpeed)
        tts.setPitch(1.0f)
        
        // Prefer the engine's best installed voice for the configured locale.
        try {
            val voices = tts.voices.orEmpty()
            val candidates = voices.map(::voiceCandidate)
            val selectedName = selectBestAndroidTtsVoice(
                candidates = candidates,
                targetLocale = tts.language ?: locale,
                allowNetworkRequired = !requireOffline,
            )
            val selectedVoice = voices.firstOrNull { it.name == selectedName }
            if (requireOffline) {
                val selectedCandidate = candidates.firstOrNull { it.name == selectedName }
                val verified = assignVerifiedOfflineAndroidTtsVoice(
                    selected = selectedCandidate,
                    assignVoice = { name ->
                        val voice = voices.firstOrNull { it.name == name }
                        voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
                    },
                    effectiveVoice = { tts.voice?.let(::voiceCandidate) },
                )
                if (!verified) return false
                val effective = tts.voice ?: return false
                Log.i(
                    TAG,
                    "Selected private voice=${effective.name} locale=${effective.locale} " +
                        "quality=${effective.quality} network=${effective.isNetworkConnectionRequired}",
                )
            } else {
                selectedVoice?.let {
                    if (tts.setVoice(it) != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "Failed to select voice=${it.name}")
                    } else {
                        Log.i(
                            TAG,
                            "Selected voice=${it.name} locale=${it.locale} quality=${it.quality} " +
                                "network=${it.isNetworkConnectionRequired}",
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error selecting voice: ${e.message}")
            if (requireOffline) return false
        }
        return true
    }
    
    override suspend fun speak(text: String): Boolean {
        val maxLen = try {
            TextToSpeech.getMaxSpeechInputLength()
        } catch (e: Exception) {
            3900
        }
        
        val chunks = splitText(text, maxLen * 9 / 10)
        
        for ((index, chunk) in chunks.withIndex()) {
            val success = speakChunk(chunk, index == 0)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed")
                return false
            }
        }
        return true
    }
    
    private suspend fun speakChunk(text: String, isFirst: Boolean): Boolean {
        val timeoutMs = (30_000L + (text.length * 15L)).coerceAtMost(120_000L)
        
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                
                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                
                if (isInitialized) {
                    setupVoice()
                    tts?.setOnUtteranceProgressListener(listener)
                    val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val speakResult = tts?.speak(text, queueMode, null, utteranceId)
                    
                    if (speakResult != TextToSpeech.SUCCESS) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                } else {
                    pendingSpeak = {
                        setupVoice()
                        tts?.setOnUtteranceProgressListener(listener)
                        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        tts?.speak(text, queueMode, null, utteranceId)
                    }
                }
                
                continuation.invokeOnCancellation { tts?.stop() }
            }
        }
        
        return result ?: false
    }
    
    override fun stop() {
        tts?.stop()
    }
    
    override fun shutdown() {
        tts?.shutdown()
        isInitialized = false
    }
    
    override fun isAvailable(): Boolean = isInitialized
    
    override fun getType(): String = TTSProviderType.LOCAL
    
    override fun getDisplayName(): String = context.getString(R.string.tts_provider_local_name)
    
    override fun isConfigured(): Boolean = true // Local TTS is always configured
    
    override fun getConfigurationError(): String? = null
    
    override fun speakWithProgress(text: String): Flow<TTSState> =
        speakWithProgressInternal(text, requireOffline = false)

    override fun speakPrivateWithProgress(text: String): Flow<TTSState> =
        speakWithProgressInternal(text, requireOffline = true)

    private fun speakWithProgressInternal(text: String, requireOffline: Boolean): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()
        
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }
            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_stopped)))
                close()
            }
            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_generic)))
                close()
            }
        }

        val activeTts = tts
        if (!isInitialized || activeTts == null) {
            trySend(TTSState.Error(context.getString(R.string.tts_error_not_initialized)))
            close()
            return@callbackFlow
        }

        if (requireOffline) {
            when (queuePrivateAndroidTtsSpeech(
                prepareVoice = { setupVoice(requireOffline = true) },
                enqueue = {
                    trySend(TTSState.Preparing)
                    activeTts.setOnUtteranceProgressListener(listener)
                    activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
                },
            )) {
                PrivateAndroidTtsQueueResult.VOICE_UNAVAILABLE -> {
                    trySend(TTSState.Error(context.getString(R.string.tts_error_private_offline_unavailable)))
                    close()
                    return@callbackFlow
                }
                PrivateAndroidTtsQueueResult.QUEUE_FAILED -> {
                    trySend(TTSState.Error(context.getString(R.string.tts_error_generic)))
                    close()
                    return@callbackFlow
                }
                PrivateAndroidTtsQueueResult.QUEUED -> Unit
            }
        } else {
            setupVoice(requireOffline = false)
            trySend(TTSState.Preparing)
            activeTts.setOnUtteranceProgressListener(listener)
            if (activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_generic)))
                close()
                return@callbackFlow
            }
        }
        
        awaitClose { stop() }
    }

    private fun voiceCandidate(voice: android.speech.tts.Voice): AndroidTtsVoiceCandidate =
        AndroidTtsVoiceCandidate(
            name = voice.name,
            languageTag = voice.locale.toLanguageTag(),
            quality = voice.quality,
            latency = voice.latency,
            networkRequired = voice.isNetworkConnectionRequired,
            installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty(),
        )
    
    private fun splitText(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)
        
        val chunks = mutableListOf<String>()
        var remaining = text
        
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }
            
            val searchRange = remaining.substring(0, maxLength)
            val splitIndex = findBestSplitPoint(searchRange)
            
            if (splitIndex > 0) {
                chunks.add(remaining.substring(0, splitIndex).trim())
                remaining = remaining.substring(splitIndex).trim()
            } else {
                chunks.add(remaining.substring(0, maxLength).trim())
                remaining = remaining.substring(maxLength).trim()
            }
        }
        
        return chunks.filter { it.isNotBlank() }
    }
    
    private fun findBestSplitPoint(text: String): Int {
        val paragraphBreak = text.lastIndexOf("\n\n")
        if (paragraphBreak > text.length / 2) return paragraphBreak + 2
        
        var bestPos = -1
        for (ender in SENTENCE_ENDERS) {
            val pos = text.lastIndexOf(ender)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > text.length / 3) return bestPos
        
        val lineBreak = text.lastIndexOf("\n")
        if (lineBreak > text.length / 3) return lineBreak + 1
        
        for (ender in COMMA_ENDERS) {
            val pos = text.lastIndexOf(ender)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > text.length / 3) return bestPos
        
        val space = text.lastIndexOf(" ")
        if (space > text.length / 3) return space + 1
        
        return -1
    }
}
