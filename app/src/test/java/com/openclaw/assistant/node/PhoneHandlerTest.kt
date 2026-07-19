package com.openclaw.assistant.node

import android.Manifest
import android.app.Application
import android.content.Intent
import com.openclaw.assistant.broker.AndroidContactCallLaunchV1
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneHandlerTest {
  private val context: Application = RuntimeEnvironment.getApplication()
  private val handler = PhoneHandler(context, Json) { "ERROR" to (it.message ?: "error") }

  @Before
  fun clearState() {
    shadowOf(context).denyPermissions(Manifest.permission.CALL_PHONE)
    while (shadowOf(context).nextStartedActivity != null) Unit
  }

  @Test
  fun `without call permission opens dialer for confirmation`() {
    val result = handler.handleCall("""{"number":"+1 (604) 555-1234"}""")

    assertTrue(result.ok)
    assertTrue(result.payloadJson!!.contains("\"placedCall\":false"))
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Intent.ACTION_DIAL, intent.action)
    assertEquals("+16045551234", intent.data!!.schemeSpecificPart)
  }

  @Test
  fun `with call permission places ordinary call`() {
    shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)

    val result = handler.handleCall("""{"number":"+16045551234"}""")

    assertTrue(result.ok)
    assertEquals(Intent.ACTION_CALL, shadowOf(context).nextStartedActivity.action)
  }

  @Test
  fun `USSD input is rejected without opening dialer`() {
    val result = handler.handleCall("""{"number":"*#06#"}""")

    assertFalse(result.ok)
    assertEquals("INVALID_NUMBER", result.error?.code)
    assertEquals(null, shadowOf(context).nextStartedActivity)
  }

  @Test
  fun `assistant contact call returns only launch disposition`() {
    shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)

    val result = handler.launchAssistantContactCall("+1 (604) 555-1234")

    assertEquals(AndroidContactCallLaunchV1.Launched(true, false), result)
    assertEquals(Intent.ACTION_CALL, shadowOf(context).nextStartedActivity.action)
  }

  @Test
  fun `assistant contact call rejects unsafe number without launching`() {
    val result = handler.launchAssistantContactCall("*#06#")

    assertEquals(AndroidContactCallLaunchV1.InvalidNumber, result)
    assertEquals(null, shadowOf(context).nextStartedActivity)
  }
}
