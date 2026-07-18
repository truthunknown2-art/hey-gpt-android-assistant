package com.openclaw.assistant.chatgpt

import com.openclaw.assistant.gateway.GatewayVoiceTurnController
import com.openclaw.assistant.service.VoiceSessionKeys
import kotlinx.coroutines.delay

/**
 * Runs the secure-keyguard conversation in a dedicated, tool-restricted
 * OpenClaw agent and stable per-device session. It deliberately avoids the
 * app's interactive ChatController session so the two histories never collide.
 */
internal class LockedConversationController(
    private val requestGateway: suspend (method: String, paramsJson: String, timeoutMs: Long) -> String,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val turns = GatewayVoiceTurnController(requestGateway, pollDelay)

    suspend fun ask(deviceId: String?, message: String): String? =
        turns.ask(
            sessionKey = lockedVoiceSessionKey(deviceId),
            agentId = AGENT_ID,
            message = message,
            promptPrefix = LOCKED_PROMPT,
            timeoutMs = LOCKED_TURN_TIMEOUT_MS,
        )

    companion object {
        internal const val AGENT_ID = "locked-voice"
        internal const val LOCKED_TURN_TIMEOUT_MS = 60_000L
        private const val LOCKED_PROMPT =
            "[Locked voice mode: answer in plain conversational text, without markdown, " +
                "in at most 80 words. Do not use tools or expose sensitive stored information.]"

        internal fun lockedVoiceSessionKey(deviceId: String?): String =
            VoiceSessionKeys.lockedVoice(deviceId)

        internal fun parseAssistantReplies(historyJson: String): List<String> =
            GatewayVoiceTurnController.parseConversationMessages(historyJson)
                .filter { it.role == "assistant" }
                .mapNotNull { it.text }
    }
}
