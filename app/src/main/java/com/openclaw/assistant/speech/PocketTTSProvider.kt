package com.openclaw.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "PocketTTSProvider"
private const val DEFAULT_SAMPLE_RATE = 24_000
private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024L

class PocketTTSProvider internal constructor(
    private val context: Context,
    private val endpointProvider: () -> String? = {
        val configuredUrl = SettingsRepository.getInstance(context).pocketTtsUrl
        val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (configuredUrl.isNotBlank()) {
            PocketTtsEndpointResolver.resolveConfiguredUrl(configuredUrl)
        } else {
            PocketTtsEndpointResolver.resolve(
                hostInput = runtime.manualHost.value,
                configuredPort = runtime.manualPort.value,
                configuredTls = runtime.manualTls.value,
            )
        }
    },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val sinkFactory: (Int) -> PcmPlaybackSink? = ::createPcmPlaybackSink,
    private val audioFocusController: PocketAudioFocusController = AndroidPocketAudioFocusController(context),
) : TTSProvider {
    private val stateLock = Any()
    private var currentCall: Call? = null
    private var currentTrack: PcmPlaybackSink? = null
    @Volatile internal var startedLastAttempt: Boolean = false
        private set

    override suspend fun speak(text: String): Boolean = speakInternal(text)

    private suspend fun speakInternal(
        text: String,
        onPlaybackStarted: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        startedLastAttempt = false
        val endpoint = endpointProvider()
        if (endpoint == null) {
            Log.w(TAG, "Pocket TTS has no valid HTTPS endpoint")
            return@withContext false
        }
        Log.d(TAG, "Requesting speech from $endpoint")
        val body = JSONObject().put("text", text).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()
        val call = client.newCall(request)
        synchronized(stateLock) { currentCall = call }
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        var attemptTrack: PcmPlaybackSink? = null
        var focusGranted = false
        var normalCompletion = false

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Pocket TTS returned HTTP ${response.code}")
                    return@withContext false
                }
                val responseBody = response.body ?: return@withContext false
                val contentLength = responseBody.contentLength()
                if (contentLength > MAX_RESPONSE_BYTES) {
                    Log.w(TAG, "Pocket TTS response is too large")
                    return@withContext false
                }

                val sampleRate = response.header("X-Audio-Sample-Rate")
                    ?.toIntOrNull()
                    ?.takeIf { it in 8_000..48_000 }
                    ?: DEFAULT_SAMPLE_RATE
                val track = sinkFactory(sampleRate) ?: return@withContext false
                attemptTrack = track
                synchronized(stateLock) { currentTrack = track }
                focusGranted = audioFocusController.request()
                if (!focusGranted) {
                    Log.w(TAG, "Pocket TTS could not acquire audio focus")
                    return@withContext false
                }

                responseBody.byteStream().use { input ->
                    val buffer = ByteArray(8_192)
                    var receivedBytes = 0L
                    track.play()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        receivedBytes += read
                        if (receivedBytes > MAX_RESPONSE_BYTES) {
                            throw IOException("Pocket TTS response exceeded the playback limit")
                        }
                        writeFully(track, buffer, read, onPlaybackStarted)
                    }
                    if (receivedBytes % PCM_FRAME_BYTES != 0L) {
                        throw IOException("Pocket TTS returned unaligned PCM audio")
                    }
                    finishPcmPlayback(track, receivedBytes / PCM_FRAME_BYTES)
                    normalCompletion = true
                }
                startedLastAttempt
            }
        } catch (e: IOException) {
            if (!call.isCanceled()) Log.w(TAG, "Pocket TTS network failure: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Pocket TTS playback failure: ${e.message}")
            false
        } finally {
            completionHandle?.dispose()
            synchronized(stateLock) {
                if (currentCall === call) currentCall = null
                if (currentTrack === attemptTrack) currentTrack = null
            }
            if (!normalCompletion) attemptTrack?.abort()
            if (focusGranted) audioFocusController.abandon()
        }
    }

    private suspend fun writeFully(
        track: PcmPlaybackSink,
        buffer: ByteArray,
        length: Int,
        onPlaybackStarted: () -> Unit,
    ) {
        var offset = 0
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            val written = track.write(buffer, offset, length - offset)
            if (written <= 0) throw IllegalStateException("AudioTrack write failed: $written")
            if (!startedLastAttempt) {
                startedLastAttempt = true
                onPlaybackStarted()
            }
            offset += written
        }
    }

    override fun stop() {
        val call: Call?
        val track: PcmPlaybackSink?
        synchronized(stateLock) {
            call = currentCall
            track = currentTrack
            currentCall = null
            currentTrack = null
        }
        call?.cancel()
        track?.abort()
    }

    override fun shutdown() = stop()

    override fun isAvailable(): Boolean = endpointProvider() != null

    override fun getType(): String = TTSProviderType.POCKET

    override fun getDisplayName(): String = "Pocket TTS"

    override fun isConfigured(): Boolean = endpointProvider() != null

    override fun getConfigurationError(): String? = if (endpointProvider() == null) {
        context.getString(R.string.tts_error_pocket_gateway_required)
    } else {
        null
    }

    override fun speakWithProgress(text: String): Flow<TTSState> = channelFlow {
        send(TTSState.Preparing)
        if (speakInternal(text) { trySend(TTSState.Speaking) }) {
            send(TTSState.Done)
        } else {
            send(TTSState.Error(context.getString(R.string.tts_error_pocket_unavailable)))
        }
    }
}

private const val PCM_FRAME_BYTES = 2L

private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

private fun createPcmPlaybackSink(sampleRate: Int): PcmPlaybackSink? {
    val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) return null
    val track = AudioTrack.Builder()
        .setAudioAttributes(speechAudioAttributes())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2 / 5))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
    return if (track.state == AudioTrack.STATE_INITIALIZED) {
        AudioTrackPcmPlaybackSink(track)
    } else {
        runCatching { track.release() }
        null
    }
}

internal interface PocketAudioFocusController {
    fun request(): Boolean
    fun abandon()
}

private class AndroidPocketAudioFocusController(
    context: Context,
) : PocketAudioFocusController {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAudioAttributes())
        .setAcceptsDelayedFocusGain(false)
        .build()

    override fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
