package com.openclaw.assistant.node

import android.content.Context
import android.os.SystemClock
import com.openclaw.assistant.data.SettingsRepository
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SpotifyAppRemoteConfig {
  const val REDIRECT_URI = "https://com.openclaw.assistant/spotify-callback"
  const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
  private val CLIENT_ID = Regex("^[A-Za-z0-9]{32}$")

  fun normalizeClientId(value: String): String = value.trim()

  fun isValidClientId(value: String): Boolean = CLIENT_ID.matches(normalizeClientId(value))
}

internal data class SpotifyRemotePlayerState(
  val isPaused: Boolean,
  val trackUri: String,
  val title: String,
  val artist: String,
)

internal interface SpotifyRemoteSession : AutoCloseable {
  suspend fun play(trackUri: String)
  suspend fun playerState(): SpotifyRemotePlayerState
}

internal fun interface SpotifyRemoteConnector {
  suspend fun connect(clientId: String, showAuthView: Boolean): SpotifyRemoteSession
}

internal class SpotifyAppRemoteController(
  private val connector: SpotifyRemoteConnector,
  private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
  private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
  private val nowMs: () -> Long = SystemClock::elapsedRealtime,
  private val operationMutex: Mutex = spotifyRemoteOperationMutex,
) {
  constructor(context: Context) : this(
    connector = AndroidSpotifyRemoteConnector(context.applicationContext),
  )

  suspend fun authorize(clientId: String) = operationMutex.withLock {
    val session = withTimeout(AUTHORIZATION_TIMEOUT_MS) {
      connector.connect(requireClientId(clientId), showAuthView = true)
    }
    try {
      Unit
    } finally {
      session.close()
    }
  }

  suspend fun playTrack(
    clientId: String,
    request: SpotifyPlaybackRequest,
  ): SpotifyPlaybackReceipt = operationMutex.withLock {
    val normalizedClientId = requireClientId(clientId)
    val session = withTimeout(CONNECTION_TIMEOUT_MS) {
      connector.connect(normalizedClientId, showAuthView = false)
    }
    try {
      withTimeout(COMMAND_TIMEOUT_MS) {
        session.play(request.spotifyTrackUri)
      }
      val deadline = nowMs() + confirmationTimeoutMs
      var confirmed: SpotifyRemotePlayerState? = null
      while (nowMs() < deadline && confirmed == null) {
        val state = withTimeout(COMMAND_TIMEOUT_MS) { session.playerState() }
        if (!state.isPaused && state.matches(request)) confirmed = state
        if (confirmed == null) delay(pollIntervalMs)
      }
      SpotifyPlaybackReceipt(
        launched = true,
        playbackConfirmed = confirmed != null,
        route = "spotify_app_remote",
        confirmedTitle = confirmed?.title.orEmpty(),
        confirmedArtist = confirmed?.artist.orEmpty(),
      )
    } finally {
      session.close()
    }
  }

  private fun requireClientId(value: String): String {
    val normalized = SpotifyAppRemoteConfig.normalizeClientId(value)
    if (!SpotifyAppRemoteConfig.isValidClientId(normalized)) {
      throw SpotifyPlaybackException(
        code = "SPOTIFY_SETUP_REQUIRED",
        message = "Complete Spotify control setup in the phone app first",
      )
    }
    return normalized
  }

  private fun SpotifyRemotePlayerState.matches(request: SpotifyPlaybackRequest): Boolean {
    if (trackUri == request.spotifyTrackUri) return true
    return AndroidSpotifyPlaybackExecutor.matchesRequestedMedia(
      request = request,
      actualTitle = title,
      actualArtist = artist,
    )
  }

  companion object {
    private const val AUTHORIZATION_TIMEOUT_MS = 120_000L
    private const val CONNECTION_TIMEOUT_MS = 15_000L
    private const val COMMAND_TIMEOUT_MS = 10_000L
    private const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 5_000L
    private const val DEFAULT_POLL_INTERVAL_MS = 125L
  }
}

internal class SpotifyAppRemotePlaybackExecutor(
  private val clientIdProvider: () -> String,
  private val controller: SpotifyAppRemoteController,
  private val fallback: SpotifyPlaybackExecutor,
) : SpotifyPlaybackExecutor {
  constructor(context: Context) : this(
    clientIdProvider = {
      SettingsRepository.getInstance(context.applicationContext).spotifyClientId
    },
    controller = SpotifyAppRemoteController(context.applicationContext),
    fallback = AndroidSpotifyPlaybackExecutor(context.applicationContext),
  )

  override suspend fun play(request: SpotifyPlaybackRequest): SpotifyPlaybackReceipt {
    if (request.spotifyTrackUri.isBlank()) return fallback.play(request)
    return try {
      controller.playTrack(clientIdProvider(), request)
    } catch (error: CancellationException) {
      throw error
    } catch (error: SpotifyPlaybackException) {
      throw error
    } catch (error: Throwable) {
      throw error.toPlaybackException()
    }
  }

  private fun Throwable.toPlaybackException(): SpotifyPlaybackException = when (spotifyCause()) {
    is UserNotAuthorizedException,
    is AuthenticationFailedException -> SpotifyPlaybackException(
      code = "SPOTIFY_SETUP_REQUIRED",
      message = "Authorize Spotify control in the phone app first",
    )
    is NotLoggedInException -> SpotifyPlaybackException(
      code = "SPOTIFY_LOGIN_REQUIRED",
      message = "Open Spotify and sign in on the phone first",
    )
    is CouldNotFindSpotifyApp -> SpotifyPlaybackException(
      code = "SPOTIFY_NOT_INSTALLED",
      message = "Spotify is not installed",
    )
    else -> SpotifyPlaybackException(
      code = "SPOTIFY_PLAYBACK_FAILED",
      message = "Spotify could not start the requested track",
    )
  }

  private fun Throwable.spotifyCause(): Throwable =
    generateSequence(this) { it.cause }
      .firstOrNull {
        it is UserNotAuthorizedException ||
          it is AuthenticationFailedException ||
          it is NotLoggedInException ||
          it is CouldNotFindSpotifyApp
      } ?: this
}

private val spotifyRemoteOperationMutex = Mutex()

private class AndroidSpotifyRemoteConnector(
  private val context: Context,
) : SpotifyRemoteConnector {
  override suspend fun connect(
    clientId: String,
    showAuthView: Boolean,
  ): SpotifyRemoteSession = suspendCancellableCoroutine { continuation ->
    val params = ConnectionParams.Builder(clientId)
      .setRedirectUri(SpotifyAppRemoteConfig.REDIRECT_URI)
      .showAuthView(showAuthView)
      .build()
    SpotifyAppRemote.connect(
      context,
      params,
      object : Connector.ConnectionListener {
        override fun onConnected(appRemote: SpotifyAppRemote) {
          if (continuation.isActive) {
            continuation.resume(AndroidSpotifyRemoteSession(appRemote))
          } else {
            SpotifyAppRemote.disconnect(appRemote)
          }
        }

        override fun onFailure(error: Throwable) {
          if (continuation.isActive) continuation.resumeWithException(error)
        }
      },
    )
  }
}

private class AndroidSpotifyRemoteSession(
  private val appRemote: SpotifyAppRemote,
) : SpotifyRemoteSession {
  override suspend fun play(trackUri: String) {
    appRemote.playerApi.play(trackUri).awaitValue()
  }

  override suspend fun playerState(): SpotifyRemotePlayerState {
    val state = appRemote.playerApi.playerState.awaitValue()
    return SpotifyRemotePlayerState(
      isPaused = state.isPaused,
      trackUri = state.track.uri.orEmpty(),
      title = state.track.name.orEmpty(),
      artist = state.track.artist?.name.orEmpty(),
    )
  }

  override fun close() {
    runCatching { SpotifyAppRemote.disconnect(appRemote) }
  }
}

private suspend fun <T> CallResult<T>.awaitValue(): T =
  suspendCancellableCoroutine { continuation ->
    setResultCallback { result ->
      if (continuation.isActive) continuation.resume(result)
    }
    setErrorCallback { error ->
      if (continuation.isActive) continuation.resumeWithException(error)
    }
    continuation.invokeOnCancellation { cancel() }
  }
