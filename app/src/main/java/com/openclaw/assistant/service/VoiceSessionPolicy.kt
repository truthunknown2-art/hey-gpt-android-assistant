package com.openclaw.assistant.service

internal object VoiceSessionPolicy {
    fun selectGatewaySessionKey(
        forcedSessionKey: String?,
        resumeLatestSession: Boolean,
        currentSessionKey: String,
        newSessionKey: String,
    ): String = forcedSessionKey
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: if (resumeLatestSession) currentSessionKey else newSessionKey

    fun continuousMode(forceContinuous: Boolean, configuredContinuous: Boolean): Boolean =
        forceContinuous || configuredContinuous
}
