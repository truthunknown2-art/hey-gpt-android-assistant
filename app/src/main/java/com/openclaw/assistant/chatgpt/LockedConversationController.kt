package com.openclaw.assistant.chatgpt

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        var serverRunId: String? = null
        return try {
            val result: String? = withTimeoutOrNull(LOCKED_TURN_TIMEOUT_MS) {
                // If the baseline cannot be read, fail closed: accepting the
                // first later assistant message could speak a stale answer.
                parseConversationMessages(
                    requestGateway("chat.history", historyParams, REQUEST_TIMEOUT_MS),
                )

                val requestId = UUID.randomUUID().toString()
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
                    put("idempotencyKey", JsonPrimitive(requestId))
                }.toString()
                val sendResult = requestGateway("chat.send", sendParams, SEND_TIMEOUT_MS)
                serverRunId = parseRunId(sendResult)
                    ?: error("chat.send did not return a runId")

                var correlatedReply: String? = null
                do {
                    pollDelay(POLL_INTERVAL_MS)
                    val historyJson = try {
                        requestGateway("chat.history", historyParams, REQUEST_TIMEOUT_MS)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        continue
                    }
                    correlatedReply = findReplyForRequest(historyJson, requestId)
                } while (correlatedReply == null)
                correlatedReply
            }
            if (result == null) abortRun(sessionKey, serverRunId)
            result
        } catch (error: CancellationException) {
            abortRun(sessionKey, serverRunId)
            throw error
        }
    }

    private suspend fun abortRun(sessionKey: String, runId: String?) {
        if (runId.isNullOrBlank()) return
        withContext(NonCancellable) {
            runCatching {
                requestGateway(
                    "chat.abort",
                    buildJsonObject {
                        put("sessionKey", JsonPrimitive(sessionKey))
                        put("runId", JsonPrimitive(runId))
                    }.toString(),
                    ABORT_TIMEOUT_MS,
                )
            }
        }
    }

    companion object {
        internal const val AGENT_ID = "locked-voice"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val SEND_TIMEOUT_MS = 35_000L
        private const val ABORT_TIMEOUT_MS = 5_000L
        internal const val LOCKED_TURN_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 750L

        internal fun lockedVoiceSessionKey(deviceId: String?): String {
            val safeDeviceId = deviceId
                ?.lowercase()
                ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                ?.take(32)
                ?.ifBlank { null }
                ?: "android"
            return "agent:$AGENT_ID:voice-locked-$safeDeviceId"
        }

        internal data class ConversationMessage(
            val role: String,
            val text: String?,
            val mirrorIdentity: String?,
            val idempotencyKey: String?,
        )

        internal fun parseAssistantReplies(historyJson: String): List<String> =
            parseConversationMessages(historyJson)
                .filter { it.role == "assistant" }
                .mapNotNull(ConversationMessage::text)

        internal fun findReplyForRequest(historyJson: String, requestId: String): String? {
            val messages = parseConversationMessages(historyJson)
            val prompt = messages.lastOrNull { message ->
                message.role == "user" && message.idempotencyKey == "$requestId:user"
            } ?: return null
            val turnIdentity = prompt.mirrorIdentity
                ?.takeIf { it.endsWith(":prompt") }
                ?.removeSuffix(":prompt")
                ?: return null
            return messages.firstOrNull { message ->
                message.role == "assistant" &&
                    message.mirrorIdentity == "$turnIdentity:assistant" &&
                    !message.text.isNullOrBlank()
            }?.text
        }

        internal fun parseConversationMessages(historyJson: String): List<ConversationMessage> {
            val root = Json.parseToJsonElement(historyJson) as? JsonObject
                ?: error("chat.history did not return an object")
            val messages = root["messages"] as? JsonArray
                ?: error("chat.history did not return messages")
            return messages.mapNotNull { item ->
                val message = item as? JsonObject ?: return@mapNotNull null
                val role = (message["role"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val text = when (val content = message["content"]) {
                    is JsonPrimitive -> content.content.trim().ifBlank { null }
                    is JsonArray -> content.asSequence()
                        .mapNotNull { it as? JsonObject }
                        .filter { (it["type"] as? JsonPrimitive)?.content == "text" }
                        .mapNotNull { (it["text"] as? JsonPrimitive)?.content?.trim()?.ifBlank { null } }
                        .joinToString(" ")
                        .trim()
                        .ifBlank { null }
                    else -> null
                }
                val metadata = message["__openclaw"] as? JsonObject
                ConversationMessage(
                    role = role,
                    text = text,
                    mirrorIdentity = (metadata?.get("mirrorIdentity") as? JsonPrimitive)?.content,
                    idempotencyKey =
                        (message["idempotencyKey"] as? JsonPrimitive)?.content
                            ?: (metadata?.get("idempotencyKey") as? JsonPrimitive)?.content,
                )
            }
        }

        internal fun parseRunId(sendResultJson: String): String? = runCatching {
            val root = Json.parseToJsonElement(sendResultJson) as? JsonObject
            (root?.get("runId") as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
        }.getOrNull()
    }
}
