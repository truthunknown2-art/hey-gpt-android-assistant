package com.openclaw.assistant.service

internal enum class VoiceDispatchPath {
    DEDICATED_GATEWAY,
    CONFIGURED_BACKEND,
}

internal object VoiceDispatchPolicy {
    fun selectPath(isHeyGptMainProfile: Boolean): VoiceDispatchPath =
        if (isHeyGptMainProfile) {
            VoiceDispatchPath.DEDICATED_GATEWAY
        } else {
            VoiceDispatchPath.CONFIGURED_BACKEND
        }
}
