package com.openclaw.assistant.node

import android.app.Application
import android.app.SearchManager
import android.provider.MediaStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaHandlerTest {
  private val context: Application = RuntimeEnvironment.getApplication()
  private val handler = MediaHandler(context, Json) { "ERROR" to (it.message ?: "error") }

  @Test
  fun `missing Spotify returns explicit error`() = runTest {
    val result = handler.handlePlaySearch("""{"query":"Daft Punk"}""")

    assertFalse(result.ok)
    assertEquals("SPOTIFY_NOT_INSTALLED", result.error?.code)
  }

  @Test
  fun `arbitrary media package is rejected`() = runTest {
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

  @Test
  fun `confirmed media session receipt includes verified track`() = runTest {
    val confirmedHandler = MediaHandler(
      context = context,
      json = Json,
      invokeErrorFromThrowable = { "ERROR" to (it.message ?: "error") },
      playbackExecutor = SpotifyPlaybackExecutor {
        SpotifyPlaybackReceipt(
          launched = true,
          playbackConfirmed = true,
          route = "media_session_uri",
          confirmedTitle = "Alive",
          confirmedArtist = "Pearl Jam",
        )
      },
      isPackageAvailable = { true },
    )

    val result = confirmedHandler.handlePlaySearch(
      """{"query":"Pearl Jam Alive","title":"Alive","artist":"Pearl Jam","spotifyUri":"spotify:track:4Qbjmdlv1eZDD1u8SWe1pt"}""",
    )

    assertTrue(result.ok)
    assertTrue(result.payloadJson.orEmpty().contains("\"playbackConfirmed\":true"))
    assertTrue(result.payloadJson.orEmpty().contains("\"route\":\"media_session_uri\""))
    assertTrue(
      result.payloadJson.orEmpty()
        .contains("\"spotifyUri\":\"spotify:track:4Qbjmdlv1eZDD1u8SWe1pt\""),
    )
    assertTrue(result.payloadJson.orEmpty().contains("\"confirmedTitle\":\"Alive\""))
    assertTrue(result.payloadJson.orEmpty().contains("\"confirmedArtist\":\"Pearl Jam\""))
  }

  @Test
  fun `metadata confirmation rejects wrong track`() {
    val request = SpotifyPlaybackRequest("Alive Pearl Jam", "Alive", "Pearl Jam", "")

    assertTrue(
      AndroidSpotifyPlaybackExecutor.matchesRequestedMedia(
        request,
        actualTitle = "Alive - Remastered",
        actualArtist = "Pearl Jam",
      ),
    )
    assertFalse(
      AndroidSpotifyPlaybackExecutor.matchesRequestedMedia(
        request,
        actualTitle = "Even Flow",
        actualArtist = "Pearl Jam",
      ),
    )
  }

  @Test
  fun `non-track Spotify URI is rejected before execution`() = runTest {
    var executed = false
    val strictHandler = MediaHandler(
      context = context,
      json = Json,
      invokeErrorFromThrowable = { "ERROR" to (it.message ?: "error") },
      playbackExecutor = SpotifyPlaybackExecutor {
        executed = true
        SpotifyPlaybackReceipt(true, false, "unexpected")
      },
      isPackageAvailable = { true },
    )

    val result = strictHandler.handlePlaySearch(
      """{"query":"Pearl Jam","spotifyUri":"spotify:playlist:37i9dQZF1DX0"}""",
    )

    assertFalse(result.ok)
    assertEquals("INVALID_SPOTIFY_URI", result.error?.code)
    assertFalse(executed)
  }
}
