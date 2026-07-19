package com.openclaw.assistant.broker

import java.io.Closeable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        else -> null
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
}
