package com.openclaw.assistant.broker

import java.io.Closeable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AssistantPrivateResultDeliveryV1(
    val capability: AssistantCapabilityV1,
    val voiceSessionKey: String,
    val targetDeviceId: String,
    val result: JsonObject,
)

internal fun interface AssistantPrivateResultReceiverV1 {
    suspend fun deliver(delivery: AssistantPrivateResultDeliveryV1): Boolean
}

/** Process-local router with one exact voice-session binding and no persistence. */
internal class AssistantPrivateResultRouterV1 {
    private data class Binding(
        val token: Any,
        val voiceSessionKey: String,
        val targetDeviceId: String,
        val receiver: AssistantPrivateResultReceiverV1,
    )

    private val lock = Any()
    private var binding: Binding? = null

    fun bind(
        voiceSessionKey: String,
        targetDeviceId: String,
        receiver: AssistantPrivateResultReceiverV1,
    ): Closeable? {
        require(voiceSessionKey.isNotBlank())
        require(targetDeviceId.isNotBlank())
        val token = Any()
        synchronized(lock) {
            if (binding != null) return null
            binding = Binding(token, voiceSessionKey, targetDeviceId, receiver)
        }
        return Closeable {
            synchronized(lock) {
                if (binding?.token === token) binding = null
            }
        }
    }

    suspend fun deliver(delivery: AssistantPrivateResultDeliveryV1): Boolean {
        val target = synchronized(lock) {
            binding?.takeIf {
                it.voiceSessionKey == delivery.voiceSessionKey &&
                    it.targetDeviceId == delivery.targetDeviceId
            }
        } ?: return false
        val delivered = runCatching { target.receiver.deliver(delivery) }.getOrDefault(false)
        return delivered && synchronized(lock) { binding?.token === target.token }
    }

    fun revokeAll() {
        synchronized(lock) { binding = null }
    }
}

internal object AssistantPrivateResultsV1 {
    val router = AssistantPrivateResultRouterV1()
}

/** Converts fixed private payloads to plain speech without exposing them to model or chat state. */
internal object AssistantPrivateResultSpeechRendererV1 {
    fun render(delivery: AssistantPrivateResultDeliveryV1): String? = when (delivery.capability) {
        AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> renderContacts(delivery.result)
        AssistantCapabilityV1.ANDROID_CALENDAR_NEXT -> renderCalendar(delivery.result)
        AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ ->
            renderMessengerNotifications(delivery.result)
        else -> null
    }

    private fun renderMessengerNotifications(result: JsonObject): String? {
        if (result.keys != setOf("notifications")) return null
        val notifications = result["notifications"] as? JsonArray ?: return null
        if (notifications.size > MAX_MESSENGER_NOTIFICATIONS) return null
        val zone = ZoneId.systemDefault()
        val rendered = notifications.map { element ->
            val notification = element as? JsonObject ?: return null
            if (notification.keys != MESSENGER_NOTIFICATION_KEYS) return null
            val rawSender = notification.string("sender") ?: return null
            val rawPreview = notification.string("textPreview") ?: return null
            val timestamp = (notification["timestamp"] as? JsonPrimitive)?.longOrNull ?: return null
            if (
                rawSender.length > MAX_MESSENGER_TEXT_LENGTH ||
                rawPreview.length > MAX_MESSENGER_TEXT_LENGTH ||
                timestamp < 0 ||
                (rawSender.isBlank() && rawPreview.isBlank())
            ) {
                return null
            }
            val sender = speechSafeMessage(rawSender).ifBlank { "an unknown sender" }
            val preview = speechSafeMessage(rawPreview)
            val time = if (timestamp == 0L) {
                ""
            } else {
                val instant = runCatching { Instant.ofEpochMilli(timestamp) }.getOrNull() ?: return null
                " at ${MESSENGER_DATE_TIME_FORMAT.format(instant.atZone(zone))}"
            }
            if (preview.isBlank()) {
                "$sender sent a Messenger notification$time without a text preview."
            } else {
                "$sender wrote$time: $preview."
            }
        }
        return when (rendered.size) {
            0 -> "I couldn't find a matching Messenger notification from the last seven days."
            1 -> "The latest matching Messenger notification is ${rendered.single()}"
            else -> "The latest ${rendered.size} matching Messenger notifications are ${rendered.joinToString(" ")}"
        }
    }

    private fun renderCalendar(result: JsonObject): String? {
        if (result.keys != setOf("events")) return null
        val events = result["events"] as? JsonArray ?: return null
        if (events.size > MAX_CALENDAR_EVENTS) return null
        val zone = ZoneId.systemDefault()
        val rendered = events.map { element ->
            val event = element as? JsonObject ?: return null
            if (event.keys != CALENDAR_EVENT_KEYS) return null
            val rawTitle = event.string("title") ?: return null
            val startEpochMs = (event["startEpochMs"] as? JsonPrimitive)?.longOrNull ?: return null
            val endEpochMs = (event["endEpochMs"] as? JsonPrimitive)?.longOrNull ?: return null
            val allDay = (event["allDay"] as? JsonPrimitive)?.booleanOrNull ?: return null
            if (rawTitle.length !in 1..MAX_CALENDAR_TITLE_LENGTH || startEpochMs < 0 || endEpochMs <= startEpochMs) {
                return null
            }
            val title = speechSafeCalendarTitle(rawTitle)
            if (title.isBlank()) return null
            val startInstant = runCatching { Instant.ofEpochMilli(startEpochMs) }.getOrNull() ?: return null
            val endInstant = runCatching { Instant.ofEpochMilli(endEpochMs) }.getOrNull() ?: return null
            if (allDay) {
                "$title. All day on ${CALENDAR_DATE_FORMAT.format(startInstant.atZone(ZoneOffset.UTC))}."
            } else {
                val start = startInstant.atZone(zone)
                val end = endInstant.atZone(zone)
                if (start.toLocalDate() == end.toLocalDate()) {
                    "$title. ${CALENDAR_DATE_TIME_FORMAT.format(start)} to ${CALENDAR_TIME_FORMAT.format(end)}."
                } else {
                    "$title. ${CALENDAR_DATE_TIME_FORMAT.format(start)} to ${CALENDAR_DATE_TIME_FORMAT.format(end)}."
                }
            }
        }
        return when (rendered.size) {
            0 -> "You have no upcoming calendar events in the next 31 days."
            1 -> "Your next calendar event is ${rendered.single()}"
            else -> "Your next ${rendered.size} calendar events are ${rendered.joinToString(" ")}"
        }
    }

    private fun renderContacts(result: JsonObject): String? {
        if (result.keys != setOf("contacts")) return null
        val contacts = result["contacts"] as? JsonArray ?: return null
        if (contacts.size > MAX_CONTACTS) return null
        val rendered = contacts.map { element ->
            val contact = element as? JsonObject ?: return null
            if (contact.keys != CONTACT_KEYS) return null
            val contactId = contact.string("contactId") ?: return null
            val displayName = contact.string("displayName") ?: return null
            val phoneNumber = contact.string("phoneNumber") ?: return null
            if (!contactId.matches(CONTACT_ID_PATTERN)) return null
            if (displayName.length !in 1..MAX_NAME_LENGTH || phoneNumber.length !in 1..MAX_NUMBER_LENGTH) {
                return null
            }
            val safeName = speechSafeName(displayName)
            val spokenNumber = spokenPhoneNumber(phoneNumber)
            if (safeName.isBlank() || spokenNumber.isBlank()) return null
            "$safeName. The phone number is $spokenNumber."
        }
        return when (rendered.size) {
            0 -> "I couldn't find a matching contact."
            1 -> "I found one matching contact. ${rendered.single()}"
            else -> "I found ${rendered.size} matching contacts. ${rendered.joinToString(" ")}"
        }
    }

    private fun JsonObject.string(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content?.trim()
    }

    private fun speechSafeName(value: String): String = value
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character.isWhitespace() -> ' '
                character in NAME_PUNCTUATION -> character
                else -> ' '
            }
        }
        .joinToString("")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()

    private fun speechSafeCalendarTitle(value: String): String = value
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character.isWhitespace() -> ' '
                character in CALENDAR_TITLE_PUNCTUATION -> character
                else -> ' '
            }
        }
        .joinToString("")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()

    private fun speechSafeMessage(value: String): String = value
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character.isWhitespace() -> ' '
                character in MESSAGE_PUNCTUATION -> character
                else -> ' '
            }
        }
        .joinToString("")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()

    private fun spokenPhoneNumber(value: String): String = buildList {
        value.forEach { character ->
            when {
                character in '0'..'9' -> add(DIGIT_WORDS.getValue(character))
                character == '+' -> add("plus")
                character.isLetter() -> add(character.toString())
            }
        }
    }.joinToString(" ")

    private val CONTACT_KEYS = setOf("contactId", "displayName", "phoneNumber")
    private val CONTACT_ID_PATTERN = Regex("[0-9]{1,32}")
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val NAME_PUNCTUATION = setOf('\'', '-', '.')
    private val CALENDAR_TITLE_PUNCTUATION = setOf('\'', '-', '.', ',', '&', '(', ')')
    private val CALENDAR_EVENT_KEYS = setOf("title", "startEpochMs", "endEpochMs", "allDay")
    private val MESSENGER_NOTIFICATION_KEYS = setOf("sender", "textPreview", "timestamp")
    private val MESSAGE_PUNCTUATION = setOf(
        '\'', '-', '.', ',', '?', '!', ':', ';', '&', '(', ')', '@', '#', '/', '$', '%', '+', '=',
    )
    private val CALENDAR_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    private val CALENDAR_DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a", Locale.getDefault())
    private val CALENDAR_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val MESSENGER_DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.getDefault())
    private val DIGIT_WORDS = mapOf(
        '0' to "zero",
        '1' to "one",
        '2' to "two",
        '3' to "three",
        '4' to "four",
        '5' to "five",
        '6' to "six",
        '7' to "seven",
        '8' to "eight",
        '9' to "nine",
    )
    private const val MAX_CONTACTS = 10
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_NUMBER_LENGTH = 100
    private const val MAX_CALENDAR_EVENTS = 10
    private const val MAX_CALENDAR_TITLE_LENGTH = 200
    private const val MAX_MESSENGER_NOTIFICATIONS = 10
    private const val MAX_MESSENGER_TEXT_LENGTH = 500
}
