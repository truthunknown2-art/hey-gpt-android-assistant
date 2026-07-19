package com.openclaw.assistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Speech Recognition Manager
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var foregroundContextRef: WeakReference<Context>? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupLock = Any()
    private val cleanupJobs = IdentityHashMap<SpeechRecognizer, Job>()

    fun attachForegroundContext(context: Context) {
        foregroundContextRef = WeakReference(context)
    }

    fun clearForegroundContext(context: Context? = null) {
        val current = foregroundContextRef?.get()
        if (context == null || current === context) {
            foregroundContextRef = null
        }
    }

    private fun recognitionContext(): Context {
        return foregroundContextRef?.get() ?: context
    }

    /**
     * Check if speech recognition is available
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(recognitionContext())
    }

    /**
     * Start speech recognition and return results as a Flow
     * If language is null, use system default
     */
    fun startListening(language: String? = null, silenceTimeoutMs: Long = 2500L): Flow<SpeechResult> = callbackFlow {
        // Determine default language
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()
        
        android.util.Log.e("SpeechRecognizerManager", "startListening called, language=$targetLanguage, isAvailable=${isAvailable()}")

        // Never create a new recorder until every tracked teardown has completed.
        destroyAndAwait()
        val currentRecognizer = withContext(Dispatchers.Main.immediate) {
            val recognitionContext = recognitionContext()
            if (SpeechRecognizer.isRecognitionAvailable(recognitionContext)) {
                val created = SpeechRecognizer.createSpeechRecognizer(recognitionContext).also {
                    synchronized(cleanupLock) { recognizer = it }
                }
                android.util.Log.d("SpeechRecognizerManager", "Created new recognizer instance")
                created
            } else {
                null
            }
        }

        if (currentRecognizer == null) {
            android.util.Log.e("SpeechRecognizerManager", "Failed to create recognizer, sending error")
            trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_speech_client)))
            close()
            return@callbackFlow
        }

        android.util.Log.d("SpeechRecognizerManager", "Setting recognition listener on recognizer")
        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                android.util.Log.d("SpeechRecognizerManager", "onReadyForSpeech: recognizer is ready for input")
                trySend(SpeechResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechResult.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                android.util.Log.d("SpeechRecognizerManager", "onEndOfSpeech")
                trySend(SpeechResult.Processing)
            }

            override fun onError(error: Int) {
                android.util.Log.e("SpeechRecognizerManager", "onError: code=$error")
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> context.getString(com.openclaw.assistant.R.string.error_speech_audio)
                    SpeechRecognizer.ERROR_CLIENT -> context.getString(com.openclaw.assistant.R.string.error_speech_client)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(com.openclaw.assistant.R.string.error_speech_permissions)
                    SpeechRecognizer.ERROR_NETWORK -> context.getString(com.openclaw.assistant.R.string.error_speech_network)
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_timeout)
                    SpeechRecognizer.ERROR_NO_MATCH -> context.getString(com.openclaw.assistant.R.string.error_speech_no_match)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(com.openclaw.assistant.R.string.error_speech_busy)
                    SpeechRecognizer.ERROR_SERVER -> context.getString(com.openclaw.assistant.R.string.error_speech_server)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_input_timeout)
                    else -> context.getString(com.openclaw.assistant.R.string.error_speech_unknown, error)
                }
                
                trySend(SpeechResult.Error(errorMessage, error))
                
                // For critical errors, force recreation of the recognizer
                // Soft errors (No Match, Timeout) can reuse the instance for speed
                val isSoftError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                  error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (!isSoftError) {
                    destroy()
                }

                close()
            }

            override fun onResults(results: Bundle?) {
                android.util.Log.d("SpeechRecognizerManager", "onResults received")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.Result(
                        text = matches[0],
                        confidence = confidence?.getOrNull(0) ?: 0f,
                        alternatives = matches.drop(1)
                    ))
                } else {
                    trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_no_recognition_result), SpeechRecognizer.ERROR_NO_MATCH))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.PartialResult(matches[0]))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            
            // Try hidden/unofficial extra to enforce minimum length if supported
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", silenceTimeoutMs)
        }

        // Run on Main thread
        android.util.Log.d("SpeechRecognizerManager", "Dispatching startListening(intent) to Main thread")
        withContext(Dispatchers.Main) {
             try {
                 android.util.Log.d("SpeechRecognizerManager", "Calling recognizer.startListening()")
                 currentRecognizer.startListening(intent)
             } catch (e: Exception) {
                 android.util.Log.e("SpeechRecognizerManager", "startListening() failed", e)
                 trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_start_failed, e.message)))
                 close()
             }
        }

        awaitClose {
            android.util.Log.d("SpeechRecognizerManager", "awaitClose: flow closing, destroying recognizer")
            synchronized(cleanupLock) {
                if (recognizer === currentRecognizer) recognizer = null
            }
            scheduleDestroy(currentRecognizer)
        }
    }

    /**
     * Stop listening manually
     */
    fun stopListening() { 
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() {
        val currentRecognizer = synchronized(cleanupLock) {
            recognizer.also { recognizer = null }
        }
        currentRecognizer?.let(::scheduleDestroy)
    }

    /** Waits until all Android recognizers owned by this manager are cancelled and destroyed. */
    suspend fun destroyAndAwait() {
        val currentRecognizer = synchronized(cleanupLock) {
            recognizer.also { recognizer = null }
        }
        currentRecognizer?.let(::scheduleDestroy)
        while (true) {
            val pending = synchronized(cleanupLock) { cleanupJobs.values.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private fun scheduleDestroy(target: SpeechRecognizer): Job = synchronized(cleanupLock) {
        cleanupJobs[target]?.let { return@synchronized it }
        lateinit var job: Job
        job = cleanupScope.launch(start = CoroutineStart.LAZY) {
            runCatching { target.cancel() }
                .onFailure { error ->
                    android.util.Log.w("SpeechRecognizerManager", "Recognizer cancellation failed", error)
                }
            runCatching { target.destroy() }
                .onFailure { error ->
                    android.util.Log.w("SpeechRecognizerManager", "Recognizer destruction failed", error)
                }
        }
        cleanupJobs[target] = job
        job.invokeOnCompletion {
            synchronized(cleanupLock) {
                if (cleanupJobs[target] === job) cleanupJobs.remove(target)
            }
        }
        job.start()
        job
    }
}

/**
 * Speech recognition results
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Listening : SpeechResult()
    object Processing : SpeechResult()
    data class RmsChanged(val rmsdB: Float) : SpeechResult()
    data class PartialResult(val text: String) : SpeechResult()
    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    data class Error(val message: String, val code: Int? = null) : SpeechResult()
}
