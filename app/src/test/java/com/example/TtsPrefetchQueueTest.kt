package com.example

import com.example.domain.tts.PrefetchItem
import com.example.domain.tts.TtsPrefetchQueue
import com.example.domain.pdf.groupIntoParagraphChunks
import com.example.data.model.ParsedSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsPrefetchQueueTest {

    @Test
    fun testQueueBoundedSizeAndOrder() = runTest {
        val queue = TtsPrefetchQueue(capacity = 2, dispatcher = Dispatchers.Unconfined) { id, text, rate, pitch ->
            PrefetchItem(sentenceId = id, text = text, rate = rate, pitch = pitch)
        }
        queue.start()

        queue.prefetchNext(listOf(
            Triple(1, "First sentence.", 1.0f),
            Triple(2, "Second sentence.", 1.0f),
            Triple(3, "Third sentence.", 1.0f)
        ))

        assertTrue(queue.size <= 2)
        val item1 = queue.poll(50L)
        assertNotNull(item1)
        assertEquals(1, item1?.sentenceId)

        val item2 = queue.poll(50L)
        assertNotNull(item2)
        assertEquals(2, item2?.sentenceId)

        queue.stop()
    }

    @Test
    fun testFlushClearsQueue() = runTest {
        val queue = TtsPrefetchQueue(capacity = 3, dispatcher = Dispatchers.Unconfined) { id, text, rate, pitch ->
            PrefetchItem(sentenceId = id, text = text, rate = rate, pitch = pitch)
        }
        queue.start()

        queue.prefetchNext(listOf(
            Triple(10, "Sentence ten.", 1.0f)
        ))

        assertEquals(1, queue.size)
        queue.flush()
        assertEquals(0, queue.size)

        queue.stop()
    }

    @Test
    fun testParagraphChunkGroupingAndOffsets() {
        val sentences = listOf(
            ParsedSentence(0, "First.", "First spoken.", 1),
            ParsedSentence(1, "Second.", "Second spoken.", 1)
        )
        val chunks = groupIntoParagraphChunks(sentences)
        assertTrue(chunks.isNotEmpty())
        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].sentences.size)
    }
}
