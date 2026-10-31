package com.example.domain.tts

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class PrefetchItem(
    val sentenceId: Int,
    val text: String,
    val rate: Float,
    val pitch: Float,
    val synthesizedData: Any? = null,
    val synthesisTimeMs: Long = 0L,
    val audioDurationMs: Long = 0L
)

class TtsPrefetchQueue(
    private val capacity: Int = 3,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val synthesizeFunc: suspend (Int, String, Float, Float) -> PrefetchItem?
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val queue = ArrayBlockingQueue<PrefetchItem>(capacity)
    private val activeJobs = mutableMapOf<Int, Job>()
    private val isRunning = AtomicInteger(0)

    companion object {
        private const val TAG = "PmugTts"
    }

    fun start() {
        if (isRunning.compareAndSet(0, 1)) {
            Log.i(TAG, "TtsPrefetchQueue started with capacity $capacity")
        }
    }

    @Synchronized
    fun flush() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        queue.clear()
        Log.i(TAG, "TtsPrefetchQueue flushed and pending synthesis cancelled")
    }

    fun stop() {
        flush()
        isRunning.set(0)
        scope.cancel()
        Log.i(TAG, "TtsPrefetchQueue stopped")
    }

    val size: Int get() = queue.size

    fun peek(): PrefetchItem? = queue.peek()

    fun poll(timeoutMs: Long = 100L): PrefetchItem? {
        return try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
    }

    fun prefetchNext(upcomingSentences: List<Triple<Int, String, Float>>) {
        if (isRunning.get() == 0) return
        scope.launch {
            for ((id, text, rate) in upcomingSentences) {
                if (!isActive || queue.size >= capacity || activeJobs.containsKey(id) || queue.any { it.sentenceId == id }) {
                    continue
                }
                val job = launch {
                    val start = System.currentTimeMillis()
                    val item = try {
                        synthesizeFunc(id, text, rate, 1.0f)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Prefetch synthesis failed for sentence $id: ${e.message}", e)
                        null
                    }
                    val synthTime = System.currentTimeMillis() - start
                    if (item != null) {
                        val finalItem = item.copy(synthesisTimeMs = synthTime)
                        if (!queue.offer(finalItem)) {
                            Log.w(TAG, "TtsPrefetchQueue full, dropped prefetch for sentence $id")
                        } else {
                            Log.i(TAG, "Prefetched sentence $id in ${synthTime}ms (queue depth: ${queue.size})")
                        }
                    }
                    activeJobs.remove(id)
                }
                activeJobs[id] = job
            }
        }
    }
}
