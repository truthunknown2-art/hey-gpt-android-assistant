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
): String? {
    val targetLanguage = targetLocale.language
    val targetTag = targetLocale.toLanguageTag()

    return candidates
        .asSequence()
        .filter { it.installed }
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
