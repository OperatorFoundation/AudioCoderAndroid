package org.operatorfoundation.audiocoder.models

import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_BIT_DEPTH
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_CHANNELS
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE

/**
 * Status and diagnostic information for a WSPR audio source.
 * Provides insight into source health, performance, and configuration.
 */
data class WSPRAudioSourceStatus(
    /** Whether the audio source is currently operational */
    val isOperational: Boolean,

    /** Current audio sample rate in Hz (should be 12000 for WSPR) */
    val currentSampleRateHz: Int,

    /** Number of audio channels (should be 1 for WSPR) */
    val channelCount: Int,

    /** Audio bit depth (should be 16 for WSPR) */
    val bitDepth: Int,

    /** Human-readable description of current source state */
    val statusDescription: String,

    /** Optional error message if source is not operational */
    val errorMessage: String? = null,

    /** Timestamp when status was last updated */
    val lastUpdated: Long = System.currentTimeMillis()
)
{
    /** True if audio format meets WSPR requirements */
    val isWSPRCompatible: Boolean
        get() = currentSampleRateHz == WSPR_REQUIRED_SAMPLE_RATE &&
                channelCount == WSPR_REQUIRED_CHANNELS &&
                bitDepth == WSPR_REQUIRED_BIT_DEPTH

    companion object
    {

        /**
         * Creates a status indicating the source is not operational.
         *
         * @param errorDescription Reason why the source is not working
         * @return Status object indicating failure state
         */
        fun createNonOperationalStatus(errorDescription: String): WSPRAudioSourceStatus
        {
            return WSPRAudioSourceStatus(
                isOperational = false,
                currentSampleRateHz = 0,
                channelCount = 0,
                bitDepth = 0,
                statusDescription = "Not operational",
                errorMessage = errorDescription
            )
        }

        /**
         * Creates a status indicating the source is working correctly.
         *
         * @param description Optional description of current operation
         * @return Status object indicating successful operation
         */
        fun createOperationalStatus(description: String = "Operating normally"): WSPRAudioSourceStatus {
            return WSPRAudioSourceStatus(
                isOperational = true,
                currentSampleRateHz = WSPR_REQUIRED_SAMPLE_RATE,
                channelCount = WSPR_REQUIRED_CHANNELS,
                bitDepth = WSPR_REQUIRED_BIT_DEPTH,
                statusDescription = description
            )
        }
    }
}
