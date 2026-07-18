package com.openclaw.assistant.service

internal object VoiceSessionKeys {
    const val VOICE_MAIN_AGENT_ID = "voice-main"

    fun mainVoice(deviceId: String?): String =
        "agent:$VOICE_MAIN_AGENT_ID:voice-android-${safeDeviceSuffix(deviceId)}"

    fun lockedVoice(deviceId: String?): String =
        "agent:locked-voice:voice-locked-${safeDeviceSuffix(deviceId)}"

    private fun safeDeviceSuffix(deviceId: String?): String =
        deviceId
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            ?.take(32)
            ?.ifBlank { null }
            ?: "android"
}
