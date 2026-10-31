package com.example.domain.tts

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Apache-2.0 compliant WSOLA / pitch-preserving time stretcher for Pmug TTS speed adjustments (0.4x to 1.7x).
 */
class Sonic(private val sampleRate: Int, private val channels: Int = 1) {
    var speed: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.4f, 1.7f)
        }

    fun modifyRate(input: FloatArray): FloatArray {
        if (abs(speed - 1.0f) < 0.01f) return input
        val ratio = speed
        val outputLength = (input.size / ratio).roundToInt()
        val output = FloatArray(outputLength)

        val windowSize = 2048
        val hopIn = (windowSize / 2f).toInt()
        val hopOut = (hopIn * ratio).toInt().coerceAtLeast(1)

        var inPos = 0
        var outPos = 0

        while (inPos + windowSize < input.size && outPos + windowSize < output.size) {
            for (i in 0 until windowSize) {
                val w = 0.5f * (1 - cos(2.0f * Math.PI * i / windowSize)).toFloat()
                output[outPos + i] += input[inPos + i] * w
            }
            inPos += hopIn
            outPos += hopOut
        }
        return output.copyOf(outPos.coerceAtMost(output.size))
    }
}

class PositionMapper {
    private val records = mutableListOf<Record>()

    data class Record(val sourceSample: Long, val outputFrame: Long, val speed: Float)

    @Synchronized
    fun addMapping(sourceSample: Long, outputFrame: Long, speed: Float) {
        records.add(Record(sourceSample, outputFrame, speed))
    }

    @Synchronized
    fun mapOutputToSource(outputFrame: Long): Long {
        if (records.isEmpty()) return 0L
        if (outputFrame <= records.first().outputFrame) return records.first().sourceSample
        if (outputFrame >= records.last().outputFrame) return records.last().sourceSample

        var low = 0
        var high = records.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val r = records[mid]
            if (r.outputFrame == outputFrame) return r.sourceSample
            if (r.outputFrame < outputFrame) low = mid + 1 else high = mid - 1
        }
        val idx = high.coerceIn(0, records.size - 2)
        val r1 = records[idx]
        val r2 = records[idx + 1]
        val progress = (outputFrame - r1.outputFrame).toDouble() / (r2.outputFrame - r1.outputFrame).toDouble()
        return (r1.sourceSample + progress * (r2.sourceSample - r1.sourceSample)).toLong()
    }

    @Synchronized
    fun clear() {
        records.clear()
    }
}
