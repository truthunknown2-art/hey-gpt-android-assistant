package com.openclaw.assistant.node

import android.app.Application
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
}
