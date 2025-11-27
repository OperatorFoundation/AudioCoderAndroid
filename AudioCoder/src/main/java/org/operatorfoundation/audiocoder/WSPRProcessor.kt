package org.operatorfoundation.audiocoder

import org.operatorfoundation.audiocoder.WSPRBandplan.getDefaultFrequency
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE
import org.operatorfoundation.audiocoder.WSPRConstants.SYMBOLS_PER_MESSAGE
import timber.log.Timber
import kotlin.math.pow

/**
 * High-level WSPR audio processing with buffering and multiple decode strategies.
 *
 * This processor manages audio buffering and provides two decoding options:
 * 1. Sliding Window: Overlapping windows to catch transmissions at any time
 * 2. Time-Aligned: Windows aligned with WSPR 2-minute transmission schedule
 */
class WSPRProcessor
{
    companion object
    {
        // WSPR Audio Format Constants
        private const val BYTES_PER_SHORT = 2
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8

        // WSPR Protocol Constants
        private const val WSPR_SYMBOL_DURATION_SECONDS = 0.683f //Each symbol is ~0.683 seconds
        private const val WSPR_TRANSMISSION_DURATION_SECONDS = WSPR_SYMBOL_DURATION_SECONDS * SYMBOLS_PER_MESSAGE // ~110.6 seconds
        private const val WSPR_CYCLE_DURATION_SECONDS = 120f // WSPR transmits every 2 minutes

        // Buffer Timing Constants
        private const val REQUIRED_DECODE_SECONDS = 114f // Minimum for decode attempt
        private const val RECOMMENDED_BUFFER_SECONDS = 180f // Recommended buffer for reliable decode (3 minutes for overlap)

        // Decode Window Strategy Constants
        private const val SLIDING_WINDOW_STEP_SECONDS = 30f // Step between sliding windows
        private const val MAX_DECODE_WINDOWS = 6 // Limit processing to prevent excessive CPU usage

        // Buffer Size Calculations
        private const val MAXIMUM_BUFFER_SAMPLES = (WSPR_REQUIRED_SAMPLE_RATE * RECOMMENDED_BUFFER_SECONDS).toInt()
        private const val REQUIRED_DECODE_SAMPLES = (WSPR_REQUIRED_SAMPLE_RATE * REQUIRED_DECODE_SECONDS).toInt() // Native decoder limit
    }

    val audioBuffer = mutableListOf<Short>()

    /**
     * Adds audio samples to the WSPR processing buffer.
     * Automatically manages buffer size to prevent memory issues.
     */
    fun addSamples(samples: ShortArray)
    {
        audioBuffer.addAll(samples.toList())

        // Maintain buffer size within limits using bulk removal
        if (audioBuffer.size > MAXIMUM_BUFFER_SAMPLES)
        {
            val samplesToRemove = audioBuffer.size - MAXIMUM_BUFFER_SAMPLES

            repeat(samplesToRemove)
            {
                audioBuffer.removeAt(0)
            }
        }
    }

    /**
     * Gets the current buffer duration in seconds.
     */
    fun getBufferDurationSeconds(): Float = audioBuffer.size.toFloat() / WSPR_REQUIRED_SAMPLE_RATE

    /**
     * Checks if buffer has enough data for a WSPR decode attempt.
     */
    fun isReadyForDecode(): Boolean = audioBuffer.size >= REQUIRED_DECODE_SAMPLES

    fun getRequiredDecodeSamples(): Int
    {
        return REQUIRED_DECODE_SAMPLES
    }

    /**
     * Decodes WSPR from buffered audio data using the specified strategy.
     *
     * @param dialFrequencyMHz Radio dial frequency in MHz
     * @param useLowerSideband Whether to use LSB mode (inverts symbol order)
     * @param useTimeAlignment Use time-aligned windows (true) or sliding windows (false)
     * @return Array of decoded WSPR messages, or null if insufficient data
     */
    fun decodeBufferedWSPR(
        dialFrequencyMHz: Double = getDefaultFrequency(),
        useLowerSideband: Boolean = false,
        useTimeAlignment: Boolean = false
    ): Array<WSPRMessage>?
    {
        if (!isReadyForDecode()) return null

        val decodeWindows = if (useTimeAlignment)
        {
            generateTimeAlignedWindows()
        }
        else
        {
            generateSlidingWindows()
        }

        return processDecodeWindows(decodeWindows, dialFrequencyMHz, useLowerSideband)
    }

    /**
     * Clears the audio buffer.
     */
    fun clearBuffer() {
        audioBuffer.clear()
    }

    // Public constants for external use
    fun getRecommendedBufferSeconds(): Float = RECOMMENDED_BUFFER_SECONDS
    fun getMinimumBufferSeconds(): Float = REQUIRED_DECODE_SECONDS
    fun getWSPRTransmissionSeconds(): Float = WSPR_TRANSMISSION_DURATION_SECONDS
    fun getBufferOverlapSeconds(): Float = RECOMMENDED_BUFFER_SECONDS - WSPR_TRANSMISSION_DURATION_SECONDS

    // ========== Private Implementation ==========

    /**
     * Represents a window of audio samples for WSPR decoding.
     */
    private data class DecodeWindow(
        val startIndex: Int,
        val endIndex: Int,
        val description: String // For debugging/logging
    )

    /**
     * Generates overlapping sliding windows for WSPR decoding.
     * This attempts to catch WSPR transmissions that start at any time.
     */
    private fun generateSlidingWindows(): List<DecodeWindow>
    {
        // Check if we have enough audio
        if (audioBuffer.size < REQUIRED_DECODE_SAMPLES)
        {
            Timber.w("Insufficient audio for decode: ${audioBuffer.size} samples < ${REQUIRED_DECODE_SAMPLES} required")
            return emptyList()
        }

        // Single window if buffer fits exactly within decoder limits
        if (audioBuffer.size <= REQUIRED_DECODE_SAMPLES)
        {
            return listOf(DecodeWindow(0, audioBuffer.size, "Full buffer"))
        }

        val windows = mutableListOf<DecodeWindow>()
        val stepSamples = (WSPR_REQUIRED_SAMPLE_RATE * SLIDING_WINDOW_STEP_SECONDS).toInt()
        val maxWindows = minOf(MAX_DECODE_WINDOWS, (audioBuffer.size - REQUIRED_DECODE_SAMPLES) / stepSamples + 1)

        for (windowIndex in 0 until maxWindows)
        {
            val startIndex = windowIndex * stepSamples
            val endIndex = startIndex + REQUIRED_DECODE_SAMPLES

            if (endIndex <= audioBuffer.size)
            {
                windows.add(DecodeWindow(
                    startIndex,
                    endIndex,
                    "Sliding window ${windowIndex + 1} (${startIndex / WSPR_REQUIRED_SAMPLE_RATE}s-${endIndex / WSPR_REQUIRED_SAMPLE_RATE}s)"
                ))
            }
        }

        return windows
    }

    /**
     * Generates time-aligned windows based on WSPR 2-minute transmission schedule.
     * This aligns with expected WSPR timing for decoding.
     */
    private fun generateTimeAlignedWindows(): List<DecodeWindow>
    {
        // Check if we have enough audio for at least one decode
        if (audioBuffer.size < REQUIRED_DECODE_SAMPLES)
        {
            Timber.w("Insufficient audio for time-aligned decode: ${audioBuffer.size} samples < ${REQUIRED_DECODE_SAMPLES} required")
            return emptyList()
        }

        val windows = mutableListOf<DecodeWindow>()

        // Create a single window from the start of the buffer
        // This is already time-aligned because collection starts at even_minute + 2s
        val endIndex = minOf(REQUIRED_DECODE_SAMPLES, audioBuffer.size)

        windows.add(DecodeWindow(
            startIndex = 0,
            endIndex = endIndex,
            description = "Time-aligned window (0s-${endIndex / WSPR_REQUIRED_SAMPLE_RATE}s)"
        ))

        Timber.d("Generated time-aligned window: 0-${endIndex} samples (${endIndex / WSPR_REQUIRED_SAMPLE_RATE}s)")

        return windows
    }

    /**
     * Processes multiple decode windows and combines results.
     * Handles the actual native decoder calls and deduplication.
     */
    private fun processDecodeWindows(
        windows: List<DecodeWindow>,
        dialFrequencyMHz: Double,
        useLowerSideband: Boolean
    ): Array<WSPRMessage>?
    {
        val allMessages = mutableListOf<WSPRMessage>()

        Timber.d("=== Starting decode with ${windows.size} windows ===")
        Timber.d("Buffer has ${audioBuffer.size} samples (${getBufferDurationSeconds()}s)")
        Timber.d("Required: ${REQUIRED_DECODE_SAMPLES} samples (${REQUIRED_DECODE_SECONDS}s)")

        for (window in windows)
        {
            try
            {
                val windowSamples = audioBuffer.subList(window.startIndex, window.endIndex).toShortArray()
                val audioBytes = convertShortsToBytes(windowSamples)

                Timber.d("Calling native decoder:")
                Timber.d("  Window: ${window.description}")
                Timber.d("  Samples: ${windowSamples.size} (${windowSamples.size / WSPR_REQUIRED_SAMPLE_RATE}s)")
                Timber.d("  Bytes: ${audioBytes.size}")
                Timber.d("  Frequency: ${dialFrequencyMHz} MHz")
                Timber.d("  LSB: $useLowerSideband")

                val audioQuality = analyzeAudioQuality(windowSamples)
                Timber.d("  Audio quality: $audioQuality")

                val messages = CJarInterface.WSPRDecodeFromPcm(audioBytes, dialFrequencyMHz, useLowerSideband)

                Timber.d("Native decoder returned: ${messages?.size ?: "null"} messages")

                messages?.let {
                    allMessages.addAll(it.toList())
                    Timber.d("Decoded ${it.size} messages from ${window.description}")
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Failed to decode ${window.description}")
            }
        }

        Timber.d("=== Decode complete: ${allMessages.size} total messages ===")

        return if (allMessages.isNotEmpty())
        {
            removeDuplicateMessages(allMessages).toTypedArray()
        }
        else { return null }
    }

    /**
     * Removes duplicate WSPR messages based on content.
     */
    private fun removeDuplicateMessages(messages: List<WSPRMessage>): List<WSPRMessage>
    {
        return messages.distinctBy { message ->
            // Create unique key from message content
            val callsign = message.call ?: "UNKNOWN"
            val location = message.loc ?: "UNKNOWN"
            val power = message.power
            val snr = String.format("%.1f", message.getSNR()) // Round SNR to 1 decimal

            "${callsign}_${location}_${power}_${snr}"
        }
    }

    private fun analyzeAudioQuality(samples: ShortArray): String
    {
        val rms = kotlin.math.sqrt(samples.map { (it.toFloat() / Short.MAX_VALUE).pow(2) }.average())
        val peakSample = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val peak = peakSample.toFloat() / Short.MAX_VALUE
        return "RMS=%.3f, Peak=%.3f".format(rms, peak)
    }

    /**
     * Converts 16-bit audio samples to byte array for native decoder.
     * Uses little-endian byte order as expected by the WSPR decoder.
     */
    private fun convertShortsToBytes(samples: ShortArray): ByteArray
    {
        val bytes = ByteArray(samples.size * BYTES_PER_SHORT)

        for (sampleIndex in samples.indices)
        {
            val sample = samples[sampleIndex].toInt()
            val byteIndex = sampleIndex * BYTES_PER_SHORT

            // Little Endian Byte order
            bytes[byteIndex] = (sample and BYTE_MASK).toByte()
            bytes[byteIndex + 1] = ((sample shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        }

        return bytes
    }


}