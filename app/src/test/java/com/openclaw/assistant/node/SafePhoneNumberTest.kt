package com.openclaw.assistant.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafePhoneNumberTest {
  @Test
  fun `normalizes ordinary contact formatting`() {
    assertEquals("+16045551234", SafePhoneNumber.normalizeOrNull("+1 (604) 555-1234"))
  }

  @Test
  fun `rejects USSD and dial modifiers`() {
    assertNull(SafePhoneNumber.normalizeOrNull("*#06#"))
    assertNull(SafePhoneNumber.normalizeOrNull("+16045551234,123"))
    assertNull(SafePhoneNumber.normalizeOrNull("+16045551234;123"))
  }
}
