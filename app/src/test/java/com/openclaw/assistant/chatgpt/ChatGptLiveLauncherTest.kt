package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptLiveLauncherTest {
    @Test
    fun `recognizes official ChatGPT assistant component`() {
        assertTrue(
            ChatGptLiveLauncher.isChatGptAssistantComponent(
                "com.openai.chatgpt/com.openai.feature.assistant.impl.AssistantVoiceInteractionService",
            ),
        )
    }

    @Test
    fun `does not invoke a different default assistant`() {
        assertFalse(
            ChatGptLiveLauncher.isChatGptAssistantComponent(
                "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            ),
        )
        assertFalse(ChatGptLiveLauncher.isChatGptAssistantComponent(null))
    }
}
