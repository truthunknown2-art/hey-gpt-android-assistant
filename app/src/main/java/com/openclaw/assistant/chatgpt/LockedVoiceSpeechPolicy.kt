package com.openclaw.assistant.chatgpt

internal const val MAX_LOCKED_REPLY_WORDS = 80

/** Enforces the spoken-output limit even if the model ignores its prompt. */
internal fun limitLockedVoiceReply(text: String): String =
    text.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(MAX_LOCKED_REPLY_WORDS)
        .joinToString(" ")

/** Allows for engine startup plus deliberately conservative speech pacing. */
internal fun localTtsTimeoutMs(text: String): Long {
    val wordCount = text.trim().split(Regex("\\s+")).count(String::isNotBlank)
    return (TTS_STARTUP_ALLOWANCE_MS + wordCount * TTS_MS_PER_WORD)
        .coerceIn(MIN_TTS_TIMEOUT_MS, MAX_TTS_TIMEOUT_MS)
}

private const val TTS_STARTUP_ALLOWANCE_MS = 5_000L
private const val TTS_MS_PER_WORD = 650L
private const val MIN_TTS_TIMEOUT_MS = 15_000L
private const val MAX_TTS_TIMEOUT_MS = 90_000L
