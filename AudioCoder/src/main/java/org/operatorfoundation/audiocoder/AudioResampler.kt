package org.operatorfoundation.audiocoder

import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Linear interpolation audio resampler for WSPR signals.
 *
 * Uses linear interpolation to convert between sample rates sufficient for WSPR's narrow band signals.
 *
 * This resampler maintains continuity between audio chunks by remembering
 * the last sample from the previous chunk for interpolation.
 *
 * Example usage:
 * ```kotlin
 * val resampler = AudioResampler(inputSampleRate = 48000, outputSampleRate = 12000)
 * val resampledAudio = resampler.resample(audioSamples)
 * ```
 */
class AudioResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int
) {
    /**
     * Ratio for sample rate conversion calculation.
     */
    private val resampleRatio = outputSampleRate.toDouble() / inputSampleRate.toDouble()

    /**
     * Last sample from previous chunk, used for interpolation continuity.
     */
    private var lastSample: Short = 0

    /**
     * Statistics for monitoring resampler performance.
     */
    private var totalInputSamples = 0L
    private var totalOutputSamples = 0L

    init {
        require(inputSampleRate > 0) { "Input sample rate must be positive: $inputSampleRate" }
        require(outputSampleRate > 0) { "Output sample rate must be positive: $outputSampleRate" }

        Timber.d("AudioResampler initialized: ${inputSampleRate}Hz -> ${outputSampleRate}Hz (ratio: %.3f)".format(resampleRatio))
    }

    /**
     * Resamples input audio to the target sample rate using linear interpolation.
     *
     * @param inputSamples Raw 16-bit audio samples at the input sample rate
     * @return Resampled audio at the output sample rate
     */
    fun resample(inputSamples: ShortArray): ShortArray
    {
        if (inputSamples.isEmpty()) {
            Timber.v("Empty input samples, returning empty array")
            return shortArrayOf()
        }

        // No resampling needed if rates match
        if (inputSampleRate == outputSampleRate)
        {
            totalInputSamples += inputSamples.size
            totalOutputSamples += inputSamples.size
            return inputSamples
        }

        // Calculate output length
        val outputLength = calculateOutputSize(inputSamples.size)
        val outputSamples = ShortArray(outputLength)

        // Perform linear interpolation resampling
        for (i in outputSamples.indices)
        {
            val inputIndex = i / resampleRatio
            val inputIndexInt = inputIndex.toInt()
            val fraction = inputIndex - inputIndexInt

            // Get the two samples to interpolate between
            val sample1 = getSampleForInterpolation(inputSamples, inputIndexInt)
            val sample2 = getSampleForInterpolation(inputSamples, inputIndexInt + 1)

            // Linear interpolation: sample1 + fraction * (sample2 - sample1)
            val interpolated = sample1 + (fraction * (sample2 - sample1))

            // Clamp to 16-bit range and store
            outputSamples[i] = interpolated.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        // Update statistics
        totalInputSamples += inputSamples.size
        totalOutputSamples += outputLength

        // Remember last sample for next chunk continuity
        if (inputSamples.isNotEmpty()) {
            lastSample = inputSamples.last()
        }

        return outputSamples
    }

    /**
     * Calculates the expected output size for a given input size.
     * Useful for pre-allocating buffers or estimating processing requirements.
     *
     * @param inputSize Number of input samples
     * @return Expected number of output samples
     */
    fun calculateOutputSize(inputSize: Int): Int
    {
        return (inputSize * resampleRatio).roundToInt()
    }

    /**
     * Resets the resampler state, clearing interpolation continuity.
     * Call this when starting a new audio stream or after a discontinuity.
     */
    fun reset()
    {
        lastSample = 0
        totalInputSamples = 0L
        totalOutputSamples = 0L
        Timber.v("AudioResampler state reset")
    }

    /**
     * Gets statistics about resampler performance.
     *
     * @return String with resampler statistics
     */
    fun getStatistics(): String
    {
        val compressionRatio = if (totalInputSamples > 0)
        {
            totalOutputSamples.toDouble() / totalInputSamples.toDouble()
        }
        else
        {
            0.0
        }

        return "AudioResampler Stats: ${totalInputSamples} -> ${totalOutputSamples} samples " +
                "(ratio: %.3f, expected: %.3f)".format(compressionRatio, resampleRatio)
    }

    /**
     * Gets a sample for interpolation, handling edge cases.
     *
     * @param samples Input sample array
     * @param index Requested sample index
     * @return Sample value for interpolation
     */
    private fun getSampleForInterpolation(samples: ShortArray, index: Int): Short
    {
        return when {
            index < 0 -> lastSample  // Use last sample from previous chunk
            index >= samples.size -> samples.last()  // Use last available sample
            else -> samples[index]  // Normal case
        }
    }
}