package com.openclaw.assistant.chatgpt

/**
 * Chooses the conversational voice path without weakening the device lock.
 * Local deterministic commands are handled before this policy is consulted.
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
