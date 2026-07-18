package com.openclaw.assistant.node

import com.openclaw.assistant.gateway.GatewaySession
import com.openclaw.assistant.protocol.OpenClawCameraCommand
import com.openclaw.assistant.protocol.OpenClawNotificationsCommand
import com.openclaw.assistant.protocol.OpenClawSystemCommand
import com.openclaw.assistant.protocol.OpenClawPhotosCommand
import com.openclaw.assistant.protocol.OpenClawContactsCommand
import com.openclaw.assistant.protocol.OpenClawCalendarCommand
import com.openclaw.assistant.protocol.OpenClawMotionCommand
import com.openclaw.assistant.protocol.OpenClawBridgeCommand
import com.openclaw.assistant.protocol.OpenClawPhoneCommand
import com.openclaw.assistant.protocol.OpenClawMediaCommand
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InvokeDispatcherTest {
  private val canvas = mockk<CanvasController>()
  private val cameraHandler = mockk<CameraHandler>()
  private val locationHandler = mockk<LocationHandler>()
  private val screenHandler = mockk<ScreenHandler>()
  private val smsHandler = mockk<SmsHandler>()
  private val notificationsHandler = mockk<NotificationsHandler>()
  private val systemHandler = mockk<SystemHandler>()
  private val photosHandler = mockk<PhotosHandler>()
  private val contactsHandler = mockk<ContactsHandler>()
  private val calendarHandler = mockk<CalendarHandler>()
  private val motionHandler = mockk<MotionHandler>()
  private val a2uiHandler = mockk<A2UIHandler>()
  private val debugHandler = mockk<DebugHandler>()
  private val appUpdateHandler = mockk<AppUpdateHandler>()
  private val deviceHandler = mockk<DeviceHandler>()
  private val wifiHandler = mockk<WifiHandler>()
  private val clipboardHandler = mockk<ClipboardHandler>()
  private val appHandler = mockk<AppHandler>()
  private val voiceWakeHandler = mockk<VoiceWakeHandler>()
  private val mobileBridgeHandler = mockk<MobileBridgeHandler>()
  private val phoneHandler = mockk<PhoneHandler>()
  private val mediaHandler = mockk<MediaHandler>()

  private fun createDispatcher(
    isForeground: Boolean = true,
    cameraEnabled: Boolean = true,
    locationEnabled: Boolean = true
  ) = InvokeDispatcher(
    canvas = canvas,
    cameraHandler = cameraHandler,
    locationHandler = locationHandler,
    screenHandler = screenHandler,
    smsHandler = smsHandler,
    notificationsHandler = notificationsHandler,
    systemHandler = systemHandler,
    photosHandler = photosHandler,
    contactsHandler = contactsHandler,
    calendarHandler = calendarHandler,
    motionHandler = motionHandler,
    a2uiHandler = a2uiHandler,
    debugHandler = debugHandler,
    appUpdateHandler = appUpdateHandler,
    deviceHandler = deviceHandler,
    wifiHandler = wifiHandler,
    clipboardHandler = clipboardHandler,
    appHandler = appHandler,
    voiceWakeHandler = voiceWakeHandler,
    mobileBridgeHandler = mobileBridgeHandler,
    phoneHandler = phoneHandler,
    mediaHandler = mediaHandler,
    isForeground = { isForeground },
    cameraEnabled = { cameraEnabled },
    locationEnabled = { locationEnabled }
  )

  @Test
  fun `camera list is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    coEvery { cameraHandler.handleList(null) } returns GatewaySession.InvokeResult.ok("{}")

    val result = dispatcher.handleInvoke(OpenClawCameraCommand.List.rawValue, null)

    assertEquals(true, result.ok)
    assertEquals("{}", result.payloadJson)
  }

  @Test
  fun `camera list returns error when camera disabled`() = runTest {
    val dispatcher = createDispatcher(cameraEnabled = false)

    val result = dispatcher.handleInvoke(OpenClawCameraCommand.List.rawValue, null)

    assertEquals(false, result.ok)
    assertEquals("CAMERA_DISABLED", result.error?.code)
  }

  @Test
  fun `camera list returns error when app in background`() = runTest {
    val dispatcher = createDispatcher(isForeground = false)

    val result = dispatcher.handleInvoke(OpenClawCameraCommand.List.rawValue, null)

    assertEquals(false, result.ok)
    assertEquals("NODE_BACKGROUND_UNAVAILABLE", result.error?.code)
  }

  @Test
  fun `notifications list is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    coEvery { notificationsHandler.handleList() } returns GatewaySession.InvokeResult.ok("""{"notifications":[]}""")

    val result = dispatcher.handleInvoke(OpenClawNotificationsCommand.List.rawValue, null)

    assertEquals(true, result.ok)
    assertEquals("""{"notifications":[]}""", result.payloadJson)
  }

  @Test
  fun `system notify is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"message":"test"}"""
    coEvery { systemHandler.handleNotify(params) } returns GatewaySession.InvokeResult.ok("""{"ok":true}""")

    val result = dispatcher.handleInvoke(OpenClawSystemCommand.Notify.rawValue, params)

    assertEquals(true, result.ok)
    assertEquals("""{"ok":true}""", result.payloadJson)
  }

  @Test
  fun `photos latest is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    coEvery { photosHandler.handleLatest() } returns GatewaySession.InvokeResult.ok("""{"photos":[]}""")

    val result = dispatcher.handleInvoke(OpenClawPhotosCommand.Latest.rawValue, null)

    assertEquals(true, result.ok)
    assertEquals("""{"photos":[]}""", result.payloadJson)
  }

  @Test
  fun `contacts search is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"query":"test"}"""
    coEvery { contactsHandler.handleSearch(params) } returns GatewaySession.InvokeResult.ok("""{"contacts":[]}""")

    val result = dispatcher.handleInvoke(OpenClawContactsCommand.Search.rawValue, params)

    assertEquals(true, result.ok)
    assertEquals("""{"contacts":[]}""", result.payloadJson)
  }

  @Test
  fun `calendar events is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"startTime":"123"}"""
    coEvery { calendarHandler.handleEvents(params) } returns GatewaySession.InvokeResult.ok("""{"events":[]}""")

    val result = dispatcher.handleInvoke(OpenClawCalendarCommand.Events.rawValue, params)

    assertEquals(true, result.ok)
    assertEquals("""{"events":[]}""", result.payloadJson)
  }

  @Test
  fun `motion activity is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    coEvery { motionHandler.handleActivity() } returns GatewaySession.InvokeResult.ok("""{"activity":"still"}""")

    val result = dispatcher.handleInvoke(OpenClawMotionCommand.Activity.rawValue, null)

    assertEquals(true, result.ok)
    assertEquals("""{"activity":"still"}""", result.payloadJson)
  }

  @Test
  fun `bridge status is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    every { mobileBridgeHandler.handleStatus() } returns GatewaySession.InvokeResult.ok("""{"enabled":true}""")

    val result = dispatcher.handleInvoke(OpenClawBridgeCommand.Status.rawValue, null)

    assertEquals(true, result.ok)
    assertEquals("""{"enabled":true}""", result.payloadJson)
  }

  @Test
  fun `bridge execute is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"requestId":"r1","capability":"device.info","arguments":{}}"""
    coEvery { mobileBridgeHandler.handleExecute(params) } returns GatewaySession.InvokeResult.ok("""{"status":"completed"}""")

    val result = dispatcher.handleInvoke(OpenClawBridgeCommand.Execute.rawValue, params)

    assertEquals(true, result.ok)
    assertEquals("""{"status":"completed"}""", result.payloadJson)
  }

  @Test
  fun `phone call is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"number":"+15551234567"}"""
    every { phoneHandler.handleCall(params) } returns GatewaySession.InvokeResult.ok("{}")

    val result = dispatcher.handleInvoke(OpenClawPhoneCommand.Call.rawValue, params)

    assertEquals(true, result.ok)
  }

  @Test
  fun `media search is dispatched to handler`() = runTest {
    val dispatcher = createDispatcher()
    val params = """{"query":"Miles Davis"}"""
    every { mediaHandler.handlePlaySearch(params) } returns GatewaySession.InvokeResult.ok("{}")

    val result = dispatcher.handleInvoke(OpenClawMediaCommand.PlaySearch.rawValue, params)

    assertEquals(true, result.ok)
  }
}
