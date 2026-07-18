package com.openclaw.assistant.service

import com.openclaw.assistant.data.SettingsRepository
import java.util.concurrent.ConcurrentHashMap

internal object WakeWordTargetMatcher {
    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun select(
        text: String,
        targets: List<SettingsRepository.WakeWordTarget>,
        threshold: Float,
    ): SettingsRepository.WakeWordTarget? {
        return targets
            .map { target -> target to confidence(text, target.phrase) }
            .filter { (_, confidence) -> confidence >= threshold }
            .maxWithOrNull(
                compareBy<Pair<SettingsRepository.WakeWordTarget, Float>> { it.second }
                    .thenBy { it.first.phrase.length }
            )
            ?.first
    }

    fun maxConfidence(text: String, targets: List<SettingsRepository.WakeWordTarget>): Float {
        return targets.maxOfOrNull { confidence(text, it.phrase) } ?: 0f
    }

    private fun confidence(text: String, phrase: String): Float {
        val pattern = regexCache.computeIfAbsent(phrase) {
            Regex("\\[${Regex.escape(phrase)}\\]\\(([0-9.]+)\\)", RegexOption.IGNORE_CASE)
        }
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()
            ?: if (text.contains(phrase, ignoreCase = true)) 1.0f else 0.0f
    }
}
