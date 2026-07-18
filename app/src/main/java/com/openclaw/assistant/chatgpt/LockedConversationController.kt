package com.openclaw.assistant.chatgpt

import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Runs the secure-keyguard conversation in a dedicated, tool-restricted
 * OpenClaw agent and stable per-device session. It deliberately avoids the
 * app's interactive ChatController session so the two histories never collide.
 */
internal class LockedConversationController(
    private val requestGateway: suspend (method: String, paramsJson: String, timeoutMs: Long) -> String,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun ask(deviceId: String?, message: String): String? {
        val sessionKey = lockedVoiceSessionKey(deviceId)
        val historyParams = buildJsonObject {
            put("sessionKey", JsonPrimitive(sessionKey))
            put("agentId", JsonPrimitive(AGENT_ID))
        }.toString()
        val repliesBefore = runCatching {
            parseAssistantReplies(requestGateway("chat.history", historyParams, REQUEST_TIMEOUT_MS))
        }.getOrDefault(emptyList())

        val voicePrompt = buildString {
            append("[Locked voice mode: answer in plain conversational text, without markdown, ")
            append("in at most 80 words. Do not use tools or expose sensitive stored information.]\n")
            append(message.trim())
        }
        val sendParams = buildJsonObject {
            put("sessionKey", JsonPrimitive(sessionKey))
            put("agentId", JsonPrimitive(AGENT_ID))
            put("message", JsonPrimitive(voicePrompt))
            put("thinking", JsonPrimitive("low"))
            put("timeoutMs", JsonPrimitive(REQUEST_TIMEOUT_MS))
            put("idempotencyKey", JsonPrimitive(UUID.randomUUID().toString()))
        }.toString()
        requestGateway("chat.send", sendParams, SEND_TIMEOUT_MS)

        repeat(MAX_POLLS) {
            pollDelay(POLL_INTERVAL_MS)
            val replies = runCatching {
                parseAssistantReplies(requestGateway("chat.history", historyParams, REQUEST_TIMEOUT_MS))
            }.getOrDefault(emptyList())
            if (replies.size > repliesBefore.size) return replies.last()
        }
        return null
    }

    companion object {
        internal const val AGENT_ID = "locked-voice"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val SEND_TIMEOUT_MS = 35_000L
        private const val POLL_INTERVAL_MS = 750L
        private const val MAX_POLLS = 120

        internal fun lockedVoiceSessionKey(deviceId: String?): String {
            val safeDeviceId = deviceId
                ?.lowercase()
                ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                ?.take(32)
                ?.ifBlank { null }
                ?: "android"
            return "agent:$AGENT_ID:voice-locked-$safeDeviceId"
        }

        internal fun parseAssistantReplies(historyJson: String): List<String> = runCatching {
            val root = Json.parseToJsonElement(historyJson) as? JsonObject ?: return@runCatching emptyList()
            val messages = root["messages"] as? JsonArray ?: return@runCatching emptyList()
            messages.mapNotNull { item ->
                val message = item as? JsonObject ?: return@mapNotNull null
                if ((message["role"] as? JsonPrimitive)?.content != "assistant") return@mapNotNull null
                when (val content = message["content"]) {
                    is JsonPrimitive -> content.content.trim().ifBlank { null }
                    is JsonArray -> content.asSequence()
                        .mapNotNull { it as? JsonObject }
                        .firstNotNullOfOrNull { part ->
                            if ((part["type"] as? JsonPrimitive)?.content != "text") return@firstNotNullOfOrNull null
                            (part["text"] as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
                        }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }
}
