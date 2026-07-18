package com.openclaw.assistant.service

import android.app.KeyguardManager
import android.content.Context
import com.openclaw.assistant.node.NodeRuntime
import com.openclaw.assistant.protocol.OpenClawContactsCommand
import com.openclaw.assistant.protocol.OpenClawMediaCommand
import com.openclaw.assistant.protocol.OpenClawPhoneCommand
import com.openclaw.assistant.protocol.OpenClawSmsCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class LocalVoiceCommandExecutor(
    context: Context,
    private val runtime: NodeRuntime,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Completed(val spokenFeedback: String? = null) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun execute(command: LocalVoiceCommand): Result = when (command) {
        is LocalVoiceCommand.Call -> executeCall(command.contactOrNumber)
        is LocalVoiceCommand.ReadSms -> executeReadSms(command.unreadOnly)
        is LocalVoiceCommand.PlayMedia -> executePlay(command.query)
    }

    private suspend fun executeCall(contactOrNumber: String): Result {
        val number = if (looksLikePhoneNumber(contactOrNumber)) {
            contactOrNumber
        } else {
            resolveSingleContactNumber(contactOrNumber)
                ?: return Result.Failed("I could not find one unambiguous contact named $contactOrNumber.")
        }
        val params = buildJsonObject { put("number", JsonPrimitive(number)) }.toString()
        val result = runtime.invokeLocalDeviceCommand(OpenClawPhoneCommand.Call.rawValue, params)
        return if (result.ok) {
            Result.Completed()
        } else {
            Result.Failed(result.error?.message ?: "The phone call could not be started.")
        }
    }

    private suspend fun resolveSingleContactNumber(query: String): String? {
        val params = buildJsonObject { put("query", JsonPrimitive(query)) }.toString()
        val result = runtime.invokeLocalDeviceCommand(OpenClawContactsCommand.Search.rawValue, params)
        if (!result.ok || result.payloadJson.isNullOrBlank()) return null

        val contacts = runCatching {
            json.parseToJsonElement(result.payloadJson).jsonObject["contacts"]?.jsonArray
        }.getOrNull() ?: return null
        val candidates = contacts.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = (item["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val number = (item["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isBlank() || number.isBlank()) null else name to number
        }
        val exact = candidates.filter { (name, _) -> name.equals(query, ignoreCase = true) }
        return (if (exact.isNotEmpty()) exact else candidates)
            .map { it.second }
            .distinct()
            .singleOrNull()
    }

    private suspend fun executeReadSms(unreadOnly: Boolean): Result {
        val command = if (unreadOnly) {
            OpenClawSmsCommand.ReadUnread.rawValue
        } else {
            OpenClawSmsCommand.ReadLatest.rawValue
        }
        val result = runtime.invokeLocalDeviceCommand(command)
        if (!result.ok || result.payloadJson.isNullOrBlank()) {
            return Result.Failed(result.error?.message ?: "I could not read text messages.")
        }

        val first = runCatching {
            json.parseToJsonElement(result.payloadJson)
                .jsonObject["messages"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
        }.getOrNull() ?: return Result.Failed(
            if (unreadOnly) "There are no unread text messages." else "There are no text messages to read."
        )
        val sender = first["address"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "an unknown sender" }
        val body = first["body"]?.jsonPrimitive?.content?.trim().orEmpty()
        val keyguardLocked = appContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val feedback = if (keyguardLocked) {
            "Your latest text is from $sender. Unlock the phone to hear its contents."
        } else if (body.isBlank()) {
            "Your latest text from $sender has no message body."
        } else {
            "Your latest text from $sender says: ${body.take(MAX_SPOKEN_SMS_LENGTH)}"
        }
        return Result.Completed(spokenFeedback = feedback)
    }

    private suspend fun executePlay(query: String): Result {
        val params = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("packageName", JsonPrimitive("com.spotify.music"))
        }.toString()
        val result = runtime.invokeLocalDeviceCommand(OpenClawMediaCommand.PlaySearch.rawValue, params)
        return if (result.ok) {
            Result.Completed()
        } else {
            Result.Failed(result.error?.message ?: "Spotify could not play that request.")
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean =
        value.replace(Regex("[\\s().-]"), "").matches(Regex("^\\+?\\d{3,15}$"))

    companion object {
        private const val MAX_SPOKEN_SMS_LENGTH = 500
    }
}
