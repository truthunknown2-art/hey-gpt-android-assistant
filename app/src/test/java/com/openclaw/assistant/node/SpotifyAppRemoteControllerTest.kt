package com.openclaw.assistant.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAppRemoteControllerTest {
  @Test
  fun `client id validation is strict`() {
    assertTrue(SpotifyAppRemoteConfig.isValidClientId(VALID_CLIENT_ID))
    assertTrue(SpotifyAppRemoteConfig.isValidClientId("  $VALID_CLIENT_ID  "))
    assertTrue(SpotifyAppRemoteConfig.isValidClientId("z123456789ABCDEF0123456789abcdeZ"))
    assertFalse(SpotifyAppRemoteConfig.isValidClientId("0123456789abcdef0123456789abcde"))
    assertFalse(SpotifyAppRemoteConfig.isValidClientId("client-secret"))
  }

  @Test
  fun `authorization requests the visible auth flow and closes the session`() = runTest {
    val session = FakeSpotifyRemoteSession()
    var receivedClientId = ""
    var receivedShowAuthView = false
    val controller = SpotifyAppRemoteController(
      connector = SpotifyRemoteConnector { clientId, showAuthView ->
        receivedClientId = clientId
        receivedShowAuthView = showAuthView
        session
      },
    )

    controller.authorize(VALID_CLIENT_ID)

    assertEquals(VALID_CLIENT_ID, receivedClientId)
    assertTrue(receivedShowAuthView)
    assertTrue(session.closed)
  }

  @Test
  fun `exact track playback is confirmed from App Remote state`() = runTest {
    val session = FakeSpotifyRemoteSession(
      state = SpotifyRemotePlayerState(
        isPaused = false,
        trackUri = TRACK_URI,
        title = "Alive",
        artist = "Pearl Jam",
      ),
    )
    val controller = SpotifyAppRemoteController(
      connector = SpotifyRemoteConnector { _, showAuthView ->
        assertFalse(showAuthView)
        session
      },
      nowMs = { 1_000L },
    )

    val receipt = controller.playTrack(VALID_CLIENT_ID, request())

    assertEquals(TRACK_URI, session.playedUri)
    assertTrue(receipt.playbackConfirmed)
    assertEquals("spotify_app_remote", receipt.route)
    assertEquals("Alive", receipt.confirmedTitle)
    assertEquals("Pearl Jam", receipt.confirmedArtist)
    assertTrue(session.closed)
  }

  @Test
  fun `missing setup fails before fallback or remote connection`() = runTest {
    var connected = false
    var fallbackInvoked = false
    val executor = SpotifyAppRemotePlaybackExecutor(
      clientIdProvider = { "" },
      controller = SpotifyAppRemoteController(
        connector = SpotifyRemoteConnector { _, _ ->
          connected = true
          FakeSpotifyRemoteSession()
        },
      ),
      fallback = SpotifyPlaybackExecutor {
        fallbackInvoked = true
        SpotifyPlaybackReceipt(true, false, "fallback")
      },
    )

    val error = runCatching { executor.play(request()) }.exceptionOrNull()

    assertTrue(error is SpotifyPlaybackException)
    assertEquals("SPOTIFY_SETUP_REQUIRED", (error as SpotifyPlaybackException).code)
    assertFalse(connected)
    assertFalse(fallbackInvoked)
  }

  @Test
  fun `cancellation is not converted to a playback error`() = runTest {
    val cancellation = CancellationException("voice turn cancelled")
    val executor = SpotifyAppRemotePlaybackExecutor(
      clientIdProvider = { VALID_CLIENT_ID },
      controller = SpotifyAppRemoteController(
        connector = SpotifyRemoteConnector { _, _ -> throw cancellation },
      ),
      fallback = SpotifyPlaybackExecutor {
        SpotifyPlaybackReceipt(true, false, "fallback")
      },
    )

    val error = runCatching { executor.play(request()) }.exceptionOrNull()

    assertTrue(error is CancellationException)
    assertFalse(error is SpotifyPlaybackException)
  }

  private fun request() = SpotifyPlaybackRequest(
    query = "Alive Pearl Jam",
    title = "Alive",
    artist = "Pearl Jam",
    spotifyTrackUri = TRACK_URI,
  )

  private class FakeSpotifyRemoteSession(
    private val state: SpotifyRemotePlayerState = SpotifyRemotePlayerState(
      isPaused = true,
      trackUri = "",
      title = "",
      artist = "",
    ),
  ) : SpotifyRemoteSession {
    var playedUri = ""
    var closed = false

    override suspend fun play(trackUri: String) {
      playedUri = trackUri
    }

    override suspend fun playerState(): SpotifyRemotePlayerState = state

    override fun close() {
      closed = true
    }
  }

  companion object {
    private const val VALID_CLIENT_ID = "0123456789abcdef0123456789abcdef"
    private const val TRACK_URI = "spotify:track:4Qbjmdlv1eZDD1u8SWe1pt"
  }
}
