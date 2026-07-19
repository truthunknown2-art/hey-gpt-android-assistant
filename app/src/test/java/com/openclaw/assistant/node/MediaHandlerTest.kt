package com.openclaw.assistant.node

import android.app.Application
import android.app.SearchManager
import android.provider.MediaStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaHandlerTest {
  private val context: Application = RuntimeEnvironment.getApplication()
  private val handler = MediaHandler(context, Json) { "ERROR" to (it.message ?: "error") }

  @Test
  fun `missing Spotify returns explicit error`() {
    val result = handler.handlePlaySearch("""{"query":"Daft Punk"}""")

    assertFalse(result.ok)
    assertEquals("SPOTIFY_NOT_INSTALLED", result.error?.code)
  }

  @Test
  fun `arbitrary media package is rejected`() {
    val result = handler.handlePlaySearch(
      """{"query":"Daft Punk","packageName":"com.example.other"}""",
    )

    assertFalse(result.ok)
    assertEquals("UNSUPPORTED_MEDIA_APP", result.error?.code)
  }

  @Test
  fun `song request uses structured title and artist playback extras`() {
    val intent = handler.createPlayIntent(
      query = "Pearl Jam Alive",
      title = "Alive",
      artist = "Pearl Jam",
    )

    assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
    assertEquals(MediaHandler.SPOTIFY_PACKAGE, intent.`package`)
    assertEquals("vnd.android.cursor.item/audio", intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
    assertEquals("Alive", intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE))
    assertEquals("Pearl Jam", intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
    assertEquals("Pearl Jam Alive", intent.getStringExtra(SearchManager.QUERY))
  }

  @Test
  fun `artist request uses structured artist playback extras`() {
    val intent = handler.createPlayIntent(
      query = "Pearl Jam",
      title = "",
      artist = "Pearl Jam",
    )

    assertEquals(
      MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
      intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS),
    )
    assertEquals("Pearl Jam", intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))
  }
}
