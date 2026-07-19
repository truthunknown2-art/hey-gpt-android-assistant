package com.openclaw.assistant.service

internal enum class AmbientUiTimeoutDecision {
    IGNORE,
    CONTINUE_HEADLESS,
    END_SESSION,
}

internal object AmbientVoicePolicy {
    fun acceptsUiAttachment(
        currentToken: String?,
        attachedToken: String?,
        sessionActive: Boolean,
    ): Boolean =
        sessionActive && !currentToken.isNullOrBlank() && currentToken == attachedToken

    fun uiTimeoutDecision(
        currentToken: String?,
        timeoutToken: String,
        launchPending: Boolean,
        sessionActive: Boolean,
    ): AmbientUiTimeoutDecision = when {
        !launchPending || currentToken != timeoutToken -> AmbientUiTimeoutDecision.IGNORE
        sessionActive -> AmbientUiTimeoutDecision.CONTINUE_HEADLESS
        else -> AmbientUiTimeoutDecision.END_SESSION
    }
}
