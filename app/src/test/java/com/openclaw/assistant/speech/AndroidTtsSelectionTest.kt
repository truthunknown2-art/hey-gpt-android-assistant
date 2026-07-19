package com.openclaw.assistant.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTtsSelectionTest {
    @Test
    fun `explicit engine wins over automatic Google preference`() {
        assertEquals(
            "com.example.tts",
            selectAndroidTtsEngine(
                configuredEngine = "com.example.tts",
                installedEngines = setOf("com.example.tts", TTSUtils.GOOGLE_TTS_PACKAGE),
            ),
        )
    }

    @Test
    fun `Google engine is preferred when automatic and installed`() {
        assertEquals(
            TTSUtils.GOOGLE_TTS_PACKAGE,
            selectAndroidTtsEngine(
                configuredEngine = "",
                installedEngines = setOf("com.samsung.SMT", TTSUtils.GOOGLE_TTS_PACKAGE),
            ),
        )
    }

    @Test
    fun `system default remains fallback when Google is unavailable`() {
        assertEquals(
            null,
            selectAndroidTtsEngine(
                configuredEngine = "",
                installedEngines = setOf("com.samsung.SMT"),
            ),
        )
    }

    @Test
    fun `voice selection favors exact locale then quality and natural network voice`() {
        val candidates = listOf(
            candidate("offline", "en-US", quality = 400, network = false),
            candidate("network", "en-US", quality = 400, network = true),
            candidate("higher-other-locale", "en-GB", quality = 500, network = true),
        )

        assertEquals(
            "network",
            selectBestAndroidTtsVoice(candidates, Locale.US),
        )
    }

    @Test
    fun `voice selection excludes missing voice data`() {
        val candidates = listOf(
            candidate("missing", "en-US", quality = 500, network = true, installed = false),
            candidate("installed", "en-US", quality = 400, network = false),
        )

        assertEquals(
            "installed",
            selectBestAndroidTtsVoice(candidates, Locale.US),
        )
    }

    @Test
    fun `private voice selection excludes network required voices`() {
        val candidates = listOf(
            candidate("natural-network", "en-US", quality = 500, network = true),
            candidate("offline", "en-US", quality = 300, network = false),
        )

        assertEquals(
            "offline",
            selectBestAndroidTtsVoice(candidates, Locale.US, allowNetworkRequired = false),
        )
    }

    @Test
    fun `private voice selection fails when every voice requires network`() {
        val candidates = listOf(
            candidate("natural-network", "en-US", quality = 500, network = true),
        )

        assertEquals(
            null,
            selectBestAndroidTtsVoice(candidates, Locale.US, allowNetworkRequired = false),
        )
    }

    private fun candidate(
        name: String,
        languageTag: String,
        quality: Int,
        network: Boolean,
        installed: Boolean = true,
    ) = AndroidTtsVoiceCandidate(
        name = name,
        languageTag = languageTag,
        quality = quality,
        latency = 200,
        networkRequired = network,
        installed = installed,
    )
}
