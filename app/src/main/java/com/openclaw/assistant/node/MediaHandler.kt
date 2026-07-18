package com.openclaw.assistant.node

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
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

      val requestedPackage = root["packageName"]?.jsonPrimitive?.content?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: SPOTIFY_PACKAGE
      val packageAvailable = context.packageManager.getLaunchIntentForPackage(requestedPackage) != null
      val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        putExtra(SearchManager.QUERY, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageAvailable) setPackage(requestedPackage)
      }
      context.startActivity(intent)
      GatewaySession.InvokeResult.ok(
        """{"success":true,"query":${query.toJsonString()},"packageName":${if (packageAvailable) requestedPackage.toJsonString() else "null"}}"""
      )
    } catch (error: Throwable) {
      val (code, message) = invokeErrorFromThrowable(error)
      GatewaySession.InvokeResult.error(code, message)
    }
  }

  companion object {
    const val SPOTIFY_PACKAGE = "com.spotify.music"
  }
}
