package com.openclaw.assistant.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatControllerProtocolTest {
  @Test
  fun chatSendParamsMatchStrictGatewaySchema() {
    val params =
      buildChatSendParams(
        sessionKey = "session-1",
        message = "hello",
        thinking = "low",
        runId = "run-1",
        attachments =
          listOf(
            OutgoingAttachment(
              type = "image",
              mimeType = "image/png",
              fileName = "photo.png",
              base64 = "cG5n",
            ),
          ),
      )

    assertEquals("session-1", params.getValue("sessionKey").jsonPrimitive.content)
    assertEquals("hello", params.getValue("message").jsonPrimitive.content)
    assertEquals("low", params.getValue("thinking").jsonPrimitive.content)
    assertEquals("run-1", params.getValue("idempotencyKey").jsonPrimitive.content)
    assertEquals(30_000, params.getValue("timeoutMs").jsonPrimitive.content.toInt())
    assertFalse(params.containsKey("model"))

    val attachment = params.getValue("attachments").jsonArray.single().jsonObject
    assertEquals("image", attachment.getValue("type").jsonPrimitive.content)
    assertEquals("image/png", attachment.getValue("mimeType").jsonPrimitive.content)
    assertEquals("photo.png", attachment.getValue("fileName").jsonPrimitive.content)
    assertEquals("cG5n", attachment.getValue("content").jsonPrimitive.content)
  }
}
