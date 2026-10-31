package com.example.domain.tts

import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

data class PlaybackUnit(
    val sentenceId: Int,
    val text: String,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val spokenText: String = text
)

data class SynthesizedAudio(val samples: FloatArray, val sampleRate: Int, val rtf: Float = 0f)

data class WordSpan(val startChar: Int, val endChar: Int, val startFrame: Int, val endFrame: Int)

data class PlaybackPosition(
    val sentenceId: Int = -1,
    val pageIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val wordIndex: Int = 0,
    val sourcePosition: Int = 0,
    val sourceFrame: Long = 0,
    val playing: Boolean = false
)

data class PlaybackStats(
    val state: String,
    val bufferedAudioMs: Long,
    val underruns: Int,
    val synthesisRtf: List<Float>,
    val averageRtf: Float,
    val numThreads: Int,
    val synthesisThread: String,
    val writerThread: String,
    val lastStartLatencyMs: Long,
    val highlightLagMs: Long,
    val speed: Float,
    val audioTrackUnderruns: Int
)

fun interface PlaybackSynthesizer {
    /** Called serially on the session's synthesis worker. */
    fun synthesize(unit: PlaybackUnit, speed: Float, voice: String): SynthesizedAudio
}

interface PlaybackAudioSink : Closeable {
    val sampleRate: Int
    fun play()
    fun pause()
    fun stopAndFlush()
    fun write(samples: FloatArray, offset: Int, count: Int): Int
    /** Monotonic count of output frames actually presented to the audio device. */
    fun playedFrames(): Long
    fun underrunCount(): Int = 0
}

/** Long lived PCM AudioTrack. Timestamp is preferred; playback-head is the fallback. */
class AudioTrackPlaybackSink(override val sampleRate: Int = 22_050) : PlaybackAudioSink {
    private val track: AudioTrack
    private val timestamp = AudioTimestamp()
    private var lastHead = 0L
    private var headWraps = 0L
    private var lastPlayedFrame = 0L
    private var latencyFrames = 0L
    private var framesWritten = 0L

    init {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum * 2, sampleRate / 2 * 4))
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        latencyFrames = runCatching {
            (AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int).toLong() * sampleRate / 1000L
        }.getOrDefault(0L)
    }

    override fun play() { track.play() }
    override fun pause() { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause() }
    override fun stopAndFlush() {
        val playState = track.playState
        if (playState == AudioTrack.PLAYSTATE_PLAYING || playState == AudioTrack.PLAYSTATE_PAUSED) {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            track.flush()
            track.stop()
            track.flush()
        }
        synchronized(this) { lastPlayedFrame = 0L; lastHead = 0L; headWraps = 0L; framesWritten = 0L }
    }
    override fun write(samples: FloatArray, offset: Int, count: Int): Int {
        val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
        if (written > 0) synchronized(this) { framesWritten += written }
        return written
    }

    @Synchronized override fun playedFrames(): Long {
        val candidate = if (track.playState == AudioTrack.PLAYSTATE_PLAYING && track.getTimestamp(timestamp)) {
            val elapsedNanos = (System.nanoTime() - timestamp.nanoTime).coerceAtLeast(0L)
            timestamp.framePosition + elapsedNanos * sampleRate / 1_000_000_000L
        } else {
        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head < lastHead && lastHead - head > 0x7fff_ffffL) headWraps++
        lastHead = head
            (head + (headWraps shl 32) - latencyFrames).coerceAtLeast(0L)
        }
        lastPlayedFrame = max(lastPlayedFrame, candidate.coerceAtMost(framesWritten))
        return lastPlayedFrame
    }

    override fun underrunCount(): Int = if (android.os.Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
    override fun close() { track.release() }
}

/**
 * Audio-owned reader loop. The UI can submit extracted plan units, but cannot advance units.
 * Synthesis and writing run on separate single-thread workers; output position comes from sink
 * timestamps/head position, never from a duration timer.
 */
class PlaybackSession(
    private val synthesizer: PlaybackSynthesizer,
    private val sink: PlaybackAudioSink,
    private val initialStartBufferMs: Long = 2_500L,
    private val maxAheadMs: Long = 15_000L,
    private val maxQueuedBytes: Long = 40L * 1024 * 1024,
    private val sentenceSilenceMs: Long = SENTENCE_PAUSE_MS,
    private val paragraphSilenceMs: Long = PARAGRAPH_PAUSE_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) : Closeable {
    companion object {
        private const val TAG = "PmugTts"
        private const val CHUNK_CAPACITY = 24
        const val SENTENCE_PAUSE_MS = 150L
        const val PARAGRAPH_PAUSE_MS = 350L
    }

    private fun safeLogI(tag: String, msg: String) {
        runCatching { Log.i(tag, msg) }.onFailure { println("$tag: $msg") }
    }
    private fun safeLogW(tag: String, msg: String) {
        runCatching { Log.w(tag, msg) }.onFailure { println("$tag: $msg") }
    }
    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }.onFailure { println("$tag: $msg ${tr?.message}") }
    }

    private data class ReadyUnit(val unit: PlaybackUnit, val audio: SynthesizedAudio, val words: List<WordSpan>)
    private data class WrittenUnit(
        val unit: PlaybackUnit,
        val words: List<WordSpan>,
        val startFrame: Long,
        val endFrame: Long,
        var startLogged: Boolean = false,
        var endLogged: Boolean = false,
        var highlightStartLogged: Boolean = false
    )

    private val lock = Object()
    private val plan = ArrayList<PlaybackUnit>()
    private var planOwner: String? = null
    private val ready = ArrayBlockingQueue<ReadyUnit>(CHUNK_CAPACITY)
    private val written = ArrayList<WrittenUnit>()
    private val synthesizerWorker = singleWorker("PmugSynthesis")
    private val audioWriterWorker = singleWorker("PmugAudioWriter")
    private var synthesisTask: Future<*>? = null
    private var writerTask: Future<*>? = null
    private var nextIndex = 0
    private var generation = 0L
    private var planComplete = false
    private var synthesisInFlight = false
    private var playing = false
    private var closed = false
    private var speed = 1f
    private var voice = TtsProviderMode.PIPER_OFFLINE.voiceId
    private var queuedFrames = 0L
    private var queuedBytes = 0L
    private var pendingWriterFrames = 0L
    private var pendingWriterBytes = 0L
    private var lastParagraph = -1
    private var totalWrittenFrames = 0L
    private var underruns = 0
    private var currentStartBufferMs = initialStartBufferMs.coerceIn(2_500L, 12_000L)
    private var lastUnderrunAt = 0L
    private var lastStartLatencyMs = 0L
    private var playRequestedAt = 0L
    private var synthesisThreadName = "not started"
    private var writerThreadName = "not started"
    private val lastRtf = ArrayDeque<Float>(5)
    private val lastPosition = AtomicReference(PlaybackPosition())
    private val state = AtomicReference("stopped")
    private val bufferStartReady = AtomicBoolean(false)
    private var highlightLagMs = 0L
    private var lastAudioEndAtMs = 0L

    fun appendUnits(units: List<PlaybackUnit>, complete: Boolean = false, owner: String? = null) {
        synchronized(lock) {
            if (closed) return
            if (owner != null && owner != planOwner) {
                playing = false
                generation++
                synthesisTask?.cancel(true); writerTask?.cancel(true)
                ready.clear(); written.clear(); plan.clear()
                queuedFrames = 0L; queuedBytes = 0L; pendingWriterFrames = 0L; pendingWriterBytes = 0L
                totalWrittenFrames = 0L; nextIndex = 0; lastParagraph = -1; synthesisInFlight = false
                planComplete = false; planOwner = owner; bufferStartReady.set(false)
                sink.stopAndFlush(); lastPosition.set(PlaybackPosition()); state.set("stopped")
            }
            val existing = plan.mapTo(HashSet()) { it.sentenceId }
            units.forEach { if (existing.add(it.sentenceId)) plan.add(it) }
            plan.sortBy { it.sentenceId }
            planComplete = planComplete || complete
            lock.notifyAll()
        }
    }

    fun play(fromSentenceId: Int) {
        synchronized(lock) {
            if (closed) return
            if (nowMs() - lastUnderrunAt >= 5 * 60_000L && currentStartBufferMs > initialStartBufferMs) {
                currentStartBufferMs = (currentStartBufferMs - 500L).coerceAtLeast(initialStartBufferMs)
                safeLogI(TAG, "Start buffer reduced to ${currentStartBufferMs}ms after clean playback")
            }
            resetLocked(fromSentenceId)
            playing = true
            state.set("buffering")
            playRequestedAt = nowMs()
            lastStartLatencyMs = 0L
            startWorkersLocked(generation)
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!playing || closed) return
            playing = false
            state.set("paused")
            sink.pause()
            lock.notifyAll()
        }
    }

    fun resume() {
        synchronized(lock) {
            if (closed || playing) return
            playing = true
            state.set(if (bufferStartReady.get()) "playing" else "buffering")
            if (bufferStartReady.get()) sink.play()
            startWorkersLocked(generation)
            lock.notifyAll()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            playing = false
            state.set("stopped")
            generation++
            synthesisTask?.cancel(true); synthesisTask = null
            writerTask?.cancel(true); writerTask = null
            ready.clear(); written.clear()
            queuedFrames = 0L; queuedBytes = 0L; pendingWriterFrames = 0L; pendingWriterBytes = 0L; nextIndex = 0; lastParagraph = -1; synthesisInFlight = false
            bufferStartReady.set(false)
            sink.stopAndFlush()
            lastPosition.set(PlaybackPosition())
            lock.notifyAll()
        }
    }

    fun seekTo(sentenceId: Int) = play(sentenceId)

    fun setSpeed(newSpeed: Float) {
        val current = position()
        val sentenceId = current.sentenceId.takeIf { it >= 0 } ?: lastPosition.get().sentenceId
        val resumeAfterReset = synchronized(lock) {
            speed = newSpeed.coerceIn(0.4f, 1.7f)
            playing
        }
        if (sentenceId >= 0) {
            if (resumeAfterReset) seekTo(sentenceId)
            else synchronized(lock) {
                resetLocked(sentenceId)
                state.set("paused")
            }
        }
    }

    fun setVoice(voiceId: String) {
        val current = position()
        synchronized(lock) { voice = voiceId }
        if (current.sentenceId >= 0 && playing) seekTo(current.sentenceId)
    }

    fun isPlaying(): Boolean = synchronized(lock) { playing }
    fun playbackState(): String = state.get()
    fun currentSpeed(): Float = synchronized(lock) { speed }
    fun unitsSnapshot(): List<PlaybackUnit> = synchronized(lock) { plan.toList() }

    /** Queries the device clock. UI should call this once per display frame. */
    fun position(): PlaybackPosition {
        val frame = sink.playedFrames()
        val now = nowMs()
        val segment = synchronized(lock) {
            written.forEach { writtenUnit ->
                if (!writtenUnit.endLogged && frame >= writtenUnit.endFrame) {
                    writtenUnit.endLogged = true
                    val endAt = now - (frame - writtenUnit.endFrame) * 1000L / sink.sampleRate
                    lastAudioEndAtMs = endAt
                    val buffered = (queuedFrames + pendingWriterFrames + (totalWrittenFrames - frame).coerceAtLeast(0L)) * 1000L / sink.sampleRate
                    safeLogI(TAG, "PmugTts: sentenceId=${writtenUnit.unit.sentenceId} audioEndAt=$endAt highlightEndAt=$endAt bufferedAudioMs=$buffered underrunCount=$underruns")
                }
            }
            written.lastOrNull { frame > it.startFrame && frame <= it.endFrame }?.also { active ->
                if (!active.startLogged) {
                    active.startLogged = true
                    val startAt = now - (frame - active.startFrame) * 1000L / sink.sampleRate
                    val gap = if (lastAudioEndAtMs == 0L) 0L else startAt - lastAudioEndAtMs
                    val buffered = (queuedFrames + pendingWriterFrames + (totalWrittenFrames - frame).coerceAtLeast(0L)) * 1000L / sink.sampleRate
                    safeLogI(TAG, "PmugTts: sentenceId=${active.unit.sentenceId} audioStartAt=$startAt gapMs=$gap bufferedAudioMs=$buffered underrunCount=$underruns")
                    if (lastStartLatencyMs == 0L && playRequestedAt > 0L) {
                        lastStartLatencyMs = now - playRequestedAt
                        safeLogI(TAG, "PmugTts: tapToFirstSoundMs=$lastStartLatencyMs")
                    }
                }
            }
        }
            ?: return PlaybackPosition(playing = isPlaying())
        val localFrame = (frame - segment.startFrame - 1L).coerceIn(0L, segment.endFrame - segment.startFrame - 1L)
        val word = segment.words.firstOrNull { localFrame >= it.startFrame && localFrame < it.endFrame }
        if (word != null && word.startFrame == 0 && synchronized(lock) { !segment.highlightStartLogged }) {
            synchronized(lock) { segment.highlightStartLogged = true }
            val highlightAt = now - localFrame * 1000L / sink.sampleRate
            safeLogI(TAG, "PmugTts: sentenceId=${segment.unit.sentenceId} highlightStartAt=$highlightAt")
        }
        val result = PlaybackPosition(
            sentenceId = segment.unit.sentenceId,
            pageIndex = segment.unit.pageIndex,
            paragraphIndex = segment.unit.paragraphIndex,
            wordIndex = word?.let { segment.words.indexOf(it) } ?: 0,
            sourcePosition = word?.startChar ?: 0,
            sourceFrame = localFrame,
            playing = isPlaying()
        )
        lastPosition.set(result)
        return result
    }

    fun updateHighlightLag(lagMs: Long) { synchronized(lock) { highlightLagMs = lagMs } }

    fun stats(): PlaybackStats = synchronized(lock) {
        val values = lastRtf.toList()
        PlaybackStats(
            state = state.get(),
            bufferedAudioMs = (queuedFrames + pendingWriterFrames + (totalWrittenFrames - sink.playedFrames()).coerceAtLeast(0L)) * 1000 / sink.sampleRate,
            underruns = underruns,
            synthesisRtf = values,
            averageRtf = if (values.isEmpty()) 0f else values.average().toFloat(),
            numThreads = Thread.activeCount(),
            synthesisThread = synthesisThreadName,
            writerThread = writerThreadName,
            lastStartLatencyMs = lastStartLatencyMs,
            highlightLagMs = highlightLagMs,
            speed = speed,
            audioTrackUnderruns = sink.underrunCount()
        )
    }

    private fun resetLocked(sentenceId: Int) {
        generation++
        synthesisTask?.cancel(true); writerTask?.cancel(true)
        ready.clear(); written.clear()
        queuedFrames = 0L; queuedBytes = 0L; pendingWriterFrames = 0L; pendingWriterBytes = 0L; totalWrittenFrames = 0L; lastParagraph = -1; synthesisInFlight = false
        nextIndex = plan.indexOfFirst { it.sentenceId == sentenceId }.coerceAtLeast(0)
        bufferStartReady.set(false)
        sink.stopAndFlush()
        lastPosition.set(PlaybackPosition())
    }

    private fun startWorkersLocked(expectedGeneration: Long) {
        if (synthesisTask?.isDone != false) synthesisTask = synthesizerWorker.submit { synthesisLoop(expectedGeneration) }
        if (writerTask?.isDone != false) writerTask = audioWriterWorker.submit { writerLoop(expectedGeneration) }
    }

    private fun synthesisLoop(expectedGeneration: Long) {
        synthesisThreadName = Thread.currentThread().name
        while (isCurrent(expectedGeneration)) {
            val unit: PlaybackUnit
            val localSpeed: Float
            val localVoice: String
            synchronized(lock) {
                while (isCurrentLocked(expectedGeneration) && ((queuedFrames + pendingWriterFrames + (totalWrittenFrames - sink.playedFrames()).coerceAtLeast(0L)) * 1000 / sink.sampleRate >= maxAheadMs || queuedBytes + pendingWriterBytes >= maxQueuedBytes || nextIndex >= plan.size && !planComplete)) lock.wait(50L)
                if (!isCurrentLocked(expectedGeneration) || nextIndex >= plan.size) return
                unit = plan[nextIndex++]
                synthesisInFlight = true
                localSpeed = speed
                localVoice = voice
            }
            val start = nowMs()
            try {
                val synthStartMs = nowMs()
                val audio = synthesizer.synthesize(unit, localSpeed, localVoice)
                val synthMs = nowMs() - synthStartMs
                if (expectedGeneration != synchronized(lock) { generation }) return
                if (audio.samples.isEmpty()) throw IllegalStateException("Piper produced no audio for sentence ${unit.sentenceId}")
                val frames = audio.samples.size.toLong()
                val words = WordWeights.wordSpans(unit.text, audio.samples.size)
                val readyUnit = ReadyUnit(unit, audio, words)
                synchronized(lock) {
                    while (isCurrentLocked(expectedGeneration) && ((queuedFrames + pendingWriterFrames + (totalWrittenFrames - sink.playedFrames()).coerceAtLeast(0L) + frames) * 1000 / sink.sampleRate > maxAheadMs || queuedBytes + pendingWriterBytes + audio.samples.size * 4L > maxQueuedBytes)) lock.wait(50L)
                    if (!isCurrentLocked(expectedGeneration)) return
                    queuedFrames += frames; queuedBytes += audio.samples.size * 4L
                    synthesisInFlight = false
                    lastRtf.addLast(audio.rtf.takeIf { it > 0f } ?: ((nowMs() - start).toFloat() / (frames * 1000f / sink.sampleRate).coerceAtLeast(1f)))
                    safeLogI(TAG, "PmugTts: sentenceId=${unit.sentenceId} synthMs=$synthMs audioMs=${frames * 1000L / sink.sampleRate} rtf=${audio.rtf}")
                    while (lastRtf.size > 5) lastRtf.removeFirst()
                    lock.notifyAll()
                }
                ready.put(readyUnit)
            } catch (interrupted: InterruptedException) { return }
            catch (failure: Throwable) {
                safeLogE(TAG, "Synthesis failed sentence=${unit.sentenceId}", failure)
                synchronized(lock) {
                    if (generation == expectedGeneration) {
                        synthesisInFlight = false; state.set("error"); playing = false
                    }
                    lock.notifyAll()
                }
                return
            }
        }
    }

    private fun writerLoop(expectedGeneration: Long) {
        writerThreadName = Thread.currentThread().name
        try {
            synchronized(lock) {
                val startLimit = nowMs() + 12_000L
                while (isCurrentLocked(expectedGeneration) && !playing) lock.wait(50L)
                while (isCurrentLocked(expectedGeneration) && queuedFrames * 1000 / sink.sampleRate < currentStartBufferMs && !(planComplete && nextIndex >= plan.size && !synthesisInFlight) && nowMs() < startLimit) lock.wait(25L)
                if (!isCurrentLocked(expectedGeneration)) return
                if (!playing) return
                while (isCurrentLocked(expectedGeneration) && queuedFrames == 0L && (!planComplete || synthesisInFlight)) lock.wait(50L)
                if (!isCurrentLocked(expectedGeneration)) return
                if (!playing) return
                if (queuedFrames == 0L) { state.set("finished"); playing = false; return }
                bufferStartReady.set(true)
                state.set("playing")
                sink.play()
            }
            while (isCurrent(expectedGeneration)) {
                position() // Boundary telemetry uses the audio clock, independent of Compose.
                if (!isPlaying()) { synchronized(lock) { lock.wait(50L) }; continue }
                val item = ready.poll(100L, TimeUnit.MILLISECONDS)
                if (item == null) {
                    synchronized(lock) {
                        if (isCurrentLocked(expectedGeneration) && (nextIndex < plan.size || !planComplete)) {
                            underruns++
                            lastUnderrunAt = nowMs()
                            currentStartBufferMs = (currentStartBufferMs + 1_000L).coerceAtMost(12_000L)
                            safeLogW(TAG, "PmugTts: Underrun count=$underruns startBufferMs=$currentStartBufferMs")
                        } else if (planComplete && nextIndex >= plan.size && !synthesisInFlight && queuedFrames == 0L && pendingWriterFrames == 0L) {
                            while (isCurrentLocked(expectedGeneration) && (!playing || sink.playedFrames() < totalWrittenFrames)) lock.wait(20L)
                            if (!isCurrentLocked(expectedGeneration)) return
                            state.set("finished"); playing = false; return
                        }
                    }
                    continue
                }
                if (lastParagraph >= 0) {
                    val pauseMs = if (lastParagraph != item.unit.paragraphIndex) paragraphSilenceMs else sentenceSilenceMs
                    writeSilence(expectedGeneration, (sink.sampleRate * pauseMs / 1000L).toInt())
                }
                if (!isCurrent(expectedGeneration)) return
                synchronized(lock) {
                    queuedFrames = (queuedFrames - item.audio.samples.size).coerceAtLeast(0L)
                    queuedBytes = (queuedBytes - item.audio.samples.size * 4L).coerceAtLeast(0L)
                    pendingWriterFrames = item.audio.samples.size.toLong()
                    pendingWriterBytes = item.audio.samples.size * 4L
                }
                val startFrame = totalWrittenFrames
                val endFrame = startFrame + item.audio.samples.size
                synchronized(lock) {
                    written += WrittenUnit(item.unit, item.words, startFrame, endFrame)
                }
                var offset = 0
                while (offset < item.audio.samples.size && isCurrent(expectedGeneration)) {
                    synchronized(lock) {
                        while (isCurrentLocked(expectedGeneration) && !playing) lock.wait(50L)
                    }
                    if (!isCurrent(expectedGeneration)) return
                    val count = minOf(2048, item.audio.samples.size - offset)
                    val writtenCount = sink.write(item.audio.samples, offset, count)
                    if (writtenCount <= 0) throw IllegalStateException("Audio sink write failed: $writtenCount")
                    offset += writtenCount
                    synchronized(lock) {
                        totalWrittenFrames = startFrame + offset
                        pendingWriterFrames = (item.audio.samples.size - offset).toLong()
                        pendingWriterBytes = pendingWriterFrames * 4L
                        lock.notifyAll()
                    }
                }
                totalWrittenFrames = endFrame
                synchronized(lock) {
                    pendingWriterFrames = 0L
                    pendingWriterBytes = 0L
                    lastParagraph = item.unit.paragraphIndex
                    lock.notifyAll()
                }
            }
        } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        catch (failure: Throwable) {
            safeLogE(TAG, "Audio writer failed", failure)
            synchronized(lock) {
                if (generation == expectedGeneration) { state.set("error"); playing = false }
            }
        }
    }

    private fun writeSilence(expectedGeneration: Long, frames: Int) {
        var remaining = frames
        val silence = FloatArray(minOf(frames, 4096).coerceAtLeast(1))
        while (remaining > 0 && isCurrent(expectedGeneration)) {
            val count = minOf(remaining, silence.size)
            val writtenCount = sink.write(silence, 0, count)
            if (writtenCount <= 0) throw IllegalStateException("Audio sink silence write failed")
            remaining -= writtenCount
            totalWrittenFrames += writtenCount
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) { isCurrentLocked(expectedGeneration) }
    private fun isCurrentLocked(expectedGeneration: Long): Boolean = !closed && generation == expectedGeneration

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; playing = false; state.set("closed"); generation++
            synthesisTask?.cancel(true); writerTask?.cancel(true)
            ready.clear(); lock.notifyAll()
        }
        synthesizerWorker.shutdownNow(); audioWriterWorker.shutdownNow(); sink.close()
    }

    private fun singleWorker(name: String): ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }

    object WordWeights {
        fun wordSpans(text: String, audioFrames: Int): List<WordSpan> {
            val words = Regex("\\S+").findAll(text).toList()
            if (words.isEmpty() || audioFrames <= 0) return emptyList()
            val weights = words.map { match ->
                val lexical = match.value.count { it.isLetterOrDigit() }.coerceAtLeast(1)
                val syllables = estimateSyllables(match.value).coerceAtLeast(1)
                val punctuation = when (match.value.lastOrNull()) { ',', ':', ';' -> 2.0; else -> 1.0 }
                max(lexical * 0.45, syllables.toDouble()) * punctuation
            }
            val total = weights.sum()
            var consumed = 0.0
            var previousFrame = 0
            return words.mapIndexed { index, match ->
                val start = previousFrame
                consumed += weights[index]
                val end = if (index == words.lastIndex) audioFrames else (audioFrames * consumed / total).roundToInt().coerceIn(start + 1, audioFrames)
                previousFrame = end
                WordSpan(match.range.first, match.range.last + 1, start, end)
            }
        }

        private fun estimateSyllables(word: String): Int {
            val cleaned = word.lowercase().filter(Char::isLetter)
            if (cleaned.length <= 3) return 1
            val groups = Regex("[aeiouy]+") .findAll(cleaned).count()
            return (groups - if (cleaned.endsWith("e")) 1 else 0).coerceAtLeast(1)
        }
    }
}
