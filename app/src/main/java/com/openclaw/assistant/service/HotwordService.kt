package com.openclaw.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.chatgpt.ChatGptHandoffTracker
import com.openclaw.assistant.chatgpt.ChatGptLiveLauncher
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Hotword detection service (Vosk)
 */
class HotwordService : Service(), VoskRecognitionListener {

    companion object {
        private const val TAG = "HotwordService"
        private const val NOTIFICATION_ID = 1001
        private const val LOCAL_COMMAND_NOTIFICATION_ID = 5602
        private const val CHANNEL_ID = "hotword_channel"
        private const val LOCAL_COMMAND_CHANNEL_ID = "local_voice_commands"
        private const val SAMPLE_RATE = 16000.0f
        private const val LOCAL_COMMAND_TIMEOUT_MS = 4_000
        private const val LOCAL_COMMAND_SILENCE_MS = 1_200L
        private const val MICROPHONE_RELEASE_TIMEOUT_MS = 3_000L
        const val ACTION_RESUME_HOTWORD = "com.openclaw.assistant.ACTION_RESUME_HOTWORD"
        const val ACTION_PAUSE_HOTWORD = "com.openclaw.assistant.ACTION_PAUSE_HOTWORD"
        
        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Background execution limits prevented starting HotwordService: ${e.message}", e)
                context.stopService(intent)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security limits prevented starting HotwordService: ${e.message}", e)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HotwordService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }

        fun shouldCopyModel(currentVersion: Int, savedVersion: Int, targetDirExists: Boolean, targetDirNotEmpty: Boolean): Boolean {
            return !(savedVersion == currentVersion && targetDirExists && targetDirNotEmpty)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    
    private lateinit var settings: SettingsRepository

    @Volatile private var isListeningForCommand = false
    @Volatile private var isSessionActive = false
    @Volatile private var pendingInterruptLaunch = false
    private var audioRetryCount = 0
    private val MAX_AUDIO_RETRIES = 5
    private var watchdogJob: Job? = null
    private var errorRecoveryJob: Job? = null
    private var retryJob: Job? = null
    private var chatGptLaunchJob: Job? = null
    private var chatGptGraceJob: Job? = null
    private var chatGptMonitorJob: Job? = null
    private var chatGptIdleJob: Job? = null
    private var localCommandCaptureJob: Job? = null
    private var chatGptHandoffActive = false
    private val chatGptHandoffTracker = ChatGptHandoffTracker()
    private val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    private fun debugLog(message: String) {
        if (settings.wakeWordDebugEnabled) HotwordDebugLogger.log(message)
    }

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_HOTWORD -> {
                    Log.d(TAG, "Pause signal received")
                    debugLog("Session started — hotword paused")
                    pendingInterruptLaunch = false
                    isSessionActive = true
                    speechService?.stop()
                    speechService?.shutdown()
                    speechService = null
                    isListeningForCommand = false
                    startWatchdog()
                }
                ACTION_RESUME_HOTWORD -> {
                    Log.d(TAG, "Resume signal received")
                    debugLog("Session ended — resuming hotword")
                    cancelWatchdog()
                    pendingInterruptLaunch = false
                    // Reset both flags to ensure clean state
                    isSessionActive = false
                    isListeningForCommand = false

                    // Ensure speechService is cleaned up
                    speechService?.let {
                        try {
                            it.stop()
                            it.shutdown()
                        } catch (e: Exception) { /* ignore */ }
                    }
                    speechService = null

                    resumeHotwordDetection()
                }
            }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            if (!chatGptHandoffActive || !chatGptHandoffTracker.isArmed) return
            handleExternalRecordingState(!configs.isNullOrEmpty())
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Safety net: catch uncaught Vosk thread crashes to prevent app-wide crash
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isVoskCrash = throwable.stackTrace.any {
                it.className.startsWith("org.vosk")
            }
            if (isVoskCrash) {
                Log.e(TAG, "Caught uncaught Vosk exception on thread ${thread.name}", throwable)
                if (throwable is UnsatisfiedLinkError || throwable.cause is UnsatisfiedLinkError) {
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(throwable)
                    }
                    getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("vosk_unsupported", true).apply()
                    // Don't resume - device doesn't support Vosk
                } else if (throwable is RuntimeException && throwable.message == "error reading audio buffer") {
                    // Mic unavailability during Vosk read — use exponential backoff to avoid
                    // tight restart loops when the mic is persistently unavailable.
                    speechService = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!isSessionActive) {
                            scheduleAudioRetry()
                        }
                    }
                } else {
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(throwable)
                    }
                    speechService = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!isSessionActive) {
                            resumeHotwordDetection()
                        }
                    }
                }
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        settings = SettingsRepository.getInstance(this)

        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction(ACTION_RESUME_HOTWORD)
            addAction(ACTION_PAUSE_HOTWORD)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java).registerAudioRecordingCallback(
            recordingCallback,
            android.os.Handler(android.os.Looper.getMainLooper())
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ requires RECORD_AUDIO runtime permission for foregroundServiceType="microphone"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Cannot start foreground service with microphone type.")
            debugLog("RECORD_AUDIO permission denied — service stopped")
            showPermissionNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            // This can happen on Android 14+ if the app is not in an eligible state
            // (e.g. started from background without a visible activity). Handled gracefully.
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        initVosk()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed. Scheduling restart.")
        val restartIntent = Intent(applicationContext, HotwordService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelWatchdog()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {}
        scope.cancel()
        speechService?.shutdown()
        localCommandCaptureJob?.cancel()
        runCatching {
            getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordingCallback)
        }
    }

    private fun showPermissionNotification() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_permission_title))
            .setContentText(getString(R.string.notification_mic_permission_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showMicUnavailableNotification() {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_unavailable_title))
            .setContentText(getString(R.string.notification_mic_unavailable_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 2, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    LOCAL_COMMAND_CHANNEL_ID,
                    getString(R.string.local_command_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val wakeWordName = settings.getWakeWordDisplayName()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content, wakeWordName))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    private fun initVosk() {
        if (model != null) {
            if (!isSessionActive) startHotwordListening()
            return
        }
        debugLog("Vosk: initializing model...")
        val prefs = getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)

        // Clear vosk_unsupported flag when the app is updated, so the new Vosk
        // native libraries get a chance to load on devices that previously failed.
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 1 }
        val unsupportedSinceVersion = prefs.getInt("vosk_unsupported_version", 0)
        if (prefs.getBoolean("vosk_unsupported", false)) {
            if (unsupportedSinceVersion < currentVersion) {
                Log.d(TAG, "App updated ($unsupportedSinceVersion -> $currentVersion). Retrying Vosk init.")
                prefs.edit().remove("vosk_unsupported").remove("vosk_unsupported_version").apply()
            } else {
                Log.w(TAG, "Vosk is unsupported on this device. Skipping init.")
                debugLog("Vosk: NOT supported on this device")
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val modelPath = copyAssets()
                if (modelPath != null) {
                    debugLog("Vosk: model loaded — starting listener")
                    model = Model(modelPath)
                    withContext(Dispatchers.Main) {
                        if (!isSessionActive) startHotwordListening()
                    }
                } else {
                    debugLog("Vosk: model copy failed")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Vosk native library not supported on this device", e)
                debugLog("Vosk: UnsatisfiedLinkError — native lib not supported")
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("audio_retry_count", audioRetryCount)
                        setCustomKey("is_session_active", isSessionActive)
                        recordException(e)
                    }
                }
                prefs.edit()
                    .putBoolean("vosk_unsupported", true)
                    .putInt("vosk_unsupported_version", currentVersion)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
                debugLog("Vosk: init error — ${e.message}")
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("audio_retry_count", audioRetryCount)
                        setCustomKey("is_session_active", isSessionActive)
                        recordException(e)
                    }
                }
            }
        }
    }

    private fun copyAssets(): String? {
        val targetDir = java.io.File(filesDir, "model")

        // Check version to avoid redundant copy
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) {
            1
        }

        val prefs = getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt("model_version", 0)

        if (!shouldCopyModel(currentVersion, savedVersion, targetDir.exists(), targetDir.list()?.isNotEmpty() == true)) {
            Log.d(TAG, "Model version $savedVersion matches current $currentVersion. Skipping copy.")
            return targetDir.absolutePath
        }

        try {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }

            val success = copyAssetFolder(assets, "model", targetDir.absolutePath)
            if (success) {
                prefs.edit().putInt("model_version", currentVersion).apply()
                return targetDir.absolutePath
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            val files = assetManager.list(fromAssetPath) ?: return false
            java.io.File(toPath).mkdirs()
            var res = true
            for (file in files) {
                if (file.contains(".")) {
                    res = res and copyAsset(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                } else {
                    res = res and copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
            }
            return res
        } catch (e: Exception) {
            return false
        }
    }

    private fun copyAsset(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        var inStream: java.io.`InputStream`? = null
        var outStream: java.io.OutputStream? = null
        try {
            inStream = assetManager.open(fromAssetPath)
            java.io.File(toPath).createNewFile()
            outStream = java.io.FileOutputStream(toPath)
            inStream.copyTo(outStream)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            inStream?.close()
            outStream?.close()
        }
    }

    private fun startHotwordListening() {
        if (model == null || isSessionActive) return

        // Clean up any existing speechService to prevent resource leak
        speechService?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up existing speechService", e)
            }
            speechService = null
        }

        // Verify RECORD_AUDIO permission before touching AudioRecord
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Cannot start hotword listening.")
            debugLog("RECORD_AUDIO permission denied — cannot listen")
            return
        }

        // Pre-validate that AudioRecord can actually be created
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $bufferSize")
            debugLog("AudioRecord.getMinBufferSize failed: $bufferSize")
            scheduleAudioRetry()
            return
        }
        val testRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            null
        }
        if (testRecord == null || testRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize. Mic may be in use or unavailable.")
            debugLog("AudioRecord init failed — mic may be in use or unavailable")
            testRecord?.release()
            scheduleAudioRetry()
            return
        }
        testRecord.release()
        audioRetryCount = 0

        try {
            // Get wake words from settings; add [unk] so Vosk can absorb non-matching speech
            val wakeWords = settings.getWakeWordTargets().map { it.phrase }
            val wakeWordsJson = (wakeWords + "[unk]").joinToString("\", \"", "[\"", "\"]")
            Log.d(TAG, "Starting hotword detection with words: $wakeWordsJson")
            debugLog("Listening for: ${wakeWords.joinToString()} (sensitivity=${settings.wakeWordSensitivity})")

            val rec = Recognizer(model, SAMPLE_RATE, wakeWordsJson)
            speechService = SpeechService(rec, SAMPLE_RATE)
            speechService?.startListening(this)
            Log.d(TAG, "Hotword listening started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotword listening", e)
            debugLog("Failed to start listener: ${e.message}")
            speechService = null
            scheduleAudioRetry()
        }
    }

    private fun scheduleAudioRetry() {
        if (audioRetryCount >= MAX_AUDIO_RETRIES) {
            Log.e(TAG, "Max audio retries ($MAX_AUDIO_RETRIES) exceeded. Giving up.")
            debugLog("Mic unavailable after $MAX_AUDIO_RETRIES retries — giving up")
            audioRetryCount = 0
            showMicUnavailableNotification()
            if (BuildConfig.FIREBASE_ENABLED) {
                FirebaseCrashlytics.getInstance().recordException(
                    RuntimeException("Microphone unavailable after $MAX_AUDIO_RETRIES retries")
                )
            }
            return
        }
        audioRetryCount++
        val delayMs = (2000L * audioRetryCount).coerceAtMost(10000L)
        Log.w(TAG, "Scheduling audio retry #$audioRetryCount in ${delayMs}ms")
        debugLog("Mic unavailable — retry #$audioRetryCount in ${delayMs}ms")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (!isSessionActive) {
                startHotwordListening()
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (isListeningForCommand || isSessionActive) return
        hypothesis?.let {
            try {
                val json = JSONObject(it)
                val text = json.optString("text", "")

                // Check against configured wake words with confidence threshold
                val wakeWordTargets = settings.getWakeWordTargets()
                val maxConfidence = WakeWordTargetMatcher.maxConfidence(text, wakeWordTargets)
                val detectedTarget = WakeWordTargetMatcher.select(
                    text = text,
                    targets = wakeWordTargets,
                    threshold = settings.wakeWordSensitivity,
                )

                if (text.isNotEmpty()) {
                    debugLog("heard: \"$text\" conf=${"%.2f".format(maxConfidence)}")
                }

                detectedTarget?.let { target ->
                    Log.e(TAG, "Hotword detected! Text: $text")
                    debugLog("DETECTED: \"$text\" -> ${target.target} conf=${"%.2f".format(maxConfidence)}")
                    onHotwordDetected(target)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Vosk result: $it", e)
            }
            Unit
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk Error: " + exception?.message)
        debugLog("Vosk error: ${exception?.message} — recovering in 3s")
        if (isSessionActive) return
        errorRecoveryJob?.cancel()
        errorRecoveryJob = scope.launch {
            delay(3000)
            if (!isSessionActive) resumeHotwordDetection()
        }
    }

    override fun onTimeout() {
        if (!isListeningForCommand && !isSessionActive) {
            speechService?.startListening(this)
        }
    }

    private fun onHotwordDetected(target: SettingsRepository.WakeWordTarget) {
        if (isListeningForCommand || (isSessionActive && !settings.ttsBargeInEnabled)) return
        isListeningForCommand = true
        startWatchdog()

        Log.d(TAG, "Hotword Detected! Triggering ${target.target} Assistant Overlay or Barge-in...")
        playWakeSound(target.wakeSound)

        // Broadcast to interrupt ongoing TTS (Barge-in)
        val interruptIntent = Intent("com.openclaw.assistant.ACTION_INTERRUPT_TTS")
        interruptIntent.setPackage(packageName)
        sendBroadcast(interruptIntent)
        pendingInterruptLaunch = true

        // Stop service on Main thread to avoid race conditions
        scope.launch {
            // Ensure speechService is safely stopped
            speechService?.let {
                try {
                    it.stop()
                    it.shutdown()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop speech service", e)
                }
            }
            speechService = null

            val isChatGptHandoff = target.target == SettingsRepository.VOICE_TARGET_CHATGPT
            if (isChatGptHandoff) {
                pendingInterruptLaunch = false
                isSessionActive = true
                captureLocalCommandOrLaunchChatGpt()
                return@launch
            } else {
                delay(350) // Give an existing session a moment to claim the interrupt

                if (!pendingInterruptLaunch) {
                    Log.d(TAG, "Barge-in handled by existing session")
                    return@launch
                }
                pendingInterruptLaunch = false
                isSessionActive = true
            }
            val intent = Intent(this@HotwordService, OpenClawAssistantService::class.java).apply {
                action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT
                putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
            }
            try {
                startService(intent)
                Log.e(TAG, "startService ACTION_SHOW_ASSISTANT called")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            } catch (e: SecurityException) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Background start failed, falling back to broadcast", e)
                val broadcastIntent = Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).apply {
                    setPackage(packageName)
                    putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, target.target)
                }
                sendBroadcast(broadcastIntent)
            }
        }
    }

    private fun playWakeSound(sound: String) {
        val tone = when (sound) {
            SettingsRepository.WAKE_SOUND_NONE -> return
            SettingsRepository.WAKE_SOUND_HIGH -> ToneGenerator.TONE_PROP_ACK
            SettingsRepository.WAKE_SOUND_LOW -> ToneGenerator.TONE_PROP_NACK
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            generator.startTone(tone, 140)
            scope.launch {
                delay(180)
                generator.release()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to play wake sound", error)
        }
    }

    /**
     * The hotword recognizer uses a restricted grammar, so v0.1 captures one
     * short unrestricted Vosk utterance after the wake tone. Deterministic
     * device commands stay local; unknown language falls through to ChatGPT.
     */
    private fun captureLocalCommandOrLaunchChatGpt() {
        localCommandCaptureJob?.cancel()
        localCommandCaptureJob = scope.launch {
            delay(220) // Keep the acknowledgement tone out of command audio.
            val currentModel = model
            if (currentModel == null) {
                Log.w(TAG, "Local command model unavailable; continuing to ChatGPT")
                beginChatGptHandoff()
                return@launch
            }

            try {
                val recognizer = Recognizer(currentModel, SAMPLE_RATE)
                val commandSpeechService = SpeechService(recognizer, SAMPLE_RATE)
                val completed = AtomicBoolean(false)
                val lastTranscript = AtomicReference("")
                var silenceJob: Job? = null

                fun finishCapture(transcript: String) {
                    if (!completed.compareAndSet(false, true)) return
                    silenceJob?.cancel()
                    scope.launch {
                        runCatching { commandSpeechService.stop() }
                        runCatching { commandSpeechService.shutdown() }
                        if (speechService === commandSpeechService) speechService = null
                        handleCapturedLocalCommand(transcript.trim())
                    }
                }

                val listener = object : VoskRecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        extractVoskText(hypothesis).takeIf { it.isNotBlank() }?.let { text ->
                            lastTranscript.set(text)
                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(LOCAL_COMMAND_SILENCE_MS)
                                finishCapture(lastTranscript.get())
                            }
                        }
                    }

                    override fun onResult(hypothesis: String?) {
                        val text = extractVoskText(hypothesis).ifBlank { lastTranscript.get() }
                        if (text.isNotBlank()) finishCapture(text)
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        finishCapture(extractVoskText(hypothesis).ifBlank { lastTranscript.get() })
                    }

                    override fun onError(exception: Exception?) {
                        Log.w(TAG, "Offline local-command capture failed", exception)
                        finishCapture(lastTranscript.get())
                    }

                    override fun onTimeout() {
                        finishCapture(lastTranscript.get())
                    }
                }

                speechService = commandSpeechService
                Log.i(TAG, "local_command_capture_started")
                if (!commandSpeechService.startListening(listener, LOCAL_COMMAND_TIMEOUT_MS)) {
                    finishCapture("")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to start offline local-command capture", error)
                beginChatGptHandoff()
            }
        }
    }

    private fun extractVoskText(payload: String?): String {
        if (payload.isNullOrBlank()) return ""
        return runCatching {
            val json = JSONObject(payload)
            json.optString("text", "")
                .ifBlank { json.optString("partial", "") }
                .trim()
        }
            .getOrDefault("")
    }

    private suspend fun handleCapturedLocalCommand(transcript: String) {
        Log.i(TAG, "local_command_transcript=${transcript.take(80)}")
        val command = LocalVoiceCommandParser.parse(transcript)
        if (command == null) {
            Log.i(TAG, "local_command_unmatched; handing off to ChatGPT")
            beginChatGptHandoff()
            return
        }

        val runtime = (application as OpenClawApplication).ensureRuntime()
        val result = withContext(Dispatchers.IO) {
            LocalVoiceCommandExecutor(this@HotwordService, runtime).execute(command)
        }
        when (result) {
            is LocalVoiceCommandExecutor.Result.Completed -> {
                result.spokenFeedback?.let { speakLocalFeedback(it) }
                finishLocalCommand("local command completed")
            }
            is LocalVoiceCommandExecutor.Result.Failed -> {
                postLocalCommandNotification(result.message)
                speakLocalFeedback(result.message)
                finishLocalCommand("local command failed")
            }
        }
    }

    private fun finishLocalCommand(reason: String) {
        Log.i(TAG, "$reason; hotword_resumed")
        cancelWatchdog()
        isSessionActive = false
        isListeningForCommand = false
        resumeHotwordDetection()
    }

    private suspend fun speakLocalFeedback(text: String): Boolean = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Boolean>()
        var engine: TextToSpeech? = null
        val utteranceId = "local-command-${UUID.randomUUID()}"
        engine = TextToSpeech(this@HotwordService) { status ->
            if (status != TextToSpeech.SUCCESS) {
                done.complete(false)
                return@TextToSpeech
            }
            val readyEngine = engine ?: run {
                done.complete(false)
                return@TextToSpeech
            }
            readyEngine.language = Locale.getDefault()
            readyEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })
            if (readyEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
                done.complete(false)
            }
        }
        try {
            withTimeoutOrNull(15_000) { done.await() } ?: false
        } finally {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
        }
    }

    private fun postLocalCommandNotification(message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, LOCAL_COMMAND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.local_command_needs_attention))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(LOCAL_COMMAND_NOTIFICATION_ID, notification)
    }

    private fun resumeHotwordDetection() {
        if (isSessionActive) return
        isListeningForCommand = false
        audioRetryCount = 0
        updateNotification()
        scope.launch {
            delay(500)
            if (!isSessionActive && speechService == null) {
                startHotwordListening()
            }
        }
    }

    private fun beginChatGptHandoff() {
        if (chatGptHandoffActive) return
        Log.i(TAG, "Pausing wake word for ChatGPT Live microphone handoff")
        cancelWatchdog()
        chatGptLaunchJob?.cancel()
        chatGptGraceJob?.cancel()
        chatGptMonitorJob?.cancel()
        chatGptIdleJob?.cancel()
        chatGptHandoffTracker.reset()
        chatGptHandoffActive = true
        isSessionActive = true
        isListeningForCommand = false
        speechService?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        speechService = null

        // SpeechService shutdown is asynchronous at the audio-server boundary.
        // Do not arm external-recording observation until our own recorder is
        // demonstrably gone, or it can be mistaken for ChatGPT.
        chatGptLaunchJob = scope.launch {
            val microphoneReleased = withTimeoutOrNull(MICROPHONE_RELEASE_TIMEOUT_MS) {
                while (hasActiveRecording()) delay(100)
                true
            } ?: false
            if (!chatGptHandoffActive) return@launch
            if (!microphoneReleased) {
                Log.w(TAG, "microphone_release_timeout")
                postLocalCommandNotification("The microphone is busy. Try Hey GPT again in a moment.")
                finishChatGptHandoff("Microphone did not release")
                return@launch
            }

            delay(150)
            if (!chatGptHandoffActive) return@launch
            chatGptHandoffTracker.arm()
            Log.i(TAG, "chatgpt_launch_requested")

            // Ask the system-bound VoiceInteractionService to perform the
            // minimal launch. No OpenClawSession or backend is constructed.
            val intent = Intent(this@HotwordService, OpenClawAssistantService::class.java).apply {
                action = OpenClawAssistantService.ACTION_HANDOFF_CHATGPT
            }
            try {
                startService(intent)
            } catch (error: Exception) {
                Log.w(TAG, "VoiceInteractionService handoff unavailable; using direct fallback", error)
                val result = ChatGptLiveLauncher.launch(this@HotwordService)
                Log.i(TAG, "chatgpt_launch_result=$result")
            }

            // If Start with Voice is off, or ChatGPT needs sign-in, no recording
            // begins. Avoid leaving the wake listener paused forever.
            chatGptGraceJob = scope.launch {
                delay(20_000)
                if (chatGptHandoffActive && !chatGptHandoffTracker.recordingObserved) {
                    if (hasActiveRecording()) {
                        handleExternalRecordingState(true)
                    } else {
                        ChatGptLiveLauncher.postFallbackNotification(this@HotwordService)
                        finishChatGptHandoff("ChatGPT did not start recording")
                    }
                }
            }
            chatGptMonitorJob = scope.launch {
                delay(500)
                while (chatGptHandoffActive && chatGptHandoffTracker.isArmed) {
                    handleExternalRecordingState(hasActiveRecording())
                    delay(2_000)
                }
            }
        }
    }

    private fun hasActiveRecording(): Boolean = runCatching {
        getSystemService(AudioManager::class.java)
            .activeRecordingConfigurations
            .isNotEmpty()
    }.getOrElse { error ->
        Log.w(TAG, "Unable to inspect microphone ownership", error)
        true // Fail closed: never launch another recorder when ownership is unknown.
    }

    private fun handleExternalRecordingState(hasActiveRecording: Boolean) {
        val recordingWasObserved = chatGptHandoffTracker.recordingObserved
        val shouldResume = chatGptHandoffTracker.onRecordingStateChanged(hasActiveRecording)
        if (hasActiveRecording) {
            if (!recordingWasObserved) Log.i(TAG, "external_recording_started")
            chatGptIdleJob?.cancel()
            chatGptIdleJob = null
        } else if (shouldResume && chatGptIdleJob?.isActive != true) {
            Log.i(TAG, "external_recording_stopped")
            chatGptIdleJob = scope.launch {
                delay(3_500)
                val stillIdle = getSystemService(AudioManager::class.java)
                    .activeRecordingConfigurations
                    .isEmpty()
                if (chatGptHandoffActive && stillIdle) {
                    finishChatGptHandoff("External microphone recording ended")
                }
            }
        }
    }

    private fun finishChatGptHandoff(reason: String) {
        Log.i(TAG, "$reason; hotword_resumed")
        chatGptLaunchJob?.cancel()
        chatGptLaunchJob = null
        chatGptGraceJob?.cancel()
        chatGptGraceJob = null
        chatGptMonitorJob?.cancel()
        chatGptMonitorJob = null
        chatGptIdleJob?.cancel()
        chatGptIdleJob = null
        chatGptHandoffTracker.reset()
        chatGptHandoffActive = false
        isSessionActive = false
        isListeningForCommand = false
        resumeHotwordDetection()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            Log.w(TAG, "Watchdog timeout! Auto-resuming hotword detection.")
            isSessionActive = false
            isListeningForCommand = false
            resumeHotwordDetection()
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
