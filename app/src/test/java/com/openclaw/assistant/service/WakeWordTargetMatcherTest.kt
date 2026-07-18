package com.openclaw.assistant.service

import com.openclaw.assistant.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTargetMatcherTest {
    private fun target(phrase: String, route: String) = SettingsRepository.WakeWordTarget(
        phrase = phrase,
        target = route,
        wakeSound = SettingsRepository.WAKE_SOUND_NONE,
    )

    @Test
    fun `longer command phrase wins when it contains live phrase`() {
        val live = target("hey g p t", SettingsRepository.VOICE_TARGET_CHATGPT)
        val command = target("hey g p t command", SettingsRepository.VOICE_TARGET_OPENCLAW)

        val selected = WakeWordTargetMatcher.select(
            text = "hey g p t command",
            targets = listOf(command, live),
            threshold = 0.7f,
        )

        assertEquals(SettingsRepository.VOICE_TARGET_OPENCLAW, selected?.target)
    }

    @Test
    fun `plain live phrase routes to ChatGPT`() {
        val live = target("hey g p t", SettingsRepository.VOICE_TARGET_CHATGPT)
        val command = target("hey g p t command", SettingsRepository.VOICE_TARGET_OPENCLAW)

        assertEquals(
            SettingsRepository.VOICE_TARGET_CHATGPT,
            WakeWordTargetMatcher.select("hey g p t", listOf(command, live), 0.7f)?.target,
        )
    }

    @Test
    fun `confidence below threshold is ignored`() {
        val live = target("hey g p t", SettingsRepository.VOICE_TARGET_CHATGPT)

        assertNull(WakeWordTargetMatcher.select("[hey g p t](0.45)", listOf(live), 0.7f))
    }
}
