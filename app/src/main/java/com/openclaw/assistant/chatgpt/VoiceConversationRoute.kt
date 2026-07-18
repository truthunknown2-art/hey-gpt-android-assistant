package com.openclaw.assistant.chatgpt

/**
 * Chooses the conversational voice path without weakening the device lock.
 * The unlocked path uses the tool-capable voice-main agent; a secure lock can only
 * use the isolated locked agent after a transcript has been captured.
 */
internal enum class VoiceConversationRoute {
    MAIN_OPENCLAW,
    LOCKED_OPENCLAW,
    UNLOCK_REQUIRED,
}

internal fun chooseVoiceConversationRoute(
    isDeviceLocked: Boolean,
    hasTranscript: Boolean,
    openClawReady: Boolean,
): VoiceConversationRoute = when {
    !isDeviceLocked -> VoiceConversationRoute.MAIN_OPENCLAW
    hasTranscript && openClawReady -> VoiceConversationRoute.LOCKED_OPENCLAW
    else -> VoiceConversationRoute.UNLOCK_REQUIRED
}
