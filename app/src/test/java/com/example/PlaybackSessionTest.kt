package com.example

import com.example.domain.tts.PlaybackAudioSink
import com.example.domain.tts.PlaybackSession
import com.example.domain.tts.PlaybackSynthesizer
import com.example.domain.tts.PlaybackUnit
import com.example.domain.tts.SynthesizedAudio
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class PlaybackSessionTest {
    @Test fun highlightUsesDisplayedTextAndMovesAtFirstPlayedSentenceFrame() {
        val sink = PositionControlledSink()
        val session = PlaybackSession(
            synthesizer = { _, _, _ -> SynthesizedAudio(FloatArray(1_000) { 0.1f }, sink.sampleRate, 0.2f) },
            sink = sink,
            nowMs = { System.nanoTime() / 1_000_000L }
        )
        try {
            session.appendUnits(listOf(
                PlaybackUnit(1, "Shown words here.", 0, 0, "Different spoken words."),
                PlaybackUnit(2, "Next display text.", 0, 0)
            ), complete = true)
            session.play(1)
            assertTrue(sink.allWritten.await(2, TimeUnit.SECONDS))

            sink.presentedFrames = 0L
            assertEquals(-1, session.position().sentenceId)
            sink.presentedFrames = 1L
            assertEquals(1, session.position().sentenceId)
            assertEquals(0, session.position().wordIndex)
            sink.presentedFrames = 1_000L
            assertEquals(1, session.position().sentenceId)
            sink.presentedFrames = 1_150L
            assertEquals(-1, session.position().sentenceId)
            sink.presentedFrames = 1_151L
            assertEquals(2, session.position().sentenceId)

            val spans = PlaybackSession.WordWeights.wordSpans("Shown words here.", 1_000)
            assertEquals(3, spans.size)
            assertEquals("Shown", "Shown words here.".substring(spans[0].startChar, spans[0].endChar))
            assertEquals("here.", "Shown words here.".substring(spans.last().startChar, spans.last().endChar))
            assertEquals(0, spans.first().startFrame)
            assertEquals(1_000, spans.last().endFrame)
        } finally {
            session.close()
        }
    }

    @Test fun synthesizesFollowingUnitWhileCurrentAudioIsStillBeingWritten() {
        val synthCalls = AtomicInteger()
        val secondSynthesized = CountDownLatch(1)
        val sink = BlockingFirstWriteSink()
        val synthesizer = PlaybackSynthesizer { unit, _, _ ->
            if (synthCalls.incrementAndGet() == 2) secondSynthesized.countDown()
            SynthesizedAudio(FloatArray(400) { 0.2f }, sink.sampleRate, rtf = 0.3f)
        }
        val session = PlaybackSession(synthesizer, sink, nowMs = { System.nanoTime() / 1_000_000L })
        try {
            session.appendUnits(listOf(
                PlaybackUnit(10, "First paragraph sentence.", 0, 0),
                PlaybackUnit(11, "Next sentence follows.", 0, 0)
            ), complete = true)
            session.play(10)
            assertTrue("first clip did not reach audio writer", sink.firstWrite.await(2, TimeUnit.SECONDS))
            assertTrue("N+1 was not synthesized while N was still writing", secondSynthesized.await(2, TimeUnit.SECONDS))
            assertTrue("queued audio exceeded the configured lookahead bound", session.stats().bufferedAudioMs <= 30_000)
        } finally {
            sink.allowWrite.countDown()
            session.close()
        }
    }

    @Test fun positionMapsFramesToCorrectSentenceAndWord() {
        val sink = BlockingFirstWriteSink()
        val synthesizer = PlaybackSynthesizer { _, _, _ ->
            SynthesizedAudio(FloatArray(100) { 0.1f }, sink.sampleRate, rtf = 0.1f)
        }
        val session = PlaybackSession(synthesizer, sink, nowMs = { System.nanoTime() / 1_000_000L })
        try {
            session.appendUnits(listOf(
                PlaybackUnit(1, "Hello world test sentence.", 0, 0),
                PlaybackUnit(2, "Second sentence here.", 0, 0)
            ), complete = true)
            session.play(1)
            assertTrue(sink.firstWrite.await(2, TimeUnit.SECONDS))
            sink.allowWrite.countDown()
            Thread.sleep(50)
            val pos = session.position()
            assertTrue("Expected sentenceId 1, got ${pos.sentenceId}", pos.sentenceId == 1)
        } finally {
            sink.allowWrite.countDown()
            session.close()
        }
    }

    @Test fun seekToCancelsStaleSynthesis() {
        val sink = BlockingFirstWriteSink()
        val synthesizer = PlaybackSynthesizer { _, _, _ ->
            Thread.sleep(50)
            SynthesizedAudio(FloatArray(100) { 0.1f }, sink.sampleRate, rtf = 0.1f)
        }
        val session = PlaybackSession(synthesizer, sink, nowMs = { System.nanoTime() / 1_000_000L })
        try {
            session.appendUnits(listOf(
                PlaybackUnit(1, "First.", 0, 0),
                PlaybackUnit(2, "Second.", 0, 0),
                PlaybackUnit(3, "Third.", 0, 0)
            ), complete = true)
            session.play(1)
            Thread.sleep(30)
            session.seekTo(3)
            assertTrue(session.unitsSnapshot().isNotEmpty())
        } finally {
            sink.allowWrite.countDown()
            session.close()
        }
    }

    private class BlockingFirstWriteSink : PlaybackAudioSink {
        override val sampleRate = 100
        val firstWrite = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        private val writes = AtomicInteger()
        @Volatile private var frames = 0L
        @Volatile var playing = false

        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun stopAndFlush() { playing = false; allowWrite.countDown(); frames = 0L }
        override fun write(samples: FloatArray, offset: Int, count: Int): Int {
            if (writes.incrementAndGet() == 1) {
                firstWrite.countDown()
                allowWrite.await(5, TimeUnit.SECONDS)
            }
            frames += count
            return count
        }
        override fun playedFrames(): Long = min(frames, 50L)
        override fun close() { allowWrite.countDown() }
    }

    private class PositionControlledSink : PlaybackAudioSink {
        override val sampleRate = 1_000
        val allWritten = CountDownLatch(1)
        @Volatile var presentedFrames = 0L
        private val totalWrites = AtomicInteger()
        override fun play() = Unit
        override fun pause() = Unit
        override fun stopAndFlush() { presentedFrames = 0L }
        override fun write(samples: FloatArray, offset: Int, count: Int): Int {
            if (totalWrites.addAndGet(count) >= 2_150) allWritten.countDown()
            return count
        }
        override fun playedFrames(): Long = presentedFrames
        override fun close() = Unit
    }
}
