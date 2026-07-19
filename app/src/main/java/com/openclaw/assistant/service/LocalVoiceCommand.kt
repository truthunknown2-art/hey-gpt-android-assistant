package com.openclaw.assistant.service

internal sealed interface LocalVoiceCommand {
    data class Call(val contactOrNumber: String) : LocalVoiceCommand
    data class ReadSms(val unreadOnly: Boolean) : LocalVoiceCommand
    data class PlayMedia(val query: String) : LocalVoiceCommand
}

/** Narrow, deterministic v0.1 parser. Unknown language falls through to ChatGPT. */
internal object LocalVoiceCommandParser {
    private val callPattern = Regex(
        "^(?:please\\s+)?(?:call|dial|phone)\\s+(.+?)(?:\\s+please)?$",
        RegexOption.IGNORE_CASE,
    )
    private val readSmsPattern = Regex(
        "^(?:please\\s+)?read\\s+(?:me\\s+)?(?:my\\s+)?(?:(latest|last|newest|most recent|unread)\\s+)?(?:text|texts|message|messages|sms)(?:\\s+message)?(?:\\s+please)?$",
        RegexOption.IGNORE_CASE,
    )
    private val playPattern = Regex(
        "^(?:please\\s+)?play\\s+(.+?)(?:\\s+(?:on|in)\\s+spotify)?(?:\\s+please)?$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(transcript: String): LocalVoiceCommand? {
        val text = transcript.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

        callPattern.matchEntire(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return LocalVoiceCommand.Call(it) }

        readSmsPattern.matchEntire(text)?.let { match ->
            val qualifier = match.groupValues.getOrNull(1).orEmpty()
            return LocalVoiceCommand.ReadSms(
                unreadOnly = qualifier.equals("unread", ignoreCase = true),
            )
        }

        playPattern.matchEntire(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return LocalVoiceCommand.PlayMedia(it) }

        return null
    }
}
