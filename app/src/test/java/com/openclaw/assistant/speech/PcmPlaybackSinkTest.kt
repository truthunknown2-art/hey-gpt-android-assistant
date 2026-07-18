package com.openclaw.assistant.speech

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PcmPlaybackSinkTest {
    @Test
    fun `completion waits for playback drain before normal release`() = runTest {
        val sink = FakePcmPlaybackSink()
        var completed = false

        val job = launch {
            finishPcmPlayback(sink, writtenFrames = 240, timeoutMs = 1_000, pollIntervalMs = 10)
            completed = true
        }
        runCurrent()

        completed shouldBe false
        sink.normalReleaseCount shouldBe 0
        sink.abortCount shouldBe 0

        sink.playedFrames = 240
        advanceTimeBy(10)
        job.join()

        completed shouldBe true
        sink.normalReleaseCount shouldBe 1
        sink.abortCount shouldBe 0
    }

    @Test
    fun `cancellation aborts playback promptly without draining`() = runTest {
        val sink = FakePcmPlaybackSink()
        val job = launch {
            finishPcmPlayback(sink, writtenFrames = 24_000, timeoutMs = 10_000, pollIntervalMs = 10)
        }
        runCurrent()

        job.cancelAndJoin()

        sink.normalReleaseCount shouldBe 0
        sink.abortCount shouldBe 1
    }

    @Test
    fun `stop winning at drain boundary prevents normal completion`() = runTest {
        val sink = FakePcmPlaybackSink().apply {
            playedFrames = 240
            abort()
        }

        val error = runCatching {
            finishPcmPlayback(sink, writtenFrames = 240, timeoutMs = 1_000, pollIntervalMs = 10)
        }.exceptionOrNull()

        error.shouldBeInstanceOf<CancellationException>()
        sink.normalReleaseCount shouldBe 0
        sink.abortCount shouldBe 1
    }

    private class FakePcmPlaybackSink : PcmPlaybackSink {
        var playedFrames = 0L
        var normalReleaseCount = 0
        var abortCount = 0

        override val playbackHeadPositionFrames: Long
            get() = playedFrames
        override val isReleased: Boolean
            get() = normalReleaseCount > 0 || abortCount > 0

        override fun play() = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length

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
}
