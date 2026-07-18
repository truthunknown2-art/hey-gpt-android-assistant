package com.openclaw.assistant.chatgpt

/**
 * Chooses the conversational voice path without weakening the device lock.
 * Device actions use the separate OpenClaw/Hermes assistant route.
 */
internal enum class VoiceConversationRoute {
    CHATGPT_LIVE,
    LOCKED_OPENCLAW,
    UNLOCK_REQUIRED,
}

internal fun chooseVoiceConversationRoute(
    isDeviceLocked: Boolean,
    hasTranscript: Boolean,
    openClawReady: Boolean,
): VoiceConversationRoute = when {
    !isDeviceLocked -> VoiceConversationRoute.CHATGPT_LIVE
    hasTranscript && openClawReady -> VoiceConversationRoute.LOCKED_OPENCLAW
    else -> VoiceConversationRoute.UNLOCK_REQUIRED
}

/**
 * An unlocked Hey GPT wake is already an explicit request for official Live.
 * Securely locked devices still need a captured question for the OpenClaw lane.
 */
internal fun shouldLaunchChatGptImmediately(isDeviceLocked: Boolean): Boolean = !isDeviceLocked
