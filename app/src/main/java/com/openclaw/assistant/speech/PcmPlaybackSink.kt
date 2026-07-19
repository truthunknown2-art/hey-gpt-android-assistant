package com.openclaw.assistant.speech

import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal interface PcmPlaybackSink {
    val playbackHeadPositionFrames: Long
    val isReleased: Boolean

    fun play()
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
    fun releaseAfterDrain(): Boolean
    fun abort()
}

internal class AudioTrackPcmPlaybackSink(
    private val track: AudioTrack,
) : PcmPlaybackSink {
    private val released = AtomicBoolean(false)

    override val playbackHeadPositionFrames: Long
        get() = track.playbackHeadPosition.toLong() and UINT32_MASK

    override val isReleased: Boolean
        get() = released.get()

    override fun play() = track.play()

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int =
        track.write(buffer, offset, length, AudioTrack.WRITE_BLOCKING)

    override fun releaseAfterDrain(): Boolean {
        if (!released.compareAndSet(false, true)) return false
        runCatching { track.stop() }
        runCatching { track.release() }
        return true
    }

    override fun abort() {
        if (!released.compareAndSet(false, true)) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private companion object {
        const val UINT32_MASK = 0xffff_ffffL
    }
}

internal suspend fun finishPcmPlayback(
    sink: PcmPlaybackSink,
    writtenFrames: Long,
    timeoutMs: Long = 3_000L,
    pollIntervalMs: Long = 10L,
) {
    try {
        val drained = withTimeoutOrNull(timeoutMs) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (sink.isReleased) throw CancellationException("PCM playback was stopped")
                if (sink.playbackHeadPositionFrames >= writtenFrames) break
                delay(pollIntervalMs)
            }
            true
        } ?: false
        if (!drained) throw IllegalStateException("Timed out draining PCM playback")
        currentCoroutineContext().ensureActive()
        if (!sink.releaseAfterDrain()) throw CancellationException("PCM playback was stopped")
    } catch (error: Throwable) {
        sink.abort()
        throw error
    }
}
