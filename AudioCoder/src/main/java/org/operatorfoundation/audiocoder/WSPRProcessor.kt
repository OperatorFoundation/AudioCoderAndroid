package org.operatorfoundation.audiocoder

import org.operatorfoundation.audiocoder.WSPRBandplan.getDefaultFrequency
import org.operatorfoundation.audiocoder.WSPRConstants.SAMPLE_RATE_HZ
import org.operatorfoundation.audiocoder.WSPRConstants.SYMBOLS_PER_MESSAGE

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
        private const val MAXIMUM_BUFFER_SAMPLES = (SAMPLE_RATE_HZ * RECOMMENDED_BUFFER_SECONDS).toInt()
        private const val REQUIRED_DECODE_SAMPLES = (SAMPLE_RATE_HZ * REQUIRED_DECODE_SECONDS).toInt() // Native decoder limit
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
    fun getBufferDurationSeconds(): Float = audioBuffer.size.toFloat() / SAMPLE_RATE_HZ

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
        // Single window if buffer fits within decoder limits
        if (audioBuffer.size <= REQUIRED_DECODE_SAMPLES)
        {
            return listOf(DecodeWindow(0, audioBuffer.size, "Full buffer"))
        }

        val windows = mutableListOf<DecodeWindow>()
        val stepSamples = (SAMPLE_RATE_HZ * SLIDING_WINDOW_STEP_SECONDS).toInt()
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
                    "Sliding window ${windowIndex + 1} (${startIndex / SAMPLE_RATE_HZ}s-${endIndex / SAMPLE_RATE_HZ}s)"
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
        val windows = mutableListOf<DecodeWindow>()
        val cycleSamples = (SAMPLE_RATE_HZ * WSPR_CYCLE_DURATION_SECONDS).toInt()
        val availableCycles = audioBuffer.size / cycleSamples
        val maxCycles = minOf(availableCycles, MAX_DECODE_WINDOWS)

        for (cycle in 0 until maxCycles)
        {
            val startIndex = cycle * cycleSamples
            val endIndex = minOf(startIndex + REQUIRED_DECODE_SAMPLES, audioBuffer.size)

            // Ensure we have enough data to decode
            val windowDurationSeconds = (endIndex - startIndex.toFloat()) / SAMPLE_RATE_HZ
            if (windowDurationSeconds >= WSPR_TRANSMISSION_DURATION_SECONDS)
            {
                windows.add(DecodeWindow(
                    startIndex,
                    endIndex,
                    description = "Time-aligned cycle ${cycle + 1} (${startIndex / SAMPLE_RATE_HZ}s-${endIndex / SAMPLE_RATE_HZ}s)"
                ))
            }
        }

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

        for (window in windows)
        {
            try
            {
                val windowSamples = audioBuffer.subList(window.startIndex, window.endIndex).toShortArray()
                val audioBytes = convertShortsToBytes(windowSamples)

                val messages = CJarInterface.WSPRDecodeFromPcm(audioBytes, dialFrequencyMHz, useLowerSideband)

                messages?.let {
                    allMessages.addAll(it.toList())
                    // Timber.d("Decoded ${it.size} messages from ${window.description}")
                }
            }
            catch (exception: Exception)
            {
                // Log decode failure but continue with other windows
                // Timber.w(exception, "Failed to decode ${window.description}")
            }
        }

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