package org.operatorfoundation.audiocoder

import org.operatorfoundation.audiocoder.WSPRBandplan.getDefaultFrequency
import org.operatorfoundation.audiocoder.WSPRConstants.SAMPLE_RATE_HZ
import org.operatorfoundation.audiocoder.WSPRConstants.SYMBOLS_PER_MESSAGE

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

        // Buffer Timing Constants
        private const val MINIMUM_DECODE_SECONDS = 120f // Minimum for decode attempt
        private const val RECOMMENDED_BUFFER_SECONDS = 180f // Recommended buffer for reliable decode (3 minutes for overlap)
        private const val BUFFER_OVERLAP_SECONDS = RECOMMENDED_BUFFER_SECONDS - WSPR_TRANSMISSION_DURATION_SECONDS // ~60 seconds overlap

        // Buffer Size Calculations
        private const val MAXIMUM_BUFFER_SAMPLES = (SAMPLE_RATE_HZ * RECOMMENDED_BUFFER_SECONDS).toInt()
        private const val MINIMUM_DECODE_SAMPLES = (SAMPLE_RATE_HZ * MINIMUM_DECODE_SECONDS).toInt()
    }

    private val audioBuffer = mutableListOf<Short>()

    /**
     * Adds audio samples to the WSPR processing buffer.
     */
    fun addSamples(samples: ShortArray)
    {
        audioBuffer.addAll(samples.toList())

        // Maintain buffer size within limits
        if (audioBuffer.size > MAXIMUM_BUFFER_SAMPLES)
        {
            val samplesToRemove = audioBuffer.size - MAXIMUM_BUFFER_SAMPLES

            // TODO: Consider saving these to a different buffer
            repeat(samplesToRemove)
            {
                audioBuffer.removeAt(0)
            }
        }
    }

    /**
     * Gets the current buffer duration in seconds.
     */
    fun getBufferDurationSeconds(): Float
    {
        return audioBuffer.size.toFloat() / SAMPLE_RATE_HZ
    }

    /**
     * Checks if buffer has enough data for a WSPR decode
     */
    fun isReadyForDecode(): Boolean
    {
        return audioBuffer.size >= MINIMUM_DECODE_SAMPLES
    }

    /**
     * Decodes WSPR from buffered audio data.
     */
    fun decodeBufferedWSPR(
        dialFrequencyMHz: Double = getDefaultFrequency(),
        useLowerSideband: Boolean = false
    ): Array<WSPRMessage>?
    {
        if (!isReadyForDecode()) { return null }

        val audioBytes = convertShortsToBytes(audioBuffer.toShortArray())
        return CJarInterface.WSPRDecodeFromPcm(audioBytes, dialFrequencyMHz, useLowerSideband)
    }

    /**
     * Clears the audio buffer
     */
    fun clearBuffer()
    {
        audioBuffer.clear()
    }

    /**
     * Gets recommended buffer duration for optimal WSPR decoding.
     */
    fun getRecommendedBufferSeconds(): Float = RECOMMENDED_BUFFER_SECONDS

    /**
     * Gets minimum buffer duration for WSPR decode attempts.
     */
    fun getMinimumBufferSeconds(): Float = MINIMUM_DECODE_SECONDS

    /**
     * Gets the actual WSPR transmission duration
     */
    fun getWSPRTransmissionSeconds(): Float = WSPR_TRANSMISSION_DURATION_SECONDS

    /**
     * Gets the buffer overlap duration (extra buffering beyond transmission time)
     */
    fun getBufferOverlapSeconds(): Float = BUFFER_OVERLAP_SECONDS

    /**
     * Converts 16-bit samples to byte array for AudioCoder processing
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