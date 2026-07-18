package com.openclaw.assistant.gateway

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Sends one Gateway voice turn and returns only that turn's correlated reply. */
internal class GatewayVoiceTurnController(
    private val requestGateway: suspend (method: String, paramsJson: String, timeoutMs: Long) -> String,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val turnMutex = Mutex()

    suspend fun ask(
        sessionKey: String,
        agentId: String,
        message: String,
        promptPrefix: String? = null,
        timeoutMs: Long = DEFAULT_TURN_TIMEOUT_MS,
        beforeSend: suspend () -> Unit = {},
    ): String? = turnMutex.withLock {
        require(sessionKey.isNotBlank())
        require(agentId.isNotBlank())
        require(message.isNotBlank())
        require(timeoutMs > 0)

        val historyParams = buildJsonObject {
            put("sessionKey", JsonPrimitive(sessionKey))
            put("agentId", JsonPrimitive(agentId))
        }.toString()

        var serverRunId: String? = null
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                // Fail closed if a baseline cannot be read. Otherwise a stale
                // assistant mirror could be spoken as this turn's response.
                parseConversationMessages(
                    requestGateway("chat.history", historyParams, REQUEST_TIMEOUT_MS),
                )

                val requestId = UUID.randomUUID().toString()
                val prompt = promptPrefix
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { "$it\n${message.trim()}" }
                    ?: message.trim()
                val sendParams = buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("agentId", JsonPrimitive(agentId))
                    put("message", JsonPrimitive(prompt))
                    put("thinking", JsonPrimitive("low"))
                    put("timeoutMs", JsonPrimitive(REQUEST_TIMEOUT_MS))
                    put("idempotencyKey", JsonPrimitive(requestId))
                }.toString()

                beforeSend()
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
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val SEND_TIMEOUT_MS = 35_000L
        private const val ABORT_TIMEOUT_MS = 5_000L
        internal const val DEFAULT_TURN_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 750L

        internal data class ConversationMessage(
            val role: String,
            val text: String?,
            val mirrorIdentity: String?,
            val idempotencyKey: String?,
        )

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
