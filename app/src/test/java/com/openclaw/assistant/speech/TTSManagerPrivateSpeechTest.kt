package com.openclaw.assistant.speech

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class TTSManagerPrivateSpeechTest {
    @Test
    fun `private speech uses only the offline local provider`() = runTest {
        val local = FakePrivateProvider()
        val pocket = mockk<TTSProvider>(relaxed = true)
        val openAi = mockk<TTSProvider>(relaxed = true)
        val manager = TTSManager(
            context = RuntimeEnvironment.getApplication(),
            providers = mapOf(
                TTSProviderType.LOCAL to local,
                TTSProviderType.POCKET to pocket,
                TTSProviderType.OPENAI to openAi,
            ),
            settings = mockk(relaxed = true),
        )

        val states = manager.speakPrivateWithProgress("private preview").toList()

        assertEquals(listOf(TTSState.Preparing, TTSState.Speaking, TTSState.Done), states)
        assertEquals(listOf("private preview"), local.privateSpeech)
        verify(exactly = 0) { pocket.speakWithProgress(any()) }
        verify(exactly = 0) { openAi.speakWithProgress(any()) }
    }

    @Test
    fun `private speech fails closed without an offline provider`() = runTest {
        val pocket = mockk<TTSProvider>(relaxed = true)
        val manager = TTSManager(
            context = RuntimeEnvironment.getApplication(),
            providers = mapOf(TTSProviderType.POCKET to pocket),
            settings = mockk(relaxed = true),
        )

        val states = manager.speakPrivateWithProgress("private preview").toList()

        assertTrue(states.single() is TTSState.Error)
        verify(exactly = 0) { pocket.speakWithProgress(any()) }
    }

    private class FakePrivateProvider : TTSProvider, PrivateTTSProvider {
        val privateSpeech = mutableListOf<String>()

        override fun speakPrivateWithProgress(text: String): Flow<TTSState> {
            privateSpeech += text
            return flowOf(TTSState.Preparing, TTSState.Speaking, TTSState.Done)
        }

        override suspend fun speak(text: String): Boolean = error("ordinary speech must not be used")
        override fun speakWithProgress(text: String): Flow<TTSState> =
            error("ordinary speech must not be used")
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun isAvailable(): Boolean = true
        override fun getType(): String = TTSProviderType.LOCAL
        override fun getDisplayName(): String = "Offline test voice"
        override fun isConfigured(): Boolean = true
        override fun getConfigurationError(): String? = null
    }
}
