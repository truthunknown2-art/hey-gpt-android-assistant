package com.openclaw.assistant.node

import android.app.Application
import android.content.Context
import com.openclaw.assistant.LocationMode
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.VoiceWakeMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceHandlerTest {

  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = mockk<SecurePrefs>()

  private val handler = DeviceHandler(appContext = context, prefs = prefs)

  @Test
  fun `handleInfo returns expected fields`() {
    every { prefs.instanceId } returns MutableStateFlow("test-device-id")
    every { prefs.displayName } returns MutableStateFlow("My Phone")

    val result = handler.handleInfo()

    assertTrue(result.ok)
    val json = result.payloadJson!!
    assertTrue(json.contains("\"deviceId\":\"test-device-id\""))
    assertTrue(json.contains("\"name\":\"My Phone\""))
    assertTrue(json.contains("\"model\""))
    assertTrue(json.contains("\"appVersion\""))
  }

  @Test
  fun `handleStatus returns battery and prefs fields`() {
    every { prefs.voiceWakeMode } returns MutableStateFlow(VoiceWakeMode.Off)
    every { prefs.locationMode } returns MutableStateFlow(LocationMode.Off)
    every { prefs.preventSleep } returns MutableStateFlow(false)

    val result = handler.handleStatus()

    assertTrue(result.ok)
    val json = result.payloadJson!!
    assertTrue(json.contains("\"charging\""))
    assertTrue(json.contains("\"batteryLevel\""))
    assertTrue(json.contains("\"voiceWakeMode\""))
    assertTrue(json.contains("\"locationMode\""))
  }

  @Test
  fun `assistant status exposes only bounded low risk fields`() {
    every { prefs.voiceWakeMode } returns MutableStateFlow(VoiceWakeMode.Off)
    every { prefs.locationMode } returns MutableStateFlow(LocationMode.Off)
    every { prefs.preventSleep } returns MutableStateFlow(false)

    val status = handler.readAssistantStatus()

    assertTrue(status.batteryLevelPercent == null || status.batteryLevelPercent in 0..100)
    assertEquals(false, status.charging)
  }

  @Test
  fun `handlePermissions returns permission fields`() {
    val result = handler.handlePermissions()

    assertTrue(result.ok)
    val json = result.payloadJson!!
    assertTrue(json.contains("\"camera\""))
    assertTrue(json.contains("\"microphone\""))
    assertTrue(json.contains("\"location\""))
    assertTrue(json.contains("\"sms\""))
  }

  @Test
  fun `handleHealth returns status ok with memory and storage`() {
    val result = handler.handleHealth()

    assertTrue(result.ok)
    val json = result.payloadJson!!
    assertTrue(json.contains("\"status\":\"ok\""))
    assertTrue(json.contains("\"memory\""))
    assertTrue(json.contains("\"storage\""))
  }
}
