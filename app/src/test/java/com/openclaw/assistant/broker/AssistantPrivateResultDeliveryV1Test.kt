package com.openclaw.assistant.broker

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPrivateResultDeliveryV1Test {
    @Test
    fun `router delivers only to the exact active session and device`() = runTest {
        val router = AssistantPrivateResultRouterV1()
        var deliveries = 0
        val binding = router.bind(SESSION, DEVICE) {
            deliveries += 1
            true
        }

        assertFalse(router.deliver(delivery(session = "wrong")))
        assertFalse(router.deliver(delivery(device = "wrong")))
        assertTrue(router.deliver(delivery()))
        assertEquals(1, deliveries)

        binding!!.close()
        assertFalse(router.deliver(delivery()))
        assertEquals(1, deliveries)
    }

    @Test
    fun `router refuses a second concurrent voice binding`() {
        val router = AssistantPrivateResultRouterV1()
        val first = router.bind(SESSION, DEVICE) { true }

        assertNull(router.bind("other-session", DEVICE) { true })

        first!!.close()
        assertTrue(router.bind("other-session", DEVICE) { true } != null)
    }

    @Test
    fun `contact renderer speaks names and digits but not internal ids`() {
        val rendered = AssistantPrivateResultSpeechRendererV1.render(delivery())

        assertEquals(
            "I found one matching contact. Jen Thorndale. The phone number is plus one two five zero five five five zero one zero zero.",
            rendered,
        )
        assertFalse(rendered!!.contains("101"))
    }

    @Test
    fun `contact renderer rejects unknown fields and non-string values`() {
        val extraField = delivery(
            result = contactsResult(
                buildJsonObject {
                    put("contactId", "101")
                    put("displayName", "Jen")
                    put("phoneNumber", "+1")
                    put("message", "private")
                },
            ),
        )
        val numericName = delivery(
            result = contactsResult(
                buildJsonObject {
                    put("contactId", "101")
                    put("displayName", JsonPrimitive(123))
                    put("phoneNumber", "+1")
                },
            ),
        )

        assertNull(AssistantPrivateResultSpeechRendererV1.render(extraField))
        assertNull(AssistantPrivateResultSpeechRendererV1.render(numericName))
    }

    @Test
    fun `contact renderer strips control and formatting characters from speech`() {
        val rendered = AssistantPrivateResultSpeechRendererV1.render(
            delivery(
                result = contactsResult(
                    buildJsonObject {
                        put("contactId", "101")
                        put("displayName", "Jen\u0000\u202e Thorndale")
                        put("phoneNumber", "+1 (250) 555-0100")
                    },
                ),
            ),
        )

        assertTrue(rendered!!.contains("Jen Thorndale"))
        assertFalse(rendered.contains('\u0000'))
        assertFalse(rendered.contains('\u202e'))
    }

    @Test
    fun `calendar renderer speaks local event details without internal metadata`() {
        val result = calendarResult(
            buildJsonObject {
                put("title", "Dentist")
                put("startEpochMs", 1_774_210_400_000L)
                put("endEpochMs", 1_774_214_000_000L)
                put("allDay", false)
            },
        )

        val rendered = AssistantPrivateResultSpeechRendererV1.render(
            delivery(result = result, capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT),
        )

        assertTrue(rendered!!.contains("Dentist"))
        assertTrue(rendered.contains("Your next calendar event"))
        assertFalse(rendered.contains("1774210400000"))
    }

    @Test
    fun `calendar renderer rejects unknown fields and sanitizes title controls`() {
        val invalid = calendarResult(
            buildJsonObject {
                put("title", "Private")
                put("startEpochMs", 1_774_210_400_000L)
                put("endEpochMs", 1_774_214_000_000L)
                put("allDay", false)
                put("location", "Secret")
            },
        )
        val sanitized = calendarResult(
            buildJsonObject {
                put("title", "Dentist\u0000\u202e visit")
                put("startEpochMs", 1_774_210_400_000L)
                put("endEpochMs", 1_774_214_000_000L)
                put("allDay", false)
            },
        )

        assertNull(
            AssistantPrivateResultSpeechRendererV1.render(
                delivery(result = invalid, capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT),
            ),
        )
        val rendered = AssistantPrivateResultSpeechRendererV1.render(
            delivery(result = sanitized, capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT),
        )
        assertTrue(rendered!!.contains("Dentist visit"))
        assertFalse(rendered.contains('\u0000'))
        assertFalse(rendered.contains('\u202e'))
    }

    @Test
    fun `all-day calendar renderer keeps the UTC provider date`() {
        val result = calendarResult(
            buildJsonObject {
                put("title", "Trip")
                put("startEpochMs", java.time.Instant.parse("2026-07-20T00:00:00Z").toEpochMilli())
                put("endEpochMs", java.time.Instant.parse("2026-07-21T00:00:00Z").toEpochMilli())
                put("allDay", true)
            },
        )

        val rendered = AssistantPrivateResultSpeechRendererV1.render(
            delivery(result = result, capability = AssistantCapabilityV1.ANDROID_CALENDAR_NEXT),
        )

        assertTrue(rendered!!.contains("July 20"))
        assertFalse(rendered.contains("July 19"))
    }

    private fun delivery(
        session: String = SESSION,
        device: String = DEVICE,
        result: JsonObject = contactsResult(
            buildJsonObject {
                put("contactId", "101")
                put("displayName", "Jen Thorndale")
                put("phoneNumber", "+1 250 555 0100")
            },
        ),
        capability: AssistantCapabilityV1 = AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
    ) = AssistantPrivateResultDeliveryV1(
        capability = capability,
        voiceSessionKey = session,
        targetDeviceId = device,
        result = result,
    )

    private fun contactsResult(vararg contacts: JsonObject): JsonObject = buildJsonObject {
        put("contacts", JsonArray(contacts.toList()))
    }

    private fun calendarResult(vararg events: JsonObject): JsonObject = buildJsonObject {
        put("events", JsonArray(events.toList()))
    }

    private companion object {
        const val SESSION = "agent:voice-main:voice-android-device"
        const val DEVICE = "paired-device"
    }
}
