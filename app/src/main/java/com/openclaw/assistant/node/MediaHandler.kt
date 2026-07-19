package com.openclaw.assistant.node

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MediaHandler(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  fun handlePlaySearch(paramsJson: String?): GatewaySession.InvokeResult {
    return try {
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")
      val query = root["query"]?.jsonPrimitive?.content?.trim().orEmpty()
      if (query.isBlank()) {
        return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "query is required")
      }
      val title = root["title"]?.jsonPrimitive?.content?.trim().orEmpty()
      val artist = root["artist"]?.jsonPrimitive?.content?.trim().orEmpty()

      val requestedPackage = root["packageName"]?.jsonPrimitive?.content?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: SPOTIFY_PACKAGE
      if (requestedPackage != SPOTIFY_PACKAGE) {
        return GatewaySession.InvokeResult.error(
          "UNSUPPORTED_MEDIA_APP",
          "Only Spotify playback is supported",
        )
      }
      val packageAvailable = context.packageManager.getLaunchIntentForPackage(requestedPackage) != null
      if (!packageAvailable) {
        return GatewaySession.InvokeResult.error(
          "SPOTIFY_NOT_INSTALLED",
          "Spotify is not installed",
        )
      }
      val intent = createPlayIntent(query, title, artist, requestedPackage)
      context.startActivity(intent)
      GatewaySession.InvokeResult.ok(
        buildJsonObject {
          put("launched", JsonPrimitive(true))
          put("playbackConfirmed", JsonPrimitive(false))
          put("query", JsonPrimitive(query))
          if (title.isNotBlank()) put("title", JsonPrimitive(title))
          if (artist.isNotBlank()) put("artist", JsonPrimitive(artist))
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
  ): Intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
    when {
      title.isNotBlank() -> {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, AUDIO_TRACK_FOCUS)
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
        if (artist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
      }
      artist.isNotBlank() -> {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
      }
      else -> putExtra(MediaStore.EXTRA_MEDIA_FOCUS, ANY_MEDIA_FOCUS)
    }
    putExtra(SearchManager.QUERY, query)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    setPackage(packageName)
  }

  companion object {
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val AUDIO_TRACK_FOCUS = "vnd.android.cursor.item/audio"
    private const val ANY_MEDIA_FOCUS = "vnd.android.cursor.item/*"
  }
}
