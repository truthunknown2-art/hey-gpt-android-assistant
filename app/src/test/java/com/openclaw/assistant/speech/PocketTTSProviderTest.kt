package com.openclaw.assistant.speech

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PocketTTSProviderTest {
    @Test
    fun `partial write failure emits Speaking so fallback remains suppressed`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "application/octet-stream")
                    .addHeader("X-Audio-Sample-Rate", "24000")
                    .setBody(Buffer().write(ByteArray(8))),
            )
            val sink = PartialWriteFailureSink()
            val provider = PocketTTSProvider(
                context = mockk<Context>(relaxed = true),
                endpointProvider = { server.url("/v1/tts").toString() },
                sinkFactory = { sink },
                audioFocusController = GrantedAudioFocusController(),
            )

            val states = provider.speakWithProgress("hello").toList()

            states.size shouldBe 3
            states[0].shouldBeInstanceOf<TTSState.Preparing>()
            states[1].shouldBeInstanceOf<TTSState.Speaking>()
            states[2].shouldBeInstanceOf<TTSState.Error>()
            provider.startedLastAttempt shouldBe true
            sink.writeCount shouldBe 2
            sink.abortCount shouldBe 1
            sink.normalReleaseCount shouldBe 0
        } finally {
            server.shutdown()
        }
    }

    private class PartialWriteFailureSink : PcmPlaybackSink {
        var writeCount = 0
        var abortCount = 0
        var normalReleaseCount = 0

        override val playbackHeadPositionFrames: Long = 0
        override val isReleased: Boolean
            get() = abortCount > 0 || normalReleaseCount > 0

        override fun play() = Unit

        override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            writeCount += 1
            return if (writeCount == 1) minOf(2, length) else -1
        }

        override fun releaseAfterDrain(): Boolean {
            if (isReleased) return false
            normalReleaseCount += 1
            return true
        }

        override fun abort() {
            if (isReleased) return
            abortCount += 1
        }
    }

    private class GrantedAudioFocusController : PocketAudioFocusController {
        override fun request(): Boolean = true
        override fun abandon() = Unit
    }
}
