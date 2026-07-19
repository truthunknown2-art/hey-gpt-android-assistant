package com.openclaw.assistant.speech

import java.util.Locale

internal data class AndroidTtsVoiceCandidate(
    val name: String,
    val languageTag: String,
    val quality: Int,
    val latency: Int,
    val networkRequired: Boolean,
    val installed: Boolean,
)

internal enum class PrivateAndroidTtsQueueResult {
    VOICE_UNAVAILABLE,
    QUEUE_FAILED,
    QUEUED,
}

internal fun selectAndroidTtsEngine(
    configuredEngine: String,
    installedEngines: Set<String>,
): String? = when {
    configuredEngine.isNotBlank() -> configuredEngine
    TTSUtils.GOOGLE_TTS_PACKAGE in installedEngines -> TTSUtils.GOOGLE_TTS_PACKAGE
    else -> null
}

internal fun selectBestAndroidTtsVoice(
    candidates: Collection<AndroidTtsVoiceCandidate>,
    targetLocale: Locale,
    allowNetworkRequired: Boolean = true,
): String? {
    val targetLanguage = targetLocale.language
    val targetTag = targetLocale.toLanguageTag()

    return candidates
        .asSequence()
        .filter { it.installed }
        .filter { allowNetworkRequired || !it.networkRequired }
        .filter { Locale.forLanguageTag(it.languageTag).language == targetLanguage }
        .sortedWith(
            compareByDescending<AndroidTtsVoiceCandidate> {
                it.languageTag.equals(targetTag, ignoreCase = true)
            }
                .thenByDescending { it.quality }
                .thenByDescending { it.networkRequired }
                .thenBy { it.latency }
                .thenBy { it.name },
        )
        .firstOrNull()
        ?.name
}

internal fun assignVerifiedOfflineAndroidTtsVoice(
    selected: AndroidTtsVoiceCandidate?,
    assignVoice: (String) -> Boolean,
    effectiveVoice: () -> AndroidTtsVoiceCandidate?,
): Boolean {
    if (selected == null || !selected.installed || selected.networkRequired) return false
    if (!assignVoice(selected.name)) return false
    val effective = effectiveVoice() ?: return false
    return effective.name == selected.name &&
        effective.languageTag.equals(selected.languageTag, ignoreCase = true) &&
        effective.installed &&
        !effective.networkRequired
}

internal fun queuePrivateAndroidTtsSpeech(
    prepareVoice: () -> Boolean,
    enqueue: () -> Boolean,
): PrivateAndroidTtsQueueResult {
    if (!runCatching(prepareVoice).getOrDefault(false)) {
        return PrivateAndroidTtsQueueResult.VOICE_UNAVAILABLE
    }
    return if (runCatching(enqueue).getOrDefault(false)) {
        PrivateAndroidTtsQueueResult.QUEUED
    } else {
        PrivateAndroidTtsQueueResult.QUEUE_FAILED
    }
}
