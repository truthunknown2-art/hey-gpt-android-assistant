package com.openclaw.assistant.node

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import com.openclaw.assistant.gateway.GatewaySession
import com.openclaw.assistant.service.OpenClawNotificationListenerService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class SpotifyPlaybackRequest(
  val query: String,
  val title: String,
  val artist: String,
  val spotifyTrackUri: String,
)

internal data class SpotifyPlaybackReceipt(
  val launched: Boolean,
  val playbackConfirmed: Boolean,
  val route: String,
  val confirmedTitle: String = "",
  val confirmedArtist: String = "",
)

internal fun interface SpotifyPlaybackExecutor {
  suspend fun play(request: SpotifyPlaybackRequest): SpotifyPlaybackReceipt
}

class MediaHandler internal constructor(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
  private val playbackExecutor: SpotifyPlaybackExecutor,
  private val isPackageAvailable: (String) -> Boolean,
) {
  constructor(
    context: Context,
    json: Json,
    invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
  ) : this(
    context = context,
    json = json,
    invokeErrorFromThrowable = invokeErrorFromThrowable,
    playbackExecutor = AndroidSpotifyPlaybackExecutor(context),
    isPackageAvailable = {
      context.packageManager.getLaunchIntentForPackage(it) != null
    },
  )

  suspend fun handlePlaySearch(paramsJson: String?): GatewaySession.InvokeResult {
    return try {
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")
      val query = root["query"]?.jsonPrimitive?.content?.trim().orEmpty()
      if (query.isBlank()) {
        return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "query is required")
      }
      val title = root["title"]?.jsonPrimitive?.content?.trim().orEmpty()
      val artist = root["artist"]?.jsonPrimitive?.content?.trim().orEmpty()
      val spotifyTrackUri = root["spotifyUri"]?.jsonPrimitive?.content?.trim().orEmpty()
      if (spotifyTrackUri.isNotBlank() && !SPOTIFY_TRACK_URI.matches(spotifyTrackUri)) {
        return GatewaySession.InvokeResult.error(
          "INVALID_SPOTIFY_URI",
          "spotifyUri must identify one Spotify track",
        )
      }

      val requestedPackage = root["packageName"]?.jsonPrimitive?.content?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: SPOTIFY_PACKAGE
      if (requestedPackage != SPOTIFY_PACKAGE) {
        return GatewaySession.InvokeResult.error(
          "UNSUPPORTED_MEDIA_APP",
          "Only Spotify playback is supported",
        )
      }
      if (!isPackageAvailable(requestedPackage)) {
        return GatewaySession.InvokeResult.error(
          "SPOTIFY_NOT_INSTALLED",
          "Spotify is not installed",
        )
      }

      val receipt = playbackExecutor.play(
        SpotifyPlaybackRequest(query, title, artist, spotifyTrackUri),
      )
      GatewaySession.InvokeResult.ok(
        buildJsonObject {
          put("launched", JsonPrimitive(receipt.launched))
          put("playbackConfirmed", JsonPrimitive(receipt.playbackConfirmed))
          put("route", JsonPrimitive(receipt.route))
          put("query", JsonPrimitive(query))
          if (title.isNotBlank()) put("title", JsonPrimitive(title))
          if (artist.isNotBlank()) put("artist", JsonPrimitive(artist))
          if (spotifyTrackUri.isNotBlank()) {
            put("spotifyUri", JsonPrimitive(spotifyTrackUri))
          }
          if (receipt.confirmedTitle.isNotBlank()) {
            put("confirmedTitle", JsonPrimitive(receipt.confirmedTitle))
          }
          if (receipt.confirmedArtist.isNotBlank()) {
            put("confirmedArtist", JsonPrimitive(receipt.confirmedArtist))
          }
          put("packageName", JsonPrimitive(requestedPackage))
        }.toString()
      )
    } catch (error: Throwable) {
      val (code, message) = invokeErrorFromThrowable(error)
      GatewaySession.InvokeResult.error(code, message)
    }
  }

  internal fun createPlayIntent(
    query: String,
    title: String,
    artist: String,
    packageName: String = SPOTIFY_PACKAGE,
  ): Intent = createSpotifyPlayIntent(query, title, artist, packageName)

  companion object {
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    internal const val AUDIO_TRACK_FOCUS = "vnd.android.cursor.item/audio"
    internal const val ANY_MEDIA_FOCUS = "vnd.android.cursor.item/*"
    internal val SPOTIFY_TRACK_URI = Regex("^spotify:track:[A-Za-z0-9]{22}$")

    internal fun createSpotifyPlayIntent(
      query: String,
      title: String,
      artist: String,
      packageName: String = SPOTIFY_PACKAGE,
    ): Intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
      putMediaSearchExtras(title, artist)
      putExtra(SearchManager.QUERY, query)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      setPackage(packageName)
    }

    internal fun Bundle.putMediaSearchExtras(title: String, artist: String) {
      when {
        title.isNotBlank() -> {
          putString(MediaStore.EXTRA_MEDIA_FOCUS, AUDIO_TRACK_FOCUS)
          putString(MediaStore.EXTRA_MEDIA_TITLE, title)
          if (artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        }
        artist.isNotBlank() -> {
          putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
          putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        }
        else -> putString(MediaStore.EXTRA_MEDIA_FOCUS, ANY_MEDIA_FOCUS)
      }
    }

    private fun Intent.putMediaSearchExtras(title: String, artist: String) {
      val extras = Bundle().apply { putMediaSearchExtras(title, artist) }
      putExtras(extras)
    }
  }
}

internal class AndroidSpotifyPlaybackExecutor(
  private val context: Context,
  private val confirmationTimeoutMs: Long = 5_000L,
) : SpotifyPlaybackExecutor {
  private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
  private val listenerComponent = ComponentName(
    context,
    OpenClawNotificationListenerService::class.java,
  )

  override suspend fun play(request: SpotifyPlaybackRequest): SpotifyPlaybackReceipt {
    var controller = spotifyController()
    var launchedActivity = false
    if (controller == null || !controller.supports(request)) {
      context.startActivity(
        createLaunchIntent(request),
      )
      launchedActivity = true
      controller = awaitSpotifyController()
    }

    if (controller == null || !controller.supports(request)) {
      return SpotifyPlaybackReceipt(
        launched = launchedActivity,
        playbackConfirmed = false,
        route = "intent",
      )
    }

    val commandStartedAt = SystemClock.elapsedRealtime()
    val extras = Bundle().apply {
      with(MediaHandler) { this@apply.putMediaSearchExtras(request.title, request.artist) }
    }
    if (request.spotifyTrackUri.isNotBlank()) {
      controller.transportControls.playFromUri(Uri.parse(request.spotifyTrackUri), extras)
    } else {
      controller.transportControls.playFromSearch(request.query, extras)
    }
    val confirmed = awaitConfirmedPlayback(controller, request, commandStartedAt)
    return SpotifyPlaybackReceipt(
      launched = true,
      playbackConfirmed = confirmed != null,
      route = if (request.spotifyTrackUri.isNotBlank()) {
        "media_session_uri"
      } else {
        "media_session_search"
      },
      confirmedTitle = confirmed?.first.orEmpty(),
      confirmedArtist = confirmed?.second.orEmpty(),
    )
  }

  private fun spotifyController(): MediaController? = runCatching {
    mediaSessionManager.getActiveSessions(listenerComponent)
      .firstOrNull { it.packageName == MediaHandler.SPOTIFY_PACKAGE }
  }.getOrNull()

  private suspend fun awaitSpotifyController(): MediaController? {
    val deadline = SystemClock.elapsedRealtime() + CONTROLLER_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      spotifyController()?.let { return it }
      delay(POLL_INTERVAL_MS)
    }
    return spotifyController()
  }

  private suspend fun awaitConfirmedPlayback(
    controller: MediaController,
    request: SpotifyPlaybackRequest,
    commandStartedAt: Long,
  ): Pair<String, String>? {
    val deadline = commandStartedAt + confirmationTimeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      val state = controller.playbackState
      val metadata = controller.metadata
      val actualTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
      val actualArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
      if (
        state?.state == PlaybackState.STATE_PLAYING &&
        state.lastPositionUpdateTime >= commandStartedAt &&
        matchesRequestedMedia(request, actualTitle, actualArtist)
      ) {
        return actualTitle to actualArtist
      }
      delay(POLL_INTERVAL_MS)
    }
    return null
  }

  private fun MediaController.supports(request: SpotifyPlaybackRequest): Boolean {
    val requiredAction = if (request.spotifyTrackUri.isNotBlank()) {
      PlaybackState.ACTION_PLAY_FROM_URI
    } else {
      PlaybackState.ACTION_PLAY_FROM_SEARCH
    }
    return playbackState?.actions?.and(requiredAction) != 0L
  }

  private fun createLaunchIntent(request: SpotifyPlaybackRequest): Intent =
    if (request.spotifyTrackUri.isNotBlank()) {
      Intent(Intent.ACTION_VIEW, Uri.parse(request.spotifyTrackUri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(MediaHandler.SPOTIFY_PACKAGE)
      }
    } else {
      MediaHandler.createSpotifyPlayIntent(request.query, request.title, request.artist)
    }

  companion object {
    private const val CONTROLLER_TIMEOUT_MS = 2_500L
    private const val POLL_INTERVAL_MS = 125L

    internal fun matchesRequestedMedia(
      request: SpotifyPlaybackRequest,
      actualTitle: String,
      actualArtist: String,
    ): Boolean {
      if (request.title.isBlank() && request.artist.isBlank()) {
        return actualTitle.isNotBlank()
      }
      val titleMatches = request.title.isBlank() ||
        normalize(actualTitle).contains(normalize(request.title))
      val artistMatches = request.artist.isBlank() ||
        normalize(actualArtist).contains(normalize(request.artist))
      return titleMatches && artistMatches
    }

    private fun normalize(value: String): String = value
      .lowercase()
      .filter { it.isLetterOrDigit() }
  }
}
